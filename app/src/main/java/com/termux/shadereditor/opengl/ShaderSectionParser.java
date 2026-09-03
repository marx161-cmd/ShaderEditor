package com.termux.shadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a shader document into a shared preamble plus inline-delimited
 * render sections.
 *
 * <p>Delimiter grammar (a full line, whitespace-trimmed on both ends):
 *
 * <pre>==name [scale] [@updateRate]==</pre>
 *
 * Everything before the first delimiter is the shared preamble, prepended to
 * every section's compiled source. No delimiter anywhere means single-pass
 * (returns zero sections) and the existing render path is used unchanged.
 */
final class ShaderSectionParser {
	static final String IMAGE_SECTION = "image";

	private static final Pattern DELIMITER = Pattern.compile(
			"^[ \\t]*==[ \\t]*([A-Za-z_]\\w*)[ \\t]*" +
					"(?:([0-9]*\\.?[0-9]+)[ \\t]*)?" +
					"(?:@[ \\t]*([1-9][0-9]*)[ \\t]*)?" +
					"==[ \\t]*$");

	record Section(
			@NonNull String name,
			boolean isImage,
			float scale,
			int updateRate,
			int delimiterLine,
			@NonNull String body) {
	}

	record Parsed(
			@NonNull String preamble,
			@NonNull List<Section> sections) {
		boolean isMultipass() {
			return !sections.isEmpty();
		}
	}

	private ShaderSectionParser() {
	}

	@NonNull
	static Parsed parse(@NonNull String source) {
		if (source.trim().isEmpty()) {
			return new Parsed("", List.of());
		}

		List<Delimiter> delimiters = findDelimiters(source);
		if (delimiters.isEmpty()) {
			return new Parsed("", List.of());
		}

		String preamble = source.substring(0, delimiters.get(0).lineStart);

		List<Section> sections = new ArrayList<>(delimiters.size());
		for (int i = 0; i < delimiters.size(); ++i) {
			Delimiter delimiter = delimiters.get(i);
			int bodyStart = delimiter.lineEnd < source.length()
					? delimiter.lineEnd + 1
					: source.length();
			int bodyEnd = i + 1 < delimiters.size()
					? delimiters.get(i + 1).lineStart
					: source.length();
			String body = source.substring(bodyStart, bodyEnd);
			sections.add(new Section(
					delimiter.name,
					IMAGE_SECTION.equals(delimiter.name),
					delimiter.scale,
					delimiter.updateRate,
					delimiter.lineNumber,
					body));
		}

		return new Parsed(preamble, sections);
	}

	private static final class Delimiter {
		final String name;
		final float scale;
		final int updateRate;
		final int lineNumber;
		final int lineStart;
		final int lineEnd;

		Delimiter(
				String name,
				float scale,
				int updateRate,
				int lineNumber,
				int lineStart,
				int lineEnd) {
			this.name = name;
			this.scale = scale;
			this.updateRate = updateRate;
			this.lineNumber = lineNumber;
			this.lineStart = lineStart;
			this.lineEnd = lineEnd;
		}
	}

	private static List<Delimiter> findDelimiters(String source) {
		List<Delimiter> delimiters = new ArrayList<>();
		int lineStart = 0;
		int lineNumber = 1;
		while (lineStart <= source.length()) {
			int lineEnd = source.indexOf('\n', lineStart);
			if (lineEnd < 0) {
				lineEnd = source.length();
			}
			String line = source.substring(lineStart, lineEnd);
			if (line.endsWith("\r")) {
				line = line.substring(0, line.length() - 1);
			}
			Matcher matcher = DELIMITER.matcher(line);
			if (matcher.matches()) {
				String name = matcher.group(1);
				float scale = matcher.group(2) != null
						? Float.parseFloat(matcher.group(2))
						: 1f;
				int updateRate = matcher.group(3) != null
						? Integer.parseInt(matcher.group(3))
						: 1;
				delimiters.add(new Delimiter(
						name,
						scale,
						updateRate,
						lineNumber,
						lineStart,
						lineEnd));
			}
			if (lineEnd >= source.length()) {
				break;
			}
			lineStart = lineEnd + 1;
			++lineNumber;
		}
		return delimiters;
	}
}
