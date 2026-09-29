package dev.accessaudit.core.report

/**
 * A minimal JSON writer.
 *
 * Hand-rolled on purpose: `:core` stays dependency-free so it builds and tests
 * on a bare JDK with no access to Google's Maven repository, and the output
 * shape here is small and fixed. Escaping is the only subtle part, and it is
 * covered by tests.
 */
public class JsonWriter(private val indentWidth: Int = 2) {

    private val sb = StringBuilder()
    private var depth = 0

    public fun obj(block: JsonWriter.() -> Unit): JsonWriter {
        sb.append('{')
        depth++
        firstAtDepth.add(true)
        block()
        firstAtDepth.removeAt(firstAtDepth.size - 1)
        depth--
        newline()
        sb.append('}')
        return this
    }

    private val firstAtDepth = mutableListOf<Boolean>()

    private fun separate() {
        if (firstAtDepth.isNotEmpty() && firstAtDepth.last()) {
            firstAtDepth[firstAtDepth.size - 1] = false
        } else {
            sb.append(',')
        }
        newline()
    }

    private fun newline() {
        if (indentWidth > 0) {
            sb.append('\n').append(" ".repeat(depth * indentWidth))
        }
    }

    public fun field(name: String, value: String?): JsonWriter {
        separate()
        key(name)
        sb.append(value?.let { quote(it) } ?: "null")
        return this
    }

    public fun field(name: String, value: Long): JsonWriter {
        separate(); key(name); sb.append(value); return this
    }

    public fun field(name: String, value: Int): JsonWriter {
        separate(); key(name); sb.append(value); return this
    }

    public fun field(name: String, value: Boolean): JsonWriter {
        separate(); key(name); sb.append(value); return this
    }

    public fun objectField(name: String, block: JsonWriter.() -> Unit): JsonWriter {
        separate(); key(name); obj(block); return this
    }

    public fun <T> arrayField(name: String, items: Iterable<T>, write: JsonWriter.(T) -> Unit): JsonWriter {
        separate()
        key(name)
        sb.append('[')
        depth++
        firstAtDepth.add(true)
        for (item in items) {
            separate()
            write(item)
        }
        firstAtDepth.removeAt(firstAtDepth.size - 1)
        depth--
        newline()
        sb.append(']')
        return this
    }

    public fun stringArrayField(name: String, items: Iterable<String>): JsonWriter =
        arrayField(name, items) { sb.append(quote(it)) }

    /** Writes a bare object element, for use inside [arrayField]. */
    public fun element(block: JsonWriter.() -> Unit): JsonWriter {
        sb.append('{')
        depth++
        firstAtDepth.add(true)
        block()
        firstAtDepth.removeAt(firstAtDepth.size - 1)
        depth--
        newline()
        sb.append('}')
        return this
    }

    private fun key(name: String) {
        sb.append(quote(name)).append(':')
        if (indentWidth > 0) sb.append(' ')
    }

    override fun toString(): String = sb.toString()

    public companion object {
        /**
         * Quotes and escapes a JSON string.
         *
         * Control characters below 0x20 must be escaped; we also escape U+2028
         * and U+2029, which are valid in JSON but break JavaScript parsers that
         * treat the output as a script literal.
         */
        public fun quote(value: String): String {
            val out = StringBuilder(value.length + 2)
            out.append('"')
            for (ch in value) {
                when (ch) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    '\b' -> out.append("\\b")
                    '\u000C' -> out.append("\\f")
                    ' ' -> out.append("\\u2028")
                    ' ' -> out.append("\\u2029")
                    else -> if (ch < ' ') {
                        out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        out.append(ch)
                    }
                }
            }
            out.append('"')
            return out.toString()
        }
    }
}
