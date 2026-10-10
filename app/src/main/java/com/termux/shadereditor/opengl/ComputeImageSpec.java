package com.termux.shadereditor.opengl;

import android.opengl.GLES30;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A storage image declared in the shader preamble for compute kernels:
 *
 * <pre>#define IMAGE_name format WxH[xD]|full [swap]</pre>
 *
 * {@code full} = surface size x quality. {@code swap} allocates two copies,
 * bound as {@code name} (read) and {@code name_out} (write), swapped after
 * every dispatch that writes {@code name_out}.
 */
record ComputeImageSpec(
		@NonNull String name,
		@NonNull Format format,
		int width,
		int height,
		int depth,
		boolean full,
		boolean swap) {
	enum Format {
		RGBA32F(GLES30.GL_RGBA32F, GLES30.GL_RGBA, GLES30.GL_FLOAT, 16, false),
		RGBA16F(GLES30.GL_RGBA16F, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, 8, true),
		RGBA8(GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 4, true),
		R32F(GLES30.GL_R32F, GLES30.GL_RED, GLES30.GL_FLOAT, 4, false),
		R16F(GLES30.GL_R16F, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, 2, true),
		R32UI(GLES30.GL_R32UI, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_INT, 4, false),
		R32I(GLES30.GL_R32I, GLES30.GL_RED_INTEGER, GLES30.GL_INT, 4, false);

		final int internalFormat;
		final int format;
		final int type;
		final int bytesPerTexel;
		// Linear filtering when sampled as a texture. 32-bit float needs
		// OES_texture_float_linear (the Pixel doesn't have it), integers never.
		final boolean filterable;

		Format(int internalFormat, int format, int type, int bytesPerTexel,
				boolean filterable) {
			this.internalFormat = internalFormat;
			this.format = format;
			this.type = type;
			this.bytesPerTexel = bytesPerTexel;
			this.filterable = filterable;
		}

		@Nullable
		static Format parse(@NonNull String name) {
			return switch (name) {
				case "rgba32f" -> RGBA32F;
				case "rgba16f" -> RGBA16F;
				case "rgba8" -> RGBA8;
				case "r32f" -> R32F;
				case "r16f" -> R16F;
				case "r32ui" -> R32UI;
				case "r32i" -> R32I;
				default -> null;
			};
		}
	}

	static final String OUT_SUFFIX = "_out";
	static final String UNIT_SUFFIX = "_UNIT";

	boolean is3D() {
		return depth > 1;
	}
}
