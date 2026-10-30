package com.termux.shadereditor.beat;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * v124 BODY KNOCKS (scope.md 2026-10-30): a knock on the phone's SIDE (or top / bottom edge) is a rhythm tap. Quick Tap
 * listens for jolts through the phone's thickness (z); a side knock jolts it in the screen plane (x / y), and so do the
 * user's knocks -- screen taps, back taps and setting the phone down are z jolts and are rejected by the axis test.
 * Accelerometer at the fastest rate while the wallpaper is visible; a knock = the per-sample change of acceleration
 * (jerk) spiking over max(KNOCK_MIN, KNOCK_K x the running floor), mostly in-plane, settled again CONFIRM_NS later,
 * no screen touch within TOUCH_PRE_NS / CONFIRM_NS of it, REFRACT_NS after the previous one. Each knock bumps count()
 * (uniform knockCount) and is a BeatClock onset. Logcat tag Knock prints candidates for tuning.
 */
public final class KnockDetector implements SensorEventListener {
	private static final String TAG = "Knock";
	static final double KNOCK_MIN = 1.2;          // m/s^2 change between two samples
	static final double KNOCK_K = 8.0;            // x the running floor (walking / handling raise it)
	static final double PLANE_MIN = 0.70;         // in-plane share of the spike (x / y vs z)
	static final double SETTLE = 0.5;             // jerk at confirm time must be below SETTLE x the threshold
	static final long CONFIRM_NS = 45_000_000L;
	static final long TOUCH_PRE_NS = 120_000_000L;
	static final long REFRACT_NS = 70_000_000L;
	static final double FLOOR_TAU_S = 0.5;
	static final double LOG_MIN = 0.5;

	private static KnockDetector instance;
	private static volatile int count;
	private static volatile long lastTouchNanos;

	private final SensorManager sm;
	private final Sensor sensor;
	private HandlerThread thread;
	private boolean on;

	private final float[] prev = new float[3];
	private boolean hasPrev;
	private long prevT;
	private double floor = 0.05;
	private long candT;          // System.nanoTime of the pending spike, 0 = none
	private double candPeak;
	private double candThr;
	private long lastKnock;
	private long lastLog;

	private KnockDetector(@NonNull Context context) {
		sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
		sensor = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
	}

	/** The wallpaper engine's visibility (not previews); main thread. */
	public static synchronized void setVisible(@NonNull Context context, boolean visible) {
		if (instance == null) {
			instance = new KnockDetector(context.getApplicationContext());
		}
		if (visible) {
			instance.start();
		} else {
			instance.stop();
		}
	}

	/** A finger landed on the screen (System.nanoTime base); any thread. */
	public static void touchDown(long nanos) {
		lastTouchNanos = nanos;
	}

	public static int count() {
		return count;
	}

	private void start() {
		if (on || sensor == null) {
			return;
		}
		thread = new HandlerThread("Knock");
		thread.start();
		hasPrev = false;
		candT = 0;
		on = sm.registerListener(this, sensor, 0, 0, new Handler(thread.getLooper()));
		Log.i(TAG, "on: " + sensor.getName() + " min delay " + sensor.getMinDelay() + " us, registered " + on);
	}

	private void stop() {
		if (!on) {
			return;
		}
		sm.unregisterListener(this);
		thread.quitSafely();
		thread = null;
		on = false;
		Log.i(TAG, "off");
	}

	@Override
	public void onSensorChanged(SensorEvent e) {
		long t = System.nanoTime() - (SystemClock.elapsedRealtimeNanos() - e.timestamp);
		float[] v = e.values;
		if (!hasPrev) {
			System.arraycopy(v, 0, prev, 0, 3);
			hasPrev = true;
			prevT = t;
			return;
		}
		double dx = v[0] - prev[0], dy = v[1] - prev[1], dz = v[2] - prev[2];
		System.arraycopy(v, 0, prev, 0, 3);
		double dt = Math.min(Math.max((t - prevT) / 1e9, 1e-4), 0.05);
		prevT = t;
		double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
		double thr = Math.max(KNOCK_MIN, KNOCK_K * floor);

		if (candT != 0) {
			if (d > candPeak) {
				candPeak = d;
			}
			if (t - candT >= CONFIRM_NS) {
				judge(d);
			}
		} else if (d > thr && t - lastKnock > REFRACT_NS) {
			double plane = Math.sqrt(dx * dx + dy * dy) / Math.max(d, 1e-6);
			if (plane >= PLANE_MIN) {
				candT = t;
				candPeak = d;
				candThr = thr;
			} else {
				note(t, String.format(Locale.US, "z jolt %.2f (plane %.2f, thr %.2f)", d, plane, thr));
			}
		}
		if (candT == 0 && d < thr) {   // the floor only learns from quiet samples
			floor += (d - floor) * (1.0 - Math.exp(-dt / FLOOR_TAU_S));
		}
	}

	private void judge(double dNow) {
		long t = candT;
		candT = 0;
		long touch = lastTouchNanos;
		if (touch != 0 && touch > t - TOUCH_PRE_NS && touch < t + CONFIRM_NS) {
			note(t, String.format(Locale.US, "screen tap %.2f", candPeak));
			return;
		}
		if (dNow > SETTLE * candThr) {
			note(t, String.format(Locale.US, "not settled %.2f (now %.2f)", candPeak, dNow));
			return;
		}
		lastKnock = t;
		count++;
		BeatClock.knock(t);
		Log.i(TAG, String.format(Locale.US, "KNOCK #%d peak %.2f thr %.2f floor %.3f", count, candPeak, candThr, floor));
	}

	private void note(long t, String what) {
		if (t - lastLog > 100_000_000L) {
			lastLog = t;
			Log.d(TAG, what);
		}
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
	}
}
