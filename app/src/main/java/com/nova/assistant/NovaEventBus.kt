package com.nova.assistant

/**
 * NovaWakeService runs independently of MainActivity — that's the whole point
 * of a real background wake word. But if the Activity DOES happen to be
 * visible when the service hears a command, it's better UX to mirror that
 * into the on-screen log/status instead of the user only finding out via the
 * notification. This is safe as a plain in-process singleton (not IPC/AIDL)
 * because the service and Activity always run in the same app process.
 */
object NovaEventBus {
    private var logListener: ((who: String, text: String) -> Unit)? = null
    private var statusListener: ((status: String) -> Unit)? = null

    fun setListeners(
        onLog: ((who: String, text: String) -> Unit)?,
        onStatus: ((status: String) -> Unit)?
    ) {
        logListener = onLog
        statusListener = onStatus
    }

    fun log(who: String, text: String) {
        logListener?.invoke(who, text)
    }

    fun status(status: String) {
        statusListener?.invoke(status)
    }
}
