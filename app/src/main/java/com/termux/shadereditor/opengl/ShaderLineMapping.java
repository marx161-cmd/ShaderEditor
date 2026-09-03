package com.termux.shadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

final class ShaderLineMapping {
	private static final ShaderLineMapping IDENTITY =
			new ShaderLineMapping(List.of(), 0, 0);

	@NonNull
	private final List<InsertedLines> insertions;
	// Optional piecewise offset for multi-pass sections: lines <= preambleLines
	// map 1:1, lines > preambleLines are shifted by lineOffset. Applied after
	// the insertion removal, so these coordinates are the compiled-section
	// string (preamble + body). Zero for the plain single-pass mapping.
	private final int preambleLines;
	private final int lineOffset;

	private ShaderLineMapping(
			@NonNull List<InsertedLines> insertions,
			int preambleLines,
			int lineOffset) {
		this.insertions = List.copyOf(insertions);
		this.preambleLines = preambleLines;
		this.lineOffset = lineOffset;
	}

	@NonNull
	static ShaderLineMapping identity() {
		return IDENTITY;
	}

	@NonNull
	static ShaderLineMapping withLeadingInsertedLines(int lineCount) {
		return identity().addInsertedLinesAfterSourceLine(0, lineCount);
	}

	@NonNull
	ShaderLineMapping withSectionOffset(int preambleLines, int lineOffset) {
		return new ShaderLineMapping(insertions, preambleLines, lineOffset);
	}

	int toSourceLine(int preparedLine) {
		if (preparedLine < 1) {
			return -1;
		}

		int removedLines = 0;
		for (InsertedLines insertion : insertions) {
			int startLine = insertion.sourceLine + removedLines + 1;
			int endLine = startLine + insertion.lineCount - 1;
			if (preparedLine < startLine) {
				break;
			}
			if (preparedLine <= endLine) {
				return -1;
			}
			removedLines += insertion.lineCount;
		}

		int mapped = preparedLine - removedLines;
		if (preambleLines > 0 && mapped > preambleLines) {
			mapped += lineOffset;
		}
		return Math.max(mapped, -1);
	}

	@NonNull
	ShaderLineMapping addInsertedLinesAfterSourceLine(int sourceLine, int lineCount) {
		if (lineCount <= 0) {
			return this;
		}

		ArrayList<InsertedLines> updatedInsertions = new ArrayList<>(insertions);
		int last = updatedInsertions.size() - 1;
		if (last >= 0) {
			InsertedLines previous = updatedInsertions.get(last);
			if (previous.sourceLine == sourceLine) {
				updatedInsertions.set(last, new InsertedLines(
						sourceLine,
						previous.lineCount + lineCount));
				return new ShaderLineMapping(
						updatedInsertions,
						preambleLines,
						lineOffset);
			}
		}
		updatedInsertions.add(new InsertedLines(sourceLine, lineCount));
		return new ShaderLineMapping(
				updatedInsertions,
				preambleLines,
				lineOffset);
	}

	private static final class InsertedLines {
		private final int sourceLine;
		private final int lineCount;

		private InsertedLines(int sourceLine, int lineCount) {
			this.sourceLine = sourceLine;
			this.lineCount = lineCount;
		}
	}
}
