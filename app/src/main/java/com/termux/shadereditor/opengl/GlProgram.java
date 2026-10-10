package com.termux.shadereditor.opengl;

import java.util.HashMap;
import java.util.Map;

final class GlProgram {
	private int id;
	private final Map<String, Integer> uniformLocations = new HashMap<>();
	private final Map<String, Integer> attribLocations = new HashMap<>();
	// Active uniform name -> GL type, filled on first query.
	private Map<String, Integer> uniformTypes;
	private final int[] workGroupSize = new int[]{1, 1, 1};
	private boolean compute;

	GlProgram(int id) {
		this.id = id;
	}

	int getId() {
		return id;
	}

	boolean isValid() {
		return id != 0;
	}

	Map<String, Integer> getUniformLocations() {
		return uniformLocations;
	}

	Map<String, Integer> getAttribLocations() {
		return attribLocations;
	}

	Map<String, Integer> getUniformTypes() {
		return uniformTypes;
	}

	void setUniformTypes(Map<String, Integer> uniformTypes) {
		this.uniformTypes = uniformTypes;
	}

	boolean isCompute() {
		return compute;
	}

	int[] getWorkGroupSize() {
		return workGroupSize;
	}

	void setCompute(int[] workGroupSize) {
		compute = true;
		System.arraycopy(workGroupSize, 0, this.workGroupSize, 0, 3);
	}

	void invalidate() {
		id = 0;
		uniformLocations.clear();
		attribLocations.clear();
	}
}
