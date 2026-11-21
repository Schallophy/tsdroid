package dev.tsdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins this app's ABI expectation to tsbridge's own header.
 *
 * `Native.EXPECTED_ABI` is the number Kotlin was written against, and it is the
 * one thing that must *not* be inferred at runtime: a shell that reads the
 * library's version and compares it with itself would always agree. So the
 * expectation is a literal here, and this test makes it fail at build time when
 * the submodule bumps `TSBRIDGE_ABI_VERSION` without the app following.
 *
 * Reads the header rather than loading the library, so it runs on a plain JVM.
 */
class NativeAbiTest {

    @Test
    fun theExpectedAbiMatchesTsbridgeHeader() {
        val header = repoFile("third_party/tsbridge/include/tsbridge.h")
        assertTrue("tsbridge submodule missing at ${header.absolutePath}", header.isFile)

        val declared = header.readLines()
            .firstOrNull { it.trimStart().startsWith("#define TSBRIDGE_ABI_VERSION ") }
            ?.trim()
            ?.removePrefix("#define TSBRIDGE_ABI_VERSION ")
            ?.trim()
            ?.toIntOrNull()
        assertTrue(
            "no TSBRIDGE_ABI_VERSION in ${header.absolutePath}",
            declared != null,
        )

        assertEquals(
            "Native.EXPECTED_ABI and tsbridge's header disagree; update "
                + "Native.kt together with the submodule bump",
            declared,
            Native.EXPECTED_ABI,
        )
    }

    /**
     * Resolves a repository-relative path regardless of the test's working
     * directory: Gradle uses the module dir, but an IDE runner uses the project
     * root, and a test that only works in one of them is a trap.
     */
    private fun repoFile(relative: String): File {
        val candidates = listOf(File("."), File(".."), File("../.."))
        return candidates.map { it.resolve(relative) }.firstOrNull { it.isFile }
            ?: File(relative)
    }
}
