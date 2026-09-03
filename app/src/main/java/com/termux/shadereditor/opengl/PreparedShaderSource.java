package com.termux.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

final class PreparedShaderSource {
	record PreparedSection(
			@NonNull String name,
			boolean isImage,
			float scale,
			int updateRate,
			@NonNull PreparedShaderInput fragmentShader,
			@NonNull List<DiscoveredSampler> samplers) {
	}

	private final float fTimeMax;
	@Nullable
	private final String gles3VersionDirective;

	// Single-pass (multipass == false).
	@Nullable
	private final PreparedShaderInput fragmentShader;
	@NonNull
	private final BackBufferParameters backBufferParameters;

	// Multi-pass (multipass == true).
	@NonNull
	private final List<PreparedSection> sections;

	// User textures shared across every pass (either mode).
	@NonNull
	private final List<DiscoveredSampler> samplers;

	private PreparedShaderSource(
			float fTimeMax,
			@Nullable String gles3VersionDirective,
			@Nullable PreparedShaderInput fragmentShader,
			@NonNull BackBufferParameters backBufferParameters,
			@NonNull List<PreparedSection> sections,
			@NonNull List<DiscoveredSampler> samplers) {
		this.fTimeMax = fTimeMax;
		this.gles3VersionDirective = gles3VersionDirective;
		this.fragmentShader = fragmentShader;
		this.backBufferParameters = backBufferParameters;
		this.sections = List.copyOf(sections);
		this.samplers = List.copyOf(samplers);
	}

	@NonNull
	static PreparedShaderSource singlePass(
			@NonNull PreparedShaderInput fragmentShader,
			float fTimeMax,
			@Nullable String gles3VersionDirective,
			@NonNull BackBufferParameters backBufferParameters,
			@NonNull List<DiscoveredSampler> samplers) {
		return new PreparedShaderSource(
				fTimeMax,
				gles3VersionDirective,
				fragmentShader,
				backBufferParameters,
				List.of(),
				samplers);
	}

	@NonNull
	static PreparedShaderSource multiPass(
			float fTimeMax,
			@Nullable String gles3VersionDirective,
			@NonNull List<PreparedSection> sections,
			@NonNull List<DiscoveredSampler> samplers) {
		return new PreparedShaderSource(
				fTimeMax,
				gles3VersionDirective,
				null,
				new BackBufferParameters(),
				sections,
				samplers);
	}

	@NonNull
	static PreparedShaderSource empty() {
		return new PreparedShaderSource(
				3f,
				null,
				null,
				new BackBufferParameters(),
				List.of(),
				List.of());
	}

	boolean isMultipass() {
		return !sections.isEmpty();
	}

	@Nullable
	PreparedShaderInput getFragmentShader() {
		return fragmentShader;
	}

	float getFTimeMax() {
		return fTimeMax;
	}

	@NonNull
	BackBufferParameters getBackBufferParameters() {
		return backBufferParameters;
	}

	@NonNull
	List<PreparedSection> getSections() {
		return sections;
	}

	@NonNull
	List<DiscoveredSampler> getSamplers() {
		return samplers;
	}

	@NonNull
	String getVertexShader(
			@NonNull String vertexShader,
			@NonNull String vertexShader3,
			int version) {
		return version == 3 && gles3VersionDirective != null
				? gles3VersionDirective + "\n" + vertexShader3
				: vertexShader;
	}
}
