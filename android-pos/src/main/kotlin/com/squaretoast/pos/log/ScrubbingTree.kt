package com.squaretoast.pos.log

import android.util.Log
import timber.log.Timber

/**
 * The only Timber tree the app plants. Drops everything tagged by the Stripe SDK and redacts any digit run that
 * looks like a card number (13–19 digits, spaces or dashes allowed, passing Luhn), including inside stack traces.
 */
class ScrubbingTree(
    private val sink: (priority: Int, tag: String?, message: String) -> Unit = { p, tag, m -> Log.println(p, tag, m) },
) : Timber.Tree() {

    public override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (tag != null && (tag.startsWith("Stripe") || tag.startsWith("com.stripe"))) return
        // Timber's public log() already appends the trace; a direct call with a throwable does not.
        val full = if (t != null && !message.contains(t.javaClass.name)) "$message\n${stackTrace(t)}" else message
        sink(priority, tag, redact(full))
    }

    companion object {
        private val CANDIDATE = Regex("""(?<!\d)\d(?:[ -]?\d){12,18}(?!\d)""")

        fun redact(text: String): String = CANDIDATE.replace(text) { m ->
            val digits = m.value.filter(Char::isDigit)
            if (luhn(digits)) "[REDACTED]" else m.value
        }

        private fun stackTrace(t: Throwable): String =
            java.io.StringWriter().also { t.printStackTrace(java.io.PrintWriter(it)) }.toString()

        private fun luhn(digits: String): Boolean {
            var sum = 0
            for ((i, c) in digits.reversed().withIndex()) {
                var d = c - '0'
                if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
                sum += d
            }
            return sum % 10 == 0
        }
    }
}
