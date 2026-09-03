package com.termux.shadereditor.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.termux.shadereditor.app.ShaderEditorApp;

public class ScreenLockReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		String action = intent.getAction();

		if (Intent.ACTION_SCREEN_OFF.equals(action)) {
			// Screen off implies the keyguard (if any) is showing — there's
			// no "screen off but still unlocked" state to distinguish here.
			ShaderEditorApp.preferences.setScreenLocked(true);
		} else if (Intent.ACTION_USER_PRESENT.equals(action)) {
			// Fires specifically on keyguard dismissal — SCREEN_ON alone
			// does NOT mean unlocked, the lock screen shows first.
			ShaderEditorApp.preferences.setScreenLocked(false);
		}
	}
}
