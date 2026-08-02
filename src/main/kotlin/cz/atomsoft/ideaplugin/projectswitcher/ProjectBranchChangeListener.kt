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

import com.intellij.util.messages.Topic
import java.util.EventListener

fun interface ProjectBranchChangeListener : EventListener {
    fun branchChanged(repositoryRoot: String, branch: String?)

    companion object {
        val TOPIC: Topic<ProjectBranchChangeListener> = Topic.create(
            "Project Switcher branch changes",
            ProjectBranchChangeListener::class.java,
        )
    }
}
