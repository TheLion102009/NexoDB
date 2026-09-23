package the.lion.nexoDB.util

/**
 * Minimaler, plattformunabhaengiger Glob-Matcher.
 *
 * Java's [java.nio.file.PathMatcher] uebersetzt "/" in den Separator des Betriebssystems.
 * Da die Sync-Keys in der Datenbank immer mit "/" gespeichert werden (damit Windows- und
 * Linux-Server dieselben Dokumente sehen), wird hier bewusst ein eigener Matcher benutzt,
 * der ausschliesslich auf "/"-normalisierten Strings arbeitet.
 *
 * Unterstuetzte Syntax:
 *  - `*`  -> beliebig viele Zeichen ausser "/"
 *  - `?`  -> genau ein Zeichen ausser "/"
 *  - `**` -> beliebig viele Zeichen inklusive "/"
 *  - Doppelstern gefolgt von Slash -> beliebig viele Verzeichnisebenen (auch null Ebenen)
 */
class Glob private constructor(private val pattern: String, private val regex: Regex) {

    fun matches(path: String): Boolean = regex.matches(path)

    override fun toString(): String = pattern

    companion object {

        fun compile(pattern: String): Glob {
            val normalized = pattern.replace('\\', '/').trim()
            val sb = StringBuilder(normalized.length * 2)
            sb.append('^')

            var i = 0
            while (i < normalized.length) {
                when (val c = normalized[i]) {
                    '*' -> {
                        val doubleStar = i + 1 < normalized.length && normalized[i + 1] == '*'
                        if (doubleStar) {
                            val followedBySlash = i + 2 < normalized.length && normalized[i + 2] == '/'
                            if (followedBySlash) {
                                // "**/" darf auch null Verzeichnisebenen abdecken.
                                sb.append("(?:.*/)?")
                                i += 3
                            } else {
                                sb.append(".*")
                                i += 2
                            }
                        } else {
                            sb.append("[^/]*")
                            i++
                        }
                    }

                    '?' -> {
                        sb.append("[^/]")
                        i++
                    }

                    '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\' -> {
                        sb.append('\\').append(c)
                        i++
                    }

                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }

            sb.append('$')
            // IGNORE_CASE, damit ".PNG" und ".png" gleich behandelt werden.
            return Glob(normalized, Regex(sb.toString(), RegexOption.IGNORE_CASE))
        }

        fun compileAll(patterns: Collection<String>): List<Glob> = patterns.map { compile(it) }

        fun matchesAny(globs: List<Glob>, path: String): Boolean {
            for (glob in globs) {
                if (glob.matches(path)) return true
            }
            return false
        }
    }
}
