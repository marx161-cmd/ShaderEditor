package com.termux.shadereditor.activity;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;

import com.termux.shadereditor.R;
import com.termux.shadereditor.fragment.CropImageFragment;
import com.termux.shadereditor.view.SystemBarMetrics;
import com.termux.shadereditor.widget.CropImageView;

public class CropImageActivity
		extends AbstractSubsequentActivity
		implements CropImageFragment.CropImageViewProvider {
	private CropImageView cropImageView;

	@NonNull
	public static Intent getIntentForImage(Context context, Uri imageUri) {
		Intent intent = new Intent(context, CropImageActivity.class);
		intent.putExtra(CropImageFragment.IMAGE_URI, imageUri);
		return intent;
	}

	@Override
	public CropImageView getCropImageView() {
		return cropImageView;
	}

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_crop_image);

		cropImageView = findViewById(R.id.crop_image_view);

		SystemBarMetrics.initMainLayout(this, cropImageView.insets);
		AbstractSubsequentActivity.initToolbar(this);

		if (state == null) {
			setFragmentForIntent(new CropImageFragment(), getIntent());
		}
	}
}
