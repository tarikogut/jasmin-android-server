package com.smsapi.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class HttpServerService : Service() {
    private val TAG = "HttpServerService"
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val gson = Gson()
    private val executor = Executors.newCachedThreadPool()
    private var startTime = 0L
    private var smppServer: SmppServer? = null

    data class ContactRequest(val phone: String? = null, val name: String? = null, val group: String? = null)
    data class SmppStartRequest(val port: Int? = null, val systemId: String? = null, val password: String? = null)

    companion object {
        const val DEFAULT_PORT = 8080
        var isRunning = false
            private set
        var port = DEFAULT_PORT
            private set

        fun startService(context: Context, port: Int = DEFAULT_PORT) {
            val intent = Intent(context, HttpServerService::class.java).apply {
                putExtra("port", port)
            }
            context.startForegroundService(intent)
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, HttpServerService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startTime = System.currentTimeMillis()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        port = intent?.getIntExtra("port", DEFAULT_PORT) ?: DEFAULT_PORT
        startForegroundService()
        startServer()
        return START_STICKY
    }

    override fun onDestroy() {
        smppServer?.stop()
        stopServer()
        executor.shutdown()
        isRunning = false
        super.onDestroy()
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, SmsApiApp.CHANNEL_ID)
            .setContentTitle("SMS API Server")
            .setContentText("Running on port $port")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun startServer() {
        serverThread = Thread {
            try {
                serverSocket = ServerSocket(port)
                isRunning = true
                Log.d(TAG, "Server started on port $port")

                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        executor.submit { handleClient(client) }
                    } catch (e: Exception) {
                        if (isRunning) Log.e(TAG, "Accept error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
                isRunning = false
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun stopServer() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Close error", e)
        }
        serverThread?.interrupt()
        serverThread = null
        isRunning = false
        Log.d(TAG, "Server stopped")
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val path = parts[1]

            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val key = line.substring(0, colonIndex).trim().lowercase()
                    val value = line.substring(colonIndex + 1).trim()
                    headers[key] = value
                    if (key == "content-length") contentLength = value.toIntOrNull() ?: 0
                }
            }

            val body = if (contentLength > 0) {
                val chars = CharArray(contentLength)
                reader.read(chars, 0, contentLength)
                String(chars)
            } else ""

            Log.d(TAG, "$method $path")

            val response = route(method, path, body)
            sendResponse(writer, response.first, response.second, response.third)

        } catch (e: Exception) {
            Log.e(TAG, "Handle client error", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun route(method: String, path: String, body: String): Triple<Int, String, String> {
        return when {
            path == "/" -> Triple(200, "text/html", indexHtml())
            path == "/api/status" && method == "GET" -> handleStatus()
            path == "/api/info" && method == "GET" -> handleInfo()
            path == "/api/send" && method == "POST" -> handleSend(body)
            path == "/api/send-bulk" && method == "POST" -> handleSendBulk(body)
            path == "/api/logs" && method == "GET" -> handleLogs()
            path == "/api/incoming" && method == "GET" -> handleGetIncoming()
            path.startsWith("/api/report/") && method == "GET" -> handleGetReport(path)
            path == "/api/reports" && method == "GET" -> handleGetAllReports()
            path == "/api/contacts" && method == "GET" -> handleGetContacts()
            path == "/api/contacts" && method == "POST" -> handleAddContact(body)
            path == "/api/contacts" && method == "DELETE" -> handleDeleteContact(body)
            path.startsWith("/api/contacts/") && method == "GET" -> handleGetContact(path)
            path == "/api/smpp/status" && method == "GET" -> handleSmppStatus()
            path == "/api/smpp/start" && method == "POST" -> handleSmppStart(body)
            path == "/api/smpp/stop" && method == "POST" -> handleSmppStop()
            else -> Triple(404, "application/json", """{"error":"Not found"}""")
        }
    }

    private fun handleStatus(): Triple<Int, String, String> {
        val status = mapOf(
            "running" to isRunning,
            "port" to port,
            "pendingCount" to SmsSender.getPendingCount(),
            "sentCount" to SmsSender.sentCount,
            "failedCount" to SmsSender.failedCount,
            "uptime" to (System.currentTimeMillis() - startTime) / 1000
        )
        return Triple(200, "application/json", gson.toJson(status))
    }

    private fun handleSend(body: String): Triple<Int, String, String> {
        return try {
            val request = gson.fromJson(body, SmsRequest::class.java)
            if (request.phone.isBlank() || request.message.isBlank()) {
                Triple(400, "application/json", gson.toJson(SmsResponse(false, "phone and message are required")))
            } else {
                val result = SmsSender.sendSms(this, request)
                Triple(200, "application/json", gson.toJson(result))
            }
        } catch (e: Exception) {
            Triple(400, "application/json", gson.toJson(SmsResponse(false, "Invalid JSON: ${e.message}")))
        }
    }

    private fun handleSendBulk(body: String): Triple<Int, String, String> {
        return try {
            val request = gson.fromJson(body, BulkSmsRequest::class.java)
            if (request.phones.isEmpty() || request.message.isBlank()) {
                Triple(400, "application/json", gson.toJson(BulkSmsResponse(false, "phones and message required", 0, 0, emptyList())))
            } else {
                val reportIds = mutableListOf<String>()
                var sent = 0
                var failed = 0
                for (phone in request.phones) {
                    val smsReq = SmsRequest(phone, request.message, request.callbackUrl)
                    val result = SmsSender.sendSms(this, smsReq)
                    if (result.success) {
                        sent++
                        result.reportId?.let { reportIds.add(it) }
                    } else {
                        failed++
                    }
                    if (request.delay > 0 && request.phones.indexOf(phone) < request.phones.size - 1) {
                        Thread.sleep(request.delay)
                    }
                }
                Triple(200, "application/json", gson.toJson(BulkSmsResponse(true, "Bulk SMS completed", sent, failed, reportIds)))
            }
        } catch (e: Exception) {
            Triple(400, "application/json", gson.toJson(BulkSmsResponse(false, "Invalid JSON: ${e.message}", 0, 0, emptyList())))
        }
    }

    private fun handleGetReport(path: String): Triple<Int, String, String> {
        val reportId = path.removePrefix("/api/report/")
        if (reportId.isBlank()) {
            return Triple(400, "application/json", """{"error":"reportId is required"}""")
        }
        val report = SmsSender.getReport(reportId)
        return if (report != null) {
            Triple(200, "application/json", gson.toJson(report))
        } else {
            Triple(404, "application/json", """{"error":"Report not found: $reportId"}""")
        }
    }

    private fun handleGetAllReports(): Triple<Int, String, String> {
        val reports = SmsSender.getAllReports(200)
        return Triple(200, "application/json", gson.toJson(mapOf("reports" to reports, "total" to reports.size)))
    }

    private fun handleLogs(): Triple<Int, String, String> {
        val reports = SmsSender.getAllReports(100)
        return Triple(200, "application/json", gson.toJson(mapOf("logs" to reports)))
    }

    private fun handleGetIncoming(): Triple<Int, String, String> {
        val incoming = SmsSender.db?.getIncomingSms(100) ?: emptyList()
        return Triple(200, "application/json", gson.toJson(mapOf(
            "incoming" to incoming,
            "total" to incoming.size
        )))
    }

    private fun handleInfo(): Triple<Int, String, String> {
        return try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val telMgr = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager

            val deviceName = android.os.Build.MODEL
            val manufacturer = android.os.Build.MANUFACTURER
            val androidVersion = android.os.Build.VERSION.RELEASE
            val sdkVersion = android.os.Build.VERSION.SDK_INT

            val simOperator = try { telMgr.simOperatorName ?: "" } catch (_: Exception) { "" }
            val simCountry = try { telMgr.simCountryIso ?: "" } catch (_: Exception) { "" }
            val networkOperator = try { telMgr.networkOperatorName ?: "" } catch (_: Exception) { "" }

            val phoneNumbers = mutableListOf<String>()
            try {
                val pm = packageManager
                val info = pm.getPackageInfo(packageName, android.content.pm.PackageManager.GET_ACTIVITIES)
                @Suppress("DEPRECATION")
                val imei = try { tm.deviceId } catch (_: Exception) { "N/A" }
                phoneNumbers.add(imei)
            } catch (_: Exception) {}

            val line1Number = try { tm.line1Number } catch (_: Exception) { "" }

            val signalStrength = try {
                val ss = telMgr.signalStrength
                ss?.let {
                    val cellSignal = it.cellSignalStrengths.firstOrNull()
                    cellSignal?.dbm?.toString() ?: "N/A"
                } ?: "N/A"
            } catch (_: Exception) { "N/A" }

            val networkType = try {
                when (telMgr.dataNetworkType) {
                    android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                    android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
                    android.telephony.TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                    android.telephony.TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE"
                    android.telephony.TelephonyManager.NETWORK_TYPE_GPRS -> "2G GPRS"
                    else -> "Unknown"
                }
            } catch (_: Exception) { "Unknown" }

            val simState = try {
                when (telMgr.simState) {
                    android.telephony.TelephonyManager.SIM_STATE_READY -> "Ready"
                    android.telephony.TelephonyManager.SIM_STATE_ABSENT -> "Absent"
                    android.telephony.TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN Required"
                    android.telephony.TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK Required"
                    android.telephony.TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network Locked"
                    else -> "Unknown"
                }
            } catch (_: Exception) { "Unknown" }

            val infoMap = mapOf(
                "device" to mapOf(
                    "name" to deviceName,
                    "manufacturer" to manufacturer,
                    "androidVersion" to androidVersion,
                    "sdkVersion" to sdkVersion
                ),
                "sim" to mapOf(
                    "state" to simState,
                    "operator" to simOperator,
                    "country" to simCountry,
                    "phoneNumber" to (line1Number ?: "")
                ),
                "network" to mapOf(
                    "operator" to networkOperator,
                    "type" to networkType,
                    "signalDbm" to signalStrength
                ),
                "contacts" to mapOf(
                    "total" to ContactsManager.count(),
                    "groups" to ContactsManager.getGroups()
                )
            )
            Triple(200, "application/json", gson.toJson(infoMap))
        } catch (e: Exception) {
            Triple(500, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleGetContacts(): Triple<Int, String, String> {
        val contacts = ContactsManager.getAll()
        return Triple(200, "application/json", gson.toJson(mapOf("contacts" to contacts, "total" to contacts.size)))
    }

    private fun handleAddContact(body: String): Triple<Int, String, String> {
        return try {
            val cleanBody = body.trim()
            val contactReq = gson.fromJson(cleanBody, ContactRequest::class.java)
            val phone = contactReq.phone?.trim() ?: ""
            val name = contactReq.name?.trim() ?: ""
            val group = contactReq.group?.trim() ?: "default"

            if (phone.isBlank() || name.isBlank()) {
                return Triple(400, "application/json", """{"error":"phone and name are required"}""")
            }

            val contact = ContactsManager.add(phone, name, group)
            Triple(200, "application/json", gson.toJson(mapOf("success" to true, "contact" to contact)))
        } catch (e: Exception) {
            Triple(400, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleDeleteContact(body: String): Triple<Int, String, String> {
        return try {
            val cleanBody = body.trim()
            val contactReq = gson.fromJson(cleanBody, ContactRequest::class.java)
            val phone = contactReq.phone?.trim() ?: ""
            if (phone.isBlank()) {
                return Triple(400, "application/json", """{"error":"phone is required"}""")
            }
            val deleted = ContactsManager.delete(phone)
            Triple(200, "application/json", gson.toJson(mapOf("success" to deleted)))
        } catch (e: Exception) {
            Triple(400, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleGetContact(path: String): Triple<Int, String, String> {
        val phone = path.removePrefix("/api/contacts/")
        val contact = ContactsManager.get(phone)
        return if (contact != null) {
            Triple(200, "application/json", gson.toJson(contact))
        } else {
            Triple(404, "application/json", """{"error":"Contact not found"}""")
        }
    }

    private fun handleSmppStatus(): Triple<Int, String, String> {
        val server = smppServer
        return if (server != null) {
            Triple(200, "application/json", gson.toJson(server.getStatus()))
        } else {
            Triple(200, "application/json", """{"running":false,"message":"SMPP server not initialized"}""")
        }
    }

    private fun handleSmppStart(body: String): Triple<Int, String, String> {
        return try {
            if (smppServer?.isRunning == true) {
                return Triple(400, "application/json", """{"error":"SMPP server already running"}""")
            }

            val req = gson.fromJson(body.trim(), SmppStartRequest::class.java)
            val config = SmppConfig(
                port = req.port ?: 2775,
                systemId = req.systemId ?: "smsapi",
                password = req.password ?: "password"
            )

            smppServer = SmppServer(this)
            smppServer?.start(config)
            SmsSender.smppServer = smppServer

            Triple(200, "application/json", gson.toJson(mapOf(
                "success" to true,
                "message" to "SMPP server started on port ${config.port}",
                "config" to mapOf(
                    "port" to config.port,
                    "systemId" to config.systemId,
                    "password" to config.password
                )
            )))
        } catch (e: Exception) {
            Triple(500, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleSmppStop(): Triple<Int, String, String> {
        return try {
            smppServer?.stop()
            SmsSender.smppServer = null
            smppServer = null
            Triple(200, "application/json", """{"success":true,"message":"SMPP server stopped"}""")
        } catch (e: Exception) {
            Triple(500, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun sendResponse(writer: PrintWriter, status: Int, contentType: String, body: String) {
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Error"
        }
        writer.println("HTTP/1.1 $status $statusText")
        writer.println("Content-Type: $contentType; charset=utf-8")
        writer.println("Content-Length: ${body.toByteArray().size}")
        writer.println("Access-Control-Allow-Origin: *")
        writer.println("Access-Control-Allow-Methods: GET, POST, OPTIONS")
        writer.println("Access-Control-Allow-Headers: Content-Type")
        writer.println()
        writer.print(body)
        writer.flush()
    }

    private fun indexHtml(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>SMS API</title>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body { font-family: -apple-system, sans-serif; background: #1a1a2e; color: #eee; padding: 20px; }
                    .c { max-width: 800px; margin: 0 auto; }
                    h1 { color: #fff; background: #e94560; padding: 15px 25px; border-radius: 10px; margin-bottom: 20px; text-align: center; }
                    .card { background: #16213e; border-radius: 10px; padding: 20px; margin-bottom: 15px; border: 1px solid #0f3460; }
                    .card h2 { color: #e94560; margin-bottom: 10px; font-size: 18px; }
                    .ep { background: #0f3460; padding: 10px 15px; border-radius: 6px; margin: 8px 0; font-family: monospace; font-size: 14px; }
                    .m { color: #4ecca3; font-weight: bold; }
                    pre { background: #0a0a1a; padding: 15px; border-radius: 6px; overflow-x: auto; font-size: 13px; margin-top: 10px; }
                    .ok { color: #4ecca3; }
                </style>
            </head>
            <body>
                <div class="c">
                    <h1>SMS API Server</h1>
                    <div class="card">
                        <h2>Status</h2>
                        <p class="ok">Running on port $port</p>
                    </div>
                    <div class="card">
                        <h2>Endpoints</h2>
                        <div class="ep"><span class="m">GET</span> /api/status</div>
                        <div class="ep"><span class="m">POST</span> /api/send</div>
                        <div class="ep"><span class="m">POST</span> /api/send-bulk</div>
                        <div class="ep"><span class="m">GET</span> /api/report/{reportId}</div>
                        <div class="ep"><span class="m">GET</span> /api/reports</div>
                    </div>
                    <div class="card">
                        <h2>Send SMS</h2>
                        <pre>curl -X POST http://PHONE:$port/api/send \
  -H "Content-Type: application/json" \
  -d '{"phone":"905441497005","message":"Hello!"}'</pre>
                    </div>
                    <div class="card">
                        <h2>Query Report</h2>
                        <pre>curl http://PHONE:$port/api/report/RPT-A1B2C3D4</pre>
                    </div>
                    <div class="card">
                        <h2>All Reports</h2>
                        <pre>curl http://PHONE:$port/api/reports</pre>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}
