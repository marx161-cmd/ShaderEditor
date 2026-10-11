package com.termux.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
 *
 * <p>BACKDROPS (v105): an image file (PNG / JPEG / WebP) from {@code ~/shaders/backdrops/}, decoded
 * to RGBA8 at its own size, declared as
 *
 * <pre>#define BACKDROP_&lt;name&gt; &lt;file name&gt;</pre>
 *
 * plus {@code uniform sampler2D <name>;} (and optionally {@code uniform vec2 <name>Res;} = its size
 * in pixels). Rows are uploaded top-to-bottom, like the .bin bridge: sample with
 * {@code vec2(u, 1.0 - v)}. {@code file} is null for the raw .bin textures; width/height are 0
 * for backdrops (they come from the file).
 */
record ExternalTextureSpec(
		@NonNull String name,
		@NonNull Format format,
		int width,
		int height,
		@Nullable String file) {

	enum Format {
		R32F(true, 4),
		RGBA8(false, 4),
		RGBA16F(true, 8),
		BITMAP(false, 4);

		final boolean requiresEs3;
		final int bytesPerPixel;

		Format(boolean requiresEs3, int bytesPerPixel) {
			this.requiresEs3 = requiresEs3;
			this.bytesPerPixel = bytesPerPixel;
		}
	}
}
