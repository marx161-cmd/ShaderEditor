package com.termux.shadereditor.beat;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

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

import com.termux.shadereditor.opengl.JdspBandsFeed;
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
	// Desktop go signal (scope.md 2026-10-10/11): comrade's ~/bin/shader-go-signal writes
	// it straight into Termux home over its persistent SSH shell (the one it measures the
	// clock offset on). "go <heard_start_phone_ms> <duration_s> <key>" or "stop <ms>";
	// the start already includes the speaker delay and the clock offset.
	private static final File GO = new File(HOME, ".shader/shader_go.txt");
	// Cybersyn's mirror of comrade's player has no usable position: never match it --
	// the go signal carries the song instead.
	private static final String MIRROR_PKG = "com.termux.cybersyn";

	private static final int BANDS_N = 108;
	private static final int HDR_OFF = BANDS_N * 4;
	private static final int SEQ_OFF = HDR_OFF + 8;
	private static final int VER_OFF = HDR_OFF + 4;
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
	public static final int SOURCE_TAP = 3;
	public static final int SOURCE_DESKTOP = 4;

	// Tap tempo (scope.md 2026-10-10, gesture 2026-10-19): a DELIBERATE gesture.
	// ON = one finger holds, a second finger taps TAP_MIN+ times (intervals within
	// TAP_TOL of their median -> least-squares tempo + phase); OFF = hold + one tap.
	// A tap = the second finger down and up again within TAP_UP_MAX_NS; a third
	// finger voids the gesture; it is judged when the holding finger lifts. Runs
	// until switched off or the wallpaper is hidden (screen off / app switch).
	private static final int TAP_MIN = 4;
	private static final double TAP_TOL = 0.15;
	private static final long TAP_UP_MAX_NS = 350_000_000L;
	private static final int EV_OFF = -1;

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

	/** The u_ms_* music-state values (MusicState.java). */
	@NonNull
	public MusicState.Snapshot music() {
		return music.snapshot();
	}

	/** A touch event (masked action, pointer count incl. the acting one, the acting
	 * pointer's id, System.nanoTime base) for the tap-tempo gesture; any thread. */
	public static void touch(int action, int pointers, int pointerId, long nanos) {
		BeatClock c = instance;
		if (c != null && c.running) {
			c.tapQueue.add(new long[]{action, pointers, pointerId, nanos});
		}
	}

	/** The wallpaper went hidden (screen off / app switch): tap tempo off; any thread. */
	public static void tapOff() {
		BeatClock c = instance;
		if (c != null && c.running) {
			c.tapQueue.add(new long[]{EV_OFF, 0, 0, 0});
		}
	}

	/** Mic level (0..1 amplitude) while a shader has the mic open; any thread. */
	public void feedMic(float amplitude, long nanos) {
		micAmp = amplitude;
		micNanos = nanos;
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
		Set<String> keys = new HashSet<>(); // exact desktop keys (file: ones URL-decoded)
		double[] beats;
		double medianIv;
		@Nullable
		MusicState.Song ms; // the analysis (beatmaps.json version 2), null for a plain map
	}

	private List<Track> maps;
	private long mapsModified;

	private final BeatTracker tracker = new BeatTracker();
	private MappedByteBuffer bands;
	private int[] delay = new int[BANDS_N];
	private int maxDelay;
	private int alignedVersion = -1;
	private float alignedHz;
	private long lastSeq = -1;
	private final double[] row = new double[BANDS_N];
	private final double[] linRow = new double[BANDS_N];
	private final MusicState music = new MusicState();
	private final double[] silentRow = new double[BANDS_N];

	private long goModified;
	private Track goTrack;
	private long goStartMs;
	private double goDurS;
	private String goLabel = "";
	private String sessionKey;
	private Track matched;
	private String matchedLabel = "";
	private PlaybackState playback;
	private long lastSessionPoll;
	private double offsetS;
	private PrintWriter log;
	private long lastLog;
	private long lastFrameNanos;

	private final java.util.concurrent.ConcurrentLinkedQueue<long[]> tapQueue =
			new java.util.concurrent.ConcurrentLinkedQueue<>();
	private final java.util.ArrayList<Long> taps = new java.util.ArrayList<>();
	private boolean tapActive;
	private long tapT0;
	private double tapPeriodNs;
	private boolean gestureLive;
	private int anchorId;
	private long tapperDownAt;
	private float frameRms;
	private volatile float micAmp;
	private volatile long micNanos;
	private String tapLabel = "";

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
				processTaps(now);
				int fed = feedFrames(now);
				if (fed == 0 && now - lastFrameNanos > 100_000_000L) {
					// Nothing playing through JDSP: flywheel at the last tempo
					// (a silent frame = zero onset), like the desktop does
					// through a stalled stream.
					steer(now);
					tracker.update(silentRow, now / 1e9, now / 1e9);
					music.update(null, 0.0, now / 1e9, now, tracker.count(), tracker.conf(), heardPosition());
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
		// v110: the tracker sees the same per-band group-delay-aligned rows as
		// the shaders' band textures (JdspBandsFeed / desktop comp_rows), so a
		// kick the wallpaper detects and the grid the tracker produces agree.
		// Band b of frame k is read from frame k - delay_b, which must still be
		// in the ring: resync earlier when we fall behind by that much.
		int version = bands.getInt(VER_OFF);
		if (alignedVersion != version || alignedHz != hz) {
			alignedVersion = version;
			alignedHz = hz;
			delay = JdspBandsFeed.delayFrames(version, hz, ringLen - 4);
			maxDelay = 0;
			for (int d : delay) {
				maxDelay = Math.max(maxDelay, d);
			}
		}
		if (lastSeq < 0 || seq < lastSeq || seq - lastSeq > ringLen - 2 - maxDelay) {
			lastSeq = Math.max(0, seq - 1); // start, engine restart, or we fell behind
		}
		int fed = 0;
		frameRms = bands.getFloat(HDR_OFF + 28);
		double pos = heardPosition();
		for (long k = lastSeq; k < seq; k++) {
			for (int b = 0; b < BANDS_N; b++) {
				long kb = k - delay[b];
				double lin = kb < 0 ? 1e-9
						: Math.max(bands.getFloat(RING_OFF + (int) (kb % ringLen) * BANDS_N * 4 + b * 4), 1e-9f);
				linRow[b] = lin;
				double db = 20.0 * Math.log10(lin);
				row[b] = BeatTracker.clamp((db - DB_FLOOR) / -DB_FLOOR, 0.0, 1.0);
			}
			steer(now);
			tracker.update(row, k / (double) hz, now / 1e9);
			music.update(linRow, frameRms, k / (double) hz, now, tracker.count(), tracker.conf(), pos);
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
		int source = localPlaying() ? SOURCE_MAP
				: desktopActive() ? SOURCE_DESKTOP
				: tapActive ? SOURCE_TAP
				: tracker.conf() > BeatTracker.CONF_GATE ? SOURCE_LIVE
				: SOURCE_NONE;
		state = new State(tracker.count(), 1.0 / Math.max(tracker.tbeat(), 1e-3),
				tracker.bpm(), tracker.conf(), source, now);
	}

	/** beat_session.py _update: steer from the map while one is matched, else from taps. */
	private void steer(long now) {
		Track song = localPlaying() ? matched : desktopActive() ? goTrack : null;
		if (song == null) {
			if (tapActive) {
				double beats = (now - tapT0) / tapPeriodNs;
				tracker.setReference(BeatTracker.mod(beats, 1.0), tapPeriodNs / 1e9);
			} else {
				tracker.clearReference();
			}
			return;
		}
		double t = heardPosition();
		double[] got = mapLookup(song.beats, t, song.medianIv);
		if (got == null) {
			tracker.clearReference();
			return;
		}
		tracker.setReference(got[1], got[2]);
	}

	/** A cached song plays in a local media session (beats the desktop). */
	private boolean localPlaying() {
		return matched != null && playback != null &&
				playback.getState() == PlaybackState.STATE_PLAYING;
	}

	/** The desktop's song (go signal) is playing as heard now. */
	private boolean desktopActive() {
		if (goTrack == null) {
			return false;
		}
		long now = System.currentTimeMillis();
		return now >= goStartMs - 5_000L && now <= goStartMs + (long) (goDurS * 1000.0) + 2_000L;
	}

	/** Position in the active cached song as heard (offset applied), NaN when none. */
	private double heardPosition() {
		if (localPlaying()) {
			return songPosition() - offsetS;
		}
		if (desktopActive()) {
			return (System.currentTimeMillis() - goStartMs) / 1000.0 - offsetS;
		}
		return Double.NaN;
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

	// --- tap tempo ---

	private void processTaps(long now) {
		long[] ev;
		while ((ev = tapQueue.poll()) != null) {
			int action = (int) ev[0];
			int pointers = (int) ev[1];
			int id = (int) ev[2];
			long t = ev[3];
			switch (action) {
				case EV_OFF:
					gestureLive = false;
					clearTap("wallpaper hidden");
					break;
				case MotionEvent.ACTION_DOWN:              // the holding finger
					gestureLive = true;
					anchorId = id;
					tapperDownAt = 0;
					taps.clear();
					break;
				case MotionEvent.ACTION_POINTER_DOWN:
					if (!gestureLive) {
						break;
					}
					if (pointers == 2) {
						tapperDownAt = t;
					} else {
						gestureLive = false;
						Log.i(TAG, "tap gesture voided: " + pointers + " fingers");
					}
					break;
				case MotionEvent.ACTION_POINTER_UP:
					if (!gestureLive) {
						break;
					}
					if (id == anchorId) {                 // the holding finger lifted first
						endGesture();
						break;
					}
					if (tapperDownAt != 0 && t - tapperDownAt <= TAP_UP_MAX_NS) {
						taps.add(tapperDownAt);
					}
					tapperDownAt = 0;
					break;
				case MotionEvent.ACTION_UP:
					if (gestureLive) {
						endGesture();
					}
					break;
				case MotionEvent.ACTION_CANCEL:
					gestureLive = false;
					break;
				default:
					break;
			}
		}
	}

	/** The holding finger lifted: 1 tap = off, TAP_MIN+ even taps = on (re-tap while on). */
	private void endGesture() {
		gestureLive = false;
		int n = taps.size();
		if (n == 1) {
			clearTap("off gesture");
			return;
		}
		if (n < TAP_MIN) {
			if (n > 0) {
				Log.i(TAG, "tap gesture ignored: " + n + " taps");
			}
			return;
		}
		double[] iv = new double[n - 1];
		for (int i = 0; i < n - 1; i++) {
			iv[i] = taps.get(i + 1) - taps.get(i);
		}
		double[] sorted = iv.clone();
		java.util.Arrays.sort(sorted);
		double med = sorted[sorted.length / 2];
		boolean ok = med > 0;
		for (double v : iv) {
			ok &= Math.abs(v - med) <= TAP_TOL * med;
		}
		if (!ok) {
			Log.i(TAG, "tap gesture ignored: uneven taps");
			return;
		}
		// Least-squares grid through the taps: time = t0 + i * period.
		double sx = 0, sy = 0, sxx = 0, sxy = 0;
		long base = taps.get(0);
		for (int i = 0; i < n; i++) {
			double y = taps.get(i) - base;
			sx += i;
			sy += y;
			sxx += (double) i * i;
			sxy += i * y;
		}
		double period = (n * sxy - sx * sy) / (n * sxx - sx * sx);
		double bpm = 60e9 / period;
		if (bpm < BeatTracker.BPM_MIN || bpm > BeatTracker.BPM_MAX) {
			Log.i(TAG, String.format(Locale.US, "tap gesture ignored: %.1f BPM out of range", bpm));
			return;
		}
		tapPeriodNs = period;
		tapT0 = base + (long) ((sy - period * sx) / n);
		tapActive = true;
		tapLabel = String.format(Locale.US, "tap %.1f BPM (%d taps)", bpm, n);
		Log.i(TAG, tapLabel);
	}

	private void clearTap(String why) {
		if (tapActive) {
			Log.i(TAG, "tap tempo cleared: " + why);
			tapLabel = "tap cleared: " + why;
		}
		tapActive = false;
		taps.clear();
	}

	// --- media session + cache ---

	private void pollSession() {
		loadMapsIfChanged();
		offsetS = readOffsetS();
		readGo();
		try {
			pollMediaSession();
		} finally {
			Track song = localPlaying() ? matched : desktopActive() ? goTrack : null;
			music.setSong(song != null ? song.ms : null);
		}
	}

	private void readGo() {
		if (!GO.exists() || GO.lastModified() == goModified) {
			return;
		}
		goModified = GO.lastModified();
		String line;
		try (BufferedReader r = new BufferedReader(new FileReader(GO))) {
			line = r.readLine();
		} catch (IOException e) {
			return;
		}
		if (line == null) {
			return;
		}
		String[] f = line.trim().split(" ", 4);
		if (f[0].equals("go") && f.length == 4) {
			try {
				goStartMs = Long.parseLong(f[1]);
				goDurS = Double.parseDouble(f[2]);
			} catch (NumberFormatException e) {
				return;
			}
			goTrack = findByKey(f[3]);
			if (goTrack != null && goDurS <= 0) {
				goDurS = goTrack.beats[goTrack.beats.length - 1];
			}
			goLabel = "desktop '" + f[3] + "' " + (goTrack != null
					? String.format(Locale.US, "-> analysis %.1f BPM", goTrack.bpm)
					: "-> not analysed");
		} else if (f[0].equals("stop")) {
			goTrack = null;
			goLabel = "desktop stopped";
		}
		Log.i(TAG, goLabel);
	}

	@Nullable
	private Track findByKey(String key) {
		if (maps == null) {
			return null;
		}
		String k = normKey(key);
		for (Track t : maps) {
			if (t.keys.contains(k)) {
				return t;
			}
		}
		return null;
	}

	@NonNull
	private static String normKey(String key) {
		if (!key.startsWith("file:")) {
			return key;
		}
		try {
			return "file:" + URLDecoder.decode(key.substring(5).replace("+", "%2B"), "UTF-8");
		} catch (Exception e) {
			return key;
		}
	}

	private void pollMediaSession() {
		MediaController playing = null;
		try {
			MediaSessionManager msm = context.getSystemService(MediaSessionManager.class);
			List<MediaController> sessions = msm.getActiveSessions(
					new ComponentName(context, NotificationService.class));
			for (MediaController c : sessions) {
				if (MIRROR_PKG.equals(c.getPackageName())) {
					continue;
				}
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
						: "-> no map, live") +
				(matched != null && matched.ms != null ? " +analysis" : "");
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
			// One exact-size read (v113): the growing StringBuilder needed a 128 MB char[] for the
			// 35 MB file and hit an OutOfMemoryError + GC retry on every app start.
			byte[] raw = new byte[(int) MAPS.length()];
			try (java.io.DataInputStream in = new java.io.DataInputStream(
					new java.io.FileInputStream(MAPS))) {
				in.readFully(raw);
			}
			String text = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
			raw = null;
			JSONArray tracks = new JSONObject(text).getJSONArray("tracks");
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
				t.ms = MusicState.Song.parse(o, t.beats);
				t.names.add(norm(t.title));
				t.names.add(norm(t.artist + t.title));
				JSONArray keys = o.optJSONArray("keys");
				if (keys != null) {
					for (int k = 0; k < keys.length(); k++) {
						String key = keys.getString(k);
						t.keys.add(normKey(key));
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
		double pos = heardPosition();
		MusicState.Snapshot m = music.snapshot();
		log.printf(Locale.US, "%.3f\t%d\t%.2f\t%.3f\t%.3f\t%.3f\t%s\t%s\tms src=%.0f chg=%.0f cur=%.2f,%.2f,%.2f,%.2f sec=%.2f,%.2f,%.2f,%.2f next_in=%.1f drop_in=%.1f%n",
				now / 1e9, s.source, s.bpm, s.conf, s.count, pos,
				desktopActive() && !localPlaying() ? goLabel : matchedLabel, tapLabel,
				m.source, m.change, m.curA[0], m.curA[1], m.curA[2], m.curA[3],
				m.secA[0], m.secA[1], m.secA[2], m.secA[3], m.nextIn, m.dropIn);
	}
}
