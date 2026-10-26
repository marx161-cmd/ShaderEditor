package com.termux.shadereditor.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.termux.shadereditor.service.ShaderControls;

/** A button of the CONTROL NOTIFICATION was pressed (v116): count it. Not exported. */
public class ControlButtonReceiver extends BroadcastReceiver {
	public static final String EXTRA_INDEX = "index";

	@Override
	public void onReceive(Context context, Intent intent) {
		int i = intent.getIntExtra(EXTRA_INDEX, -1);
		ShaderControls.press(context, i);
		Log.i("ShaderControls", "button " + i + " -> " + (i >= 0 && i < ShaderControls.MAX
				? ShaderControls.count(i) : -1));
	}
}
