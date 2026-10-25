package com.maya.core

import java.time.Instant

/**
 * What the listener extracts from a posted notification. Deliberately has no text field:
 * message bodies, EXTRA_TEXT, EXTRA_BIG_TEXT and EXTRA_SUB_TEXT are never read.
 */
data class NotificationInfo(
    val packageName: String,
    val app: String,
    val category: String?,
    val ongoing: Boolean,
    val groupSummary: Boolean,
    val foregroundService: Boolean,
    val messagingStyle: Boolean,
    val title: String?,
    val styleSender: String?,
    val conversationTitle: String?,
    val isGroupConversation: Boolean,
    val postedAt: Instant,
)

/** Pure filter: turns a [NotificationInfo] into a [Notification] event, or null to ignore it. */
class NotificationFilter(
    private val isBlocked: (String) -> Boolean,
    private val ownPackage: String,
    private val dedupeMs: Long = 120_000,
) {
    private val lastAccepted = HashMap<Triple<String, String, String?>, Instant>()

    fun accept(n: NotificationInfo): Notification? {
        if (n.packageName == ownPackage || isBlocked(n.packageName)) return null
        if (n.ongoing || n.groupSummary || n.foregroundService) return null

        val kind = when {
            n.category == CATEGORY_EMAIL -> "email"
            n.category == CATEGORY_CALL || n.category == CATEGORY_MISSED_CALL -> "call"
            n.category == CATEGORY_MESSAGE || n.messagingStyle -> "message"
            else -> return null
        }

        val sender = clean(n.styleSender) ?: clean(n.title) ?: return null
        val group = if (n.isGroupConversation) clean(n.conversationTitle) else null
        val app = clean(n.app) ?: cap(n.packageName)

        val key = Triple(n.packageName, sender, group)
        val last = lastAccepted[key]
        if (last != null && n.postedAt.toEpochMilli() - last.toEpochMilli() < dedupeMs) return null
        lastAccepted[key] = n.postedAt
        if (lastAccepted.size > PRUNE_AT) {
            val cutoff = n.postedAt.toEpochMilli() - dedupeMs
            lastAccepted.values.removeAll { it.toEpochMilli() <= cutoff }
        }

        return Notification(n.packageName, app, sender, group, kind, ts = n.postedAt)
    }

    private fun clean(s: String?): String? = s?.trim()?.let(::cap)?.trim()?.ifEmpty { null }

    /** At most MAX_LEN chars without splitting an emoji (surrogate pair) in half. */
    private fun cap(s: String): String {
        val t = s.take(MAX_LEN)
        return if (t.isNotEmpty() && t.last().isHighSurrogate()) t.dropLast(1) else t
    }

    private companion object {
        const val MAX_LEN = 100
        const val PRUNE_AT = 200

        // Literal values of android.app.Notification.CATEGORY_* (core has no Android dependency).
        const val CATEGORY_MESSAGE = "msg"
        const val CATEGORY_EMAIL = "email"
        const val CATEGORY_CALL = "call"
        const val CATEGORY_MISSED_CALL = "missed_call"
    }
}
