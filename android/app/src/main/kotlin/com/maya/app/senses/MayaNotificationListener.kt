package com.maya.app.senses

import android.app.Notification
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.maya.core.NotificationInfo
import java.time.Instant

/**
 * Notices who messages the user. Needs the special "Notification access" permission. Only the
 * app, category, flags, title, conversation title and the last MessagingStyle sender's name are
 * read: never EXTRA_TEXT, EXTRA_BIG_TEXT, EXTRA_SUB_TEXT or any message text. Does nothing unless
 * MayaService is running with the sense on ([NotificationBridge.sink] set). Filtering happens in
 * MayaService (core's NotificationFilter). Callbacks arrive on the main thread.
 */
class MayaNotificationListener : NotificationListenerService() {
    private val labels = HashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || NotificationBridge.sink == null) return
        val info = try {
            read(sbn)
        } catch (e: Exception) {
            Log.w(TAG, "could not read a notification: ${e.javaClass.simpleName}")
            null
        } ?: return
        NotificationBridge.post(info)
    }

    private fun read(sbn: StatusBarNotification): NotificationInfo? {
        val n: Notification = sbn.notification ?: return null
        val extras: Bundle = n.extras ?: Bundle()
        val pkg: String = sbn.packageName ?: return null
        val messagingStyle = extras.getString(Notification.EXTRA_TEMPLATE) == Notification.MessagingStyle::class.java.name
        return NotificationInfo(
            packageName = pkg,
            app = labelOf(pkg),
            category = n.category,
            ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            groupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            foregroundService = (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0,
            messagingStyle = messagingStyle,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            styleSender = if (messagingStyle) lastSender(extras) else null,
            conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
            isGroupConversation = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
            postedAt = Instant.ofEpochMilli(sbn.postTime),
        )
    }

    /**
     * The name of the last MessagingStyle message's sender (null when the user sent it). Only the
     * sender is looked at, never the text. Android 11+; older versions fall back to the title.
     */
    @Suppress("DEPRECATION")
    private fun lastSender(extras: Bundle): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
        return messages.lastOrNull()?.senderPerson?.name?.toString()
    }

    @Suppress("DEPRECATION")
    private fun labelOf(pkg: String): String = labels.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    private companion object {
        const val TAG = "MayaNotifications"
    }
}
