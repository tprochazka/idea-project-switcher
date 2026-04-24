package cz.atomsoft.projectswitcher.projectswitcherplugin

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
        assertEquals("login", project.branch)
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
