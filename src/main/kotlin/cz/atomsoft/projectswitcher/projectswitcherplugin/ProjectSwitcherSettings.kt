package cz.atomsoft.projectswitcher.projectswitcherplugin

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
