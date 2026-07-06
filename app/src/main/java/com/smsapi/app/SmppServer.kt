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
    val maxConnections: Int = 10
)

data class SmppSession(
    val id: String,
    val socket: Socket,
    var systemId: String = "",
    var bound: Boolean = false,
    var boundAt: Long = 0L,
    var lastActivity: Long = 0L,
    var messagesReceived: Int = 0
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
            Log.d(TAG, "SMPP Server started on port ${config.port}")

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
                    Log.d(TAG, "Session $sessionId received PDU length: $len")
                    if (len < 16 || len > 65536) {
                        Log.e(TAG, "Invalid PDU length: $len")
                        break
                    }

                    val remaining = len - 4
                    val pduData = ByteArray(len)
                    // First 4 bytes already read (the length field itself)
                    pduData[0] = ((len shr 24) and 0xFF).toByte()
                    pduData[1] = ((len shr 16) and 0xFF).toByte()
                    pduData[2] = ((len shr 8) and 0xFF).toByte()
                    pduData[3] = (len and 0xFF).toByte()
                    input.readFully(pduData, 4, remaining)
                    Log.d(TAG, "Session $sessionId received PDU: ${pduData.take(20).map { String.format("%02X", it) }.joinToString(" ")}")

                    val pdu = SmppPdu.parse(pduData)
                    if (pdu != null) {
                        session.lastActivity = System.currentTimeMillis()
                        val response = handlePdu(session, pdu)
                        if (response != null) {
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
            else -> {
                Log.w(TAG, "Unknown command: 0x${pdu.commandId.toString(16)}")
                SmppPdu(
                    16, SmppPdu.GENERIC_NACK,
                    SmppPdu.STATUS_INVALID_CMD, pdu.sequenceNumber
                )
            }
        }
    }

    private fun handleBind(session: SmppSession, pdu: SmppPdu): SmppPdu {
        val bindReq = SmppBindRequest.parse(pdu.body)
        Log.d(TAG, "BIND from ${session.id}: systemId=${bindReq.systemId}, type=${bindReq.systemType}")

        val status = if (bindReq.systemId == config.systemId && bindReq.password == config.password) {
            session.systemId = bindReq.systemId
            session.bound = true
            session.boundAt = System.currentTimeMillis()
            totalBound++
            SmppPdu.STATUS_OK
        } else {
            Log.w(TAG, "Bind failed for ${bindReq.systemId}: wrong credentials")
            SmppPdu.STATUS_BIND_FAIL
        }

        val respId = when (pdu.commandId) {
            SmppPdu.BIND_RECEIVER -> SmppPdu.BIND_RECEIVER_RESP
            SmppPdu.BIND_TRANSMITTER -> SmppPdu.BIND_TRANSMITTER_RESP
            SmppPdu.BIND_TRANSCEIVER -> SmppPdu.BIND_TRANSCEIVER_RESP
            else -> SmppPdu.BIND_RECEIVER_RESP
        }

        val body = writeCString(config.systemId)
        return SmppPdu(16 + body.size, respId, status, pdu.sequenceNumber, body)
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
        Log.d(TAG, "SUBMIT_SM from ${session.id}: ${submitSm.sourceAddr} -> ${submitSm.destAddr}: $messageText")

        val request = SmsRequest(
            phone = submitSm.destAddr,
            message = messageText
        )
        val result = SmsSender.sendSms(context, request)

        val messageId = result.reportId ?: "MSG-${System.currentTimeMillis()}"

        val body = writeCString(messageId)
        return SmppPdu(
            16 + body.size,
            SmppPdu.SUBMIT_SM_RESP,
            if (result.success) SmppPdu.STATUS_OK else SmppPdu.STATUS_INVL_MSG_FLD,
            pdu.sequenceNumber,
            body
        )
    }

    private fun handleEnquireLink(pdu: SmppPdu): SmppPdu {
        return SmppPdu(16, SmppPdu.ENQUIRE_LINK_RESP, SmppPdu.STATUS_OK, pdu.sequenceNumber)
    }

    fun getStatus(): Map<String, Any> = mapOf(
        "running" to isRunning,
        "port" to config.port,
        "systemId" to config.systemId,
        "activeSessions" to sessions.size,
        "totalBound" to totalBound,
        "totalReceived" to totalReceived,
        "uptime" to if (isRunning) (System.currentTimeMillis() - startTime) / 1000 else 0,
        "sessions" to sessions.values.map { s ->
            mapOf(
                "id" to s.id,
                "systemId" to s.systemId,
                "bound" to s.bound,
                "messagesReceived" to s.messagesReceived,
                "lastActivity" to s.lastActivity
            )
        }
    )
}
