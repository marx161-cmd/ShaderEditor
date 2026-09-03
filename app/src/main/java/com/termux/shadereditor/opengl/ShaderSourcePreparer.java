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
		ShaderSectionParser.Parsed parsed = ShaderSectionParser.parse(source);
		if (parsed.isMultipass()) {
			return prepareMultiPass(
					parsed,
					gles3Version,
					fTimeMax,
					maxTextures);
		}
		return prepareSinglePass(
				source,
				gles3Version,
				fTimeMax,
				maxTextures);
	}

	@NonNull
	private static PreparedShaderSource prepareSinglePass(
			@NonNull String source,
			@Nullable String gles3Version,
			float fTimeMax,
			int maxTextures) {
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
				samplers);
	}

	@NonNull
	private static PreparedShaderSource prepareMultiPass(
			@NonNull ShaderSectionParser.Parsed parsed,
			@Nullable String gles3Version,
			float fTimeMax,
			int maxTextures) {
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

		Map<String, DiscoveredSampler> userSamplers = new LinkedHashMap<>();
		List<PreparedShaderSource.PreparedSection> sections = new ArrayList<>();

		for (ShaderSectionParser.Section section : parsed.sections()) {
			String compiledSource = preamble + section.body();
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

			for (DiscoveredSampler sampler : sectionSamplers) {
				if (userSamplers.size() < maxTextures) {
					userSamplers.putIfAbsent(sampler.name(), sampler);
				}
			}

			sections.add(new PreparedShaderSource.PreparedSection(
					section.name(),
					section.isImage(),
					section.scale(),
					section.updateRate(),
					input,
					sectionSamplers));
		}

		return PreparedShaderSource.multiPass(
				fTimeMax,
				gles3Version,
				sections,
				new ArrayList<>(userSamplers.values()));
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
