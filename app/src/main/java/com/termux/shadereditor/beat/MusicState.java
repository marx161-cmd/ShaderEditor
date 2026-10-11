package com.termux.shadereditor.beat;

import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The shared "music state" layer (scope.md 2026-10-11 "shared baseline", step 2):
 * a port of shader-backdrop music_state.py with the SAME u_ms_* uniforms and
 * meanings, so movement code copies between desktop and phone shaders unchanged.
 * Runs on the BeatClock thread, one update per JDSP frame (60 Hz).
 *
 * <pre>
 * u_ms_source      0 live, 1 cache
 * u_ms_cur_a       energy, weight, density, brightness   (0..1, song-relative, now)
 * u_ms_cur_b       pulse, voice, tension, impact          (tension: cache only, else -1)
 * u_ms_sec_a/b     the same 8 axes for the CURRENT section
 * u_ms_next_a/b    the next section's 8 axes (cache only, else -1)
 * u_ms_change      section-change COUNTER (+1 per change; compare with a stored copy)
 * u_ms_change_age  seconds since the last change
 * u_ms_next_in     beats until the next change (cache only, else -1)
 * u_ms_drop_in     seconds until the next drop (cache only, else -1)
 * u_ms_bar_phase   0..1 through the bar (cache: real downbeats; live: beat count / 4)
 * u_ms_repeat      section repeat id (cache only, else -1)
 * u_ms_song_pos    seconds into the song as heard (cache only, else -1)
 * </pre>
 *
 * Cached path = music_state.py 1:1, from the analysis the desktop exports into
 * beatmaps.json (beatmaps-sync-pixel, version 2). Live path: the phone has no
 * drum/tonal stems, so it works from JDSP's 108 linear band amplitudes (band i
 * = 27.5 Hz * 2^(i/12)) -- weight = power share below 250 Hz, brightness =
 * power-weighted log centroid, density = onsets/s in three band groups (kick,
 * snare body, hats), voice = power share in 300-3400 Hz (the whole mix, not a
 * vocal stem: close, not identical). Each live axis is scaled against the song
 * so far exactly as on the desktop, so 0..1 means the same thing.
 */
public final class MusicState {
	static final String[] AXES = {"energy", "weight", "density", "brightness",
			"pulse", "voice", "tension", "impact"};
	private static final int LIVE = 6;
	// music_state.py constants
	private static final double[] LIVE_MIN_RANGE = {0.15, 0.10, 1.5, 0.06, 0.20, 0.08};
	private static final double RANGE_TAU_S = 45.0;
	private static final double CUR_TAU_S = 0.5;
	private static final double PULSE_TAU_S = 2.5;
	private static final double WARMUP_S = 4.0;
	private static final double SHORT_TAU_S = 2.0;
	private static final double NOVELTY_ON = 0.12;
	private static final double NOVELTY_HOLD_S = 0.6;
	private static final double MIN_SECTION_BEATS = 12;
	private static final double SILENCE_LEVEL = 0.05;
	private static final double SILENCE_S = 1.5;
	private static final double ONSET_REFRACT_S = 0.08;
	private static final double DENSITY_WIN_S = 2.0;
	private static final double[] NOV_W = {1.0, 1.0, 0.8, 0.6, 0.0, 0.5};
	// phone live features
	private static final int N = 108;
	private static final int WEIGHT_TOP = 38;          // bands below 250 Hz
	private static final int VOICE_LO = 42, VOICE_HI = 83;   // 300..3400 Hz
	private static final int[][] ONSET_GROUPS = {{0, 24}, {36, 61}, {84, 108}};
	private static final double ONSET_DB = 6.0;        // fast rise over the slow level = one onset
	private static final double ONSET_FAST_TAU = 0.02;
	private static final double ONSET_SLOW_TAU = 0.25;

	/** One analysed song, from beatmaps.json (version 2 fields). */
	static final class Song {
		double[] beats;
		double[] downbeats;
		int bpb;
		double[] secStart;
		double[] secStartBeat;
		double[][] secScore;
		int[] secRepeat;
		double[] drops;
		byte[][] rel;      // [6][beats] u8, song-relative curve per beat
		double duration;

		@Nullable
		static Song parse(@NonNull JSONObject o, double[] beats) {
			JSONArray secs = o.optJSONArray("sections");
			JSONObject rel = o.optJSONObject("rel");
			if (secs == null || secs.length() == 0 || rel == null) {
				return null;
			}
			try {
				Song s = new Song();
				s.beats = beats;
				s.bpb = Math.max(1, o.optInt("beats_per_bar", 4));
				s.duration = o.optDouble("duration_s", beats[beats.length - 1]);
				s.downbeats = doubles(o.optJSONArray("downbeats"));
				s.drops = doubles(o.optJSONArray("drops"));
				int n = secs.length();
				s.secStart = new double[n];
				s.secStartBeat = new double[n];
				s.secScore = new double[n][8];
				s.secRepeat = new int[n];
				for (int i = 0; i < n; i++) {
					JSONObject sec = secs.getJSONObject(i);
					s.secStart[i] = sec.getDouble("start_s");
					s.secStartBeat[i] = sec.getDouble("start_beat");
					JSONArray sc = sec.getJSONArray("score");
					for (int k = 0; k < 8; k++) {
						s.secScore[i][k] = k < sc.length() ? sc.getDouble(k) : -1.0;
					}
					s.secRepeat[i] = sec.optInt("repeat", -1);
				}
				s.rel = new byte[LIVE][];
				for (int k = 0; k < LIVE; k++) {
					s.rel[k] = Base64.decode(rel.optString(AXES[k], ""), Base64.DEFAULT);
				}
				return s;
			} catch (JSONException | IllegalArgumentException e) {
				return null;
			}
		}

		private static double[] doubles(@Nullable JSONArray a) throws JSONException {
			if (a == null) {
				return new double[0];
			}
			double[] out = new double[a.length()];
			for (int i = 0; i < out.length; i++) {
				out[i] = a.getDouble(i);
			}
			return out;
		}

		int sectionAt(double t) {
			int i = upper(secStart, t) - 1;
			return Math.max(i, 0);
		}

		double beatIndexAt(double t) {
			double[] b = beats;
			if (t <= b[0]) {
				return (t - b[0]) / Math.max(b[1] - b[0], 1e-3);
			}
			int i = upper(b, t) - 1;
			if (i >= b.length - 1) {
				int l = b.length - 1;
				return l + (t - b[l]) / Math.max(b[l] - b[l - 1], 1e-3);
			}
			return i + (t - b[i]) / Math.max(b[i + 1] - b[i], 1e-6);
		}

		/** The per-beat curve, linearly interpolated between beats. */
		double curve(int k, double beatIndex) {
			byte[] a = rel[k];
			if (a.length == 0) {
				return -1.0;
			}
			double x = Math.min(Math.max(beatIndex, 0.0), a.length - 1);
			int i = (int) Math.floor(x);
			int j = Math.min(i + 1, a.length - 1);
			double f = x - i;
			return ((a[i] & 0xff) * (1.0 - f) + (a[j] & 0xff) * f) / 255.0;
		}

		/** first index with a[i] > t */
		static int upper(double[] a, double t) {
			int lo = 0, hi = a.length;
			while (lo < hi) {
				int mid = (lo + hi) >>> 1;
				if (a[mid] <= t) {
					lo = mid + 1;
				} else {
					hi = mid;
				}
			}
			return lo;
		}
	}

	/** Immutable uniform values for the render thread. */
	public static final class Snapshot {
		public final float source;
		public final float[] curA, curB, secA, secB, nextA, nextB;
		public final float change;
		public final long changeNanos;
		public final float nextIn, dropIn, barPhase, repeat, songPos;

		Snapshot(float source, float[] curA, float[] curB, float[] secA, float[] secB,
				float[] nextA, float[] nextB, float change, long changeNanos,
				float nextIn, float dropIn, float barPhase, float repeat, float songPos) {
			this.source = source;
			this.curA = curA;
			this.curB = curB;
			this.secA = secA;
			this.secB = secB;
			this.nextA = nextA;
			this.nextB = nextB;
			this.change = change;
			this.changeNanos = changeNanos;
			this.nextIn = nextIn;
			this.dropIn = dropIn;
			this.barPhase = barPhase;
			this.repeat = repeat;
			this.songPos = songPos;
		}

		public float changeAge(long now) {
			return (now - changeNanos) / 1e9f;
		}
	}

	/** music_state.py _Range: song-relative scaling with a minimum span. */
	private static final class Range {
		final double minSpan;
		double lo, hi;
		boolean set;

		Range(double minSpan) {
			this.minSpan = minSpan;
		}

		double rel(double x, double dt) {
			if (!set) {
				lo = x - 0.5 * minSpan;
				hi = x + 0.5 * minSpan;
				set = true;
			}
			double a = 1.0 - Math.exp(-dt / RANGE_TAU_S);
			double mid = 0.5 * (lo + hi);
			lo = Math.min(x, lo + (mid - lo) * a);
			hi = Math.max(x, hi + (mid - hi) * a);
			double span = Math.max(hi - lo, minSpan);
			double c = 0.5 * (lo + hi);
			return clamp((x - (c - 0.5 * span)) / span);
		}
	}

	private final Range[] ranges = new Range[LIVE];
	private final double[] cur = new double[LIVE];
	private final double[] shortV = new double[LIVE];
	private final double[] secSum = new double[LIVE];
	private final double[] secVals = new double[LIVE];
	private final double[] raw = new double[LIVE];
	private int secN;
	private double impact;
	private int changes;
	private long changeNanos = System.nanoTime();
	private double changeBeat = Double.NaN;
	private double novelSince = -1;
	private boolean pendingChange;
	private double silentSince = -1;
	private double songT;
	private double lastT = -1;
	// live onsets
	private final double[] onFast = new double[3];
	private final double[] onSlow = new double[3];
	private final boolean[] onAbove = new boolean[3];
	private final double[] onLast = {-1, -1, -1};
	private final double[] onsetHist = new double[256];
	private int onsetHead, onsetCount;
	// cache
	@Nullable
	private Song song;
	private int cacheSec = -1;

	private volatile Snapshot snapshot;

	MusicState() {
		for (int k = 0; k < LIVE; k++) {
			ranges[k] = new Range(LIVE_MIN_RANGE[k]);
			cur[k] = shortV[k] = secVals[k] = 0.5;
		}
		snapshot = liveSnapshot(0.0);
	}

	@NonNull
	public Snapshot snapshot() {
		return snapshot;
	}

	/** The matched song (null = none); a change resets the song-relative state. */
	void setSong(@Nullable Song s) {
		if (s != song) {
			song = s;
			cacheSec = -1;
			resetSong();
		}
	}

	/**
	 * One update. {@code lin} = this frame's 108 linear band amplitudes (null when
	 * no JDSP frames flow -- the live part then holds), {@code rms} = frame rms
	 * (linear), {@code t} = seconds, {@code songPos} = heard position in the
	 * matched song (NaN = not playing).
	 */
	void update(@Nullable double[] lin, double rms, double t, long nanos,
			double beatCount, double beatConf, double songPos) {
		if (lin != null) {        // t = JDSP frame time; only frames advance the live part
			double dt = lastT < 0 || t < lastT ? 1.0 / 60.0 : Math.min(Math.max(t - lastT, 1e-3), 0.25);
			lastT = t;
			live(liveRaw(lin, rms, beatConf, t), dt, t, nanos, beatCount);
		}
		Song s = song;
		if (s != null && !Double.isNaN(songPos) && songPos >= 0.0 && songPos <= s.duration) {
			snapshot = cached(s, songPos, nanos);
		} else {
			snapshot = liveSnapshot(beatCount);
		}
	}

	// --- live ---

	private double[] liveRaw(double[] lin, double rms, double beatConf, double t) {
		double total = 1e-20, low = 0, voice = 0, wlog = 0;
		for (int i = 0; i < N; i++) {
			double p = lin[i] * lin[i];
			total += p;
			if (i < WEIGHT_TOP) {
				low += p;
			}
			if (i >= VOICE_LO && i <= VOICE_HI) {
				voice += p;
			}
			wlog += p * (i / 12.0);              // log2(f / 27.5)
		}
		double hz = 27.5 * Math.pow(2.0, wlog / total);
		double bright = clamp(Math.log(Math.max(hz, 20.0) / 20.0) / Math.log(16000.0 / 20.0));
		// music_state.py: energy = 1.5 * norm_db(rms) - 0.5, norm_db over -60..0 dB
		double nd = clamp((20.0 * Math.log10(Math.max(rms, 1e-9)) + 60.0) / 60.0);
		raw[0] = clamp(1.5 * nd - 0.5);
		raw[1] = Math.min(low / total, 1.0);
		raw[2] = onsets(lin, t);
		raw[3] = bright;
		raw[4] = beatConf;
		raw[5] = Math.min(voice / total, 1.0);
		return raw;
	}

	private double onsets(double[] lin, double t) {
		double a = 1.0 - Math.exp(-1.0 / 60.0 / ONSET_FAST_TAU);
		double b = 1.0 - Math.exp(-1.0 / 60.0 / ONSET_SLOW_TAU);
		for (int g = 0; g < 3; g++) {
			double p = 1e-20;
			for (int i = ONSET_GROUPS[g][0]; i < ONSET_GROUPS[g][1]; i++) {
				p += lin[i] * lin[i];
			}
			double db = 10.0 * Math.log10(p);
			if (onFast[g] == 0 && onSlow[g] == 0) {
				onFast[g] = onSlow[g] = db;
			}
			onFast[g] += (db - onFast[g]) * a;
			onSlow[g] += (db - onSlow[g]) * b;
			boolean above = onFast[g] - onSlow[g] > ONSET_DB;
			if (above && !onAbove[g] && t - onLast[g] > ONSET_REFRACT_S) {
				onLast[g] = t;
				onsetHist[onsetHead] = t;
				onsetHead = (onsetHead + 1) % onsetHist.length;
				onsetCount = Math.min(onsetCount + 1, onsetHist.length);
			}
			onAbove[g] = above;
		}
		int n = 0;
		for (int k = 0; k < onsetCount; k++) {
			if (onsetHist[(onsetHead - 1 - k + onsetHist.length) % onsetHist.length] >= t - DENSITY_WIN_S) {
				n++;
			} else {
				break;
			}
		}
		return n / DENSITY_WIN_S;
	}

	private void resetSong() {
		songT = 0;
		for (Range r : ranges) {
			r.set = false;
		}
		secN = 0;
		java.util.Arrays.fill(secSum, 0.0);
		changeBeat = Double.NaN;
	}

	private void live(double[] r, double dt, double t, long nanos, double beatCount) {
		if (r[0] < SILENCE_LEVEL) {
			if (silentSince < 0) {
				silentSince = t;
			}
			if (t - silentSince >= SILENCE_S) {
				resetSong();
			}
			return;
		}
		silentSince = -1;
		double aCur = 1.0 - Math.exp(-dt / CUR_TAU_S);
		double aShort = 1.0 - Math.exp(-dt / SHORT_TAU_S);
		double aPulse = 1.0 - Math.exp(-dt / PULSE_TAU_S);
		for (int k = 0; k < LIVE; k++) {
			double v = ranges[k].rel(r[k], dt);
			cur[k] += (v - cur[k]) * (k == 4 ? aPulse : aCur);
			shortV[k] += (v - shortV[k]) * aShort;
		}
		songT += dt;
		if (songT < WARMUP_S) {
			return;
		}
		for (int k = 0; k < LIVE; k++) {
			secSum[k] += cur[k];
		}
		secN++;
		for (int k = 0; k < LIVE; k++) {
			secVals[k] = secSum[k] / secN;
		}
		if (Double.isNaN(changeBeat)) {
			changeBeat = beatCount;
		}
		double num = 0, den = 0;
		for (int k = 0; k < LIVE; k++) {
			double d = shortV[k] - secVals[k];
			num += NOV_W[k] * d * d;
			den += NOV_W[k];
		}
		double nov = Math.sqrt(num / den);
		// the beat count wraps at BEAT_WRAP: measure the distance forward
		double since = BeatTracker.mod(beatCount - changeBeat, BeatTracker.BEAT_WRAP);
		boolean oldEnough = since >= MIN_SECTION_BEATS && secN > 60;
		if (nov > NOVELTY_ON && oldEnough) {
			if (novelSince < 0) {
				novelSince = t;
			}
			if (t - novelSince >= NOVELTY_HOLD_S) {
				pendingChange = true;
			}
		} else {
			novelSince = -1;
		}
		if (pendingChange && BeatTracker.mod(beatCount, 1.0) < 0.25) {
			pendingChange = false;
			novelSince = -1;
			impact = clamp(2.0 * (shortV[0] - secVals[0]));
			changes++;
			changeNanos = nanos;
			changeBeat = beatCount;
			System.arraycopy(shortV, 0, secSum, 0, LIVE);
			secN = 1;
		}
	}

	private Snapshot liveSnapshot(double beatCount) {
		float[] none = {-1f, -1f, -1f, -1f};
		return new Snapshot(0f,
				f4(cur[0], cur[1], cur[2], cur[3]), f4(cur[4], cur[5], -1, impact),
				f4(secVals[0], secVals[1], secVals[2], secVals[3]), f4(secVals[4], secVals[5], -1, impact),
				none, none.clone(), changes, changeNanos, -1f, -1f,
				(float) (BeatTracker.mod(beatCount, 4.0) / 4.0), -1f, -1f);
	}

	// --- cache (music_state.py _cached) ---

	private Snapshot cached(Song s, double pos, long nanos) {
		int i = s.sectionAt(pos);
		// count a change only on a forward step (or a real jump back = a seek)
		if (cacheSec < 0) {
			cacheSec = i;
		} else if (i > cacheSec || (i < cacheSec && pos < s.secStart[cacheSec] - 1.0)) {
			changes++;
			changeNanos = nanos;
			cacheSec = i;
		} else {
			i = cacheSec;
		}
		double[] sc = s.secScore[i];
		boolean hasNext = i + 1 < s.secStart.length;
		double bi = s.beatIndexAt(pos);
		double nextIn = hasNext ? s.secStartBeat[i + 1] - bi : -1.0;
		double dropIn = -1.0;
		int d = Song.upper(s.drops, pos);
		if (d < s.drops.length) {
			dropIn = s.drops[d] - pos;
		}
		double barPhase;
		double[] db = s.downbeats;
		int k = Song.upper(db, pos) - 1;
		if (k >= 0 && k < db.length - 1) {
			barPhase = (pos - db[k]) / Math.max(db[k + 1] - db[k], 1e-3);
		} else {
			barPhase = BeatTracker.mod(bi, s.bpb) / s.bpb;
		}
		double[] c = new double[LIVE];
		for (int a = 0; a < LIVE; a++) {
			c[a] = s.curve(a, bi);
		}
		float[] nA, nB;
		if (hasNext) {
			double[] n = s.secScore[i + 1];
			nA = f4(n[0], n[1], n[2], n[3]);
			nB = f4(n[4], n[5], n[6], n[7]);
		} else {
			nA = f4(-1, -1, -1, -1);
			nB = f4(-1, -1, -1, -1);
		}
		return new Snapshot(1f,
				f4(c[0], c[1], c[2], c[3]), f4(c[4], c[5], sc[6], sc[7]),
				f4(sc[0], sc[1], sc[2], sc[3]), f4(sc[4], sc[5], sc[6], sc[7]),
				nA, nB, changes, changeNanos, (float) nextIn, (float) dropIn,
				(float) barPhase, s.secRepeat[i], (float) pos);
	}

	private static float[] f4(double a, double b, double c, double d) {
		return new float[]{(float) a, (float) b, (float) c, (float) d};
	}

	private static double clamp(double x) {
		return Math.min(Math.max(x, 0.0), 1.0);
	}
}
