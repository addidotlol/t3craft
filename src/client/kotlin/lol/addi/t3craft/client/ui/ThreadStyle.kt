package lol.addi.t3craft.client.ui

import lol.addi.t3craft.client.t3.ConnectionState
import lol.addi.t3craft.client.t3.ThreadAttention
import lol.addi.t3craft.client.t3.attentionFor
import net.minecraft.network.chat.Component

object ThreadStyle {
    const val PANEL_BACKGROUND = 0xB0101014.toInt()
    const val PANEL_BORDER = 0x40FFFFFF
    const val TEXT_PRIMARY = 0xFFF2F2F2.toInt()
    const val TEXT_MUTED = 0xFF9A9AA6.toInt()
    const val ROW_HOVER = 0x30FFFFFF
    const val USER_ACCENT = 0xFF7FB7FF.toInt()
    const val ASSISTANT_ACCENT = 0xFFC9A0FF.toInt()

    fun color(attention: ThreadAttention): Int = when (attention) {
        ThreadAttention.NEEDS_INPUT -> 0xFFFFB347.toInt()
        ThreadAttention.RUNNING -> 0xFF5AC8FA.toInt()
        ThreadAttention.READY -> 0xFF7EE081.toInt()
        ThreadAttention.QUIET -> TEXT_MUTED
    }

    fun statusColor(status: String): Int =
        if (status == "failed") 0xFFFF6B6B.toInt() else color(attentionFor(status))

    fun sectionTitle(attention: ThreadAttention): Component =
        Component.translatable("t3craft.section.${attention.name.lowercase()}")

    fun statusLabel(status: String): Component = Component.translatable("t3craft.status.$status")

    fun connectionLabel(state: ConnectionState): Component =
        Component.translatable("t3craft.connection.${state.name.lowercase()}")
}
