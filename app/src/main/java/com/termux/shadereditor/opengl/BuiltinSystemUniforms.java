package com.termux.shadereditor.opengl;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.os.BatteryManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Calendar;

import com.termux.shadereditor.app.ShaderEditorApp;
import com.termux.shadereditor.hardware.MicInputListener;
import com.termux.shadereditor.service.NotificationService;

final class BuiltinSystemUniforms {
	private static final long BATTERY_UPDATE_INTERVAL = 10000000000L;
	private static final long DATE_UPDATE_INTERVAL = 1000000000L;
	private static final long MEDIA_VOLUME_UPDATE_INTERVAL = 1000000000L;
	// Matches JDSP's reactive.lua tick rate — polling faster than the source
	// updates just adds file-read overhead for no new data.
	private static final long MPV_AUDIO_LEVEL_UPDATE_INTERVAL = 33333333L;  // ~30Hz, matches JDSP's reactive.lua tick()

	// com.termux.jdsp shares this app's UID (android.uid.system), so its
	// private app data is directly readable here — no IPC, no permission,
	// same "zero sandbox" pattern as the rest of the com.termux.* family.
	// JDSP's reactive.lua script (auto-loaded into the native engine, see
	// project notes) writes a multi-line key=value bridge here at ~30Hz
	// covering whatever audio the system is currently outputting — not tied
	// to any one player app, unlike the old mpv-only version of this uniform.
	// Kept reading the "mpvAudioLevel" uniform name itself unchanged so
	// existing shaders don't silently break; only the driver moved.
	private static final File JDSP_AUDIO_BRIDGE_FILE =
			new File("/data/data/com.termux.jdsp/files/audio_bridge.txt");

	private final float[] daytime = new float[]{0, 0, 0};
	private final float[] dateTime = new float[]{0, 0, 0, 0};
	@NonNull
	private final Context context;

	@Nullable
	private MicInputListener micInputListener;
	private boolean hasNightMode;
	private boolean hasNotificationCount;
	private boolean hasLastNotificationTime;
	private boolean hasBattery;
	private boolean hasPowerConnected;
	private boolean hasDate;
	private boolean hasDaytime;
	private boolean hasMediaVolume;
	private boolean hasMicAmplitude;
	private boolean hasMpvAudioLevel;
	private int nightMode;
	private long lastBatteryUpdate;
	private long lastDateUpdate;
	private long lastMediaVolumeUpdate;
	private long lastMpvAudioLevelUpdate;
	private float batteryLevel;
	private float mediaVolumeLevel;
	private float mpvAudioLevelValue;

	BuiltinSystemUniforms(@NonNull Context context) {
		this.context = context;
	}

	void configure(
			@NonNull GlDevice device,
			@NonNull GlProgram program) {
		lastBatteryUpdate = 0L;
		lastDateUpdate = 0L;
		lastMediaVolumeUpdate = 0L;
		lastMpvAudioLevelUpdate = 0L;
		hasNightMode = device.hasUniform(program, ShaderRenderer.UNIFORM_NIGHT_MODE);
		hasNotificationCount = device.hasUniform(
				program,
				ShaderRenderer.UNIFORM_NOTIFICATION_COUNT);
		hasLastNotificationTime = device.hasUniform(
				program,
				ShaderRenderer.UNIFORM_LAST_NOTIFICATION_TIME);
		hasBattery = device.hasUniform(program, ShaderRenderer.UNIFORM_BATTERY);
		hasPowerConnected = device.hasUniform(
				program,
				ShaderRenderer.UNIFORM_POWER_CONNECTED);
		hasDate = device.hasUniform(program, ShaderRenderer.UNIFORM_DATE);
		hasDaytime = device.hasUniform(program, ShaderRenderer.UNIFORM_DAYTIME);
		hasMediaVolume = device.hasUniform(
				program,
				ShaderRenderer.UNIFORM_MEDIA_VOLUME);
		hasMicAmplitude = device.hasUniform(
				program,
				ShaderRenderer.UNIFORM_MIC_AMPLITUDE);
		hasMpvAudioLevel = device.hasUniform(
				program,
				ShaderRenderer.UNIFORM_MPV_AUDIO_LEVEL);

		if (hasNightMode) {
			nightMode = (context.getResources().getConfiguration().uiMode &
					Configuration.UI_MODE_NIGHT_MASK) ==
					Configuration.UI_MODE_NIGHT_YES ? 1 : 0;
		}

		if (usesNotificationUniforms()) {
			NotificationService.requirePermissions(context);
		}

		if (hasMicAmplitude) {
			if (micInputListener == null) {
				micInputListener = new MicInputListener(context);
			}
			if (!micInputListener.register()) {
				micInputListener = null;
				requestPermission(android.Manifest.permission.RECORD_AUDIO);
			}
		}
	}

	void apply(@NonNull ProgramBindings bindings, long now) {
		if (hasNightMode) {
			bindings.setInt(ShaderRenderer.UNIFORM_NIGHT_MODE, nightMode);
		}
		if (hasNotificationCount) {
			bindings.setInt(
					ShaderRenderer.UNIFORM_NOTIFICATION_COUNT,
					NotificationService.getCount());
		}
		if (hasLastNotificationTime) {
			Long lastTime = NotificationService.getLastNotificationTime();
			if (lastTime == null) {
				bindings.setFloat(
						ShaderRenderer.UNIFORM_LAST_NOTIFICATION_TIME,
						Float.NaN);
			} else {
				bindings.setFloat(
						ShaderRenderer.UNIFORM_LAST_NOTIFICATION_TIME,
						(System.currentTimeMillis() - lastTime) / 1000f);
			}
		}
		if (hasBattery) {
			if (now - lastBatteryUpdate > BATTERY_UPDATE_INTERVAL) {
				batteryLevel = getBatteryLevel();
				lastBatteryUpdate = now;
			}
			bindings.setFloat(ShaderRenderer.UNIFORM_BATTERY, batteryLevel);
		}
		if (hasPowerConnected) {
			bindings.setInt(
					ShaderRenderer.UNIFORM_POWER_CONNECTED,
					ShaderEditorApp.preferences.isPowerConnected() ? 1 : 0);
		}
		if (usesDateUniforms()) {
			if (now - lastDateUpdate > DATE_UPDATE_INTERVAL) {
				Calendar calendar = Calendar.getInstance();
				if (hasDate) {
					dateTime[0] = calendar.get(Calendar.YEAR);
					dateTime[1] = calendar.get(Calendar.MONTH);
					dateTime[2] = calendar.get(Calendar.DAY_OF_MONTH);
					dateTime[3] = calendar.get(Calendar.HOUR_OF_DAY) * 3600f +
							calendar.get(Calendar.MINUTE) * 60f +
							calendar.get(Calendar.SECOND);
				}
				if (hasDaytime) {
					daytime[0] = calendar.get(Calendar.HOUR_OF_DAY);
					daytime[1] = calendar.get(Calendar.MINUTE);
					daytime[2] = calendar.get(Calendar.SECOND);
				}
				lastDateUpdate = now;
			}
			if (hasDate) {
				bindings.setFloat4(ShaderRenderer.UNIFORM_DATE, dateTime);
			}
			if (hasDaytime) {
				bindings.setFloat3(ShaderRenderer.UNIFORM_DAYTIME, daytime);
			}
		}
		if (hasMediaVolume) {
			if (now - lastMediaVolumeUpdate > MEDIA_VOLUME_UPDATE_INTERVAL) {
				mediaVolumeLevel = getMediaVolumeLevel(context);
				lastMediaVolumeUpdate = now;
			}
			bindings.setFloat(ShaderRenderer.UNIFORM_MEDIA_VOLUME, mediaVolumeLevel);
		}
		if (hasMicAmplitude && micInputListener != null) {
			bindings.setFloat(
					ShaderRenderer.UNIFORM_MIC_AMPLITUDE,
					micInputListener.getAmplitude());
		}
		if (hasMpvAudioLevel) {
			if (now - lastMpvAudioLevelUpdate > MPV_AUDIO_LEVEL_UPDATE_INTERVAL) {
				mpvAudioLevelValue = readMpvAudioLevel();
				lastMpvAudioLevelUpdate = now;
			}
			bindings.setFloat(
					ShaderRenderer.UNIFORM_MPV_AUDIO_LEVEL,
					mpvAudioLevelValue);
		}
	}

	void release() {
		if (micInputListener != null) {
			micInputListener.unregister();
			micInputListener = null;
		}
	}

	private boolean usesNotificationUniforms() {
		return hasNotificationCount || hasLastNotificationTime;
	}

	private boolean usesDateUniforms() {
		return hasDate || hasDaytime;
	}

	private void requestPermission(@NonNull String permission) {
		if (ContextCompat.checkSelfPermission(context, permission) ==
				PackageManager.PERMISSION_GRANTED) {
			return;
		}
		if (!(context instanceof Activity activity)) {
			return;
		}
		ActivityCompat.requestPermissions(
				activity,
				new String[]{permission},
				1);
	}

	private float getBatteryLevel() {
		Intent batteryStatus = context.registerReceiver(
				null,
				new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		if (batteryStatus == null) {
			return 0;
		}

		int level = batteryStatus.getIntExtra(
				BatteryManager.EXTRA_LEVEL,
				-1);
		int scale = batteryStatus.getIntExtra(
				BatteryManager.EXTRA_SCALE,
				-1);

		return (float) level / scale;
	}

	private static float readMpvAudioLevel() {
		if (!JDSP_AUDIO_BRIDGE_FILE.exists()) {
			return 0f;
		}
		// Multi-line key=value bridge (audio_level/audio_mood/audio_peak) —
		// only audio_level feeds this uniform for now; the other two fields
		// are available in the same file if a future uniform wants them.
		try (BufferedReader reader = new BufferedReader(
				new FileReader(JDSP_AUDIO_BRIDGE_FILE))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.startsWith("audio_level=")) {
					return Float.parseFloat(line.substring(12).trim());
				}
			}
			return 0f;
		} catch (Exception e) {
			// Missing/mid-write/JDSP not running (e.g. disabled to save
			// battery) — not an error state, just means there's currently
			// nothing to react to, which is the correct fallback appearance.
			return 0f;
		}
	}

	private static float getMediaVolumeLevel(@NonNull Context context) {
		AudioManager audioManager = (AudioManager) context.getSystemService(
				Context.AUDIO_SERVICE);
		if (audioManager == null) {
			return 0;
		}
		float maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
		float currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
		if (maxVolume <= 0 || currentVolume < 0) {
			return 0;
		}
		return currentVolume / maxVolume;
	}
}