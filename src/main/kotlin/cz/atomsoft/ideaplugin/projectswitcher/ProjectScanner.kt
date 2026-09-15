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
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

object ProjectScanner {
    private val ignoredDirectories = setOf(".git", ".gradle", "build", "out", ".idea_modules", "node_modules")

    fun scan(rootPaths: List<String>): List<ProjectEntry> {
        val projects = linkedMapOf<String, ProjectEntry>()
        val visitedDirectories = hashSetOf<String>()

        for (rootPath in rootPaths) {
            val root = runCatching { Paths.get(rootPath) }
                .mapCatching { ProjectPathUtils.normalize(it) }
                .getOrNull()
                ?: continue
            if (!isDirectory(root)) continue

            scanDirectory(root, root, projects, visitedDirectories)
        }

        return projects.values.toList()
    }

    private fun scanDirectory(
        candidate: Path,
        scanRoot: Path,
        projects: MutableMap<String, ProjectEntry>,
        visitedDirectories: MutableSet<String>,
    ) {
        if (candidate != scanRoot && shouldSkipDirectory(candidate)) return

        val current = runCatching { ProjectPathUtils.realPathOrNormalized(candidate) }.getOrNull() ?: return
        if (!isDirectory(current)) return
        if (!visitedDirectories.add(ProjectPathUtils.key(current))) return

        if (isProjectDirectory(current)) {
            val repositoryRoot = GitRepositoryLocator.findRoot(current)
            val entry = ProjectEntry(
                path = ProjectPathUtils.normalize(candidate),
                scanRoot = scanRoot,
                name = candidate.fileName?.toString() ?: current.toString(),
                branch = repositoryRoot?.let(GitBranchReader::readBranch),
                repositoryRoot = repositoryRoot,
            )
            projects.putIfAbsent(ProjectPathUtils.key(current), entry)
            return
        }

        for (child in childDirectories(candidate)) {
            scanDirectory(child, scanRoot, projects, visitedDirectories)
        }
    }

    private fun shouldSkipDirectory(path: Path): Boolean {
        val name = path.fileName?.toString() ?: return false
        if (name in ignoredDirectories) return true
        if (name.startsWith(".") && name != ".idea") return true
        return false
    }

    private fun isProjectDirectory(path: Path): Boolean {
        return isDirectory(path.resolve(".idea")) ||
            exists(path.resolve("settings.gradle")) ||
            exists(path.resolve("settings.gradle.kts")) ||
            exists(path.resolve("build.gradle")) ||
            exists(path.resolve("build.gradle.kts")) ||
            exists(path.resolve("pom.xml")) ||
            runCatching {
                Files.newDirectoryStream(path, "*.ipr").use { stream -> stream.iterator().hasNext() }
            }.getOrDefault(false)
    }

    private fun childDirectories(path: Path): List<Path> {
        return runCatching {
            val children = mutableListOf<Path>()
            Files.newDirectoryStream(path).use { stream ->
                val iterator = stream.iterator()
                while (true) {
                    val hasNext = runCatching { iterator.hasNext() }.getOrDefault(false)
                    if (!hasNext) break

                    val child = runCatching { iterator.next() }.getOrNull() ?: continue
                    if (isDirectory(child) && !isDirectoryLink(child)) {
                        children.add(child)
                    }
                }
            }
            children.sortedBy { it.fileName?.toString().orEmpty().lowercase() }
        }.getOrDefault(emptyList())
    }

    private fun isDirectory(path: Path): Boolean {
        return runCatching { Files.isDirectory(path) }.getOrDefault(false)
    }

    private fun exists(path: Path): Boolean {
        return runCatching { Files.exists(path) }.getOrDefault(false)
    }

    private fun isDirectoryLink(path: Path): Boolean {
        return runCatching {
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).isSymbolicLink && Files.isDirectory(path)
        }.getOrDefault(false)
    }
}

private object GitBranchReader {
    fun readBranch(projectPath: Path): String? {
        val gitPath = resolveGitPath(projectPath) ?: return null
        val headFile = gitPath.resolve("HEAD")
        if (!runCatching { Files.isRegularFile(headFile) }.getOrDefault(false)) return null

        val head = runCatching { Files.readString(headFile).trim() }.getOrNull() ?: return null
        if (head.startsWith("ref:")) {
            val ref = head.removePrefix("ref:").trim()
            return ref.removePrefix("refs/heads/")
                .takeIf { ref.startsWith("refs/heads/") }
                ?.ifBlank { null }
        }
        return head.take(7).ifBlank { null }
    }

    private fun resolveGitPath(projectPath: Path): Path? {
        val dotGit = projectPath.resolve(".git")
        if (runCatching { Files.isDirectory(dotGit) }.getOrDefault(false)) return dotGit
        if (!runCatching { Files.isRegularFile(dotGit) }.getOrDefault(false)) return null

        val content = runCatching { Files.readString(dotGit).trim() }.getOrNull() ?: return null
        val gitDir = content.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("gitdir:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            .orEmpty()
        if (gitDir.isBlank()) return null

        return runCatching {
            val path = Paths.get(gitDir)
            if (path.isAbsolute) path.normalize() else projectPath.resolve(path).normalize()
        }.getOrNull()
    }
}

private object GitRepositoryLocator {
    fun findRoot(projectPath: Path): Path? {
        var current: Path? = ProjectPathUtils.normalize(projectPath)
        while (current != null) {
            if (hasGitMetadata(current)) return current
            current = current.parent
        }
        return null
    }

    private fun hasGitMetadata(path: Path): Boolean {
        val dotGit = path.resolve(".git")
        return runCatching {
            Files.isDirectory(dotGit) || Files.isRegularFile(dotGit)
        }.getOrDefault(false)
    }
}
