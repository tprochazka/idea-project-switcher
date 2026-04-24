package cz.atomsoft.projectswitcher.projectswitcherplugin

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object ProjectScanner {
    private val ignoredDirectories = setOf(".git", ".gradle", "build", "out", ".idea_modules", "node_modules")

    fun scan(rootPaths: List<String>): List<ProjectEntry> {
        val projects = linkedMapOf<Path, ProjectEntry>()

        for (rootPath in rootPaths) {
            val root = runCatching { Paths.get(rootPath).toAbsolutePath().normalize() }.getOrNull()
                ?: continue
            if (!Files.isDirectory(root)) continue

            scanDirectory(root, root, projects)
        }

        return projects.values.toList()
    }

    private fun scanDirectory(current: Path, scanRoot: Path, projects: MutableMap<Path, ProjectEntry>) {
        if (!Files.isDirectory(current)) return
        if (current != scanRoot && shouldSkipDirectory(current)) return

        if (isProjectDirectory(current)) {
            projects.putIfAbsent(
                current,
                ProjectEntry(
                    path = current,
                    scanRoot = scanRoot,
                    name = current.fileName?.toString() ?: current.toString(),
                    branch = GitBranchReader.readBranch(current),
                ),
            )
            return
        }

        Files.newDirectoryStream(current).use { children ->
            children
                .asSequence()
                .filter { Files.isDirectory(it) }
                .sortedBy { it.fileName?.toString().orEmpty().lowercase() }
                .forEach { child ->
                    scanDirectory(child.toAbsolutePath().normalize(), scanRoot, projects)
                }
        }
    }

    private fun shouldSkipDirectory(path: Path): Boolean {
        val name = path.fileName?.toString() ?: return false
        if (name in ignoredDirectories) return true
        if (name.startsWith(".") && name != ".idea") return true
        return false
    }

    private fun isProjectDirectory(path: Path): Boolean {
        return Files.isDirectory(path.resolve(".idea")) ||
            Files.exists(path.resolve("settings.gradle")) ||
            Files.exists(path.resolve("settings.gradle.kts")) ||
            Files.exists(path.resolve("build.gradle")) ||
            Files.exists(path.resolve("build.gradle.kts")) ||
            Files.exists(path.resolve("pom.xml")) ||
            Files.newDirectoryStream(path, "*.ipr").use { stream -> stream.iterator().hasNext() }
    }
}

private object GitBranchReader {
    fun readBranch(projectPath: Path): String? {
        val gitPath = resolveGitPath(projectPath) ?: return null
        val headFile = gitPath.resolve("HEAD")
        if (!Files.isRegularFile(headFile)) return null

        val head = runCatching { Files.readString(headFile).trim() }.getOrNull() ?: return null
        if (head.startsWith("ref:")) {
            return head.substringAfterLast('/').ifBlank { null }
        }
        return head.take(7).ifBlank { null }
    }

    private fun resolveGitPath(projectPath: Path): Path? {
        val dotGit = projectPath.resolve(".git")
        if (Files.isDirectory(dotGit)) return dotGit
        if (!Files.isRegularFile(dotGit)) return null

        val content = runCatching { Files.readString(dotGit).trim() }.getOrNull() ?: return null
        val gitDir = content.substringAfter("gitdir:", "").trim()
        if (gitDir.isBlank()) return null

        val path = Paths.get(gitDir)
        return if (path.isAbsolute) path.normalize() else projectPath.resolve(path).normalize()
    }
}
