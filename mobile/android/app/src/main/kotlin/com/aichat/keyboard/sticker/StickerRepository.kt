package com.aichat.keyboard.sticker

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withContext

/**
 * Process-wide source of sticker packs: the user's own stickers, the [BuiltInStickers] and any image packs bundled
 * under `assets/ime/media/sticker/` ([AssetStickerPacks]).
 */
class StickerRepository private constructor(private val appContext: Context) {
    val userFiles = UserStickerFiles(File(appContext.filesDir, USER_DIR))

    private val _userStickers = MutableStateFlow<List<Sticker>>(emptyList())

    /** The user's stickers, newest first. Call [refresh] after anything changes them. */
    val userStickers: StateFlow<List<Sticker>> = _userStickers.asStateFlow()

    val assetPacks: List<StickerPack> by lazy { AssetStickerPacks.load(appContext) }

    private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val recentPrefs = appContext.getSharedPreferences(RECENT_PREFS, Context.MODE_PRIVATE)
    private val _recentIds = MutableStateFlow(RecentStickers.parse(recentPrefs.getString(RECENT_KEY, null)))

    /**
     * Ids of the last sent stickers, most recent first ([RecentStickers]), persisted across restarts. Resolve them
     * against [packs] with [RecentStickers.resolve]; ids of stickers that no longer exist are simply skipped.
     */
    val recentIds: StateFlow<List<String>> = _recentIds.asStateFlow()

    /**
     * Records [sticker] as recently sent if [result] means it reached the chat. Call it after every
     * [StickerSendFlow.sendAndReport]. The result is matched by name so a newly added success result
     * (e.g. `SHARED`) only needs adding to [SENT_RESULTS].
     */
    fun recordSent(sticker: Sticker, result: StickerSender.Result) {
        if (result.name !in SENT_RESULTS) return
        val ids = _recentIds.updateAndGet { RecentStickers.add(it, sticker.id) }
        // apply() writes on a background thread.
        recentPrefs.edit().putString(RECENT_KEY, RecentStickers.serialize(ids)).apply()
    }

    suspend fun refresh() {
        val stickers = withContext(Dispatchers.IO) {
            userFiles.list().map { file ->
                val id = file.nameWithoutExtension
                Sticker(id = "user.$id", description = USER_STICKER_DESCRIPTION, source = StickerSource.UserFile(id))
            }
        }
        _userStickers.value = stickers
    }

    suspend fun delete(sticker: Sticker) {
        val source = sticker.source as? StickerSource.UserFile ?: return
        withContext(Dispatchers.IO) { userFiles.delete(source.id) }
        refresh()
    }

    /** Every pack in tab order: the user's pack first (so the add tile is easy to find), then the bundled ones. */
    fun packs(userStickers: List<Sticker>, userPackTitle: String): List<StickerPack> {
        val userPack = StickerPack(
            id = USER_PACK_ID,
            title = userPackTitle,
            icon = USER_PACK_ICON,
            stickers = userStickers,
            isUserPack = true,
        )
        // Pack ids are tab keys: an asset pack can never shadow a built-in one.
        return (listOf(userPack) + BuiltInStickers.packs + assetPacks).distinctBy { it.id }
    }

    /**
     * Imports [uris] as user stickers, then refreshes and reports the result. Runs in a process-wide scope, so the
     * import completes and the palette updates even if the (invisible) import activity is gone by then.
     */
    fun importInBackground(uris: List<android.net.Uri>, onDone: suspend (List<UserStickerImporter.Outcome>) -> Unit) {
        importScope.launch {
            val outcomes = uris.map { UserStickerImporter.import(appContext, it, userFiles) }
            refresh()
            onDone(outcomes)
        }
    }

    /** The "Gần đây" pack for [packs] (the result of [packs]), or null when nothing was sent yet. */
    fun recentPack(recentIds: List<String>, packs: List<StickerPack>, title: String): StickerPack? {
        val stickers = RecentStickers.resolve(recentIds, packs)
        if (stickers.isEmpty()) return null
        return StickerPack(id = RECENT_PACK_ID, title = title, icon = RECENT_PACK_ICON, stickers = stickers)
    }

    companion object {
        const val USER_PACK_ID = "user"
        const val RECENT_PACK_ID = "recent"
        private const val RECENT_PACK_ICON = "🕘"
        private const val RECENT_PREFS = "aichat_stickers"
        private const val RECENT_KEY = "recent_ids"
        private val SENT_RESULTS = setOf("SENT", "SENT_AS_TEXT", "SHARED")
        private const val USER_PACK_ICON = "★"
        private const val USER_STICKER_DESCRIPTION = "Sticker"
        private const val USER_DIR = "aichat_stickers/user"

        @Volatile
        private var instance: StickerRepository? = null

        fun get(context: Context): StickerRepository = instance ?: synchronized(this) {
            instance ?: StickerRepository(context.applicationContext).also { instance = it }
        }
    }
}

/** Loads the image packs bundled as `assets/ime/media/sticker/<pack>/pack.json` (see [StickerPackManifest]). */
object AssetStickerPacks {
    private const val TAG = "AssetStickerPacks"
    const val ROOT = "ime/media/sticker"

    fun load(context: Context): List<StickerPack> {
        val dirs = try {
            context.assets.list(ROOT).orEmpty()
        } catch (e: IOException) {
            Log.w(TAG, "Cannot list $ROOT", e)
            return emptyList()
        }
        return dirs.sorted().mapNotNull { dir ->
            val path = "$ROOT/$dir"
            val manifest = try {
                context.assets.open("$path/pack.json").bufferedReader().use { it.readText() }
            } catch (e: IOException) {
                Log.w(TAG, "Skipping $path: no readable pack.json", e)
                return@mapNotNull null
            }
            StickerPackManifest.parse(manifest, path).also {
                if (it == null) Log.w(TAG, "Skipping $path: invalid pack.json")
            }
        }
    }
}
