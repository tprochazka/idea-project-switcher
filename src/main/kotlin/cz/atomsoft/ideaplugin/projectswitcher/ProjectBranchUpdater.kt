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
