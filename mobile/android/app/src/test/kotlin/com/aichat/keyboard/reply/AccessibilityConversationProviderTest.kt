package com.aichat.keyboard.reply

import com.aichat.keyboard.capture.CapturedNode
import com.aichat.keyboard.capture.ChatWindowSnapshot
import com.aichat.keyboard.capture.SnapshotOutcome
import com.aichat.keyboard.core.network.model.MessageSender
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AccessibilityConversationProviderTest {
    private fun bubble(text: String, left: Int, right: Int, top: Int) = CapturedNode(
        text = text,
        contentDescription = null,
        boundsLeft = left,
        boundsTop = top,
        boundsRight = right,
        boundsBottom = top + 90,
        className = "android.widget.TextView",
        viewIdResourceName = null,
        isEditable = false,
        isPassword = false,
    )

    private fun snapshot(nodes: List<CapturedNode>) = ChatWindowSnapshot(
        packageName = "com.zing.zalo",
        windowLeft = 0,
        windowTop = 0,
        windowRight = 1080,
        windowBottom = 2400,
        nodes = nodes,
    )

    private fun provider(enabled: Boolean = true, source: suspend () -> SnapshotOutcome) =
        AccessibilityConversationProvider(isServiceEnabled = { enabled }, snapshotSource = source)

    @Test
    fun `a disabled service is NOT_ENABLED and is never asked for a snapshot`() = runTest {
        var asked = false
        val result = provider(enabled = false) { asked = true; SnapshotOutcome.NotRunning }.capture()

        assertEquals(CaptureResult.Unavailable(UnavailableReason.NOT_ENABLED), result)
        assertEquals(false, asked)
    }

    @Test
    fun `an enabled but not yet connected service is NOT_ENABLED`() = runTest {
        val result = provider { SnapshotOutcome.NotRunning }.capture()

        assertEquals(CaptureResult.Unavailable(UnavailableReason.NOT_ENABLED), result)
    }

    @Test
    fun `another foreground app is UNSUPPORTED_APP`() = runTest {
        val result = provider { SnapshotOutcome.UnsupportedApp }.capture()

        assertEquals(CaptureResult.Unavailable(UnavailableReason.UNSUPPORTED_APP), result)
    }

    @Test
    fun `a chat without messages is NOTHING_FOUND`() = runTest {
        val result = provider { SnapshotOutcome.Snapshot(snapshot(emptyList())) }.capture()

        assertEquals(CaptureResult.Unavailable(UnavailableReason.NOTHING_FOUND), result)
    }

    @Test
    fun `a failed snapshot is NOTHING_FOUND`() = runTest {
        val result = provider { SnapshotOutcome.Failed }.capture()

        assertEquals(CaptureResult.Unavailable(UnavailableReason.NOTHING_FOUND), result)
    }

    @Test
    fun `visible messages are Captured`() = runTest {
        val nodes = listOf(bubble("đi ăn không", 150, 700, 400), bubble("ok", 600, 1040, 520))

        val result = provider { SnapshotOutcome.Snapshot(snapshot(nodes)) }.capture()

        val captured = assertIs<CaptureResult.Captured>(result).conversation
        assertEquals("zalo", captured.platform)
        assertEquals(listOf(MessageSender.THEM, MessageSender.ME), captured.messages.map { it.sender })
    }

    @Test
    fun `an exception from the service never escapes`() = runTest {
        val result = provider { error("binder died") }.capture()

        assertEquals(CaptureResult.Unavailable(UnavailableReason.NOTHING_FOUND), result)
    }

    @Test
    fun `an exception from the enabled check never escapes`() = runTest {
        val result = AccessibilityConversationProvider(
            isServiceEnabled = { throw SecurityException("no access") },
            snapshotSource = { SnapshotOutcome.Failed },
        ).capture()

        assertEquals(CaptureResult.Unavailable(UnavailableReason.NOT_ENABLED), result)
    }

    @Test
    fun `a snapshot that hangs times out as NOTHING_FOUND`() = runTest {
        val result = provider { awaitCancellation() }.capture()

        assertEquals(CaptureResult.Unavailable(UnavailableReason.NOTHING_FOUND), result)
    }
}
