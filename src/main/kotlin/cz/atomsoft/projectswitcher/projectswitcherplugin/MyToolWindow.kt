package cz.atomsoft.projectswitcher.projectswitcherplugin

import com.intellij.icons.AllIcons
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.hover.ListHoverListener
import com.intellij.ui.hover.TreeHoverListener
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ProjectSwitcherPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

private class ProjectSwitcherPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {
    private val settings = ProjectSwitcherSettings.getInstance()
    private val projectIconProvider = ProjectIconProvider()
    private val searchField = SearchTextField(false)
    private val sortCombo = ComboBox(SortMode.entries.toTypedArray())
    private val viewCombo = ComboBox(ViewMode.entries.toTypedArray())
    private val statusLabel = JBLabel()
    private val contentPanel = JPanel(BorderLayout())
    private val currentProjectPath = project.basePath?.let { Paths.get(it).toAbsolutePath().normalize() }
    private var entries: List<ProjectEntry> = emptyList()
    private var renderedScrollPane: JBScrollPane? = null
    private var renderedTree: Tree? = null
    private var renderedViewMode: ViewMode? = null
    private var restoringTreeExpansionState = false

    init {
        border = JBUI.Borders.empty(4)
        add(createToolbar(), BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        sortCombo.selectedItem = settings.state.sortModeEnum
        viewCombo.selectedItem = settings.state.viewModeEnum

        refreshProjects()
    }

    private fun createToolbar(): JComponent {
        val panel = JPanel(BorderLayout())
        val selectors = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))

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
        sortCombo.renderer = enumRenderer()
        viewCombo.renderer = enumRenderer()
        sortCombo.addActionListener {
            saveVisibleState()
            settings.state.sortMode = (sortCombo.selectedItem as SortMode).name
            renderEntries()
        }
        viewCombo.addActionListener {
            saveVisibleState()
            settings.state.viewMode = (viewCombo.selectedItem as ViewMode).name
            renderEntries()
        }

        val refreshButton = JButton(AllIcons.Actions.Refresh)
        refreshButton.toolTipText = "Refresh"
        refreshButton.addActionListener { refreshProjects() }

        val settingsButton = JButton(AllIcons.General.Settings)
        settingsButton.toolTipText = "Settings"
        settingsButton.addActionListener {
            val changed = ShowSettingsUtil.getInstance().editConfigurable(project, ProjectSwitcherConfigurable())
            if (changed) {
                refreshProjects()
            }
        }

        selectors.add(searchField)
        selectors.add(sortCombo)
        selectors.add(viewCombo)
        actions.add(refreshButton)
        actions.add(settingsButton)
        panel.add(selectors, BorderLayout.WEST)
        panel.add(actions, BorderLayout.EAST)
        panel.border = JBUI.Borders.emptyBottom(4)
        return panel
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
        list.cellRenderer = ProjectListRenderer(projectIconProvider, currentProjectPath)
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

        val tree = Tree(DefaultTreeModel(root))
        tree.isRootVisible = false
        tree.showsRootHandles = true
        TreeHoverListener.DEFAULT.addTo(tree)
        tree.cellRenderer = ProjectTreeRenderer(projectIconProvider, currentProjectPath)
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

private class ProjectListRenderer(
    private val projectIconProvider: ProjectIconProvider,
    private val currentProjectPath: Path?,
) : ColoredListCellRenderer<ProjectEntry>() {
    override fun customizeCellRenderer(
        list: JList<out ProjectEntry>,
        value: ProjectEntry?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        if (value == null) return
        val isActiveProject = value.path == currentProjectPath
        val isHovered = index == ListHoverListener.getHoveredIndex(list)
        icon = projectIconProvider.getIcon(value)
        iconTextGap = PROJECT_ICON_TEXT_GAP
        append(value.name, if (isActiveProject) ACTIVE_PROJECT_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
        appendBranch(value.branch)
        toolTipText = value.path.toString()
        ipad = JBUI.insets(4, 6)
        when {
            selected -> isOpaque = true
            isHovered -> {
                background = HOVER_PROJECT_BACKGROUND
                isOpaque = true
            }
            isActiveProject -> {
                background = ACTIVE_PROJECT_BACKGROUND
                isOpaque = true
            }
            else -> isOpaque = false
        }
    }
}

private class ProjectTreeRenderer(
    private val projectIconProvider: ProjectIconProvider,
    private val currentProjectPath: Path?,
) : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = value as? DefaultMutableTreeNode
        val isHovered = row == TreeHoverListener.getHoveredRow(tree)
        when (val userObject = node?.userObject) {
            is ProjectEntry -> {
                val isActiveProject = userObject.path == currentProjectPath
                icon = projectIconProvider.getIcon(userObject)
                iconTextGap = PROJECT_ICON_TEXT_GAP
                append(userObject.name, if (isActiveProject) ACTIVE_PROJECT_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                appendBranch(userObject.branch)
                toolTipText = userObject.path.toString()
                when {
                    selected -> isOpaque = true
                    isHovered -> {
                        background = HOVER_PROJECT_BACKGROUND
                        isOpaque = true
                    }
                    isActiveProject -> {
                        background = ACTIVE_PROJECT_BACKGROUND
                        isOpaque = true
                    }
                    else -> isOpaque = false
                }
            }
            is DirectoryNode -> {
                icon = AllIcons.Nodes.Folder
                iconTextGap = PROJECT_ICON_TEXT_GAP
                append(userObject.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                when {
                    selected -> isOpaque = true
                    isHovered -> {
                        background = HOVER_PROJECT_BACKGROUND
                        isOpaque = true
                    }
                    else -> isOpaque = false
                }
            }
            else -> isOpaque = selected
        }
    }
}

private fun Component.appendBranch(branch: String?) {
    if (this !is com.intellij.ui.SimpleColoredComponent || branch.isNullOrBlank()) return
    append("  ")
    append(branch, BRANCH_ATTRIBUTES)
}

private fun enumRenderer(): DefaultListCellRenderer {
    return object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            text = when (value) {
                SortMode.ALPHABETICAL -> "Alphabetical"
                SortMode.RECENT -> "Recent"
                ViewMode.FLAT -> "Flat"
                ViewMode.TREE -> "Tree"
                else -> value?.toString().orEmpty()
            }
            return this
        }
    }
}

private val BRANCH_ATTRIBUTES = SimpleTextAttributes(
    SimpleTextAttributes.STYLE_BOLD,
    JBUI.CurrentTheme.Link.Foreground.ENABLED,
)

private val PROJECT_ICON_TEXT_GAP = JBUI.scale(8)

private val ACTIVE_PROJECT_ATTRIBUTES = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, null as Color?)

private val ACTIVE_PROJECT_BACKGROUND = JBColor(
    Color(0xEAF3FF),
    Color(0x24344A),
)

private val HOVER_PROJECT_BACKGROUND = JBColor(
    Color(0xDCEBFF),
    Color(0x2F4A73),
)
