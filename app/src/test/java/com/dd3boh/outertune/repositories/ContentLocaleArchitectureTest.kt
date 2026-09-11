package com.dd3boh.outertune.repositories

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prevent reintroducing UI-locale or independent preference writers into content localization. */
class ContentLocaleArchitectureTest {
    @Test
    fun `only the content locale repository writes the active YouTube locale`() {
        val appSources = File(repositoryRoot(), "app/src/main/java")
        val assignment = Regex("\\bYouTube\\s*\\.\\s*locale\\s*=(?!=)")
        val writers = sourceFiles(appSources).filter { assignment.containsMatchIn(it.readText()) }
            .map { it.relativeTo(appSources).invariantSeparatorsPath }.toList()

        assertEquals(
            "App startup, settings UI and metadata consumers must use the published content locale",
            listOf("com/dd3boh/outertune/repositories/ContentLocaleRepository.kt"),
            writers,
        )
    }

    @Test
    fun `content repositories and innertube never derive content language from the app process locale`() {
        val root = repositoryRoot()
        val roots = listOf(
            File(root, "app/src/main/java/com/dd3boh/outertune/repositories"),
            File(root, "innertube/src/main/java"),
        )
        val processDefault = Regex("\\bLocale\\s*\\.\\s*getDefault\\s*\\(")
        val offenders = roots.asSequence().flatMap(::sourceFiles)
            .filter { processDefault.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toList()

        // UI formatting deliberately remains outside this guard: ArtistDisplay's separators,
        // Player's numeric labels, PlayerMenu's dates/times, and settings language display names
        // follow the app UI locale. They must not determine request hl/gl or metadata namespaces.
        assertTrue("Content localization must use the shared device/configured locale: $offenders", offenders.isEmpty())
    }

    private fun sourceFiles(directory: File): Sequence<File> {
        check(directory.isDirectory) { "Missing source directory $directory" }
        return directory.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }
    }

    private fun repositoryRoot(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "app/src/main/java").isDirectory && File(it, "innertube/src/main/java").isDirectory }
        ?: error("Cannot locate OuterTune sources from ${System.getProperty("user.dir")}")
}
