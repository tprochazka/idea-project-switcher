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

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.lang.reflect.Modifier
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

class ProjectIconProvider {
    private val iconCache = ConcurrentHashMap<String, Icon>()

    fun getIcon(entry: ProjectEntry): Icon {
        return iconCache.computeIfAbsent(entry.path.toString()) {
            loadPlatformProjectIcon(entry.path, entry.name) ?: MonogramProjectIcon(entry.name)
        }
    }

    private fun loadPlatformProjectIcon(path: Path, projectName: String): Icon? {
        return loadUsingRecentProjectIconHelper(path, projectName)
            ?: loadUsingRecentProjectsManager(path, projectName)
    }

    private fun loadUsingRecentProjectIconHelper(path: Path, projectName: String): Icon? {
        val clazz = runCatching { Class.forName("com.intellij.ide.RecentProjectIconHelper") }.getOrNull() ?: return null
        val target = clazz.kotlin.objectInstance ?: clazz.fields.firstOrNull { it.name == "INSTANCE" }?.get(null)
        val pathText = path.toString()
        val iconSize = JBUI.scale(18)

        clazz.methods.firstOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.name == "generateProjectIcon" &&
                method.parameterCount == 4
        }?.let { method ->
            val icon = runCatching { method.invoke(null, pathText, true, iconSize, projectName) }.getOrNull()
            if (icon is Icon) return icon
        }

        val candidates = listOf(
            arrayOf<Any>(pathText, true, iconSize, projectName),
            arrayOf<Any>(pathText, true),
            arrayOf<Any>(pathText),
            arrayOf<Any>(path, true),
            arrayOf<Any>(path),
        )

        clazz.methods.forEach { method ->
            if (method.name != "getProjectIcon") return@forEach
            val instance = when {
                Modifier.isStatic(method.modifiers) -> null
                target != null -> target
                else -> return@forEach
            }
            candidates.forEach { arguments ->
                val result = runCatching {
                    if (method.parameterCount == arguments.size) method.invoke(instance, *arguments) else null
                }.getOrNull()
                if (result is Icon) return result
            }
        }

        return null
    }

    private fun loadUsingRecentProjectsManager(path: Path, projectName: String): Icon? {
        val clazz = runCatching { Class.forName("com.intellij.ide.RecentProjectsManagerBase") }.getOrNull() ?: return null
        val instance = clazz.methods.firstOrNull {
            it.name == "getInstanceEx" && it.parameterCount == 0 && Modifier.isStatic(it.modifiers)
        }?.invoke(null) ?: return null

        val pathText = path.toString()
        clazz.methods.forEach { method ->
            if (method.name != "getProjectIcon") return@forEach
            val result = runCatching {
                when (method.parameterCount) {
                    4 -> method.invoke(instance, pathText, true, JBUI.scale(18), projectName)
                    1 -> method.invoke(instance, pathText)
                    2 -> method.invoke(instance, pathText, true)
                    else -> null
                }
            }.getOrNull()
            if (result is Icon) return result
        }

        return null
    }
}

private class MonogramProjectIcon(
    projectName: String,
) : Icon {
    private val size = JBUI.scale(18)
    private val arc = JBUI.scale(6).toFloat()
    private val background = palette[projectName.lowercase().hashCode().mod(palette.size)]
    private val initial = projectName.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

    override fun paintIcon(component: java.awt.Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = background
        g.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), size.toFloat(), size.toFloat(), arc, arc))

        g.color = Color.WHITE
        g.font = g.font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
        val metrics = g.fontMetrics
        val textX = x + (size - metrics.stringWidth(initial)) / 2
        val textY = y + (size - metrics.height) / 2 + metrics.ascent
        g.drawString(initial, textX, textY)
        g.dispose()
    }

    override fun getIconWidth(): Int = size

    override fun getIconHeight(): Int = size

    companion object {
        private val palette = listOf(
            Color(0x5B8DEF),
            Color(0x44BBA4),
            Color(0xF59E0B),
            Color(0xE56B6F),
            Color(0x8B5CF6),
            Color(0x14B8A6),
            Color(0xF97316),
            Color(0x3B82F6),
        )
    }
}
