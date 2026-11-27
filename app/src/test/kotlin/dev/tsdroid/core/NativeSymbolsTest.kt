package dev.tsdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * Pins the JNI symbol names to the Rust side.
 *
 * Renaming the Kotlin package changes every symbol the JVM looks for
 * (`Java_dev_tsdroid_core_Native_nativeExec` -> `Java_dev_litespeak_core_Native_...`)
 * while `crates/jni/src/lib.rs` keeps exporting the old ones. Nothing fails at
 * compile time and `System.loadLibrary` still succeeds; the miss surfaces as
 * `UnsatisfiedLinkError` the first time a native method is *called*, i.e. on the
 * connect button. The UI's `loadError` path cannot catch that, because it only
 * covers loading.
 *
 * The expected prefix is derived from the class itself rather than written here,
 * so the test follows a package rename instead of quietly agreeing with itself:
 * move the package and forget `lib.rs`, and this goes red.
 *
 * Reads source, not the built `.so`, so it runs on a plain JVM with no NDK.
 */
class NativeSymbolsTest {

    @Test
    fun everyNativeMethodHasAMatchingRustExport() {
        // `initialize = false`: reading the class must not run `Native`'s init
        // block, which calls `System.loadLibrary` and cannot succeed on the host.
        val nativeClass = Class.forName("dev.tsdroid.core.Native", false, javaClass.classLoader)
        val prefix = "Java_" + nativeClass.name.replace('.', '_')

        val declared = nativeClass.declaredMethods
            .filter { Modifier.isNative(it.modifiers) }
            .map { it.name }
            .sorted()

        assertTrue("Native declares no native methods; did the class move?", declared.isNotEmpty())
        assertEquals(
            "overloaded native methods need JNI signature suffixes; this check assumes unique names",
            declared.size,
            declared.toSet().size,
        )

        val exported = Regex("""fn\s+(Java_[A-Za-z0-9_]+)\s*\(""")
            .findAll(jniSource().readText())
            .map { it.groupValues[1] }
            .toSet()

        val missing = declared.filter { "${prefix}_$it" !in exported }
        assertTrue(
            "crates/jni/src/lib.rs exports no symbol for $missing (expected prefix $prefix)",
            missing.isEmpty(),
        )

        val stale = exported
            .filter { it.startsWith("${prefix}_") }
            .map { it.removePrefix("${prefix}_") }
            .toSet() - declared.toSet()
        assertTrue("lib.rs still exports symbols Kotlin no longer declares: $stale", stale.isEmpty())

        val foreign = exported.filterNot { it.startsWith(prefix) }
        assertTrue("lib.rs exports symbols for another package: $foreign", foreign.isEmpty())
    }

    /**
     * Finds `crates/jni/src/lib.rs` by walking up from wherever the test runs,
     * so the same test works before and after the shell moves into the
     * monorepo (`shells/android/app` -> `crates/jni/src/lib.rs` two levels up)
     * and while the core is still a `third_party/tsbridge` submodule.
     */
    private fun jniSource(): File {
        val relative = listOf(
            "crates/jni/src/lib.rs",
            "third_party/tsbridge/crates/jni/src/lib.rs",
        )
        val roots = generateSequence(File(".").absoluteFile) { it.parentFile }
        for (root in roots.take(8)) {
            for (path in relative) {
                val file = root.resolve(path)
                if (file.isFile) return file
            }
        }
        throw AssertionError("crates/jni/src/lib.rs not found above ${File(".").absolutePath}")
    }
}
