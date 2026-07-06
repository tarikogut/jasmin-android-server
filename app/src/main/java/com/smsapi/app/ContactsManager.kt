package com.smsapi.app

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class Contact(
    val phone: String,
    val name: String,
    val group: String = "default",
    val createdAt: Long = System.currentTimeMillis()
)

object ContactsManager {
    private const val PREFS_NAME = "sms_api_contacts"
    private const val KEY_CONTACTS = "contacts"
    private val gson = Gson()
    private var prefs: SharedPreferences? = null
    private val contacts = mutableMapOf<String, Contact>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        val json = prefs?.getString(KEY_CONTACTS, null)
        if (json != null) {
            val type = object : TypeToken<Map<String, Contact>>() {}.type
            val loaded: Map<String, Contact> = gson.fromJson(json, type)
            contacts.clear()
            contacts.putAll(loaded)
        }
    }

    private fun save() {
        prefs?.edit()?.putString(KEY_CONTACTS, gson.toJson(contacts))?.apply()
    }

    fun add(phone: String, name: String, group: String = "default"): Contact {
        val contact = Contact(phone.trim(), name.trim(), group.trim())
        contacts[phone.trim()] = contact
        save()
        return contact
    }

    fun get(phone: String): Contact? = contacts[phone.trim()]

    fun getName(phone: String): String? = contacts[phone.trim()]?.name

    fun getAll(): List<Contact> = contacts.values.sortedBy { it.name.lowercase() }

    fun getByGroup(group: String): List<Contact> =
        contacts.values.filter { it.group == group }.sortedBy { it.name.lowercase() }

    fun getGroups(): List<String> =
        contacts.values.map { it.group }.distinct().sorted()

    fun update(phone: String, name: String? = null, group: String? = null): Contact? {
        val existing = contacts[phone.trim()] ?: return null
        val updated = existing.copy(
            name = name?.trim() ?: existing.name,
            group = group?.trim() ?: existing.group
        )
        contacts[phone.trim()] = updated
        save()
        return updated
    }

    fun delete(phone: String): Boolean {
        val removed = contacts.remove(phone.trim())
        if (removed != null) save()
        return removed != null
    }

    fun search(query: String): List<Contact> {
        val q = query.lowercase()
        return contacts.values.filter {
            it.name.lowercase().contains(q) || it.phone.contains(q)
        }.sortedBy { it.name.lowercase() }
    }

    fun count(): Int = contacts.size
}
