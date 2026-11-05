package com.one_studio

/**
 * Minimal java.util.logging-free logger so the mod also runs on Minecraft
 * versions whose runtime does not ship slf4j (1.14 - 1.16).
 */
object Log {
    private fun format(msg: String, args: Array<out Any?>): String {
        if (args.isEmpty()) return msg
        val sb = StringBuilder(msg.length + 32)
        var ai = 0
        var i = 0
        while (i < msg.length) {
            if (i + 1 < msg.length && msg[i] == '{' && msg[i + 1] == '}' && ai < args.size) {
                sb.append(args[ai])
                ai++
                i += 2
            } else {
                sb.append(msg[i])
                i++
            }
        }
        return sb.toString()
    }

    fun info(msg: String, vararg args: Any?) {
        println("[MidnightAssist/INFO] " + format(msg, args))
    }

    fun warn(msg: String, vararg args: Any?) {
        println("[MidnightAssist/WARN] " + format(msg, args))
    }

    fun error(msg: String, vararg args: Any?) {
        System.err.println("[MidnightAssist/ERROR] " + format(msg, args))
    }
}