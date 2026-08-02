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

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel

class ProjectSwitcherConfigurable : SearchableConfigurable {
    private val settings = ProjectSwitcherSettings.getInstance()
    private val rootsModel = DefaultListModel<String>()
    private var panel: JPanel? = null

    override fun getId(): String = "cz.atomsoft.ideaplugin.projectswitcher.settings"

    override fun getDisplayName(): String = "Project Switcher"

    override fun createComponent(): JComponent {
        if (panel == null) {
            val rootsList = JBList(rootsModel)
            val decorator = ToolbarDecorator.createDecorator(rootsList)
                .setAddAction {
                    val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    FileChooser.chooseFiles(descriptor, null, null).forEach { file ->
                        val path = file.toNioPath().toAbsolutePath().normalize().toString()
                        if (!containsPath(path)) rootsModel.addElement(path)
                    }
                }
                .setRemoveAction {
                    rootsList.selectedValuesList.forEach { rootsModel.removeElement(it) }
                }

            panel = JPanel(BorderLayout()).apply {
                add(decorator.createPanel(), BorderLayout.CENTER)
            }
            reset()
        }
        return panel!!
    }

    override fun isModified(): Boolean {
        return modelPaths() != settings.state.rootPaths
    }

    override fun apply() {
        settings.state.rootPaths = modelPaths().toMutableList()
        ProjectCatalogService.getInstance().refresh(settings.state.rootPaths, force = true)
    }

    override fun reset() {
        rootsModel.clear()
        settings.state.rootPaths.forEach(rootsModel::addElement)
    }

    private fun containsPath(path: String): Boolean {
        return (0 until rootsModel.size()).any { rootsModel.getElementAt(it) == path }
    }

    private fun modelPaths(): List<String> {
        return (0 until rootsModel.size()).map(rootsModel::getElementAt)
    }
}
