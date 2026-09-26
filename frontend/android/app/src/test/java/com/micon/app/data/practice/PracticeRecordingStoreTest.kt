package com.micon.app.data.practice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PracticeRecordingStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun savesOnlyUnderTheOwningAccountAndDeletesOnePractice() {
        val store = PracticeRecordingStore(folder.newFolder("recordings"))
        val source = folder.newFile("take.wav").apply { writeBytes(validWav()) }

        store.save(ownerId = 11, practiceId = 49, source = source)

        assertArrayEquals(validWav(), store.find(11, 49)?.readBytes())
        assertNull(store.find(12, 49))
        assertNotNull(source.takeIf { it.exists() }) // immediate finished-screen replay remains possible
        store.delete(12, 49)
        assertNotNull(store.find(11, 49))
        store.delete(11, 49)
        assertNull(store.find(11, 49))
    }

    @Test fun rejectsInvalidTakeAndRemovesAllFilesForWithdrawnAccountOnly() {
        val store = PracticeRecordingStore(folder.newFolder("recordings"))
        val source = folder.newFile("take.wav").apply { writeBytes(validWav()) }
        store.save(11, 49, source)
        store.save(12, 49, source)
        store.deleteAll(11)
        assertNull(store.find(11, 49))
        assertNotNull(store.find(12, 49))

        val invalid = folder.newFile("invalid.wav").apply { writeBytes(ByteArray(64)) }
        val rejected = runCatching { store.save(12, 50, invalid) }.isFailure
        assertNull(store.find(12, 50))
        assertTrue(rejected)
    }

    private fun validWav() = ByteArray(48).also {
        "RIFF".toByteArray().copyInto(it, 0)
        "WAVE".toByteArray().copyInto(it, 8)
    }
}
