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

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectSearchKeyTest {
    @Test
    fun `normalizes separators and special characters`() {
        assertEquals("scanmode", normalizeSearchText("scan mode"))
        assertEquals("scanmode", normalizeSearchText("scan-mode"))
        assertEquals("scanmode", normalizeSearchText("scan_mode"))
    }

    @Test
    fun `normalizes diacritics and fuzzy Czech pairs`() {
        assertEquals("sinchronisace", normalizeSearchText("synchronizace"))
        assertEquals("sinchronisace", normalizeSearchText("sýnchronižace"))
    }

    @Test
    fun `creates search key from project name and branch`() {
        val entry = ProjectEntry(
            path = Paths.get("Projects", "scan-mode").toAbsolutePath().normalize(),
            scanRoot = Paths.get("Projects").toAbsolutePath().normalize(),
            name = "scan-mode",
            branch = "FW-6138_espresso-tests",
        )

        assertEquals("scanmodefw6138espressotests", createProjectSearchKey(entry))
    }
}
