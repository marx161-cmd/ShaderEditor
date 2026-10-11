package com.termux.shadereditor.opengl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.termux.shadereditor.fragment.AbstractSamplerPropertiesFragment;

final class ShaderSourcePreparer {
	private static final String SAMPLER_2D = "2D";
	private static final String SAMPLER_CUBE = "Cube";
	private static final String SAMPLER_EXTERNAL_OES = "ExternalOES";
	private static final Pattern PATTERN_SAMPLER = Pattern.compile(
			String.format(
					"uniform[ \t]+sampler(" +
							SAMPLER_2D + "|" +
							SAMPLER_CUBE + "|" +
							SAMPLER_EXTERNAL_OES +
							")+[ \t]+(%s);[ \t]*(.*)",
					AbstractSamplerPropertiesFragment.TEXTURE_NAME_PATTERN));
	private static final Pattern PATTERN_FTIME = Pattern.compile(
			"^#define[ \\t]+FTIME_PERIOD[ \\t]+([0-9.]+)[ \\t]*$",
			Pattern.MULTILINE);
	private static final Pattern PATTERN_GLES3_VERSION = Pattern.compile(
			"^#version 3[0-9]{2} es$",
			Pattern.MULTILINE);
	private static final Pattern PATTERN_EXT_TEXTURE = Pattern.compile(
			"^#define[ \\t]+EXT_TEXTURE_([A-Za-z_][A-Za-z0-9_]*)[ \\t]+" +
					"(r32f|rgba8|rgba16f)[ \\t]+" +
					"([0-9]+)[ \\t]+([0-9]+)[ \\t]*$",
			Pattern.MULTILINE);
	private static final Pattern PATTERN_BACKDROP = Pattern.compile(
			"^#define[ \\t]+BACKDROP_([A-Za-z_][A-Za-z0-9_]*)[ \\t]+" +
					"\"?([A-Za-z0-9_.\\-]+)\"?[ \\t]*$",
			Pattern.MULTILINE);
	private static final Pattern PATTERN_IMAGE = Pattern.compile(
			"^#define[ \\t]+IMAGE_([A-Za-z_][A-Za-z0-9_]*)[ \\t]+" +
					"([a-z0-9]+)[ \\t]+" +
					"(?:([0-9]+)[xX]([0-9]+)(?:[xX]([0-9]+))?|(full))" +
					"(?:[ \\t]+(swap))?[ \\t]*$",
			Pattern.MULTILINE);
	private static final Pattern PATTERN_VERSION_LINE = Pattern.compile(
			"^[ \\t]*#version[^\\n]*",
			Pattern.MULTILINE);
	private static final String COMPUTE_VERSION = "#version 320 es";
	// Images have no default precision; samplers 3D/integer have none in
	// ESSL 3.00 fragment shaders either.
	private static final Pattern PATTERN_EXTENSION_LINE = Pattern.compile(
			"^[ \\t]*#extension[^\\n]*\\n",
			Pattern.MULTILINE);
	private static final String COMPUTE_DEFINE = "#define COMPUTE 1\n";
	private static final String COMPUTE_HEADER =
			"precision highp float;\n" +
					"precision highp int;\n" +
					"precision highp image2D;\n" +
					"precision highp image3D;\n" +
					"precision highp uimage2D;\n" +
					"precision highp uimage3D;\n" +
					"precision highp iimage2D;\n" +
					"precision highp iimage3D;\n" +
					"precision highp sampler3D;\n" +
					"precision highp usampler2D;\n" +
					"precision highp usampler3D;\n" +
					"precision highp isampler2D;\n" +
					"precision highp isampler3D;\n";
	private static final String FRAGMENT_IMAGE_HEADER =
			"precision highp sampler3D;\n" +
					"precision highp usampler2D;\n" +
					"precision highp usampler3D;\n" +
					"precision highp isampler2D;\n" +
					"precision highp isampler3D;\n";
	private static final String OES_EXTERNAL =
			"#extension GL_OES_EGL_image_external : require\n";
	private static final String OES_EXTERNAL_ESS3 =
			"#extension GL_OES_EGL_image_external_essl3 : require\n";
	private static final String SHADER_EDITOR =
			"#define SHADER_EDITOR 1\n";

	private ShaderSourcePreparer() {
	}

	@NonNull
	static PreparedShaderSource prepare(
			@Nullable String source,
			int version,
			int maxTextures) {
		float fTimeMax = parseFTime(source);
		if (source == null) {
			return PreparedShaderSource.empty();
		}

		String gles3Version = getGLES3Version(source, version);
		List<ExternalTextureSpec> externalTextures = parseExternalTextures(source);
		ShaderSectionParser.Parsed parsed = ShaderSectionParser.parse(source);
		if (parsed.isMultipass()) {
			List<ShaderError> errors = new ArrayList<>();
			List<ComputeImageSpec> images = parseComputeImages(source, errors);
			return prepareMultiPass(
					parsed,
					gles3Version,
					fTimeMax,
					maxTextures,
					externalTextures,
					images,
					errors).withCompute(images, errors);
		}
		return prepareSinglePass(
				source,
				gles3Version,
				fTimeMax,
				maxTextures,
				externalTextures);
	}

	@NonNull
	private static PreparedShaderSource prepareSinglePass(
			@NonNull String source,
			@Nullable String gles3Version,
			float fTimeMax,
			int maxTextures,
			@NonNull List<ExternalTextureSpec> externalTextures) {
		PreparedShaderInput preparedInput = new PreparedShaderInput(
				source,
				ShaderLineMapping.identity());
		BackBufferParameters backBufferParameters = new BackBufferParameters();
		ArrayList<DiscoveredSampler> samplers = new ArrayList<>();

		preparedInput = scanSamplers(
				source,
				gles3Version,
				preparedInput,
				null,
				backBufferParameters,
				samplers,
				maxTextures);

		if (!preparedInput.getSource().contains(SHADER_EDITOR)) {
			preparedInput = addPreprocessorDirective(preparedInput, SHADER_EDITOR);
		}

		return PreparedShaderSource.singlePass(
				preparedInput,
				fTimeMax,
				gles3Version,
				backBufferParameters,
				samplers,
				externalTextures);
	}

	@NonNull
	private static PreparedShaderSource prepareMultiPass(
			@NonNull ShaderSectionParser.Parsed parsed,
			@Nullable String gles3Version,
			float fTimeMax,
			int maxTextures,
			@NonNull List<ExternalTextureSpec> externalTextures,
			@NonNull List<ComputeImageSpec> images,
			@NonNull List<ShaderError> errors) {
		String preamble = parsed.preamble();
		int preambleLines = preamble.isEmpty()
				? 0
				: countNewlines(preamble);

		Set<String> excludedNames = new HashSet<>();
		for (ShaderSectionParser.Section section : parsed.sections()) {
			if (!section.isImage()) {
				excludedNames.add(section.name());
			}
		}
		excludedNames.add(ShaderRenderer.UNIFORM_BACKBUFFER);
		// Images are bound by the pipeline, never user textures.
		for (ComputeImageSpec image : images) {
			excludedNames.add(image.name());
			excludedNames.add(image.name() + ComputeImageSpec.OUT_SUFFIX);
		}
		String computeDefines = buildComputeDefines(images);

		Map<String, DiscoveredSampler> userSamplers = new LinkedHashMap<>();
		List<PreparedShaderSource.PreparedSection> sections = new ArrayList<>();

		// The app consumes the IMAGE_ lines; some GLSL compilers (PowerVR)
		// reject "16384x1" even in an unused #define body, so comment them
		// out (same line, so line numbers don't move).
		String compiledPreamble = PATTERN_IMAGE.matcher(preamble)
				.replaceAll(match -> Matcher.quoteReplacement(
						"// " + match.group().replaceFirst("^#", "")));
		for (ShaderSectionParser.Section section : parsed.sections()) {
			String compiledSource = compiledPreamble + section.body();
			if (section.isCompute()) {
				if (gles3Version == null) {
					errors.add(ShaderError.createGeneral(
							"compute section '" + section.name() +
									"' needs a GLES 3 context and " +
									"#version 300 es (or later) on line 1"));
					continue;
				}
				// Same line count, so the line mapping stays valid.
				compiledSource = PATTERN_VERSION_LINE.matcher(compiledSource)
						.replaceFirst(COMPUTE_VERSION);
			}
			ShaderLineMapping lineMapping = ShaderLineMapping.identity()
					.withSectionOffset(
							preambleLines,
							section.delimiterLine() - preambleLines);
			PreparedShaderInput input = new PreparedShaderInput(
					compiledSource,
					lineMapping);

			ArrayList<DiscoveredSampler> sectionSamplers = new ArrayList<>();
			input = scanSamplers(
					compiledSource,
					gles3Version,
					input,
					excludedNames,
					null,
					sectionSamplers,
					maxTextures);

			if (!input.getSource().contains(SHADER_EDITOR)) {
				input = addPreprocessorDirective(input, SHADER_EDITOR);
			}
			if (section.isCompute()) {
				input = addPreprocessorDirective(input,
						COMPUTE_DEFINE + computeDefines);
				input = addAfterExtensions(input, COMPUTE_HEADER);
			} else if (!images.isEmpty() && gles3Version != null) {
				input = addAfterExtensions(input, FRAGMENT_IMAGE_HEADER);
			}

			for (DiscoveredSampler sampler : sectionSamplers) {
				if (userSamplers.size() < maxTextures) {
					userSamplers.putIfAbsent(sampler.name(), sampler);
				}
			}

			sections.add(new PreparedShaderSource.PreparedSection(
					section.name(),
					section.isImage(),
					section.scale(),
					section.fixedWidth(),
					section.fixedHeight(),
					section.updateRate(),
					input,
					sectionSamplers,
					section.isCompute(),
					section.threads(),
					section.sizeImage(),
					section.repeat()));
		}

		return PreparedShaderSource.multiPass(
				fTimeMax,
				gles3Version,
				sections,
				new ArrayList<>(userSamplers.values()),
				externalTextures);
	}

	@NonNull
	private static PreparedShaderInput scanSamplers(
			@NonNull String source,
			@Nullable String gles3Version,
			@NonNull PreparedShaderInput preparedInput,
			@Nullable Set<String> excludedNames,
			@Nullable BackBufferParameters backBufferParameters,
			@NonNull List<DiscoveredSampler> outSamplers,
			int maxTextures) {
		for (Matcher matcher = PATTERN_SAMPLER.matcher(source);
				matcher.find() && outSamplers.size() < maxTextures; ) {
			String type = matcher.group(1);
			String name = matcher.group(2);
			String params = matcher.group(3);

			if (type == null || name == null) {
				continue;
			}

			if (backBufferParameters != null &&
					ShaderRenderer.UNIFORM_BACKBUFFER.equals(name)) {
				backBufferParameters.parse(params);
				continue;
			}

			if (excludedNames != null && excludedNames.contains(name)) {
				continue;
			}

			int target;
			boolean external = false;
			switch (type) {
				case SAMPLER_2D:
					target = GLES20.GL_TEXTURE_2D;
					break;
				case SAMPLER_CUBE:
					target = GLES20.GL_TEXTURE_CUBE_MAP;
					break;
				case SAMPLER_EXTERNAL_OES:
					target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
					external = true;
					break;
				default:
					target = -1;
					break;
			}
			if (target < 0) {
				continue;
			}
			if (external) {
				String pattern = gles3Version != null
						? OES_EXTERNAL_ESS3
						: OES_EXTERNAL;
				if (!preparedInput.getSource().contains(pattern)) {
					preparedInput = addPreprocessorDirective(preparedInput, pattern);
				}
			}

			outSamplers.add(new DiscoveredSampler(
					name,
					target,
					new TextureParameters(params)));
		}
		return preparedInput;
	}

	private static float parseFTime(@Nullable String source) {
		if (source != null) {
			Matcher matcher = PATTERN_FTIME.matcher(source);
			String period;
			if (matcher.find() &&
					matcher.groupCount() > 0 &&
					(period = matcher.group(1)) != null) {
				return Float.parseFloat(period);
			}
		}
		return 3f;
	}

	@Nullable
	private static String getGLES3Version(@NonNull String source, int version) {
		Matcher matcher = PATTERN_GLES3_VERSION.matcher(source);
		return version == 3 && matcher.find() ? matcher.group(0) : null;
	}

	@NonNull
	private static List<ExternalTextureSpec> parseExternalTextures(
			@Nullable String source) {
		if (source == null) {
			return List.of();
		}
		List<ExternalTextureSpec> specs = new ArrayList<>();
		Matcher matcher = PATTERN_EXT_TEXTURE.matcher(source);
		while (matcher.find() &&
				specs.size() < ExternalTextureBridge.MAX_EXTERNAL_TEXTURES) {
			ExternalTextureSpec.Format format = switch (matcher.group(2)) {
				case "r32f" -> ExternalTextureSpec.Format.R32F;
				case "rgba8" -> ExternalTextureSpec.Format.RGBA8;
				case "rgba16f" -> ExternalTextureSpec.Format.RGBA16F;
				default -> null;
			};
			if (format == null) {
				continue;
			}
			int width = Integer.parseInt(matcher.group(3));
			int height = Integer.parseInt(matcher.group(4));
			if (width < 1 || height < 1) {
				continue;
			}
			specs.add(new ExternalTextureSpec(
					matcher.group(1),
					format,
					width,
					height,
					null));
		}
		Matcher backdrop = PATTERN_BACKDROP.matcher(source);
		while (backdrop.find() &&
				specs.size() < ExternalTextureBridge.MAX_EXTERNAL_TEXTURES) {
			String file = backdrop.group(2);
			if (file.contains("..")) {
				continue;
			}
			specs.add(new ExternalTextureSpec(
					backdrop.group(1),
					ExternalTextureSpec.Format.BITMAP,
					0,
					0,
					file));
		}
		return specs;
	}

	@NonNull
	private static List<ComputeImageSpec> parseComputeImages(
			@NonNull String source,
			@NonNull List<ShaderError> errors) {
		List<ComputeImageSpec> images = new ArrayList<>();
		Set<String> names = new HashSet<>();
		Matcher matcher = PATTERN_IMAGE.matcher(source);
		while (matcher.find()) {
			String name = matcher.group(1);
			ComputeImageSpec.Format format =
					ComputeImageSpec.Format.parse(matcher.group(2));
			if (format == null) {
				errors.add(ShaderError.createGeneral("IMAGE_" + name +
						": unknown format '" + matcher.group(2) +
						"' (rgba32f rgba16f rgba8 r32f r16f r32ui r32i)"));
				continue;
			}
			if (!names.add(name)) {
				errors.add(ShaderError.createGeneral(
						"IMAGE_" + name + " declared twice"));
				continue;
			}
			boolean full = matcher.group(6) != null;
			int width = full ? 0 : Integer.parseInt(matcher.group(3));
			int height = full ? 0 : Integer.parseInt(matcher.group(4));
			int depth = matcher.group(5) != null
					? Integer.parseInt(matcher.group(5))
					: 1;
			if (!full && (width < 1 || height < 1 || depth < 1)) {
				errors.add(ShaderError.createGeneral(
						"IMAGE_" + name + ": size must be at least 1"));
				continue;
			}
			images.add(new ComputeImageSpec(
					name,
					format,
					width,
					height,
					depth,
					full,
					matcher.group(7) != null));
		}
		return images;
	}

	// <name>_UNIT macros for layout(binding = ...): each image (and its
	// _out copy) gets the image unit the pipeline binds it to.
	@NonNull
	private static String buildComputeDefines(
			@NonNull List<ComputeImageSpec> images) {
		StringBuilder sb = new StringBuilder();
		int unit = 0;
		for (ComputeImageSpec image : images) {
			sb.append("#define ").append(image.name())
					.append(ComputeImageSpec.UNIT_SUFFIX).append(' ')
					.append(unit++).append('\n');
			if (image.swap()) {
				sb.append("#define ").append(image.name())
						.append(ComputeImageSpec.OUT_SUFFIX)
						.append(ComputeImageSpec.UNIT_SUFFIX).append(' ')
						.append(unit++).append('\n');
			}
		}
		return sb.toString();
	}

	static int countImageUnits(@NonNull List<ComputeImageSpec> images) {
		int units = 0;
		for (ComputeImageSpec image : images) {
			units += image.swap() ? 2 : 1;
		}
		return units;
	}

	// Precision statements are not preprocessor lines, so they must follow
	// every #extension directive.
	@NonNull
	private static PreparedShaderInput addAfterExtensions(
			@NonNull PreparedShaderInput input,
			@NonNull String text) {
		String source = input.getSource();
		int insertAt = -1;
		Matcher matcher = PATTERN_EXTENSION_LINE.matcher(source);
		while (matcher.find()) {
			insertAt = matcher.end();
		}
		if (insertAt < 0) {
			return addPreprocessorDirective(input, text);
		}
		// Earlier insertions all sit right after #version, i.e. above
		// this point, so subtract them to get the original line.
		int sourceLine = countSourceLines(source, insertAt) -
				input.getLineMapping().insertedLineCount();
		return new PreparedShaderInput(
				source.substring(0, insertAt) + text + source.substring(insertAt),
				input.getLineMapping().addInsertedLinesAfterSourceLine(
						sourceLine,
						countLines(text)));
	}

	@NonNull
	private static PreparedShaderInput addPreprocessorDirective(
			@NonNull PreparedShaderInput input,
			@NonNull String directive) {
		String source = input.getSource();
		ShaderLineMapping lineMapping = input.getLineMapping();
		int insertedLines = countLines(directive);
		if (source.trim().startsWith("#version")) {
			int lineFeed = source.indexOf("\n");
			if (lineFeed < 0) {
				return input;
			}
			int sourceLine = countSourceLines(source, lineFeed + 1);
			++lineFeed;
			return new PreparedShaderInput(
					source.substring(0, lineFeed) +
							directive +
							source.substring(lineFeed),
					lineMapping.addInsertedLinesAfterSourceLine(
							sourceLine,
							insertedLines));
		}
		return new PreparedShaderInput(
				directive + source,
				lineMapping.addInsertedLinesAfterSourceLine(0, insertedLines));
	}

	private static int countSourceLines(@NonNull String source, int endExclusive) {
		int lines = 0;
		for (int index = 0; index < endExclusive; ++index) {
			if (source.charAt(index) == '\n') {
				++lines;
			}
		}
		return lines;
	}

	private static int countNewlines(@NonNull String source) {
		int lines = 0;
		for (int index = 0; index < source.length(); ++index) {
			if (source.charAt(index) == '\n') {
				++lines;
			}
		}
		return lines;
	}

	private static int countLines(@NonNull String source) {
		int lines = 1;
		for (int index = 0; index < source.length(); ++index) {
			if (source.charAt(index) == '\n') {
				++lines;
			}
		}
		return source.endsWith("\n") ? lines - 1 : lines;
	}
}
