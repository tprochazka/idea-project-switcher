package cz.atomsoft.projectswitcher.projectswitcherplugin

import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

object ProjectBranchUpdater {
    fun updateBranch(entries: List<ProjectEntry>, repositoryRoot: String, branch: String?): List<ProjectEntry> {
        val rootPath = runCatching { Paths.get(repositoryRoot).toAbsolutePath().normalize() }.getOrNull()
            ?: return entries

        var changed = false
        val updatedEntries = entries.map { entry ->
            if (!isSameOrUnder(entry.path, rootPath) || entry.branch == branch) {
                entry
            } else {
                changed = true
                entry.copy(branch = branch)
            }
        }

        return if (changed) updatedEntries else entries
    }

    private fun isSameOrUnder(path: Path, possibleParent: Path): Boolean {
        val pathId = path.toAbsolutePath().normalize().hierarchyId()
        val parentId = possibleParent.toAbsolutePath().normalize().hierarchyId()
        return pathId == parentId || pathId.startsWith("$parentId\\")
    }

    private fun Path.hierarchyId(): String {
        return toString()
            .trimEnd('\\', '/')
            .replace('/', '\\')
            .lowercase(Locale.ROOT)
    }
}
