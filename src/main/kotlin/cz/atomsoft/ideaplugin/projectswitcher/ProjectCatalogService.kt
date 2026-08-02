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

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.nio.file.Paths
import java.text.Normalizer
import java.util.Locale

@Service(Service.Level.APP)
class ProjectCatalogService : Disposable {
    private val lock = Any()
    private var currentSnapshot = ProjectCatalogSnapshot()
    private var scanSequence = 0
    private var disposed = false

    init {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            ProjectBranchChangeListener.TOPIC,
            ProjectBranchChangeListener { repositoryRoot, branch ->
                updateBranch(repositoryRoot, branch)
            },
        )
    }

    fun snapshot(): ProjectCatalogSnapshot {
        return synchronized(lock) { currentSnapshot }
    }

    fun ensureLoaded(rootPaths: List<String>) {
        refresh(rootPaths, force = false)
    }

    fun refresh(rootPaths: List<String>, force: Boolean) {
        val normalizedRoots = normalizeRootPaths(rootPaths)
        if (normalizedRoots.isEmpty()) {
            val snapshot = synchronized(lock) {
                scanSequence++
                currentSnapshot = ProjectCatalogSnapshot(
                    rootPaths = emptyList(),
                    items = emptyList(),
                    state = ProjectCatalogState.NO_ROOTS,
                )
                currentSnapshot
            }
            publish(snapshot)
            return
        }

        val request = synchronized(lock) {
            if (disposed) return

            val current = currentSnapshot
            if (!force && current.rootPaths == normalizedRoots) {
                when (current.state) {
                    ProjectCatalogState.SCANNING,
                    ProjectCatalogState.READY,
                    ProjectCatalogState.FAILED,
                    -> return
                    ProjectCatalogState.NO_ROOTS -> Unit
                }
            }

            if (current.state == ProjectCatalogState.SCANNING && current.rootPaths == normalizedRoots) {
                return
            }

            val id = ++scanSequence
            val retainedItems = current.items.takeIf { current.rootPaths == normalizedRoots }.orEmpty()
            currentSnapshot = ProjectCatalogSnapshot(
                rootPaths = normalizedRoots,
                items = retainedItems,
                state = ProjectCatalogState.SCANNING,
            )
            ScanRequest(id, normalizedRoots, currentSnapshot)
        }

        publish(request.scanningSnapshot)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { ProjectScanner.scan(request.rootPaths) }
            val snapshot = synchronized(lock) {
                if (disposed || request.id != scanSequence) return@executeOnPooledThread

                currentSnapshot = result.fold(
                    onSuccess = { entries ->
                        ProjectCatalogSnapshot(
                            rootPaths = request.rootPaths,
                            items = entries.toCatalogItems(),
                            state = ProjectCatalogState.READY,
                        )
                    },
                    onFailure = {
                        ProjectCatalogSnapshot(
                            rootPaths = request.rootPaths,
                            items = currentSnapshot.items,
                            state = ProjectCatalogState.FAILED,
                        )
                    },
                )
                currentSnapshot
            }
            publish(snapshot)
        }
    }

    override fun dispose() {
        synchronized(lock) {
            disposed = true
            scanSequence++
            currentSnapshot = ProjectCatalogSnapshot()
        }
    }

    private fun updateBranch(repositoryRoot: String, branch: String?) {
        val snapshot = synchronized(lock) {
            val currentEntries = currentSnapshot.items.map { it.entry }
            val updatedEntries = ProjectBranchUpdater.updateBranch(currentEntries, repositoryRoot, branch)
            if (updatedEntries === currentEntries) return

            currentSnapshot = currentSnapshot.copy(items = updatedEntries.toCatalogItems())
            currentSnapshot
        }
        publish(snapshot)
    }

    private fun publish(snapshot: ProjectCatalogSnapshot) {
        if (disposed) return

        ApplicationManager.getApplication().messageBus
            .syncPublisher(ProjectCatalogListener.TOPIC)
            .catalogChanged(snapshot)
    }

    companion object {
        fun getInstance(): ProjectCatalogService {
            return ApplicationManager.getApplication().getService(ProjectCatalogService::class.java)
        }

        private fun normalizeRootPaths(rootPaths: List<String>): List<String> {
            return rootPaths
                .mapNotNull { rootPath ->
                    runCatching { Paths.get(rootPath).toAbsolutePath().normalize().toString() }.getOrNull()
                }
                .distinct()
        }
    }
}

data class ProjectCatalogSnapshot(
    val rootPaths: List<String> = emptyList(),
    val items: List<ProjectCatalogItem> = emptyList(),
    val state: ProjectCatalogState = ProjectCatalogState.NO_ROOTS,
)

data class ProjectCatalogItem(
    val entry: ProjectEntry,
    val searchKey: String,
)

enum class ProjectCatalogState {
    NO_ROOTS,
    SCANNING,
    READY,
    FAILED,
}

private data class ScanRequest(
    val id: Int,
    val rootPaths: List<String>,
    val scanningSnapshot: ProjectCatalogSnapshot,
)

fun createProjectSearchKey(entry: ProjectEntry): String {
    return normalizeSearchText("${entry.name} ${entry.branch.orEmpty()}")
}

fun normalizeSearchText(text: String): String {
    val withoutDiacritics = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    val builder = StringBuilder(withoutDiacritics.length)

    for (char in withoutDiacritics) {
        if (char.category == CharCategory.NON_SPACING_MARK) continue

        val normalizedChar = when (char) {
            'y' -> 'i'
            'z' -> 's'
            else -> char
        }
        if (normalizedChar.isLetterOrDigit()) {
            builder.append(normalizedChar)
        }
    }

    return builder.toString()
}

private fun List<ProjectEntry>.toCatalogItems(): List<ProjectCatalogItem> {
    return map { entry ->
        ProjectCatalogItem(
            entry = entry,
            searchKey = createProjectSearchKey(entry),
        )
    }
}
