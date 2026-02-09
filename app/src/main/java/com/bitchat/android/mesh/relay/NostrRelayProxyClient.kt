package com.bitchat.android.mesh.relay

import android.util.Log
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import java.util.Collections
import java.util.LinkedHashSet

/**
 * Client-side Nostr relay proxy for BT-only devices.
 *
 * Wraps pre-signed Nostr events as NOSTR_RELAY BLE packets and broadcasts
 * them to bridge peers. Also receives EVENT packets from bridges and delivers
 * them to the local DM handler.
 */
class NostrRelayProxyClient(
    private val myPeerID: String,
    private val sendPacket: (BitchatPacket) -> Unit
) {
    companion object {
        private const val TAG = "NostrRelayProxyClient"
        private const val DEDUP_CAP = 1000
        private val PROXY_TTL: UByte = 1u
    }

    /** Callback for incoming Nostr events from bridge. Params: (eventJson, geohash?) */
    var eventHandler: ((String) -> Unit)? = null

    // LRU dedup set for incoming event IDs
    private val seenEventIds: MutableSet<String> = Collections.synchronizedSet(
        object : LinkedHashSet<String>() {
            override fun add(element: String): Boolean {
                if (size >= DEDUP_CAP) iterator().let { it.next(); it.remove() }
                return super.add(element)
            }
        }
    )

    /**
     * Publish a pre-signed Nostr event via BLE to a bridge peer.
     */
    fun publishEvent(event: NostrEvent) {
        val json = event.toJsonString()
        val payload = NostrRelayProxyCodec.encode(NostrRelayProxyMessage.Publish(json))
        if (payload == null) {
            Log.e(TAG, "Failed to encode PUBLISH for event ${event.id.take(16)}")
            return
        }
        val packet = BitchatPacket(
            type = MessageType.NOSTR_RELAY.value,
            ttl = PROXY_TTL,
            senderID = myPeerID,
            payload = payload
        )
        Log.d(TAG, "Publishing event ${event.id.take(16)} via BLE proxy (${payload.size} bytes)")
        sendPacket(packet)
    }

    /**
     * Request a bridge peer to subscribe for gift-wraps to the given pubkeys.
     */
    fun subscribe(pubkeys: List<String>, geohash: String?) {
        val payload = NostrRelayProxyCodec.encode(NostrRelayProxyMessage.Subscribe(pubkeys, geohash))
        if (payload == null) {
            Log.e(TAG, "Failed to encode SUBSCRIBE")
            return
        }
        val packet = BitchatPacket(
            type = MessageType.NOSTR_RELAY.value,
            ttl = PROXY_TTL,
            senderID = myPeerID,
            payload = payload
        )
        Log.d(TAG, "Subscribing via BLE proxy for ${pubkeys.size} pubkey(s), geohash=$geohash")
        sendPacket(packet)
    }

    /**
     * Request a bridge peer to unsubscribe from the given pubkeys.
     */
    fun unsubscribe(pubkeys: List<String>) {
        val payload = NostrRelayProxyCodec.encode(NostrRelayProxyMessage.Unsubscribe(pubkeys))
        if (payload == null) {
            Log.e(TAG, "Failed to encode UNSUBSCRIBE")
            return
        }
        val packet = BitchatPacket(
            type = MessageType.NOSTR_RELAY.value,
            ttl = PROXY_TTL,
            senderID = myPeerID,
            payload = payload
        )
        Log.d(TAG, "Unsubscribing via BLE proxy for ${pubkeys.size} pubkey(s)")
        sendPacket(packet)
    }

    /**
     * Handle an incoming NOSTR_RELAY EVENT packet from a bridge peer.
     * Returns true if the event was new (not a duplicate).
     */
    fun handleIncomingPacket(routed: RoutedPacket): Boolean {
        val msg = NostrRelayProxyCodec.decode(routed.packet.payload) ?: return false
        if (msg !is NostrRelayProxyMessage.Event) return false

        // Dedup by event ID
        val event = NostrEvent.fromJsonString(msg.eventJson) ?: return false
        if (!seenEventIds.add(event.id)) {
            Log.d(TAG, "Duplicate proxy event ${event.id.take(16)}, skipping")
            return false
        }

        Log.d(TAG, "Received proxy event ${event.id.take(16)} kind=${event.kind}")
        eventHandler?.invoke(msg.eventJson)
        return true
    }

    fun clearState() {
        seenEventIds.clear()
    }
}
