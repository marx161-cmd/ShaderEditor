package com.termux.shadereditor.receiver;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.termux.shadereditor.app.ShaderEditorApp;
import com.termux.shadereditor.database.DataRecords;
import com.termux.shadereditor.database.Database;
import com.termux.shadereditor.service.ShaderWallpaperService;

/**
 * Switch or reload the wallpaper shader from a shell (scope.md 2026-10-23, v112), no editor taps:
 *   am broadcast -n com.termux.shadereditor/.receiver.WallpaperControlReceiver \
 *       -a com.termux.shadereditor.action.SET_WALLPAPER --es shader diorama_ships_60fps
 *   am broadcast -n com.termux.shadereditor/.receiver.WallpaperControlReceiver \
 *       -a com.termux.shadereditor.action.RELOAD_WALLPAPER
 * "shader" = a file name in ~/shaders (".glsl" optional); "--el id <n>" works too. RELOAD re-reads
 * the current wallpaper's file. If ShaderEditor is not the live wallpaper (an app install resets it),
 * it makes itself the live wallpaper first. Senders need WRITE_SECURE_SETTINGS (adb shell, UID 1000 = Termux).
 */
public class WallpaperControlReceiver extends BroadcastReceiver {
	public static final String ACTION_SET =
			"com.termux.shadereditor.action.SET_WALLPAPER";
	public static final String ACTION_RELOAD =
			"com.termux.shadereditor.action.RELOAD_WALLPAPER";
	private static final String TAG = "WallpaperControl";

	@Override
	public void onReceive(Context context, Intent intent) {
		String action = intent.getAction();
		long current = ShaderEditorApp.preferences.getWallpaperShader();
		long id;
		if (ACTION_SET.equals(action)) {
			id = intent.getLongExtra("id", -1);
			String name = intent.getStringExtra("shader");
			if (name != null) {
				id = findByName(context, name);
				if (id < 1) {
					Log.w(TAG, "no shader named " + name);
					result(1);
					return;
				}
			}
		} else if (ACTION_RELOAD.equals(action)) {
			id = current;
		} else {
			return;
		}
		if (id < 1) {
			Log.w(TAG, "no shader id");
			result(1);
			return;
		}
		if (id != current) {
			ShaderEditorApp.preferences.setWallpaperShader(id);   // the engine's listener loads it
		} else {
			ShaderWallpaperService.reloadShader();
		}
		if (!ShaderWallpaperService.isRunning()) {
			becomeLiveWallpaper(context);   // e.g. after an app install reset it to the image
		}
		Log.i(TAG, action.substring(action.lastIndexOf('.') + 1) + " -> shader " + id);
		result(0);
	}

	// Only ordered broadcasts carry a result (Termux's am sends unordered ones).
	private void result(int code) {
		if (isOrderedBroadcast()) {
			setResultCode(code);
		}
	}

	// Hidden system API; ShaderEditor is ROM-signed UID 1000 (SET_WALLPAPER_COMPONENT).
	private static void becomeLiveWallpaper(Context context) {
		try {
			WallpaperManager wm = WallpaperManager.getInstance(context);
			wm.getClass()
					.getMethod("setWallpaperComponent", ComponentName.class)
					.invoke(wm, new ComponentName(context, ShaderWallpaperService.class));
			Log.i(TAG, "set ShaderEditor as the live wallpaper");
		} catch (Exception e) {
			Log.w(TAG, "could not become the live wallpaper (pick it once in the editor)", e);
		}
	}

	private static long findByName(Context context, String name) {
		String want = name.endsWith(".glsl")
				? name.substring(0, name.length() - 5)
				: name;
		for (DataRecords.ShaderInfo info : Database.getInstance(context)
				.getDataSource().shader.getShaders(false)) {
			if (want.equals(info.name())) {
				return info.id();
			}
		}
		return -1;
	}
}
