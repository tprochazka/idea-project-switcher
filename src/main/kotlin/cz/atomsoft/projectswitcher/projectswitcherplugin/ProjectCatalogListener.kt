package cz.atomsoft.projectswitcher.projectswitcherplugin

import com.intellij.util.messages.Topic
import java.util.EventListener

fun interface ProjectCatalogListener : EventListener {
    fun catalogChanged(snapshot: ProjectCatalogSnapshot)

    companion object {
        val TOPIC: Topic<ProjectCatalogListener> = Topic.create(
            "Project Switcher catalog changes",
            ProjectCatalogListener::class.java,
        )
    }
}
