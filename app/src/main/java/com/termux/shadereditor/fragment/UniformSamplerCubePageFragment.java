package com.termux.shadereditor.fragment;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.util.List;

import com.termux.shadereditor.activity.CubeMapActivity;
import com.termux.shadereditor.database.DataRecords;
import com.termux.shadereditor.database.DataSource;

public class UniformSamplerCubePageFragment
		extends UniformSampler2dPageFragment {
	@Override
	public void onCreate(Bundle state) {
		super.onCreate(state);
		setSamplerType(AbstractSamplerPropertiesFragment.SAMPLER_CUBE);
	}

	@Override
	protected void addTexture() {
		Activity activity = getActivity();
		if (activity == null) {
			return;
		}
		startActivity(new Intent(activity, CubeMapActivity.class));
	}

	@Override
	protected List<DataRecords.TextureInfo> getTextures(DataSource dataSource, String query) {
		// Override the parent method to return cube maps instead of regular textures.
		return dataSource.texture.getSamplerCubeTextures(query);
	}
}