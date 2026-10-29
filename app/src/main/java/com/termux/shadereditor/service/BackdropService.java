package com.termux.shadereditor.service;

import android.app.Service;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.termux.shadereditor.widget.ShaderView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a shader file into a Surface that another app of the same UID hands over, so
 * Termux can show a live backdrop behind its terminal text with this app's full renderer
 * (multipass buffers, JDSP uniforms, FPS_CAP, ...). scope.md 2026-10-30.
 *
 * Protocol (Messenger, main thread):
 * - {@link #MSG_ATTACH} data: "surface" (Surface), "width", "height", "shader" (file path),
 *   "quality" (float render scale). Replaces any current session.
 * - {@link #MSG_RESIZE} data: "width", "height".
 * - {@link #MSG_DETACH} stops rendering and lets go of the surface.
 *
 * The shader file is re-read when its mtime changes (checked once a second).
 */
public class BackdropService extends Service {
	public static final int MSG_ATTACH = 1;
	public static final int MSG_RESIZE = 2;
	public static final int MSG_DETACH = 3;

	private static final String TAG = "BackdropService";
	private static final long RELOAD_CHECK_MS = 1000;

	private final Handler handler = new Handler(Looper.getMainLooper()) {
		@Override
		public void handleMessage(Message msg) {
			// Only apps sharing our UID (the platform-signed family) may drive this.
			if (msg.sendingUid != Process.myUid()) {
				Log.w(TAG, "ignoring message from uid " + msg.sendingUid);
				return;
			}
			Bundle data = msg.getData();
			switch (msg.what) {
				case MSG_ATTACH:
					attach(data.getParcelable("surface"),
							data.getInt("width"), data.getInt("height"),
							data.getString("shader"), data.getFloat("quality", 1f));
					break;
				case MSG_RESIZE:
					resize(data.getInt("width"), data.getInt("height"));
					break;
				case MSG_DETACH:
					detach();
					break;
				default:
					super.handleMessage(msg);
			}
		}
	};
	private final Messenger messenger = new Messenger(handler);
	private final Runnable reloadCheck = this::checkReload;

	private RemoteHolder holder;
	private BackdropView view;
	private File shaderFile;
	private long shaderMtime;
	private float quality = 1f;

	@Override
	public IBinder onBind(Intent intent) {
		return messenger.getBinder();
	}

	@Override
	public boolean onUnbind(Intent intent) {
		detach();
		return false;
	}

	@Override
	public void onDestroy() {
		detach();
		super.onDestroy();
	}

	private void attach(Surface surface, int width, int height, String shaderPath, float quality) {
		detach();
		if (surface == null || !surface.isValid() || shaderPath == null) {
			return;
		}
		this.quality = quality;
		shaderFile = new File(shaderPath);
		String src = readShader();
		if (src == null) {
			Log.w(TAG, "cannot read " + shaderPath);
			return;
		}
		holder = new RemoteHolder(surface, width, height);
		view = new BackdropView();
		view.setFragmentShader(src, quality);
		holder.dispatchCreated();
		holder.dispatchChanged();
		view.onResume();
		handler.postDelayed(reloadCheck, RELOAD_CHECK_MS);
	}

	private void resize(int width, int height) {
		if (holder != null) {
			holder.width = width;
			holder.height = height;
			holder.dispatchChanged();
		}
	}

	private void detach() {
		handler.removeCallbacks(reloadCheck);
		if (view != null) {
			view.onPause();
			if (holder != null) {
				holder.dispatchDestroyed();
			}
			view.destroy();
			view = null;
		}
		holder = null;
	}

	private void checkReload() {
		if (view == null || shaderFile == null) {
			return;
		}
		if (shaderFile.lastModified() != shaderMtime) {
			String src = readShader();
			if (src != null) {
				view.setFragmentShader(src, quality);
			}
		}
		handler.postDelayed(reloadCheck, RELOAD_CHECK_MS);
	}

	private String readShader() {
		try (InputStream in = new FileInputStream(shaderFile)) {
			shaderMtime = shaderFile.lastModified();
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			byte[] buffer = new byte[16384];
			for (int n; (n = in.read(buffer)) > 0; ) {
				out.write(buffer, 0, n);
			}
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			return null;
		}
	}

	/** A ShaderView drawing into the remote surface (same trick as the wallpaper engine). */
	private class BackdropView extends ShaderView {
		BackdropView() {
			super(BackdropService.this, GLSurfaceView.RENDERMODE_CONTINUOUSLY);
		}

		@Override
		public final SurfaceHolder getHolder() {
			// Called from GLSurfaceView's constructor, so this must not depend on our own fields.
			return BackdropService.this.holder;
		}

		void destroy() {
			super.onDetachedFromWindow();
		}
	}

	/** Minimal SurfaceHolder around a Surface received over binder. */
	private static class RemoteHolder implements SurfaceHolder {
		private final Surface surface;
		private final List<Callback> callbacks = new ArrayList<>();
		int width;
		int height;

		RemoteHolder(Surface surface, int width, int height) {
			this.surface = surface;
			this.width = width;
			this.height = height;
		}

		void dispatchCreated() {
			for (Callback callback : new ArrayList<>(callbacks)) {
				callback.surfaceCreated(this);
			}
		}

		void dispatchChanged() {
			for (Callback callback : new ArrayList<>(callbacks)) {
				callback.surfaceChanged(this, android.graphics.PixelFormat.RGBA_8888, width, height);
			}
		}

		void dispatchDestroyed() {
			for (Callback callback : new ArrayList<>(callbacks)) {
				callback.surfaceDestroyed(this);
			}
		}

		@Override public void addCallback(Callback callback) { if (!callbacks.contains(callback)) callbacks.add(callback); }
		@Override public void removeCallback(Callback callback) { callbacks.remove(callback); }
		@Override public boolean isCreating() { return false; }
		@Override public void setType(int type) {}
		@Override public void setFixedSize(int width, int height) {}
		@Override public void setSizeFromLayout() {}
		@Override public void setFormat(int format) {}
		@Override public void setKeepScreenOn(boolean screenOn) {}
		@Override public Canvas lockCanvas() { return null; }
		@Override public Canvas lockCanvas(Rect dirty) { return null; }
		@Override public void unlockCanvasAndPost(Canvas canvas) {}
		@Override public Rect getSurfaceFrame() { return new Rect(0, 0, width, height); }
		@Override public Surface getSurface() { return surface; }
	}
}
