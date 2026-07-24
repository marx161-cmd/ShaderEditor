package com.termux.shadereditor.activity;

import android.os.Bundle;

import com.termux.shadereditor.R;
import com.termux.shadereditor.fragment.TextureViewFragment;
import com.termux.shadereditor.view.SystemBarMetrics;
import com.termux.shadereditor.widget.ScalingImageView;

public class TextureViewActivity
		extends AbstractSubsequentActivity
		implements TextureViewFragment.ScalingImageViewProvider {
	private ScalingImageView scalingImageView;

	@Override
	public ScalingImageView getScalingImageView() {
		return scalingImageView;
	}

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_view_texture);

		scalingImageView = findViewById(R.id.scaling_image_view);

		SystemBarMetrics.initMainLayout(this, null);
		AbstractSubsequentActivity.initToolbar(this);

		if (state == null) {
			setFragmentForIntent(new TextureViewFragment(), getIntent());
		}
	}
}
