package com.nova.assistant

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.Settings

/**
 * Executes real Android actions. This is the ONLY class that touches system APIs —
 * keeping it isolated means the permission-gating logic (PermissionGate) always sits
 * between a command and this class for anything sensitive, with no way to bypass it.
 */
class ActionExecutor(private val context: Context) {

    fun openCalculator(): String {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_CALCULATOR)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Opening Calculator."
        } catch (e: Exception) {
            "I couldn't find a Calculator app on this phone."
        }
    }

    fun openMaps(): String = openViaCategory(Intent.CATEGORY_APP_MAPS, "Maps")
    fun openBrowser(): String = openViaCategory(Intent.CATEGORY_APP_BROWSER, "Browser")
    fun openEmail(): String = openViaCategory(Intent.CATEGORY_APP_EMAIL, "Mail")

    private fun openViaCategory(category: String, label: String): String {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(category)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Opening $label."
        } catch (e: Exception) {
            "I couldn't find a $label app on this phone."
        }
    }

    fun openAppByPackage(packageName: String, label: String): String {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
                "Opening $label."
            } else {
                "$label isn't installed on this phone."
            }
        } catch (e: Exception) {
            "I couldn't open $label."
        }
    }

    fun openWhatsApp(): String = openAppByPackage("com.whatsapp", "WhatsApp")

    /** Generic fallback for "open <app>" when it's not one of the specific apps
     *  handled above — searches installed apps by their visible name. Needs the
     *  <queries><intent> block in the manifest (Android 11+ package visibility;
     *  this is the Google-documented, Play-Store-safe way to do this WITHOUT the
     *  heavily-restricted QUERY_ALL_PACKAGES permission). */
    fun openAppByName(spokenName: String): String {
        return try {
            val pm = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(launcherIntent, 0)
            val match = apps.firstOrNull {
                it.loadLabel(pm).toString().contains(spokenName, ignoreCase = true)
            } ?: return "I couldn't find an app called \"$spokenName\" on this phone."
            val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
                ?: return "I found $spokenName but couldn't launch it."
            launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(launch)
            "Opening ${match.loadLabel(pm)}."
        } catch (e: Exception) {
            "I couldn't open \"$spokenName\"."
        }
    }

    /**
     * Opens WhatsApp with the message ALREADY TYPED IN for a specific contact —
     * it does NOT press send. WhatsApp has no public consumer API that lets a
     * third-party app silently send a message on the user's behalf; the
     * officially-supported mechanism for prefilling a chat is this "click to
     * chat" link (https://wa.me/<number>?text=<message>), same as WhatsApp's
     * own website buttons use. The user still has to tap Send inside WhatsApp —
     * that's a real Android/WhatsApp security boundary, not a shortcut we chose.
     */
    fun sendWhatsAppMessage(phoneNumber: String, message: String): String {
        return try {
            val cleanNumber = phoneNumber.filter { it.isDigit() || it == '+' }
            if (cleanNumber.isBlank()) return "That contact doesn't have a usable phone number."
            val uri = android.net.Uri.parse("https://wa.me/$cleanNumber?text=${android.net.Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Opened WhatsApp with your message ready — tap Send inside WhatsApp to actually send it."
        } catch (e: Exception) {
            "I couldn't open WhatsApp to prepare that message."
        }
    }

    fun openSettings(): String {
        return try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Opening Settings."
        } catch (e: Exception) {
            "I couldn't open Settings."
        }
    }

    /** Camera app itself opens for manual use — this does NOT trigger the camera silently or take a photo. Still gated by approval before this is ever called. */
    fun openCameraApp(): String {
        return try {
            val intent = Intent("android.media.action.IMAGE_CAPTURE").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Opening Camera."
        } catch (e: Exception) {
            "I couldn't open the Camera app."
        }
    }

    fun setFlashlight(on: Boolean): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "This phone doesn't have a flashlight I can control."
            cameraManager.setTorchMode(cameraId, on)
            if (on) "Flashlight on." else "Flashlight off."
        } catch (e: Exception) {
            "I couldn't control the flashlight."
        }
    }

    fun adjustVolume(up: Boolean): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            if (up) "Volume up." else "Volume down."
        } catch (e: Exception) {
            "I couldn't change the volume."
        }
    }

    /** Uses Android's built-in alarm-setting intent — the Clock app handles the actual save,
     *  which naturally gives you a look at it before it's final, no extra permission needed. */
    fun setAlarm(hour: Int, minute: Int, label: String = "Nova alarm"): String {
        return try {
            val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val displayHour = if (hour % 12 == 0) 12 else hour % 12
            val ampm = if (hour < 12) "AM" else "PM"
            "Setting an alarm for $displayHour:${minute.toString().padStart(2, '0')} $ampm."
        } catch (e: Exception) {
            "I couldn't open the alarm screen."
        }
    }

    /** Opens the Calendar app's event-creation screen pre-filled — you confirm and save it yourself.
     *  No calendar permission needed since this hands off to the Calendar app rather than writing directly. */
    fun createCalendarEvent(title: String): String {
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = android.provider.CalendarContract.Events.CONTENT_URI
                putExtra(android.provider.CalendarContract.Events.TITLE, title)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Opening a new calendar event for \"$title\" — just confirm the details and save."
        } catch (e: Exception) {
            "I couldn't open the calendar."
        }
    }

    /** Opens Android's built-in Wi-Fi quick-panel (one tap to toggle) — this is the real,
     *  current, Play-Store-safe way to touch Wi-Fi. Direct silent WifiManager.setWifiEnabled()
     *  has been blocked by Android for regular apps since Android 10 (API 29) — Google removed
     *  that ability app-side for user-safety reasons, it's not something we chose to skip. */
    fun openWifiPanel(): String {
        return try {
            val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Opened the Wi-Fi panel — one tap to switch it."
        } catch (e: Exception) {
            "I couldn't open the Wi-Fi panel."
        }
    }

    /** HONEST LIMITATION: no Android app — with any permission a normal user can grant
     *  without a computer — can silently toggle mobile data. That control is restricted to
     *  system-level apps only; the only workaround (WRITE_SECURE_SETTINGS granted via ADB)
     *  needs a computer, which breaks this project's phone-only constraint. So this opens
     *  the real data-usage settings screen instead of pretending to flip it. */
    fun openMobileDataSettings(): String {
        return try {
            val intent = Intent(Settings.ACTION_DATA_USAGE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "I can't toggle mobile data myself — Android doesn't allow that for a regular app without a computer. I opened the data settings screen so you can flip it in one tap."
        } catch (e: Exception) {
            "I can't toggle mobile data myself — Android doesn't allow a regular app to do that."
        }
    }

    /** Opens a browser search — local AI has no live internet access, so real questions about
     *  current info are handed off honestly rather than answered with a guess. */
    fun webSearch(query: String): String {
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Searching the web for \"$query\"."
        } catch (e: Exception) {
            "I couldn't open a web search."
        }
    }
}
