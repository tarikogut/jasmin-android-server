package com.smsapi.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SmppPdu(
    val commandLength: Int,
    val commandId: Int,
    val commandStatus: Int,
    val sequenceNumber: Int,
    val body: ByteArray = byteArrayOf()
) {
    companion object {
        const val BIND_RECEIVER = 0x00000001
        const val BIND_RECEIVER_RESP = -0x7FFFFFFF // 0x80000001
        const val BIND_TRANSMITTER = 0x00000002
        const val BIND_TRANSMITTER_RESP = -0x7FFFFFFE // 0x80000002
        const val BIND_TRANSCEIVER = 0x00000009
        const val BIND_TRANSCEIVER_RESP = -0x7FFFFFF7 // 0x80000009
        const val UNBIND = 0x00000006
        const val UNBIND_RESP = -0x7FFFFFFA // 0x80000006
        const val SUBMIT_SM = 0x00000004
        const val SUBMIT_SM_RESP = -0x7FFFFFFC // 0x80000004
        const val DELIVER_SM = 0x00000005
        const val DELIVER_SM_RESP = -0x7FFFB // 0x80000005
        const val ENQUIRE_LINK = 0x00000015
        const val ENQUIRE_LINK_RESP = -0x7FFFFFFB // 0x80000015
        const val GENERIC_NACK = -0x80000000.toInt() // 0x80000000

        const val STATUS_OK = 0x00000000
        const val STATUS_INVALID_CMD = 0x00000003
        const val STATUS_BIND_FAIL = 0x00000005
        const val STATUS_INVL_MSG_FLD = 0x00000001

        fun parse(data: ByteArray): SmppPdu? {
            if (data.size < 16) return null
            val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val commandLength = bb.getInt()
            val commandId = bb.getInt()
            val commandStatus = bb.getInt()
            val sequenceNumber = bb.getInt()
            val body = if (data.size > 16) data.copyOfRange(16, commandLength) else byteArrayOf()
            return SmppPdu(commandLength, commandId, commandStatus, sequenceNumber, body)
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
}

data class SmppBindRequest(
    val systemId: String,
    val password: String,
    val systemType: String,
    val interfaceVersion: Int
) {
    companion object {
        fun parse(body: ByteArray): SmppBindRequest {
            val buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
            val systemId = readCString(buf)
            val password = readCString(buf)
            val systemType = readCString(buf)
            val interfaceVersion = if (buf.hasRemaining()) buf.get().toInt() and 0xFF else 0x34
            return SmppBindRequest(systemId, password, systemType, interfaceVersion)
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
    val shortMessage: ByteArray
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
            val shortMessage = ByteArray(smLength)
            buf.get(shortMessage)
            return SmppSubmitSm(
                serviceType, sourceAddrTon, sourceAddrNpi, sourceAddr,
                destAddrTon, destAddrNpi, destAddr,
                esmClass, protocolId, priorityFlag,
                scheduleDeliveryTime, validityPeriod,
                registeredDelivery, replaceIfPresentFlag,
                dataCoding, smDefaultMsgId, smLength, shortMessage
            )
        }
    }

    fun getMessageText(): String {
        return when (dataCoding) {
            0x00 -> String(shortMessage, Charsets.US_ASCII)
            0x08 -> String(shortMessage, Charsets.UTF_16BE)
            else -> String(shortMessage, Charsets.UTF_8)
        }
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
