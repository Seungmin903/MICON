package com.micon.app.ui.settings

import androidx.lifecycle.ViewModelStore
import com.micon.app.data.settings.*
import com.micon.app.data.local.UserStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsVoiceDeletionTest {
    private val dispatcher=StandardTestDispatcher()
    private val models=ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.clear(); UserStore.clearVoiceData(); Dispatchers.resetMain() }
    private class Settings : SettingsRepository {
        var fail=false
        override suspend fun settings()=UserSettings(true)
        override suspend fun updateSaveOriginalRecording(enabled:Boolean)=UserSettings(enabled)
        override suspend fun deleteVoiceData():VoiceDataDeletionResult {
            if (fail) throw java.io.IOException("offline")
            return VoiceDataDeletionResult(1,"COMPLETED",1,1)
        }
    }
    @Test fun successfulServerDeletionClearsAccountFilesAndDisplayedProfile()=runTest(dispatcher) {
        var cleared=0
        UserStore.setProfile(UserStore.VoiceProfile(measuredAt=1))
        val revision=UserStore.voiceDataRevision.value
        val vm=SettingsViewModel(Settings()) { cleared++; UserStore.clearVoiceData() }
        models.put("test",vm); runCurrent(); vm.deleteVoiceData(); runCurrent()
        assertEquals(1,cleared); assertNull(UserStore.profile.value)
        assertTrue(UserStore.voiceDataRevision.value>revision)
        assertFalse(vm.state.value.deleting)
    }
    @Test fun failedServerDeletionDoesNotClearAccountFiles()=runTest(dispatcher) {
        var cleared=0
        val vm=SettingsViewModel(Settings().apply { fail=true }) { cleared++ }
        models.put("test",vm); runCurrent(); vm.deleteVoiceData(); runCurrent()
        assertEquals(0,cleared)
    }
}
