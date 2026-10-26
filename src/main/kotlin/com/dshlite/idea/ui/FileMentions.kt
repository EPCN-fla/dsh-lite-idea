package com.dshlite.idea.ui

/** Names referenced by complete @ tokens, longest match first at each mention. */
internal fun referencedMentionNames(text: String, names: Collection<String>): Set<String> {
    val matches = HashSet<String>()
    for (at in text.indices) {
        if (text[at] != '@' && text[at] != '＠') continue
        val match = names.asSequence().filter { name ->
            val end = at + 1 + name.length
            text.startsWith(name, startIndex = at + 1) &&
                (end == text.length || text[end].isWhitespace())
        }.maxByOrNull { it.length }
        if (match != null) matches.add(match)
    }
    return matches
}
