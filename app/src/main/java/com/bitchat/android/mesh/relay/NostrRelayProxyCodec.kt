package com.bitchat.android.mesh.relay

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Sub-types for NOSTR_RELAY packets
 */
enum class NostrRelaySubType(val value: UByte) {
    PUBLISH(0x01u),
    SUBSCRIBE(0x02u),
    UNSUBSCRIBE(0x03u),
    EVENT(0x04u);

    companion object {
        fun fromValue(value: UByte): NostrRelaySubType? =
            entries.find { it.value == value }
    }
}

/**
 * Sealed hierarchy for all NOSTR_RELAY proxy messages
 */
sealed class NostrRelayProxyMessage {
    /** BT-only → bridge: publish this pre-signed event */
    data class Publish(val eventJson: String) : NostrRelayProxyMessage()

    /** BT-only → bridge: subscribe for gift-wraps to these pubkeys */
    data class Subscribe(val pubkeys: List<String>, val geohash: String?) : NostrRelayProxyMessage()

    /** BT-only → bridge: remove these subscriptions */
    data class Unsubscribe(val pubkeys: List<String>) : NostrRelayProxyMessage()

    /** Bridge → BT-only: incoming event from Nostr */
    data class Event(val eventJson: String) : NostrRelayProxyMessage()
}

/**
 * Stateless encoder/decoder for NOSTR_RELAY packet payloads.
 *
 * Wire format:
 *   PUBLISH / EVENT:   [sub-type: 1 byte] [gzip-compressed UTF-8 JSON]
 *   SUBSCRIBE:         [sub-type: 1 byte] [count: 1 byte] [pubkey1: 32 bytes] ... [geohash: UTF-8, remaining bytes]
 *   UNSUBSCRIBE:       [sub-type: 1 byte] [count: 1 byte] [pubkey1: 32 bytes] ...
 */
object NostrRelayProxyCodec {
    private const val TAG = "NostrRelayProxyCodec"

    fun encode(msg: NostrRelayProxyMessage): ByteArray? {
        return try {
            when (msg) {
                is NostrRelayProxyMessage.Publish -> encodeJson(NostrRelaySubType.PUBLISH, msg.eventJson)
                is NostrRelayProxyMessage.Event -> encodeJson(NostrRelaySubType.EVENT, msg.eventJson)
                is NostrRelayProxyMessage.Subscribe -> encodeSubscribe(msg)
                is NostrRelayProxyMessage.Unsubscribe -> encodeUnsubscribe(msg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encode failed: ${e.message}")
            null
        }
    }

    fun decode(payload: ByteArray): NostrRelayProxyMessage? {
        if (payload.isEmpty()) return null
        return try {
            val subType = NostrRelaySubType.fromValue(payload[0].toUByte()) ?: return null
            val data = payload.copyOfRange(1, payload.size)
            when (subType) {
                NostrRelaySubType.PUBLISH -> NostrRelayProxyMessage.Publish(gunzip(data))
                NostrRelaySubType.EVENT -> NostrRelayProxyMessage.Event(gunzip(data))
                NostrRelaySubType.SUBSCRIBE -> decodeSubscribe(data)
                NostrRelaySubType.UNSUBSCRIBE -> decodeUnsubscribe(data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode failed: ${e.message}")
            null
        }
    }

    // -- internal helpers --

    private fun encodeJson(subType: NostrRelaySubType, json: String): ByteArray {
        val compressed = gzip(json.toByteArray(Charsets.UTF_8))
        return byteArrayOf(subType.value.toByte()) + compressed
    }

    private fun encodeSubscribe(msg: NostrRelayProxyMessage.Subscribe): ByteArray {
        val count = msg.pubkeys.size.coerceAtMost(255)
        val buf = ByteArrayOutputStream()
        buf.write(NostrRelaySubType.SUBSCRIBE.value.toInt())
        buf.write(count)
        for (i in 0 until count) {
            buf.write(hexToBytes(msg.pubkeys[i]))
        }
        msg.geohash?.let { buf.write(it.toByteArray(Charsets.UTF_8)) }
        return buf.toByteArray()
    }

    private fun encodeUnsubscribe(msg: NostrRelayProxyMessage.Unsubscribe): ByteArray {
        val count = msg.pubkeys.size.coerceAtMost(255)
        val buf = ByteArrayOutputStream()
        buf.write(NostrRelaySubType.UNSUBSCRIBE.value.toInt())
        buf.write(count)
        for (i in 0 until count) {
            buf.write(hexToBytes(msg.pubkeys[i]))
        }
        return buf.toByteArray()
    }

    private fun decodeSubscribe(data: ByteArray): NostrRelayProxyMessage.Subscribe? {
        if (data.isEmpty()) return null
        val count = data[0].toInt() and 0xFF
        val expectedPubkeyBytes = 1 + count * 32
        if (data.size < expectedPubkeyBytes) return null
        val pubkeys = (0 until count).map { i ->
            val offset = 1 + i * 32
            bytesToHex(data.copyOfRange(offset, offset + 32))
        }
        val geohash = if (data.size > expectedPubkeyBytes) {
            String(data, expectedPubkeyBytes, data.size - expectedPubkeyBytes, Charsets.UTF_8)
        } else null
        return NostrRelayProxyMessage.Subscribe(pubkeys, geohash)
    }

    private fun decodeUnsubscribe(data: ByteArray): NostrRelayProxyMessage.Unsubscribe? {
        if (data.isEmpty()) return null
        val count = data[0].toInt() and 0xFF
        val expectedSize = 1 + count * 32
        if (data.size < expectedSize) return null
        val pubkeys = (0 until count).map { i ->
            val offset = 1 + i * 32
            bytesToHex(data.copyOfRange(offset, offset + 32))
        }
        return NostrRelayProxyMessage.Unsubscribe(pubkeys)
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gunzip(data: ByteArray): String {
        GZIPInputStream(ByteArrayInputStream(data)).use { gis ->
            return gis.readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.lowercase()
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
