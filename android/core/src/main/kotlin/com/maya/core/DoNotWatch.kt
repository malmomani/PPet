package com.maya.core

/** Packages Maya must never observe (wallets, password managers, authenticators). */
val DEFAULT_DO_NOT_WATCH: Set<String> = setOf(
    "com.samsung.android.spay",
    "com.samsung.android.samsungpass",
    "com.google.android.apps.walletnfcrel",
    "com.paypal.android.p2pmobile",
    "com.x8bit.bitwarden",
    "com.lastpass.lpandroid",
    "com.agilebits.onepassword",
    "com.google.android.apps.authenticator2",
    "com.azure.authenticator",
)

/** Blocked if the package is in [packages] or its name contains "bank" (case-insensitive). */
class DoNotWatch(private val packages: Set<String>) {
    fun isBlocked(pkg: String): Boolean = pkg in packages || pkg.contains("bank", ignoreCase = true)
}
