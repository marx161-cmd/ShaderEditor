package com.termux.shadereditor.opengl;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Binds arbitrary, shader-declared external textures to raw files dropped in
 * Termux home's {@code ~/.shader} directory.
 *
 * <p>A shader opts a texture in by declaring it in its source:
 *
 * <pre>#define EXT_TEXTURE_state rgba16f 256 256</pre>
 *
 * plus the matching {@code uniform sampler2D state;}. The bridge watches
 * {@code <name>.bin} in the watch directory and re-uploads on mtime change.
 * Format and dimensions come from the shader declaration; the file supplies
 * only the raw pixels.
 */
final class ExternalTextureBridge {
	static final int MAX_EXTERNAL_TEXTURES = 12;
	private static final String WATCH_DIR =
			"/data/data/com.termux/files/home/.shader";
	private static final String FILE_EXTENSION = ".bin";

	private GlDevice device;
	private final Map<String, Slot> slots = new HashMap<>();

	private static final class Slot {
		@NonNull
		final ExternalTextureSpec spec;
		@NonNull
		final File file;
		@NonNull
		final GlTexture2D texture;
		long lastModified = -1L;

		Slot(@NonNull ExternalTextureSpec spec,
				@NonNull File file,
				@NonNull GlTexture2D texture) {
			this.spec = spec;
			this.file = file;
			this.texture = texture;
		}
	}

	void configure(@NonNull GlDevice device,
			@NonNull List<ExternalTextureSpec> specs,
			@NonNull Set<String> declaredSamplerNames,
			boolean gles3) {
		release();
		this.device = device;
		// Best-effort; the producer/user usually creates it first.
		new File(WATCH_DIR).mkdirs();

		for (ExternalTextureSpec spec : specs) {
			if (slots.size() >= MAX_EXTERNAL_TEXTURES) {
				break;
			}
			if (!declaredSamplerNames.contains(spec.name())) {
				continue;
			}
			if (spec.format().requiresEs3 && !gles3) {
				continue;
			}
			File file = new File(WATCH_DIR, spec.name() + FILE_EXTENSION);
			slots.put(
					spec.name(),
					new Slot(spec, file, device.createTexture2D()));
		}
	}

	void apply(@NonNull ProgramBindings bindings, long now) {
		for (Slot slot : slots.values()) {
			long modified = slot.file.lastModified();
			if (modified != 0L && modified != slot.lastModified) {
				upload(slot);
				slot.lastModified = modified;
			}
			bindings.setTexture(slot.spec.name(), slot.texture);
		}
	}

	void release() {
		if (device != null) {
			for (Slot slot : slots.values()) {
				device.deleteTexture(slot.texture);
			}
		}
		slots.clear();
		device = null;
	}

	private void upload(@NonNull Slot slot) {
		ExternalTextureSpec spec = slot.spec;
		int expected = spec.width() * spec.height() * spec.format().bytesPerPixel;
		byte[] bytes = new byte[expected];
		try (FileInputStream in = new FileInputStream(slot.file)) {
			if (in.read(bytes) < expected) {
				// Torn/short write; keep the previous texture content.
				return;
			}
		} catch (IOException | SecurityException e) {
			// Unreadable or mid-write; keep the previous texture content.
			return;
		}

		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
		switch (spec.format()) {
			case RGBA8 -> device.uploadRgba8Texture2D(
					slot.texture, buffer, spec.width(), spec.height());
			case RGBA16F -> device.uploadRgba16fTexture2D(
					slot.texture, buffer, spec.width(), spec.height());
			case R32F -> {
				FloatBuffer floats = buffer.asFloatBuffer();
				float[] data = new float[spec.width() * spec.height()];
				floats.get(data);
				device.uploadFloatTexture2D(
						slot.texture, data, spec.width(), spec.height());
			}
		}
	}
}
