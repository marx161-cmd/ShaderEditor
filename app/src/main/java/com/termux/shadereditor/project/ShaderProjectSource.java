package com.termux.shadereditor.project;

import androidx.annotation.NonNull;

public interface ShaderProjectSource {
	@NonNull
	ShaderProjectOrigin getOrigin();

	@NonNull
	ShaderProjectSession openSession();
}
