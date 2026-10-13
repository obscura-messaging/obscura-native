package dev.barrelmaker.obscura.kit.managers

import dev.barrelmaker.obscura.kit.crypto.toBase64
import dev.barrelmaker.obscura.kit.managers.SignalKeyUtils.toApiJson
import dev.barrelmaker.obscura.kit.network.UploadDeviceKeysRequest
import dev.barrelmaker.obscura.kit.stores.DeviceData
import dev.barrelmaker.obscura.kit.stores.FriendExport
import dev.barrelmaker.obscura.kit.stores.LinkedFriend
import obscura.client.v1.Client.ClientMessage

/** Device announcements, link approval, and takeover. */
internal class DeviceManager(private val ctx: ClientContext) {
    private val session get() = ctx.session
    private val api get() = ctx.api
    private val signalStore get() = ctx.signalStore
    private val friends get() = ctx.friends
    private val devices get() = ctx.devices
    private val messageSender get() = ctx.messageSender

    private fun selfUserId() = requireNotNull(session.userId) { "userId not set - call register/login first" }

    suspend fun announceDevices() {
        val ownDevices = devices.devicesOf(selfUserId())
        val msg = ClientMessage.newBuilder()
            .setTimestamp(System.currentTimeMillis())
            .setDeviceAnnounce(obscura.client.v1.deviceAnnounce {
                for (d in ownDevices) {
                    this.devices.add(obscura.client.v1.deviceInfo {
                        id = d.deviceId
                        name = d.name
                    })
                }
            }).build()

        for (friend in friends.getAccepted()) {
            messageSender.sendOrThrow(friend.userId, msg)
        }
    }

    suspend fun approveLink(newDeviceId: String, challengeResponse: ByteArray) {
        val selfUserId = selfUserId()
        // Include the device being approved so the recipient can persist a complete own-device
        // registry. Resolve its human name from the server before shipping the list.
        val newDeviceName = try {
            val serverDevices = api.listDevices()
            (0 until serverDevices.length())
                .map { serverDevices.getJSONObject(it) }
                .firstOrNull { it.getString("deviceId") == newDeviceId }
                ?.optString("name", "Device")
        } catch (e: Exception) { null } ?: "Device"
        devices.add(DeviceData(selfUserId, newDeviceId, newDeviceName))

        val ownDevices = devices.devicesOf(selfUserId)
        val export = FriendExport.encode(friends.getAll().map { LinkedFriend(it, devices.devicesOf(it.userId)) })

        val msg = ClientMessage.newBuilder()
            .setTimestamp(System.currentTimeMillis())
            .setDeviceLinkApproval(obscura.client.v1.deviceLinkApproval {
                this.challengeResponse = com.google.protobuf.ByteString.copyFrom(challengeResponse)
                for (d in ownDevices) {
                    this.ownDevices.add(obscura.client.v1.deviceInfo {
                        id = d.deviceId
                        name = d.name
                    })
                }
                friendsExport = com.google.protobuf.ByteString.copyFrom(export.toByteArray())
            }).build()

        // The approval carries the friend graph and is for the new device alone.
        messageSender.sendOrThrow(selfUserId, msg, onlyDeviceIds = setOf(newDeviceId))
        announceDevices()
    }

    suspend fun takeoverDevice() {
        val (identityKeyPair, regId) = signalStore.generateIdentity()

        val signedPreKey = SignalKeyUtils.generateSignedPreKey(signalStore, identityKeyPair, 1)
        val oneTimePreKeys = SignalKeyUtils.generateOneTimePreKeys(signalStore, 1, 100)

        api.uploadDeviceKeys(UploadDeviceKeysRequest(
            identityKey = identityKeyPair.publicKey.serialize().toBase64(),
            registrationId = regId,
            signedPreKey = signedPreKey.toApiJson(),
            oneTimePreKeys = oneTimePreKeys.toApiJson()
        ))

        // Every session was built for the old identity. Clear them all, then announce: each
        // friend's devices get a fresh prekey message from the new identity and replace the old
        // session, instead of encrypting to an identity this device no longer holds.
        signalStore.deleteAllSessions()
        announceDevices()
    }
}
