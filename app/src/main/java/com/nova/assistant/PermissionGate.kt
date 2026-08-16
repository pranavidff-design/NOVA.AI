package com.nova.assistant

import android.app.AlertDialog
import android.content.Context

/**
 * The one place in the app where a sensitive action (camera, mic, payment, OTP)
 * gets a real confirmation dialog. No sensitive action executes without this
 * being called and the user tapping Approve — matches the "medium/high-risk
 * needs confirmation" rule and the explicit camera/mic/payment/OTP requirement.
 */
object PermissionGate {

    fun request(context: Context, actionLabel: String, onDecision: (approved: Boolean) -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("Approval required")
            .setMessage("Nova wants to $actionLabel. Allow this?")
            .setCancelable(false)
            .setPositiveButton("Approve") { dialog, _ ->
                dialog.dismiss()
                onDecision(true)
            }
            .setNegativeButton("Deny") { dialog, _ ->
                dialog.dismiss()
                onDecision(false)
            }
            .show()
    }
}
