package com.termux.shadereditor.service;

import android.app.ActivityManager;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.opengl.GLSurfaceView;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import androidx.annotation.Nullable;

import com.termux.shadereditor.app.ShaderEditorApp;
import com.termux.shadereditor.beat.BeatClock;
import com.termux.shadereditor.beat.KnockDetector;
import com.termux.shadereditor.database.DataRecords;
import com.termux.shadereditor.database.DataSource;
import com.termux.shadereditor.database.Database;
import com.termux.shadereditor.preference.Preferences;
import com.termux.shadereditor.project.LegacyShaderProjectSource;
import com.termux.shadereditor.project.ShaderProjectSession;
import com.termux.shadereditor.receiver.BatteryLevelReceiver;
import com.termux.shadereditor.widget.ShaderView;

public class ShaderWallpaperService extends WallpaperService {
	private static ShaderWallpaperEngine engine;

	private ComponentName batteryLevelComponent;

	public static boolean isRunning() {
		return engine != null;
	}

	public static void reloadShader() {
		if (engine != null) {
			engine.setShader();
		}
	}

	// v131 KEEP RENDERING (debug, user 2026-11-07: "keep the backdrop running when not visible, screen off or another
	// app on top, so I can test without keeping the screen on"). Memory only: a reboot or process restart turns it off.
	// While on, a hidden wallpaper is NOT paused and a partial wake lock keeps the CPU awake for the render thread.
	private static boolean keepRendering;
	private static PowerManager.WakeLock keepLock;

	public static boolean isKeepRendering() {
		return keepRendering;
	}

	public static synchronized void setKeepRendering(android.content.Context context, boolean on) {
		keepRendering = on;
		if (on && keepLock == null) {
			PowerManager pm = (PowerManager) context.getApplicationContext()
					.getSystemService(POWER_SERVICE);
			keepLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shadereditor:keep_rendering");
			keepLock.setReferenceCounted(false);
			keepLock.acquire();
		} else if (!on && keepLock != null) {
			keepLock.release();
			keepLock = null;
		}
		Log.i("WallpaperControl", "KEEP_RENDERING " + (on ? "ON" : "OFF"));
		if (engine != null) {
			engine.applyRunState();
		}
	}

	public static void setRenderMode(int renderMode) {
		if (engine != null) {
			engine.setRenderMode(renderMode);
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		batteryLevelComponent = new ComponentName(this,
				BatteryLevelReceiver.class);
		enableComponent(batteryLevelComponent, true);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		enableComponent(batteryLevelComponent, false);
		engine = null;
	}

	@Override
	public Engine onCreateEngine() {
		engine = new ShaderWallpaperEngine();
		return engine;
	}

	private class ShaderWallpaperEngine
			extends Engine
			implements SharedPreferences.OnSharedPreferenceChangeListener {
		private ShaderWallpaperView view;

		@Override
		public void onSharedPreferenceChanged(
				SharedPreferences preferences,
				String key) {
			if (Preferences.WALLPAPER_SHADER.equals(key)) {
				setShader();
			}
		}

		@Override
		public void onCreate(SurfaceHolder holder) {
			super.onCreate(holder);
			view = new ShaderWallpaperView();
			setShader();
		}

		@Override
		public void onDestroy() {
			super.onDestroy();
			ShaderControls.hide(ShaderWallpaperService.this);   // CONTROL NOTIFICATION (v116)
			// Unregister listener to prevent memory leaks.
			ShaderEditorApp.preferences.getSharedPreferences()
					.unregisterOnSharedPreferenceChangeListener(this);
			if (view != null) {
				view.destroy();
				view = null;
			}
		}

		@Override
		public void onVisibilityChanged(boolean visible) {
			super.onVisibilityChanged(visible);
			if (!visible) {
				BeatClock.tapOff();   // screen off / app switch: the tap tempo is off (scope.md 2026-10-19)
			}
			if (!isPreview()) {
				ShaderControls.setVisible(visible);
				KnockDetector.setVisible(ShaderWallpaperService.this, visible);   // v124 body knocks
			}
			shownNow = visible;
			applyRunState();
		}

		private boolean shownNow;

		// Runs the view when shown, or when KEEP RENDERING is on (never for the picker preview).
		void applyRunState() {
			if (view == null) {
				return;
			}
			boolean run = shownNow || (keepRendering && !isPreview());
			if (run) {
				view.onResume();
			} else {
				view.onPause();
			}
		}

		// v128 TOUCH GATE (user: pokes went through the recents screen and see-through apps like MiXplorer): Android
		// copies touches to the wallpaper from ANY window that shows it. A gesture reaches the shader only when the
		// HOME launcher is the top task at its ACTION_DOWN; a one-finger tap is a boop only when the launcher also
		// sends COMMAND_TAP (SimFeed.tapConfirmed). System UID: getRunningTasks sees every task.
		private boolean gateOpen;
		private String homePkg;
		private long homeAtMs;

		private boolean homeOnTop() {
			try {
				long t = SystemClock.uptimeMillis();
				if (homePkg == null || t - homeAtMs > 60_000L) {
					ResolveInfo ri = getPackageManager().resolveActivity(
							new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
							PackageManager.MATCH_DEFAULT_ONLY);
					homePkg = ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : null;
					homeAtMs = t;
				}
				ActivityManager am = getSystemService(ActivityManager.class);
				java.util.List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
				if (homePkg == null || tasks == null || tasks.isEmpty() || tasks.get(0).topActivity == null) {
					return true;                      // cannot tell: do not lock the user out
				}
				return homePkg.equals(tasks.get(0).topActivity.getPackageName());
			} catch (RuntimeException ex) {
				return true;
			}
		}

		@Override
		public void onTouchEvent(MotionEvent e) {
			super.onTouchEvent(e);
			if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
				gateOpen = homeOnTop();
				if (!gateOpen) {
					Log.i("TouchGate", "touch ignored: home launcher not on top");
				}
			}
			if (view != null && gateOpen) {
				view.getRenderer().touchAt(e);
			}
		}

		@Override
		public Bundle onCommand(String action, int x, int y, int z, Bundle extras, boolean resultRequested) {
			if (WallpaperManager.COMMAND_TAP.equals(action) && view != null && gateOpen) {
				view.getRenderer().wallpaperTap(SystemClock.uptimeMillis() * 1_000_000L);
			}
			return super.onCommand(action, x, y, z, extras, resultRequested);
		}

		@Override
		public void onOffsetsChanged(
				float xOffset,
				float yOffset,
				float xStep,
				float yStep,
				int xPixels,
				int yPixels) {
			if (view != null) {
				view.getRenderer().setOffset(xOffset, yOffset);
			}
		}

		private ShaderWallpaperEngine() {
			super();
			ShaderEditorApp.preferences.getSharedPreferences()
					.registerOnSharedPreferenceChangeListener(this);
			setTouchEventsEnabled(true);
		}

		private void setRenderMode(int renderMode) {
			if (view != null) {
				view.setRenderMode(renderMode);
			}
		}

		private void setShader() {
			ShaderProjectSession projectSession = openWallpaperProjectSession();
			if (view != null && projectSession != null) {
				String src = projectSession.getEntryPointSource();
				view.getRenderer().setFragmentShader(
						src,
						projectSession.getQuality());
				view.setFpsCap(ShaderView.parseFpsCap(src));
				view.setKeepState(ShaderView.parseKeepState(src));
				view.setFeed(src);
				if (!isPreview()) {
					KnockDetector.setWanted(ShaderWallpaperService.this, src.contains("knockCount"));   // v133
					ShaderControls.update(ShaderWallpaperService.this, src);   // BUTTON_ lines (v116)
				}
			}
		}

		@Nullable
		private ShaderProjectSession openWallpaperProjectSession() {
			DataSource dataSource = Database.getInstance(
					ShaderWallpaperService.this).getDataSource();

			long shaderId = ShaderEditorApp.preferences.getWallpaperShader();
			DataRecords.Shader shader = dataSource.shader.getShader(shaderId);

			// If the saved shader doesn't exist, pick a random one.
			if (shader == null) {
				shader = dataSource.shader.getRandomShader();

				// If there are no shaders at all, we can't do anything.
				if (shader == null) {
					return null;
				}

				// Update the preferences to store the new random shader ID.
				ShaderEditorApp.preferences.setWallpaperShader(shader.id());
			}

			return new LegacyShaderProjectSource(shader).openSession();
		}

		private class ShaderWallpaperView extends ShaderView {
			public ShaderWallpaperView() {
				super(ShaderWallpaperService.this,
						ShaderEditorApp.preferences.isBatteryLow()
								? GLSurfaceView.RENDERMODE_WHEN_DIRTY
								: GLSurfaceView.RENDERMODE_CONTINUOUSLY);
			}

			@Override
			public final SurfaceHolder getHolder() {
				return ShaderWallpaperEngine.this.getSurfaceHolder();
			}

			public void destroy() {
				super.onDetachedFromWindow();
			}
		}
	}

	private void enableComponent(ComponentName name, boolean enable) {
		getPackageManager().setComponentEnabledSetting(name,
				enable ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
						PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
				PackageManager.DONT_KILL_APP);
	}
}