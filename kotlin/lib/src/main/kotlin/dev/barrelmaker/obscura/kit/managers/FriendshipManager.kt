package dev.barrelmaker.obscura.kit.managers

import dev.barrelmaker.obscura.kit.stores.FriendStatus
import obscura.client.v1.Client.ClientMessage

/**
 * Befriend and acceptFriend.
 */
internal class FriendshipManager(
    private val ctx: ClientContext
) {
    private val session get() = ctx.session
    private val messenger get() = ctx.messenger
    private val friends get() = ctx.friends
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

        messenger.fetchPreKeyBundles(targetUserId)

        // The FriendRequest carries only our display username — a first-contact bootstrap label.
        // Our IDENTITY is not in the payload: the server stamps envelope.sender_id with
        // our user id, and the recipient's Signal session pins our identity key on first contact
        // (TOFU), exactly as Signal authenticates. No user_id field is needed or sent.
        val msg = ClientMessage.newBuilder()
            .setFriendRequest(obscura.client.v1.friendRequest {
                username = session.username ?: ""
            })
            .setTimestamp(System.currentTimeMillis()).build()

        messageSender.sendToAllDevices(targetUserId, msg)
        // Persist the friend's devices (learned by the prekey fetch above) so the device->user
        // mapping survives a restart: rebuildDeviceMap(getAccepted()) restores it from here.
        if (existing == null) {
            friends.add(
                targetUserId,
                targetUsername,
                FriendStatus.PENDING_SENT,
                messenger.knownDevicesFor(targetUserId),
            )
        }
    }

    suspend fun acceptFriend(targetUserId: String) {
        val existing = requireNotNull(friends.get(targetUserId)) {
            "Cannot accept a friend request that is not stored locally"
        }
        messenger.fetchPreKeyBundles(targetUserId)

        val msg = ClientMessage.newBuilder()
            .setFriendAccept(obscura.client.v1.friendAccept {})
            .setTimestamp(System.currentTimeMillis()).build()

        messageSender.sendToAllDevices(targetUserId, msg)
        friends.add(
            targetUserId,
            existing.username,
            FriendStatus.ACCEPTED,
            messenger.knownDevicesFor(targetUserId),
        )
    }

}
