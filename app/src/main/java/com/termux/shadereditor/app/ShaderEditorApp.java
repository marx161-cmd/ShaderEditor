package com.termux.shadereditor.app;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.StrictMode;

import com.termux.shadereditor.BuildConfig;
import com.termux.shadereditor.database.Database;
import com.termux.shadereditor.preference.Preferences;
import com.termux.shadereditor.receiver.BatteryLevelReceiver;
import com.termux.shadereditor.receiver.ScreenLockReceiver;
import com.termux.shadereditor.view.UndoRedo;

public class ShaderEditorApp extends Application {
	public static final Preferences preferences = new Preferences();
	public static final UndoRedo.EditHistory editHistory = new UndoRedo.EditHistory();

	private static final BatteryLevelReceiver batteryLevelReceiver = new BatteryLevelReceiver();
	private static final ScreenLockReceiver screenLockReceiver = new ScreenLockReceiver();

	@Override
	public void onCreate() {
		super.onCreate();

		if (BuildConfig.DEBUG) {
			StrictMode.setThreadPolicy(
					new StrictMode.ThreadPolicy.Builder()
							.detectAll()
							.penaltyLog()
							.build());

			StrictMode.setVmPolicy(
					new StrictMode.VmPolicy.Builder()
							.detectLeakedSqlLiteObjects()
							.penaltyLog()
							.penaltyDeath()
							.build());
		}

		preferences.init(this);
		// Initialize the singleton instance for the application lifecycle.
		// Other components will get this instance via Database.getInstance().
		Database.getInstance(this);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			registerBatteryReceiver();
		}
		registerScreenLockReceiver();
	}

	private void registerBatteryReceiver() {
		IntentFilter filter = new IntentFilter();
		filter.addAction(Intent.ACTION_BATTERY_LOW);
		filter.addAction(Intent.ACTION_BATTERY_OKAY);
		filter.addAction(Intent.ACTION_BATTERY_CHANGED);
		registerReceiver(batteryLevelReceiver, filter);
		// Note it's not required to unregister the receiver because it
		// needs to be there as long as this application is running.
	}

	private void registerScreenLockReceiver() {
		// Seed the initial state — SCREEN_OFF/USER_PRESENT only fire on
		// future transitions, not for "already locked when the app/engine
		// starts" (e.g. wallpaper engine created while the device is
		// already sitting on the lock screen).
		KeyguardManager keyguardManager =
				(KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
		preferences.setScreenLocked(
				keyguardManager != null && keyguardManager.isKeyguardLocked());

		IntentFilter filter = new IntentFilter();
		filter.addAction(Intent.ACTION_SCREEN_OFF);
		filter.addAction(Intent.ACTION_USER_PRESENT);
		registerReceiver(screenLockReceiver, filter);
	}
}
