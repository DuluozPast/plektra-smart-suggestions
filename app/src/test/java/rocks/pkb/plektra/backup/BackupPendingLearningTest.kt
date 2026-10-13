package rocks.pkb.plektra.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupPendingLearningTest {
    @Test
    fun backupDrainsLearningUnderTheGateAndReportsDrainFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previousPreparation = LearningOperationGate.prepareForRestore
        var preparationCalled = false
        try {
            LearningOperationGate.prepareForRestore = {
                preparationCalled = true
                assertTrue(LearningOperationGate.isLearningSuspended)
                throw IllegalStateException("pending learning could not be persisted")
            }

            val result = BackupManager.createBackup(context, Uri.parse("content://test/backup"))

            assertTrue(preparationCalled)
            assertEquals(
                BackupResult.Failure("pending learning could not be persisted"),
                result
            )
            assertFalse(LearningOperationGate.isLearningSuspended)
        } finally {
            LearningOperationGate.prepareForRestore = previousPreparation
        }
    }
}
