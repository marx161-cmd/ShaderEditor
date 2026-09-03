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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

	// reactive.lua only writes the bridge file while an actual local audio
	// session is active; if nothing is playing through the phone itself
	// (e.g. audio is coming from a different device entirely) the file just
	// sits there holding whatever it last wrote, indefinitely. Content alone
	// can't tell a genuinely-live low reading from a session that ended ten
	// minutes ago, so gate on the file's age too -- a few ticks past
	// reactive.lua's own ~30Hz write rate is enough margin for normal write
	// jitter without masking a truly dead file.
	private static final long AUDIO_BRIDGE_STALE_THRESHOLD_MS = 150L;

	// Mirrors the JDSP/mic arbitration threshold used shader-side (see
	// getEffectiveAudio() in the wallpaper shader) so the mic only runs
	// while JDSP is genuinely stale/silent -- leaving AudioRecord open the
	// whole time wastes battery once real JDSP signal is flowing again.
	private static final float JDSP_ACTIVE_THRESHOLD = 0.01f;
	// Checked at 1Hz, not every frame -- this interval alone acts as a
	// simple debounce so the mic doesn't rapidly open/close right at the
	// threshold.
	private static final long MIC_GATE_CHECK_INTERVAL = 1000000000L; // 1s

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
	private boolean hasBatteryTemp;
	private boolean hasPowerConnected;
	private boolean hasDate;
	private boolean hasDaytime;
	private boolean hasMediaVolume;
	private boolean hasMicAmplitude;
	private boolean hasMpvAudioLevel;
	private boolean hasScreenLocked;
	// Generic bridge: every key=value line in the JDSP audio bridge file
	// becomes its own same-named uniform IF the current shader declares it
	// -- no Kotlin/rebuild needed to add a new one, unlike hasMpvAudioLevel
	// above (kept as its own special case for backward compat: it reads the
	// "audio_level" key under the different legacy uniform name
	// "mpvAudioLevel", so existing shaders don't break). Discovered once
	// per shader compile in configure(), since a program's declared
	// uniforms never change after that.
	private final Map<String, Boolean> bridgeUniformPresence = new HashMap<>();
	private final Map<String, Float> bridgeValues = new HashMap<>();
	private long lastBridgeUpdate;
	private long lastMicGateCheck;
	private int nightMode;
	private long lastBatteryUpdate;
	private long lastBatteryTempUpdate;
	private long lastDateUpdate;
	private long lastMediaVolumeUpdate;
	private long lastMpvAudioLevelUpdate;
	private float batteryLevel;
	private float batteryTemp;
	private float mediaVolumeLevel;
	private float mpvAudioLevelValue;

	BuiltinSystemUniforms(@NonNull Context context) {
		this.context = context;
	}

	void configure(
			@NonNull GlDevice device,
			@NonNull List<GlProgram> programs) {
		lastBatteryUpdate = 0L;
		lastBatteryTempUpdate = 0L;
		lastDateUpdate = 0L;
		lastMediaVolumeUpdate = 0L;
		lastMpvAudioLevelUpdate = 0L;
		lastBridgeUpdate = 0L;
		hasNightMode = anyHasUniform(
				device,
				programs,
				ShaderRenderer.UNIFORM_NIGHT_MODE);
		hasNotificationCount = anyHasUniform(
				device,
				programs,
				ShaderRenderer.UNIFORM_NOTIFICATION_COUNT);
		hasLastNotificationTime = anyHasUniform(
				device,
				programs,
				ShaderRenderer.UNIFORM_LAST_NOTIFICATION_TIME);
		hasBattery = anyHasUniform(
				device,
				programs,
				ShaderRenderer.UNIFORM_BATTERY);
		hasBatteryTemp = anyHasUniform(
				device,
				programs,
				ShaderRenderer.UNIFORM_BATTERY_TEMP);
		hasPowerConnected = anyHasUniform(
				device,
				programs,
				ShaderRenderer.UNIFORM_POWER_CONNECTED);
		hasDate = anyHasUniform(
				device,
				programs,
				ShaderRenderer.UNIFORM_DATE);
		hasDaytime = anyHasUniform(
				device,
				programs,
				ShaderRenderer.UNIFORM_DAYTIME);
		hasMediaVolume = anyHasUniform(
				device,
				programs,
				ShaderRenderer.UNIFORM_MEDIA_VOLUME);
		hasMicAmplitude = anyHasUniform(
				device,
				programs,
				ShaderRenderer.UNIFORM_MIC_AMPLITUDE);
		hasMpvAudioLevel = anyHasUniform(
				device,
				programs,
				ShaderRenderer.UNIFORM_MPV_AUDIO_LEVEL);
		hasScreenLocked = anyHasUniform(
				device,
				programs,
				ShaderRenderer.UNIFORM_SCREEN_LOCKED);

		bridgeUniformPresence.clear();
		for (String key : readAudioBridge().keySet()) {
			bridgeUniformPresence.put(
					key,
					anyHasUniform(device, programs, key));
		}

		if (hasNightMode) {
			nightMode = (context.getResources().getConfiguration().uiMode &
					Configuration.UI_MODE_NIGHT_MASK) ==
					Configuration.UI_MODE_NIGHT_YES ? 1 : 0;
		}

		if (usesNotificationUniforms()) {
			NotificationService.requirePermissions(context);
		}

		if (!hasMicAmplitude && micInputListener != null) {
			micInputListener.unregister();
			micInputListener = null;
		}
		// Force an immediate gating check on the next apply() rather than
		// waiting up to MIC_GATE_CHECK_INTERVAL -- a fresh shader compile
		// (e.g. screen on/off, wallpaper rebind) is exactly the moment the
		// JDSP-vs-mic decision should be re-evaluated, not delayed.
		lastMicGateCheck = 0L;
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
		if (hasBatteryTemp) {
			if (now - lastBatteryTempUpdate > BATTERY_UPDATE_INTERVAL) {
				batteryTemp = getBatteryTemperature();
				lastBatteryTempUpdate = now;
			}
			bindings.setFloat(ShaderRenderer.UNIFORM_BATTERY_TEMP, batteryTemp);
		}
		if (hasPowerConnected) {
			bindings.setInt(
					ShaderRenderer.UNIFORM_POWER_CONNECTED,
					ShaderEditorApp.preferences.isPowerConnected() ? 1 : 0);
		}
		if (hasScreenLocked) {
			bindings.setInt(
					ShaderRenderer.UNIFORM_SCREEN_LOCKED,
					ShaderEditorApp.preferences.isScreenLocked() ? 1 : 0);
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
		if (hasMicAmplitude) {
			updateMicGating(now);
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
		if (!bridgeUniformPresence.isEmpty()) {
			if (now - lastBridgeUpdate > MPV_AUDIO_LEVEL_UPDATE_INTERVAL) {
				bridgeValues.clear();
				bridgeValues.putAll(readAudioBridge());
				lastBridgeUpdate = now;
			}
			for (Map.Entry<String, Boolean> entry : bridgeUniformPresence.entrySet()) {
				if (!entry.getValue()) {
					continue;
				}
				Float value = bridgeValues.get(entry.getKey());
				if (value != null) {
					bindings.setFloat(entry.getKey(), value);
				}
			}
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

	// Keeps the mic open only while JDSP has no genuine signal to offer,
	// matching the shader-side fallback arbitration -- so RECORD_AUDIO
	// isn't held for the entire time the wallpaper is showing, only for the
	// moments it's actually needed.
	private void updateMicGating(long now) {
		if (now - lastMicGateCheck < MIC_GATE_CHECK_INTERVAL) {
			return;
		}
		lastMicGateCheck = now;

		Map<String, Float> bridge = readAudioBridge();
		Float bass = bridge.get("audio_bass");
		Float treble = bridge.get("audio_treble");
		float bassLevel = bass == null ? 0f : bass;
		float trebleLevel = treble == null ? 0f : treble;
		boolean jdspActive = Math.max(bassLevel, trebleLevel) > JDSP_ACTIVE_THRESHOLD;

		if (jdspActive) {
			if (micInputListener != null) {
				micInputListener.unregister();
				micInputListener = null;
			}
			return;
		}

		if (micInputListener == null) {
			micInputListener = new MicInputListener(context);
			if (!micInputListener.register()) {
				micInputListener = null;
				requestPermission(android.Manifest.permission.RECORD_AUDIO);
			}
		}
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

	// EXTRA_TEMPERATURE is tenths of a degree Celsius (Android convention,
	// e.g. 293 == 29.3°C) -- divide down to plain °C for shader use.
	private float getBatteryTemperature() {
		Intent batteryStatus = context.registerReceiver(
				null,
				new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		if (batteryStatus == null) {
			return 0f;
		}

		int tenthsCelsius = batteryStatus.getIntExtra(
				BatteryManager.EXTRA_TEMPERATURE,
				0);

		return tenthsCelsius / 10f;
	}

	private static float readMpvAudioLevel() {
		Float level = readAudioBridge().get("audio_level");
		return level == null ? 0f : level;
	}

	// Parses every "key=value" line in the JDSP audio bridge file into a
	// map, key names exactly as reactive.lua writes them (audio_level,
	// audio_bass, audio_treble, ...). Whatever reactive.lua writes shows up
	// here automatically -- adding a new field on the Lua side needs no
	// Kotlin change, only a shader that declares a same-named uniform.
	@NonNull
	private static Map<String, Float> readAudioBridge() {
		Map<String, Float> values = new HashMap<>();
		if (!JDSP_AUDIO_BRIDGE_FILE.exists()) {
			return values;
		}
		long age = System.currentTimeMillis() - JDSP_AUDIO_BRIDGE_FILE.lastModified();
		if (age > AUDIO_BRIDGE_STALE_THRESHOLD_MS) {
			return values;
		}
		try (BufferedReader reader = new BufferedReader(
				new FileReader(JDSP_AUDIO_BRIDGE_FILE))) {
			String line;
			while ((line = reader.readLine()) != null) {
				int eq = line.indexOf('=');
				if (eq <= 0) {
					continue;
				}
				String key = line.substring(0, eq).trim();
				String valueText = line.substring(eq + 1).trim();
				try {
					values.put(key, Float.parseFloat(valueText));
				} catch (NumberFormatException ignored) {
					// A mid-write partial line -- skip it, next read (30Hz
					// later) will have the complete value.
				}
			}
		} catch (Exception e) {
			// Missing/mid-write/JDSP not running (e.g. disabled to save
			// battery) — not an error state, just means there's currently
			// nothing to react to, which is the correct fallback appearance.
		}
		return values;
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

	private static boolean anyHasUniform(
			@NonNull GlDevice device,
			@NonNull List<GlProgram> programs,
			@NonNull String name) {
		for (GlProgram program : programs) {
			if (device.hasUniform(program, name)) {
				return true;
			}
		}
		return false;
	}
}