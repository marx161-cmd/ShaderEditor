package com.termux.shadereditor.activity;

import androidx.fragment.app.Fragment;

import com.termux.shadereditor.fragment.PreferencesFragment;

public class PreferencesActivity extends AbstractContentActivity {
	@Override
	protected Fragment defaultFragment() {
		return new PreferencesFragment();
	}
}
