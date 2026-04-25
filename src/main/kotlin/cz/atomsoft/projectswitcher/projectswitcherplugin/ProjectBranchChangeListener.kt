package cz.atomsoft.projectswitcher.projectswitcherplugin

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
