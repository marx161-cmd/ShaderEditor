package com.termux.shadereditor.beat;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URLDecoder;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.termux.shadereditor.service.NotificationService;

/**
 * The phone's beat clock (scope.md 2026-10-10). One thread, shared by every
 * shader that uses a beat uniform:
 * <ul>
 *   <li>feeds BeatTracker every 60 Hz frame from JDSP's bands.bin v2 ring
 *   (never drops or doubles a frame);</li>
 *   <li>reads the active media session; when the song is in the desktop's
 *   beat-map cache (~/.shader/beatmaps.json, from beatmaps-sync-pixel) the
 *   stored grid steers the tracker through its PLL -- a port of
 *   beat_session.py (gates, duration rule, map_lookup);</li>
 *   <li>publishes a snapshot the renderer extrapolates between frames.</li>
 * </ul>
 */
public final class BeatClock {
	private static final String TAG = "BeatClock";
	private static final String HOME = "/data/data/com.termux/files/home";
	private static final File BANDS = new File(
			"/data/data/com.termux.jdsp/files/bands.bin");
	private static final File MAPS = new File(HOME, ".shader/beatmaps.json");
	private static final File OFFSET = new File(HOME, ".shader/beat_offset_ms.txt");
	private static final File LOG = new File(HOME, ".shader/beat_clock.log");

	private static final int BANDS_N = 108;
	private static final int HDR_OFF = BANDS_N * 4;
	private static final int SEQ_OFF = HDR_OFF + 8;
	private static final int RING_LEN_OFF = HDR_OFF + 12;
	private static final int HZ_OFF = HDR_OFF + 20;
	private static final int RING_OFF = HDR_OFF + 32;
	private static final int MAGIC = 0x3242444A;
	private static final double DB_FLOOR = -90.0;

	// beat_session.py gates
	private static final double FULL_COVERAGE = 0.90;
	private static final double CONF_MIN = 0.10;
	private static final double DURATION_TOL_S = 2.0;

	public static final int SOURCE_NONE = 0;
	public static final int SOURCE_LIVE = 1;
	public static final int SOURCE_MAP = 2;

	/** Immutable snapshot for the render thread. */
	public static final class State {
		public final double count;
		public final double ratePerSec;
		public final double bpm;
		public final double conf;
		public final int source;
		public final long nanos;

		State(double count, double ratePerSec, double bpm, double conf,
				int source, long nanos) {
			this.count = count;
			this.ratePerSec = ratePerSec;
			this.bpm = bpm;
			this.conf = conf;
			this.source = source;
			this.nanos = nanos;
		}

		/** Beat count extrapolated to {@code now}, at most 0.1 s ahead. */
		public double countAt(long now) {
			double dt = Math.min(Math.max(now - nanos, 0L) / 1e9, 0.1);
			return BeatTracker.mod(count + dt * ratePerSec, BeatTracker.BEAT_WRAP);
		}
	}

	private static BeatClock instance;
	private static int users;

	@NonNull
	private final Context context;
	private volatile State state = new State(0, 2, 120, 0, SOURCE_NONE,
			System.nanoTime());
	private volatile boolean running;
	// The live clock thread; an older one (stop + quick re-acquire) sees it
	// is no longer current and exits on its next loop.
	private volatile Thread thread;

	private BeatClock(@NonNull Context context) {
		this.context = context.getApplicationContext();
	}

	public static synchronized BeatClock acquire(@NonNull Context context) {
		if (instance == null) {
			instance = new BeatClock(context);
		}
		if (users++ == 0) {
			instance.start();
		}
		return instance;
	}

	public static synchronized void release() {
		if (instance != null && users > 0 && --users == 0) {
			instance.stop();
		}
	}

	@NonNull
	public State state() {
		return state;
	}

	private void start() {
		running = true;
		thread = new Thread(this::run, "BeatClock");
		thread.setDaemon(true);
		thread.start();
	}

	private void stop() {
		running = false;
		if (thread != null) {
			thread.interrupt();
			thread = null;
		}
	}

	// --- thread ---

	private static final class Track {
		String artist;
		String title;
		double durationS;
		double bpm;
		double coverage;
		double confidence = Double.NaN;
		boolean exactKey; // a file: key matched (no duration check, like yt:/file: on desktop)
		Set<String> names = new HashSet<>();
		double[] beats;
		double medianIv;
	}

	private List<Track> maps;
	private long mapsModified;

	private final BeatTracker tracker = new BeatTracker();
	private MappedByteBuffer bands;
	private long lastSeq = -1;
	private final double[] row = new double[BANDS_N];
	private final double[] silentRow = new double[BANDS_N];

	private String sessionKey;
	private Track matched;
	private String matchedLabel = "";
	private PlaybackState playback;
	private long lastSessionPoll;
	private double offsetS;
	private PrintWriter log;
	private long lastLog;
	private long lastFrameNanos;

	private void run() {
		openLog();
		long sleepMs = 8;
		while (running && Thread.currentThread() == thread) {
			try {
				long now = System.nanoTime();
				if (now - lastSessionPoll > 500_000_000L) {
					lastSessionPoll = now;
					pollSession();
				}
				int fed = feedFrames(now);
				if (fed == 0 && now - lastFrameNanos > 100_000_000L) {
					// Nothing playing through JDSP: flywheel at the last tempo
					// (a silent frame = zero onset), like the desktop does
					// through a stalled stream.
					steer(now);
					tracker.update(silentRow, now / 1e9, now / 1e9);
					lastFrameNanos = now - 83_000_000L; // ~one frame per loop at 60 Hz
					publish(now);
				}
				writeLog(now);
				Thread.sleep(sleepMs);
			} catch (InterruptedException e) {
				break;
			} catch (Exception e) {
				Log.w(TAG, "beat clock error", e);
				try {
					Thread.sleep(500);
				} catch (InterruptedException ie) {
					break;
				}
			}
		}
		if (log != null) {
			log.close();
			log = null;
		}
	}

	private int feedFrames(long now) {
		if (bands == null && !mapBands()) {
			return 0;
		}
		if (bands.getInt(HDR_OFF) != MAGIC) {
			bands = null;
			return 0;
		}
		long seq = bands.getInt(SEQ_OFF) & 0xffffffffL;
		int ringLen = bands.getInt(RING_LEN_OFF);
		float hz = bands.getFloat(HZ_OFF);
		if (ringLen <= 0 || hz <= 0f) {
			return 0;
		}
		if (lastSeq < 0 || seq < lastSeq || seq - lastSeq > ringLen - 2) {
			lastSeq = Math.max(0, seq - 1); // start, engine restart, or we fell behind
		}
		int fed = 0;
		for (long k = lastSeq; k < seq; k++) {
			int off = RING_OFF + (int) (k % ringLen) * BANDS_N * 4;
			for (int b = 0; b < BANDS_N; b++) {
				double lin = Math.max(bands.getFloat(off + b * 4), 1e-9f);
				double db = 20.0 * Math.log10(lin);
				row[b] = BeatTracker.clamp((db - DB_FLOOR) / -DB_FLOOR, 0.0, 1.0);
			}
			steer(now);
			tracker.update(row, k / (double) hz, now / 1e9);
			fed++;
		}
		lastSeq = seq;
		if (fed > 0) {
			lastFrameNanos = now;
			publish(now);
		}
		return fed;
	}

	private boolean mapBands() {
		if (!BANDS.exists() || BANDS.length() < RING_OFF) {
			return false;
		}
		try (FileChannel ch = FileChannel.open(BANDS.toPath(), StandardOpenOption.READ)) {
			bands = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
			bands.order(ByteOrder.LITTLE_ENDIAN);
			lastSeq = -1;
			return true;
		} catch (IOException e) {
			bands = null;
			return false;
		}
	}

	private void publish(long now) {
		int source = matched != null ? SOURCE_MAP
				: tracker.conf() > BeatTracker.CONF_GATE ? SOURCE_LIVE
				: SOURCE_NONE;
		state = new State(tracker.count(), 1.0 / Math.max(tracker.tbeat(), 1e-3),
				tracker.bpm(), tracker.conf(), source, now);
	}

	/** beat_session.py _update: steer from the map while one is matched. */
	private void steer(long now) {
		if (matched == null || playback == null ||
				playback.getState() != PlaybackState.STATE_PLAYING) {
			tracker.clearReference();
			return;
		}
		double t = songPosition() - offsetS;
		double[] got = mapLookup(matched.beats, t, matched.medianIv);
		if (got == null) {
			tracker.clearReference();
			return;
		}
		tracker.setReference(got[1], got[2]);
	}

	private double songPosition() {
		long since = SystemClock.elapsedRealtime() - playback.getLastPositionUpdateTime();
		return (playback.getPosition() + since * playback.getPlaybackSpeed()) / 1000.0;
	}

	/** beat_session.py map_lookup -> {count, phase, tbeat}. */
	@Nullable
	static double[] mapLookup(double[] bt, double t, double medianIv) {
		if (bt == null || bt.length < 2 || medianIv <= 0.0) {
			return null;
		}
		int last = bt.length - 1;
		double count;
		double tbeat;
		if (t <= bt[0]) {
			tbeat = bt[1] - bt[0];
			count = -(bt[0] - t) / Math.max(tbeat, 1e-6);
		} else if (t >= bt[last]) {
			tbeat = bt[last] - bt[last - 1];
			count = last + (t - bt[last]) / Math.max(tbeat, 1e-6);
		} else {
			int lo = 0;
			int hi = last;
			while (hi - lo > 1) {
				int mid = (lo + hi) >>> 1;
				if (bt[mid] <= t) {
					lo = mid;
				} else {
					hi = mid;
				}
			}
			tbeat = bt[lo + 1] - bt[lo];
			count = lo + (t - bt[lo]) / Math.max(tbeat, 1e-6);
		}
		return new double[]{count, BeatTracker.mod(count, 1.0), tbeat};
	}

	// --- media session + cache ---

	private void pollSession() {
		loadMapsIfChanged();
		offsetS = readOffsetS();
		MediaController playing = null;
		try {
			MediaSessionManager msm = context.getSystemService(MediaSessionManager.class);
			List<MediaController> sessions = msm.getActiveSessions(
					new ComponentName(context, NotificationService.class));
			for (MediaController c : sessions) {
				PlaybackState s = c.getPlaybackState();
				if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
					playing = c;
					break;
				}
			}
		} catch (SecurityException e) {
			// Notification access not granted: live tracker only.
		}
		if (playing == null) {
			playback = null;
			if (sessionKey != null) {
				sessionKey = null;
				matched = null;
				matchedLabel = "";
			}
			return;
		}
		playback = playing.getPlaybackState();
		MediaMetadata md = playing.getMetadata();
		String artist = md != null ? nz(md.getString(MediaMetadata.METADATA_KEY_ARTIST)) : "";
		String title = md != null ? nz(md.getString(MediaMetadata.METADATA_KEY_TITLE)) : "";
		long durMs = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
		String key = playing.getPackageName() + "|" + artist + "|" + title + "|" + durMs;
		if (key.equals(sessionKey)) {
			return;
		}
		sessionKey = key;
		matched = match(artist, title, durMs / 1000.0);
		matchedLabel = playing.getPackageName() + " '" + artist + " - " + title + "' " +
				(matched != null
						? String.format(Locale.US, "-> map %.1f BPM (%s - %s)",
								matched.bpm, matched.artist, matched.title)
						: "-> no map, live");
		Log.i(TAG, matchedLabel);
	}

	/** Phone-side matching (scope.md: normalised artist/title, desktop gates). */
	@Nullable
	private Track match(String artist, String title, double durationS) {
		if (maps == null || title.isEmpty()) {
			return null;
		}
		Set<String> want = new HashSet<>();
		want.add(norm(title));
		if (!artist.isEmpty()) {
			want.add(norm(artist + title));
		}
		for (Track t : maps) {
			boolean hit = false;
			for (String n : want) {
				if (t.names.contains(n)) {
					hit = true;
					break;
				}
			}
			if (!hit) {
				continue;
			}
			if (t.coverage < FULL_COVERAGE) {
				continue;
			}
			if (!Double.isNaN(t.confidence) && t.confidence < CONF_MIN) {
				continue;
			}
			// A fuzzy (meta) match must agree on duration; beat_session.py
			// compares the grid's span with the track duration.
			if (!t.exactKey && durationS > 0) {
				double span = t.beats[t.beats.length - 1];
				if (Math.abs(durationS - span) > Math.max(DURATION_TOL_S, 0.1 * durationS)) {
					continue;
				}
			}
			return t;
		}
		return null;
	}

	private void loadMapsIfChanged() {
		if (!MAPS.exists() || MAPS.lastModified() == mapsModified) {
			return;
		}
		mapsModified = MAPS.lastModified();
		List<Track> out = new ArrayList<>();
		try {
			StringBuilder sb = new StringBuilder();
			try (BufferedReader r = new BufferedReader(new FileReader(MAPS))) {
				char[] buf = new char[65536];
				int n;
				while ((n = r.read(buf)) > 0) {
					sb.append(buf, 0, n);
				}
			}
			JSONArray tracks = new JSONObject(sb.toString()).getJSONArray("tracks");
			for (int i = 0; i < tracks.length(); i++) {
				JSONObject o = tracks.getJSONObject(i);
				JSONArray b = o.getJSONArray("beats");
				if (b.length() < 2) {
					continue;
				}
				Track t = new Track();
				t.artist = o.optString("artist");
				t.title = o.optString("title");
				t.durationS = o.optDouble("duration_s", 0);
				t.bpm = o.optDouble("bpm", 0);
				t.coverage = o.optDouble("coverage", 0);
				t.confidence = o.isNull("confidence") ? Double.NaN : o.optDouble("confidence");
				t.beats = new double[b.length()];
				for (int k = 0; k < b.length(); k++) {
					t.beats[k] = b.getDouble(k);
				}
				double[] iv = new double[t.beats.length - 1];
				for (int k = 0; k < iv.length; k++) {
					iv[k] = t.beats[k + 1] - t.beats[k];
				}
				java.util.Arrays.sort(iv);
				t.medianIv = iv[iv.length / 2];
				t.names.add(norm(t.title));
				t.names.add(norm(t.artist + t.title));
				JSONArray keys = o.optJSONArray("keys");
				if (keys != null) {
					for (int k = 0; k < keys.length(); k++) {
						String key = keys.getString(k);
						if (key.startsWith("file:")) {
							String base = fileBase(key);
							t.names.add(norm(base));
							int dash = base.indexOf(" - ");
							if (dash > 0) {
								t.names.add(norm(base.substring(dash + 3)));
							}
							t.exactKey = true;
						}
					}
				}
				out.add(t);
			}
			maps = out;
			Log.i(TAG, "loaded " + out.size() + " beat maps");
		} catch (Exception e) {
			Log.w(TAG, "cannot load " + MAPS, e);
		}
	}

	@NonNull
	private static String fileBase(String key) {
		String path = key.substring(5);
		try {
			path = URLDecoder.decode(path.replace("+", "%2B"), "UTF-8");
		} catch (Exception ignored) {
			// keep encoded
		}
		String base = path.substring(path.lastIndexOf('/') + 1);
		int dot = base.lastIndexOf('.');
		return dot > 0 ? base.substring(0, dot) : base;
	}

	@NonNull
	static String norm(String s) {
		return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
	}

	@NonNull
	private static String nz(@Nullable String s) {
		return s == null ? "" : s;
	}

	private static double readOffsetS() {
		if (!OFFSET.exists()) {
			return 0.0;
		}
		try (BufferedReader r = new BufferedReader(new FileReader(OFFSET))) {
			return Double.parseDouble(r.readLine().trim()) / 1000.0;
		} catch (Exception e) {
			return 0.0;
		}
	}

	// --- debug log: 4 lines/s, for checking the clock numerically ---

	private void openLog() {
		try {
			// Append (a pause/resume restarts this thread), but start over
			// once it passes 1 MB.
			boolean append = LOG.exists() && LOG.length() < 1_000_000L;
			log = new PrintWriter(new OutputStreamWriter(
					new FileOutputStream(LOG, append), StandardCharsets.UTF_8), true);
			if (!append) {
				log.println("# t_s\tsource\tbpm\tconf\tcount\tsong_pos_s\tmatch");
			}
			log.println("# clock thread started");
		} catch (IOException e) {
			log = null;
		}
	}

	private void writeLog(long now) {
		if (log == null || now - lastLog < 250_000_000L) {
			return;
		}
		lastLog = now;
		State s = state;
		double pos = playback != null ? songPosition() : -1;
		log.printf(Locale.US, "%.3f\t%d\t%.2f\t%.3f\t%.3f\t%.3f\t%s%n",
				now / 1e9, s.source, s.bpm, s.conf, s.count, pos, matchedLabel);
	}
}
