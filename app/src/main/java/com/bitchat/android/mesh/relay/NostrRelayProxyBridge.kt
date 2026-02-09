package com.bitchat.android.mesh.relay

import android.util.Log
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrFilter
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridge-side Nostr relay proxy for WiFi-connected devices.
 *
 * Receives PUBLISH / SUBSCRIBE / UNSUBSCRIBE from BLE peers and forwards
 * events to/from Nostr relays. Acts as a dumb proxy — never decrypts payloads.
 */
class NostrRelayProxyBridge(
    private val myPeerID: String,
    private val sendPacket: (BitchatPacket) -> Unit
) {
    companion object {
        private const val TAG = "NostrRelayProxyBridge"
        private const val DEDUP_CAP = 1000
        private val PROXY_TTL: UByte = 1u
    }

    // pubkeyHex → Nostr subscription ID
    private val proxySubscriptions = ConcurrentHashMap<String, String>()

    // pubkeyHex → set of requesting peer IDs
    private val subscriberPeers = ConcurrentHashMap<String, MutableSet<String>>()

    // Dedup set for PUBLISH requests
    private val publishDedup: MutableSet<String> = Collections.synchronizedSet(
        object : LinkedHashSet<String>() {
            override fun add(element: String): Boolean {
                if (size >= DEDUP_CAP) iterator().let { it.next(); it.remove() }
                return super.add(element)
            }
        }
    )

    /**
     * Handle an incoming NOSTR_RELAY packet from a BLE peer.
     */
    fun handlePacket(routed: RoutedPacket) {
        val msg = NostrRelayProxyCodec.decode(routed.packet.payload)
        if (msg == null) {
            Log.w(TAG, "Failed to decode NOSTR_RELAY payload")
            return
        }
        val senderPeerID = routed.peerID ?: "unknown"

        when (msg) {
            is NostrRelayProxyMessage.Publish -> handlePublish(msg, senderPeerID)
            is NostrRelayProxyMessage.Subscribe -> handleSubscribe(msg, senderPeerID)
            is NostrRelayProxyMessage.Unsubscribe -> handleUnsubscribe(msg)
            is NostrRelayProxyMessage.Event -> {
                // Bridge should not receive EVENT sub-type from BLE peers
                Log.w(TAG, "Ignoring EVENT sub-type from BLE peer $senderPeerID")
            }
        }
    }

    private fun handlePublish(msg: NostrRelayProxyMessage.Publish, senderPeerID: String) {
        val event = NostrEvent.fromJsonString(msg.eventJson)
        if (event == null) {
            Log.w(TAG, "Invalid event JSON from peer $senderPeerID")
            return
        }

        // Validate minimum fields
        if (event.id.isEmpty() || event.sig.isNullOrEmpty()) {
            Log.w(TAG, "Event missing id or sig from peer $senderPeerID")
            return
        }

        // Dedup
        if (!publishDedup.add(event.id)) {
            Log.d(TAG, "Duplicate PUBLISH ${event.id.take(16)} from $senderPeerID, skipping")
            return
        }

        Log.d(TAG, "Proxying PUBLISH ${event.id.take(16)} kind=${event.kind} from BLE peer $senderPeerID")
        NostrRelayManager.registerPendingGiftWrap(event.id)
        NostrRelayManager.shared.sendEvent(event)
    }

    private fun handleSubscribe(msg: NostrRelayProxyMessage.Subscribe, senderPeerID: String) {
        for (pubkey in msg.pubkeys) {
            // Track this peer as a subscriber for this pubkey
            subscriberPeers.getOrPut(pubkey) { Collections.synchronizedSet(mutableSetOf()) }.add(senderPeerID)

            // If we already have a subscription for this pubkey, skip creating a new one
            if (proxySubscriptions.containsKey(pubkey)) {
                Log.d(TAG, "Already subscribed for pubkey ${pubkey.take(16)}, adding peer $senderPeerID")
                continue
            }

            // Create a Nostr subscription for gift-wraps addressed to this pubkey
            val sinceMs = System.currentTimeMillis() - 172800000L // 48 hours
            val filter = NostrFilter.giftWrapsFor(pubkey, sinceMs)
            val subId = "proxy-${pubkey.take(16)}"

            Log.d(TAG, "Creating proxy subscription $subId for pubkey ${pubkey.take(16)} (peer $senderPeerID)")

            val actualSubId = NostrRelayManager.shared.subscribe(
                filter = filter,
                id = subId,
                handler = { event -> onNostrEventForProxy(event, pubkey) }
            )
            proxySubscriptions[pubkey] = actualSubId
        }
    }

    private fun handleUnsubscribe(msg: NostrRelayProxyMessage.Unsubscribe) {
        for (pubkey in msg.pubkeys) {
            // Remove subscribers — if no peers remain, tear down Nostr subscription
            subscriberPeers.remove(pubkey)
            val subId = proxySubscriptions.remove(pubkey) ?: continue
            Log.d(TAG, "Removing proxy subscription $subId for pubkey ${pubkey.take(16)}")
            try {
                NostrRelayManager.shared.unsubscribe(subId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unsubscribe $subId: ${e.message}")
            }
        }
    }

    /**
     * Called when a Nostr event arrives for a proxied subscription.
     * Wraps it in a NOSTR_RELAY EVENT packet and broadcasts it via BLE.
     */
    private fun onNostrEventForProxy(event: NostrEvent, pubkey: String) {
        val json = event.toJsonString()
        val payload = NostrRelayProxyCodec.encode(NostrRelayProxyMessage.Event(json))
        if (payload == null) {
            Log.e(TAG, "Failed to encode EVENT for ${event.id.take(16)}")
            return
        }

        val packet = BitchatPacket(
            type = MessageType.NOSTR_RELAY.value,
            ttl = PROXY_TTL,
            senderID = myPeerID,
            payload = payload
        )

        Log.d(TAG, "Forwarding Nostr event ${event.id.take(16)} kind=${event.kind} to BLE mesh for pubkey ${pubkey.take(16)}")
        sendPacket(packet)
    }

    /**
     * Clean up subscriptions requested by a disconnected peer.
     */
    fun onPeerDisconnected(peerID: String) {
        val pubkeysToRemove = mutableListOf<String>()
        for ((pubkey, peers) in subscriberPeers) {
            peers.remove(peerID)
            if (peers.isEmpty()) {
                pubkeysToRemove.add(pubkey)
            }
        }
        for (pubkey in pubkeysToRemove) {
            subscriberPeers.remove(pubkey)
            val subId = proxySubscriptions.remove(pubkey) ?: continue
            Log.d(TAG, "Peer $peerID disconnected; removing proxy subscription $subId")
            try {
                NostrRelayManager.shared.unsubscribe(subId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unsubscribe $subId on peer disconnect: ${e.message}")
            }
        }
    }

    /**
     * Tear down all proxy subscriptions.
     */
    fun shutdown() {
        for ((_, subId) in proxySubscriptions) {
            try {
                NostrRelayManager.shared.unsubscribe(subId)
            } catch (_: Exception) {}
        }
        proxySubscriptions.clear()
        subscriberPeers.clear()
        publishDedup.clear()
    }
}
