package app.pigeonsms.data

import app.pigeonsms.db.ChannelEntity
import app.pigeonsms.db.DmEntity
import app.pigeonsms.db.FriendEntity
import app.pigeonsms.db.SpaceEntity
import app.pigeonsms.network.ChannelDto
import app.pigeonsms.network.DmDto
import app.pigeonsms.network.FriendDto
import app.pigeonsms.network.FriendsResponse
import app.pigeonsms.network.LastMessageDto
import app.pigeonsms.network.PeerDto
import app.pigeonsms.network.SpaceDto

/**
 * Translation between the network DTOs and the Room cache rows for the app shell
 * (v2.9.0 — see [SocialRepository]).
 *
 * Deliberately DTO-in / DTO-out: every screen and view model already speaks
 * `SpaceDto` / `DmDto` / `FriendsResponse`, so the cache slots in underneath them
 * without a single UI change. The alternative — exposing entities upward — would
 * have meant touching every consumer of the home screens for no behavioural gain.
 *
 * `position` is assigned from the server's list order on write and used as the
 * only sort key on read, so the cached list renders in exactly the order the
 * server sent, without the client inventing a ranking of its own.
 */

// ── nests ──────────────────────────────────────────────────────────────────

internal fun List<SpaceDto>.toSpaceEntities(): List<SpaceEntity> =
    mapIndexed { index, dto ->
        SpaceEntity(
            id = dto.id,
            name = dto.name,
            ownerId = dto.owner_id,
            iconKey = dto.icon_key,
            iconOriginalKey = dto.icon_original_key,
            iconSquareKey = dto.icon_square_key,
            description = dto.description,
            role = dto.role,
            memberCount = dto.member_count,
            position = index,
        )
    }

/**
 * Flattens every nest's channels into one table. A channel's index within its own
 * nest is not enough to order the flattened list, so `position` is a running
 * counter across all nests — which preserves both the nest order and the channel
 * order within each nest when they are regrouped on read.
 */
internal fun List<SpaceDto>.toChannelEntities(): List<ChannelEntity> {
    var position = 0
    return flatMap { space ->
        space.channels.map { channel ->
            ChannelEntity(
                id = channel.id,
                spaceId = space.id,
                name = channel.name,
                topic = channel.topic,
                lastSeq = channel.last_seq,
                unread = channel.unread,
                kind = channel.kind,
                position = position++,
            )
        }
    }
}

internal fun List<SpaceEntity>.toSpaceDtos(channels: List<ChannelEntity>): List<SpaceDto> {
    val bySpace = channels.groupBy { it.spaceId }
    return map { space ->
        SpaceDto(
            id = space.id,
            name = space.name,
            owner_id = space.ownerId,
            icon_key = space.iconKey,
            icon_original_key = space.iconOriginalKey,
            icon_square_key = space.iconSquareKey,
            description = space.description,
            role = space.role,
            member_count = space.memberCount,
            channels = bySpace[space.id].orEmpty().map { channel ->
                ChannelDto(
                    id = channel.id,
                    name = channel.name,
                    topic = channel.topic,
                    last_seq = channel.lastSeq,
                    unread = channel.unread,
                    kind = channel.kind,
                )
            },
        )
    }
}

// ── dms ────────────────────────────────────────────────────────────────────

internal fun List<DmDto>.toDmEntities(): List<DmEntity> =
    mapIndexed { index, dto ->
        DmEntity(
            channelId = dto.channel_id,
            peerId = dto.peer.id,
            peerUsername = dto.peer.username,
            peerDisplayName = dto.peer.display_name,
            peerAvatarKey = dto.peer.avatar_key,
            peerAccent = dto.peer.accent,
            peerStatusText = dto.peer.status_text,
            peerLastOnline = dto.peer.last_online,
            lastSeq = dto.last_seq,
            unread = dto.unread,
            lastMessageContent = dto.last_message?.content,
            lastMessageCreatedAt = dto.last_message?.created_at,
            lastMessageDeleted = dto.last_message?.deleted ?: false,
            position = index,
        )
    }

internal fun List<DmEntity>.toDmDtos(): List<DmDto> =
    map { row ->
        DmDto(
            channel_id = row.channelId,
            last_seq = row.lastSeq,
            unread = row.unread,
            peer = PeerDto(
                id = row.peerId,
                username = row.peerUsername,
                display_name = row.peerDisplayName,
                avatar_key = row.peerAvatarKey,
                accent = row.peerAccent,
                status_text = row.peerStatusText,
                last_online = row.peerLastOnline,
            ),
            // A DM with no messages yet has no preview; content and timestamp are
            // written together, so either both are present or the preview is absent.
            last_message = row.lastMessageContent?.let { content ->
                LastMessageDto(
                    content = content,
                    created_at = row.lastMessageCreatedAt ?: 0L,
                    deleted = row.lastMessageDeleted,
                )
            },
        )
    }

// ── friends ────────────────────────────────────────────────────────────────

/** Which of the three server lists a cached friend row came from. */
internal const val BUCKET_FRIENDS = "friends"
internal const val BUCKET_INCOMING = "incoming"
internal const val BUCKET_OUTGOING = "outgoing"

internal fun FriendsResponse.toFriendEntities(): List<FriendEntity> {
    var position = 0
    fun bucket(list: List<FriendDto>, name: String) = list.map { dto ->
        FriendEntity(
            id = dto.id,
            bucket = name,
            username = dto.username,
            displayName = dto.display_name,
            avatarKey = dto.avatar_key,
            accent = dto.accent,
            statusText = dto.status_text,
            lastOnline = dto.last_online,
            note = dto.note,
            closeFriend = dto.close_friend,
            position = position++,
        )
    }
    return bucket(friends, BUCKET_FRIENDS) +
        bucket(incoming, BUCKET_INCOMING) +
        bucket(outgoing, BUCKET_OUTGOING)
}

internal fun List<FriendEntity>.toFriendsResponse(): FriendsResponse {
    fun bucket(name: String) = filter { it.bucket == name }.map { row ->
        FriendDto(
            id = row.id,
            username = row.username,
            display_name = row.displayName,
            avatar_key = row.avatarKey,
            accent = row.accent,
            status_text = row.statusText,
            last_online = row.lastOnline,
            note = row.note,
            close_friend = row.closeFriend,
        )
    }
    return FriendsResponse(
        friends = bucket(BUCKET_FRIENDS),
        incoming = bucket(BUCKET_INCOMING),
        outgoing = bucket(BUCKET_OUTGOING),
    )
}
