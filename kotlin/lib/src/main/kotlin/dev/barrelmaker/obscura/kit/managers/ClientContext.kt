package dev.barrelmaker.obscura.kit.managers

import dev.barrelmaker.obscura.kit.crypto.SignalStore
import dev.barrelmaker.obscura.kit.network.APIClient
import dev.barrelmaker.obscura.kit.stores.*

internal class ClientContext(
    val session: ClientSession,
    val api: APIClient,
    val signalStore: SignalStore,
    val friends: FriendStore,
    val devices: DeviceStore,
) {
    lateinit var messageSender: MessageSender
}
