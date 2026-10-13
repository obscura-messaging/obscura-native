package dev.barrelmaker.obscura.kit.stores

import org.json.JSONArray
import org.json.JSONObject

/** A friend and its devices, as DEVICE_LINK_APPROVAL carries the friend graph to a new own device. */
internal data class LinkedFriend(val friend: FriendData, val devices: List<DeviceData>)

/**
 * The `friends_export` encoding shared with the Swift kit:
 * `[{"userId","username","status","devices":[{"deviceId","deviceName"}]}]`.
 */
internal object FriendExport {
    fun encode(friends: List<LinkedFriend>): String = JSONArray(friends.map { (friend, devices) ->
        JSONObject()
            .put("userId", friend.userId)
            .put("username", friend.username)
            .put("status", friend.status.value)
            .put("devices", JSONArray(devices.map { JSONObject().put("deviceId", it.deviceId).put("deviceName", it.name) }))
    }).toString()

    /** Entries with an unknown status are dropped rather than guessed. */
    fun decode(json: String): List<LinkedFriend> {
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getJSONObject(it) }.mapNotNull { obj ->
            val userId = obj.getString("userId")
            val status = FriendStatus.entries.find { it.value == obj.getString("status") } ?: return@mapNotNull null
            val devices = obj.optJSONArray("devices") ?: JSONArray()
            LinkedFriend(
                FriendData(userId, obj.getString("username"), status),
                (0 until devices.length()).map { devices.getJSONObject(it) }
                    .map { DeviceData(userId, it.getString("deviceId"), it.optString("deviceName")) },
            )
        }
    }
}
