package dev.barrelmaker.obscura.kit.wire

import dev.barrelmaker.obscura.kit.TypingState
import obscura.client.v1.Client

/**
 * Single source of truth for translating the `ClientMessage.payload` arm to
 * the app-facing message kind.
 *
 * Keeping every mapping here prevents call-site drift. The shared
 * `protocol/conformance/wire.json` vectors pin cross-platform behavior.
 */
object WireCodec {

    // ─── ClientMessage.payload oneof → app string ────────────────────────────

    /**
     * The app-facing message-type string: the set `payload` oneof arm's field
     * name, upper-snake (which the generated [Client.ClientMessage.PayloadCase]
     * already is). An unset payload maps to "".
     */
    fun decodeType(case: Client.ClientMessage.PayloadCase): String =
        if (case == Client.ClientMessage.PayloadCase.PAYLOAD_NOT_SET) "" else case.name

    fun decodeTypingState(state: Client.TypingState): TypingState? = when (state) {
        Client.TypingState.TYPING_STATE_STARTED -> TypingState.STARTED
        Client.TypingState.TYPING_STATE_STOPPED -> TypingState.STOPPED
        Client.TypingState.TYPING_STATE_UNSPECIFIED, Client.TypingState.UNRECOGNIZED -> null
    }

    fun encodeTypingState(state: TypingState): Client.TypingState = when (state) {
        TypingState.STARTED -> Client.TypingState.TYPING_STATE_STARTED
        TypingState.STOPPED -> Client.TypingState.TYPING_STATE_STOPPED
    }
}
