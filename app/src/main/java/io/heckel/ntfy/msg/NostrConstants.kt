package io.heckel.ntfy.msg

/**
 * Event type constants. Previously lived in ApiService; extracted here so the
 * Notification entity doesn't depend on the HTTP-layer ApiService.
 */
object NostrConstants {
    const val EVENT_MESSAGE = "message"
    const val EVENT_MESSAGE_DELETE = "message_delete"
    const val EVENT_MESSAGE_CLEAR = "message_clear"
    const val EVENT_KEEPALIVE = "keepalive"
    const val EVENT_OPEN = "open"
    const val EVENT_POLL_REQUEST = "poll_request"

    const val NOSTR_BASE_URL = "nostr://"
}
