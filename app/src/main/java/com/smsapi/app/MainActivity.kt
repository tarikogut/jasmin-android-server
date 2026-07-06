package com.smsapi.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.smsapi.app.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val PERMISSION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestPermissions()
        setupUI()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setupUI() {
        binding.etPort.setText(HttpServerService.port.toString())

        binding.btnStart.setOnClickListener {
            val port = binding.etPort.text.toString().toIntOrNull() ?: 8080
            HttpServerService.startService(this, port)
            Thread.sleep(500)
            updateStatus()
            Toast.makeText(this, "Server started on port $port", Toast.LENGTH_SHORT).show()
        }

        binding.btnStop.setOnClickListener {
            HttpServerService.stopService(this)
            Thread.sleep(300)
            updateStatus()
            Toast.makeText(this, "Server stopped", Toast.LENGTH_SHORT).show()
        }

        binding.btnRefresh.setOnClickListener {
            updateStatus()
        }

        binding.btnTestSend.setOnClickListener {
            testSendSms()
        }
    }

    private fun updateStatus() {
        val ip = getDeviceIpAddress()
        binding.tvIpAddress.text = "IP: $ip"
        binding.tvPort.text = "Port: ${HttpServerService.port}"
        binding.tvUrl.text = "http://$ip:${HttpServerService.port}"

        val status = if (HttpServerService.isRunning) "RUNNING" else "STOPPED"
        binding.tvStatus.text = "Status: $status"
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (HttpServerService.isRunning) android.R.color.holo_green_dark
                else android.R.color.holo_red_dark
            )
        )

        binding.tvStats.text = buildString {
            append("Sent: ${SmsSender.sentCount} | ")
            append("Failed: ${SmsSender.failedCount} | ")
            append("Pending: ${SmsSender.getPendingCount()}")
        }

        val logs = SmsSender.getAllReports(10).joinToString("\n") { entry ->
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val time = sdf.format(Date(entry.timestamp))
            "[${entry.reportId}] $time ${entry.phone}: ${entry.status}"
        }
        binding.tvLogs.text = if (logs.isEmpty()) "No logs yet" else logs
    }

    private fun testSendSms() {
        val phone = binding.etTestPhone.text.toString().trim()
        val message = binding.etTestMessage.text.toString().trim()

        if (phone.isEmpty() || message.isEmpty()) {
            Toast.makeText(this, "Enter phone and message", Toast.LENGTH_SHORT).show()
            return
        }

        val request = SmsRequest(phone, message)
        val result = SmsSender.sendSms(this, request)
        Toast.makeText(
            this,
            if (result.success) "SMS queued: ${result.message}" else "Failed: ${result.message}",
            Toast.LENGTH_SHORT
        ).show()
        updateStatus()
    }

    private fun getDeviceIpAddress(): String {
        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ip = wifiManager.connectionInfo.ipAddress
            return String.format(
                "%d.%d.%d.%d",
                ip and 0xff,
                ip shr 8 and 0xff,
                ip shr 16 and 0xff,
                ip shr 24 and 0xff
            )
        } catch (e: Exception) {
            return "127.0.0.1"
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(this, "Some permissions denied. SMS may not work.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
