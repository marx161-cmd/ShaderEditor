package com.termux.shadereditor.opengl;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

/**
 * Reader for JDSP's bands.bin (the engine's 108-band meter, jdspController.c
 * band_publish). Memory-mapped, polled by sequence number, so every 60 Hz
 * frame the engine publishes is seen exactly once (the old reader re-read the
 * first 432 bytes at 30 Hz and missed half of them).
 *
 * File layout (little-endian; v2 since 2026-10-10, v3 since 2026-10-22):
 *   [0, 432)     latest RAW frame: 108 floats, linear RMS per semitone band
 *   [432, 464)   header: magic "JDB2", version, seq, ringLen, bands, frameHz,
 *                sampleRate, rms
 *   [464, R)     ring of ringLen raw frames; frame k in slot k % ringLen
 *   [R, R+432)   v3: COOKED latest frame, the desktop feed's semantics
 *                (norm_db 0..1 over -60..0 dBFS, 60/70 ms attack/release)
 *   [R+432, +44) v3: 11 features: rms, sub, bass, lowmid, mid, highmid,
 *                presence, brilliance, centroid, flux, onset (0..1)
 * On a v2 file the cooked frame is derived here (norm_db of raw, no
 * envelope) and only the rms feature is available.
 *
 * Stale detection: when the sequence number stops advancing for STALE_NS
 * (paused player, effect detached, JDSP off) every array is zeroed, so a
 * wallpaper never keeps showing the last spectrum frozen in place.
 *
 * Alignment (v109, same as comrade's renderer.py comp_rows()): every band
 * filter rings for its own group delay (283 ms at A0 down to ~2 ms at A8 for
 * the v3 engine's Butterworth bank), so without correction the bass rows lag
 * the treble rows on screen. Each band is therefore read DELAY_S[b] later
 * from a local history, so all 108 line up with each other; the global
 * output-latency offset is the beat clock's business, as on the desktop.
 * The cooked (desktop-semantics) frame is rebuilt here from the aligned raw
 * history with the engine's own norm_db + envelope constants, because the
 * engine only rings the raw frames. Alignment is applied only for a v3
 * engine (whose filter design these delays belong to); a v2 engine's
 * single-biquad bank is read unshifted.
 */
public final class JdspBandsFeed {
	static final int N = 108;
	static final int FEATURES = 11;
	static final int HIST = 32;
	static final String[] FEATURE_NAMES = {"rms", "sub", "bass", "lowmid", "mid",
			"highmid", "presence", "brilliance", "centroid", "flux", "onset"};

	private static final File FILE = new File("/data/data/com.termux.jdsp/files/bands.bin");
	private static final int HDR_OFF = N * 4;
	private static final int VER_OFF = HDR_OFF + 4;
	private static final int SEQ_OFF = HDR_OFF + 8;
	private static final int RING_LEN_OFF = HDR_OFF + 12;
	private static final int HZ_OFF = HDR_OFF + 20;
	private static final int RMS_OFF = HDR_OFF + 28;
	private static final int RING_OFF = HDR_OFF + 32;
	private static final int MAGIC = 0x3242444A;
	private static final long STALE_NS = 150_000_000L;
	private static final long REMAP_INTERVAL_NS = 1_000_000_000L;
	private static final double DB_FLOOR = -60.0;

	// Per-band group delay of the v3 engine's filter bank in seconds, from
	// shader-backdrop band_filters.delays_s() (orders {2,2,2,3,3,4,4,4,4},
	// 48 kHz); row = octave, A0 first. Converted to frames with frameHz.
	public static final float[] DELAY_S = {
			0.2834f, 0.2675f, 0.2524f, 0.2383f, 0.2249f, 0.2123f, 0.2004f, 0.1891f, 0.1785f, 0.1685f, 0.1590f, 0.1501f,
			0.1417f, 0.1337f, 0.1262f, 0.1191f, 0.1124f, 0.1061f, 0.1002f, 0.0946f, 0.0893f, 0.0842f, 0.0795f, 0.0751f,
			0.0708f, 0.0669f, 0.0631f, 0.0596f, 0.0562f, 0.0531f, 0.0501f, 0.0473f, 0.0446f, 0.0421f, 0.0398f, 0.0375f,
			0.0501f, 0.0473f, 0.0446f, 0.0421f, 0.0398f, 0.0375f, 0.0354f, 0.0334f, 0.0316f, 0.0298f, 0.0281f, 0.0265f,
			0.0250f, 0.0236f, 0.0223f, 0.0211f, 0.0199f, 0.0188f, 0.0177f, 0.0167f, 0.0158f, 0.0149f, 0.0141f, 0.0133f,
			0.0164f, 0.0154f, 0.0146f, 0.0138f, 0.0130f, 0.0123f, 0.0116f, 0.0109f, 0.0103f, 0.0097f, 0.0092f, 0.0087f,
			0.0082f, 0.0077f, 0.0073f, 0.0069f, 0.0065f, 0.0061f, 0.0058f, 0.0055f, 0.0052f, 0.0049f, 0.0046f, 0.0043f,
			0.0041f, 0.0039f, 0.0036f, 0.0034f, 0.0032f, 0.0031f, 0.0029f, 0.0027f, 0.0026f, 0.0024f, 0.0023f, 0.0022f,
			0.0020f, 0.0019f, 0.0018f, 0.0017f, 0.0016f, 0.0015f, 0.0014f, 0.0014f, 0.0013f, 0.0012f, 0.0011f, 0.0011f
	};
	private static final double ENV_ATTACK_MS = 60.0;    // engine BAND_ENV_ATTACK_MS
	private static final double ENV_RELEASE_MS = 70.0;   // engine BAND_ENV_RELEASE_MS
	private static final int LOCAL_HIST = 64;            // frames kept here (>= HIST + max delay)

	/** Per-band shift in frames at `hz` for a v3 engine; all zero for older engines. */
	public static int[] delayFrames(int version, float hz, int cap) {
		int[] d = new int[N];
		for (int b = 0; b < N; b++) {
			d[b] = version >= 3 ? Math.min(cap, Math.round(DELAY_S[b] * hz)) : 0;
		}
		return d;
	}
	public static final int MAX_DELAY_FRAMES_60HZ = 17;

	final float[] raw = new float[N];                    // aligned latest raw frame
	final float[] cooked = new float[N];                 // aligned cooked frame
	final float[] hist = new float[N * HIST];            // aligned raw history, row 0 newest
	final float[] features = new float[FEATURES];        // engine features (unaligned, as on the desktop)
	int version;
	float frameHz = 60f;
	boolean live;

	private MappedByteBuffer map;
	private long mapSize;
	private long lastMapTry;
	private long lastSeq = -1;
	private long lastChangeNanos;
	// local rings, newest at (head) wrapping downwards; filled per engine frame
	private final float[] rawRing = new float[N * LOCAL_HIST];
	private final float[] cookedRing = new float[N * LOCAL_HIST];
	private final double[] env = new double[N];
	private int head = -1;
	private long frames;
	private final int[] delayFrames = new int[N];
	private float delayHz;
	private double attackCoef, releaseCoef;

	/** @return true when the arrays changed (a new frame, or everything went stale). */
	boolean poll(long now) {
		if (map == null || (now - lastMapTry > REMAP_INTERVAL_NS && FILE.length() != mapSize)) {
			if (now - lastMapTry < REMAP_INTERVAL_NS && map == null) {
				return goStale(now);
			}
			lastMapTry = now;
			if (!remap()) {
				return goStale(now);
			}
		}
		if (map.getInt(HDR_OFF) != MAGIC) {
			map = null;
			return goStale(now);
		}
		long seq = map.getInt(SEQ_OFF) & 0xffffffffL;
		if (seq == lastSeq) {
			if (live && now - lastChangeNanos > STALE_NS) {
				return goStale(now);
			}
			return false;
		}
		int ringLen = map.getInt(RING_LEN_OFF);
		float hz = map.getFloat(HZ_OFF);
		if (ringLen <= 0 || hz <= 0f) {
			return false;
		}
		version = map.getInt(VER_OFF);
		if (hz != frameHz || delayHz != hz) {
			frameHz = hz;
			delayHz = hz;
			attackCoef = Math.exp(-1.0 / (hz * ENV_ATTACK_MS / 1000.0));
			releaseCoef = Math.exp(-1.0 / (hz * ENV_RELEASE_MS / 1000.0));
			System.arraycopy(delayFrames(version, hz, LOCAL_HIST - HIST), 0, delayFrames, 0, N);
		}
		if (lastSeq < 0 || seq < lastSeq || seq - lastSeq > ringLen - 2) {
			// start, engine restart, or we fell behind the ring: resync on the
			// newest frames only (two slots of margin against the writer, as
			// BeatClock does), history restarts
			lastSeq = Math.max(0, seq - Math.min(ringLen - 2, LOCAL_HIST));
			head = -1;
			frames = 0;
			java.util.Arrays.fill(env, 0.0);
			java.util.Arrays.fill(rawRing, 0f);
			java.util.Arrays.fill(cookedRing, 0f);
		}
		for (long k = lastSeq; k < seq; k++) {
			head = (head + 1) % LOCAL_HIST;
			frames++;
			int src = RING_OFF + (int) (k % ringLen) * N * 4;
			int dst = head * N;
			for (int b = 0; b < N; b++) {
				float v = map.getFloat(src + b * 4);
				rawRing[dst + b] = v;
				double x = normDb(v);
				double coef = x > env[b] ? attackCoef : releaseCoef;
				env[b] = coef * env[b] + (1.0 - coef) * x;
				cookedRing[dst + b] = (float) env[b];
			}
		}
		// aligned outputs: band b, r frames ago = local row (r + delay_b) ago
		for (int b = 0; b < N; b++) {
			int d = delayFrames[b];
			raw[b] = rawRing[rowAgo(d) * N + b];
			cooked[b] = cookedRing[rowAgo(d) * N + b];
			for (int r = 0; r < HIST; r++) {
				hist[r * N + b] = rawRing[rowAgo(r + d) * N + b];
			}
		}
		int cookedOff = RING_OFF + ringLen * N * 4;
		int featOff = cookedOff + N * 4;
		if (version >= 3 && mapSize >= featOff + FEATURES * 4L) {
			for (int i = 0; i < FEATURES; i++) {
				features[i] = map.getFloat(featOff + i * 4);
			}
		} else {
			java.util.Arrays.fill(features, 0f);
			features[0] = normDb(map.getFloat(RMS_OFF));
		}
		lastSeq = seq;
		lastChangeNanos = now;
		live = true;
		return true;
	}

	// Local ring row holding the frame `ago` frames before the newest; rows
	// older than what was ever filled read as the oldest zero row.
	private int rowAgo(int ago) {
		if (ago >= frames) {
			return (head + 1) % LOCAL_HIST;   // never written (still zero) or the oldest
		}
		return ((head - ago) % LOCAL_HIST + LOCAL_HIST) % LOCAL_HIST;
	}

	private boolean goStale(long now) {
		if (!live) {
			return false;
		}
		live = false;
		lastChangeNanos = now;
		java.util.Arrays.fill(raw, 0f);
		java.util.Arrays.fill(cooked, 0f);
		java.util.Arrays.fill(hist, 0f);
		java.util.Arrays.fill(features, 0f);
		return true;
	}

	private boolean remap() {
		map = null;
		lastSeq = -1;
		delayHz = 0f;   // re-derive delays: the file may now come from a v3 engine
		if (!FILE.exists() || FILE.length() < RING_OFF) {
			return false;
		}
		try (FileChannel ch = FileChannel.open(FILE.toPath(), StandardOpenOption.READ)) {
			mapSize = ch.size();
			map = ch.map(FileChannel.MapMode.READ_ONLY, 0, mapSize);
			map.order(ByteOrder.LITTLE_ENDIAN);
			return true;
		} catch (IOException e) {
			map = null;
			return false;
		}
	}

	// desktop norm_db(): linear magnitude -> 0..1 over DB_FLOOR..0 dBFS
	static float normDb(float mag) {
		double db = 20.0 * Math.log10(mag + 1e-9);
		double v = (db - DB_FLOOR) / (0.0 - DB_FLOOR);
		return (float) Math.min(1.0, Math.max(0.0, v));
	}
}
