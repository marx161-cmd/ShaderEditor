plugins {
	alias(libs.plugins.android.application)
}

android {
	namespace = "com.termux.shadereditor"

	compileSdk = 36

	defaultConfig {
		minSdk = 23
		targetSdk = compileSdk

		versionCode = 93
		versionName = "2.36.2"

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
