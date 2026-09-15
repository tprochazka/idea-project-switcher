package cz.atomsoft.ideaplugin.projectswitcher

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectCatalogServiceTest {
    @Test
    fun `reconciles branch event that arrived during a scan`() {
        val repositoryRoot = Files.createTempDirectory("catalog-reconciliation")
        val entry = ProjectEntry(
            path = repositoryRoot.resolve("project"),
            scanRoot = repositoryRoot,
            name = "project",
            branch = "old",
            repositoryRoot = repositoryRoot,
        )

        val reconciled = reconcileScannedEntries(
            scannedEntries = listOf(entry),
            scanStartedAfterBranchEvent = 0,
            branchEvents = listOf(
                BranchUpdateEvent(1, repositoryRoot.toString(), "new"),
            ),
        )

        assertEquals("new", reconciled.single().branch)
    }

    @Test
    fun `ignores branch events that predate a scan`() {
        val repositoryRoot = Files.createTempDirectory("catalog-reconciliation-old-event")
        val entry = ProjectEntry(
            path = repositoryRoot.resolve("project"),
            scanRoot = repositoryRoot,
            name = "project",
            branch = "scan-result",
            repositoryRoot = repositoryRoot,
        )

        val reconciled = reconcileScannedEntries(
            scannedEntries = listOf(entry),
            scanStartedAfterBranchEvent = 1,
            branchEvents = listOf(
                BranchUpdateEvent(1, repositoryRoot.toString(), "old-event"),
            ),
        )

        assertEquals("scan-result", reconciled.single().branch)
    }
}
