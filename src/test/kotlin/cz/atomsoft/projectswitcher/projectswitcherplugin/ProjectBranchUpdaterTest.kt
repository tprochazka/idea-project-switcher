package cz.atomsoft.projectswitcher.projectswitcherplugin

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ProjectBranchUpdaterTest {
    @Test
    fun `updates branch for matching project root`() {
        val root = Files.createTempDirectory("branch-update-root")
        val entry = projectEntry(root.resolve("project").createDirectories(), "old")

        val updated = ProjectBranchUpdater.updateBranch(listOf(entry), entry.path.toString(), "new")

        assertEquals("new", updated.single().branch)
    }

    @Test
    fun `updates branch for project below changed repository root`() {
        val root = Files.createTempDirectory("branch-update-parent")
        val projectPath = root.resolve("repo").resolve("project").createDirectories()
        val entry = projectEntry(projectPath, "old")

        val updated = ProjectBranchUpdater.updateBranch(listOf(entry), root.resolve("repo").toString(), "main")

        assertEquals("main", updated.single().branch)
    }

    @Test
    fun `keeps unrelated projects unchanged`() {
        val root = Files.createTempDirectory("branch-update-unrelated")
        val matching = projectEntry(root.resolve("repo").createDirectories(), "old")
        val unrelated = projectEntry(root.resolve("other").createDirectories(), "keep")

        val updated = ProjectBranchUpdater.updateBranch(
            listOf(matching, unrelated),
            matching.path.toString(),
            "new",
        )

        assertEquals("new", updated[0].branch)
        assertEquals("keep", updated[1].branch)
    }

    @Test
    fun `returns same list instance when nothing changed`() {
        val root = Files.createTempDirectory("branch-update-same")
        val entry = projectEntry(root.resolve("project").createDirectories(), "main")
        val entries = listOf(entry)

        val updated = ProjectBranchUpdater.updateBranch(entries, entry.path.toString(), "main")

        assertSame(entries, updated)
    }

    private fun projectEntry(path: java.nio.file.Path, branch: String): ProjectEntry {
        return ProjectEntry(
            path = path.toAbsolutePath().normalize(),
            scanRoot = path.parent.toAbsolutePath().normalize(),
            name = path.fileName.toString(),
            branch = branch,
        )
    }
}
