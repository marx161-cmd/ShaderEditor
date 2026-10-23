package com.termux.shadereditor.opengl;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * SIM FEED (v113, phone-ringbox scope.md 2026-10-23): uniforms from a Termux process, and data back to it.
 * A shader declares "#define FEED <name>" (+ "#define FEED_EXPORT_<image>" per compute image to send back, in source order).
 * <ul>
 * <li>~/.shader/feeds/&lt;name&gt;.bin (brain -> app): seqlocked header + up to 256 records (name, kind,
 * ncomp, interp, prev[4], cur[4]). Every frame each record becomes a uniform of that name on every pass;
 * interp records are blended prev -> cur, shown one brain step behind (both clocks are CLOCK_MONOTONIC).</li>
 * <li>~/.shader/feeds/&lt;name&gt;.in.bin (app -> brain): heartbeat + visible flag + canvas every frame,
 * touches (tap count + position, 3-finger tap count), and each FEED_EXPORT image's red channel as u32,
 * read back through a PBO every EXPORT_EVERY frames (one export late, never a pipeline stall).</li>
 * </ul>
 * Layouts must match phone-ringbox/brain.py.
 */
final class SimFeed {
	private static final String TAG = "SimFeed";
	static final File DIR = new File("/data/data/com.termux/files/home/.shader/feeds");

	private static final int HDR = 64;
	private static final int REC = 80;
	private static final int MAX_REC = 256;
	private static final int MAGIC = 0x31464252;      // "RBF1" little endian
	private static final int IN_MAGIC = 0x31494252;   // "RBI1"
	private static final int IN_TOUCH = 16;
	private static final int EXPORT_EVERY = 6;
	private static final long TAP_MAX_NS = 250_000_000L;
	private static final float TAP_SLOP_PX = 40f;
	private static final long RETRY_NS = 1_000_000_000L;

	@NonNull
	private final String name;
	@NonNull
	private final List<String> exports;

	// feed (read)
	private MappedByteBuffer feed;
	private long nextTry;
	private int lastSeq = -1;
	private int count;
	private final String[] names = new String[MAX_REC];
	private final int[] kinds = new int[MAX_REC];
	private final int[] comps = new int[MAX_REC];
	private final boolean[] interp = new boolean[MAX_REC];
	private final float[][] prev = new float[MAX_REC][4];
	private final float[][] cur = new float[MAX_REC][4];
	private final float[][] out = new float[MAX_REC][];
	private double tPrev;
	private double tCur;
	private final byte[] recBytes = new byte[MAX_REC * REC];

	// inbox (write)
	private MappedByteBuffer inbox;
	private int inSeq;
	private int[] exportOffsets;
	private int inSize;
	private float canvasW;
	private float canvasH;
	private volatile boolean visible;
	private int frame;

	// touches (main thread writes, GL thread reads)
	private volatile int boops;
	private volatile float boopX;
	private volatile float boopY;
	private volatile int taps3;
	private long downNs;
	private float downX;
	private float downY;
	private int maxPointers;
	private boolean moved;

	// export readback
	private GlFramebuffer fbo;
	private int[] pbos;
	private boolean[] pending;
	private int[] exportTexels;

	SimFeed(@NonNull String name, @NonNull List<String> exports) {
		this.name = name;
		this.exports = exports;
	}

	@NonNull
	String getName() {
		return name;
	}

	@NonNull
	List<String> getExports() {
		return exports;
	}

	void setCanvas(int width, int height) {
		canvasW = width;
		canvasH = height;
	}

	void setVisible(boolean v) {
		visible = v;
		if (!v) {
			writeHeader(System.nanoTime());   // the brain pauses at once, not after the stale timeout
		}
	}

	// ---- touches (main thread) ----
	void touch(@NonNull MotionEvent e) {
		int action = e.getActionMasked();
		long now = e.getEventTime() * 1_000_000L;
		switch (action) {
			case MotionEvent.ACTION_DOWN -> {
				downNs = now;
				downX = e.getX();
				downY = e.getY();
				maxPointers = 1;
				moved = false;
			}
			case MotionEvent.ACTION_POINTER_DOWN ->
					maxPointers = Math.max(maxPointers, e.getPointerCount());
			case MotionEvent.ACTION_MOVE -> {
				if (Math.abs(e.getX() - downX) > TAP_SLOP_PX ||
						Math.abs(e.getY() - downY) > TAP_SLOP_PX) {
					moved = true;
				}
			}
			case MotionEvent.ACTION_UP -> {
				if (!moved && now - downNs < TAP_MAX_NS * (maxPointers >= 3 ? 2 : 1)) {
					if (maxPointers >= 3) {
						++taps3;
					} else if (maxPointers == 1) {
						boopX = e.getX();
						boopY = canvasH - e.getY();   // canvas px, y up (GL fragCoord)
						++boops;
					}
				}
			}
			default -> {
			}
		}
	}

	// ---- per frame (GL thread) ----
	void apply(@NonNull List<ProgramBindings> bindings, long nowNs) {
		if (!readFeed(nowNs) || count == 0) {
			return;
		}
		double now = nowNs / 1e9;
		double step = tCur - tPrev;
		float a = 1f;
		if (step > 1e-4 && now - tCur < 0.25) {
			a = (float) Math.max(0.0, Math.min(1.0, (now - step - tPrev) / step));
		}
		for (int i = 0; i < count; ++i) {
			float[] o = out[i];
			float[] p = prev[i];
			float[] c = cur[i];
			for (int k = 0; k < comps[i]; ++k) {
				o[k] = interp[i] ? p[k] + (c[k] - p[k]) * a : c[k];
			}
			for (ProgramBindings b : bindings) {
				set(b, i, o);
			}
		}
	}

	private void set(@NonNull ProgramBindings b, int i, @NonNull float[] o) {
		String n = names[i];
		if (kinds[i] == 1 && comps[i] == 1) {
			b.setInt(n, Math.round(o[0]));
			return;
		}
		switch (comps[i]) {
			case 1 -> b.setFloat(n, o[0]);
			case 2 -> b.setFloat2(n, o);
			case 3 -> b.setFloat3(n, o);
			default -> b.setFloat4(n, o);
		}
	}

	private boolean readFeed(long nowNs) {
		if (feed == null) {
			if (nowNs < nextTry) {
				return false;
			}
			nextTry = nowNs + RETRY_NS;
			feed = map(new File(DIR, name + ".bin"), false, 0);
			if (feed == null) {
				return false;
			}
		}
		int seq1 = feed.getInt(8);
		if (seq1 == lastSeq) {
			return true;
		}
		if ((seq1 & 1) != 0 || feed.getInt(0) != MAGIC) {
			return count > 0;
		}
		int n = Math.min(feed.getInt(12), MAX_REC);
		double tp = feed.getDouble(16);
		double tc = feed.getDouble(24);
		feed.position(HDR);
		feed.get(recBytes, 0, n * REC);
		if (feed.getInt(8) != seq1) {
			return count > 0;                 // the brain wrote meanwhile: next frame
		}
		ByteBuffer r = ByteBuffer.wrap(recBytes).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < n; ++i) {
			int o = i * REC;
			int len = 0;
			while (len < 32 && recBytes[o + len] != 0) {
				++len;
			}
			String nm = new String(recBytes, o, len, StandardCharsets.US_ASCII);
			if (!nm.equals(names[i])) {
				names[i] = nm;
			}
			kinds[i] = recBytes[o + 32];
			comps[i] = Math.max(1, Math.min(4, recBytes[o + 33]));
			interp[i] = recBytes[o + 34] != 0;
			for (int k = 0; k < 4; ++k) {
				prev[i][k] = r.getFloat(o + 36 + 4 * k);
				cur[i][k] = r.getFloat(o + 52 + 4 * k);
			}
			if (out[i] == null || out[i].length != comps[i]) {
				out[i] = new float[comps[i]];
			}
		}
		count = n;
		tPrev = tp;
		tCur = tc;
		lastSeq = seq1;
		return true;
	}

	void afterFrame(@NonNull ShaderRenderPipeline pipeline, @NonNull GlDevice device, long nowNs) {
		if (inbox == null && !openInbox(pipeline)) {
			return;
		}
		if (++frame % EXPORT_EVERY == 0) {
			exportImages(pipeline, device);
		}
		writeHeader(nowNs);
	}

	private boolean openInbox(@NonNull ShaderRenderPipeline pipeline) {
		int n = exports.size();
		exportOffsets = new int[n];
		exportTexels = new int[n];
		int o = HDR + IN_TOUCH + 4 + 8 * n;      // nblobs, then (offset, texels) per blob, then the blobs
		for (int i = 0; i < n; ++i) {
			GlImageTexture t = pipeline.getImage(exports.get(i));
			int texels = t == null ? 0 : t.getWidth() * t.getHeight();
			exportOffsets[i] = o;
			exportTexels[i] = texels;
			o += 4 * texels;
		}
		inSize = o;
		inbox = map(new File(DIR, name + ".in.bin"), true, inSize);
		if (inbox == null) {
			return false;
		}
		inbox.putInt(HDR + IN_TOUCH, n);
		for (int i = 0; i < n; ++i) {
			inbox.putInt(HDR + IN_TOUCH + 4 + 8 * i, exportOffsets[i]);
			inbox.putInt(HDR + IN_TOUCH + 8 + 8 * i, exportTexels[i]);
		}
		return true;
	}

	private void writeHeader(long nowNs) {
		MappedByteBuffer m = inbox;
		if (m == null) {
			return;
		}
		m.putInt(8, ++inSeq | 1);                // odd = writing
		m.putInt(0, IN_MAGIC);
		m.putInt(4, 1);
		m.putInt(12, visible ? 1 : 0);
		m.putDouble(16, nowNs / 1e9);
		m.putFloat(24, canvasW);
		m.putFloat(28, canvasH);
		m.putInt(HDR, boops);
		m.putFloat(HDR + 4, boopX);
		m.putFloat(HDR + 8, boopY);
		m.putInt(HDR + 12, taps3);
		inSeq = (inSeq | 1) + 1;
		m.putInt(8, inSeq);                      // even = complete
	}

	private void exportImages(@NonNull ShaderRenderPipeline pipeline, @NonNull GlDevice device) {
		int n = exports.size();
		if (pbos == null) {
			pbos = new int[n];
			pending = new boolean[n];
			GLES30.glGenBuffers(n, pbos, 0);
			fbo = device.createFramebuffer();
		}
		for (int i = 0; i < n; ++i) {
			GlImageTexture t = pipeline.getImage(exports.get(i));
			int texels = exportTexels[i];
			if (t == null || texels == 0 || t.getWidth() * t.getHeight() != texels) {
				continue;
			}
			int bytes = texels * 16;             // RGBA_INTEGER / UNSIGNED_INT: the always-legal read format
			GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[i]);
			if (pending[i]) {                    // the readback queued last time: copy its red channel out
				ByteBuffer b = (ByteBuffer) GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, bytes,
						GLES30.GL_MAP_READ_BIT);
				if (b != null) {
					b.order(ByteOrder.LITTLE_ENDIAN);
					int base = exportOffsets[i];
					inbox.putInt(8, inSeq | 1);
					for (int k = 0; k < texels; ++k) {
						inbox.putInt(base + 4 * k, b.getInt(16 * k));
					}
					GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
				}
			} else {
				GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, bytes, null, GLES30.GL_STREAM_READ);
			}
			device.bindFramebuffer(fbo);
			GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
					GLES20.GL_TEXTURE_2D, t.getId(), 0);
			GLES30.glReadPixels(0, 0, t.getWidth(), t.getHeight(), GLES30.GL_RGBA_INTEGER,
					GLES20.GL_UNSIGNED_INT, 0);
			pending[i] = true;
			device.bindFramebuffer(null);
		}
		GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
	}

	/** GL objects die with the context: forget them (fresh context) or delete them (live context). */
	void releaseGl(@Nullable GlDevice device) {
		if (pbos != null && device != null) {
			GLES30.glDeleteBuffers(pbos.length, pbos, 0);
			device.deleteFramebuffer(fbo);
		}
		pbos = null;
		pending = null;
		fbo = null;
		inbox = null;                            // re-opened (image sizes may change with the shader)
	}

	@Nullable
	private static MappedByteBuffer map(@NonNull File file, boolean write, int size) {
		try {
			if (write) {
				DIR.mkdirs();
			}
			if (!write && !file.exists()) {
				return null;
			}
			try (RandomAccessFile raf = new RandomAccessFile(file, write ? "rw" : "r");
					FileChannel ch = raf.getChannel()) {
				if (write && raf.length() != size) {
					raf.setLength(size);
				}
				long len = write ? size : raf.length();
				if (len < HDR) {
					return null;
				}
				MappedByteBuffer m = ch.map(write ? FileChannel.MapMode.READ_WRITE
						: FileChannel.MapMode.READ_ONLY, 0, len);
				m.order(ByteOrder.LITTLE_ENDIAN);
				return m;
			}
		} catch (Exception e) {
			Log.w(TAG, "cannot map " + file, e);
			return null;
		}
	}
}
