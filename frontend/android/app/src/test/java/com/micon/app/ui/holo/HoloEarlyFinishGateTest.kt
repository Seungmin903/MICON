package com.micon.app.ui.holo

import com.micon.app.audio.VocalQuality
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HoloEarlyFinishGateTest {
    @Test fun songCannotFinishWithOnlyThreeUsableWindows() {
        val issue = HoloEarlyFinishGate.issue(localUsable = true, songSelected = true,
            quality = VocalQuality(usableWindows = 3, checkedWindows = 4))
        assertTrue(issue?.contains("3/4") == true)
    }

    @Test fun songCanFinishWithLocalResultAndFourUsableWindows() {
        assertNull(HoloEarlyFinishGate.issue(localUsable = true, songSelected = true,
            quality = VocalQuality(usableWindows = 4, checkedWindows = 4)))
    }

    @Test fun localResultMustBeUsableEvenWhenAudioWindowsPass() {
        assertTrue(HoloEarlyFinishGate.issue(localUsable = false, songSelected = true,
            quality = VocalQuality(usableWindows = 4, checkedWindows = 4))?.contains("조금 더") == true)
    }

    @Test fun quickRangePreviewDoesNotRequireTimbreWindows() {
        assertNull(HoloEarlyFinishGate.issue(localUsable = true, songSelected = false, quality = null))
    }

    @Test fun unreadableLiveRecordingCannotFinish() {
        assertTrue(HoloEarlyFinishGate.issue(localUsable = true, songSelected = true,
            quality = null)?.contains("확인하고 있어요") == true)
    }
}
