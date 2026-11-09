package lol.addi.t3craft.client.ui

import java.time.Duration
import java.time.Instant

object Format {
    fun duration(duration: Duration): String {
        val seconds = duration.seconds.coerceAtLeast(0)
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            seconds < 86_400 -> if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}m"
            else -> "${seconds / 86_400}d"
        }
    }

    fun since(instant: Instant): String = duration(Duration.between(instant, Instant.now()))

    fun model(slug: String): String {
        if (slug.isBlank()) return ""
        val tokens = slug.lowercase().split('-').filter { it.isNotEmpty() }
        return when (tokens.first()) {
            "claude" -> claudeModel(tokens.drop(1))
            "gpt" -> "GPT-" + tokens.drop(1).joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
            else -> tokens.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        }
    }

    fun runtimeMode(mode: String): String = mode.replace('-', ' ').replaceFirstChar(Char::uppercase)

    private fun claudeModel(tokens: List<String>): String {
        val family = tokens.firstOrNull { token -> token.any(Char::isLetter) } ?: return "Claude"
        val version = tokens.filter { token -> token.all(Char::isDigit) && token.length <= 2 }.joinToString(".")
        return listOf(family.replaceFirstChar(Char::uppercase), version).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
