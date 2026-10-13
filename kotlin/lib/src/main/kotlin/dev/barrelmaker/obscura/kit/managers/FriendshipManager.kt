package dev.barrelmaker.obscura.kit.managers

import dev.barrelmaker.obscura.kit.stores.FriendStatus
import obscura.client.v1.Client.ClientMessage

/**
 * Befriend and acceptFriend. First contact needs no special handling: the peer has no listed
 * devices yet, so [MessageSender] fetches their bundles once and records the devices.
 */
internal class FriendshipManager(
    private val ctx: ClientContext
) {
    private val session get() = ctx.session
    private val friends get() = ctx.friends
    private val devices get() = ctx.devices
    private val messageSender get() = ctx.messageSender

    suspend fun befriend(targetUserId: String, targetUsername: String) {
        require(targetUserId != session.userId) { "Cannot befriend yourself" }

        val existing = friends.get(targetUserId)
        when (existing?.status) {
            FriendStatus.ACCEPTED -> return
            FriendStatus.PENDING_RECEIVED -> {
                acceptFriend(targetUserId)
                return
            }
            FriendStatus.PENDING_SENT, null -> Unit
        }

        // The FriendRequest carries only our display username — a first-contact bootstrap label.
        // Our IDENTITY is not in the payload: the server stamps envelope.sender_id with
        // our user id, and the recipient's Signal session binds our identity key to this device.
        val msg = ClientMessage.newBuilder()
            .setFriendRequest(obscura.client.v1.friendRequest {
                username = session.username ?: ""
            })
            .setTimestamp(System.currentTimeMillis()).build()

        messageSender.sendOrThrow(targetUserId, msg)
        if (existing == null) friends.add(targetUserId, targetUsername, FriendStatus.PENDING_SENT)
    }

    suspend fun acceptFriend(targetUserId: String) {
        requireNotNull(friends.get(targetUserId)) { "Cannot accept a friend request that is not stored locally" }

        val msg = ClientMessage.newBuilder()
            .setFriendAccept(obscura.client.v1.friendAccept {})
            .setTimestamp(System.currentTimeMillis()).build()

        messageSender.sendOrThrow(targetUserId, msg)
        friends.updateStatus(targetUserId, FriendStatus.ACCEPTED)
    }

    /** Forget [userId]: the friendship and their devices. Nothing is sent to them. */
    suspend fun removeFriend(userId: String) {
        friends.remove(userId)
        devices.replace(userId, emptyList())
    }
}
