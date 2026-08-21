package app.rebubble.data.repo

import app.rebubble.data.local.entity.ContactEntity

/**
 * Canonicalizes a handle address (phone number or email) so contacts synced from `GET /contact`
 * (which may report a phone number in a different punctuation than the one a chat/message handle
 * uses, e.g. `"+1 (555) 010-0001"` vs `"+15550100001"`) can still be found by a lookup keyed on
 * whatever verbatim form the server happens to report for a given handle.
 *
 * Rules:
 *  - Anything containing `@` is treated as an email and lowercased.
 *  - Anything else is treated as a phone number: every character other than digits and a single
 *    leading `+` is stripped (spaces, dashes, dots, parens, and any non-leading `+`).
 *
 * This is deliberately simple (no libphonenumber-style E.164 normalization, no region/country-code
 * inference) — it only needs to make the *common* punctuation variants of the same number collide,
 * not fully canonicalize arbitrary international input.
 */
fun normalizeAddress(raw: String): String {
    if (raw.contains('@')) return raw.lowercase()

    val leadingPlus = raw.trimStart().startsWith("+")
    val digits = raw.filter { it.isDigit() }
    return if (leadingPlus) "+$digits" else digits
}

/**
 * Looks up [address] in this address-keyed contact map, falling back to its
 * [normalizeAddress] form when the verbatim address isn't present. Centralizes the
 * verbatim-then-normalized fallback so every lookup site (chat title resolution, notification
 * person names, group-event sender names) applies the same rule instead of forking it.
 */
fun Map<String, ContactEntity>.findByAddress(address: String): ContactEntity? =
    this[address] ?: this[normalizeAddress(address)]

/** Same fallback as [findByAddress], for call sites that only carry a display-name string map. */
fun Map<String, String>.findNameByAddress(address: String): String? =
    this[address] ?: this[normalizeAddress(address)]
