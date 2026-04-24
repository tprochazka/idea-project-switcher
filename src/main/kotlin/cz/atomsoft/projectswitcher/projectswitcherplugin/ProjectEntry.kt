package cz.atomsoft.projectswitcher.projectswitcherplugin

import java.nio.file.Path

data class ProjectEntry(
    val path: Path,
    val scanRoot: Path,
    val name: String,
    val branch: String?,
)

enum class SortMode {
    ALPHABETICAL,
    RECENT,
}

enum class ViewMode {
    FLAT,
    TREE,
}
