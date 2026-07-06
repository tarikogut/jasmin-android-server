package com.smsapi.app

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class SmppConfig(
    val port: Int = 2775,
    val systemId: String = "smsapi",
    val password: String = "password",
    val maxConnections: Int = 10,
    val version: Int = SmppPdu.SMPP_VERSION_3_4,
    val enableDeliveryReports: Boolean = true
)

data class SmppSession(
    val id: String,
    val socket: Socket,
    var systemId: String = "",
    var bound: Boolean = false,
    var boundAs: Int = 0, // BIND_TRANSMITTER, BIND_RECEIVER, BIND_TRANSCEIVER
    var boundAt: Long = 0L,
    var lastActivity: Long = 0L,
    var messagesReceived: Int = 0,
    var interfaceVersion: Int = SmppPdu.SMPP_VERSION_3_4
)

class SmppServer(private val context: Context) {
    companion object {
        private const val TAG = "SmppServer"
    }

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val sessions = ConcurrentHashMap<String, SmppSession>()
    private val executor = Executors.newCachedThreadPool()
    private val sessionCounter = AtomicInteger(0)
    private var startTime = 0L

    // Long SMS reassembly: key = (reference, source) -> list of parts
    private val multipartBuffers = ConcurrentHashMap<String, MultipartBuffer>()

    var isRunning = false
        private set
    var config = SmppConfig()
        private set
    var lastSubmitSm: SmppSubmitSm? = null
        private set
    var totalReceived = 0
        private set
    var totalBound = 0
        private set

    fun start(config: SmppConfig) {
        if (isRunning) stop()
        this.config = config
        try {
            serverSocket = ServerSocket(config.port)
            isRunning = true
            startTime = System.currentTimeMillis()
            Log.d(TAG, "SMPP Server started on port ${config.port} (v${if (config.version == 0x34) "3.4" else "5.0"})")

            serverThread = Thread {
                while (isRunning && !Thread.currentThread().isInterrupted) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        if (sessions.size >= config.maxConnections) {
                            Log.w(TAG, "Max connections reached, rejecting")
                            client.close()
                            continue
                        }
                        handleNewConnection(client)
                    } catch (e: Exception) {
                        if (isRunning) Log.e(TAG, "Accept error", e)
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SMPP server", e)
            isRunning = false
        }
    }

    fun stop() {
        isRunning = false
        sessions.values.forEach { session ->
            try { session.socket.close() } catch (_: Exception) {}
        }
        sessions.clear()
        multipartBuffers.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverThread?.interrupt()
        serverThread = null
        Log.d(TAG, "SMPP Server stopped")
    }

    private fun handleNewConnection(socket: Socket) {
        val sessionId = "SMPP-${sessionCounter.incrementAndGet()}"
        val session = SmppSession(sessionId, socket)
        sessions[sessionId] = session
        Log.d(TAG, "New connection: $sessionId from ${socket.inetAddress.hostAddress}")

        executor.submit {
            try {
                socket.soTimeout = 30000
                socket.tcpNoDelay = true
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                Log.d(TAG, "Session $sessionId ready, waiting for data...")

                while (isRunning && !socket.isClosed) {
                    val len = input.readInt()
                    if (len < 16 || len > 65536) {
                        Log.e(TAG, "Invalid PDU length: $len")
                        break
                    }

                    val remaining = len - 4
                    val pduData = ByteArray(len)
                    pduData[0] = ((len shr 24) and 0xFF).toByte()
                    pduData[1] = ((len shr 16) and 0xFF).toByte()
                    pduData[2] = ((len shr 8) and 0xFF).toByte()
                    pduData[3] = (len and 0xFF).toByte()
                    input.readFully(pduData, 4, remaining)

                    val pdu = SmppPdu.parse(pduData)
                    if (pdu != null) {
                        session.lastActivity = System.currentTimeMillis()
                        Log.d(TAG, "Session $sessionId << ${pdu.getCommandName()} (seq=${pdu.sequenceNumber})")
                        val response = handlePdu(session, pdu)
                        if (response != null) {
                            Log.d(TAG, "Session $sessionId >> ${response.getCommandName()} status=${response.commandStatus}")
                            output.write(response.toByteArray())
                            output.flush()
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Session $sessionId error", e)
            } finally {
                session.bound = false
                sessions.remove(sessionId)
                try { socket.close() } catch (_: Exception) {}
                Log.d(TAG, "Session disconnected: $sessionId")
            }
        }
    }

    private fun handlePdu(session: SmppSession, pdu: SmppPdu): SmppPdu? {
        return when (pdu.commandId) {
            SmppPdu.BIND_RECEIVER,
            SmppPdu.BIND_TRANSMITTER,
            SmppPdu.BIND_TRANSCEIVER -> handleBind(session, pdu)
            SmppPdu.UNBIND -> handleUnbind(session, pdu)
            SmppPdu.SUBMIT_SM -> handleSubmitSm(session, pdu)
            SmppPdu.ENQUIRE_LINK -> handleEnquireLink(pdu)
            SmppPdu.DELIVER_SM_RESP -> handleDeliverSmResp(session, pdu)
            // Response PDUs from client - just log, don't respond
            SmppPdu.BIND_RECEIVER_RESP,
            SmppPdu.BIND_TRANSMITTER_RESP,
            SmppPdu.BIND_TRANSCEIVER_RESP,
            SmppPdu.ENQUIRE_LINK_RESP,
            SmppPdu.SUBMIT_SM_RESP,
            SmppPdu.UNBIND_RESP -> {
                Log.d(TAG, "Response PDU received from ${session.id}: ${pdu.getCommandName()} status=${pdu.commandStatus}")
                null
            }
            else -> {
                Log.w(TAG, "Unknown command: 0x${pdu.commandId.toString(16)}")
                SmppPdu.buildGenericNack(SmppPdu.STATUS_INVALID_CMD, pdu.sequenceNumber)
            }
        }
    }

    private fun handleBind(session: SmppSession, pdu: SmppPdu): SmppPdu {
        val bindReq = SmppBindRequest.parse(pdu.body)
        Log.d(TAG, "BIND from ${session.id}: systemId=${bindReq.systemId}, type=${bindReq.systemType}, version=0x${bindReq.interfaceVersion.toString(16)}")

        val status = if (bindReq.systemId == config.systemId && bindReq.password == config.password) {
            session.systemId = bindReq.systemId
            session.bound = true
            session.boundAs = pdu.commandId
            session.boundAt = System.currentTimeMillis()
            session.interfaceVersion = bindReq.interfaceVersion
            totalBound++
            SmppPdu.STATUS_OK
        } else {
            Log.w(TAG, "Bind failed for ${bindReq.systemId}: wrong credentials")
            SmppPdu.STATUS_BIND_FAIL
        }

        // Build optional params for v5
        val optionalParams = mutableListOf<SmppTlv>()
        if (status == SmppPdu.STATUS_OK) {
            // sc_interface_version TLV
            optionalParams.add(SmppTlv(
                SmppPdu.TLV_SC_INTERFACE_VERSION,
                1,
                byteArrayOf(config.version.toByte())
            ))
        }

        return SmppPdu.buildBindResp(
            pdu.commandId,
            status,
            pdu.sequenceNumber,
            config.systemId,
            config.version,
            optionalParams
        )
    }

    private fun handleUnbind(session: SmppSession, pdu: SmppPdu): SmppPdu {
        session.bound = false
        Log.d(TAG, "UNBIND from ${session.id}")
        return SmppPdu(16, SmppPdu.UNBIND_RESP, SmppPdu.STATUS_OK, pdu.sequenceNumber)
    }

    private fun handleSubmitSm(session: SmppSession, pdu: SmppPdu): SmppPdu {
        val submitSm = SmppSubmitSm.parse(pdu.body)
        session.messagesReceived++
        totalReceived++
        lastSubmitSm = submitSm

        val messageText = submitSm.getMessageText()
        Log.d(TAG, "SUBMIT_SM from ${session.id}: ${submitSm.sourceAddr} -> ${submitSm.destAddr}, dcs=${submitSm.getDataCodingName()}, len=${submitSm.smLength}, text=$messageText")

        // Handle concatenated SMS (long SMS)
        val finalMessage = if (submitSm.isConcatenated()) {
            val udh = submitSm.getUdhInfo()
            if (udh != null) {
                val key = "${udh.reference}_${submitSm.sourceAddr}"
                val buffer = multipartBuffers.getOrPut(key) {
                    MultipartBuffer(udh.totalParts, submitSm.destAddr, submitSm.sourceAddr, submitSm.dataCoding)
                }

                // Store the message part
                val bodyMessage = getMessageBody(submitSm)
                buffer.parts[udh.partNumber] = bodyMessage

                Log.d(TAG, "Long SMS part ${udh.partNumber}/${udh.totalParts} for ref=$key")

                if (buffer.isComplete()) {
                    multipartBuffers.remove(key)
                    val assembled = buffer.assemble()
                    Log.d(TAG, "Long SMS assembled: $assembled")
                    assembled
                } else {
                    // Still waiting for more parts, return success
                    val messageId = "PART-${System.currentTimeMillis()}"
                    return SmppPdu.buildSubmitSmResp(SmppPdu.STATUS_OK, messageId, pdu.sequenceNumber)
                }
            } else {
                messageText
            }
        } else {
            messageText
        }

        // Send SMS via Android
        val request = SmsRequest(
            phone = submitSm.destAddr,
            message = finalMessage
        )
        val result = SmsSender.sendSms(context, request)

        val messageId = result.reportId ?: "MSG-${System.currentTimeMillis()}"

        // Store SMPP session for delivery report
        if (result.success && result.reportId != null) {
            SmsSender.setSmppMessageId(result.reportId, messageId, session.id)
        }

        return SmppPdu.buildSubmitSmResp(
            if (result.success) SmppPdu.STATUS_OK else SmppPdu.STATUS_INVL_MSG_FLD,
            messageId,
            pdu.sequenceNumber
        )
    }

    private fun getMessageBody(submitSm: SmppSubmitSm): String {
        // Extract message body after UDH if present
        val udhInfo = submitSm.getUdhInfo()
        if (udhInfo != null && submitSm.shortMessage.isNotEmpty()) {
            val udhLen = submitSm.shortMessage[0].toInt() and 0xFF
            val msgBody = submitSm.shortMessage.copyOfRange(udhLen + 1, submitSm.shortMessage.size)
            val dataCoding = submitSm.dataCoding

            when (dataCoding) {
                0x08, 0x0D -> {
                    // UCS2
                    return try {
                        String(msgBody, Charsets.UTF_16BE)
                    } catch (e: Exception) {
                        String(msgBody, Charsets.UTF_8)
                    }
                }
                else -> {
                    // GSM 7bit or ASCII
                    val isGsm7bit = (dataCoding and 0x0F) == 0x00 || (dataCoding and 0x0F) == 0x02
                    return if (isGsm7bit) {
                        decodeGsm7bit(msgBody)
                    } else {
                        String(msgBody, Charsets.UTF_8)
                    }
                }
            }
        }
        return submitSm.getMessageText()
    }

    private fun handleEnquireLink(pdu: SmppPdu): SmppPdu {
        return SmppPdu(16, SmppPdu.ENQUIRE_LINK_RESP, SmppPdu.STATUS_OK, pdu.sequenceNumber)
    }

    private fun handleDeliverSmResp(session: SmppSession, pdu: SmppPdu): SmppPdu? {
        Log.d(TAG, "DELIVER_SM_RESP from ${session.id}: status=${pdu.commandStatus}")
        return null // No response needed
    }

    // Send DELIVER_SM (delivery report) to all bound RECEIVER/TRANSCEIVER sessions
    fun sendDeliveryReport(reportId: String, phone: String, message: String, status: String) {
        if (!config.enableDeliveryReports) return

        // Run on background thread to avoid NetworkOnMainThreadException
        Thread {
            try {
                val esmClass = 0x04 // Delivery receipt
                val receiptMessage = buildDeliveryReceipt(reportId, phone, status)

                val deliverSm = SmppPdu.buildDeliverSm(
                    sourceAddr = phone,
                    destAddr = config.systemId,
                    message = receiptMessage.toByteArray(Charsets.US_ASCII),
                    esmClass = esmClass,
                    dataCoding = 0x00,
                    registeredDelivery = 0x00,
                    sequenceNumber = sessionCounter.incrementAndGet()
                )

                for ((_, session) in sessions) {
                    if (session.bound && (session.boundAs == SmppPdu.BIND_RECEIVER || session.boundAs == SmppPdu.BIND_TRANSCEIVER)) {
                        try {
                            val output = DataOutputStream(session.socket.getOutputStream())
                            output.write(deliverSm.toByteArray())
                            output.flush()
                            Log.d(TAG, "DELIVER_SM sent to ${session.id}: reportId=$reportId, status=$status")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send DELIVER_SM to ${session.id}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send DELIVER_SM", e)
            }
        }.start()
    }

    private fun buildDeliveryReceipt(reportId: String, phone: String, status: String): String {
        val statusCode = when (status) {
            "DELIVERED" -> "2"
            "SENT" -> "1"
            "FAILED" -> "8"
            else -> "3"
        }
        val timestamp = java.text.SimpleDateFormat("yyMMddHHmmss", java.util.Locale.US).format(java.util.Date())
        return "id:$reportId sub:001 dlvrd:0$statusCode submit_date:$timestamp done_date:$timestamp stat:$status err:0 Text:0"
    }

    // Forward incoming SMS to all bound RECEIVER/TRANSCEIVER sessions via DELIVER_SM
    fun forwardIncomingSms(phone: String, message: String, contactName: String?, dbId: Long) {
        Thread {
            try {
                // Use esmClass 0x00 for regular message (not delivery receipt)
                // The message is an incoming MO SMS, forwarded as MT to SMPP clients
                val deliverSm = SmppPdu.buildDeliverSm(
                    sourceAddr = phone,
                    destAddr = config.systemId,
                    message = message.toByteArray(Charsets.UTF_8),
                    esmClass = 0x00, // Regular message
                    dataCoding = 0x03, // UTF-8
                    registeredDelivery = 0x00,
                    sequenceNumber = sessionCounter.incrementAndGet()
                )

                var forwarded = 0
                for ((_, session) in sessions) {
                    if (session.bound && (session.boundAs == SmppPdu.BIND_RECEIVER || session.boundAs == SmppPdu.BIND_TRANSCEIVER)) {
                        try {
                            val output = DataOutputStream(session.socket.getOutputStream())
                            output.write(deliverSm.toByteArray())
                            output.flush()
                            forwarded++
                            Log.d(TAG, "DELIVER_SM (incoming) sent to ${session.id}: from=$phone")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to forward incoming SMS to ${session.id}", e)
                        }
                    }
                }

                // Mark as forwarded in database
                if (forwarded > 0) {
                    SmsSender.db?.markIncomingForwarded(dbId)
                }

                Log.d(TAG, "Incoming SMS forwarded to $forwarded sessions: from=$phone")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to forward incoming SMS", e)
            }
        }.start()
    }

    fun getStatus(): Map<String, Any> = mapOf(
        "running" to isRunning,
        "port" to config.port,
        "systemId" to config.systemId,
        "version" to if (config.version == 0x34) "3.4" else "5.0",
        "activeSessions" to sessions.size,
        "totalBound" to totalBound,
        "totalReceived" to totalReceived,
        "uptime" to if (isRunning) (System.currentTimeMillis() - startTime) / 1000 else 0,
        "sessions" to sessions.values.map { s ->
            mapOf(
                "id" to s.id,
                "systemId" to s.systemId,
                "bound" to s.bound,
                "boundAs" to when (s.boundAs) {
                    SmppPdu.BIND_TRANSMITTER -> "transmitter"
                    SmppPdu.BIND_RECEIVER -> "receiver"
                    SmppPdu.BIND_TRANSCEIVER -> "transceiver"
                    else -> "none"
                },
                "interfaceVersion" to "0x${s.interfaceVersion.toString(16)}",
                "messagesReceived" to s.messagesReceived,
                "lastActivity" to s.lastActivity
            )
        }
    )

    fun getSessions(): Collection<SmppSession> = sessions.values
}

data class MultipartBuffer(
    val totalParts: Int,
    val destAddr: String,
    val sourceAddr: String,
    val dataCoding: Int,
    val parts: ConcurrentHashMap<Int, String> = ConcurrentHashMap()
) {
    fun isComplete(): Boolean = parts.size >= totalParts

    fun assemble(): String {
        val sb = StringBuilder()
        for (i in 1..totalParts) {
            sb.append(parts[i] ?: "")
        }
        return sb.toString()
    }
}
