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
	static final String UNIFORM_PASS = "pass";
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
	// Compute storage images, in declaration order (= image unit order).
	private final Map<String, ImageSet> imageSets = new LinkedHashMap<>();
	private boolean multipassTargetsReady;
	private int multipassFrame;
	private final float[] imageResolution3 = new float[3];
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
		imageSets.clear();
		multipassTargetsReady = false;
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

	// A flag, not !bufferSets.isEmpty(): a shader may have no buffers at all
	// (only compute sections + image).
	boolean hasMultipassTargets() {
		return multipassTargetsReady;
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
		for (ImageSet set : imageSets.values()) {
			device.deleteTexture(set.textures[0]);
			device.deleteTexture(set.textures[1]);
		}
		imageSets.clear();
		multipassTargetsReady = false;
		multipassFrame = 0;
	}

	@NonNull
	List<ShaderError> ensureMultipassTargets(
			@NonNull List<RendererProgramManager.PassProgram> passes,
			@NonNull List<ComputeImageSpec> images,
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
		for (ComputeImageSpec spec : images) {
			int width = spec.full()
					? Math.max(1, Math.round(surfaceWidth * quality))
					: spec.width();
			int height = spec.full()
					? Math.max(1, Math.round(surfaceHeight * quality))
					: spec.height();
			ImageSet set = new ImageSet(spec);
			set.textures[0] = device.createImage(spec.format(), width, height,
					spec.depth());
			if (spec.swap()) {
				set.textures[1] = device.createImage(spec.format(), width,
						height, spec.depth());
			}
			imageSets.put(spec.name(), set);
		}
		if (!imageSets.isEmpty()) {
			int error = device.getGlError();
			if (error != GLES20.GL_NO_ERROR) {
				errors.add(ShaderError.createGeneral(
						"Allocating compute images failed: GL error 0x" +
								Integer.toHexString(error)));
			}
		}
		for (RendererProgramManager.PassProgram pass : passes) {
			if (pass.isImage() || pass.isCompute()) {
				continue;
			}
			// Buffer resolution = surface × quality × per-buffer scale, unless
			// the section declares an absolute pixel size (fixedWidth/Height),
			// which is used verbatim for small data buffers. Quality scales the
			// expensive computation exactly as it does the main pass in
			// single-pass mode; the image pass always renders full-res.
			int width = pass.fixedWidth() > 0
					? pass.fixedWidth()
					: Math.max(
							1,
							Math.round(surfaceWidth * quality * pass.scale()));
			int height = pass.fixedHeight() > 0
					? pass.fixedHeight()
					: Math.max(
							1,
							Math.round(surfaceHeight * quality * pass.scale()));
			BufferSet set = new BufferSet(width, height);
			createBufferTarget(set, 0, halfFloat, gles3, errors);
			createBufferTarget(set, 1, halfFloat, gles3, errors);
			bufferSets.put(pass.name(), set);
		}
		device.bindFramebuffer(null);
		multipassTargetsReady = true;
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

			if (pass.isCompute()) {
				if (multipassFrame % pass.updateRate() == 0) {
					dispatch(pass, passBindings, surfaceWidth, surfaceHeight);
				}
				continue;
			}

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
			bindBufferResolutions(passBindings);
			bindImageSamplers(passBindings, pass.program());
			device.applyBindings(passBindings);
			device.draw(fullScreenQuadMesh, pass.program());

			if (set != null) {
				int target = set.front;
				set.front = set.back;
				set.back = target;
			}
		}
		++multipassFrame;
	}

	private void dispatch(
			@NonNull RendererProgramManager.PassProgram pass,
			@NonNull ProgramBindings passBindings,
			int surfaceWidth,
			int surfaceHeight) {
		int threadsX;
		int threadsY;
		int threadsZ;
		if (pass.sizeImage() != null) {
			ImageSet sizing = imageSets.get(pass.sizeImage());
			if (sizing == null) {
				return;
			}
			threadsX = sizing.textures[0].getWidth();
			threadsY = sizing.textures[0].getHeight();
			threadsZ = sizing.textures[0].getDepth();
		} else {
			threadsX = pass.threads()[0];
			threadsY = pass.threads()[1];
			threadsZ = pass.threads()[2];
		}
		int[] local = pass.program().getWorkGroupSize();
		int groupsX = (threadsX + local[0] - 1) / Math.max(1, local[0]);
		int groupsY = (threadsY + local[1] - 1) / Math.max(1, local[1]);
		int groupsZ = (threadsZ + local[2] - 1) / Math.max(1, local[2]);

		drawResolution[0] = surfaceWidth;
		drawResolution[1] = surfaceHeight;
		passBindings.setFloat2(ShaderRenderer.UNIFORM_RESOLUTION, drawResolution);
		bindBufferSamplers(passBindings, pass.program(), null);
		bindBufferResolutions(passBindings);
		for (int p = 0; p < pass.repeat(); ++p) {
			passBindings.setInt(UNIFORM_PASS, p);
			bindImageSamplers(passBindings, pass.program());
			device.applyBindings(passBindings);
			bindImageUnits();
			device.dispatchCompute(groupsX, groupsY, groupsZ);
			swapWrittenImages(pass.program());
		}
	}

	// Units follow declaration order: name, then name_out for swap images,
	// matching the <name>_UNIT macros from ShaderSourcePreparer.
	private void bindImageUnits() {
		int unit = 0;
		for (ImageSet set : imageSets.values()) {
			device.bindImage(unit++, set.textures[set.front]);
			if (set.spec.swap()) {
				device.bindImage(unit++, set.textures[1 - set.front]);
			}
		}
	}

	private void swapWrittenImages(@NonNull GlProgram program) {
		for (ImageSet set : imageSets.values()) {
			if (set.spec.swap() && device.getUniformType(program,
					set.spec.name() + ComputeImageSpec.OUT_SUFFIX) != 0) {
				set.front = 1 - set.front;
			}
		}
	}

	// Any pass can read an image as a texture by its name (the current
	// read copy), and gets <name>Res (vec2, or vec3 for 3D images).
	private void bindImageSamplers(
			@NonNull ProgramBindings bindings,
			@NonNull GlProgram program) {
		for (ImageSet set : imageSets.values()) {
			GlImageTexture texture = set.textures[set.front];
			String name = set.spec.name();
			if (GlDevice.isSamplerType(device.getUniformType(program, name))) {
				bindings.setTexture(name, texture);
			}
			if (texture.is3D()) {
				imageResolution3[0] = texture.getWidth();
				imageResolution3[1] = texture.getHeight();
				imageResolution3[2] = texture.getDepth();
				bindings.setFloat3(name + "Res", imageResolution3.clone());
			} else {
				bindings.setFloat2(name + "Res", new float[]{
						texture.getWidth(), texture.getHeight()});
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
			bindBufferResolutions(passBindings);
			bindImageSamplers(passBindings, pass.program());
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

	// Exposes each buffer's own resolution as a "<name>Res" vec2 uniform, so a
	// pass can index a buffer by texel (buffer.width/buffer.height) instead of
	// assuming it is surface-sized.
	private void bindBufferResolutions(@NonNull ProgramBindings bindings) {
		for (Map.Entry<String, BufferSet> entry : bufferSets.entrySet()) {
			bindings.setFloat2(
					entry.getKey() + "Res",
					new float[]{entry.getValue().width, entry.getValue().height});
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

	private static final class ImageSet {
		@NonNull
		final ComputeImageSpec spec;
		final GlImageTexture[] textures = new GlImageTexture[2];
		int front = 0;

		ImageSet(@NonNull ComputeImageSpec spec) {
			this.spec = spec;
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
