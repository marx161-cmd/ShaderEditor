package com.termux.shadereditor.opengl;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

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
	private static final String TAG = "ExternalTextureBridge";
	static final int MAX_EXTERNAL_TEXTURES = 12;
	private static final String WATCH_DIR =
			"/data/data/com.termux/files/home/.shader";
	private static final String FILE_EXTENSION = ".bin";
	// BACKDROPS (v105): image files any shader may declare by name, #define BACKDROP_<name> <file>
	private static final String BACKDROP_DIR =
			"/data/data/com.termux/files/home/shaders/backdrops";
	private static final int BACKDROP_MAX_SIZE = 4096;

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
		final float[] size = new float[]{0f, 0f};

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
		new File(BACKDROP_DIR).mkdirs();

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
			File file = spec.file() != null
					? new File(BACKDROP_DIR, spec.file())
					: new File(WATCH_DIR, spec.name() + FILE_EXTENSION);
			slots.put(
					spec.name(),
					new Slot(spec, file, device.createTexture2D()));
		}
	}

	void apply(@NonNull ProgramBindings bindings, long now) {
		for (Slot slot : slots.values()) {
			long modified = slot.file.lastModified();
			// Only record the mtime once the data is actually in the texture.
			// Recording it unconditionally meant a single failed read retired
			// the slot permanently.
			if (modified != 0L && modified != slot.lastModified
					&& upload(slot)) {
				slot.lastModified = modified;
			}
			bindings.setTexture(slot.spec.name(), slot.texture);
			if (slot.spec.file() != null) {
				bindings.setFloat2(slot.spec.name() + "Res", slot.size);
			}
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

	// Returns true only if the texture actually received storage. A false here
	// must NOT be recorded as "done" by the caller, or the slot is stuck for the
	// lifetime of the process.
	private boolean upload(@NonNull Slot slot) {
		ExternalTextureSpec spec = slot.spec;
		if (spec.format() == ExternalTextureSpec.Format.BITMAP) {
			return uploadBitmap(slot);
		}
		int expected = spec.width() * spec.height() * spec.format().bytesPerPixel;
		byte[] bytes = new byte[expected];
		try (FileInputStream in = new FileInputStream(slot.file)) {
			// InputStream.read(byte[]) is ONE read() and is free to return fewer
			// bytes than asked for -- routinely so for multi-megabyte buffers,
			// depending on page-cache state. The old code did a single read and
			// bailed when it came up short, which left the GlTexture2D created
			// but never given storage: an INCOMPLETE texture, where every
			// texelFetch returns (0, 0, 0, 1). That is silent, it is sticky, and
			// it differs between processes, which is exactly how a shader could
			// look right in the editor and have no texture at all as a
			// wallpaper. Read until full or genuine EOF instead.
			int off = 0;
			while (off < expected) {
				int n = in.read(bytes, off, expected - off);
				if (n < 0) {
					Log.w(TAG, "short file for '" + spec.name() + "': got " + off
							+ " of " + expected + " bytes; not uploading");
					return false;
				}
				off += n;
			}
		} catch (IOException | SecurityException e) {
			Log.w(TAG, "cannot read '" + spec.name() + "' from "
					+ slot.file.getPath(), e);
			return false;
		}

		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
		// (BITMAP never gets here)
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
		return true;
	}

	// A backdrop image: decoded at its own size (halved until it fits BACKDROP_MAX_SIZE), RGBA8.
	private boolean uploadBitmap(@NonNull Slot slot) {
		Bitmap bmp;
		try {
			BitmapFactory.Options bounds = new BitmapFactory.Options();
			bounds.inJustDecodeBounds = true;
			BitmapFactory.decodeFile(slot.file.getPath(), bounds);
			if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
				Log.w(TAG, "backdrop '" + slot.spec.name() + "': cannot read " + slot.file.getPath());
				return false;
			}
			BitmapFactory.Options opts = new BitmapFactory.Options();
			opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
			opts.inScaled = false;
			opts.inPremultiplied = false;
			int sample = 1;
			while (bounds.outWidth / sample > BACKDROP_MAX_SIZE || bounds.outHeight / sample > BACKDROP_MAX_SIZE) {
				sample *= 2;
			}
			opts.inSampleSize = sample;
			bmp = BitmapFactory.decodeFile(slot.file.getPath(), opts);
		} catch (OutOfMemoryError | SecurityException e) {
			Log.w(TAG, "backdrop '" + slot.spec.name() + "': decode failed", e);
			return false;
		}
		if (bmp == null) {
			return false;
		}
		if (bmp.getConfig() != Bitmap.Config.ARGB_8888) {
			Bitmap c = bmp.copy(Bitmap.Config.ARGB_8888, false);
			bmp.recycle();
			bmp = c;
		}
		int w = bmp.getWidth(), h = bmp.getHeight();
		ByteBuffer buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
		bmp.copyPixelsToBuffer(buffer);   // ARGB_8888 is laid out R, G, B, A in memory
		bmp.recycle();
		buffer.rewind();
		device.uploadRgba8Texture2D(slot.texture, buffer, w, h);
		slot.size[0] = w;
		slot.size[1] = h;
		return true;
	}
}
