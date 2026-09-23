plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.hvkeyn.ceditneuro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hvkeyn.ceditneuro"
        minSdk = 26
        // API 29+ forbids executing a program this app just wrote. API 28 stays in the
        // compatibility domain that can run compilers installed into the app's private files.
        targetSdk = 28
        versionCode = 6
        versionName = "0.6.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // Same install as the debug app, so a release update keeps the on-device API key.
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    lint {
        // Sideloaded on purpose. API 28 is what still allows this app to run installed compilers.
        disable += "ExpiredTargetSdkVersion"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.security.crypto)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)
    implementation(libs.slf4j.nop)
    implementation(libs.commons.net)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.jsch)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.sora.editor)

    // Milestone 2 (syntax highlighting) needs these, but language-textmate pulls in tm4e,
    // which is compiled against Java records. AGP 8.7 dexes external libraries without the
    // global synthetics that record desugaring requires, so the build fails with
    // "Attempt to create a global synthetic for 'Record desugaring'". Enabling them means
    // either a newer AGP or dexing the grammars from source instead of as an AAR.
    // implementation(libs.sora.language.textmate)
    // implementation(libs.sora.editor.lsp)
    // implementation(libs.sora.language.treesitter)

    implementation(libs.jgit) {
        exclude(group = "com.googlecode.javaewah", module = "JavaEWAH")
    }
    implementation("com.googlecode.javaewah:JavaEWAH:1.2.3")
}
