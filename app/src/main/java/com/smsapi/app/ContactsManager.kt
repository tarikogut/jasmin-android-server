package com.smsapi.app

import android.content.Context
import android.util.Log

data class Contact(
    val phone: String,
    val name: String,
    val group: String = "default",
    val createdAt: Long = System.currentTimeMillis()
)

object ContactsManager {
    private const val TAG = "ContactsManager"
    private var db: SmsDatabase? = null

    fun init(context: Context) {
        db = SmsDatabase(context)
        Log.d(TAG, "ContactsManager initialized: ${db?.getContactCount()} contacts")
    }

    fun add(phone: String, name: String, group: String = "default"): Contact {
        val contact = Contact(phone.trim(), name.trim(), group.trim())
        db?.insertContact(contact)
        return contact
    }

    fun get(phone: String): Contact? = db?.getContact(phone.trim())

    fun getName(phone: String): String? = db?.getContact(phone.trim())?.name

    fun getAll(): List<Contact> = db?.getAllContacts() ?: emptyList()

    fun getByGroup(group: String): List<Contact> = db?.getContactsByGroup(group) ?: emptyList()

    fun getGroups(): List<String> = db?.getContactGroups() ?: emptyList()

    fun update(phone: String, name: String? = null, group: String? = null): Contact? {
        val existing = db?.getContact(phone.trim()) ?: return null
        val updated = existing.copy(
            name = name?.trim() ?: existing.name,
            group = group?.trim() ?: existing.group
        )
        db?.insertContact(updated) // UPSERT
        return updated
    }

    fun delete(phone: String): Boolean = db?.deleteContact(phone.trim()) ?: false

    fun search(query: String): List<Contact> {
        val q = query.lowercase()
        return db?.getAllContacts()?.filter {
            it.name.lowercase().contains(q) || it.phone.contains(q)
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    fun count(): Int = db?.getContactCount() ?: 0
}
