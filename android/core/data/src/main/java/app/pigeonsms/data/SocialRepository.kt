package app.pigeonsms.data

import app.pigeonsms.db.PigeonDatabase
import app.pigeonsms.network.AttachmentDto
import app.pigeonsms.network.DmDto
import app.pigeonsms.network.FriendsResponse
import app.pigeonsms.network.PigeonApi
import app.pigeonsms.network.ProfileResponse
import app.pigeonsms.network.SpaceDto
import java.util.UUID
import app.pigeonsms.network.SpaceEmojiDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Files at or above this size go through the resumable multipart path. 8 MB is
 * comfortably above a typical photo (which should stay a single request) and
 * below the point where a failed upload is genuinely painful to repeat.
 */
private const val RESUMABLE_THRESHOLD_BYTES = 8 * 1024 * 1024

/**
 * Friends, DMs, spaces, profiles.
 *
 * Network-backed and refreshed on open + gateway events, but as of v2.9.0 each of
 * the three list endpoints also writes through to Room, and [cachedDms],
 * [cachedFriends] and [cachedSpaces] read that cache back. Before this, Room held
 * *only* messages: a cold start with no network rendered an empty app even though
 * every conversation in it was already on disk.
 *
 * The cache is strictly a display fallback — the server stays the source of truth,
 * a successful refresh always replaces the cached set wholesale, and mutations
 * (add friend, create nest, …) still go straight to the network. [db] is nullable
 * so the repository can still be constructed without a database in tests.
 */
class SocialRepository(
    private val api: PigeonApi,
    private val db: PigeonDatabase? = null,
) {
    private val cache get() = db?.shellCache()

    fun mediaUrl(key: String?) = key?.let { api.mediaUrl(it) }

    suspend fun dms(): List<DmDto> = api.dms().also { dms ->
        runCatching { cache?.replaceDms(dms.toDmEntities()) }
    }

    /** Last known conversation list; empty when nothing has been cached yet. */
    suspend fun cachedDms(): List<DmDto> =
        runCatching { cache?.dms()?.toDmDtos() }.getOrNull().orEmpty()

    suspend fun openDm(userId: String): String = api.openDm(userId)

    suspend fun friends(): FriendsResponse = api.friends().also { friends ->
        runCatching { cache?.replaceFriends(friends.toFriendEntities()) }
    }

    /** Last known friends/incoming/outgoing lists; all empty when uncached. */
    suspend fun cachedFriends(): FriendsResponse =
        runCatching { cache?.friends()?.toFriendsResponse() }.getOrNull() ?: FriendsResponse()
    suspend fun addFriend(username: String) = api.addFriend(username)
    suspend fun acceptFriend(userId: String) = api.acceptFriend(userId)
    suspend fun removeFriend(userId: String) = api.removeFriend(userId)
    suspend fun updateFriend(userId: String, note: String?, close: Boolean?) = api.updateFriend(userId, note, close)
    suspend fun block(userId: String) = api.block(userId)
    suspend fun unblock(userId: String) = api.unblock(userId)
    suspend fun blocks() = api.blocks()
    suspend fun searchUsers(q: String) = api.searchUsers(q)

    suspend fun spaces(): List<SpaceDto> = api.spaces().also { spaces ->
        runCatching { cache?.replaceSpaces(spaces.toSpaceEntities(), spaces.toChannelEntities()) }
    }

    /** Last known nests, each with its channels regrouped; empty when uncached. */
    suspend fun cachedSpaces(): List<SpaceDto> =
        runCatching { cache?.let { it.spaces().toSpaceDtos(it.channels()) } }.getOrNull().orEmpty()

    /**
     * Wipe every cached list. Must run on sign-out and on a session switch: this
     * cache is per-account, so leaving it in place would show the previous user's
     * nests, DMs and friends to whoever signs in next.
     */
    suspend fun clearCache() {
        runCatching { cache?.clearAll() }
    }
    suspend fun createSpace(name: String) = api.createSpace(name, UUID.randomUUID().toString())
    suspend fun createChannel(spaceId: String, name: String) = api.createChannel(spaceId, name)
    suspend fun renameChannel(spaceId: String, channelId: String, name: String) = api.renameChannel(spaceId, channelId, name)
    suspend fun deleteChannel(spaceId: String, channelId: String) = api.deleteChannel(spaceId, channelId)
    suspend fun spaceInvite(spaceId: String) = api.spaceInvite(spaceId)
    suspend fun joinSpace(code: String) = api.joinSpace(code)
    suspend fun spaceMembers(spaceId: String) = api.spaceMembers(spaceId)
    suspend fun setRole(spaceId: String, userId: String, role: String) = api.setRole(spaceId, userId, role)
    suspend fun transferSpace(spaceId: String, userId: String) = api.transferSpace(spaceId, userId)
    suspend fun leaveSpace(spaceId: String) = api.leaveSpace(spaceId)
    suspend fun deleteSpace(spaceId: String) = api.deleteSpace(spaceId)
    suspend fun uploadFile(bytes: ByteArray, filename: String, type: String) = api.uploadFile(bytes, filename, type)

    /**
     * Upload a file with the resumable multipart path, **streaming** it.
     *
     * Takes a factory that opens a fresh `InputStream` rather than a `ByteArray`:
     * the previous signature held the entire file in the heap, so the 500 MB
     * ceiling this path advertises was a guaranteed OutOfMemoryError long before
     * you reached it. Only one part (5 MB by default) is resident at a time now.
     *
     * [openStream] must be re-openable — it is called once up front, and again if
     * a resume needs to skip past already-uploaded parts.
     *
     * [onProgress] receives 0f..1f for a real progress bar.
     */
    suspend fun uploadLargeStream(
        openStream: () -> java.io.InputStream,
        filename: String,
        type: String,
        totalSize: Long,
        onProgress: (Float) -> Unit = {},
    ): AttachmentDto {
        // Small files: one request beats session bookkeeping, and reading them
        // whole is harmless at this size.
        if (totalSize < RESUMABLE_THRESHOLD_BYTES) {
            val bytes = openStream().use { it.readBytes() }
            onProgress(1f)
            return api.uploadFile(bytes, filename, type)
        }

        val session = api.openUpload(filename, type, totalSize)
        try {
            val done = runCatching { api.uploadStatus(session.id).uploaded_parts.toSet() }
                .getOrDefault(emptySet())

            openStream().use { stream ->
                val buffer = ByteArray(session.part_size)
                for (part in 1..session.part_count) {
                    // Fill one part. read() may return short, so loop until the
                    // buffer is full or the stream ends — a short read would
                    // otherwise upload an undersized middle part, which R2 rejects
                    // only at completion time.
                    var filled = 0
                    while (filled < buffer.size) {
                        val read = stream.read(buffer, filled, buffer.size - filled)
                        if (read < 0) break
                        filled += read
                    }
                    if (filled == 0) break

                    if (part !in done) {
                        val chunk = if (filled == buffer.size) buffer else buffer.copyOf(filled)
                        api.uploadPart(session.id, part, chunk)
                    }
                    onProgress(part.toFloat() / session.part_count)
                }
            }
            return api.completeUpload(session.id)
        } catch (error: Throwable) {
            // Release the R2 storage rather than leaving it against the caller's
            // open-session cap. Best-effort: the original error is what matters.
            runCatching { api.abortUpload(session.id) }
            throw error
        }
    }

    suspend fun setSpaceIcon(spaceId: String, key: String?) = api.setSpaceIcon(spaceId, key)

    // ── v2.9.5: custom emoji + stickers ────────────────────────────────────
    //
    // Cached per nest for the lifetime of the process: the picker and every
    // message that renders a `:shortcode:` need this list, and re-fetching it per
    // message would be one request per bubble. Mutations invalidate the entry
    // rather than trying to patch it, since the set is small and a refetch is one
    // request.
    private val emojiCache = mutableMapOf<String, List<SpaceEmojiDto>>()
    private val emojiMutex = Mutex()

    suspend fun spaceEmojis(spaceId: String, refresh: Boolean = false): List<SpaceEmojiDto> =
        emojiMutex.withLock {
            if (!refresh) emojiCache[spaceId]?.let { return@withLock it }
            val fetched = runCatching { api.spaceEmojis(spaceId) }.getOrElse {
                // Never let a missing emoji list break rendering — worst case the
                // shortcode stays as text, which is what older clients show anyway.
                return@withLock emojiCache[spaceId].orEmpty()
            }
            emojiCache[spaceId] = fetched
            fetched
        }

    suspend fun createSpaceEmoji(
        spaceId: String,
        name: String,
        mediaKey: String,
        kind: String = "emoji",
        contentType: String? = null,
    ): SpaceEmojiDto {
        val created = api.createSpaceEmoji(spaceId, name, mediaKey, kind, contentType)
        emojiMutex.withLock { emojiCache.remove(spaceId) }
        return created
    }

    suspend fun renameSpaceEmoji(spaceId: String, emojiId: String, name: String): SpaceEmojiDto {
        val renamed = api.renameSpaceEmoji(spaceId, emojiId, name)
        emojiMutex.withLock { emojiCache.remove(spaceId) }
        return renamed
    }

    suspend fun deleteSpaceEmoji(spaceId: String, emojiId: String) {
        api.deleteSpaceEmoji(spaceId, emojiId)
        emojiMutex.withLock { emojiCache.remove(spaceId) }
    }

    // ── v2.9.5: roles + permissions ────────────────────────────────────────
    suspend fun spaceRoles(spaceId: String) = api.spaceRoles(spaceId)
    suspend fun spacePermissions(spaceId: String, channelId: String? = null) =
        api.spacePermissions(spaceId, channelId)
    suspend fun createRole(spaceId: String, name: String, permissions: List<String>, color: String? = null) =
        api.createRole(spaceId, name, permissions, color)
    suspend fun updateRole(
        spaceId: String,
        roleId: String,
        name: String? = null,
        permissions: List<String>? = null,
        color: String? = null,
    ) = api.updateRole(spaceId, roleId, name, permissions, color)
    suspend fun deleteRole(spaceId: String, roleId: String) = api.deleteRole(spaceId, roleId)
    suspend fun setMemberRoles(spaceId: String, userId: String, roleIds: List<String>) =
        api.setMemberRoles(spaceId, userId, roleIds)
    suspend fun channelOverrides(spaceId: String, channelId: String) =
        api.channelOverrides(spaceId, channelId)
    suspend fun setChannelOverride(
        spaceId: String,
        channelId: String,
        roleId: String? = null,
        userId: String? = null,
        allow: List<String> = emptyList(),
        deny: List<String> = emptyList(),
    ) = api.setChannelOverride(spaceId, channelId, roleId, userId, allow, deny)


    // ── v2.9.5: global search ──────────────────────────────────────────────
    suspend fun searchEverywhere(query: String, before: Long? = null) =
        api.searchEverywhere(query, before)

    suspend fun profile(userId: String): ProfileResponse = api.profile(userId)
    suspend fun updateProfile(fields: Map<String, String?>) = api.updateProfile(fields)
    suspend fun uploadAvatar(bytes: ByteArray, type: String) = api.uploadAvatar(bytes, type)
    suspend fun uploadBanner(bytes: ByteArray, type: String) = api.uploadBanner(bytes, type)
    suspend fun resetAvatar() = api.resetAvatar()
    suspend fun resetBanner() = api.resetBanner()
}
