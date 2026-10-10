package com.termux.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

final class PreparedShaderSource {
	record PreparedSection(
			@NonNull String name,
			boolean isImage,
			float scale,
			int fixedWidth,
			int fixedHeight,
			int updateRate,
			@NonNull PreparedShaderInput fragmentShader,
			@NonNull List<DiscoveredSampler> samplers,
			boolean isCompute,
			@NonNull int[] threads,
			@Nullable String sizeImage,
			int repeat) {
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

	// Shader-declared external textures (arbitrary named sampler2D backed by
	// raw files in ~/.shader).
	@NonNull
	private final List<ExternalTextureSpec> externalTextures;

	// Storage images for compute kernels (#define IMAGE_...).
	@NonNull
	private List<ComputeImageSpec> computeImages = List.of();

	// Set when the source can't be prepared (e.g. compute without GLES3).
	@NonNull
	private List<ShaderError> errors = List.of();

	private PreparedShaderSource(
			float fTimeMax,
			@Nullable String gles3VersionDirective,
			@Nullable PreparedShaderInput fragmentShader,
			@NonNull BackBufferParameters backBufferParameters,
			@NonNull List<PreparedSection> sections,
			@NonNull List<DiscoveredSampler> samplers,
			@NonNull List<ExternalTextureSpec> externalTextures) {
		this.fTimeMax = fTimeMax;
		this.gles3VersionDirective = gles3VersionDirective;
		this.fragmentShader = fragmentShader;
		this.backBufferParameters = backBufferParameters;
		this.sections = List.copyOf(sections);
		this.samplers = List.copyOf(samplers);
		this.externalTextures = List.copyOf(externalTextures);
	}

	@NonNull
	static PreparedShaderSource singlePass(
			@NonNull PreparedShaderInput fragmentShader,
			float fTimeMax,
			@Nullable String gles3VersionDirective,
			@NonNull BackBufferParameters backBufferParameters,
			@NonNull List<DiscoveredSampler> samplers,
			@NonNull List<ExternalTextureSpec> externalTextures) {
		return new PreparedShaderSource(
				fTimeMax,
				gles3VersionDirective,
				fragmentShader,
				backBufferParameters,
				List.of(),
				samplers,
				externalTextures);
	}

	@NonNull
	static PreparedShaderSource multiPass(
			float fTimeMax,
			@Nullable String gles3VersionDirective,
			@NonNull List<PreparedSection> sections,
			@NonNull List<DiscoveredSampler> samplers,
			@NonNull List<ExternalTextureSpec> externalTextures) {
		return new PreparedShaderSource(
				fTimeMax,
				gles3VersionDirective,
				null,
				new BackBufferParameters(),
				sections,
				samplers,
				externalTextures);
	}

	@NonNull
	static PreparedShaderSource empty() {
		return new PreparedShaderSource(
				3f,
				null,
				null,
				new BackBufferParameters(),
				List.of(),
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
	List<ExternalTextureSpec> getExternalTextures() {
		return externalTextures;
	}

	@NonNull
	List<ComputeImageSpec> getComputeImages() {
		return computeImages;
	}

	@NonNull
	List<ShaderError> getErrors() {
		return errors;
	}

	@NonNull
	PreparedShaderSource withCompute(
			@NonNull List<ComputeImageSpec> computeImages,
			@NonNull List<ShaderError> errors) {
		this.computeImages = List.copyOf(computeImages);
		this.errors = List.copyOf(errors);
		return this;
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
