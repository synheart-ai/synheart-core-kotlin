package ai.synheart.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards every place the SDK version is written down against
 * `gradle.properties`.
 *
 * These drift silently: the constant, the README badge, and the README's Maven
 * coordinate are each edited by hand, and nothing fails when one is forgotten.
 * The README shipped `0.0.8` against a `0.1.0` build, so a developer copying
 * the documented dependency line got a version behind.
 */
class VersionSyncTest {

    /** Walk up to the repo root, so the test works from any working directory. */
    private fun repoRoot(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "gradle.properties").exists()) {
            dir = dir.parentFile ?: error("could not locate the repo root")
        }
        return dir
    }

    private fun declaredVersion(): String {
        val props = File(repoRoot(), "gradle.properties").readText()
        val match = Regex("""^VERSION_NAME=(.+)$""", RegexOption.MULTILINE).find(props)
        assertNotNull("gradle.properties is missing VERSION_NAME", match)
        return match!!.groupValues[1].trim()
    }

    @Test
    fun `SYNHEART_CORE_VERSION matches gradle properties`() {
        assertEquals(
            "SynheartVersion.kt is out of sync with gradle.properties",
            declaredVersion(),
            SYNHEART_CORE_VERSION,
        )
    }

    @Test
    fun `the README version badge matches gradle properties`() {
        val readme = File(repoRoot(), "README.md").readText()
        val badge = Regex("""img\.shields\.io/badge/version-([0-9][^-]*)-""").find(readme)
        assertNotNull("README.md is missing a version badge", badge)
        assertEquals(
            "The README version badge is out of sync with gradle.properties",
            declaredVersion(),
            badge!!.groupValues[1],
        )
    }

    @Test
    fun `every README Maven coordinate matches gradle properties`() {
        val readme = File(repoRoot(), "README.md").readText()
        val coords = Regex("""ai\.synheart:synheart-core:([0-9][A-Za-z0-9.\-]*)""")
            .findAll(readme)
            .map { it.groupValues[1] }
            .toList()
        assertTrue(
            "README.md documents no `ai.synheart:synheart-core:<version>` coordinate",
            coords.isNotEmpty(),
        )
        coords.forEach {
            assertEquals(
                "A README dependency coordinate is out of sync with gradle.properties",
                declaredVersion(),
                it,
            )
        }
    }
}
