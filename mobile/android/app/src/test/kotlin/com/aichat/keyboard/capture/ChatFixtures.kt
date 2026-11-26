package com.aichat.keyboard.capture

/**
 * Synthetic node lists shaped like Messenger and Zalo chat screens (1080x2400 window). Names and messages are made
 * up; the layout follows what both apps show: a toolbar with the contact name, left (them) and right (me) bubbles,
 * centered date separators, small status lines and an input field at the bottom.
 */
internal object ChatFixtures {
    const val WIDTH = 1080
    const val HEIGHT = 2400

    fun node(
        text: String? = null,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        desc: String? = null,
        className: String? = "android.widget.TextView",
        viewId: String? = null,
        editable: Boolean = false,
        password: Boolean = false,
    ) = CapturedNode(
        text = text,
        contentDescription = desc,
        boundsLeft = left,
        boundsTop = top,
        boundsRight = right,
        boundsBottom = bottom,
        className = className,
        viewIdResourceName = viewId,
        isEditable = editable,
        isPassword = password,
    )

    fun them(text: String, top: Int, height: Int = 90) = node(text, left = 150, top = top, right = 700, bottom = top + height)

    fun me(text: String, top: Int, height: Int = 90) = node(text, left = 560, top = top, right = 1040, bottom = top + height)

    fun snapshot(packageName: String, nodes: List<CapturedNode>) = ChatWindowSnapshot(
        packageName = packageName,
        windowLeft = 0,
        windowTop = 0,
        windowRight = WIDTH,
        windowBottom = HEIGHT,
        nodes = nodes,
    )

    val messengerHeader = listOf(
        node(left = 0, top = 0, right = WIDTH, bottom = HEIGHT, className = "android.widget.FrameLayout"),
        node(left = 20, top = 100, right = 140, bottom = 220, desc = "Back", className = "android.widget.ImageButton"),
        node("Minh Anh", left = 180, top = 105, right = 520, bottom = 165),
        node("Active now", left = 180, top = 165, right = 420, bottom = 210),
        node(left = 820, top = 100, right = 940, bottom = 220, desc = "Audio call", className = "android.widget.ImageButton"),
    )

    val messengerComposer = listOf(
        node(left = 20, top = 2220, right = 120, bottom = 2320, desc = "Camera", className = "android.widget.ImageView"),
        node("Aa", left = 200, top = 2210, right = 900, bottom = 2330, className = "android.widget.EditText", editable = true),
        node(left = 940, top = 2220, right = 1060, bottom = 2320, desc = "Like", className = "android.widget.Button"),
    )

    val zaloHeader = listOf(
        node(left = 20, top = 90, right = 130, bottom = 210, desc = "Quay lại", className = "android.widget.ImageView"),
        node("Bạn Test", left = 160, top = 95, right = 560, bottom = 155, viewId = "com.zing.zalo:id/action_bar_title"),
        node("Vừa mới truy cập", left = 160, top = 155, right = 560, bottom = 200),
        node(left = 860, top = 90, right = 960, bottom = 210, desc = "Gọi thoại", className = "android.widget.ImageView"),
    )

    val zaloComposer = listOf(
        node("Tin nhắn", left = 130, top = 2230, right = 860, bottom = 2320, className = "android.widget.EditText", editable = true),
        node(left = 960, top = 2230, right = 1060, bottom = 2320, desc = "Gửi ảnh", className = "android.widget.ImageView"),
    )
}
