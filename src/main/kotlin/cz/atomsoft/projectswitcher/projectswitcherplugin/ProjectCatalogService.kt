package cz.atomsoft.projectswitcher.projectswitcherplugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.nio.file.Paths

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
                    entries = emptyList(),
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
            val retainedEntries = current.entries.takeIf { current.rootPaths == normalizedRoots }.orEmpty()
            currentSnapshot = ProjectCatalogSnapshot(
                rootPaths = normalizedRoots,
                entries = retainedEntries,
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
                            entries = entries,
                            state = ProjectCatalogState.READY,
                        )
                    },
                    onFailure = {
                        ProjectCatalogSnapshot(
                            rootPaths = request.rootPaths,
                            entries = currentSnapshot.entries,
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
            val updatedEntries = ProjectBranchUpdater.updateBranch(currentSnapshot.entries, repositoryRoot, branch)
            if (updatedEntries === currentSnapshot.entries) return

            currentSnapshot = currentSnapshot.copy(entries = updatedEntries)
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
    val entries: List<ProjectEntry> = emptyList(),
    val state: ProjectCatalogState = ProjectCatalogState.NO_ROOTS,
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
