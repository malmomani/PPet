package com.maya.core

/** Tease tags whose press needs the user's yes (same as the brain). */
val RISKY_TAGS: Set<String> = setOf("send", "share", "comment", "follow")

/**
 * Words (English and Arabic) that make a press risky when they appear, as whole words, in its
 * target or in the label of the node it finds (same as the brain). Stored in the form [words] gives:
 * lower case, no combining marks.
 */
val RISKY_WORDS: Set<String> = setOf(
    "send", "post", "publish", "buy", "pay", "order", "checkout", "purchase", "delete", "remove",
    "call", "dial", "share", "subscribe", "follow", "confirm", "submit", "install", "uninstall", "reply",
    "إرسال", "ارسال", "نشر", "شراء", "دفع", "حذف", "اتصال", "مشاركة", "متابعة", "اشتراك", "تأكيد",
).map { words(it).single() }.toSet()

/** Places Maya never acts in (besides her own package and the do-not-watch apps). */
val NEVER_PACKAGES: Set<String> = setOf("com.android.systemui", "com.android.settings")

private val PHONE_KINDS = setOf("press", "type", "scroll") // act on the screen of an app

/**
 * Whether [act] needs the user's yes first: any `type`, and a `press` whose target is a risky tag, or
 * whose target text or [matchedLabel] (the label of the node it found) holds a risky word.
 */
fun isRisky(act: Act, matchedLabel: String? = null): Boolean = when (act.kind) {
    "type" -> true
    "press" -> {
        val target = act.target.orEmpty()
        target.lowercase().trim() in RISKY_TAGS ||
            words(target).any { it in RISKY_WORDS } ||
            words(matchedLabel).any { it in RISKY_WORDS }
    }
    else -> false
}

/**
 * Why [act] may not run, or null when it may: "off" (the switch), "locked" (keyguard), "not here"
 * (the app in front is on the never list, is Maya herself, or is do-not-watch; press/type/scroll also
 * need to know the app: [pkg] null). System kinds work without knowing the app.
 */
fun blockedReason(
    act: Act,
    pkg: String?,
    ownPackage: String,
    doNotWatch: (String) -> Boolean,
    keyguardLocked: Boolean,
    allowActions: Boolean,
): String? {
    if (!allowActions) return "off"
    if (keyguardLocked) return "locked"
    if (pkg == null) return if (act.kind in PHONE_KINDS) "not here" else null
    return if (pkg in NEVER_PACKAGES || pkg == ownPackage || doNotWatch(pkg)) "not here" else null
}

private const val APP_MATCH_MIN = 0.8
private val NOT_LETTER_OR_DIGIT = Regex("[^\\p{L}\\p{N}]+")

private fun normalize(s: String): String = s.lowercase().replace(NOT_LETTER_OR_DIGIT, "")

/** 1 - Levenshtein distance / the longer length, on normalized strings; 1 for two empty ones. */
private fun similarity(a: String, b: String): Double {
    val n = maxOf(a.length, b.length)
    if (n == 0) return 1.0
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        for (j in 1..b.length) {
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        prev = cur
    }
    return 1.0 - prev[b.length].toDouble() / n
}

/**
 * The package of the launcher app that [query] means, from [apps] (label to package), or null.
 * Names are normalized (lower case, only letters and digits). An exact name wins, then a name
 * that starts with the query (the shortest), then the most similar one (at least 0.8).
 */
fun matchApp(query: String, apps: List<Pair<String, String>>): String? {
    val q = normalize(query)
    if (q.isEmpty()) return null
    val named = apps.map { (label, pkg) -> normalize(label) to pkg }.filter { it.first.isNotEmpty() }
    named.firstOrNull { it.first == q }?.let { return it.second }
    named.filter { it.first.startsWith(q) }.minByOrNull { it.first.length }?.let { return it.second }
    return named
        .map { it to similarity(q, it.first) }
        .filter { it.second >= APP_MATCH_MIN }
        .maxByOrNull { it.second }
        ?.first?.second
}

/** What a node says: its text (its description for an editable one: the text is the user's own) or its description. */
private fun labelOf(n: UiNode): String? =
    (if (n.editable) n.description else n.text?.takeIf { it.isNotBlank() } ?: n.description)

/**
 * The clickable node [target] means, or null. A tease tag (`send`, `like`, ...) finds the nodes that
 * [tagOf] gives that tag; anything else finds the nodes whose label holds the target's words, whole
 * and in order (case-insensitive). The smallest one wins: the button, not the card around it.
 */
fun matchLabel(target: String, nodes: List<UiNode>): UiNode? {
    val tag = target.trim().lowercase().takeIf { it in TEASE_TAGS }
    val want = words(target)
    if (tag == null && want.isEmpty()) return null
    return nodes
        .filter { n ->
            n.clickable && if (tag != null) tagOf(n) == tag else holds(words(labelOf(n)), want)
        }
        .minByOrNull { it.bounds.width.toLong() * it.bounds.height }
}

/** [want] appears in [have] as a run of whole words. */
private fun holds(have: List<String>, want: List<String>): Boolean {
    if (want.isEmpty() || want.size > have.size) return false
    return (0..have.size - want.size).any { i -> want.indices.all { have[i + it] == want[it] } }
}
