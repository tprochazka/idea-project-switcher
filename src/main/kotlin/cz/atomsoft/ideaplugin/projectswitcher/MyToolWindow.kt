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
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.hover.ListHoverListener
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.KeyStroke
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
        val panel = ProjectSwitcherPanel(project, toolWindow)
        toolWindow.setTitleActions(listOf(panel.createRefreshAction()))
        toolWindow.setAdditionalGearActions(panel.createOptionsActionGroup())
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

private class ProjectSwitcherPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : JPanel(BorderLayout()), Disposable {
    private val settings = ProjectSwitcherSettings.getInstance()
    private val projectCatalog = ProjectCatalogService.getInstance()
    private val projectIconProvider = ProjectIconProvider()
    private val activeProjectPathIds = resolveActiveProjectPathIds(project)
    private val searchField = SearchTextField(false)
    private val statusLabel = JBLabel()
    private val contentPanel = JPanel(BorderLayout())
    private val renderAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val renderLock = Any()
    private var catalogItems: List<ProjectCatalogItem> = emptyList()
    private var entries: List<ProjectEntry> = emptyList()
    private var recentTimestampsByPath: Map<String, Long> = emptyMap()
    private var renderedScrollPane: JBScrollPane? = null
    private var renderedList: JBList<ProjectEntry>? = null
    private var renderedTree: Tree? = null
    private var renderedViewMode: ViewMode? = null
    private var restoringTreeExpansionState = false
    private var renderRequestSequence = 0
    @Volatile
    private var latestRenderRequestId = 0
    private var activeRenderCancellation: RenderCancellation? = null
    private var renderInProgress = false
    private var pendingRenderRequest: RenderRequest? = null
    private var refreshInProgress = false
    private var selectAllSearchTextAfterMouseRelease = false
    private var disposed = false
    private val refreshAction = object : DumbAwareAction(
        "Refresh",
        "Rescan configured project folders",
        AllIcons.Actions.Refresh,
    ) {
        override fun actionPerformed(event: AnActionEvent) {
            refreshProjects()
        }

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !refreshInProgress && !disposed
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    init {
        border = JBUI.Borders.empty(4)
        add(createSearchBar(), BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        subscribeToCatalogChanges()
        projectCatalog.ensureLoaded(settings.state.rootPaths.toList())
        applyCatalogSnapshot(projectCatalog.snapshot())
    }

    override fun dispose() {
        disposed = true
        toolWindow.setTitleActions(Collections.emptyList())
        toolWindow.setAdditionalGearActions(DefaultActionGroup())
        renderAlarm.cancelAllRequests()
        synchronized(renderLock) {
            activeRenderCancellation?.cancel()
            pendingRenderRequest?.cancellation?.cancel()
            pendingRenderRequest = null
        }
        refreshInProgress = false
    }

    fun createRefreshAction(): AnAction {
        return refreshAction
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
        installSearchFieldNavigation()
        installSearchFieldFocusBehavior()
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = updateSearch()

            override fun removeUpdate(event: DocumentEvent) = updateSearch()

            override fun changedUpdate(event: DocumentEvent) = updateSearch()

            private fun updateSearch() {
                saveVisibleState()
                settings.state.searchQuery = searchField.text
                if (settings.state.searchQuery.trim().isEmpty()) {
                    refreshRecentTimestampSnapshotIfAllowed()
                }
                renderEntries(SEARCH_DEBOUNCE_MS)
            }
        })

        panel.add(searchField, BorderLayout.CENTER)
        panel.border = JBUI.Borders.emptyBottom(4)
        return panel
    }

    private fun installSearchFieldFocusBehavior() {
        val textEditor = searchField.textEditor
        textEditor.addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) {
                selectAllSearchTextLater()
            }

            override fun focusLost(event: FocusEvent) {
                selectAllSearchTextAfterMouseRelease = false
                settings.state.searchQuery = searchField.text
                ApplicationManager.getApplication().saveSettings()
            }
        })
        textEditor.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (!textEditor.hasFocus()) {
                    selectAllSearchTextAfterMouseRelease = true
                    textEditor.requestFocusInWindow()
                    event.consume()
                }
            }

            override fun mouseReleased(event: MouseEvent) {
                if (selectAllSearchTextAfterMouseRelease) {
                    selectAllSearchTextAfterMouseRelease = false
                    selectAllSearchTextLater()
                    event.consume()
                }
            }
        })
    }

    private fun selectAllSearchTextLater() {
        SwingUtilities.invokeLater {
            val textEditor = searchField.textEditor
            if (!textEditor.hasFocus()) return@invokeLater

            textEditor.select(0, textEditor.text.length)
        }
    }

    private fun installSearchFieldNavigation() {
        val actionKey = "focusProjectResults"
        searchField.textEditor.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), actionKey)
        searchField.textEditor.actionMap.put(actionKey, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                focusProjectResults()
            }
        })
    }

    private fun focusProjectResults() {
        when (settings.state.viewModeEnum) {
            ViewMode.FLAT -> {
                val list = renderedList ?: return
                if (list.model.size == 0) return
                if (list.selectedIndex < 0) {
                    list.selectedIndex = 0
                    list.ensureIndexIsVisible(0)
                }
                list.requestFocusInWindow()
            }
            ViewMode.TREE -> {
                val tree = renderedTree ?: return
                if (tree.rowCount == 0) return
                if (tree.selectionCount == 0) {
                    tree.setSelectionRow(0)
                    tree.scrollRowToVisible(0)
                }
                tree.requestFocusInWindow()
            }
        }
    }

    private fun setSortMode(mode: SortMode) {
        if (settings.state.sortModeEnum == mode) return

        saveVisibleState()
        settings.state.sortMode = mode.name
        refreshRecentTimestampSnapshotIfAllowed()
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
        if (refreshInProgress) return

        projectCatalog.refresh(settings.state.rootPaths.toList(), force = true)
    }

    private fun setRefreshInProgress(inProgress: Boolean) {
        refreshInProgress = inProgress
        refreshAction.templatePresentation.isEnabled = !inProgress && !disposed
    }

    private fun showNoConfiguredRoots() {
        renderAlarm.cancelAllRequests()
        synchronized(renderLock) {
            activeRenderCancellation?.cancel()
            pendingRenderRequest?.cancellation?.cancel()
            pendingRenderRequest = null
        }
        saveVisibleState()
        contentPanel.removeAll()
        renderedScrollPane = null
        renderedList = null
        renderedTree = null
        renderedViewMode = null
        statusLabel.text = ""
        contentPanel.add(createNoConfiguredRootsPanel(), BorderLayout.CENTER)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun createNoConfiguredRootsPanel(): JComponent {
        val panel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(24, 12)
        }
        val constraints = GridBagConstraints().apply {
            gridx = 0
            weightx = 1.0
            anchor = GridBagConstraints.CENTER
            fill = GridBagConstraints.HORIZONTAL
        }

        panel.addCentered(JBLabel("No project folders configured").apply {
            horizontalAlignment = JLabel.CENTER
            font = font.deriveFont(Font.BOLD)
        }, constraints, bottom = 22)
        panel.addCentered(JBLabel("Add folders to scan for IDE, Gradle, and Maven projects.").apply {
            horizontalAlignment = JLabel.CENTER
            foreground = UIUtil.getContextHelpForeground()
        }, constraints, bottom = 12)
        panel.addCentered(JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(JBLabel(AllIcons.General.Settings))
            add(ActionLink("Configure Folders...") {
                openSettings()
            })
        }, constraints, bottom = 22)
        panel.addCentered(JBLabel("Also available from the tool window overflow menu.").apply {
            horizontalAlignment = JLabel.CENTER
            foreground = UIUtil.getContextHelpForeground()
            font = font.deriveFont(font.size2D - 1.0f)
        }, constraints, bottom = 0)

        return panel
    }

    private fun JPanel.addCentered(component: JComponent, constraints: GridBagConstraints, bottom: Int) {
        constraints.gridy += 1
        constraints.insets = Insets(0, 0, JBUI.scale(bottom), 0)
        add(component, constraints)
    }

    private fun subscribeToCatalogChanges() {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            ProjectCatalogListener.TOPIC,
            ProjectCatalogListener { snapshot ->
                SwingUtilities.invokeLater {
                    if (project.isDisposed || disposed) return@invokeLater
                    applyCatalogSnapshot(snapshot)
                }
            },
        )
    }

    private fun applyCatalogSnapshot(snapshot: ProjectCatalogSnapshot) {
        catalogItems = snapshot.items
        entries = catalogItems.map { it.entry }
        refreshRecentTimestampSnapshotIfAllowed()

        when (snapshot.state) {
            ProjectCatalogState.NO_ROOTS -> {
                recentTimestampsByPath = emptyMap()
                setRefreshInProgress(false)
                showNoConfiguredRoots()
            }
            ProjectCatalogState.SCANNING -> {
                setRefreshInProgress(true)
                statusLabel.text = "Scanning..."
                renderEntries()
            }
            ProjectCatalogState.READY -> {
                setRefreshInProgress(false)
                statusLabel.text = "${entries.size} project(s)"
                renderEntries()
            }
            ProjectCatalogState.FAILED -> {
                setRefreshInProgress(false)
                statusLabel.text = "Scan failed"
                renderEntries()
            }
        }
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
        "Configure Folders...",
        "Configure scanned folders",
        AllIcons.General.Settings,
    ) {
        override fun actionPerformed(event: AnActionEvent) {
            openSettings()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private fun renderEntries(delayMillis: Int = 0) {
        saveVisibleState()
        val request = createRenderRequest()
        latestRenderRequestId = request.id
        synchronized(renderLock) {
            activeRenderCancellation?.cancel()
            pendingRenderRequest?.cancellation?.cancel()
            pendingRenderRequest = null
        }
        renderAlarm.cancelAllRequests()
        renderAlarm.addRequest({ runRenderRequest(request) }, delayMillis)
    }

    private fun createRenderRequest(): RenderRequest {
        val id = ++renderRequestSequence
        val searchText = searchField.text
        return RenderRequest(
            id = id,
            items = catalogItems,
            query = normalizeSearchText(searchText),
            hasQuery = searchText.trim().isNotEmpty(),
            sortMode = settings.state.sortModeEnum,
            viewMode = settings.state.viewModeEnum,
            recentTimestampsByPath = recentTimestampsByPath,
            cancellation = RenderCancellation(),
        )
    }

    private fun runRenderRequest(request: RenderRequest) {
        synchronized(renderLock) {
            if (renderInProgress) {
                pendingRenderRequest?.cancellation?.cancel()
                pendingRenderRequest = request
                return
            }
            renderInProgress = true
            activeRenderCancellation = request.cancellation
        }

        try {
            request.cancellation.throwIfCancelled()
            val filtered = filteredEntries(request.items, request.query, request.cancellation)
            request.cancellation.throwIfCancelled()
            val projects = filtered.sortedWith(
                cancellableComparator(
                    projectComparator(request.sortMode, request.recentTimestampsByPath),
                    request.cancellation,
                ),
            )
            request.cancellation.throwIfCancelled()
            val activeProjectEntryPathIds = resolveActiveProjectEntryPathIds(projects, activeProjectPathIds)

            val result = RenderResult(
                id = request.id,
                hasQuery = request.hasQuery,
                sortMode = request.sortMode,
                viewMode = request.viewMode,
                recentTimestampsByPath = request.recentTimestampsByPath,
                projects = projects,
                activeProjectPathIds = activeProjectEntryPathIds,
            )

            SwingUtilities.invokeLater {
                if (request.cancellation.isCancelled) return@invokeLater
                applyRenderResult(result)
            }
        } catch (_: CancellationException) {
            // A newer search request superseded this one.
        } finally {
            val pendingRequest = synchronized(renderLock) {
                renderInProgress = false
                if (activeRenderCancellation === request.cancellation) {
                    activeRenderCancellation = null
                }
                val pending = pendingRenderRequest
                pendingRenderRequest = null
                pending
            }

            if (pendingRequest != null) {
                runRenderRequest(pendingRequest)
            }
        }
    }

    private fun applyRenderResult(result: RenderResult) {
        if (project.isDisposed || disposed || result.id != latestRenderRequestId) return

        contentPanel.removeAll()
        renderedScrollPane = null
        renderedList = null
        renderedTree = null
        renderedViewMode = result.viewMode
        val component = when (result.viewMode) {
            ViewMode.FLAT -> createFlatList(
                projects = result.projects,
                resetScroll = result.hasQuery,
                activeProjectPathIds = result.activeProjectPathIds,
            )
            ViewMode.TREE -> createTree(
                projects = result.projects,
                sortMode = result.sortMode,
                recentTimestampsByPath = result.recentTimestampsByPath,
                expandSearchMatches = result.hasQuery,
                activeProjectPathIds = result.activeProjectPathIds,
            )
        }
        contentPanel.add(component, BorderLayout.CENTER)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun projectComparator(
        sortMode: SortMode,
        recentTimestampsByPath: Map<String, Long>,
    ): Comparator<ProjectEntry> {
        return when (sortMode) {
            SortMode.ALPHABETICAL -> Comparator { left, right -> compareProjectsByNameAndPath(left, right) }
            SortMode.RECENT -> Comparator { left, right ->
                val byTimestamp = recentTimestamp(right, recentTimestampsByPath)
                    .compareTo(recentTimestamp(left, recentTimestampsByPath))
                if (byTimestamp != 0) byTimestamp else compareProjectsByNameAndPath(left, right)
            }
        }
    }

    private fun filteredEntries(
        items: List<ProjectCatalogItem>,
        query: String,
        cancellation: RenderCancellation,
    ): List<ProjectEntry> {
        if (query.isEmpty()) return items.map { it.entry }

        return items.mapIndexedNotNull { index, item ->
            if (index % CANCELLATION_CHECK_INTERVAL == 0) cancellation.throwIfCancelled()
            item.entry.takeIf { item.searchKey.contains(query) }
        }
    }

    private fun cancellableComparator(
        comparator: Comparator<ProjectEntry>,
        cancellation: RenderCancellation,
    ): Comparator<ProjectEntry> {
        var comparisons = 0
        return Comparator { left, right ->
            if (++comparisons % CANCELLATION_CHECK_INTERVAL == 0) cancellation.throwIfCancelled()
            comparator.compare(left, right)
        }
    }

    private fun createRecentTimestampSnapshot(entries: List<ProjectEntry>): Map<String, Long> {
        if (entries.isEmpty()) return emptyMap()

        val recent = RecentProjectsManagerBase.getInstanceEx()
        return entries.associate { entry ->
            entry.path.toString() to recentTimestamp(entry, recent)
        }
    }

    private fun recentTimestamp(entry: ProjectEntry, recent: RecentProjectsManagerBase): Long {
        return recentPathCandidates(entry).firstNotNullOfOrNull { path ->
            recent.getActivationTimestamp(path)
        } ?: 0L
    }

    private fun recentPathCandidates(entry: ProjectEntry): List<String> {
        val path = entry.path.toAbsolutePath().normalize().toString()
        val systemIndependentPath = FileUtil.toSystemIndependentName(path)
        return listOf(path, systemIndependentPath).distinct()
    }

    private fun recentTimestamp(entry: ProjectEntry, recentTimestampsByPath: Map<String, Long>): Long {
        return recentTimestampsByPath[entry.path.toString()] ?: 0L
    }

    private fun refreshRecentTimestampSnapshotIfAllowed() {
        if (settings.state.sortModeEnum != SortMode.RECENT) {
            recentTimestampsByPath = emptyMap()
            return
        }

        recentTimestampsByPath = createRecentTimestampSnapshot(entries)
    }

    private fun compareProjectsByNameAndPath(left: ProjectEntry, right: ProjectEntry): Int {
        val byName = String.CASE_INSENSITIVE_ORDER.compare(left.name, right.name)
        if (byName != 0) return byName
        return String.CASE_INSENSITIVE_ORDER.compare(left.path.toString(), right.path.toString())
    }

    private fun createFlatList(
        projects: List<ProjectEntry>,
        resetScroll: Boolean,
        activeProjectPathIds: Set<String>,
    ): JComponent {
        val model = DefaultListModel<ProjectEntry>()
        projects.forEach(model::addElement)
        val list = JBList(model)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.emptyText.text = "No projects found"
        ListHoverListener.DEFAULT.addTo(list)
        list.cellRenderer = ProjectListRenderer(projectIconProvider, activeProjectPathIds)
        list.addMouseListener(ProjectMouseListener { event ->
            val index = list.locationToIndex(event.point)
            if (index < 0 || list.getCellBounds(index, index)?.contains(event.point) != true) {
                null
            } else {
                model.getElementAt(index)
            }
        })
        val scrollPane = createProjectScrollPane(list)
        registerScrollPersistence(scrollPane, ViewMode.FLAT)
        if (resetScroll) {
            restoreScrollPosition(scrollPane, 0, 0)
        } else {
            restoreScrollPosition(scrollPane, settings.state.flatScrollX, settings.state.flatScrollY)
        }
        renderedScrollPane = scrollPane
        renderedList = list
        return scrollPane
    }

    private fun createTree(
        projects: List<ProjectEntry>,
        sortMode: SortMode,
        recentTimestampsByPath: Map<String, Long>,
        expandSearchMatches: Boolean,
        activeProjectPathIds: Set<String>,
    ): JComponent {
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
        sortTreeChildren(root, projectComparator(sortMode, recentTimestampsByPath))

        val tree = ProjectTree(DefaultTreeModel(root), activeProjectPathIds)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.rowHeight = 0
        tree.cellRenderer = ProjectTreeRenderer(projectIconProvider, activeProjectPathIds)
        tree.addMouseListener(ProjectMouseListener { event ->
            val path = tree.getPathForLocation(event.x, event.y) ?: return@ProjectMouseListener null
            if (tree.getPathBounds(path)?.contains(event.x, event.y) != true) {
                return@ProjectMouseListener null
            }
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
        if (expandSearchMatches) {
            expandAllDirectories(tree)
        } else {
            restoreTreeExpansionState(tree)
        }
        val scrollPane = createProjectScrollPane(tree)
        registerScrollPersistence(scrollPane, ViewMode.TREE)
        if (expandSearchMatches) {
            restoreScrollPosition(scrollPane, 0, 0)
        } else {
            restoreScrollPosition(scrollPane, settings.state.treeScrollX, settings.state.treeScrollY)
        }
        renderedTree = tree
        renderedList = null
        renderedScrollPane = scrollPane
        return scrollPane
    }

    private fun createProjectScrollPane(view: Component): JBScrollPane {
        return JBScrollPane(view).apply {
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()
        }
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

    private fun expandAllDirectories(tree: Tree) {
        restoringTreeExpansionState = true
        try {
            val root = tree.model.root as DefaultMutableTreeNode
            expandAllDirectoryNodes(tree, root)
        } finally {
            restoringTreeExpansionState = false
        }
    }

    private fun expandAllDirectoryNodes(tree: Tree, node: DefaultMutableTreeNode) {
        for (i in 0 until node.childCount) {
            expandAllDirectoryNodes(tree, node.getChildAt(i) as DefaultMutableTreeNode)
        }

        if (node.userObject is DirectoryNode) {
            tree.expandPath(TreePath(node.path))
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

private data class RenderRequest(
    val id: Int,
    val items: List<ProjectCatalogItem>,
    val query: String,
    val hasQuery: Boolean,
    val sortMode: SortMode,
    val viewMode: ViewMode,
    val recentTimestampsByPath: Map<String, Long>,
    val cancellation: RenderCancellation,
)

private data class RenderResult(
    val id: Int,
    val hasQuery: Boolean,
    val sortMode: SortMode,
    val viewMode: ViewMode,
    val recentTimestampsByPath: Map<String, Long>,
    val projects: List<ProjectEntry>,
    val activeProjectPathIds: Set<String>,
)

private class RenderCancellation {
    private val cancelled = AtomicBoolean(false)

    val isCancelled: Boolean
        get() = cancelled.get()

    fun cancel() {
        cancelled.set(true)
    }

    fun throwIfCancelled() {
        if (cancelled.get()) throw CancellationException()
    }
}

private fun resolveActiveProjectPathIds(project: Project): Set<String> {
    val paths = linkedSetOf<Path>()
    project.basePath?.let { addProjectPathVariants(Paths.get(it), paths) }
    project.projectFilePath?.let { addProjectPathVariants(Paths.get(it), paths) }

    return paths
        .flatMapTo(linkedSetOf()) { resolvePathIdentityIds(it) }
}

private fun addProjectPathVariants(path: Path, paths: MutableSet<Path>) {
    val normalized = path.toAbsolutePath().normalize()
    paths.add(normalized)

    val fileName = normalized.fileName?.toString() ?: return
    if (fileName.equals(".idea", ignoreCase = true) || fileName.endsWith(".ipr", ignoreCase = true)) {
        normalized.parent?.let(paths::add)
    }
}

private fun resolveActiveProjectEntryPathIds(
    entries: List<ProjectEntry>,
    activeProjectPathIds: Set<String>,
): Set<String> {
    if (activeProjectPathIds.isEmpty()) return emptySet()
    return entries.asSequence()
        .filter { entry -> resolvePathIdentityIds(entry.path).any(activeProjectPathIds::contains) }
        .map { entry -> ProjectPathUtils.key(entry.path) }
        .toSet()
}

private fun isActiveProjectPath(projectPath: Path, activeProjectPathIds: Set<String>): Boolean {
    return ProjectPathUtils.key(projectPath) in activeProjectPathIds
}

private fun resolvePathIdentityIds(path: Path): Set<String> {
    val normalized = ProjectPathUtils.normalize(path)
    return linkedSetOf<String>().apply {
        add(ProjectPathUtils.key(normalized))
        runCatching { normalized.toRealPath().normalize() }
            .getOrNull()
            ?.let { add(ProjectPathUtils.key(it)) }
    }
}

private class ProjectTree(
    model: DefaultTreeModel,
    private val activeProjectPathIds: Set<String>,
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
    private val activeProjectPathIds: Set<String>,
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
        val isActiveProject = isActiveProjectPath(value.path, activeProjectPathIds)
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
    private val activeProjectPathIds: Set<String>,
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
                val isActiveProject = isActiveProjectPath(userObject.path, activeProjectPathIds)
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

private const val SEARCH_DEBOUNCE_MS = 100

private const val CANCELLATION_CHECK_INTERVAL = 50

private val ACTIVE_PROJECT_BACKGROUND = JBColor(
    Color(0xE7EAEE),
    Color(0x45494D),
)

private val HOVER_PROJECT_BACKGROUND = JBColor(
    Color(0xDCEBFF),
    Color(0x243F63),
)
