import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Compiles `third_party/tsbridge` into `libtsbridge_jni.so`.
 *
 * The core is a git submodule, so this library is always built from the exact
 * sources the commit pins. That is the whole point of the arrangement: a
 * committed `.so` cannot say which checkout produced it, and a hand-run `-Sync`
 * copy is one forgotten step away from shipping stale code.
 *
 * Inputs and outputs are declared so Gradle skips the task when nothing under
 * the submodule changed — an incremental cargo build is ~80 s, a no-op has to
 * be ~0. Skipping is only safe because the output is *deleted* first inside the
 * task: a failed build leaves no `.so`, rather than the previous one wearing the
 * new code's name.
 */
abstract class CargoNdkTask : DefaultTask() {
    /** Everything whose change must force a rebuild. */
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val manifestPath: Property<String>

    @get:Input
    abstract val abi: Property<String>

    @get:Input
    abstract val ndkHome: Property<String>

    /** Cargo's incremental state, kept outside the submodule. */
    @get:Input
    abstract val cargoTargetDir: Property<String>

    /** The `jniLibs` layout Android expects: `<abi>/libtsbridge_jni.so`. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun build() {
        fileSystem.delete { delete(outputDir) }

        execOps.exec {
            workingDir = manifestPath.get().let { File(it).parentFile }
            commandLine(
                "cargo", "ndk",
                "-t", abi.get(),
                "-o", outputDir.get().asFile.absolutePath,
                "build",
                "--release",
                "--manifest-path", manifestPath.get(),
                "-p", "tsbridge-jni",
            )
            environment(
                // cargo-ndk looks for both spellings; set both so an
                // ANDROID_NDK_HOME exported by some other tool cannot win.
                "ANDROID_NDK_HOME" to ndkHome.get(),
                "ANDROID_NDK" to ndkHome.get(),
                // Opus's CMakeLists still declares a pre-3.5 minimum, which
                // CMake 4 refuses without this escape hatch.
                "CMAKE_POLICY_VERSION_MINIMUM" to "3.5",
                // Otherwise CMake picks the Visual Studio generator, which
                // cannot cross-compile to Android.
                "CMAKE_GENERATOR" to "Ninja",
                "CARGO_TARGET_DIR" to cargoTargetDir.get(),
                "PATH" to toolPath(),
            )
        }
    }

    /**
     * `cmake` is not on the default Windows PATH and cargo needs it to build
     * Opus from C source. The Android SDK's copy is optional (this machine has
     * none), so a system CMake is the fallback that actually exists.
     */
    private fun toolPath(): String {
        val existing = System.getenv("PATH").orEmpty()
        val extra = File("C:\\Program Files\\CMake\\bin")
        val prefix = if (extra.isDirectory) extra.absolutePath else null
        return listOfNotNull(prefix, existing).joinToString(File.pathSeparator)
    }
}

/** `sdk.dir` from local.properties, then the environment — as Android Studio does. */
fun androidSdkDir(): File {
    val fromLocal = rootProject.file("local.properties").takeIf { it.isFile }?.let { file ->
        runCatching {
            Properties().apply { file.inputStream().use { load(it) } }.getProperty("sdk.dir")
        }.getOrNull()
    }
    val sdk = fromLocal ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    return File(
        requireNotNull(sdk) {
            "Android SDK not found: set sdk.dir in local.properties or ANDROID_HOME"
        },
    )
}

/**
 * Which NDK to compile with: an explicit `ANDROID_NDK_HOME` / `ANDROID_NDK` wins,
 * otherwise the newest one installed in the SDK.
 *
 * The environment variable has to come first. It is how `tools/build-android.ps1`
 * and CI select the NDK, and honouring it here is what makes `assembleDebug` and
 * that script agree instead of silently using two different sysroots — CI installs
 * r27c while the SDK image also ships 29 and 30, so "newest wins" would build an
 * APK with an NDK no shell was ever tested against.
 */
fun selectedNdk(): File {
    val fromEnv = System.getenv("ANDROID_NDK_HOME")?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_NDK")?.takeIf { it.isNotBlank() }
    if (fromEnv != null) {
        val dir = File(fromEnv)
        require(dir.isDirectory) { "ANDROID_NDK_HOME points at a missing directory: $fromEnv" }
        return dir
    }
    val ndkRoot = androidSdkDir().resolve("ndk")
    return ndkRoot.listFiles()
        ?.filter { it.isDirectory }
        ?.sortedByDescending { it.name }
        ?.firstOrNull()
        ?: throw GradleException("No NDK under $ndkRoot; install one or set ANDROID_NDK_HOME")
}

val tsbridgeDir = rootProject.file("third_party/tsbridge")

val buildTsbridgeJni = tasks.register<CargoNdkTask>("buildTsbridgeJni") {
    group = "build"
    description = "Compiles tsbridge-jni from the submodule into a jniLibs directory."

    sources.from(
        tsbridgeDir.resolve("Cargo.toml"),
        tsbridgeDir.resolve("Cargo.lock"),
        tsbridgeDir.resolve("crates"),
    )
    manifestPath.set(tsbridgeDir.resolve("Cargo.toml").absolutePath)
    abi.set("arm64-v8a")
    // Resolved when the task runs, not while Gradle configures: a checkout
    // without an Android SDK can still run `tasks`, `clean`, or a lint check.
    ndkHome.set(providers.provider { selectedNdk().absolutePath })
    // Deliberately the submodule's own `target/`, not `app/build`: `gradlew clean`
    // deletes `app/build`, and throwing away the Rust dependency cache turns a
    // 80-second incremental build into a five-minute cold one. It is ignored by
    // tsbridge's own .gitignore, so the submodule stays clean, and it is the same
    // cache a by-hand `tools/build-android.ps1` uses — one compile, shared.
    cargoTargetDir.set(tsbridgeDir.resolve("target").absolutePath)
    // cargo-ndk writes `<output-dir>/<abi>/libtsbridge_jni.so`.
    outputDir.set(layout.buildDirectory.dir("generated/tsbridgeJniLibs"))
}

// Packaging native code goes through the merge tasks, not through `preBuild`,
// so the dependency has to be attached there or the first build packs nothing.
// Two names matter: `merge*JniLibFolders` collects the source-set directories
// (where the compiler writes), and `merge*NativeLibs` folds in AAR contents.
tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLibFolders") }
    .configureEach { dependsOn(buildTsbridgeJni) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }
    .configureEach { dependsOn(buildTsbridgeJni) }

android {
    namespace = "dev.tsdroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.tsdroid"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // The native library is built for arm64 only; shipping a stub for other
        // ABIs would make Play/installers pick the stub on 32-bit devices.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    }

    sourceSets["main"].kotlin.directories.add("src/main/kotlin")

    // Native code now comes from the generated directory; nothing under
    // src/main/jniLibs is tracked. A plain path rather than a provider: AGP
    // forbids provider-backed `jniLibs` dirs here, and the task dependency is
    // attached explicitly below anyway.
    sourceSets["main"].jniLibs.directories.add("build/generated/tsbridgeJniLibs")

    packaging {
        // Only LGPL2.1 is excluded, and only because nothing here ships LGPL
        // code: it comes from a dependency that uses the text for a linking
        // exception clause.
        //
        // AL2.0 is deliberately *not* excluded any more. tsclientlib is
        // Apache-2.0 OR MIT and is statically linked into libtsbridge_jni.so, so
        // if a dependency starts shipping that file, stripping it would be a
        // redistribution violation. The notice itself travels in
        // assets/licenses/ (see README) because a JAR entry does not describe
        // code compiled into a .so.
        resources.excludes += setOf("/META-INF/{LGPL2.1}")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    debugImplementation(libs.androidx.ui.tooling)

    // The event/snapshot contract is pure Kotlin, so it is testable on the JVM
    // without an emulator. That is the only test tier that can run in CI here.
    testImplementation(libs.junit)
}
