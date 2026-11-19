import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Native CI/release builds require the Rust library; legacy JVM-only development
// remains possible when the NDK/cargo-ndk toolchain is not installed.
val ctsBuildRust = providers.environmentVariable("CTS_BUILD_RUST").orNull == "1"
val rustOutput = layout.buildDirectory.dir("generated/ctsRust/jniLibs")
val rustSource = rootProject.layout.projectDirectory.dir("native")
val buildRustCore = tasks.register<Exec>("buildRustCore") {
    onlyIf { ctsBuildRust }
    workingDir(rustSource.asFile)
    inputs.files(fileTree(rustSource.asFile) { include("Cargo.toml", "Cargo.lock", "src/**") })
    outputs.dir(rustOutput)
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "-P", "26", "-o", rustOutput.get().asFile.absolutePath, "build", "--release", "--locked")
}

val ctsKeystorePath = providers.environmentVariable("CTS_ANDROID_KEYSTORE_PATH").orNull
val ctsStorePassword = providers.environmentVariable("CTS_ANDROID_KEYSTORE_PASSWORD").orNull
val ctsKeyAlias = providers.environmentVariable("CTS_ANDROID_KEY_ALIAS").orNull
val ctsKeyPassword = providers.environmentVariable("CTS_ANDROID_KEY_PASSWORD").orNull
val ctsStableSigningReady = listOf(
    ctsKeystorePath,
    ctsStorePassword,
    ctsKeyAlias,
    ctsKeyPassword,
).all { !it.isNullOrBlank() }
val ctsRequireStableSigning = providers.environmentVariable("CTS_REQUIRE_STABLE_SIGNING")
    .orNull
    ?.equals("true", ignoreCase = true) == true

if (ctsRequireStableSigning && !ctsStableSigningReady) {
    throw GradleException(
        "CTS stable signing is required, but CTS_ANDROID_KEYSTORE_PATH/PASSWORD/ALIAS credentials are incomplete.",
    )
}

android {
    namespace = "io.github.retrofrost.cts.android"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.github.retrofrost.cts.android.v5.rewrite"
        minSdk = 26
        targetSdk = 36
        versionCode = 5000101
        versionName = "5.0.0-android-rewrite-alpha.2"
        buildConfigField("boolean", "CTS_RUST_REQUIRED", ctsBuildRust.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    if (ctsBuildRust) {
        sourceSets.getByName("main").jniLibs.srcDir(rustOutput)
    }

    signingConfigs {
        if (ctsStableSigningReady) {
            create("ctsStable") {
                storeFile = file(ctsKeystorePath!!)
                storePassword = ctsStorePassword
                keyAlias = ctsKeyAlias
                keyPassword = ctsKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ctsStableSigningReady) {
                signingConfig = signingConfigs.getByName("ctsStable")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

if (ctsBuildRust) tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildRustCore) }

// Host JNI parity tests use the host-built Rust library, not the Android ELF.
if (providers.environmentVariable("CTS_TEST_NATIVE").orNull == "1") {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        systemProperty("java.library.path", rustSource.dir("target/debug").asFile.absolutePath)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.4.0-alpha15")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
