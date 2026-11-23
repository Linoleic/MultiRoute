package com.multiroute.ui.main

/** Removes the localized default-data suffix while preserving the SIM and carrier label. */
internal fun compactCellularLabel(label: String?, localizedDefaultSuffix: String): String? {
    if (label.isNullOrBlank()) return null
    return label.removeSuffix(localizedDefaultSuffix).trimEnd().takeIf(String::isNotBlank)
}

/** Drops a trailing Android package name from an owner label, retaining the human-readable app name. */
internal fun compactVpnOwnerName(ownerName: String?): String? {
    if (ownerName.isNullOrBlank()) return null
    val packageSuffix = Regex("\\s+\\([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+\\)$")
    return ownerName.replace(packageSuffix, "").trim().takeIf(String::isNotBlank)
}
