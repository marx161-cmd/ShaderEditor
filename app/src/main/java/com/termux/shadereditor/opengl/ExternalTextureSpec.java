package com.termux.shadereditor.opengl;

import androidx.annotation.NonNull;

/**
 * A shader-declared external texture: an arbitrary {@code sampler2D <name>}
 * whose pixel data is read from a raw file in the external texture directory.
 *
 * <p>Declared in the shader source (shared preamble or anywhere) as:
 *
 * <pre>#define EXT_TEXTURE_&lt;name&gt; &lt;format&gt; &lt;width&gt; &lt;height&gt;</pre>
 *
 * The {@code #define} is a legal, unused object-like GLSL macro so it survives
 * compilation unchanged and has no effect on line mapping.
 */
record ExternalTextureSpec(
		@NonNull String name,
		@NonNull Format format,
		int width,
		int height) {

	enum Format {
		R32F(true, 4),
		RGBA8(false, 4),
		RGBA16F(true, 8);

		final boolean requiresEs3;
		final int bytesPerPixel;

		Format(boolean requiresEs3, int bytesPerPixel) {
			this.requiresEs3 = requiresEs3;
			this.bytesPerPixel = bytesPerPixel;
		}
	}
}
