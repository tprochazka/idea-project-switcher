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
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

class GitBranchChangeStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        project.service<GitBranchChangeProjectService>().start()
    }
}

@Service(Service.Level.PROJECT)
private class GitBranchChangeProjectService(
    private val project: Project,
) : Disposable {
    private var started = false

    @Synchronized
    fun start() {
        if (started || project.isDisposed) return

        project.messageBus.connect(this).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { repository ->
                ApplicationManager.getApplication().messageBus
                    .syncPublisher(ProjectBranchChangeListener.TOPIC)
                    .branchChanged(repository.root.path, repository.currentDisplayBranch())
            },
        )

        started = true
    }

    override fun dispose() {
        started = false
    }

    private fun GitRepository.currentDisplayBranch(): String? {
        return currentBranchName ?: currentRevision?.take(7)
    }
}
