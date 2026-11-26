package com.aichat.keyboard.sticker

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserStickerFilesTest {
    private lateinit var root: File
    private lateinit var files: UserStickerFiles

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("stickers").toFile()
        files = UserStickerFiles(File(root, "user"), maxStickers = 3)
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun addSticker(): String {
        val target = files.newStickerFile() ?: error("store is full")
        target.writeBytes(byteArrayOf(1, 2, 3))
        return target.nameWithoutExtension
    }

    @Test
    fun `an empty or missing directory lists nothing`() {
        assertEquals(emptyList(), files.list())
    }

    @Test
    fun `new stickers are listed newest first`() {
        val first = addSticker()
        File(files.dir, "$first.png").setLastModified(1_000)
        val second = addSticker()
        File(files.dir, "$second.png").setLastModified(2_000)

        assertEquals(listOf(second, first), files.list().map { it.nameWithoutExtension })
    }

    @Test
    fun `files that are not stickers are ignored`() {
        addSticker()
        File(files.dir, "notes.txt").writeText("x")
        File(files.dir, "not-a-uuid.png").writeBytes(byteArrayOf(1))

        assertEquals(1, files.list().size)
    }

    @Test
    fun `no new file is handed out once the store is full`() {
        repeat(3) { addSticker() }
        assertTrue(files.isFull())
        assertNull(files.newStickerFile())
    }

    @Test
    fun `delete removes only the named sticker`() {
        val keep = addSticker()
        val drop = addSticker()

        assertTrue(files.delete(drop))
        assertEquals(listOf(keep), files.list().map { it.nameWithoutExtension })
    }

    @Test
    fun `delete refuses ids that could escape the sticker directory`() {
        val outside = File(root, "secret.png").apply { writeBytes(byteArrayOf(9)) }

        listOf("../secret", "..", "", "a/b", "secret").forEach { id ->
            assertFalse(files.delete(id), id)
        }
        assertTrue(outside.exists())
    }

    @Test
    fun `fileFor only resolves valid existing ids`() {
        val id = addSticker()
        assertEquals(File(files.dir, "$id.png"), files.fileFor(id))
        assertNull(files.fileFor("../$id"))
        assertNull(files.fileFor("00000000-0000-0000-0000-000000000000"))
    }
}
