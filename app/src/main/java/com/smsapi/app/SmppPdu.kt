package com.smsapi.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SmppPdu(
    val commandLength: Int,
    val commandId: Int,
    val commandStatus: Int,
    val sequenceNumber: Int,
    val body: ByteArray = byteArrayOf(),
    val optionalParams: List<SmppTlv> = emptyList()
) {
    companion object {
        const val BIND_RECEIVER = 0x00000001
        const val BIND_RECEIVER_RESP = -0x7FFFFFFF.toInt() // 0x80000001
        const val BIND_TRANSMITTER = 0x00000002
        const val BIND_TRANSMITTER_RESP = -0x7FFFFFFE.toInt() // 0x80000002
        const val BIND_TRANSCEIVER = 0x00000009
        const val BIND_TRANSCEIVER_RESP = -0x7FFFFFF7.toInt() // 0x80000009
        const val UNBIND = 0x00000006
        const val UNBIND_RESP = -0x7FFFFFFA.toInt() // 0x80000006
        const val SUBMIT_SM = 0x00000004
        const val SUBMIT_SM_RESP = -0x7FFFFFFC.toInt() // 0x80000004
        const val DELIVER_SM = 0x00000005
        const val DELIVER_SM_RESP = -0x7FFFB // 0x80000005
        const val ENQUIRE_LINK = 0x00000015
        const val ENQUIRE_LINK_RESP = -0x7FFFFFFB.toInt() // 0x80000015
        const val GENERIC_NACK = -0x80000000.toInt() // 0x80000000

        const val STATUS_OK = 0x00000000
        const val STATUS_INVALID_CMD = 0x00000003
        const val STATUS_BIND_FAIL = 0x00000005
        const val STATUS_INVL_MSG_FLD = 0x00000001

        // SMPP Version constants
        const val SMPP_VERSION_3_4 = 0x34
        const val SMPP_VERSION_5_0 = 0x50

        // Optional parameter tags (TLV)
        const val TLV_SC_INTERFACE_VERSION = 0x001D
        const val TLV_MESSAGE_PAYLOAD = 0x0424
        const val TLV_PADDING = 0x0000
        const val TLV_SOURCE_SUBADDRESS = 0x0201
        const val TLV_DEST_SUBADDRESS = 0x0202
        const val TLV_DELIVERY_RESULT = 0x0421
        const val TLV_MESSAGE_STATE = 0x0427
        const val TLV_RECEPTION_DATE_TIME = 0x0423

        fun parse(data: ByteArray): SmppPdu? {
            if (data.size < 16) return null
            val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val commandLength = bb.getInt()
            val commandId = bb.getInt()
            val commandStatus = bb.getInt()
            val sequenceNumber = bb.getInt()

            val bodyLen = commandLength - 16
            val body = if (bodyLen > 0 && data.size >= commandLength) {
                data.copyOfRange(16, commandLength)
            } else {
                byteArrayOf()
            }

            // Parse optional parameters (TLVs) if present
            val optionalParams = mutableListOf<SmppTlv>()
            if (data.size > commandLength) {
                var pos = commandLength
                while (pos + 4 <= data.size) {
                    val tag = (data[pos].toInt() and 0xFF) shl 8 or (data[pos + 1].toInt() and 0xFF)
                    val len = (data[pos + 2].toInt() and 0xFF) shl 8 or (data[pos + 3].toInt() and 0xFF)
                    if (pos + 4 + len <= data.size) {
                        val value = data.copyOfRange(pos + 4, pos + 4 + len)
                        optionalParams.add(SmppTlv(tag, len, value))
                    }
                    pos += 4 + len
                }
            }

            return SmppPdu(commandLength, commandId, commandStatus, sequenceNumber, body, optionalParams)
        }

        fun buildDeliverSm(
            sourceAddr: String,
            destAddr: String,
            message: ByteArray,
            esmClass: Int = 0x04, // Delivery receipt
            dataCoding: Int = 0x00,
            registeredDelivery: Int = 0x00,
            sequenceNumber: Int = 0
        ): SmppPdu {
            val body = ByteArray(300)
            var pos = 0

            // service_type (null-terminated empty string)
            body[pos++] = 0x00

            // source_addr_ton (international)
            body[pos++] = 0x01
            // source_addr_npi (ISDN)
            body[pos++] = 0x01
            // source_addr
            val srcBytes = sourceAddr.toByteArray(Charsets.US_ASCII)
            System.arraycopy(srcBytes, 0, body, pos, srcBytes.size)
            pos += srcBytes.size
            body[pos++] = 0x00

            // dest_addr_ton (international)
            body[pos++] = 0x01
            // dest_addr_npi (ISDN)
            body[pos++] = 0x01
            // dest_addr
            val dstBytes = destAddr.toByteArray(Charsets.US_ASCII)
            System.arraycopy(dstBytes, 0, body, pos, dstBytes.size)
            pos += dstBytes.size
            body[pos++] = 0x00

            // esm_class
            body[pos++] = esmClass.toByte()
            // protocol_id
            body[pos++] = 0x00
            // priority_flag
            body[pos++] = 0x00
            // schedule_delivery_time (empty)
            body[pos++] = 0x00
            // validity_period (empty)
            body[pos++] = 0x00
            // registered_delivery
            body[pos++] = registeredDelivery.toByte()
            // replace_if_present_flag
            body[pos++] = 0x00
            // data_coding
            body[pos++] = dataCoding.toByte()
            // sm_default_msg_id
            body[pos++] = 0x00
            // sm_length
            body[pos++] = message.size.toByte()
            // short_message
            System.arraycopy(message, 0, body, pos, message.size)
            pos += message.size

            val pduBody = body.copyOfRange(0, pos)
            return SmppPdu(16 + pduBody.size, DELIVER_SM, STATUS_OK, sequenceNumber, pduBody)
        }

        fun buildSubmitSmResp(
            status: Int,
            messageId: String,
            sequenceNumber: Int
        ): SmppPdu {
            val body = writeCString(messageId)
            return SmppPdu(16 + body.size, SUBMIT_SM_RESP, status, sequenceNumber, body)
        }

        fun buildGenericNack(status: Int, sequenceNumber: Int): SmppPdu {
            return SmppPdu(16, GENERIC_NACK, status, sequenceNumber)
        }

        fun buildBindResp(
            commandId: Int,
            status: Int,
            sequenceNumber: Int,
            systemId: String,
            interfaceVersion: Int = SMPP_VERSION_3_4,
            optionalParams: List<SmppTlv> = emptyList()
        ): SmppPdu {
            val respId = when (commandId) {
                BIND_RECEIVER -> BIND_RECEIVER_RESP
                BIND_TRANSMITTER -> BIND_TRANSMITTER_RESP
                BIND_TRANSCEIVER -> BIND_TRANSCEIVER_RESP
                else -> BIND_RECEIVER_RESP
            }

            val body = writeCString(systemId)

            // Encode optional params
            var optLen = 0
            for (tlv in optionalParams) {
                optLen += 4 + tlv.value.size
            }

            val fullBody = ByteArray(body.size + optLen)
            System.arraycopy(body, 0, fullBody, 0, body.size)

            if (optLen > 0) {
                var pos = body.size
                for (tlv in optionalParams) {
                    fullBody[pos++] = ((tlv.tag shr 8) and 0xFF).toByte()
                    fullBody[pos++] = (tlv.tag and 0xFF).toByte()
                    fullBody[pos++] = ((tlv.length shr 8) and 0xFF).toByte()
                    fullBody[pos++] = (tlv.length and 0xFF).toByte()
                    System.arraycopy(tlv.value, 0, fullBody, pos, tlv.value.size)
                    pos += tlv.value.size
                }
            }

            return SmppPdu(16 + fullBody.size, respId, status, sequenceNumber, fullBody, optionalParams)
        }
    }

    fun toByteArray(): ByteArray {
        val totalLen = 16 + body.size
        val bb = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(totalLen)
        bb.putInt(commandId)
        bb.putInt(commandStatus)
        bb.putInt(sequenceNumber)
        bb.put(body)
        return bb.array()
    }

    fun getCommandName(): String = when (commandId) {
        BIND_RECEIVER -> "BIND_RECEIVER"
        BIND_RECEIVER_RESP -> "BIND_RECEIVER_RESP"
        BIND_TRANSMITTER -> "BIND_TRANSMITTER"
        BIND_TRANSMITTER_RESP -> "BIND_TRANSMITTER_RESP"
        BIND_TRANSCEIVER -> "BIND_TRANSCEIVER"
        BIND_TRANSCEIVER_RESP -> "BIND_TRANSCEIVER_RESP"
        UNBIND -> "UNBIND"
        UNBIND_RESP -> "UNBIND_RESP"
        SUBMIT_SM -> "SUBMIT_SM"
        SUBMIT_SM_RESP -> "SUBMIT_SM_RESP"
        DELIVER_SM -> "DELIVER_SM"
        DELIVER_SM_RESP -> "DELIVER_SM_RESP"
        ENQUIRE_LINK -> "ENQUIRE_LINK"
        ENQUIRE_LINK_RESP -> "ENQUIRE_LINK_RESP"
        GENERIC_NACK -> "GENERIC_NACK"
        else -> "UNKNOWN(0x${commandId.toString(16)})"
    }
}

data class SmppTlv(val tag: Int, val length: Int, val value: ByteArray) {
    fun getTag(): String = when (tag) {
        SmppPdu.TLV_SC_INTERFACE_VERSION -> "sc_interface_version"
        SmppPdu.TLV_MESSAGE_PAYLOAD -> "message_payload"
        SmppPdu.TLV_PADDING -> "padding"
        SmppPdu.TLV_SOURCE_SUBADDRESS -> "source_subaddress"
        SmppPdu.TLV_DEST_SUBADDRESS -> "dest_subaddress"
        SmppPdu.TLV_DELIVERY_RESULT -> "delivery_result"
        SmppPdu.TLV_MESSAGE_STATE -> "message_state"
        SmppPdu.TLV_RECEPTION_DATE_TIME -> "reception_date_time"
        else -> "0x${tag.toString(16).uppercase()}"
    }

    fun getValueAsInt(): Int = if (value.isNotEmpty()) {
        var result = 0
        for (b in value) {
            result = (result shl 8) or (b.toInt() and 0xFF)
        }
        result
    } else 0

    fun getValueAsString(): String = String(value, Charsets.US_ASCII)
}

data class SmppBindRequest(
    val systemId: String,
    val password: String,
    val systemType: String,
    val interfaceVersion: Int,
    val addrTon: Int = 0,
    val addrNpi: Int = 0,
    val addressRange: String = ""
) {
    companion object {
        fun parse(body: ByteArray): SmppBindRequest {
            val buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
            val systemId = readCString(buf)
            val password = readCString(buf)
            val systemType = readCString(buf)
            val interfaceVersion = if (buf.hasRemaining()) buf.get().toInt() and 0xFF else 0x34

            // v3.4 optional fields after interface_version
            var addrTon = 0
            var addrNpi = 0
            var addressRange = ""
            if (buf.hasRemaining()) addrTon = buf.get().toInt() and 0xFF
            if (buf.hasRemaining()) addrNpi = buf.get().toInt() and 0xFF
            if (buf.hasRemaining()) addressRange = readCString(buf)

            return SmppBindRequest(systemId, password, systemType, interfaceVersion, addrTon, addrNpi, addressRange)
        }
    }
}

data class SmppSubmitSm(
    val serviceType: String,
    val sourceAddrTon: Int,
    val sourceAddrNpi: Int,
    val sourceAddr: String,
    val destAddrTon: Int,
    val destAddrNpi: Int,
    val destAddr: String,
    val esmClass: Int,
    val protocolId: Int,
    val priorityFlag: Int,
    val scheduleDeliveryTime: String,
    val validityPeriod: String,
    val registeredDelivery: Int,
    val replaceIfPresentFlag: Int,
    val dataCoding: Int,
    val smDefaultMsgId: Int,
    val smLength: Int,
    val shortMessage: ByteArray,
    val optionalParams: List<SmppTlv> = emptyList()
) {
    companion object {
        fun parse(body: ByteArray): SmppSubmitSm {
            val buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
            val serviceType = readCString(buf)
            val sourceAddrTon = buf.get().toInt() and 0xFF
            val sourceAddrNpi = buf.get().toInt() and 0xFF
            val sourceAddr = readCString(buf)
            val destAddrTon = buf.get().toInt() and 0xFF
            val destAddrNpi = buf.get().toInt() and 0xFF
            val destAddr = readCString(buf)
            val esmClass = buf.get().toInt() and 0xFF
            val protocolId = buf.get().toInt() and 0xFF
            val priorityFlag = buf.get().toInt() and 0xFF
            val scheduleDeliveryTime = readCString(buf)
            val validityPeriod = readCString(buf)
            val registeredDelivery = buf.get().toInt() and 0xFF
            val replaceIfPresentFlag = buf.get().toInt() and 0xFF
            val dataCoding = buf.get().toInt() and 0xFF
            val smDefaultMsgId = buf.get().toInt() and 0xFF
            val smLength = buf.get().toInt() and 0xFF

            val shortMessage = if (smLength > 0 && buf.remaining() >= smLength) {
                ByteArray(smLength).also { buf.get(it) }
            } else if (buf.hasRemaining()) {
                // Remaining bytes in buffer
                ByteArray(buf.remaining()).also { buf.get(it) }
            } else {
                byteArrayOf()
            }

            // Parse optional parameters from remaining buffer
            val optionalParams = mutableListOf<SmppTlv>()
            while (buf.hasRemaining() && buf.remaining() >= 4) {
                val tag = buf.getShort().toInt() and 0xFFFF
                val len = buf.getShort().toInt() and 0xFFFF
                if (len > 0 && buf.remaining() >= len) {
                    val value = ByteArray(len)
                    buf.get(value)
                    optionalParams.add(SmppTlv(tag, len, value))
                }
            }

            return SmppSubmitSm(
                serviceType, sourceAddrTon, sourceAddrNpi, sourceAddr,
                destAddrTon, destAddrNpi, destAddr,
                esmClass, protocolId, priorityFlag,
                scheduleDeliveryTime, validityPeriod,
                registeredDelivery, replaceIfPresentFlag,
                dataCoding, smDefaultMsgId, smLength, shortMessage, optionalParams
            )
        }
    }

    fun getMessageText(): String {
        return when (dataCoding) {
            0x00 -> decodeGsm7bit(shortMessage)
            0x01 -> String(shortMessage, Charsets.ISO_8859_1)
            0x02 -> decodeGsm7bit(shortMessage) // GSM 7bit default alphabet
            0x03 -> String(shortMessage, Charsets.UTF_8)
            0x04 -> String(shortMessage, Charsets.US_ASCII)
            0x08 -> decodeUCS2(shortMessage)
            0x0C -> String(shortMessage, Charsets.UTF_8) // Latin/Hebrew
            0x0D -> decodeUCS2(shortMessage) // UCS2
            else -> decodeGsm7bit(shortMessage)
        }
    }

    fun getDataCodingName(): String = when (dataCoding and 0xF0) {
        0x00 -> when (dataCoding) {
            0x00 -> "GSM 7bit default"
            0x01 -> "IA5/ASCII"
            0x02 -> "GSM 7bit"
            0x03 -> "Binary"
            0x04 -> "UCS2"
            0x05 -> "Irish/Gaelic"
            else -> "Reserved($dataCoding)"
        }
        0x10 -> "GSM 7bit (compressed)"
        0x20 -> "GSM data class (Flash)"
        0x30 -> "UCS2 class"
        0xF0 -> "SC-specific"
        else -> "Unknown($dataCoding)"
    }

    fun isConcatenated(): Boolean {
        // ESM class bit 6 indicates UDH
        if (esmClass and 0x40 != 0) return true
        // Check for UDH indicator in data coding
        if (dataCoding and 0x04 != 0) return true
        // Check message_payload TLV
        return optionalParams.any { it.tag == SmppPdu.TLV_MESSAGE_PAYLOAD }
    }

    fun getUdhInfo(): UdhInfo? {
        if (shortMessage.isEmpty() || shortMessage[0].toInt() and 0xFF == 0) return null

        // Check for UDH (User Data Header)
        val udhIndicator = dataCoding and 0x04
        if (udhIndicator != 0 || esmClass and 0x40 != 0) {
            return UdhParser.parse(shortMessage)
        }
        return null
    }
}

data class UdhInfo(
    val totalParts: Int,
    val partNumber: Int,
    val reference: Int,
    val iei: Int,
    val ieiData: ByteArray = byteArrayOf()
)

object UdhParser {
    private const val IEI_CONCATENATED_8BIT = 0x00
    private const val IEI_CONCATENATED_16BIT = 0x08
    private const val IEI_APPLICATION_PORT_8BIT = 0x04
    private const val IEI_APPLICATION_PORT_16BIT = 0x05
    private const val IEI_NATIONAL_LANGUAGE = 0x24

    fun parse(data: ByteArray): UdhInfo? {
        if (data.isEmpty()) return null

        val gsmHeaderLen = data[0].toInt() and 0xFF
        if (gsmHeaderLen >= data.size) return null

        var pos = 1
        val endPos = 1 + gsmHeaderLen

        while (pos + 1 < endPos) {
            val iei = data[pos].toInt() and 0xFF
            val ieiLen = data[pos + 1].toInt() and 0xFF
            pos += 2

            if (pos + ieiLen > endPos) break

            when (iei) {
                IEI_CONCATENATED_8BIT -> {
                    if (ieiLen >= 3) {
                        val ref = data[pos].toInt() and 0xFF
                        val total = data[pos + 1].toInt() and 0xFF
                        val part = data[pos + 2].toInt() and 0xFF
                        return UdhInfo(total, part, ref, iei)
                    }
                }
                IEI_CONCATENATED_16BIT -> {
                    if (ieiLen >= 4) {
                        val ref = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                        val total = data[pos + 2].toInt() and 0xFF
                        val part = data[pos + 3].toInt() and 0xFF
                        return UdhInfo(total, part, ref, iei)
                    }
                }
            }

            pos += ieiLen
        }

        return null
    }
}

// GSM 7bit default alphabet mapping
private val GSM7BIT_TABLE = charArrayOf(
    '@', '£', '$', '¥', 'è', 'é', 'ù', 'ì', 'ò', 'Ç', '\n', 'Ø', 'ø', '\r', 'Å', 'å',
    'Δ', '_', 'Φ', 'Γ', 'Λ', 'Ω', 'Π', 'Ψ', 'Σ', 'Θ', 'Ξ', ' ', 'Æ', 'æ', 'ß', 'É',
    ' ', '!', '"', '#', '¤', '%', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', ':', ';', '<', '=', '>', '?',
    '¡', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O',
    'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', 'Ä', 'Ö', 'Ñ', 'Ü', '§',
    '¿', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o',
    'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', 'ä', 'ö', 'ñ', 'ü', 'à'
)

private val GSM7BIT_EXT_TABLE = charArrayOf(
    '\u000C', // Form feed
    '€',
    '\u001B', // Escape
    ' ',
    ' ',
    ' ',
    ' ',
    ' ',
    ' ',
    ' ',
    '\u0394', // Delta
    '\u03A6', // Phi
    '\u0393', // Gamma
    '\u039B', // Lambda
    '\u03A9', // Omega
    '\u03A0', // Pi
    '\u03A8', // Psi
    '\u03A3', // Sigma
    '\u0398', // Theta
    '\u039E', // Xi
    ' ',
    '\u00C6', // AE
    '\u00E6', // ae
    '\u00DF', // ss
    '\u00C9', // É
    ' ',
    ' ',
    ' ',
    ' ',
    ' ',
    ' ',
    ' ',
    ' '
)

fun decodeGsm7bit(data: ByteArray): String {
    val result = StringBuilder()
    var i = 0
    while (i < data.size) {
        val b = data[i].toInt() and 0xFF
        if (b == 0x1B && i + 1 < data.size) {
            // Escape sequence
            val ext = data[i + 1].toInt() and 0xFF
            if (ext < GSM7BIT_EXT_TABLE.size) {
                result.append(GSM7BIT_EXT_TABLE[ext])
            }
            i += 2
        } else {
            if (b < GSM7BIT_TABLE.size) {
                result.append(GSM7BIT_TABLE[b])
            } else {
                result.append(b.toChar())
            }
            i++
        }
    }
    return result.toString()
}

fun decodeUCS2(data: ByteArray): String {
    return try {
        String(data, Charsets.UTF_16BE)
    } catch (e: Exception) {
        String(data, Charsets.UTF_8)
    }
}

fun readCString(buf: ByteBuffer): String {
    val sb = StringBuilder()
    while (buf.hasRemaining()) {
        val b = buf.get()
        if (b == 0.toByte()) break
        sb.append(b.toInt().toChar())
    }
    return sb.toString()
}

fun writeCString(value: String): ByteArray {
    val bytes = value.toByteArray(Charsets.US_ASCII)
    return bytes + 0x00.toByte()
}
