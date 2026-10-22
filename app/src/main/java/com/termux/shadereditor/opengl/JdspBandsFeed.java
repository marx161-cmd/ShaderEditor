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
 */
final class JdspBandsFeed {
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

	final float[] raw = new float[N];
	final float[] cooked = new float[N];
	final float[] hist = new float[N * HIST];   // row r = r frames ago, row 0 newest
	final float[] features = new float[FEATURES];
	int version;
	float frameHz = 60f;
	boolean live;

	private MappedByteBuffer map;
	private long mapSize;
	private long lastMapTry;
	private long lastSeq = -1;
	private long lastChangeNanos;

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
		frameHz = hz;
		for (int b = 0; b < N; b++) {
			raw[b] = map.getFloat(b * 4);
		}
		int rows = Math.min(HIST, ringLen);
		for (int r = 0; r < rows; r++) {
			long k = seq - 1 - r;
			if (k < 0) {
				java.util.Arrays.fill(hist, r * N, (r + 1) * N, 0f);
				continue;
			}
			int off = RING_OFF + (int) (k % ringLen) * N * 4;
			for (int b = 0; b < N; b++) {
				hist[r * N + b] = map.getFloat(off + b * 4);
			}
		}
		int cookedOff = RING_OFF + ringLen * N * 4;
		int featOff = cookedOff + N * 4;
		if (version >= 3 && mapSize >= featOff + FEATURES * 4L) {
			for (int b = 0; b < N; b++) {
				cooked[b] = map.getFloat(cookedOff + b * 4);
			}
			for (int i = 0; i < FEATURES; i++) {
				features[i] = map.getFloat(featOff + i * 4);
			}
		} else {
			for (int b = 0; b < N; b++) {
				cooked[b] = normDb(raw[b]);
			}
			java.util.Arrays.fill(features, 0f);
			features[0] = normDb(map.getFloat(RMS_OFF));
		}
		lastSeq = seq;
		lastChangeNanos = now;
		live = true;
		return true;
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
