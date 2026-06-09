import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// --- Version is read from /version.properties (single source of truth) ---
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val appVersionName: String = (project.findProperty("VERSION_NAME") as String?)
    ?: versionProps.getProperty("VERSION_NAME", "1.0.0")
val appVersionCode: Int = ((project.findProperty("VERSION_CODE") as String?)
    ?: versionProps.getProperty("VERSION_CODE", "1")).toInt()

// GitHub repository that hosts releases + downloadable catalogs.
val githubOwner = "pr0zak"
val githubRepo = "starmap"

android {
    namespace = "com.starmap.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.starmap.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GITHUB_OWNER", "\"$githubOwner\"")
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
    }

    // Release signing. Every release MUST use the same certificate so the app can
    // update itself in place. Resolution order:
    //   1. CI secrets via RELEASE_* env vars (preferred for public distribution)
    //   2. The committed keystore described by /keystore.properties
    //   3. Debug key fallback (local dev only; updates won't install in place)
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val envStore = System.getenv("RELEASE_STORE_FILE")
    val useEnv = envStore != null && file(envStore).exists()
    val committedStore = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
    val useCommitted = committedStore != null && committedStore.exists()
    val signRelease = useEnv || useCommitted
    if (signRelease) {
        signingConfigs {
            create("release") {
                if (useEnv) {
                    storeFile = file(envStore!!)
                    storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                    keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                    keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                } else {
                    storeFile = committedStore
                    storePassword = keystoreProps.getProperty("storePassword")
                    keyAlias = keystoreProps.getProperty("keyAlias")
                    keyPassword = keystoreProps.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (signRelease) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // Keep lint running for reports, but don't fail CI on style-level findings.
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
