package com.termux.shadereditor.opengl;

import com.termux.shadereditor.beat.BeatClock;
import com.termux.shadereditor.beat.MusicState;

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
import java.io.FileInputStream;
import java.io.FileReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
	// JDSP 108-band bridge (bands.bin, see JdspBandsFeed): polled by sequence
	// number so every 60 Hz frame is seen once. Three R32F samplers, each
	// only uploaded when a program declares it:
	//   bands       108x1  RAW linear RMS per semitone (unchanged meaning, so
	//                      existing phone shaders keep their tuning)
	//   bands_db    108x1  cooked frame = the desktop feed's `bands` (norm_db
	//                      0..1 over -60..0 dBFS, enveloped)
	//   bands_hist  108x32 raw history, row 0 newest, 60 Hz rows
	// texture2D(bands, vec2((float(i)+0.5)/108.0, 0.5)).r, or texelFetch in ES3.
	// Plus the desktop's audio feature uniforms (u_rms .. u_onset, u_onset_smooth,
	// u_onset_accum, u_bass_accum), conditioned exactly as renderer.py does.
	private GlDevice device;
	private final JdspBandsFeed bandsFeed = new JdspBandsFeed();
	private GlTexture2D bandsTexture;
	private GlTexture2D bandsDbTexture;
	private GlTexture2D bandsHistTexture;
	private boolean hasBands;
	private boolean hasBandsDb;
	private boolean hasBandsHist;
	private boolean hasAudioFeatures;
	private boolean bandsDirty;
	private long lastFeatureNanos;
	private float onsetSmooth;
	private float onsetAccum;
	private float bassAccum;
	private static final float ONSET_SMOOTH_TAU = 0.15f;  // renderer.py ONSET_SMOOTH_TAU
	private static final float SHAPE_EXP = 3.0f;          // renderer.py SHAPE_EXP (bass accum)
	// Shared beat clock, held only while a shader uses a beat uniform.
	private boolean hasBeat;
	// u_ms_* (beat/MusicState.java, same names + meanings as shader-backdrop music_state.py)
	private static final String[] MUSIC_UNIFORMS = {"u_ms_source", "u_ms_cur_a", "u_ms_cur_b",
			"u_ms_sec_a", "u_ms_sec_b", "u_ms_next_a", "u_ms_next_b", "u_ms_change",
			"u_ms_change_age", "u_ms_next_in", "u_ms_drop_in", "u_ms_bar_phase",
			"u_ms_repeat", "u_ms_song_pos"};
	private boolean hasMusic;
	// Written from the UI thread (release on pause) and the GL thread
	// (configure/apply), so volatile; apply() re-acquires after a pause,
	// because a resume that keeps the GL surface never re-runs configure().
	@Nullable
	private volatile BeatClock beatClock;

	BuiltinSystemUniforms(@NonNull Context context) {
		this.context = context;
	}

	void configure(
			@NonNull GlDevice device,
			@NonNull List<GlProgram> programs) {
		this.device = device;
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
		hasBands = anyHasUniform(device, programs, "bands");
		hasBandsDb = anyHasUniform(device, programs, "bands_db");
		hasBandsHist = anyHasUniform(device, programs, "bands_hist");
		hasAudioFeatures = false;
		for (String name : JdspBandsFeed.FEATURE_NAMES) {
			hasAudioFeatures |= anyHasUniform(device, programs, "u_" + name);
		}
		hasAudioFeatures |= anyHasUniform(device, programs, "u_onset_smooth")
				|| anyHasUniform(device, programs, "u_onset_accum")
				|| anyHasUniform(device, programs, "u_bass_accum");
		bandsDirty = true;
		lastFeatureNanos = 0L;
		onsetSmooth = 0f;
		onsetAccum = 0f;
		bassAccum = 0f;
		hasBeat = false;
		for (String name : new String[]{
				ShaderRenderer.UNIFORM_BEAT_COUNT,
				ShaderRenderer.UNIFORM_BEAT_PHASE,
				ShaderRenderer.UNIFORM_BEAT_CONF,
				ShaderRenderer.UNIFORM_BEAT_BPM,
				ShaderRenderer.UNIFORM_BEAT_SOURCE}) {
			hasBeat |= anyHasUniform(device, programs, name);
		}
		// music-state layer (beat/MusicState.java): needs the clock too
		hasMusic = false;
		for (String name : MUSIC_UNIFORMS) {
			hasMusic |= anyHasUniform(device, programs, name);
		}
		hasBeat |= hasMusic;
		if (hasBeat && beatClock == null) {
			beatClock = BeatClock.acquire(context);
		} else if (!hasBeat && beatClock != null) {
			BeatClock.release();
			beatClock = null;
		}

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
		applyBands(bindings, now);
		if (hasBeat && beatClock == null) {
			beatClock = BeatClock.acquire(context);
		}
		BeatClock clock = beatClock;
		if (clock != null) {
			BeatClock.State beat = clock.state();
			double count = beat.countAt(now);
			bindings.setFloat(ShaderRenderer.UNIFORM_BEAT_COUNT, (float) count);
			bindings.setFloat(ShaderRenderer.UNIFORM_BEAT_PHASE,
					(float) (count - Math.floor(count)));
			bindings.setFloat(ShaderRenderer.UNIFORM_BEAT_CONF, (float) beat.conf);
			bindings.setFloat(ShaderRenderer.UNIFORM_BEAT_BPM, (float) beat.bpm);
			bindings.setInt(ShaderRenderer.UNIFORM_BEAT_SOURCE, beat.source);
			if (hasMusic) {
				MusicState.Snapshot m = clock.music();
				bindings.setFloat("u_ms_source", m.source);
				bindings.setFloat4("u_ms_cur_a", m.curA);
				bindings.setFloat4("u_ms_cur_b", m.curB);
				bindings.setFloat4("u_ms_sec_a", m.secA);
				bindings.setFloat4("u_ms_sec_b", m.secB);
				bindings.setFloat4("u_ms_next_a", m.nextA);
				bindings.setFloat4("u_ms_next_b", m.nextB);
				bindings.setFloat("u_ms_change", m.change);
				bindings.setFloat("u_ms_change_age", m.changeAge(now));
				bindings.setFloat("u_ms_next_in", m.nextIn);
				bindings.setFloat("u_ms_drop_in", m.dropIn);
				bindings.setFloat("u_ms_bar_phase", m.barPhase);
				bindings.setFloat("u_ms_repeat", m.repeat);
				bindings.setFloat("u_ms_song_pos", m.songPos);
			}
		}
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
			float amp = micInputListener.getAmplitude();
			bindings.setFloat(ShaderRenderer.UNIFORM_MIC_AMPLITUDE, amp);
			BeatClock micClock = beatClock;
			if (micClock != null) {
				micClock.feedMic(amp, now); // tap tempo's silence reset
			}
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
		if (beatClock != null) {
			BeatClock.release();
			beatClock = null;
		}
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

	private void applyBands(@NonNull ProgramBindings bindings, long now) {
		if (!(hasBands || hasBandsDb || hasBandsHist || hasAudioFeatures) || device == null) {
			return;
		}
		// One mmap'd int per frame; uploads only when the engine published.
		if (bandsFeed.poll(now)) {
			bandsDirty = true;
		}
		if (bandsDirty) {
			bandsDirty = false;
			if (hasBands) {
				if (bandsTexture == null) {
					bandsTexture = device.createTexture2D();
				}
				device.updateFloatTexture2D(bandsTexture, bandsFeed.raw, JdspBandsFeed.N, 1);
			}
			if (hasBandsDb) {
				if (bandsDbTexture == null) {
					bandsDbTexture = device.createTexture2D();
				}
				device.updateFloatTexture2D(bandsDbTexture, bandsFeed.cooked, JdspBandsFeed.N, 1);
			}
			if (hasBandsHist) {
				if (bandsHistTexture == null) {
					bandsHistTexture = device.createTexture2D();
				}
				device.updateFloatTexture2D(bandsHistTexture, bandsFeed.hist,
						JdspBandsFeed.N, JdspBandsFeed.HIST);
			}
		}
		if (hasBands) {
			bindings.setTexture("bands", bandsTexture);
		}
		if (hasBandsDb) {
			bindings.setTexture("bands_db", bandsDbTexture);
		}
		if (hasBandsHist) {
			bindings.setTexture("bands_hist", bandsHistTexture);
		}
		if (hasAudioFeatures) {
			float dt = lastFeatureNanos == 0L ? 0f
					: Math.min(0.1f, (now - lastFeatureNanos) / 1e9f);
			lastFeatureNanos = now;
			float[] f = bandsFeed.features;
			// renderer.py: loudness features re-mapped from the plugin's -60 dB
			// floor to -40 dB (y = 1.5x - 0.5), centroid log-mapped over
			// 20 Hz..16 kHz, flux/onset passed through.
			for (int i = 0; i < JdspBandsFeed.FEATURES; i++) {
				String name = JdspBandsFeed.FEATURE_NAMES[i];
				float v = f[i];
				if (i <= 7) {
					v = Math.min(1f, Math.max(0f, 1.5f * v - 0.5f));
				} else if (i == 8) {
					double hz = Math.max(20.0, v * 28160.0);
					v = (float) Math.min(1.0, Math.log(hz / 20.0) / Math.log(16000.0 / 20.0));
				}
				bindings.setFloat("u_" + name, v);
			}
			float onset = f[10];
			onsetSmooth += (onset - onsetSmooth) * Math.min(1f, dt / ONSET_SMOOTH_TAU);
			onsetAccum += onset * dt;
			bassAccum += (float) Math.pow(f[2], SHAPE_EXP) * dt;
			bindings.setFloat("u_onset_smooth", onsetSmooth);
			bindings.setFloat("u_onset_accum", onsetAccum);
			bindings.setFloat("u_bass_accum", bassAccum);
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