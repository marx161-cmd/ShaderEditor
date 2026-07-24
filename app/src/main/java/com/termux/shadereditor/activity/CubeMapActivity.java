package com.termux.shadereditor.activity;

import android.os.Bundle;

import com.termux.shadereditor.R;
import com.termux.shadereditor.fragment.CubeMapFragment;
import com.termux.shadereditor.view.SystemBarMetrics;
import com.termux.shadereditor.widget.CubeMapView;

public class CubeMapActivity
		extends AbstractSubsequentActivity
		implements CubeMapFragment.CubeMapViewProvider {
	private CubeMapView cubeMapImageView;

	@Override
	public CubeMapView getCubeMapView() {
		return cubeMapImageView;
	}

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_cube_map);

		cubeMapImageView = findViewById(R.id.cube_map_view);

		SystemBarMetrics.initMainLayout(this, cubeMapImageView.insets);
		AbstractSubsequentActivity.initToolbar(this);

		if (state == null) {
			setFragment(getSupportFragmentManager(), new CubeMapFragment());
		}
	}
}
