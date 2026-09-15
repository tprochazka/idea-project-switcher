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

import java.nio.file.Paths

object ProjectBranchUpdater {
    fun updateBranch(entries: List<ProjectEntry>, repositoryRoot: String, branch: String?): List<ProjectEntry> {
        val rootPath = runCatching { Paths.get(repositoryRoot) }
            .mapCatching(ProjectPathUtils::normalize)
            .getOrNull()
            ?: return entries

        var changed = false
        val updatedEntries = entries.map { entry ->
            if (entry.repositoryRoot == null ||
                !ProjectPathUtils.samePath(entry.repositoryRoot, rootPath) ||
                entry.branch == branch
            ) {
                entry
            } else {
                changed = true
                entry.copy(branch = branch)
            }
        }

        return if (changed) updatedEntries else entries
    }
}
