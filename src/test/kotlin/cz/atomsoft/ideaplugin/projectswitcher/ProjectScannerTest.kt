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
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectScannerTest {
    @Test
    fun `finds gradle project and reads branch from git head`() {
        val root = Files.createTempDirectory("scanner-root")
        val projectDir = root.resolve("alpha").createDirectories()
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"alpha\"")
        projectDir.resolve(".git").createDirectories()
        projectDir.resolve(".git").resolve("HEAD").writeText("ref: refs/heads/feature/login\n")

        val projects = ProjectScanner.scan(listOf(root.toString()))

        assertEquals(1, projects.size)
        val project = projects.single()
        assertEquals(projectDir.toAbsolutePath().normalize(), project.path)
        assertEquals(root.toAbsolutePath().normalize(), project.scanRoot)
        assertEquals("alpha", project.name)
        assertEquals("feature/login", project.branch)
        assertEquals(projectDir.toAbsolutePath().normalize(), project.repositoryRoot)
    }

    @Test
    fun `supports detached head hashes`() {
        val root = Files.createTempDirectory("scanner-detached-root")
        val projectDir = root.resolve("detached").createDirectories()
        projectDir.resolve("build.gradle.kts").writeText("plugins {}")
        projectDir.resolve(".git").createDirectories()
        projectDir.resolve(".git").resolve("HEAD").writeText("0123456789abcdef\n")

        val project = ProjectScanner.scan(listOf(root.toString())).single()

        assertEquals("0123456", project.branch)
    }

    @Test
    fun `resolves gitdir file`() {
        val root = Files.createTempDirectory("scanner-gitdir-root")
        val projectDir = root.resolve("linked").createDirectories()
        val externalGitDir = root.resolve("git-metadata").createDirectories()
        projectDir.resolve("settings.gradle").writeText("rootProject.name = 'linked'")
        projectDir.resolve(".git").writeText("gitdir: ../git-metadata\n")
        externalGitDir.resolve("HEAD").writeText("ref: refs/heads/main\n")

        val project = ProjectScanner.scan(listOf(root.toString())).single()

        assertEquals("main", project.branch)
    }

    @Test
    fun `returns null branch when project is not a git repository`() {
        val root = Files.createTempDirectory("scanner-nogit-root")
        root.resolve(".git").createDirectories()
        val projectDir = root.resolve("plain").createDirectories()
        projectDir.resolve(".idea").createDirectories()

        val project = ProjectScanner.scan(listOf(root.toString())).single()

        assertEquals("plain", project.name)
        assertNull(project.branch)
    }

    @Test
    fun `deduplicates projects found through overlapping roots`() {
        val root = Files.createTempDirectory("scanner-overlap-root")
        val projectDir = root.resolve("nested").createDirectories()
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"nested\"")
        projectDir.resolve(".git").createDirectories()
        projectDir.resolve(".git").resolve("HEAD").writeText("ref: refs/heads/dev\n")

        val projects = ProjectScanner.scan(
            listOf(root.toString(), projectDir.toString()),
        )

        assertEquals(1, projects.size)
        assertEquals(projectDir.toAbsolutePath().normalize(), projects.single().path)
    }

    @Test
    fun `ignores projects inside hidden directories except idea metadata`() {
        val root = Files.createTempDirectory("scanner-hidden-root")
        val hiddenDirProject = root.resolve(".hidden").resolve("inner").createDirectories()
        hiddenDirProject.resolve("settings.gradle.kts").writeText("rootProject.name = \"inner\"")

        val visibleProject = root.resolve("visible").createDirectories()
        visibleProject.resolve(".idea").createDirectories()

        val projects = ProjectScanner.scan(listOf(root.toString()))

        assertEquals(1, projects.size)
        assertEquals("visible", projects.single().name)
    }

    @Test
    fun `finds multiple projects under one root`() {
        val root = Files.createTempDirectory("scanner-multi-root")
        createProject(root.resolve("one"), "one", "ref: refs/heads/main\n")
        createProject(root.resolve("two"), "two", "ref: refs/heads/release\n")

        val projects = ProjectScanner.scan(listOf(root.toString()))

        assertEquals(2, projects.size)
        assertTrue(projects.any { it.name == "one" && it.branch == "main" })
        assertTrue(projects.any { it.name == "two" && it.branch == "release" })
    }

    @Test
    fun `reads branch and repository root from an ancestor repository`() {
        val root = Files.createTempDirectory("scanner-parent-repository-root")
        root.resolve(".git").createDirectories().resolve("HEAD").writeText("ref: refs/heads/feature/parent\n")
        val projectDir = root.resolve("nested").createDirectories()
        projectDir.resolve("build.gradle.kts").writeText("plugins {}")

        val project = ProjectScanner.scan(listOf(root.toString())).single()

        assertEquals("feature/parent", project.branch)
        assertEquals(root.toAbsolutePath().normalize(), project.repositoryRoot)
    }

    @Test
    fun `preserves the discovered alias path while using the real repository root`() {
        val root = Files.createTempDirectory("scanner-alias-root")
        val realRoot = root.resolve("real-root").createDirectories()
        val realProject = realRoot.resolve("nested-project").createDirectories()
        realProject.resolve("settings.gradle.kts").writeText("rootProject.name = \"real-project\"")
        realProject.resolve(".git").createDirectories().resolve("HEAD")
            .writeText("ref: refs/heads/main\n")
        val alias = root.resolve("alias")
        try {
            Files.createSymbolicLink(alias, realRoot)
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: java.nio.file.FileSystemException) {
            return
        }

        val project = ProjectScanner.scan(listOf(alias.toString())).single()

        assertEquals(alias.resolve("nested-project").toAbsolutePath().normalize(), project.path)
        assertEquals(realProject.toAbsolutePath().normalize(), project.repositoryRoot)
    }

    @Test
    fun `keeps healthy projects when another git metadata file is malformed`() {
        val root = Files.createTempDirectory("scanner-malformed-git-root")
        val healthy = root.resolve("healthy").createDirectories()
        healthy.resolve("settings.gradle.kts").writeText("rootProject.name = \"healthy\"")
        healthy.resolve(".git").createDirectories().resolve("HEAD").writeText("ref: refs/heads/main\n")
        val malformed = root.resolve("malformed").createDirectories()
        malformed.resolve("settings.gradle.kts").writeText("rootProject.name = \"malformed\"")
        malformed.resolve(".git").writeText("gitdir: " + '\u0000' + "invalid\n")

        val projects = ProjectScanner.scan(listOf(root.toString()))

        assertEquals(setOf("healthy", "malformed"), projects.map { it.name }.toSet())
        assertEquals("main", projects.single { it.name == "healthy" }.branch)
        assertNull(projects.single { it.name == "malformed" }.branch)
    }

    @Test
    fun `does not follow a directory symlink back to an ancestor`() {
        val root = Files.createTempDirectory("scanner-cycle-root")
        val projectDir = root.resolve("project").createDirectories()
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"project\"")
        val link = root.resolve("cycle")
        try {
            Files.createSymbolicLink(link, root)
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: java.nio.file.FileSystemException) {
            return
        }

        val projects = ProjectScanner.scan(listOf(root.toString()))

        assertEquals(listOf("project"), projects.map { it.name })
    }

    @Test
    fun `does not treat nested gradle modules as standalone projects`() {
        val root = Files.createTempDirectory("scanner-nested-root")
        val projectDir = root.resolve("workspace").createDirectories()
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"workspace\"")
        projectDir.resolve(".git").createDirectories()
        projectDir.resolve(".git").resolve("HEAD").writeText("ref: refs/heads/main\n")
        projectDir.resolve("app").createDirectories().resolve("build.gradle.kts").writeText("plugins {}")
        projectDir.resolve("feature").createDirectories().resolve("pom.xml").writeText("<project/>")

        val projects = ProjectScanner.scan(listOf(root.toString()))

        assertEquals(1, projects.size)
        assertEquals(projectDir.toAbsolutePath().normalize(), projects.single().path)
    }

    private fun createProject(path: Path, name: String, head: String) {
        path.createDirectories()
        path.resolve("settings.gradle.kts").writeText("rootProject.name = \"$name\"")
        path.resolve(".git").createDirectories()
        path.resolve(".git").resolve("HEAD").writeText(head)

        val project = ProjectScanner.scan(listOf(path.parent.toString())).find { it.name == name }
        assertNotNull(project)
    }
}
