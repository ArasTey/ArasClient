package com.aras.client.extension

/**
 * Wraps the text in Unicode directional isolates so it renders left-to-right whatever
 * direction the paragraph around it runs in.
 *
 * The app name is Latin. In a Persian paragraph the bidirectional algorithm may reorder
 * the neutral parts of it, which is why "ArasClient" came out as "ClientAras". An
 * isolate pins the run as left-to-right and is invisible on screen.
 *
 * Use this for display only: the marks are part of the string, so a file name or URL
 * built from it would carry them.
 */
fun String.ltrIsolated(): String = "⁦" + this + "⁩"

/**
 * Removes all whitespace from the string.
 *
 * @return The string without whitespace.
 */
fun String?.removeWhiteSpace(): String? = this?.replace(" ", "")

/**
 * Returns null if the string is null or blank, otherwise returns the string itself.
 *
 * @return The string or null.
 */
fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

/**
 * Converts the string to a Long value, or returns 0 if the conversion fails.
 *
 * @return The Long value.
 */
fun String.toLongEx(): Long = toLongOrNull() ?: 0

/**
 * Checks if the CharSequence is not null and not empty.
 *
 * @return True if the CharSequence is not null and not empty, false otherwise.
 */
fun CharSequence?.isNotNullEmpty(): Boolean = !this.isNullOrBlank()

fun String.concatUrl(vararg paths: String): String {
    val builder = StringBuilder(this.trimEnd('/'))

    paths.forEach { path ->
        val trimmedPath = path.trim('/')
        if (trimmedPath.isNotEmpty()) {
            builder.append('/').append(trimmedPath)
        }
    }

    return builder.toString()
}

/**
 * Helper function to match text either by Regex or literal string.
 */
fun String.matchesPattern(regex: Regex?, keyword: String?, ignoreCase: Boolean = true): Boolean {
    if (keyword.isNullOrEmpty()) {
        return true
    }
    return regex?.containsMatchIn(this)
        ?: this.contains(keyword, ignoreCase = ignoreCase)
}