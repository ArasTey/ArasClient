package com.aras.client.handler

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DeltaPatchTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("delta/$name")?.use { it.readBytes() }
            ?: error("missing test resource $name")

    private fun tempFile(name: String, bytes: ByteArray): File =
        File(temp.root, name).apply { writeBytes(bytes) }

    @Test
    fun applyRebuildsTheTargetFile() = runBlocking {
        val oldFile = tempFile("old.bin", resource("old.bin"))
        val patchFile = tempFile("update.patch", resource("update.patch"))
        val outputFile = File(temp.root, "new.bin")

        var reportedTotal = -1L
        DeltaPatch.apply(oldFile, patchFile, outputFile) { _, total -> reportedTotal = total }

        assertArrayEquals(resource("new.bin"), outputFile.readBytes())
        assertEquals(resource("new.bin").size.toLong(), reportedTotal)
    }

    @Test
    fun rejectsPatchThatTargetsAnotherApk() = runBlocking {
        val installed = resource("old.bin")
        val oldFile = tempFile("old.bin", installed + byteArrayOf(0))
        val patchFile = tempFile("update.patch", resource("update.patch"))
        val outputFile = File(temp.root, "new.bin")

        try {
            DeltaPatch.apply(oldFile, patchFile, outputFile)
            fail("expected IOException")
        } catch (expected: IOException) {
        }
        assertFalse(outputFile.exists())
    }

    @Test
    fun rejectsCorruptPatch() = runBlocking {
        val patch = resource("update.patch")
        val oldFile = tempFile("old.bin", resource("old.bin"))
        val patchFile = tempFile("update.patch", patch.copyOf(patch.size - 1))
        val outputFile = File(temp.root, "new.bin")

        try {
            DeltaPatch.apply(oldFile, patchFile, outputFile)
            fail("expected IOException")
        } catch (expected: IOException) {
        }
        assertFalse(outputFile.exists())
    }
}
