package com.termux.shadereditor.beat;

/**
 * Tempo and beat-phase tracker: a port of shader-backdrop/beat_track.py
 * (BeatTracker, causal path) with two phone-only changes, marked PHONE below
 * (scope.md 2026-10-10):
 * <ol>
 *   <li>confidence from how far the winning tempo stands out from the score
 *   field (median/spread/z), not best/mean -- the mean score sits near zero
 *   on the phone's dense full-mix onset function, which made best/mean flip
 *   randomly between 0 and 1;</li>
 *   <li>the normalised autocorrelation is averaged over AC_AVG_TAU_S, so one
 *   noisy 8 s window cannot move the tempo.</li>
 * </ol>
 * No look-ahead DP phase (there is no speaker delay to hide it in), so phase
 * is the comb -- the desktop's documented fallback.
 * Not thread-safe; driven from BeatClock's thread only.
 */
final class BeatTracker {
	// --- constants mirrored from beat_track.py ---
	static final int KICK_BAND_LO = 4;
	static final int KICK_BAND_HI = 27;
	static final double HISTORY_S = 8.0;
	static final double NOMINAL_RATE_HZ = 60.0;
	static final double BPM_MIN = 50.0;
	static final double BPM_MAX = 210.0;
	static final int[] COMB_HARMONICS = {1, 2, 3, 4};
	static final double[] COMB_WEIGHTS = {1.0, 0.5, 0.34, 0.25};
	static final double PRIOR_BPM = 120.0;
	static final double PRIOR_SIGMA_OCT = 0.9;
	static final double ESTIMATE_PERIOD_S = 0.10;
	static final double TEMPO_SWITCH_MARGIN = 1.15;
	static final double TBEAT_TAU_S = 1.0;
	static final double PLL_TAU_S = 0.5;
	static final double PLL_RATE_CLAMP = 0.25;
	static final int PHASE_COMB_BEATS = 8;
	static final double PHASE_COMB_DECAY = 0.85;
	static final double CONF_GATE = 0.15;
	static final double CONF_TAU_S = 0.4;
	static final double BEAT_WRAP = 1024.0;
	static final double ODF_SCALE_DECAY_S = 4.0;
	static final double ODF_FLOOR = 1e-4;
	static final double ACTIVITY_WINDOW_S = 1.5;
	// PHONE: autocorrelation averaging time constant (offline replay of two
	// recorded phone sessions: 8 s took TODESFLOETE from 40% to 100% on tempo).
	static final double AC_AVG_TAU_S = 8.0;

	private final int len = (int) (HISTORY_S * NOMINAL_RATE_HZ);
	private final double[] odf = new double[len];   // ring, oldest at odfHead
	private int odfHead;
	private int filled;
	private double[] prevBand;

	private double rateHz = NOMINAL_RATE_HZ;
	private double lastAudioT = Double.NaN;
	private double lastMonoT = Double.NaN;

	private double tbeat = 0.5;
	private double tbeatTarget = 0.5;
	private double count;
	private double conf;
	private double phaseErr;
	private double sinceEstimate;
	private double scale = ODF_FLOOR;
	private double lastOdf;

	private boolean hasRef;
	private double refPhase;
	private double refTbeat;

	private double[] acAvg;
	private final double[] hist = new double[len];

	// --- feed ---

	/** {@code row} = norm_db (0..1) per band. Times in seconds. */
	void update(double[] row, double audioT, double monoT) {
		double dt;
		if (Double.isNaN(lastMonoT)) {
			dt = 1.0 / NOMINAL_RATE_HZ;
		} else {
			dt = clamp(monoT - lastMonoT, 0.0, 0.1);
		}
		lastMonoT = monoT;
		if (!Double.isNaN(lastAudioT)) {
			double step = audioT - lastAudioT;
			if (step > 1e-4 && step < 0.5) {
				rateHz += (1.0 / step - rateHz) * 0.02;
			}
		}
		lastAudioT = audioT;

		pushOdf(row);

		if (hasRef) {
			tbeatTarget = clamp(refTbeat, 60.0 / BPM_MAX, 60.0 / BPM_MIN);
			phaseErr = wrapSigned(refPhase - frac(count));
			conf = 1.0;
			sinceEstimate = 0.0;
		} else {
			sinceEstimate += dt;
			if (sinceEstimate >= ESTIMATE_PERIOD_S) {
				sinceEstimate = 0.0;
				estimate(ESTIMATE_PERIOD_S);
			}
		}

		double k = 1.0 - Math.exp(-dt / TBEAT_TAU_S);
		tbeat += (tbeatTarget - tbeat) * k;

		double rate = 1.0 / Math.max(tbeat, 1e-3);
		double corr = phaseErr / PLL_TAU_S * (conf > CONF_GATE ? 1.0 : 0.0);
		corr = clamp(corr, -PLL_RATE_CLAMP * rate, PLL_RATE_CLAMP * rate);
		count = mod(count + dt * (rate + corr), BEAT_WRAP);
	}

	/**
	 * v121 TAP ONSETS (user 2026-10-29: "let my taps drive the flywheel tempo just like the music"): a finger landing is
	 * added to the newest onset sample as loud as the loudest recent onset (x strength), so the tempo autocorrelation and
	 * the phase comb hear it like a kick. Without a reference (live audio / silence) the taps steer with the audio; with a
	 * map reference the ODF is not used for tempo, so there they are only the shaders' accents.
	 */
	void addOnset(double strength) {
		if (filled == 0) {
			return;
		}
		int i = (odfHead - 1 + len) % len;
		double v = strength * Math.max(scale, 0.05);
		odf[i] += v;
		lastOdf = odf[i];
		if (odf[i] > scale) {
			scale = odf[i];
		}
	}

	void setReference(double phase, double tbeat) {
		hasRef = true;
		refPhase = mod(phase, 1.0);
		refTbeat = tbeat;
	}

	void clearReference() {
		hasRef = false;
	}

	double tbeat() {
		return tbeat;
	}

	double count() {
		return count;
	}

	double conf() {
		return conf;
	}

	double bpm() {
		return 60.0 / Math.max(tbeat, 1e-3);
	}

	double lastOdf() {
		return lastOdf;
	}

	// --- internals (beat_track.py names) ---

	private void pushOdf(double[] row) {
		int lo = Math.min(KICK_BAND_LO, row.length - 1);
		int hi = Math.min(KICK_BAND_HI + 1, row.length);
		double value = 0.0;
		if (prevBand == null || prevBand.length != hi - lo) {
			prevBand = new double[hi - lo];
		} else {
			for (int b = lo; b < hi; b++) {
				value += Math.max(0.0, row[b] - prevBand[b - lo]);
			}
		}
		System.arraycopy(row, lo, prevBand, 0, hi - lo);

		odf[odfHead] = value;
		odfHead = (odfHead + 1) % len;
		filled = Math.min(filled + 1, len);
		lastOdf = value;

		if (value > scale) {
			scale = value;
		} else {
			scale *= Math.exp(-1.0 / (ODF_SCALE_DECAY_S * rateHz));
		}
		scale = Math.max(scale, ODF_FLOOR);
	}

	/** Copies the filled part of the ring, oldest first, into hist; returns its size. */
	private int history() {
		int n = filled;
		int start = (odfHead - n + len) % len;
		for (int i = 0; i < n; i++) {
			hist[i] = odf[(start + i) % len];
		}
		return n;
	}

	private double scoreLag(double[] ac, double lag) {
		if (lag < 1.0) {
			return 0.0;
		}
		double total = 0.0;
		for (int h = 0; h < COMB_HARMONICS.length; h++) {
			double idx = lag * COMB_HARMONICS[h];
			int i = (int) idx;
			if (i + 1 >= ac.length) {
				break;
			}
			double f = idx - i;
			total += COMB_WEIGHTS[h] * (ac[i] * (1.0 - f) + ac[i + 1] * f);
		}
		double bpm = 60.0 * rateHz / lag;
		if (bpm < BPM_MIN || bpm > BPM_MAX) {
			return 0.0;
		}
		double z = Math.log(bpm / PRIOR_BPM) / Math.log(2.0) / PRIOR_SIGMA_OCT;
		return total * Math.exp(-0.5 * z * z);
	}

	private void estimate(double dt) {
		int n = history();
		if (n < (int) (3.0 * rateHz)) {
			return;
		}
		int recentN = Math.max(2, (int) (ACTIVITY_WINDOW_S * rateHz));
		double recent = 0.0;
		for (int i = n - Math.min(recentN, n); i < n; i++) {
			recent += hist[i];
		}
		recent /= Math.min(recentN, n);
		double activity = recent / Math.max(scale, ODF_FLOOR);
		double confK = 1.0 - Math.exp(-dt / CONF_TAU_S);

		double mean = 0.0;
		for (int i = 0; i < n; i++) {
			mean += hist[i];
		}
		mean /= n;
		double denom = 0.0;
		for (int i = 0; i < n; i++) {
			double c = hist[i] - mean;
			hist[i] = c;
			denom += c * c;
		}
		if (denom <= 1e-12 || activity < 1e-3) {
			conf += (0.0 - conf) * confK;
			phaseErr = 0.0;
			restoreHistory(n, mean);
			return;
		}

		int lagMin = Math.max(2, (int) Math.round(60.0 / BPM_MAX * rateHz));
		int lagMaxWanted = (int) Math.round(60.0 / BPM_MIN * rateHz);
		// The comb reads up to 4x the longest lag (+1 for interpolation).
		int acLen = Math.min(n, lagMaxWanted * COMB_HARMONICS[COMB_HARMONICS.length - 1] + 2);
		double[] ac = new double[acLen];
		for (int lag = 0; lag < acLen; lag++) {
			double s = 0.0;
			for (int i = lag; i < n; i++) {
				s += hist[i] * hist[i - lag];
			}
			ac[lag] = s / denom;
		}
		// PHONE: average the normalised autocorrelation.
		if (acAvg == null || acAvg.length != acLen) {
			acAvg = ac.clone();
		} else {
			double k = 1.0 - Math.exp(-dt / AC_AVG_TAU_S);
			for (int i = 0; i < acLen; i++) {
				acAvg[i] += (ac[i] - acAvg[i]) * k;
			}
		}
		ac = acAvg;

		int lagMax = Math.min(ac.length - 2, lagMaxWanted);
		if (lagMax <= lagMin) {
			conf += (0.0 - conf) * confK;
			restoreHistory(n, mean);
			return;
		}
		int nl = lagMax - lagMin + 1;
		double[] scores = new double[nl];
		int bestI = 0;
		for (int i = 0; i < nl; i++) {
			scores[i] = scoreLag(ac, lagMin + i);
			if (scores[i] > scores[bestI]) {
				bestI = i;
			}
		}
		double best = scores[bestI];
		if (best <= 0.0) {
			conf += (0.0 - conf) * confK;
			restoreHistory(n, mean);
			return;
		}
		double offset = (bestI > 0 && bestI < nl - 1)
				? parabolic(scores[bestI - 1], best, scores[bestI + 1])
				: 0.0;
		double candLag = lagMin + bestI + offset;
		double candTbeat = candLag / rateHz;

		double incLag = tbeatTarget * rateHz;
		double incScore = scoreLag(ac, incLag);
		double lockLag;
		double lockScore;
		if (best > incScore * TEMPO_SWITCH_MARGIN || incScore <= 0.0) {
			tbeatTarget = clamp(candTbeat, 60.0 / BPM_MAX, 60.0 / BPM_MIN);
			lockLag = candLag;
			lockScore = best;
		} else {
			lockLag = incLag;
			lockScore = incScore;
		}

		// PHONE: confidence from the score spread (see class comment).
		double[] sorted = scores.clone();
		java.util.Arrays.sort(sorted);
		double med = nl % 2 == 1
				? sorted[nl / 2]
				: 0.5 * (sorted[nl / 2 - 1] + sorted[nl / 2]);
		double sMean = 0.0;
		for (double s : scores) {
			sMean += s;
		}
		sMean /= nl;
		double var = 0.0;
		for (double s : scores) {
			var += (s - sMean) * (s - sMean);
		}
		double std = Math.sqrt(var / nl);
		double prom = (lockScore - med) / (sorted[nl - 1] - med + 1e-12);
		double z = (best - med) / (std + 1e-12);
		double targetConf = clamp(prom, 0.0, 1.0) *
				clamp((z - 1.0) / 2.0, 0.0, 1.0) *
				clamp(activity * 12.0, 0.0, 1.0);
		conf += (targetConf - conf) * confK;

		restoreHistory(n, mean);
		phaseErr = conf > CONF_GATE ? measurePhaseError(n, lockLag) : 0.0;
	}

	private void restoreHistory(int n, double mean) {
		for (int i = 0; i < n; i++) {
			hist[i] += mean;
		}
	}

	private double measurePhaseError(int n, double lag) {
		double l = Math.max(lag, 2.0);
		int nOff = (int) Math.ceil(l);
		if (n < l * 2) {
			return 0.0;
		}
		int kMax = Math.min(PHASE_COMB_BEATS, (int) ((n - 1) / l));
		if (kMax < 1) {
			return 0.0;
		}
		int newest = n - 1;
		int bestOff = 0;
		double bestVal = -1.0;
		double[] vals = new double[nOff];
		for (int off = 0; off < nOff; off++) {
			double total = 0.0;
			double w = 1.0;
			for (int k = 0; k < kMax; k++) {
				int idx = newest - off - (int) Math.round(k * l);
				if (idx < 0) {
					break;
				}
				total += w * hist[idx];
				w *= PHASE_COMB_DECAY;
			}
			vals[off] = total;
			if (total > bestVal) {
				bestVal = total;
				bestOff = off;
			}
		}
		if (bestVal <= 0.0) {
			return 0.0;
		}
		double bestPos = (bestOff > 0 && bestOff < nOff - 1)
				? bestOff + parabolic(vals[bestOff - 1], vals[bestOff], vals[bestOff + 1])
				: bestOff;
		double measured = mod(bestPos / l, 1.0);
		return wrapSigned(measured - frac(count));
	}

	// --- helpers ---

	static double parabolic(double lo, double mid, double hi) {
		double denom = lo - 2.0 * mid + hi;
		if (Math.abs(denom) < 1e-12) {
			return 0.0;
		}
		return clamp(0.5 * (lo - hi) / denom, -0.5, 0.5);
	}

	static double wrapSigned(double x) {
		return mod(x + 0.5, 1.0) - 0.5;
	}

	static double mod(double x, double m) {
		double r = x % m;
		return r < 0 ? r + m : r;
	}

	static double frac(double x) {
		return mod(x, 1.0);
	}

	static double clamp(double x, double lo, double hi) {
		return Math.max(lo, Math.min(hi, x));
	}
}
