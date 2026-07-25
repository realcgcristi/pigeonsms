package app.pigeonsms.data

import app.pigeonsms.db.PigeonDatabase
import app.pigeonsms.network.DmDto
import app.pigeonsms.network.FriendsResponse
import app.pigeonsms.network.PigeonApi
import app.pigeonsms.network.ProfileResponse
import app.pigeonsms.network.SpaceDto
import java.util.UUID

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
    suspend fun setSpaceIcon(spaceId: String, key: String?) = api.setSpaceIcon(spaceId, key)

    suspend fun profile(userId: String): ProfileResponse = api.profile(userId)
    suspend fun updateProfile(fields: Map<String, String?>) = api.updateProfile(fields)
    suspend fun uploadAvatar(bytes: ByteArray, type: String) = api.uploadAvatar(bytes, type)
    suspend fun uploadBanner(bytes: ByteArray, type: String) = api.uploadBanner(bytes, type)
    suspend fun resetAvatar() = api.resetAvatar()
    suspend fun resetBanner() = api.resetBanner()
}
