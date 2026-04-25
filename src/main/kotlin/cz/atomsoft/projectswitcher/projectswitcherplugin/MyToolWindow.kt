package cz.atomsoft.projectswitcher.projectswitcherplugin

import com.intellij.icons.AllIcons
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.hover.ListHoverListener
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreePath

class MyToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ProjectSwitcherPanel(project)
        toolWindow.setTitleActions(listOf(panel.createRefreshAction()))
        toolWindow.setAdditionalGearActions(panel.createOptionsActionGroup())
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

private class ProjectSwitcherPanel(
    private val project: Project,
) : JPanel(BorderLayout()), Disposable {
    private val settings = ProjectSwitcherSettings.getInstance()
    private val projectIconProvider = ProjectIconProvider()
    private val searchField = SearchTextField(false)
    private val statusLabel = JBLabel()
    private val contentPanel = JPanel(BorderLayout())
    private var entries: List<ProjectEntry> = emptyList()
    private var renderedScrollPane: JBScrollPane? = null
    private var renderedTree: Tree? = null
    private var renderedViewMode: ViewMode? = null
    private var restoringTreeExpansionState = false
    private var disposed = false

    init {
        border = JBUI.Borders.empty(4)
        add(createSearchBar(), BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        subscribeToBranchChanges()
        refreshProjects()
    }

    override fun dispose() {
        disposed = true
    }

    fun createRefreshAction(): AnAction {
        return object : DumbAwareAction(
            "Refresh",
            "Rescan configured project folders",
            AllIcons.Actions.Refresh,
        ) {
            override fun actionPerformed(event: AnActionEvent) {
                refreshProjects()
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }
    }

    fun createOptionsActionGroup(): ActionGroup {
        return DefaultActionGroup("Project Switcher Options", false).apply {
            add(SortModeAction(SortMode.ALPHABETICAL))
            add(SortModeAction(SortMode.RECENT))
            addSeparator()
            add(ViewModeAction(ViewMode.FLAT))
            add(ViewModeAction(ViewMode.TREE))
            addSeparator()
            add(OpenSettingsAction())
        }
    }

    private fun createSearchBar(): JComponent {
        val panel = JPanel(BorderLayout())

        searchField.textEditor.columns = 18
        searchField.text = settings.state.searchQuery
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = updateSearch()

            override fun removeUpdate(event: DocumentEvent) = updateSearch()

            override fun changedUpdate(event: DocumentEvent) = updateSearch()

            private fun updateSearch() {
                saveVisibleState()
                settings.state.searchQuery = searchField.text
                ApplicationManager.getApplication().saveSettings()
                renderEntries()
            }
        })

        panel.add(searchField, BorderLayout.CENTER)
        panel.border = JBUI.Borders.emptyBottom(4)
        return panel
    }

    private fun setSortMode(mode: SortMode) {
        if (settings.state.sortModeEnum == mode) return

        saveVisibleState()
        settings.state.sortMode = mode.name
        ApplicationManager.getApplication().saveSettings()
        renderEntries()
    }

    private fun setViewMode(mode: ViewMode) {
        if (settings.state.viewModeEnum == mode) return

        saveVisibleState()
        settings.state.viewMode = mode.name
        ApplicationManager.getApplication().saveSettings()
        renderEntries()
    }

    private fun openSettings() {
        val changed = ShowSettingsUtil.getInstance().editConfigurable(project, ProjectSwitcherConfigurable())
        if (changed) {
            refreshProjects()
        }
    }

    private fun refreshProjects() {
        val roots = settings.state.rootPaths.toList()
        if (roots.isEmpty()) {
            entries = emptyList()
            statusLabel.text = "No folders configured"
            renderEntries()
            return
        }

        statusLabel.text = "Scanning..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val scanned = ProjectScanner.scan(roots)
            SwingUtilities.invokeLater {
                if (project.isDisposed) return@invokeLater
                entries = scanned
                statusLabel.text = "${entries.size} project(s)"
                renderEntries()
            }
        }
    }

    private fun subscribeToBranchChanges() {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            ProjectBranchChangeListener.TOPIC,
            ProjectBranchChangeListener { repositoryRoot, branch ->
                SwingUtilities.invokeLater {
                    if (project.isDisposed || disposed) return@invokeLater
                    val updatedEntries = ProjectBranchUpdater.updateBranch(entries, repositoryRoot, branch)
                    if (updatedEntries === entries) return@invokeLater
                    entries = updatedEntries
                    renderEntries()
                }
            },
        )
    }

    private inner class SortModeAction(
        private val mode: SortMode,
    ) : ToggleAction("Sort: ${mode.displayText()}") {
        override fun isSelected(event: AnActionEvent): Boolean = settings.state.sortModeEnum == mode

        override fun setSelected(event: AnActionEvent, selected: Boolean) {
            if (selected) setSortMode(mode)
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class ViewModeAction(
        private val mode: ViewMode,
    ) : ToggleAction("View: ${mode.displayText()}") {
        override fun isSelected(event: AnActionEvent): Boolean = settings.state.viewModeEnum == mode

        override fun setSelected(event: AnActionEvent, selected: Boolean) {
            if (selected) setViewMode(mode)
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class OpenSettingsAction : DumbAwareAction(
        "Settings...",
        "Configure scanned folders",
        AllIcons.General.Settings,
    ) {
        override fun actionPerformed(event: AnActionEvent) {
            openSettings()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private fun renderEntries() {
        saveVisibleState()
        contentPanel.removeAll()
        renderedScrollPane = null
        renderedTree = null
        renderedViewMode = settings.state.viewModeEnum
        val sorted = filteredEntries().sortedWith(projectComparator())
        val component = when (settings.state.viewModeEnum) {
            ViewMode.FLAT -> createFlatList(sorted)
            ViewMode.TREE -> createTree(sorted)
        }
        contentPanel.add(component, BorderLayout.CENTER)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun projectComparator(): Comparator<ProjectEntry> {
        return when (settings.state.sortModeEnum) {
            SortMode.ALPHABETICAL -> Comparator { left, right -> compareProjectsByNameAndPath(left, right) }
            SortMode.RECENT -> {
                val recent = RecentProjectsManagerBase.getInstanceEx()
                Comparator { left, right ->
                    val byTimestamp = recentTimestamp(right, recent).compareTo(recentTimestamp(left, recent))
                    if (byTimestamp != 0) byTimestamp else compareProjectsByNameAndPath(left, right)
                }
            }
        }
    }

    private fun filteredEntries(): List<ProjectEntry> {
        val query = settings.state.searchQuery.trim()
        if (query.isEmpty()) return entries

        return entries.filter { entry ->
            entry.name.contains(query, ignoreCase = true) ||
                entry.branch?.contains(query, ignoreCase = true) == true
        }
    }

    private fun recentTimestamp(entry: ProjectEntry, recent: RecentProjectsManagerBase): Long {
        return recent.getActivationTimestamp(entry.path.toString())
            ?: recent.getProjectMetaInfo(entry.path.toString())?.projectOpenTimestamp
            ?: 0L
    }

    private fun compareProjectsByNameAndPath(left: ProjectEntry, right: ProjectEntry): Int {
        val byName = String.CASE_INSENSITIVE_ORDER.compare(left.name, right.name)
        if (byName != 0) return byName
        return String.CASE_INSENSITIVE_ORDER.compare(left.path.toString(), right.path.toString())
    }

    private fun createFlatList(projects: List<ProjectEntry>): JComponent {
        val model = DefaultListModel<ProjectEntry>()
        projects.forEach(model::addElement)
        val list = JBList(model)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.emptyText.text = "No projects found"
        ListHoverListener.DEFAULT.addTo(list)
        list.cellRenderer = ProjectListRenderer(projectIconProvider) { activeProjectPathIds() }
        list.addMouseListener(ProjectMouseListener { event ->
            val index = list.locationToIndex(event.point)
            if (index >= 0) model.getElementAt(index) else null
        })
        val scrollPane = JBScrollPane(list)
        registerScrollPersistence(scrollPane, ViewMode.FLAT)
        restoreScrollPosition(scrollPane, settings.state.flatScrollX, settings.state.flatScrollY)
        renderedScrollPane = scrollPane
        return scrollPane
    }

    private fun createTree(projects: List<ProjectEntry>): JComponent {
        val root = DefaultMutableTreeNode("Projects")
        val rootNodes = linkedMapOf<Path, DefaultMutableTreeNode>()

        for (entry in projects) {
            val configuredRoot = entry.scanRoot
            val topNode = rootNodes.getOrPut(configuredRoot) {
                val node = DefaultMutableTreeNode(
                    DirectoryNode(
                        name = configuredRoot.fileName?.toString() ?: configuredRoot.toString(),
                        id = configuredRoot.toString(),
                    ),
                )
                root.add(node)
                node
            }

            val relative = runCatching { configuredRoot.relativize(entry.path) }.getOrNull()
            if (relative == null || relative.nameCount == 0) {
                topNode.add(DefaultMutableTreeNode(entry))
                continue
            }

            var current = topNode
            var currentPath = configuredRoot
            for (i in 0 until relative.nameCount - 1) {
                val segment = relative.getName(i).toString()
                currentPath = currentPath.resolve(segment).normalize()
                current = findOrCreateDirectoryNode(current, segment, currentPath.toString())
            }
            current.add(DefaultMutableTreeNode(entry))
        }
        sortTreeChildren(root, projectComparator())

        val tree = ProjectTree(DefaultTreeModel(root)) { activeProjectPathIds() }
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.rowHeight = 0
        tree.cellRenderer = ProjectTreeRenderer(projectIconProvider) { activeProjectPathIds() }
        tree.addMouseListener(ProjectMouseListener { event ->
            val path = tree.getPathForLocation(event.x, event.y) ?: return@ProjectMouseListener null
            (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ProjectEntry
        })
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) {
                if (!restoringTreeExpansionState) rememberExpandedTreePath(event.path)
            }

            override fun treeCollapsed(event: TreeExpansionEvent) {
                if (!restoringTreeExpansionState) rememberCollapsedTreePath(event.path)
            }
        })
        restoreTreeExpansionState(tree)
        val scrollPane = JBScrollPane(tree)
        registerScrollPersistence(scrollPane, ViewMode.TREE)
        restoreScrollPosition(scrollPane, settings.state.treeScrollX, settings.state.treeScrollY)
        renderedTree = tree
        renderedScrollPane = scrollPane
        return scrollPane
    }

    private fun findOrCreateDirectoryNode(parent: DefaultMutableTreeNode, name: String, id: String): DefaultMutableTreeNode {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) as DefaultMutableTreeNode
            val directory = child.userObject as? DirectoryNode
            if (directory?.id == id) return child
        }
        val child = DefaultMutableTreeNode(DirectoryNode(name, id))
        parent.add(child)
        return child
    }

    private fun sortTreeChildren(node: DefaultMutableTreeNode, projectComparator: Comparator<ProjectEntry>) {
        val sortedChildren = (0 until node.childCount)
            .map { node.getChildAt(it) as DefaultMutableTreeNode }
            .sortedWith { left, right -> compareTreeNodes(left, right, projectComparator) }

        node.removeAllChildren()
        sortedChildren.forEach { child ->
            sortTreeChildren(child, projectComparator)
            node.add(child)
        }
    }

    private fun compareTreeNodes(
        left: DefaultMutableTreeNode,
        right: DefaultMutableTreeNode,
        projectComparator: Comparator<ProjectEntry>,
    ): Int {
        val leftObject = left.userObject
        val rightObject = right.userObject

        return when {
            leftObject is DirectoryNode && rightObject is ProjectEntry -> -1
            leftObject is ProjectEntry && rightObject is DirectoryNode -> 1
            leftObject is DirectoryNode && rightObject is DirectoryNode -> {
                compareValuesBy(leftObject, rightObject, String.CASE_INSENSITIVE_ORDER, DirectoryNode::name)
                    .takeIf { it != 0 }
                    ?: compareValues(leftObject.id, rightObject.id)
            }
            leftObject is ProjectEntry && rightObject is ProjectEntry -> projectComparator.compare(leftObject, rightObject)
            else -> leftObject.toString().compareTo(rightObject.toString(), ignoreCase = true)
        }
    }

    private fun expandFirstLevel(tree: Tree) {
        val modelRoot = tree.model.root as DefaultMutableTreeNode
        for (i in 0 until modelRoot.childCount) {
            tree.expandPath(TreePath((modelRoot.getChildAt(i) as DefaultMutableTreeNode).path))
        }
    }

    private fun restoreTreeExpansionState(tree: Tree) {
        if (!settings.state.treeExpansionStateSaved) {
            expandFirstLevel(tree)
            saveTreeExpansionState(tree)
            return
        }

        restoringTreeExpansionState = true
        try {
            val expandedIds = settings.state.treeExpandedPathIds.toSet()
            val root = tree.model.root as DefaultMutableTreeNode
            expandDirectoryNodes(tree, root, expandedIds)
        } finally {
            restoringTreeExpansionState = false
        }
    }

    private fun expandDirectoryNodes(tree: Tree, node: DefaultMutableTreeNode, expandedIds: Set<String>): Boolean {
        val directory = node.userObject as? DirectoryNode
        var shouldExpand = directory?.id in expandedIds

        for (i in 0 until node.childCount) {
            val childShouldExpand = expandDirectoryNodes(tree, node.getChildAt(i) as DefaultMutableTreeNode, expandedIds)
            shouldExpand = shouldExpand || childShouldExpand
        }

        if (shouldExpand && directory != null) {
            tree.expandPath(TreePath(node.path))
        }
        return shouldExpand
    }

    private fun registerScrollPersistence(scrollPane: JBScrollPane, viewMode: ViewMode) {
        scrollPane.viewport.addChangeListener {
            if (renderedScrollPane === scrollPane || renderedScrollPane == null) {
                saveScrollPosition(scrollPane, viewMode)
            }
        }
    }

    private fun restoreScrollPosition(scrollPane: JBScrollPane, x: Int, y: Int) {
        SwingUtilities.invokeLater {
            scrollPane.viewport.viewPosition = Point(x.coerceAtLeast(0), y.coerceAtLeast(0))
        }
    }

    private fun saveVisibleState() {
        val scrollPane = renderedScrollPane
        val viewMode = renderedViewMode
        if (scrollPane != null && viewMode != null) {
            saveScrollPosition(scrollPane, viewMode)
        }
    }

    private fun saveScrollPosition(scrollPane: JBScrollPane, viewMode: ViewMode) {
        val position = scrollPane.viewport.viewPosition
        when (viewMode) {
            ViewMode.FLAT -> {
                settings.state.flatScrollX = position.x
                settings.state.flatScrollY = position.y
            }
            ViewMode.TREE -> {
                settings.state.treeScrollX = position.x
                settings.state.treeScrollY = position.y
            }
        }
    }

    private fun saveTreeExpansionState(tree: Tree) {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return
        val expandedIds = mutableListOf<String>()
        collectExpandedDirectoryIds(tree, root, expandedIds)
        settings.state.treeExpandedPathIds = expandedIds
        settings.state.treeExpansionStateSaved = true
        ApplicationManager.getApplication().saveSettings()
    }

    private fun collectExpandedDirectoryIds(tree: Tree, node: DefaultMutableTreeNode, expandedIds: MutableList<String>) {
        val path = TreePath(node.path)
        val directory = node.userObject as? DirectoryNode
        if (directory != null && tree.isExpanded(path)) {
            expandedIds.add(directory.id)
        }

        for (i in 0 until node.childCount) {
            collectExpandedDirectoryIds(tree, node.getChildAt(i) as DefaultMutableTreeNode, expandedIds)
        }
    }

    private fun rememberExpandedTreePath(path: TreePath) {
        val id = treePathDirectoryId(path) ?: return
        if (!settings.state.treeExpandedPathIds.contains(id)) {
            settings.state.treeExpandedPathIds.add(id)
        }
        settings.state.treeExpansionStateSaved = true
        ApplicationManager.getApplication().saveSettings()
    }

    private fun rememberCollapsedTreePath(path: TreePath) {
        val id = treePathDirectoryId(path) ?: return
        settings.state.treeExpandedPathIds.removeAll { expandedId ->
            expandedId == id || expandedId.startsWith("$id\\") || expandedId.startsWith("$id/")
        }
        settings.state.treeExpansionStateSaved = true
        ApplicationManager.getApplication().saveSettings()
    }

    private fun treePathDirectoryId(path: TreePath): String? {
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return (node.userObject as? DirectoryNode)?.id
    }

    private fun activeProjectPathIds(): Set<String> {
        return activeProjectPathIds(project)
    }

    private fun openProject(entry: ProjectEntry, newWindow: Boolean) {
        saveVisibleState()
        ProjectUtil.openOrImport(entry.path, project, newWindow)
    }

    private inner class ProjectMouseListener(
        private val entryAt: (MouseEvent) -> ProjectEntry?,
    ) : MouseAdapter() {
        override fun mouseClicked(event: MouseEvent) {
            if (SwingUtilities.isLeftMouseButton(event) && event.clickCount == 1) {
                val entry = entryAt(event) ?: return
                openProject(entry, newWindow = false)
            }
        }

        override fun mousePressed(event: MouseEvent) = maybeShowPopup(event)

        override fun mouseReleased(event: MouseEvent) = maybeShowPopup(event)

        private fun maybeShowPopup(event: MouseEvent) {
            if (!event.isPopupTrigger) return
            val entry = entryAt(event) ?: return
            val menu = JPopupMenu()
            menu.add(JMenuItem("Open in New Window").apply {
                addActionListener { openProject(entry, newWindow = true) }
            })
            menu.show(event.component, event.x, event.y)
        }
    }
}

private data class DirectoryNode(val name: String, val id: String)

private fun activeProjectPathIds(project: Project): Set<String> {
    val paths = linkedSetOf<Path>()
    project.basePath?.let { addProjectPathVariants(Paths.get(it), paths) }
    project.projectFilePath?.let { addProjectPathVariants(Paths.get(it), paths) }

    return paths
        .flatMapTo(linkedSetOf()) { normalizedPathIds(it) }
}

private fun addProjectPathVariants(path: Path, paths: MutableSet<Path>) {
    val normalized = path.toAbsolutePath().normalize()
    paths.add(normalized)

    val fileName = normalized.fileName?.toString() ?: return
    if (fileName.equals(".idea", ignoreCase = true) || fileName.endsWith(".ipr", ignoreCase = true)) {
        normalized.parent?.let(paths::add)
    }
}

private fun isActiveProjectPath(projectPath: Path, activeProjectPathIds: Set<String>): Boolean {
    if (activeProjectPathIds.isEmpty()) return false
    return normalizedPathIds(projectPath).any { it in activeProjectPathIds }
}

private fun normalizedPathIds(path: Path): Set<String> {
    val ids = linkedSetOf(path.toAbsolutePath().normalize().pathId())
    if (Files.exists(path)) {
        runCatching { path.toRealPath().normalize().pathId() }
            .getOrNull()
            ?.let(ids::add)
    }
    return ids
}

private fun Path.pathId(): String {
    return toString()
        .trimEnd('\\', '/')
        .lowercase(Locale.ROOT)
}

private class ProjectTree(
    model: DefaultTreeModel,
    private val activeProjectPathIdsProvider: () -> Set<String>,
) : Tree(model) {
    var hoveredRow: Int = -1
        private set

    init {
        isOpaque = false
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                updateHoveredRow(getRowForLocation(event.x, event.y))
            }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mouseExited(event: MouseEvent) {
                updateHoveredRow(-1)
            }
        })
    }

    override fun paintComponent(graphics: Graphics) {
        paintRowMarkers(graphics)
        super.paintComponent(graphics)
    }

    private fun updateHoveredRow(row: Int) {
        if (hoveredRow == row) return
        val previous = hoveredRow
        hoveredRow = row
        repaintRow(previous)
        repaintRow(row)
    }

    private fun repaintRow(row: Int) {
        if (row < 0) return
        val bounds = getRowBounds(row) ?: return
        repaint(0, bounds.y, width, bounds.height)
    }

    private fun paintRowMarkers(graphics: Graphics) {
        val activeProjectPathIds = activeProjectPathIdsProvider()

        val graphics2D = graphics.create() as Graphics2D
        try {
            graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (activeProjectPathIds.isNotEmpty()) {
                graphics2D.color = ACTIVE_PROJECT_BACKGROUND
                for (row in 0 until rowCount) {
                    val treePath = getPathForRow(row) ?: continue
                    val node = treePath.lastPathComponent as? DefaultMutableTreeNode ?: continue
                    val entry = node.userObject as? ProjectEntry ?: continue
                    if (!isActiveProjectPath(entry.path, activeProjectPathIds)) continue

                    paintRowMarker(graphics2D, row)
                }
            }

            if (hoveredRow >= 0) {
                graphics2D.color = HOVER_PROJECT_BACKGROUND
                paintRowMarker(graphics2D, hoveredRow)
            }
        } finally {
            graphics2D.dispose()
        }
    }

    private fun paintRowMarker(graphics2D: Graphics2D, row: Int) {
        val bounds = getRowBounds(row) ?: return
        val x = TREE_ROW_MARKER_HORIZONTAL_INSET
        val y = bounds.y + TREE_ROW_MARKER_VERTICAL_INSET
        val markerWidth = (width - x * 2).coerceAtLeast(0)
        val markerHeight = (bounds.height - TREE_ROW_MARKER_VERTICAL_INSET * 2).coerceAtLeast(0)
        graphics2D.fillRoundRect(
            x,
            y,
            markerWidth,
            markerHeight,
            TREE_ROW_MARKER_ARC,
            TREE_ROW_MARKER_ARC,
        )
    }
}

private class ProjectListRenderer(
    private val projectIconProvider: ProjectIconProvider,
    private val activeProjectPathIdsProvider: () -> Set<String>,
) : ListCellRenderer<ProjectEntry> {
    private val cell = ProjectCellPanel(
        JBUI.Borders.empty(6, 6),
    )

    override fun getListCellRendererComponent(
        list: JList<out ProjectEntry>,
        value: ProjectEntry?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ): Component {
        if (value == null) return cell.configureEmpty(list.background)
        val isActiveProject = isActiveProjectPath(value.path, activeProjectPathIdsProvider())
        val isHovered = index == ListHoverListener.getHoveredIndex(list)
        val foreground = if (selected) UIUtil.getListSelectionForeground(hasFocus) else list.foreground
        return cell.configure(
            projectIcon = projectIconProvider.getIcon(value),
            projectName = value.name,
            branch = value.branch,
            active = isActiveProject,
            backgroundColor = listCellBackground(list, selected, hasFocus, isHovered, isActiveProject),
            foregroundColor = foreground,
            branchForegroundColor = if (selected) foreground else BRANCH_FOREGROUND,
            baseFont = list.font,
            tooltip = value.path.toString(),
        )
    }
}

private class ProjectTreeRenderer(
    private val projectIconProvider: ProjectIconProvider,
    private val activeProjectPathIdsProvider: () -> Set<String>,
) : TreeCellRenderer {
    private val projectCell = ProjectCellPanel(
        JBUI.Borders.empty(4, 2, 5, 4),
    )
    private val directoryCell = DirectoryCellPanel()

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        val node = value as? DefaultMutableTreeNode
        val isHovered = row == (tree as? ProjectTree)?.hoveredRow
        return when (val userObject = node?.userObject) {
            is ProjectEntry -> {
                val isActiveProject = isActiveProjectPath(userObject.path, activeProjectPathIdsProvider())
                val foreground = UIUtil.getTreeForeground(selected, hasFocus)
                projectCell.configure(
                    projectIcon = projectIconProvider.getIcon(userObject),
                    projectName = userObject.name,
                    branch = userObject.branch,
                    active = isActiveProject,
                    backgroundColor = treeCellBackground(tree, selected, hasFocus, isHovered, isActiveProject),
                    foregroundColor = foreground,
                    branchForegroundColor = if (selected) foreground else BRANCH_FOREGROUND,
                    baseFont = tree.font,
                    tooltip = userObject.path.toString(),
                )
            }
            is DirectoryNode -> {
                directoryCell.configure(
                    icon = AllIcons.Nodes.Folder,
                    name = userObject.name,
                    backgroundColor = treeCellBackground(tree, selected, hasFocus, isHovered, active = false),
                    foregroundColor = UIUtil.getTreeForeground(selected, hasFocus),
                    baseFont = tree.font,
                )
            }
            else -> directoryCell.configure(
                icon = null,
                name = value.toString(),
                backgroundColor = tree.background,
                foregroundColor = tree.foreground,
                baseFont = tree.font,
            )
        }
    }
}

private class ProjectCellPanel(
    cellBorder: javax.swing.border.Border,
) : JPanel(BorderLayout()) {
    private val projectIconLabel = JBLabel()
    private val textPanel = JPanel()
    private val projectNameLabel = JBLabel()
    private val branchLabel = JBLabel()

    init {
        isOpaque = true
        border = cellBorder
        projectIconLabel.verticalAlignment = JLabel.TOP
        projectIconLabel.border = JBUI.Borders.empty(1, 0, 0, PROJECT_ICON_TEXT_GAP)

        textPanel.layout = BoxLayout(textPanel, BoxLayout.Y_AXIS)
        textPanel.isOpaque = false
        projectNameLabel.isOpaque = false
        branchLabel.isOpaque = false
        projectNameLabel.alignmentX = Component.LEFT_ALIGNMENT
        branchLabel.alignmentX = Component.LEFT_ALIGNMENT
        branchLabel.icon = AllIcons.Vcs.Branch
        branchLabel.iconTextGap = BRANCH_ICON_TEXT_GAP

        textPanel.add(projectNameLabel)
        textPanel.add(branchLabel)
        add(projectIconLabel, BorderLayout.WEST)
        add(textPanel, BorderLayout.CENTER)
    }

    fun configure(
        projectIcon: javax.swing.Icon,
        projectName: String,
        branch: String?,
        active: Boolean,
        backgroundColor: Color,
        foregroundColor: Color,
        branchForegroundColor: Color,
        baseFont: Font,
        tooltip: String,
    ): Component {
        background = backgroundColor
        toolTipText = tooltip
        projectIconLabel.icon = projectIcon
        projectIconLabel.toolTipText = tooltip
        projectNameLabel.text = projectName
        projectNameLabel.font = baseFont.deriveFont(if (active) Font.BOLD else Font.PLAIN)
        projectNameLabel.foreground = foregroundColor
        projectNameLabel.toolTipText = tooltip
        branchLabel.text = branch.orEmpty()
        branchLabel.isVisible = !branch.isNullOrBlank()
        branchLabel.font = baseFont
        branchLabel.foreground = branchForegroundColor
        branchLabel.toolTipText = branch
        return this
    }

    fun configureEmpty(backgroundColor: Color): Component {
        background = backgroundColor
        toolTipText = null
        projectIconLabel.icon = null
        projectNameLabel.text = ""
        branchLabel.text = ""
        branchLabel.isVisible = false
        return this
    }
}

private class DirectoryCellPanel : JBLabel(), TreeCellRenderer {
    init {
        isOpaque = true
        iconTextGap = PROJECT_ICON_TEXT_GAP
        border = JBUI.Borders.empty(2, 2, 2, 4)
    }

    fun configure(
        icon: javax.swing.Icon?,
        name: String,
        backgroundColor: Color,
        foregroundColor: Color,
        baseFont: Font,
    ): Component {
        this.icon = icon
        text = name
        background = backgroundColor
        foreground = foregroundColor
        font = baseFont
        toolTipText = null
        return this
    }

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component = this
}

private fun listCellBackground(
    list: JList<*>,
    selected: Boolean,
    hasFocus: Boolean,
    hovered: Boolean,
    active: Boolean,
): Color {
    return when {
        selected -> UIUtil.getListSelectionBackground(hasFocus)
        hovered -> HOVER_PROJECT_BACKGROUND
        active -> ACTIVE_PROJECT_BACKGROUND
        else -> list.background
    }
}

private fun treeCellBackground(
    tree: JTree,
    selected: Boolean,
    hasFocus: Boolean,
    hovered: Boolean,
    active: Boolean,
): Color {
    return when {
        selected -> UIUtil.getTreeSelectionBackground(hasFocus)
        hovered -> HOVER_PROJECT_BACKGROUND
        active -> ACTIVE_PROJECT_BACKGROUND
        else -> tree.background
    }
}

private fun SortMode.displayText(): String = when (this) {
    SortMode.ALPHABETICAL -> "Alphabetical"
    SortMode.RECENT -> "Recent"
}

private fun ViewMode.displayText(): String = when (this) {
    ViewMode.FLAT -> "Flat"
    ViewMode.TREE -> "Tree"
}

private val BRANCH_FOREGROUND = JBUI.CurrentTheme.Link.Foreground.ENABLED

private val PROJECT_ICON_TEXT_GAP = JBUI.scale(8)

private val BRANCH_ICON_TEXT_GAP = JBUI.scale(4)

private val TREE_ROW_MARKER_HORIZONTAL_INSET = JBUI.scale(12)

private val TREE_ROW_MARKER_VERTICAL_INSET = JBUI.scale(1)

private val TREE_ROW_MARKER_ARC = JBUI.scale(6)

private val ACTIVE_PROJECT_BACKGROUND = JBColor(
    Color(0xE7EAEE),
    Color(0x45494D),
)

private val HOVER_PROJECT_BACKGROUND = JBColor(
    Color(0xDCEBFF),
    Color(0x243F63),
)
