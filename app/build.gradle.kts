import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
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
 * the submodule changed — the release build is the slowest step in the project,
 * and a no-op has to be ~0. Skipping is only safe because the output is
 * *deleted* first inside the task: a failed build leaves no `.so`, rather than
 * the previous one wearing the new code's name.
 *
 * `@CacheableTask` is what makes a fresh CI runner skip it too: a new runner has
 * no task history, so only the build cache can say "this exact input set already
 * produced this library".
 *
 * The inputs are the submodule's content, the ABI, the NDK revision, and the
 * cargo version. `rust-toolchain.toml` pins the compiler, but only a rustup
 * `cargo` obeys it — a Homebrew or distro `cargo` ignores the file, so the
 * version is asked for directly rather than inferred. `cargo-ndk` is the one
 * tool still outside the key: it is pinned to 4.1.2 in the workflow and by
 * `cargo install` locally, and a version bump there should come with a `clean`.
 */
@CacheableTask
abstract class CargoNdkTask : DefaultTask() {
    /**
     * Everything whose change must force a rebuild.
     *
     * `RELATIVE` sensitivity: the cache key is built from each file's path
     * relative to this collection's roots, not its absolute location. That is
     * what lets an entry written on a CI runner (`/home/runner/work/...`) be
     * reused by a checkout in any other directory. Gradle requires an explicit
     * strategy here — a cacheable task with unnormalised file inputs fails
     * validation rather than silently caching per-path.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /**
     * The workspace manifest, for cargo's `--manifest-path`.
     *
     * `@Internal` because it is an absolute path and the same file is already in
     * [sources], where Gradle hashes its content instead. Keeping the path out of
     * the key is what lets a cache entry built on a CI runner be reused locally.
     */
    @get:Internal
    abstract val manifestPath: Property<String>

    @get:Input
    abstract val abi: Property<String>

    /**
     * The cargo profile, named rather than a `--release` flag.
     *
     * Debug and release APKs have different jobs: a debug build is rebuilt on
     * every core edit and only needs to run, while a release build is linked
     * once and ships. They are separate Gradle tasks so the profile is part of
     * the cache key and neither variant can package the other's `.so`.
     */
    @get:Input
    abstract val profile: Property<String>

    /**
     * The NDK's own revision, read from its `source.properties`.
     *
     * A version rather than the install path: a path would be a machine-specific
     * absolute string, so Gradle would store a separate cache entry per machine
     * and the entry could never be relocated. The revision is what actually
     * decides the sysroot, and it is the same number everywhere.
     */
    @get:Input
    abstract val ndkVersion: Property<String>

    /**
     * The resolved path of the `cargo` to run, e.g. `/Users/me/.cargo/bin/cargo`.
     *
     * Passed in rather than looked up inside the task for two reasons. `exec`
     * resolves the program on the *parent* process's `PATH`, so the `PATH` set in
     * the environment below reaches the child but cannot make `cargo` findable —
     * the absolute path has to be handed over already resolved. And a Kotlin DSL
     * script compiles its top-level functions into the script class, so calling
     * one from this nested class would make the class `inner`, which Gradle
     * cannot instantiate.
     *
     * `@Internal` because the path is machine-specific; [cargoVersion] is what
     * the cache key uses.
     */
    @get:Internal
    abstract val cargoExe: Property<String>

    /**
     * The `cargo` version, e.g. `cargo 1.97.1 (c980f4866 2026-06-30)`.
     *
     * An input rather than a comment about `rust-toolchain.toml`: that file only
     * decides the compiler when `cargo` is rustup's, and a distro or Homebrew
     * `cargo` ignores it entirely. Asking the binary is the only way the key
     * describes the tool that actually produced the library.
     */
    @get:Input
    abstract val cargoVersion: Property<String>

    /**
     * Cargo's incremental state, kept outside the submodule.
     *
     * `@Internal`: it is where cargo writes, not something that changes what it
     * writes. Declaring it as an input would key the cache entry on an absolute
     * path, so a CI-built library could never be reused locally and every
     * machine would store its own copy. The toolchain is pinned separately by
     * [ndkVersion] and the `rust-toolchain.toml` in [sources].
     */
    @get:Internal
    abstract val cargoTargetDir: Property<String>

    /**
     * Where the NDK lives, for the exec environment.
     *
     * `@Internal` for the same reason as [cargoTargetDir]: the path is
     * machine-specific. [ndkVersion] is what the cache key uses.
     */
    @get:Internal
    abstract val ndkHome: Property<String>

    /**
     * The search path the toolchain is found on.
     *
     * `@Internal` rather than an input: it is the whole inherited `PATH`, so
     * declaring it would key the cache entry on every directory a machine
     * happens to export and nothing would ever be reused. What actually decides
     * the compiler is [cargoVersion], and the NDK is [ndkVersion]. `cmake` and
     * `ninja` are the runner image's own and are not part of the key; a cached
     * library built by a different CMake patch release is still a correct
     * library.
     */
    @get:Internal
    abstract val toolPath: Property<String>

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

        // Already resolved by the caller: `exec` searches the parent's PATH, so
        // setting PATH below cannot make a bare "cargo" findable.
        execOps.exec {
            workingDir = manifestPath.get().let { File(it).parentFile }
            commandLine(
                cargoExe.get(), "ndk",
                "-t", abi.get(),
                "-o", outputDir.get().asFile.absolutePath,
                "build",
                "--profile", profile.get(),
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
                "PATH" to toolPath.get(),
            )
        }
    }
}

/**
 * The `PATH` [CargoNdkTask] runs with.
 *
 * Two problems are solved here, both of them about a tool that exists but is not
 * on the inherited `PATH`:
 *
 * `cmake` is not on the default Windows PATH and cargo needs it to build Opus
 * from C source. A standalone install under Program Files is the usual one; the
 * Android SDK's `cmake/<version>` package is the fallback (and it also carries
 * ninja, which the task asks for as the generator). Both are prepended when
 * present — a fresh machine with only the SDK used to fail this task with "is
 * `cmake` not installed?".
 *
 * `cargo` comes from rustup, which puts it in `~/.cargo/bin` and expects a shell
 * profile to add that directory to `PATH`. A Gradle build started from Android
 * Studio on macOS never reads those profiles, and a non-login shell does not
 * either, so the build fails with "A problem occurred starting process 'command
 * 'cargo''". Prepending the directory unconditionally is safe: when rustup
 * already exported it this is a duplicate entry, and when it does not exist the
 * entry is inert.
 */
private fun cargoToolPath(sdk: File): String {
    val existing = System.getenv("PATH").orEmpty()
    val cargoBin = File(System.getProperty("user.home"), ".cargo/bin")
    val sdkCmakeBins = sdk.resolve("cmake").listFiles()
        ?.filter { it.isDirectory }
        ?.sortedByDescending { it.name }
        ?.map { it.resolve("bin") }
        .orEmpty()
    val candidates = listOf(cargoBin, File("C:\\Program Files\\CMake\\bin")) + sdkCmakeBins
    val prefix = candidates.filter { it.isDirectory }.map { it.absolutePath }
    return (prefix + existing).joinToString(File.pathSeparator)
}

/**
 * Finds an executable by searching `path`, the way a shell would.
 *
 * Needed because `ProcessBuilder` and Gradle's `ExecOperations` look the program
 * up with `execvp`, which searches the *parent* process's `PATH` — the
 * `environment()` map handed to them only becomes the child's environment, after
 * the lookup has already happened. Setting `PATH` in that map therefore cannot
 * make a binary findable; the absolute path has to be resolved here and passed
 * as the command.
 */
private fun resolveExecutable(name: String, path: String): File? =
    path.split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it, name) }
        .firstOrNull { it.isFile && it.canExecute() }

/**
 * `cargo --version`, asked of the same binary the build will run.
 *
 * `directory` matters: rustup reads `rust-toolchain.toml` from the working
 * directory and switches toolchains accordingly, so the same binary reports
 * 1.100.0 at the repository root and 1.97.1 inside the submodule that pins it.
 * The build runs in the submodule, so that is where the version has to be read —
 * otherwise the cache key names a compiler the library was not built with.
 *
 * Resolved during configuration, which means one short process per build. That
 * is the price of the version being a real cache input.
 *
 * A missing `cargo` is reported as a string rather than thrown, so `clean` still
 * works on a machine that cannot build native code at all.
 */
private fun detectCargoVersion(cargo: File?, directory: File): String = try {
    if (cargo == null) {
        "cargo unavailable"
    } else {
        val process = ProcessBuilder(cargo.absolutePath, "--version")
            .directory(directory)
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && text.isNotEmpty()) text else "cargo unavailable"
    }
} catch (_: Exception) {
    "cargo unavailable"
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

/**
 * The NDK's own revision, e.g. `27.2.12479018`.
 *
 * Read from the NDK's `source.properties` because that is the only place the
 * revision is written. The directory it happens to be installed in (`r27c/x64`
 * from `setup-ndk`, `27.2.12479018` from the SDK manager) is not it.
 */
fun ndkRevision(ndk: File): String {
    val props = Properties().apply {
        ndk.resolve("source.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }
    val revision = props.getProperty("Pkg.Revision")
        ?: throw GradleException("No Pkg.Revision in ${ndk.resolve("source.properties")}")
    // `setup-ndk` records `27.2.12479018-beta1` for a preview; the stable
    // revision is the part before the dash, which is what `ndkVersion` means.
    return revision.substringBefore("-")
}

val tsbridgeDir = rootProject.file("third_party/tsbridge")

/**
 * `rustls-platform-verifier` is a Rust crate whose Android half is a small
 * prebuilt AAR. Its version has to match `rustls-platform-verifier-android` in
 * the core's Cargo.lock — a mismatch is a runtime failure in the verifier, not
 * a compile error — so the file name is derived from the lock instead of being
 * typed twice.
 */
fun rustlsVerifierAndroidVersion(lockFile: File): String {
    val lines = lockFile.readLines()
    val start = lines.indexOfFirst { it.trim() == "name = \"rustls-platform-verifier-android\"" }
    require(start >= 0) {
        "rustls-platform-verifier-android is missing from ${lockFile.absolutePath}"
    }
    return lines.drop(start + 1)
        .first { it.trim().startsWith("version = ") }
        .substringAfter("version = ")
        .trim()
        .trim('"')
}

/**
 * Registers one cargo build per build type.
 *
 * The split is not cosmetic: debug and release used to share a single task and
 * a single output directory, which meant a debug APK paid the release profile's
 * fat LTO on every core edit and both variants could point at the same `.so`.
 */
fun registerTsbridgeJni(name: String, profileName: String, outputSubdir: String) =
    project.tasks.register<CargoNdkTask>(name) {
        group = "build"
        description = "Compiles tsbridge-jni ($profileName) into a jniLibs directory."

        sources.from(
            tsbridgeDir.resolve("Cargo.toml"),
            tsbridgeDir.resolve("Cargo.lock"),
            // Decides which compiler runs, so it decides the output. Without it a
            // submodule bump that changed only the pinned toolchain would report
            // UP-TO-DATE and keep the old `.so`.
            tsbridgeDir.resolve("rust-toolchain.toml"),
            tsbridgeDir.resolve("crates"),
            // The vendored tsclientlib is a path dependency of `crates/core`, so a
            // change there changes the library. It was missing from the input set,
            // which meant a submodule bump that only touched the vendored stack
            // reported this task UP-TO-DATE and shipped the previous `.so`.
            tsbridgeDir.resolve("vendor"),
        )
        manifestPath.set(tsbridgeDir.resolve("Cargo.toml").absolutePath)
        abi.set("arm64-v8a")
        profile.set(profileName)
        // Resolved when the task runs, not while Gradle configures: a checkout
        // without an Android SDK can still run `tasks`, `clean`, or a lint check.
        ndkHome.set(project.providers.provider { selectedNdk().absolutePath })
        ndkVersion.set(project.providers.provider { ndkRevision(selectedNdk()) })
        toolPath.set(project.providers.provider { cargoToolPath(androidSdkDir()) })
        // Resolved once, outside the task class, and handed over as a path. The
        // version is asked of that same binary so the cache key and the build cannot
        // disagree about which `cargo` runs.
        cargoExe.set(project.providers.provider {
            val path = cargoToolPath(androidSdkDir())
            resolveExecutable("cargo", path)?.absolutePath
                ?: throw GradleException(
                    "cargo not found on PATH: $path. Install Rust with rustup, " +
                        "or set PATH so that cargo is reachable.",
                )
        })
        cargoVersion.set(project.providers.provider {
            // Read in the submodule: that is where `rust-toolchain.toml` lives and
            // where cargo actually runs, so that is the version the library gets.
            detectCargoVersion(
                resolveExecutable("cargo", cargoToolPath(androidSdkDir())),
                tsbridgeDir,
            )
        })
        // Deliberately the submodule's own `target/`, not `app/build`: `gradlew clean`
        // deletes `app/build`, and throwing away the Rust dependency cache turns a
        // 80-second incremental build into a five-minute cold one. It is ignored by
        // tsbridge's own .gitignore, so the submodule stays clean, and it is the same
        // cache a by-hand `tools/build-android.ps1` uses — one compile, shared.
        cargoTargetDir.set(tsbridgeDir.resolve("target").absolutePath)
        // cargo-ndk writes `<output-dir>/<abi>/libtsbridge_jni.so`.
        outputDir.set(project.layout.buildDirectory.dir("generated/tsbridgeJniLibs/$outputSubdir"))
    }

val buildTsbridgeJniDebug = registerTsbridgeJni("buildTsbridgeJniDebug", "android-dev", "debug")
val buildTsbridgeJniRelease = registerTsbridgeJni("buildTsbridgeJniRelease", "release", "release")

// Packaging native code goes through the merge tasks, not through `preBuild`,
// so the dependency has to be attached there or the first build packs nothing.
// Two names matter: `merge*JniLibFolders` collects the source-set directories
// (where the compiler writes), and `merge*NativeLibs` folds in AAR contents.
// The variant in the task name decides which cargo profile that variant gets.
tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLibFolders") }
    .configureEach {
        when {
            name.contains("Release") -> dependsOn(buildTsbridgeJniRelease)
            name.contains("Debug") -> dependsOn(buildTsbridgeJniDebug)
        }
    }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }
    .configureEach {
        when {
            name.contains("Release") -> dependsOn(buildTsbridgeJniRelease)
            name.contains("Debug") -> dependsOn(buildTsbridgeJniDebug)
        }
    }

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

    // AGP 9 registers a Compose Preview task per variant that reads compiled
    // Android resources, and refuses to configure itself when the unit-test
    // variant has none. That is a *configuration*-time failure, so it took down
    // `gradlew tasks` as well as anything else that configures every task — the
    // build was unusable outside `assembleDebug` and `testDebugUnitTest`, which
    // only configure what they need.
    //
    // There is no public switch: `VariantTaskManager` registers the task whenever
    // `buildFeatures.compose` is on, and disabling it afterwards is too late
    // because it throws while being created. What it actually requires is that
    // the variant has unit tests packaging Android resources, so both variants
    // now do.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    androidComponents {
        beforeVariants(selector().withBuildType("release")) { variant ->
            // Without this the release preview task fails for the opposite
            // reason: no unit-test component to read resources from. It costs a
            // test APK on release builds, which this project does not ship from
            // CI — `assembleDebug` and `testDebugUnitTest` are the only builds
            // that run there.
            (variant as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = true
        }
    }

    sourceSets["main"].kotlin.directories.add("src/main/kotlin")

    // Native code now comes from the generated directories; nothing under
    // src/main/jniLibs is tracked. One directory per build type, because the
    // debug and release cargo tasks produce different profiles. A plain path
    // rather than a provider: AGP forbids provider-backed `jniLibs` dirs here,
    // and the task dependency is attached explicitly below anyway.
    sourceSets["debug"].jniLibs.directories.add("build/generated/tsbridgeJniLibs/debug")
    sourceSets["release"].jniLibs.directories.add("build/generated/tsbridgeJniLibs/release")

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
    // The Kotlin/Java half of the TLS verifier the account client uses on
    // Android. The
    // Rust half is inside libtsbridge_jni.so; the JNI shim initializes this
    // component with the JVM context at load, before the account's first HTTPS
    // request. Without it the verifier panics and a login never finishes.
    implementation(
        files(
            "libs/rustls-platform-verifier-${
                rustlsVerifierAndroidVersion(tsbridgeDir.resolve("Cargo.lock"))
            }.aar",
        ),
    )

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
    // The navigation bar uses Chat/Folder/ViewList, which are not in the small
    // core icon set. R8 strips the ones this app never references in release.
    implementation(libs.androidx.material.icons.extended)

    debugImplementation(libs.androidx.ui.tooling)

    // The event/snapshot contract is pure Kotlin, so it is testable on the JVM
    // without an emulator. That is the only test tier that can run in CI here.
    testImplementation(libs.junit)
}
