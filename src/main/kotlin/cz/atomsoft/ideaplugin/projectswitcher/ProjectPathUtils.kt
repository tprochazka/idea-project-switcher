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

import java.io.File
import java.nio.file.Path
import java.util.Locale

internal object ProjectPathUtils {
    private val caseInsensitiveFileSystem = File.separatorChar == '\\'

    fun normalize(path: Path): Path = path.toAbsolutePath().normalize()

    fun realPathOrNormalized(path: Path): Path {
        val normalized = normalize(path)
        return runCatching { normalized.toRealPath().normalize() }.getOrElse { normalized }
    }

    fun key(path: Path): String = pathKey(normalize(path))

    fun samePath(first: Path, second: Path): Boolean {
        val firstIds = identities(first)
        val secondIds = identities(second)
        return firstIds.any(secondIds::contains)
    }

    fun isSameOrUnder(path: Path, possibleParent: Path): Boolean {
        val pathIds = identities(path)
        val parentIds = identities(possibleParent)
        return pathIds.any { pathId ->
            parentIds.any { parentId -> isSameOrUnderKey(pathId, parentId) }
        }
    }

    private fun identities(path: Path): Set<String> {
        val normalized = normalize(path)
        return linkedSetOf<String>().apply {
            add(pathKey(normalized))
            runCatching { normalized.toRealPath().normalize() }
                .getOrNull()
                ?.let { add(pathKey(it)) }
        }
    }

    private fun isSameOrUnderKey(path: String, possibleParent: String): Boolean {
        if (path == possibleParent) return true
        if (possibleParent.endsWith('\\') || possibleParent.endsWith('/')) {
            return path.startsWith(possibleParent)
        }

        val separator = if (caseInsensitiveFileSystem) '\\' else '/'
        return path.startsWith("$possibleParent$separator")
    }

    private fun pathKey(path: Path): String {
        val root = path.root?.toString().orEmpty()
        var value = path.toString()
        while (value.length > root.length && value.lastOrNull()?.let { it == '\\' || it == '/' } == true) {
            value = value.dropLast(1)
        }
        return if (caseInsensitiveFileSystem) value.lowercase(Locale.ROOT) else value
    }
}
