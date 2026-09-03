package com.termux.shadereditor.opengl;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.opengl.GLES11Ext;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import java.util.List;

import com.termux.shadereditor.hardware.CameraListener;

final class BuiltinCameraUniforms {
	private final float[] cameraOrientation = new float[4];
	@NonNull
	private final Context context;

	@Nullable
	private CameraListener cameraListener;
	@Nullable
	private ShaderTextureResources.SamplerTextureBinding cameraTextureBinding;
	private boolean hasCameraOrientation;
	private boolean hasCameraAddent;
	private int renderWidth;
	private int renderHeight;
	private int deviceRotation;

	BuiltinCameraUniforms(@NonNull Context context) {
		this.context = context;
	}

	void configure(
			@NonNull GlDevice device,
			@NonNull List<GlProgram> programs,
			@NonNull ShaderTextureResources textureResources) {
		hasCameraOrientation = anyHasUniform(
				device,
				programs,
				ShaderRenderer.UNIFORM_CAMERA_ORIENTATION);
		hasCameraAddent = anyHasUniform(
				device,
				programs,
				ShaderRenderer.UNIFORM_CAMERA_ADDENT);
		cameraTextureBinding = textureResources.getFirstBinding(
				ShaderRenderer.UNIFORM_CAMERA_BACK,
				ShaderRenderer.UNIFORM_CAMERA_FRONT);
		openCameraIfNeeded();
	}

	void updateSurface(int renderWidth, int renderHeight, int deviceRotation) {
		this.renderWidth = renderWidth;
		this.renderHeight = renderHeight;
		this.deviceRotation = deviceRotation;
		openCameraIfNeeded();
	}

	// Advances the camera stream once per frame. Called exactly once per
	// frame even in multi-pass mode, so the stream isn't stepped N times.
	void updateFrame() {
		if (cameraListener == null) {
			return;
		}
		cameraListener.update();
		if (cameraTextureBinding != null && cameraTextureBinding.texture() != null) {
			cameraTextureBinding.texture().markBindingDirty();
		}
	}

	void apply(@NonNull ProgramBindings bindings) {
		if (cameraListener == null) {
			return;
		}

		if (hasCameraOrientation) {
			var orientationMatrix = cameraListener.getOrientationMatrix();
			orientationMatrix.rewind();
			orientationMatrix.get(cameraOrientation);
			orientationMatrix.rewind();
			bindings.setMatrix2(
					ShaderRenderer.UNIFORM_CAMERA_ORIENTATION,
					false,
					cameraOrientation);
		}
		if (hasCameraAddent) {
			bindings.setFloat2(
					ShaderRenderer.UNIFORM_CAMERA_ADDENT,
					cameraListener.addent);
		}
	}

	void release() {
		cameraTextureBinding = null;
		unregisterCamera();
	}

	private void openCameraIfNeeded() {
		unregisterCamera();

		var binding = cameraTextureBinding;
		if (binding == null ||
				renderWidth <= 0 ||
				renderHeight <= 0 ||
				!(context instanceof LifecycleOwner lifecycleOwner)) {
			return;
		}

		var texture = binding.texture();
		if (texture == null ||
				!texture.isValid() ||
				texture.getTarget() != GLES11Ext.GL_TEXTURE_EXTERNAL_OES) {
			return;
		}

		int lensFacing = ShaderRenderer.UNIFORM_CAMERA_BACK.equals(
				binding.sampler().name())
				? CameraSelector.LENS_FACING_BACK
				: CameraSelector.LENS_FACING_FRONT;
		requestPermission(android.Manifest.permission.CAMERA);
		cameraListener = new CameraListener(
				texture.getId(),
				lensFacing,
				renderWidth,
				renderHeight,
				deviceRotation,
				context);
		cameraListener.register(lifecycleOwner);
	}

	private void unregisterCamera() {
		if (cameraListener != null) {
			cameraListener.unregister();
			cameraListener = null;
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