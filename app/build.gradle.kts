plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.manhvu1212.aamediaproxy"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.manhvu1212.aamediaproxy"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.1"
    }

    // Release signing reads from ~/.gradle/gradle.properties (or matching env vars) so
    // the upload keystore never lives in the repo. The keystore is shared across all of
    // this developer's apps; PKCS12 uses one password for both store and key. Recognized
    // keys (gradle property name = env var name):
    //   NMV_KEYSTORE        absolute path to the .p12 / .jks
    //   NMV_KEYSTORE_PASS   password (used for both store and key)
    // The per-app alias is fixed in code — it's an identifier, not a secret.
    // When credentials are missing we fall back to debug signing so `bundleRelease` still
    // works locally for smoke testing without forcing contributors to provision the key.
    fun cred(name: String): String? =
        (findProperty(name) as String?)?.takeIf { it.isNotBlank() }
            ?: System.getenv(name)?.takeIf { it.isNotBlank() }

    val releaseKeystorePath = cred("NMV_KEYSTORE")
    val releaseKeystorePass = cred("NMV_KEYSTORE_PASS")
    val releaseSigningReady = releaseKeystorePath != null && releaseKeystorePass != null

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePass
                keyAlias = "aa-media-proxy"
                keyPassword = releaseKeystorePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (releaseSigningReady) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "NMV_KEYSTORE / NMV_KEYSTORE_PASS not set — release build will be " +
                        "signed with the debug key. Do NOT upload this artifact to Play."
                )
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.car.app)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.guava)
}
