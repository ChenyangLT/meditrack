package com.meditrack.data.local.entity

/**
 * Which search engine the «复查» search button opens.
 *
 * Two engines rather than one because the user's request was "打开百度搜索", and a medical question is
 * exactly the kind where a single vendor's ranking should not be the only door. Neither is embedded:
 * the button hands a query to the installed browser, so nothing about the user's prescriptions ever
 * passes through this app - it stays the offline-first app it advertises itself as.
 */
enum class ReviewSearchEngine(val label: String, val baseUrl: String) {
    BAIDU("百度", "https://www.baidu.com/s?wd="),
    BING("必应", "https://www.bing.com/search?q="),
    SOGOU("搜狗", "https://www.sogou.com/web?query="),
    ;

    companion object {
        val DEFAULT = BAIDU

        fun fromName(name: String?): ReviewSearchEngine =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Builds the search the «复查» button opens, and the queries offered as one-tap starting points.
 *
 * ## Why the query is phrased the way it is
 *
 * "复查" is the word a Chinese prescription actually uses ("定期复查肝功能"), so the generated query is
 * `阿司匹林 吃多久需要去复查` rather than `阿司匹林 复查` - the first one is a question, and search
 * engines rank questions better than keywords. The [SUGGESTED] list exists because the honest answer
 * to "how long until I need a follow-up" is usually "it depends what it is for", and a user who does
 * not know what to type will not type anything.
 *
 * Pure except for [Uri.encode], a static string helper - deliberately not a `Context` method, so this
 * whole file is unit testable without Robolectric.
 */
object ReviewSearchQuery {

    /** The question the requirements name explicitly, kept verbatim as the default phrasing. */
    const val DEFAULT_QUESTION = "吃多久需要去复查"

    /** Other phrasings worth offering, in the order they are shown. */
    val SUGGESTED = listOf(
        "吃多久需要去复查",
        "多久复查一次",
        "长期吃有什么副作用",
        "漏服了怎么办",
        "能不能和别的药一起吃",
        "停药要注意什么",
    )

    /**
     * The query text: the drug name, the question, and whatever the user appended.
     *
     * @param suffix extra words from the user's own setting (for example "高血压"), so their situation
     *        is part of the query without them having to retype the drug name.
     */
    fun text(
        medicationName: String,
        question: String = DEFAULT_QUESTION,
        suffix: String = "",
    ): String = buildString {
        append(medicationName.trim())
        if (question.isNotBlank()) {
            append(' ')
            append(question.trim())
        }
        if (suffix.isNotBlank()) {
            append(' ')
            append(suffix.trim())
        }
    }

    /** A full, openable URL for [question] about [medicationName]. */
    fun url(
        engine: ReviewSearchEngine,
        medicationName: String,
        question: String = DEFAULT_QUESTION,
        suffix: String = "",
    ): String = engine.baseUrl + encode(text(medicationName, question, suffix))

    /**
     * Percent-encodes a query for a URL, purely.
     *
     * ## Why this is hand-written rather than `Uri.encode`
     *
     * Three reasons, in order of importance:
     *
     *  1. `Uri.encode` leaves a space alone, because a space is legal inside a *path* segment. It is not
     *     legal unescaped in a query string: several browsers normalise it to `+`, some proxies reject the
     *     request outright, and the query can silently lose the separation between the drug name and the
     *     question - turning "阿司匹林 吃多久需要去复查" into one keyword that matches nothing useful. A space
     *     is the *normal* case here, since the query is "drug name<space>question".
     *  2. `Uri.encode` is Android framework code, so it returns null under a plain JVM unit test - which
     *     means the string this app hands to the user's browser could not be tested at all.
     *  3. The escaping rules are three lines of arithmetic and do not need a framework to get right.
     *
     * Everything outside the RFC 3986 unreserved set is escaped, which is stricter than a query string
     * requires and therefore safe everywhere: a search engine that receives `%2F` for a slash understands
     * it, while one that receives a bare `+` may not.
     */
    internal fun encode(query: String): String = buildString {
        for (byte in query.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '-' || char == '_' || char == '.' || char == '~'
            ) {
                append(char)
            } else {
                append('%')
                append(HEX[value shr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
