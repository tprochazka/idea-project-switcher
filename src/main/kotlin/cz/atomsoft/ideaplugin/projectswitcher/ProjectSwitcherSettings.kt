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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(
    name = "ProjectSwitcherSettings",
    storages = [Storage("branch-project-switcher.xml")],
)
class ProjectSwitcherSettings : PersistentStateComponent<ProjectSwitcherSettings.State> {
    private var currentState = State()

    override fun getState(): State = currentState

    override fun loadState(state: State) {
        currentState = state
    }

    class State {
        var rootPaths: MutableList<String> = mutableListOf()
        var searchQuery: String = ""
        var sortMode: String = SortMode.ALPHABETICAL.name
        var viewMode: String = ViewMode.FLAT.name
        var flatScrollX: Int = 0
        var flatScrollY: Int = 0
        var treeScrollX: Int = 0
        var treeScrollY: Int = 0
        var treeExpansionStateSaved: Boolean = false
        var treeExpandedPathIds: MutableList<String> = mutableListOf()

        val sortModeEnum: SortMode
            get() = runCatching { SortMode.valueOf(sortMode) }.getOrDefault(SortMode.ALPHABETICAL)

        val viewModeEnum: ViewMode
            get() = runCatching { ViewMode.valueOf(viewMode) }.getOrDefault(ViewMode.FLAT)
    }

    companion object {
        fun getInstance(): ProjectSwitcherSettings {
            return ApplicationManager.getApplication().getService(ProjectSwitcherSettings::class.java)
        }
    }
}
