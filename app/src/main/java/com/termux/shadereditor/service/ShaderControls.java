package com.termux.shadereditor.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.termux.shadereditor.R;
import com.termux.shadereditor.receiver.ControlButtonReceiver;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CONTROL NOTIFICATION (v116, ShaderEditor scope.md 2026-10-24): a wallpaper shader declares buttons,
 * one per line, in source order (max 8):
 *   #define BUTTON_&lt;id&gt; &lt;label text&gt;
 * While it is the live wallpaper, an ongoing notification shows them. A press adds 1 to that button's
 * counter: the shader reads it as "uniform int u_btn_&lt;id&gt;", and a FEED shader's return file carries
 * the counters (header bytes 32..63, u32 per button in declaration order) to its Termux brain.
 * The brain may write ~/.shader/feeds/&lt;feed&gt;.status: line 1 = the header text, the rest = the
 * expanded text; re-read every second while the wallpaper is visible.
 */
public final class ShaderControls {
	public static final int MAX = 8;
	private static final Pattern PATTERN_BUTTON = Pattern.compile(
			"^#define[ \\t]+BUTTON_([A-Za-z_0-9]+)[ \\t]+(.+)$",
			Pattern.MULTILINE);
	private static final Pattern PATTERN_FEED = Pattern.compile(
			"^#define[ \\t]+FEED[ \\t]+([A-Za-z0-9_.-]+)[ \\t]*$",
			Pattern.MULTILINE);
	private static final String CHANNEL = "shader_controls";
	private static final int NOTIFICATION_ID = 0x5c01;
	private static final long POLL_MS = 1000;
	private static final File FEED_DIR = new File("/data/data/com.termux/files/home/.shader/feeds");
	private static final int[] BUTTON_VIEWS = {R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3,
			R.id.btn4, R.id.btn5, R.id.btn6, R.id.btn7};

	private static final AtomicIntegerArray counts = new AtomicIntegerArray(MAX);
	private static volatile String[] uniforms = new String[0];
	private static final Handler handler = new Handler(Looper.getMainLooper());

	// main thread only
	private static Context appContext;
	private static String[] labels = new String[0];
	private static String feed;
	private static String status = "";
	private static boolean visible;
	private static final Runnable poll = ShaderControls::poll;

	private ShaderControls() {
	}

	/** "u_btn_<id>" per declared button (GL thread). */
	@NonNull
	public static String[] uniformNames() {
		return uniforms;
	}

	public static int count(int i) {
		return counts.get(i);
	}

	public static void press(@NonNull Context context, int i) {
		if (i < 0 || i >= MAX) {
			return;
		}
		counts.incrementAndGet(i);
		handler.removeCallbacks(poll);
		handler.postDelayed(poll, 400);       // the brain's answer, soon
	}

	/** The live wallpaper's source changed (main thread). No BUTTON_ lines = no notification. */
	public static void update(@NonNull Context context, @Nullable String src) {
		appContext = context.getApplicationContext();
		List<String> ids = new ArrayList<>();
		List<String> texts = new ArrayList<>();
		String feedName = null;
		if (src != null) {
			Matcher m = PATTERN_BUTTON.matcher(src);
			while (m.find() && ids.size() < MAX) {
				String label = m.group(2);
				int c = label.indexOf("//");
				if (c >= 0) {
					label = label.substring(0, c);
				}
				ids.add(m.group(1));
				texts.add(label.trim().replace('_', ' '));
			}
			Matcher f = PATTERN_FEED.matcher(src);
			if (f.find()) {
				feedName = f.group(1);
			}
		}
		String[] names = new String[ids.size()];
		for (int i = 0; i < names.length; ++i) {
			names[i] = "u_btn_" + ids.get(i);
		}
		uniforms = names;
		labels = texts.toArray(new String[0]);
		feed = feedName;
		status = readStatus();
		if (labels.length == 0) {
			hide(context);
			return;
		}
		post();
		schedule();
	}

	public static void hide(@NonNull Context context) {
		handler.removeCallbacks(poll);
		labels = new String[0];
		uniforms = new String[0];
		NotificationManager nm = context.getSystemService(NotificationManager.class);
		if (nm != null) {
			nm.cancel(NOTIFICATION_ID);
		}
	}

	public static void setVisible(boolean v) {
		visible = v;
		schedule();
	}

	private static void schedule() {
		handler.removeCallbacks(poll);
		if (visible && labels.length > 0 && feed != null) {
			handler.postDelayed(poll, POLL_MS);
		}
	}

	private static void poll() {
		if (labels.length == 0) {
			return;
		}
		String s = readStatus();
		if (!s.equals(status)) {
			status = s;
			post();
		}
		schedule();
	}

	@NonNull
	private static String readStatus() {
		if (feed == null) {
			return "";
		}
		File f = new File(FEED_DIR, feed + ".status");
		byte[] b = new byte[1024];
		try (FileInputStream in = new FileInputStream(f)) {
			int n = in.read(b);
			return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8).trim() : "";
		} catch (Exception e) {
			return "";
		}
	}

	private static void post() {
		Context ctx = appContext;
		if (ctx == null || labels.length == 0) {
			return;
		}
		NotificationManager nm = ctx.getSystemService(NotificationManager.class);
		if (nm == null) {
			return;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			nm.createNotificationChannel(new NotificationChannel(CHANNEL,
					"Shader controls", NotificationManager.IMPORTANCE_LOW));
		}
		int nl = status.indexOf('\n');
		String head = nl < 0 ? status : status.substring(0, nl);
		String body = nl < 0 ? "" : status.substring(nl + 1).trim();
		NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
				.setSmallIcon(R.drawable.ic_wallpaper)
				.setOngoing(true)
				.setOnlyAlertOnce(true)
				.setShowWhen(false)
				.setSilent(true)
				.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)   // buttons on the lock screen too
				.setCategory(NotificationCompat.CATEGORY_STATUS)
				.setStyle(new NotificationCompat.DecoratedCustomViewStyle())
				.setCustomContentView(views(ctx, null))
				.setCustomBigContentView(views(ctx, body.isEmpty() ? null : body));
		if (!head.isEmpty()) {
			b.setSubText(head);
			b.setContentTitle(head);
		}
		// v119 (2026-10-26, ringbox scope.md): an ONGOING notification with a progress value is promoted into the
		// crDroid dynamic-bar ISLAND, which then draws this notification's own button layout when expanded.
		// The value = the first "NN %" in the status header (ringbox: the ring level), else full.
		java.util.regex.Matcher pm = java.util.regex.Pattern.compile("(\\d{1,3})\\s*%").matcher(head);
		int pct = pm.find() ? Math.min(100, Integer.parseInt(pm.group(1))) : 100;
		b.setProgress(100, pct, false);
		nm.notify(NOTIFICATION_ID, b.build());
	}

	@NonNull
	private static RemoteViews views(@NonNull Context ctx, @Nullable String body) {
		RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.notification_controls);
		if (body != null) {
			v.setTextViewText(R.id.status, body);
			v.setViewVisibility(R.id.status, View.VISIBLE);
		}
		for (int i = 0; i < MAX; ++i) {
			if (i >= labels.length) {
				v.setViewVisibility(BUTTON_VIEWS[i], View.GONE);
				continue;
			}
			v.setViewVisibility(BUTTON_VIEWS[i], View.VISIBLE);
			v.setTextViewText(BUTTON_VIEWS[i], labels[i]);
			Intent intent = new Intent(ctx, ControlButtonReceiver.class)
					.putExtra(ControlButtonReceiver.EXTRA_INDEX, i);
			v.setOnClickPendingIntent(BUTTON_VIEWS[i], PendingIntent.getBroadcast(ctx, i, intent,
					PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
		}
		return v;
	}
}
