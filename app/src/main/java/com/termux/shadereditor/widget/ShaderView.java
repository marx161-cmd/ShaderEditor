package com.termux.shadereditor.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

import com.termux.shadereditor.opengl.ShaderRenderer;

public class ShaderView extends GLSurfaceView {
	// Per-shader frame-rate cap: "#define FPS_CAP 30" (scope.md 2026-10-23, v111).
	private static final Pattern PATTERN_FPS_CAP = Pattern.compile(
			"^#define[ \\t]+FPS_CAP[ \\t]+([0-9]+)[ \\t]*$",
			Pattern.MULTILINE);
	// Keep the GL context (all buffers) while hidden: "#define KEEP_STATE" (v112). Value 0 = off.
	private static final Pattern PATTERN_KEEP_STATE = Pattern.compile(
			"^#define[ \\t]+KEEP_STATE(?:[ \\t]+([0-9]+))?[ \\t]*$",
			Pattern.MULTILINE);
	// SIM FEED (v113): "#define FEED <name>" + one "#define FEED_EXPORT_<image>" per image to send back.
	private static final Pattern PATTERN_FEED = Pattern.compile(
			"^#define[ \\t]+FEED[ \\t]+([A-Za-z0-9_.-]+)[ \\t]*$",
			Pattern.MULTILINE);
	private static final Pattern PATTERN_FEED_EXPORT = Pattern.compile(
			"^#define[ \\t]+FEED_EXPORT_([A-Za-z_][A-Za-z0-9_]*)\\b",
			Pattern.MULTILINE);
	private static final long NS_PER_SECOND = 1000000000L;
	private static final long DEFAULT_VSYNC_NS = NS_PER_SECOND / 120;

	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final Choreographer.FrameCallback pacer = this::pace;
	private ShaderRenderer renderer;
	private int requestedRenderMode = RENDERMODE_CONTINUOUSLY;
	private volatile int fpsCap;
	private boolean resumed;
	private boolean pacing;
	private long lastVsyncNs;
	private long lastPacedNs;
	private long vsyncNs = DEFAULT_VSYNC_NS;

	public ShaderView(Context context, int renderMode) {
		super(context);
		init(context, renderMode);
	}

	public ShaderView(Context context) {
		super(context);
		init(context, GLSurfaceView.RENDERMODE_CONTINUOUSLY);
	}

	public ShaderView(Context context, AttributeSet attrs) {
		super(context, attrs);
		init(context, GLSurfaceView.RENDERMODE_CONTINUOUSLY);
	}

	@Override
	public void onPause() {
		super.onPause();
		renderer.unregisterListeners();
		resumed = false;
		applyPacing();
	}

	@Override
	public void onResume() {
		super.onResume();
		resumed = true;
		applyPacing();
	}

	// Callers ask for a mode; with a cap, CONTINUOUSLY becomes vsync-paced WHEN_DIRTY.
	@Override
	public void setRenderMode(int renderMode) {
		requestedRenderMode = renderMode;
		applyPacing();
	}

	public void setFpsCap(int cap) {
		fpsCap = Math.max(cap, 0);
		applyPacing();
	}

	public void setKeepState(boolean keep) {
		renderer.setKeepState(keep);
		setPreserveEGLContextOnPause(keep);
	}

	public void setFeed(String src) {
		String name = null;
		java.util.ArrayList<String> exports = new java.util.ArrayList<>();
		if (src != null) {
			Matcher m = PATTERN_FEED.matcher(src);
			if (m.find()) {
				name = m.group(1);
				Matcher e = PATTERN_FEED_EXPORT.matcher(src);
				while (e.find()) {
					exports.add(e.group(1));
				}
			}
		}
		renderer.setFeed(name, exports);
	}

	public static boolean parseKeepState(String src) {
		if (src == null) {
			return false;
		}
		Matcher matcher = PATTERN_KEEP_STATE.matcher(src);
		if (!matcher.find()) {
			return false;
		}
		String value = matcher.group(1);
		return value == null || !value.equals("0");
	}

	public static int parseFpsCap(String src) {
		if (src == null) {
			return 0;
		}
		Matcher matcher = PATTERN_FPS_CAP.matcher(src);
		if (!matcher.find()) {
			return 0;
		}
		try {
			return Integer.parseInt(matcher.group(1));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	// Click handling is implemented in renderer.
	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		renderer.touchAt(event);
		return true;
	}

	public void setFragmentShader(String src, float quality) {
		setKeepState(parseKeepState(src));
		setFeed(src);
		onPause();
		// When pasting text from other apps, e.g. Gmail, the
		// text is sometimes tainted with useless non-ascii
		// characters that can raise an exception in the shader
		// compiler. To still allow UTF-8 characters in comments,
		// the source is cleaned up here.
		renderer.setFragmentShader(removeNonAscii(src), quality);
		setFpsCap(parseFpsCap(src));
		onResume();
	}

	public ShaderRenderer getRenderer() {
		return renderer;
	}

	private void init(Context context, int renderMode) {
		renderer = new ShaderRenderer(context);

		// On some devices it's important to setEGLContextClientVersion()
		// even if the docs say it's not used when setEGLContextFactory()
		// is called. Not doing so will crash the app (e.g. on the FP1).
		setEGLContextClientVersion(2);
		setEGLContextFactory(new ContextFactory(renderer));
		setRenderer(renderer);
		setRenderMode(renderMode);
	}

	private void applyPacing() {
		boolean paced = fpsCap > 0 &&
				requestedRenderMode == RENDERMODE_CONTINUOUSLY;
		super.setRenderMode(paced ? RENDERMODE_WHEN_DIRTY : requestedRenderMode);
		boolean run = paced && resumed;
		if (Looper.myLooper() == Looper.getMainLooper()) {
			setPacing(run);
		} else {
			mainHandler.post(() -> setPacing(run));
		}
	}

	// Main thread only (Choreographer is per looper).
	private void setPacing(boolean run) {
		if (run == pacing) {
			return;
		}
		pacing = run;
		if (!run) {
			renderer.setPacedFrame(0L);
		}
		Choreographer choreographer = Choreographer.getInstance();
		choreographer.removeFrameCallback(pacer);
		if (run) {
			lastVsyncNs = 0;
			lastPacedNs = 0;
			choreographer.postFrameCallback(pacer);
		}
	}

	private void pace(long frameTimeNanos) {
		if (!pacing) {
			return;
		}
		if (lastVsyncNs > 0) {
			long d = frameTimeNanos - lastVsyncNs;
			if (d > 0 && d < NS_PER_SECOND / 20) {
				vsyncNs = d;   // the panel's current refresh (60/90/120 Hz)
			}
		}
		lastVsyncNs = frameTimeNanos;
		int cap = fpsCap;
		// Half a vsync of slack: 30 on 120 Hz lands on every 4th vsync, not every 5th.
		if (cap > 0 && frameTimeNanos - lastPacedNs >=
				NS_PER_SECOND / cap - vsyncNs / 2) {
			lastPacedNs = frameTimeNanos;
			renderer.setPacedFrame(frameTimeNanos);   // v114: presentation time + feed sample time
			requestRender();
		}
		Choreographer.getInstance().postFrameCallback(pacer);
	}

	private static String removeNonAscii(String text) {
		return text == null
				? null
				: text.replaceAll("[^\\x0A\\x09\\x20-\\x7E]", "");
	}

	private static class ContextFactory
			implements GLSurfaceView.EGLContextFactory {
		private final ShaderRenderer renderer;

		private ContextFactory(ShaderRenderer renderer) {
			this.renderer = renderer;
		}

		@Override
		public EGLContext createContext(EGL10 egl, EGLDisplay display,
				EGLConfig eglConfig) {
			int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
			EGLContext context = egl.eglCreateContext(display, eglConfig,
					EGL10.EGL_NO_CONTEXT, new int[]{
							EGL_CONTEXT_CLIENT_VERSION,
							3,
							EGL10.EGL_NONE
					});
			if (context != null && context != EGL10.EGL_NO_CONTEXT &&
					context.getGL() != null) {
				renderer.setVersion(3);
				return context;
			}
			return egl.eglCreateContext(display, eglConfig,
					EGL10.EGL_NO_CONTEXT, new int[]{
							EGL_CONTEXT_CLIENT_VERSION,
							2,
							EGL10.EGL_NONE
					});
		}

		@Override
		public void destroyContext(EGL10 egl, EGLDisplay display,
				EGLContext context) {
			egl.eglDestroyContext(display, context);
		}
	}
}
