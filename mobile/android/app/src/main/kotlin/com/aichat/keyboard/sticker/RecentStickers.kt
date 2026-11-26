package com.aichat.keyboard.sticker

/**
 * The "Gần đây" list: ids of the last sent stickers, most recent first. Pure Kotlin so the list rules are
 * unit-tested; [StickerRepository] keeps the live list and persists it. Only ids are stored, never images.
 */
object RecentStickers {
    const val MAX = 16

    private const val MAX_ID_LENGTH = 200

    /** [ids] with [id] moved (or added) to the front, without duplicates, at most [max] long. */
    fun add(ids: List<String>, id: String, max: Int = MAX): List<String> {
        if (!isValidId(id)) return ids.take(max)
        return (listOf(id) + ids.filter { it != id }).take(max)
    }

    /** One id per line. */
    fun serialize(ids: List<String>): String = ids.filter(::isValidId).joinToString("\n")

    /** The ids in [text] (see [serialize]); blank, malformed and repeated lines are dropped. */
    fun parse(text: String?, max: Int = MAX): List<String> = text.orEmpty()
        .lineSequence()
        .map { it.trim() }
        .filter(::isValidId)
        .distinct()
        .take(max)
        .toList()

    /**
     * The stickers of [ids], in order, looked up in [packs]. Ids no pack knows any more (a deleted user sticker,
     * a removed asset pack) are dropped.
     */
    fun resolve(ids: List<String>, packs: List<StickerPack>): List<Sticker> {
        val byId = HashMap<String, Sticker>()
        for (pack in packs) for (sticker in pack.stickers) byId.putIfAbsent(sticker.id, sticker)
        return ids.mapNotNull { byId[it] }
    }

    /** Any sticker id fits on one line of the stored text; the length cap bounds a corrupted file. */
    private fun isValidId(id: String): Boolean =
        id.isNotBlank() && id.length <= MAX_ID_LENGTH && id == id.trim() && id.lines().size == 1
}
