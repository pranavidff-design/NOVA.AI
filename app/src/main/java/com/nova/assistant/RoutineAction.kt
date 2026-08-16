package com.nova.assistant

/**
 * A single, replayable step inside a routine. Stored as a compact "TYPE:param"
 * string in RoutineEntity.actions, so routines survive app restarts without a
 * bigger schema change. Deliberately limited to exactly what ActionExecutor
 * already does one-at-a-time for normal voice commands — a routine can never
 * perform an action a regular spoken command couldn't already do. Nothing new
 * or hidden is unlocked by "teaching" Nova a routine.
 */
sealed class RoutineAction(val type: String, val param: String = "") {
    object OpenCalculator : RoutineAction("OPEN_CALCULATOR")
    object OpenMaps : RoutineAction("OPEN_MAPS")
    object OpenBrowser : RoutineAction("OPEN_BROWSER")
    object OpenEmail : RoutineAction("OPEN_EMAIL")
    object OpenSettings : RoutineAction("OPEN_SETTINGS")
    object OpenWhatsApp : RoutineAction("OPEN_WHATSAPP")
    object FlashlightOn : RoutineAction("FLASHLIGHT_ON")
    object FlashlightOff : RoutineAction("FLASHLIGHT_OFF")
    object VolumeUp : RoutineAction("VOLUME_UP")
    object VolumeDown : RoutineAction("VOLUME_DOWN")
    object OpenWifiPanel : RoutineAction("OPEN_WIFI_PANEL")
    object OpenMobileDataSettings : RoutineAction("OPEN_MOBILE_DATA_SETTINGS")
    class WebSearch(query: String) : RoutineAction("WEB_SEARCH", query)
    class OpenAppByPackage(pkg: String) : RoutineAction("OPEN_APP_PKG", pkg)
    /** Still requires a fresh approval tap every time it runs — a routine
     *  cannot pre-authorize a sensitive action, same rule as everything else. */
    class WhatsAppTo(contactName: String, message: String) : RoutineAction("WHATSAPP_MSG", "$contactName|$message")

    fun serialize(): String = "$type:${param.replace(";", ",").replace("\n", " ")}"

    fun describe(): String = when (this) {
        OpenCalculator -> "open Calculator"
        OpenMaps -> "open Maps"
        OpenBrowser -> "open the Browser"
        OpenEmail -> "open Email"
        OpenSettings -> "open Settings"
        OpenWhatsApp -> "open WhatsApp"
        FlashlightOn -> "turn the flashlight on"
        FlashlightOff -> "turn the flashlight off"
        VolumeUp -> "turn the volume up"
        VolumeDown -> "turn the volume down"
        is WebSearch -> "search for \"$param\""
        is OpenAppByPackage -> "open $param"
        is WhatsAppTo -> "prepare a WhatsApp message to ${param.substringBefore('|')} (still needs your approval each time)"
        OpenWifiPanel -> "open the Wi-Fi panel for you to tap"
        OpenMobileDataSettings -> "open data settings for you to tap (mobile data can't be auto-toggled on Android)"
    }

    /** Runs the step for real via ActionExecutor — the exact same code path a
     *  one-off voice command uses, nothing routine-specific or shortcutted. */
    fun execute(executor: ActionExecutor): String = when (this) {
        OpenCalculator -> executor.openCalculator()
        OpenMaps -> executor.openMaps()
        OpenBrowser -> executor.openBrowser()
        OpenEmail -> executor.openEmail()
        OpenSettings -> executor.openSettings()
        OpenWhatsApp -> executor.openWhatsApp()
        FlashlightOn -> executor.setFlashlight(true)
        FlashlightOff -> executor.setFlashlight(false)
        VolumeUp -> executor.adjustVolume(true)
        VolumeDown -> executor.adjustVolume(false)
        is WebSearch -> executor.webSearch(param)
        is OpenAppByPackage -> executor.openAppByPackage(param, param)
        is WhatsAppTo -> "" // handled specially by RoutineEngine — always needs a live approval + contact lookup
        OpenWifiPanel -> executor.openWifiPanel()
        OpenMobileDataSettings -> executor.openMobileDataSettings()
    }

    companion object {
        fun parseList(stored: String): List<RoutineAction> =
            stored.split(";").mapNotNull { parseOne(it.trim()) }

        fun serializeList(actions: List<RoutineAction>): String =
            actions.joinToString(";") { it.serialize() }

        fun parseOne(raw: String): RoutineAction? {
            if (raw.isBlank()) return null
            val idx = raw.indexOf(':')
            val type = if (idx >= 0) raw.substring(0, idx) else raw
            val param = if (idx >= 0) raw.substring(idx + 1) else ""
            return when (type) {
                "OPEN_CALCULATOR" -> OpenCalculator
                "OPEN_MAPS" -> OpenMaps
                "OPEN_BROWSER" -> OpenBrowser
                "OPEN_EMAIL" -> OpenEmail
                "OPEN_SETTINGS" -> OpenSettings
                "OPEN_WHATSAPP" -> OpenWhatsApp
                "FLASHLIGHT_ON" -> FlashlightOn
                "FLASHLIGHT_OFF" -> FlashlightOff
                "VOLUME_UP" -> VolumeUp
                "VOLUME_DOWN" -> VolumeDown
                "WEB_SEARCH" -> WebSearch(param)
                "OPEN_APP_PKG" -> OpenAppByPackage(param)
                "OPEN_WIFI_PANEL" -> OpenWifiPanel
                "OPEN_MOBILE_DATA_SETTINGS" -> OpenMobileDataSettings
                "WHATSAPP_MSG" -> {
                    val parts = param.split("|")
                    if (parts.size == 2) WhatsAppTo(parts[0], parts[1]) else null
                }
                else -> null
            }
        }
    }
}
