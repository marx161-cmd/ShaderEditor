package com.termux.shadereditor.opengl;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ShaderRenderPipeline {
	private static final int THUMBNAIL_WIDTH = 144;
	private static final int THUMBNAIL_HEIGHT = 144;
	private static final String SURFACE_FRAME = "frame";
	private static final TextureParameters BUFFER_TEXTURE_PARAMETERS =
			new TextureParameters(
					GLES20.GL_LINEAR,
					GLES20.GL_LINEAR,
					GLES20.GL_CLAMP_TO_EDGE,
					GLES20.GL_CLAMP_TO_EDGE);

	private final float[] thumbnailResolution =
			new float[]{THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT};
	private final float[] drawResolution = new float[2];
	private final TextureParameters thumbnailTextureParameters =
			new TextureParameters(
					GLES20.GL_LINEAR,
					GLES20.GL_LINEAR,
					GLES20.GL_CLAMP_TO_EDGE,
					GLES20.GL_CLAMP_TO_EDGE);
	@NonNull
	private final GlDevice device;
	@NonNull
	private final Mesh fullScreenQuadMesh;
	private final GlFramebuffer[] framebuffers = new GlFramebuffer[2];
	private final GlTexture2D[] targetTextures = new GlTexture2D[2];
	private final Map<String, BufferSet> bufferSets = new LinkedHashMap<>();
	private int frontTarget;
	private int backTarget = 1;
	@Nullable
	private GlFramebuffer thumbnailFramebuffer;
	@Nullable
	private GlTexture2D thumbnailTexture;

	ShaderRenderPipeline(@NonNull GlDevice device, @NonNull Mesh fullScreenQuadMesh) {
		this.device = device;
		this.fullScreenQuadMesh = fullScreenQuadMesh;
	}

	@NonNull
	List<ShaderError> createContextResources() {
		ArrayList<ShaderError> errors = new ArrayList<>();
		thumbnailTexture = device.createTexture2D();
		device.applyTextureParameters(thumbnailTexture, thumbnailTextureParameters);
		device.allocateTexture2D(
				thumbnailTexture,
				THUMBNAIL_WIDTH,
				THUMBNAIL_HEIGHT);
		thumbnailFramebuffer = device.createFramebuffer();
		device.attachColor(thumbnailFramebuffer, thumbnailTexture);
		int status = device.checkFramebufferStatus(thumbnailFramebuffer);
		if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
			errors.add(ShaderError.createGeneral(
					"Thumbnail framebuffer incomplete: 0x" +
							Integer.toHexString(status)));
		}
		device.bindFramebuffer(null);
		return errors;
	}

	void discardContextResources() {
		thumbnailFramebuffer = null;
		thumbnailTexture = null;
		bufferSets.clear();
		discardTargets();
	}

	void releaseTargets() {
		for (int i = 0; i < framebuffers.length; ++i) {
			device.deleteFramebuffer(framebuffers[i]);
			framebuffers[i] = null;
			device.deleteTexture(targetTextures[i]);
			targetTextures[i] = null;
		}
		frontTarget = 0;
		backTarget = 1;
	}

	boolean hasTargets() {
		return framebuffers[0] != null && targetTextures[0] != null;
	}

	@NonNull
	List<ShaderError> ensureTargets(
			@NonNull Context context,
			int width,
			int height,
			@NonNull BackBufferParameters parameters) {
		ArrayList<ShaderError> errors = new ArrayList<>();
		if (hasTargets()) {
			return errors;
		}

		releaseTargets();
		createTarget(context, frontTarget, width, height, parameters, errors);
		createTarget(context, backTarget, width, height, parameters, errors);
		device.bindFramebuffer(null);
		return errors;
	}

	boolean hasMultipassTargets() {
		return !bufferSets.isEmpty();
	}

	void releaseMultipassTargets() {
		for (BufferSet set : bufferSets.values()) {
			for (int i = 0; i < 2; ++i) {
				device.deleteFramebuffer(set.framebuffers[i]);
				set.framebuffers[i] = null;
				device.deleteTexture(set.textures[i]);
				set.textures[i] = null;
			}
		}
		bufferSets.clear();
	}

	@NonNull
	List<ShaderError> ensureMultipassTargets(
			@NonNull List<RendererProgramManager.PassProgram> passes,
			int surfaceWidth,
			int surfaceHeight,
			float quality,
			boolean halfFloat,
			boolean gles3) {
		ArrayList<ShaderError> errors = new ArrayList<>();
		if (hasMultipassTargets()) {
			return errors;
		}

		releaseMultipassTargets();
		for (RendererProgramManager.PassProgram pass : passes) {
			if (pass.isImage()) {
				continue;
			}
			// Buffer resolution = surface × quality × per-buffer scale. Quality
			// scales the expensive computation exactly as it does the main pass
			// in single-pass mode; the image pass always renders full-res.
			int width = Math.max(
					1,
					Math.round(surfaceWidth * quality * pass.scale()));
			int height = Math.max(
					1,
					Math.round(surfaceHeight * quality * pass.scale()));
			BufferSet set = new BufferSet(width, height);
			createBufferTarget(set, 0, halfFloat, gles3, errors);
			createBufferTarget(set, 1, halfFloat, gles3, errors);
			bufferSets.put(pass.name(), set);
		}
		device.bindFramebuffer(null);
		return errors;
	}

	void renderMultipass(
			@NonNull List<ProgramBindings> bindings,
			@NonNull List<RendererProgramManager.PassProgram> passes,
			int surfaceWidth,
			int surfaceHeight) {
		for (int i = 0; i < passes.size(); ++i) {
			RendererProgramManager.PassProgram pass = passes.get(i);
			ProgramBindings passBindings = bindings.get(i);

			BufferSet set = null;
			int width;
			int height;
			if (pass.isImage()) {
				device.bindFramebuffer(null);
				width = surfaceWidth;
				height = surfaceHeight;
			} else {
				set = bufferSets.get(pass.name());
				if (set == null) {
					continue;
				}
				device.bindFramebuffer(set.framebuffers[set.front]);
				width = set.width;
				height = set.height;
			}

			device.setViewport(0, 0, width, height);
			drawResolution[0] = width;
			drawResolution[1] = height;
			passBindings.setFloat2(
					ShaderRenderer.UNIFORM_RESOLUTION,
					drawResolution);
			bindBufferSamplers(passBindings, pass.program(), pass.name());
			device.applyBindings(passBindings);
			device.draw(fullScreenQuadMesh, pass.program());

			if (set != null) {
				int target = set.front;
				set.front = set.back;
				set.back = target;
			}
		}
	}

	void renderMainPass(
			@NonNull ProgramBindings bindings,
			@NonNull GlProgram program) {
		GlFramebuffer framebuffer = framebuffers[frontTarget];
		GlTexture2D targetTexture = targetTextures[frontTarget];
		if (framebuffer == null || targetTexture == null) {
			return;
		}

		device.bindFramebuffer(framebuffer);
		device.setViewport(0, 0, targetTexture.getWidth(), targetTexture.getHeight());
		device.applyBindings(bindings);
		device.draw(fullScreenQuadMesh, program);
	}

	void renderSurfacePass(
			@NonNull ProgramBindings surfaceBindings,
			@NonNull GlProgram surfaceProgram,
			int surfaceWidth,
			int surfaceHeight) {
		drawSurface(
				targetTextures[frontTarget],
				surfaceWidth,
				surfaceHeight,
				null,
				surfaceBindings,
				surfaceProgram);
	}

	@Nullable
	GlTexture2D getBackTexture() {
		return targetTextures[backTarget];
	}

	void swapTargets() {
		int target = frontTarget;
		frontTarget = backTarget;
		backTarget = target;
	}

	@Nullable
	byte[] captureThumbnail(
			@NonNull ProgramBindings surfaceBindings,
			@NonNull GlProgram surfaceProgram) {
		if (thumbnailFramebuffer == null || targetTextures[frontTarget] == null) {
			return null;
		}

		drawSurface(
				targetTextures[frontTarget],
				(int) thumbnailResolution[0],
				(int) thumbnailResolution[1],
				thumbnailFramebuffer,
				surfaceBindings,
				surfaceProgram);

		return encodeThumbnail();
	}

	@Nullable
	byte[] captureMultipassThumbnail(
			@NonNull List<ProgramBindings> bindings,
			@NonNull List<RendererProgramManager.PassProgram> passes) {
		if (thumbnailFramebuffer == null) {
			return null;
		}

		for (int i = 0; i < passes.size(); ++i) {
			RendererProgramManager.PassProgram pass = passes.get(i);
			if (!pass.isImage()) {
				continue;
			}
			ProgramBindings passBindings = bindings.get(i);
			device.bindFramebuffer(thumbnailFramebuffer);
			device.setViewport(0, 0, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
			drawResolution[0] = thumbnailResolution[0];
			drawResolution[1] = thumbnailResolution[1];
			passBindings.setFloat2(
					ShaderRenderer.UNIFORM_RESOLUTION,
					drawResolution);
			bindBufferSamplers(passBindings, pass.program(), pass.name());
			device.applyBindings(passBindings);
			device.clear(GLES20.GL_COLOR_BUFFER_BIT);
			device.draw(fullScreenQuadMesh, pass.program());
			return encodeThumbnail();
		}
		return null;
	}

	private void bindBufferSamplers(
			@NonNull ProgramBindings bindings,
			@NonNull GlProgram program,
			@Nullable String currentName) {
		for (Map.Entry<String, BufferSet> entry : bufferSets.entrySet()) {
			String name = entry.getKey();
			if (!device.hasUniform(program, name)) {
				continue;
			}
			BufferSet set = entry.getValue();
			GlTexture2D texture = name.equals(currentName)
					? set.textures[set.back]
					: set.textures[set.front];
			bindings.setTexture(name, texture);
		}
	}

	private void createBufferTarget(
			@NonNull BufferSet set,
			int index,
			boolean halfFloat,
			boolean gles3,
			@NonNull List<ShaderError> errors) {
		GlTexture2D texture = device.createTexture2D();
		set.textures[index] = texture;

		if (halfFloat) {
			device.allocateTexture2DHalfFloat(
					texture,
					set.width,
					set.height,
					gles3);
		} else {
			device.allocateTexture2D(texture, set.width, set.height);
		}
		device.applyTextureParameters(texture, BUFFER_TEXTURE_PARAMETERS);

		GlFramebuffer framebuffer = device.createFramebuffer();
		set.framebuffers[index] = framebuffer;
		device.attachColor(framebuffer, texture);
		int status = device.checkFramebufferStatus(framebuffer);
		if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
			errors.add(ShaderError.createGeneral(
					"Framebuffer incomplete: 0x" + Integer.toHexString(status)));
		}

		// Clear once on allocation so the first feedback frame doesn't sample
		// garbage; never clear again per frame.
		device.bindFramebuffer(framebuffer);
		device.clear(GLES20.GL_COLOR_BUFFER_BIT |
				GLES20.GL_DEPTH_BUFFER_BIT);
	}

	@Nullable
	private byte[] encodeThumbnail() {
		final int pixels = THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT;
		final int[] rgba = new int[pixels];
		final IntBuffer buffer = IntBuffer.wrap(rgba);
		device.readPixels(
				0,
				0,
				THUMBNAIL_WIDTH,
				THUMBNAIL_HEIGHT,
				buffer);
		device.bindFramebuffer(null);

		int[] argb = new int[pixels];
		for (int y = 0; y < THUMBNAIL_HEIGHT; ++y) {
			for (int x = 0; x < THUMBNAIL_WIDTH; ++x) {
				int srcIdx = y * THUMBNAIL_WIDTH + x;
				int destIdx = (THUMBNAIL_HEIGHT - y - 1) * THUMBNAIL_WIDTH + x;
				int pixel = rgba[srcIdx];
				argb[destIdx] = 0xff000000
						| ((pixel << 16) & 0x00ff0000)
						| (pixel & 0x0000ff00)
						| ((pixel >> 16) & 0x000000ff);
			}
		}

		try (var out = new ByteArrayOutputStream()) {
			Bitmap.createBitmap(
					argb,
					THUMBNAIL_WIDTH,
					THUMBNAIL_HEIGHT,
					Bitmap.Config.ARGB_8888).compress(
					Bitmap.CompressFormat.PNG,
					100,
					out);
			return out.toByteArray();
		} catch (OutOfMemoryError | IllegalArgumentException | IOException e) {
			return null;
		}
	}

	private void drawSurface(
			@Nullable GlTexture2D sourceTexture,
			int drawWidth,
			int drawHeight,
			@Nullable GlFramebuffer targetFramebuffer,
			@NonNull ProgramBindings surfaceBindings,
			@NonNull GlProgram surfaceProgram) {
		if (sourceTexture == null) {
			return;
		}

		device.bindFramebuffer(targetFramebuffer);
		device.setViewport(0, 0, drawWidth, drawHeight);
		drawResolution[0] = drawWidth;
		drawResolution[1] = drawHeight;
		surfaceBindings.clear();
		surfaceBindings.setFloat2(
				ShaderRenderer.UNIFORM_RESOLUTION,
				drawResolution);
		surfaceBindings.setTexture(SURFACE_FRAME, sourceTexture);
		device.applyBindings(surfaceBindings);
		device.clear(GLES20.GL_COLOR_BUFFER_BIT);
		device.draw(fullScreenQuadMesh, surfaceProgram);
	}

	private void discardTargets() {
		framebuffers[0] = null;
		framebuffers[1] = null;
		targetTextures[0] = null;
		targetTextures[1] = null;
		frontTarget = 0;
		backTarget = 1;
	}

	private void createTarget(
			@NonNull Context context,
			int index,
			int width,
			int height,
			@NonNull BackBufferParameters parameters,
			@NonNull List<ShaderError> errors) {
		GlTexture2D texture = device.createTexture2D();
		targetTextures[index] = texture;

		Bitmap bitmap = parameters.getPresetBitmap(context, width, height);
		if (bitmap != null) {
			String message = device.uploadTexture2D(texture, bitmap, true);
			if (message != null) {
				errors.add(ShaderError.createGeneral(message));
			}
			bitmap.recycle();
		} else {
			device.allocateTexture2D(texture, width, height);
		}

		device.applyTextureParameters(texture, parameters);
		device.generateMipmap(texture);

		GlFramebuffer framebuffer = device.createFramebuffer();
		framebuffers[index] = framebuffer;
		device.attachColor(framebuffer, texture);
		int status = device.checkFramebufferStatus(framebuffer);
		if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
			errors.add(ShaderError.createGeneral(
					"Framebuffer incomplete: 0x" + Integer.toHexString(status)));
		}

		if (bitmap == null) {
			device.bindFramebuffer(framebuffer);
			device.clear(GLES20.GL_COLOR_BUFFER_BIT |
					GLES20.GL_DEPTH_BUFFER_BIT);
		}
	}

	private static final class BufferSet {
		final GlFramebuffer[] framebuffers = new GlFramebuffer[2];
		final GlTexture2D[] textures = new GlTexture2D[2];
		int front = 0;
		int back = 1;
		final int width;
		final int height;

		BufferSet(int width, int height) {
			this.width = width;
			this.height = height;
		}
	}
}
