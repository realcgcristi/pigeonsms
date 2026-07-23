package app.pigeonsms.data

import app.pigeonsms.network.DmDto
import app.pigeonsms.network.FriendsResponse
import app.pigeonsms.network.PigeonApi
import app.pigeonsms.network.ProfileResponse
import app.pigeonsms.network.SpaceDto
import java.util.UUID

class SocialRepository(private val api: PigeonApi) {
    fun mediaUrl(key: String?) = key?.let { api.mediaUrl(it) }

    suspend fun dms(): List<DmDto> = api.dms()
    suspend fun openDm(userId: String): String = api.openDm(userId)

    suspend fun friends(): FriendsResponse = api.friends()
    suspend fun addFriend(username: String) = api.addFriend(username)
    suspend fun acceptFriend(userId: String) = api.acceptFriend(userId)
    suspend fun removeFriend(userId: String) = api.removeFriend(userId)
    suspend fun updateFriend(userId: String, note: String?, close: Boolean?) = api.updateFriend(userId, note, close)
    suspend fun block(userId: String) = api.block(userId)
    suspend fun unblock(userId: String) = api.unblock(userId)
    suspend fun blocks() = api.blocks()
    suspend fun searchUsers(q: String) = api.searchUsers(q)

    suspend fun spaces(): List<SpaceDto> = api.spaces()
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
