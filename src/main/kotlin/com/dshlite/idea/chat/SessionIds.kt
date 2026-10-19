package com.dshlite.idea.chat

/** Session ids end up in target-side shell scripts (log reads, deletion). */
object SessionIds {
    private val SAFE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    /** True for ids that are inert in a shell word and a `find -name` pattern. */
    fun isSafe(id: String): Boolean = SAFE.matches(id)
}
