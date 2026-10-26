package com.termux.shadereditor.opengl;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shadereditor.service.ShaderControls;

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
 * Return header bytes 32..63: CONTROL NOTIFICATION button counters (v116), u32 each in BUTTON_ order.
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
	// RETURN FILE v2 (v117): the touch block gained the 3-finger tap's position (the brain zones taps by screen half)
	// and a GESTURE block carries the live pointers, so the brain can fly the camera from swipes/pinch/twist.
	// v1 = boops/boop xy/3-finger count (16 bytes). v2 = 24 + 44 bytes, blob table after both.
	private static final int IN_VERSION = 2;
	private static final int TOUCH_OFF = HDR;                 // v2: 24 bytes
	private static final int TOUCH_LEN = 24;
	private static final int GEST_OFF = HDR + TOUCH_LEN;      // v2: 44 bytes (count, moved, seq, 4 x (x, y))
	private static final int GEST_LEN = 44;
	private static final int BLOB_OFF = GEST_OFF + GEST_LEN;
	private static final int EXPORT_EVERY = 6;
	private static final long TAP_MAX_NS = 250_000_000L;
	private static final float TAP_SLOP_PX = 40f;
	private static final long RETRY_NS = 1_000_000_000L;
	// PLAYBACK (v114, retuned v118): the producer's steps arrive irregularly -- the Pixel's Termux stalls up to
	// 78-100 ms and then bursts. The playback delay must outlast the WORST STALL or the newest snapshot is older than
	// the playback moment, the app clamps, and every body freezes and then jumps in unison (the "moons jitter
	// together" symptom, 2026-10-25). 0.120 s covers a 100 ms stall with a snapshot to spare; HIST 12 keeps ~400 ms
	// of history so a burst always finds its bracketing pair.
	private static final double DELAY_S = 0.120;
	private static final int HIST = 12;

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
	// snapshot ring: time, values (MAX_REC x 4), interp flags (prev -> this snapshot)
	private final double[] histT = new double[HIST];
	private final float[][] histV = new float[HIST][MAX_REC * 4];
	private final boolean[][] histI = new boolean[HIST][MAX_REC];
	private int histN;
	private int histHead;                  // index of the newest snapshot
	private double lastTs;
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
	private volatile float tap3X;
	private volatile float tap3Y;
	private long downNs;
	private float downX;
	private float downY;
	private int maxPointers;
	private boolean moved;
	// v117 gestures: live pointers in canvas px (y up) + a sequence id, written to the GEST block every frame
	private final float[] ptrX = new float[4];
	private final float[] ptrY = new float[4];
	private volatile int ptrN;
	private volatile int gestMoved;
	private volatile int gestSeq;

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
				++gestSeq;                            // a new touch sequence: the brain re-anchors its gesture state
				gestMoved = 0;
				updatePointers(e);
			}
			case MotionEvent.ACTION_POINTER_DOWN -> {
				maxPointers = Math.max(maxPointers, e.getPointerCount());
				updatePointers(e);
			}
			case MotionEvent.ACTION_MOVE -> {
				if (Math.abs(e.getX() - downX) > TAP_SLOP_PX ||
						Math.abs(e.getY() - downY) > TAP_SLOP_PX) {
					moved = true;
					gestMoved = 1;
				}
				updatePointers(e);
			}
			case MotionEvent.ACTION_UP -> {
				if (!moved && now - downNs < TAP_MAX_NS * (maxPointers >= 3 ? 2 : 1)) {
					if (maxPointers >= 3) {
						// v117: where the 3-finger tap happened (centroid, canvas px, y up) -> the brain's cam zones
						int n = Math.max(1, e.getPointerCount());
						float cx = 0f, cy = 0f;
						for (int i = 0; i < n; ++i) {
							cx += e.getX(i);
							cy += e.getY(i);
						}
						tap3X = cx / n;
						tap3Y = canvasH - cy / n;
						++taps3;
					} else if (maxPointers == 1) {
						boopX = e.getX();
						boopY = canvasH - e.getY();   // canvas px, y up (GL fragCoord)
						++boops;
					}
				}
				ptrN = 0;
			}
			case MotionEvent.ACTION_CANCEL -> ptrN = 0;
			default -> {
			}
		}
	}

	/** v117: current pointers in canvas px (y up); a lifted finger simply leaves the remaining ones. */
	private void updatePointers(@NonNull MotionEvent e) {
		int n = Math.min(e.getPointerCount(), 4);
		for (int i = 0; i < n; ++i) {
			ptrX[i] = e.getX(i);
			ptrY[i] = canvasH - e.getY(i);
		}
		for (int i = n; i < 4; ++i) {
			ptrX[i] = 0f;
			ptrY[i] = 0f;
		}
		ptrN = n;
	}

	// ---- per frame (GL thread) ----
	void apply(@NonNull List<ProgramBindings> bindings, long nowNs) {
		if (!readFeed(nowNs) || count == 0 || histN == 0) {
			return;
		}
		double ts = nowNs / 1e9 - DELAY_S;
		// u_feedDt (v115): the step between this frame's sample moment and the last one -- the shader's own sim
		// (ring grains) advances on the SAME clock as the played-back bodies and camera
		float feedDt = lastTs > 0 ? (float) Math.max(0.0, Math.min(0.1, ts - lastTs)) : 0f;
		lastTs = ts;
		// bracketing snapshots a (older) <= ts <= b (newer); clamp at both ends (no extrapolation)
		int b = histHead, a = histHead;
		for (int k = 0; k < histN - 1; ++k) {
			int newer = (histHead - k + HIST) % HIST;
			int older = (newer - 1 + HIST) % HIST;
			a = b = older;
			if (histT[older] <= ts) {
				b = newer;
				break;
			}
		}
		if (ts >= histT[histHead]) {
			a = b = histHead;
		}
		float t = 1f;
		double h = histT[b] - histT[a];
		if (a != b && h > 1e-6) {
			t = (float) Math.max(0.0, Math.min(1.0, (ts - histT[a]) / h));
		}
		// Catmull-Rom (v115, non-uniform times): a curve through the neighbours instead of straight segments,
		// so a camera on a curving path has no kink at every producer step. One-sided where a neighbour is
		// missing or that transition was flagged discontinuous.
		int a0 = (a - 1 + HIST) % HIST, b1 = (b + 1) % HIST;
		boolean hasA0 = a != b && (histHead - a + HIST) % HIST + 1 <= histN - 1;   // a0 inside the kept history
		boolean hasB1 = a != b && b != histHead && histT[b1] > histT[b];
		float t2 = t * t, t3 = t2 * t;
		float h00 = 2 * t3 - 3 * t2 + 1, h10 = t3 - 2 * t2 + t, h01 = -2 * t3 + 3 * t2, h11 = t3 - t2;
		float[] va = histV[a], vb = histV[b], v0 = histV[a0], v3 = histV[b1];
		boolean[] ib = histI[b], ia = histI[a], i3 = histI[b1];
		for (int i = 0; i < count; ++i) {
			float[] o = out[i];
			boolean lerp = ib[i] && a != b;
			for (int k = 0; k < comps[i]; ++k) {
				float p1 = va[i * 4 + k], p2 = vb[i * 4 + k];
				if (!lerp) {
					o[k] = p2;
					continue;
				}
				double sec = (p2 - p1) / h;
				double m1 = hasA0 && ia[i] ? (p2 - v0[i * 4 + k]) / (histT[b] - histT[a0]) : sec;
				double m2 = hasB1 && i3[i] ? (v3[i * 4 + k] - p1) / (histT[b1] - histT[a]) : sec;
				o[k] = (float) (h00 * p1 + h10 * h * m1 + h01 * p2 + h11 * h * m2);
			}
			for (ProgramBindings pb : bindings) {
				set(pb, i, o);
			}
		}
		for (ProgramBindings pb : bindings) {
			pb.setFloat("u_feedDt", feedDt);
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
				histN = 0;                           // the record layout changed: old snapshots don't apply
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
		if (n != count) {
			histN = 0;
		}
		count = n;
		tPrev = tp;
		tCur = tc;
		lastSeq = seq1;
		if (histN == 0) {                        // seed with the file's previous snapshot too
			push(tp, prev, null);
		}
		if (histN == 0 || tc > histT[histHead]) {
			push(tc, cur, interp);
		}
		return true;
	}

	private void push(double t, @NonNull float[][] v, @Nullable boolean[] ip) {
		histHead = (histHead + 1) % HIST;
		histT[histHead] = t;
		float[] dst = histV[histHead];
		for (int i = 0; i < count; ++i) {
			System.arraycopy(v[i], 0, dst, i * 4, 4);
			histI[histHead][i] = ip != null && ip[i];
		}
		histN = Math.min(histN + 1, HIST);
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
		int o = BLOB_OFF + 4 + 8 * n;            // nblobs, then (offset, texels) per blob, then the blobs
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
		inbox.putInt(BLOB_OFF, n);
		for (int i = 0; i < n; ++i) {
			inbox.putInt(BLOB_OFF + 4 + 8 * i, exportOffsets[i]);
			inbox.putInt(BLOB_OFF + 8 + 8 * i, exportTexels[i]);
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
		m.putInt(4, IN_VERSION);
		m.putInt(12, visible ? 1 : 0);
		m.putDouble(16, nowNs / 1e9);
		m.putFloat(24, canvasW);
		m.putFloat(28, canvasH);
		m.putInt(TOUCH_OFF, boops);
		m.putFloat(TOUCH_OFF + 4, boopX);
		m.putFloat(TOUCH_OFF + 8, boopY);
		m.putInt(TOUCH_OFF + 12, taps3);
		m.putFloat(TOUCH_OFF + 16, tap3X);       // v2: the 3-finger tap's position (canvas px, y up)
		m.putFloat(TOUCH_OFF + 20, tap3Y);
		m.putInt(GEST_OFF, ptrN);                // v2: live pointers -> the brain's gesture camera
		m.putInt(GEST_OFF + 4, gestMoved);
		m.putInt(GEST_OFF + 8, gestSeq);
		for (int i = 0; i < 4; ++i) {
			m.putFloat(GEST_OFF + 12 + 8 * i, ptrX[i]);
			m.putFloat(GEST_OFF + 16 + 8 * i, ptrY[i]);
		}
		for (int i = 0; i < ShaderControls.MAX; ++i) {   // CONTROL NOTIFICATION presses (v116), header 32..63
			m.putInt(32 + 4 * i, ShaderControls.count(i));
		}
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
