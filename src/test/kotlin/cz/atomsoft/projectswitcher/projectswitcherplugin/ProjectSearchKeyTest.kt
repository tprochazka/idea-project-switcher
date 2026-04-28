package cz.atomsoft.projectswitcher.projectswitcherplugin

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
            path = Paths.get("C:/Projects/scan-mode").toAbsolutePath().normalize(),
            scanRoot = Paths.get("C:/Projects").toAbsolutePath().normalize(),
            name = "scan-mode",
            branch = "FW-6138_espresso-tests",
        )

        assertEquals("scanmodefw6138espressotests", createProjectSearchKey(entry))
    }
}
