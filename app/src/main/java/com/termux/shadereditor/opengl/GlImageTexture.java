package com.termux.shadereditor.opengl;

import android.opengl.GLES30;

import androidx.annotation.NonNull;

// Immutable-storage 2D/3D texture written by compute kernels
// (glBindImageTexture needs glTexStorage) and sampled by name elsewhere.
final class GlImageTexture implements GlTexture {
	private int id;
	private final int width;
	private final int height;
	private final int depth;
	@NonNull
	private final ComputeImageSpec.Format format;

	GlImageTexture(int id, int width, int height, int depth,
			@NonNull ComputeImageSpec.Format format) {
		this.id = id;
		this.width = width;
		this.height = height;
		this.depth = depth;
		this.format = format;
	}

	@Override
	public int getId() {
		return id;
	}

	@Override
	public int getTarget() {
		return depth > 1 ? GLES30.GL_TEXTURE_3D : GLES30.GL_TEXTURE_2D;
	}

	@Override
	public int getWidth() {
		return width;
	}

	@Override
	public int getHeight() {
		return height;
	}

	int getDepth() {
		return depth;
	}

	boolean is3D() {
		return depth > 1;
	}

	@NonNull
	ComputeImageSpec.Format getFormat() {
		return format;
	}

	@Override
	public boolean isValid() {
		return id != 0;
	}

	@Override
	public void invalidate() {
		id = 0;
	}
}
