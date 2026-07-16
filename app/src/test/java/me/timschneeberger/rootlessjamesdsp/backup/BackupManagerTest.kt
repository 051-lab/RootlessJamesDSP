package me.timschneeberger.rootlessjamesdsp.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManagerTest {
    @Test
    fun cleanRestoreRequiresBackupMarkerAndPayload() {
        assertFalse(BackupManager.isValidBackup(emptyMap(), true))
        assertFalse(BackupManager.isValidBackup(mapOf(BackupManager.META_IS_BACKUP to "true"), false))
        assertTrue(BackupManager.isValidBackup(mapOf(BackupManager.META_IS_BACKUP to "true"), true))
    }
}
