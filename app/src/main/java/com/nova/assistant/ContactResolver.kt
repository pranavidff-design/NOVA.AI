package com.nova.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import android.util.Log

/**
 * Resolves a spoken contact name ("Om") to a real phone number using the
 * phone's own Contacts provider. Requires READ_CONTACTS (a dangerous
 * permission — the caller must have already requested and been granted it;
 * this class never requests permissions itself, it just checks).
 */
class ContactResolver(private val context: Context) {

    companion object { private const val TAG = "ContactResolver" }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Best-effort case-insensitive contains match on display name.
     *  Returns null if contacts permission is missing, no match found, or the
     *  match has no usable phone number. Never throws — always logs the reason. */
    fun findPhoneNumber(spokenName: String): String? {
        if (!hasPermission()) {
            Log.w(TAG, "findPhoneNumber() called without READ_CONTACTS granted")
            return null
        }
        val needle = spokenName.trim().lowercase()
        if (needle.isEmpty()) return null

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null, null, null
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameIdx < 0 || numberIdx < 0) return null

                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameIdx) ?: continue
                    if (displayName.lowercase().contains(needle)) {
                        val number = cursor.getString(numberIdx)
                        if (!number.isNullOrBlank()) return number
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "findPhoneNumber() query failed", e)
            return null
        }
        return null
    }
}
