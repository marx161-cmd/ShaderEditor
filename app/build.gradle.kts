plugins {
	alias(libs.plugins.android.application)
}

android {
	namespace = "com.termux.shadereditor"

	compileSdk = 36

	defaultConfig {
		minSdk = 23
		targetSdk = compileSdk

		versionCode = 130
		versionName = "2.36.9"

		vectorDrawables {
			useSupportLibrary = true
		}
	}

	signingConfigs {
		create("release") {
			keyAlias = providers.gradleProperty("TERMUX_KEY_ALIAS").orNull
			keyPassword = providers.gradleProperty("TERMUX_KEY_PASSWORD").orNull
			storePassword = providers.gradleProperty("TERMUX_STORE_PASSWORD").orNull
			storeFile = providers.gradleProperty("TERMUX_KEYSTORE").map { file(it) }.orNull
		}
	}

	buildTypes {
		debug {
			applicationIdSuffix = ".debug"
		}

		release {
			isMinifyEnabled = true
			isShrinkResources = true
			signingConfig = signingConfigs["release"]
		}
	}
	lint {
		checkReleaseBuilds = false
	}

	// 2026-11-05 ROM-READY (docs-consolidated/70-crdroid-rom.md standing rule): dex + native libs stored
	// uncompressed and aligned, so the release APK goes into the ROM's prebuilts as built -- no repack step
	packaging {
		dex { useLegacyPackaging = false }
		jniLibs { useLegacyPackaging = false }
	}

	buildFeatures {
		buildConfig = true
		viewBinding = true
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
}

dependencies {
	implementation(libs.androidx.appcompat)
	implementation(libs.material)
	implementation(libs.androidx.preference)

	implementation(libs.androidx.camera.core)
	implementation(libs.androidx.camera.camera2)
	implementation(libs.androidx.camera.lifecycle)

	debugImplementation(libs.leakcanary.android)
}
