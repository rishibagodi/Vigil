package com.rishi.vigil

import android.annotation.SuppressLint
import android.service.notification.NotificationListenerService

// Android keeps this connected while Notification access is on.
// Tools.kt reads current notifications through `instance`.
class VigilNotificationService : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile var instance: VigilNotificationService? = null
    }
}
