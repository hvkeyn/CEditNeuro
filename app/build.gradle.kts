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
        versionCode = 119
        versionName = "0.99.3"
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

    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/web-shell"))

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

android.applicationVariants.configureEach {
    if (name == "release") {
        outputs.configureEach {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "CEditNeuro-$versionName.apk"
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
    implementation(libs.apksig)
}

val packWebShell = tasks.register<Copy>("packWebShell") {
    description = "Copies the WebView shell into app assets."
    dependsOn(":webviewshell:assembleDebug")
    from(rootProject.layout.projectDirectory.dir("webviewshell/build/outputs/apk/debug"))
    include("webviewshell-debug.apk")
    into(layout.buildDirectory.dir("generated/web-shell"))
    rename { "web-shell.apk" }
    doFirst {
        project.delete(layout.buildDirectory.dir("generated/web-shell"))
    }
}

afterEvaluate {
    tasks.named("preBuild").configure { dependsOn(packWebShell) }
    tasks.named("testDebugUnitTest").configure { dependsOn(packWebShell) }
    listOf("debug", "release").forEach { variant ->
        val variantName = variant.replaceFirstChar { it.uppercase() }
        val stripTask = if (variant == "debug") "stripDebugDebugSymbols" else "stripReleaseDebugSymbols"
        val alignTask = tasks.register("align${variantName}NativeLibs") {
            dependsOn(stripTask)
            val libs = layout.buildDirectory.dir("intermediates/stripped_native_libs/$variant/$stripTask/out")
            inputs.dir(libs)
            outputs.file(layout.buildDirectory.file("align16k/$variant.stamp"))
            doLast {
                exec {
                    commandLine("python", rootProject.file("tools/align_elf_16k.py").absolutePath, libs.get().asFile.absolutePath)
                }
                layout.buildDirectory.file("align16k/$variant.stamp").get().asFile.apply {
                    parentFile.mkdirs()
                    writeText("16k")
                }
            }
        }
        tasks.named("package$variantName").configure {
            dependsOn(alignTask)
            doLast {
                val apkName = if (variant == "release") {
                    "CEditNeuro-${android.defaultConfig.versionName}.apk"
                } else {
                    "app-$variant.apk"
                }
                pageAlignApk(layout.buildDirectory.file("outputs/apk/$variant/$apkName").get().asFile)
            }
        }
    }
}

// Tree-sitter ships 4 KB ELF alignment, and AGP 8.7 zip-aligns .so files to 4 KB.
// Android 16 then shows the compatibility dialog on every cold start. Pad the LOAD
// segments to 16 KB and zip-align the finished APK to the same page size.
fun pageAlignApk(apk: File) {
    val tools = android.sdkDirectory.resolve("build-tools").listFiles()
        ?.filter { it.isDirectory }
        ?.maxByOrNull { it.name }
        ?: error("Android build-tools not found")
    val windows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
    fun sdkBin(name: String): String {
        val file = if (windows) {
            tools.resolve(if (name == "apksigner") "apksigner.bat" else "$name.exe")
        } else {
            tools.resolve(name)
        }
        if (!file.isFile) error("Missing ${file.absolutePath}")
        return file.absolutePath
    }
    val aligned = File(apk.parentFile, apk.nameWithoutExtension + "-16k.apk")
    exec {
        commandLine(sdkBin("zipalign"), "-f", "-P", "16", "4", apk.absolutePath, aligned.absolutePath)
    }
    if (!apk.delete() || !aligned.renameTo(apk)) error("Could not replace ${apk.name}")
    val signing = android.signingConfigs.getByName("debug")
    exec {
        commandLine(
            sdkBin("apksigner"),
            "sign",
            "--ks", (signing.storeFile ?: File(System.getProperty("user.home"), ".android/debug.keystore")).absolutePath,
            "--ks-pass", "pass:${signing.storePassword ?: "android"}",
            "--key-pass", "pass:${signing.keyPassword ?: "android"}",
            "--ks-key-alias", signing.keyAlias ?: "androiddebugkey",
            apk.absolutePath,
        )
    }
}

