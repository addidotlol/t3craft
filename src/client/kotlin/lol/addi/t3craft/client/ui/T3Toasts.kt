package lol.addi.t3craft.client.ui

import lol.addi.t3craft.client.t3.ThreadAttention
import lol.addi.t3craft.client.t3.ThreadTransition
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.Component

object T3Toasts {
    private val id = SystemToast.SystemToastId(6000L)

    fun show(title: Component, detail: String?) {
        SystemToast.addOrUpdate(
            Minecraft.getInstance().gui.toastManager(),
            id,
            title,
            Component.literal(detail.orEmpty()),
        )
    }

    fun announce(transitions: List<ThreadTransition>) {
        val needsInput = transitions.filter { it.thread.attention == ThreadAttention.NEEDS_INPUT }
        val finished = transitions.filter { it.thread.attention == ThreadAttention.READY }
        val headline = needsInput.firstOrNull() ?: finished.first()
        val title = when {
            transitions.size > 1 -> Component.translatable("t3craft.toast.several", needsInput.size, finished.size)
            needsInput.isNotEmpty() -> Component.translatable("t3craft.toast.needs_input")
            headline.thread.status == "failed" -> Component.translatable("t3craft.toast.failed")
            else -> Component.translatable("t3craft.toast.finished")
        }
        show(title, headline.thread.title)
    }
}
