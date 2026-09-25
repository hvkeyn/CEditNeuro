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
        versionCode = 40
        versionName = "0.40.0"
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
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
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
    implementation(libs.sora.editor.lsp)
    implementation(libs.sora.language.treesitter)
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-java:4.3.1")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-kotlin:4.3.1")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-python:4.3.1")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-json:4.3.1")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-xml:4.3.1")

    testImplementation("junit:junit:4.13.2")

    implementation(libs.jgit) {
        exclude(group = "com.googlecode.javaewah", module = "JavaEWAH")
    }
    implementation("com.googlecode.javaewah:JavaEWAH:1.2.3")
}
