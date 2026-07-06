package com.smsapi.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SmsDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "sms_api.db"
        private const val DB_VERSION = 1

        private const val TABLE_REPORTS = "reports"
        private const val TABLE_CONTACTS = "contacts"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_REPORTS (
                report_id TEXT PRIMARY KEY,
                phone TEXT NOT NULL,
                contact_name TEXT,
                message TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING',
                error_code INTEGER,
                timestamp INTEGER NOT NULL,
                sent_at INTEGER,
                delivered_at INTEGER,
                smpp_message_id TEXT,
                smpp_session_id TEXT
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_CONTACTS (
                phone TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                group_name TEXT NOT NULL DEFAULT 'default',
                created_at INTEGER NOT NULL
            )
        """)

        db.execSQL("CREATE INDEX idx_reports_phone ON $TABLE_REPORTS(phone)")
        db.execSQL("CREATE INDEX idx_reports_status ON $TABLE_REPORTS(status)")
        db.execSQL("CREATE INDEX idx_reports_timestamp ON $TABLE_REPORTS(timestamp DESC)")
        db.execSQL("CREATE INDEX idx_contacts_group ON $TABLE_CONTACTS(group_name)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_REPORTS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CONTACTS")
        onCreate(db)
    }

    // ==================== REPORTS ====================

    fun insertReport(entry: ReportEntry) {
        val values = ContentValues().apply {
            put("report_id", entry.reportId)
            put("phone", entry.phone)
            put("contact_name", entry.contactName)
            put("message", entry.message)
            put("status", entry.status)
            put("error_code", entry.errorCode)
            put("timestamp", entry.timestamp)
            put("sent_at", entry.sentAt)
            put("delivered_at", entry.deliveredAt)
            put("smpp_message_id", entry.smppMessageId)
            put("smpp_session_id", entry.smppSessionId)
        }
        writableDatabase.insertWithOnConflict(TABLE_REPORTS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateReport(entry: ReportEntry) {
        val values = ContentValues().apply {
            put("status", entry.status)
            put("error_code", entry.errorCode)
            put("sent_at", entry.sentAt)
            put("delivered_at", entry.deliveredAt)
            put("contact_name", entry.contactName)
            put("smpp_message_id", entry.smppMessageId)
            put("smpp_session_id", entry.smppSessionId)
        }
        writableDatabase.update(TABLE_REPORTS, values, "report_id = ?", arrayOf(entry.reportId))
    }

    fun getReport(reportId: String): ReportEntry? {
        val cursor = readableDatabase.query(
            TABLE_REPORTS, null, "report_id = ?", arrayOf(reportId),
            null, null, null
        )
        return cursor.use { if (it.moveToFirst()) cursorToReport(it) else null }
    }

    fun getAllReports(limit: Int = 100): List<ReportEntry> {
        val reports = mutableListOf<ReportEntry>()
        val cursor = readableDatabase.query(
            TABLE_REPORTS, null, null, null,
            null, null, "timestamp DESC", limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                reports.add(cursorToReport(it))
            }
        }
        return reports
    }

    fun getReportsByStatus(status: String): List<ReportEntry> {
        val reports = mutableListOf<ReportEntry>()
        val cursor = readableDatabase.query(
            TABLE_REPORTS, null, "status = ?", arrayOf(status),
            null, null, "timestamp DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                reports.add(cursorToReport(it))
            }
        }
        return reports
    }

    fun getReportsByPhone(phone: String): List<ReportEntry> {
        val reports = mutableListOf<ReportEntry>()
        val cursor = readableDatabase.query(
            TABLE_REPORTS, null, "phone = ?", arrayOf(phone),
            null, null, "timestamp DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                reports.add(cursorToReport(it))
            }
        }
        return reports
    }

    fun getReportCount(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_REPORTS", null)
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun getStatusCounts(): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        val cursor = readableDatabase.rawQuery(
            "SELECT status, COUNT(*) FROM $TABLE_REPORTS GROUP BY status", null
        )
        cursor.use {
            while (it.moveToNext()) {
                map[it.getString(0)] = it.getInt(1)
            }
        }
        return map
    }

    fun deleteOldReports(olderThanMs: Long): Int {
        val cutoff = System.currentTimeMillis() - olderThanMs
        return writableDatabase.delete(TABLE_REPORTS, "timestamp < ?", arrayOf(cutoff.toString()))
    }

    private fun cursorToReport(cursor: Cursor): ReportEntry {
        return ReportEntry(
            reportId = cursor.getString(cursor.getColumnIndexOrThrow("report_id")),
            phone = cursor.getString(cursor.getColumnIndexOrThrow("phone")),
            contactName = cursor.getString(cursor.getColumnIndexOrThrow("contact_name")),
            message = cursor.getString(cursor.getColumnIndexOrThrow("message")),
            status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
            errorCode = if (cursor.isNull(cursor.getColumnIndexOrThrow("error_code"))) null
                        else cursor.getInt(cursor.getColumnIndexOrThrow("error_code")),
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
            sentAt = if (cursor.isNull(cursor.getColumnIndexOrThrow("sent_at"))) null
                     else cursor.getLong(cursor.getColumnIndexOrThrow("sent_at")),
            deliveredAt = if (cursor.isNull(cursor.getColumnIndexOrThrow("delivered_at"))) null
                          else cursor.getLong(cursor.getColumnIndexOrThrow("delivered_at")),
            smppMessageId = cursor.getString(cursor.getColumnIndexOrThrow("smpp_message_id")),
            smppSessionId = cursor.getString(cursor.getColumnIndexOrThrow("smpp_session_id"))
        )
    }

    // ==================== CONTACTS ====================

    fun insertContact(contact: Contact) {
        val values = ContentValues().apply {
            put("phone", contact.phone)
            put("name", contact.name)
            put("group_name", contact.group)
            put("created_at", contact.createdAt)
        }
        writableDatabase.insertWithOnConflict(TABLE_CONTACTS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getContact(phone: String): Contact? {
        val cursor = readableDatabase.query(
            TABLE_CONTACTS, null, "phone = ?", arrayOf(phone),
            null, null, null
        )
        return cursor.use { if (it.moveToFirst()) cursorToContact(it) else null }
    }

    fun getAllContacts(): List<Contact> {
        val contacts = mutableListOf<Contact>()
        val cursor = readableDatabase.query(
            TABLE_CONTACTS, null, null, null,
            null, null, "name ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                contacts.add(cursorToContact(it))
            }
        }
        return contacts
    }

    fun getContactsByGroup(group: String): List<Contact> {
        val contacts = mutableListOf<Contact>()
        val cursor = readableDatabase.query(
            TABLE_CONTACTS, null, "group_name = ?", arrayOf(group),
            null, null, "name ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                contacts.add(cursorToContact(it))
            }
        }
        return contacts
    }

    fun getContactGroups(): List<String> {
        val groups = mutableListOf<String>()
        val cursor = readableDatabase.rawQuery(
            "SELECT DISTINCT group_name FROM $TABLE_CONTACTS ORDER BY group_name", null
        )
        cursor.use {
            while (it.moveToNext()) {
                groups.add(it.getString(0))
            }
        }
        return groups
    }

    fun deleteContact(phone: String): Boolean {
        return writableDatabase.delete(TABLE_CONTACTS, "phone = ?", arrayOf(phone)) > 0
    }

    fun getContactCount(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_CONTACTS", null)
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun cursorToContact(cursor: Cursor): Contact {
        return Contact(
            phone = cursor.getString(cursor.getColumnIndexOrThrow("phone")),
            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
            group = cursor.getString(cursor.getColumnIndexOrThrow("group_name")),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
        )
    }
}
