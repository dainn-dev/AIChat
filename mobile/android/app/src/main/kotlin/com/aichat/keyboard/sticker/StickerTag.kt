package com.aichat.keyboard.sticker

/**
 * The fixed sticker tag vocabulary. Shared contract: stickers are tagged with these ([Sticker.tags]), typing
 * suggestions map keywords onto them, and the backend's `/v1/keyboard/reply` may return them as `sticker_tags`
 * (see backend/docs/keyboard-api.md). The [wire] names must stay in sync with the backend's `STICKER_TAGS`.
 * Adding a tag is backwards compatible; renaming or removing one is not.
 */
enum class StickerTag(val wire: String) {
    THANKS("thanks"),
    OK("ok"),
    LAUGH("laugh"),
    LOVE("love"),
    WAIT("wait"),
    GREAT("great"),
    SORRY("sorry"),
    GOODNIGHT("goodnight"),
    COMING("coming"),
    YES("yes"),
    NO("no"),
    CONGRATS("congrats"),
    EAT("eat"),
    HELLO("hello"),
    BYE("bye"),
    SAD("sad"),
    ANGRY("angry"),
    SURPRISED("surprised"),
    CHEER("cheer"),
    BUSY("busy"),
    MISS_YOU("miss_you"),
    THINKING("thinking"),
    COOL("cool"),
    PLEASE("please"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        /** The tag for a wire name, or null for an unknown one (a newer backend may know more tags). */
        fun fromWire(wire: String): StickerTag? = byWire[wire.trim().lowercase()]
    }
}
