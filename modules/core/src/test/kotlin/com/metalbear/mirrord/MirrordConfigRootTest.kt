package com.metalbear.mirrord

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pins the rule that decides *where* mirrord looks for `.mirrord/mirrord.json`.
 *
 * Getting it wrong is silent. With no config the CLI runs with built-in defaults — mirror
 * instead of steal, no HTTP filter, no port mapping — and reports nothing.
 *
 * The plugin looks healthy and the app starts, so only a traffic test shows the difference. That
 * is why the ordering below is asserted.
 *
 * The candidate type is generic so the rule can be exercised with plain strings. The production
 * call passes `VirtualFile`, which needs a running IDE; the decision itself does not.
 */
class MirrordConfigRootTest {

    private fun candidate(source: String, root: String?, mirrordDir: String?) =
        ConfigRootCandidate(source, root, mirrordDir)

    @Test
    fun `content root wins when the file-derived heuristic points somewhere else`() {
        // The dev-container case, and the one that shipped broken. Inside a JetBrains dev
        // container the workspace file lives under the IDE's own configuration directory, so
        // deriving the project root from it yields ~/.config/JetBrains/<IDE> — a real directory
        // that simply has no `.mirrord` in it.
        val chosen = chooseConfigRoot(
            listOf(
                candidate("contentRoot[0]", "/IdeaProjects/repro", "/IdeaProjects/repro/.mirrord"),
                candidate("projectFileHeuristic", "/home/u/.config/JetBrains/IntelliJIdea2026.2", null)
            )
        )

        assertEquals("contentRoot[0]", chosen?.source)
        assertEquals("/IdeaProjects/repro/.mirrord", chosen?.mirrordDir)
    }

    @Test
    fun `heuristic still answers when it is the only source that has the directory`() {
        // The locally opened project. `.idea/workspace.xml` sits inside the project, so the
        // heuristic is correct here. It stays a fallback rather than being deleted.
        val chosen = chooseConfigRoot(
            listOf(
                candidate("contentRoot[0]", "/proj", null),
                candidate("projectFileHeuristic", "/proj", "/proj/.mirrord")
            )
        )

        assertEquals("projectFileHeuristic", chosen?.source)
        assertEquals("/proj/.mirrord", chosen?.mirrordDir)
    }

    @Test
    fun `order is by candidate position, not by which source is named`() {
        // Guards against a refactor that sorts or reorders candidates. Whoever is listed first
        // and has the directory wins; nothing inspects the source string to decide.
        val chosen = chooseConfigRoot(
            listOf(
                candidate("contentRoot[0]", "/first", "/first/.mirrord"),
                candidate("contentRoot[1]", "/second", "/second/.mirrord")
            )
        )

        assertEquals("/first/.mirrord", chosen?.mirrordDir)
    }

    @Test
    fun `a candidate without the directory is skipped, not treated as an answer`() {
        // A root that exists but holds no `.mirrord` must not end the search. Returning it would
        // reintroduce the original bug, because the IDE configuration directory does exist.
        val chosen = chooseConfigRoot(
            listOf(
                candidate("contentRoot[0]", "/exists/but/empty", null),
                candidate("contentRoot[1]", "/has/it", "/has/it/.mirrord")
            )
        )

        assertEquals("/has/it/.mirrord", chosen?.mirrordDir)
    }

    @Test
    fun `no candidate has the directory`() {
        val chosen = chooseConfigRoot(
            listOf(
                candidate("contentRoot[0]", "/a", null),
                candidate("projectFileHeuristic", "/b", null)
            )
        )

        assertNull(chosen)
    }

    @Test
    fun `an empty candidate list is not an error`() {
        // Reachable when the project has no content roots and the heuristic throws.
        assertNull(chooseConfigRoot(emptyList<ConfigRootCandidate<String>>()))
    }
}
