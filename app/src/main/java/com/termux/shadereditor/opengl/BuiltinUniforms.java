package com.termux.shadereditor.opengl;

import android.content.Context;
import android.view.MotionEvent;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

final class BuiltinUniforms {
	record SurfaceState(int renderWidth, int renderHeight, boolean renderTargetsChanged) {
		@NonNull
		static SurfaceState empty() {
			return new SurfaceState(0, 0, false);
		}
	}

	record PreparedFrame(
			@NonNull List<ProgramBindings> bindings,
			int surfaceWidth,
			int surfaceHeight,
			long now) {
	}

	private static final float NS_PER_SECOND = 1000000000f;
	private static final float DEFAULT_FTIME_MAX = 3f;
	private static final int MAX_POINTERS = 10;

	private final float[] surfaceResolution = new float[]{0, 0};
	private final float[] resolution = new float[]{0, 0};
	private final float[] touch = new float[]{0, 0};
	private final float[] touchStart = new float[]{0, 0};
	private final float[] mouse = new float[]{0, 0};
	private final float[] pointers = new float[MAX_POINTERS * 3];
	private final float[] offset = new float[]{0, 0};
	@NonNull
	private final Context context;
	@NonNull
	private final BuiltinSensorUniforms sensorUniforms;
	@NonNull
	private final BuiltinSystemUniforms systemUniforms;
	@NonNull
	private final BuiltinCameraUniforms cameraUniforms;

	@Nullable
	private List<ProgramBindings> programBindings;
	private boolean multipass;
	@NonNull
	private ShaderTextureResources textureResources =
			ShaderTextureResources.empty();
	private int pointerCount;
	private int frameNum;
	private long startTime;
	private float fTimeMax = DEFAULT_FTIME_MAX;
	private float quality = 1f;
	private float startRandom;

	BuiltinUniforms(@NonNull Context context) {
		this.context = context;
		sensorUniforms = new BuiltinSensorUniforms(context);
		systemUniforms = new BuiltinSystemUniforms(context);
		cameraUniforms = new BuiltinCameraUniforms(context);
	}

	void setQuality(float quality) {
		this.quality = quality;
	}

	void configure(
			@NonNull GlDevice device,
			@NonNull List<GlProgram> programs,
			float fTimeMax,
			@NonNull ShaderTextureResources textureResources,
			boolean multipass) {
		releaseModules();
		this.fTimeMax = fTimeMax;
		this.textureResources = textureResources;
		this.multipass = multipass;

		List<ProgramBindings> bindings = new ArrayList<>(programs.size());
		for (GlProgram program : programs) {
			bindings.add(new ProgramBindings(program));
		}
		programBindings = bindings;

		sensorUniforms.configure(device, programs);
		systemUniforms.configure(device, programs);
		cameraUniforms.configure(device, programs, textureResources);
	}

	@NonNull
	SurfaceState updateSurface(int width, int height, long now) {
		startTime = now;
		startRandom = (float) Math.random();
		frameNum = 0;

		surfaceResolution[0] = width;
		surfaceResolution[1] = height;
		int deviceRotation = getDeviceRotation(context);
		sensorUniforms.setDeviceRotation(deviceRotation);

		float w = Math.round(width * quality);
		float h = Math.round(height * quality);
		boolean resolutionChanged = w != resolution[0] || h != resolution[1];
		resolution[0] = w;
		resolution[1] = h;

		cameraUniforms.updateSurface(
				(int) resolution[0],
				(int) resolution[1],
				deviceRotation);
		return new SurfaceState(
				(int) resolution[0],
				(int) resolution[1],
				resolutionChanged);
	}

	void updateTouch(@NonNull MotionEvent e) {
		// Multi-pass passes may render at their own scale, so report input
		// coordinates in surface space (1:1) and let each pass scale by its
		// own resolution. Single-pass keeps the legacy quality-scaled space.
		float scale = multipass ? 1f : quality;
		float w = multipass ? surfaceResolution[0] : resolution[0];
		float h = multipass ? surfaceResolution[1] : resolution[1];

		float x = e.getX() * scale;
		float y = e.getY() * scale;

		touch[0] = x;
		touch[1] = h - y;

		mouse[0] = x / w;
		mouse[1] = 1 - y / h;

		switch (e.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				touchStart[0] = touch[0];
				touchStart[1] = touch[1];
				break;
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
				pointerCount = 0;
				return;
			default:
				break;
		}

		pointerCount = Math.min(e.getPointerCount(), pointers.length / 3);
		for (int i = 0, pointerOffset = 0; i < pointerCount; ++i) {
			pointers[pointerOffset++] = e.getX(i) * scale;
			pointers[pointerOffset++] = h - e.getY(i) * scale;
			pointers[pointerOffset++] = e.getTouchMajor(i);
		}
	}

	void updateOffset(float x, float y) {
		offset[0] = x;
		offset[1] = y;
	}

	@Nullable
	PreparedFrame beginFrame(@Nullable GlTexture2D backBufferTexture) {
		var bindings = programBindings;
		if (bindings == null || bindings.isEmpty()) {
			return null;
		}

		long now = System.nanoTime();
		float delta = (now - startTime) / NS_PER_SECOND;

		cameraUniforms.updateFrame();

		for (ProgramBindings b : bindings) {
			b.clear();
			bindFrameUniforms(b, delta);
			systemUniforms.apply(b, now);
			sensorUniforms.apply(b);
			cameraUniforms.apply(b);
			textureResources.applyTo(b);
		}

		if (backBufferTexture != null) {
			bindings.get(0).setTexture(
					ShaderRenderer.UNIFORM_BACKBUFFER,
					backBufferTexture);
		}

		return new PreparedFrame(
				bindings,
				(int) surfaceResolution[0],
				(int) surfaceResolution[1],
				now);
	}

	void endFrame() {
		++frameNum;
	}

	void clearConfiguration() {
		fTimeMax = DEFAULT_FTIME_MAX;
		textureResources = ShaderTextureResources.empty();
		programBindings = null;
		multipass = false;
	}

	void release() {
		releaseModules();
		clearConfiguration();
	}

	private void bindFrameUniforms(
			@NonNull ProgramBindings bindings,
			float delta) {
		bindings.setFloat(ShaderRenderer.UNIFORM_TIME, delta);
		bindings.setInt(ShaderRenderer.UNIFORM_SECOND, (int) delta);
		bindings.setFloat(
				ShaderRenderer.UNIFORM_SUB_SECOND,
				delta - (int) delta);
		bindings.setInt(ShaderRenderer.UNIFORM_FRAME_NUMBER, frameNum);
		bindings.setFloat(
				ShaderRenderer.UNIFORM_FTIME,
				((delta % fTimeMax) / fTimeMax * 2f - 1f));
		bindings.setFloat2(ShaderRenderer.UNIFORM_RESOLUTION, resolution);
		bindings.setFloat2(ShaderRenderer.UNIFORM_TOUCH, touch);
		bindings.setFloat2(ShaderRenderer.UNIFORM_TOUCH_START, touchStart);
		bindings.setFloat2(ShaderRenderer.UNIFORM_MOUSE, mouse);
		bindings.setInt(ShaderRenderer.UNIFORM_POINTER_COUNT, pointerCount);
		if (pointerCount > 0) {
			bindings.setFloat3(
					ShaderRenderer.UNIFORM_POINTERS,
					pointerCount,
					pointers);
		}
		bindings.setFloat2(ShaderRenderer.UNIFORM_OFFSET, offset);
		bindings.setFloat(ShaderRenderer.UNIFORM_START_RANDOM, startRandom);
	}

	private void releaseModules() {
		sensorUniforms.release();
		systemUniforms.release();
		cameraUniforms.release();
	}

	private static int getDeviceRotation(@NonNull Context context) {
		WindowManager windowManager = (WindowManager) context.getSystemService(
				Context.WINDOW_SERVICE);
		if (windowManager == null) {
			return 0;
		}

		return windowManager.getDefaultDisplay().getRotation();
	}
}
