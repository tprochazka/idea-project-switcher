/*
 * Copyright (C) 2026 ATomSoft
 *
 * This file is part of Project Switcher.
 *
 * Project Switcher is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * Project Switcher is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Project Switcher. If not, see <https://www.gnu.org/licenses/>.
 */

package cz.atomsoft.ideaplugin.projectswitcher

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ProjectBranchUpdaterTest {
    @Test
    fun `updates branch for matching project root`() {
        val root = Files.createTempDirectory("branch-update-root")
        val projectPath = root.resolve("project").createDirectories()
        val entry = projectEntry(projectPath, "old", repositoryRoot = projectPath)

        val updated = ProjectBranchUpdater.updateBranch(listOf(entry), entry.path.toString(), "new")

        assertEquals("new", updated.single().branch)
    }

    @Test
    fun `updates branch for project below changed repository root`() {
        val root = Files.createTempDirectory("branch-update-parent")
        val projectPath = root.resolve("repo").resolve("project").createDirectories()
        val entry = projectEntry(projectPath, "old", repositoryRoot = root.resolve("repo"))

        val updated = ProjectBranchUpdater.updateBranch(listOf(entry), root.resolve("repo").toString(), "main")

        assertEquals("main", updated.single().branch)
    }

    @Test
    fun `keeps unrelated projects unchanged`() {
        val root = Files.createTempDirectory("branch-update-unrelated")
        val matchingPath = root.resolve("repo").createDirectories()
        val unrelatedPath = root.resolve("other").createDirectories()
        val matching = projectEntry(matchingPath, "old", repositoryRoot = matchingPath)
        val unrelated = projectEntry(unrelatedPath, "keep", repositoryRoot = unrelatedPath)

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
        val projectPath = root.resolve("project").createDirectories()
        val entry = projectEntry(projectPath, "main", repositoryRoot = projectPath)
        val entries = listOf(entry)

        val updated = ProjectBranchUpdater.updateBranch(entries, entry.path.toString(), "main")

        assertSame(entries, updated)
    }

    @Test
    fun `does not update nested checkout owned by another repository`() {
        val root = Files.createTempDirectory("branch-update-nested-repositories")
        val parentRepository = root.resolve("parent").createDirectories()
        val nestedRepository = parentRepository.resolve("nested").createDirectories()
        val parentEntry = projectEntry(parentRepository, "parent", repositoryRoot = parentRepository)
        val nestedEntry = projectEntry(nestedRepository, "nested", repositoryRoot = nestedRepository)

        val updated = ProjectBranchUpdater.updateBranch(
            listOf(parentEntry, nestedEntry),
            parentRepository.toString(),
            "changed",
        )

        assertEquals("changed", updated[0].branch)
        assertEquals("nested", updated[1].branch)
    }

    @Test
    fun `keeps case-distinct paths separate on case-sensitive filesystems`() {
        if (java.io.File.separatorChar == '\\') return

        val root = Files.createTempDirectory("branch-update-case-sensitive")
        val upper = root.resolve("Foo").createDirectories()
        val lower = root.resolve("foo").createDirectories()
        val entries = listOf(
            projectEntry(upper, "upper", repositoryRoot = upper),
            projectEntry(lower, "lower", repositoryRoot = lower),
        )

        val updated = ProjectBranchUpdater.updateBranch(entries, upper.toString(), "changed")

        assertEquals("changed", updated[0].branch)
        assertEquals("lower", updated[1].branch)
    }

    private fun projectEntry(
        path: java.nio.file.Path,
        branch: String,
        repositoryRoot: java.nio.file.Path,
    ): ProjectEntry {
        return ProjectEntry(
            path = path.toAbsolutePath().normalize(),
            scanRoot = path.parent.toAbsolutePath().normalize(),
            name = path.fileName.toString(),
            branch = branch,
            repositoryRoot = repositoryRoot.toAbsolutePath().normalize(),
        )
    }
}
