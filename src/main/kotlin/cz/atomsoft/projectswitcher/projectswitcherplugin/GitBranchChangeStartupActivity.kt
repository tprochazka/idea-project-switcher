package cz.atomsoft.projectswitcher.projectswitcherplugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

class GitBranchChangeStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        project.messageBus.connect(project).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { repository ->
                ApplicationManager.getApplication().messageBus
                    .syncPublisher(ProjectBranchChangeListener.TOPIC)
                    .branchChanged(repository.root.path, repository.currentDisplayBranch())
            },
        )
    }

    private fun GitRepository.currentDisplayBranch(): String? {
        return currentBranchName ?: currentRevision?.take(7)
    }
}
