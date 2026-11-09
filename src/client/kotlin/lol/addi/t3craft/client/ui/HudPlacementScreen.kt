package lol.addi.t3craft.client.ui

import lol.addi.t3craft.client.config.ConfigStore
import lol.addi.t3craft.client.config.HudAnchor
import lol.addi.t3craft.client.t3.ConnectionState
import lol.addi.t3craft.client.t3.Environment
import lol.addi.t3craft.client.t3.ThreadRef
import lol.addi.t3craft.client.t3.MonitorSnapshot
import lol.addi.t3craft.client.t3.ThreadAttention
import lol.addi.t3craft.client.t3.ThreadMonitor
import lol.addi.t3craft.client.t3.ThreadSummary
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.time.Instant
import kotlin.math.roundToInt

class HudPlacementScreen(private val parent: Screen?) : Screen(Component.translatable("t3craft.placement.title")) {
    private var lastBox = HudBox(0, 0, 0, 0)
    private var grab: Pair<Double, Double>? = null

    override fun init() {
        addRenderableWidget(
            Button.builder(Component.translatable("gui.done")) { onClose() }
                .bounds(width / 2 - 50, height / 2 + 30, 100, 20)
                .build(),
        )
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        graphics.centeredText(font, title, width / 2, height / 2 - 20, ThreadStyle.TEXT_PRIMARY)
        graphics.centeredText(font, Component.translatable("t3craft.placement.hint"), width / 2, height / 2 - 6, ThreadStyle.TEXT_MUTED)
        lastBox = ThreadHud.draw(graphics, font, previewSnapshot(), ConfigStore.config)
        graphics.outline(lastBox.x - 1, lastBox.y - 1, lastBox.width + 2, lastBox.height + 2, ThreadStyle.color(ThreadAttention.NEEDS_INPUT))
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (contains(lastBox, event.x(), event.y())) {
            grab = (event.x() - lastBox.x) to (event.y() - lastBox.y)
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val (grabX, grabY) = grab ?: return super.mouseDragged(event, dragX, dragY)
        moveBoxTo((event.x() - grabX).roundToInt(), (event.y() - grabY).roundToInt())
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (grab != null) {
            grab = null
            ConfigStore.save()
            return true
        }
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (!contains(lastBox, mouseX, mouseY)) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        val config = ConfigStore.config
        config.hudScale = (config.hudScale + if (scrollY > 0) 0.1f else -0.1f).coerceIn(0.5f, 2.0f)
        ConfigStore.save()
        return true
    }

    override fun onClose() {
        ConfigStore.save()
        minecraft.gui.setScreen(parent)
    }

    private fun moveBoxTo(left: Int, top: Int) {
        val config = ConfigStore.config
        val x = left.coerceIn(0, (width - lastBox.width).coerceAtLeast(0))
        val y = top.coerceIn(0, (height - lastBox.height).coerceAtLeast(0))
        val right = x + lastBox.width / 2 > width / 2
        val bottom = y + lastBox.height / 2 > height / 2
        config.hudAnchor = HudAnchor.entries.first { it.right == right && it.bottom == bottom }
        config.hudOffsetX = if (right) width - lastBox.width - x else x
        config.hudOffsetY = if (bottom) height - lastBox.height - y else y
    }

    private fun contains(box: HudBox, x: Double, y: Double): Boolean =
        x >= box.x && x < box.x + box.width && y >= box.y && y < box.y + box.height

    private fun previewSnapshot(): MonitorSnapshot {
        val live = ThreadMonitor.snapshot
        if (live.connection == ConnectionState.CONNECTED && live.threads.isNotEmpty()) return live
        val now = Instant.now()
        fun sample(title: String, status: String) =
            ThreadSummary(ThreadRef(null, title), "", Environment(null, ""), title, status, now)
        return MonitorSnapshot(
            connection = ConnectionState.CONNECTED,
            threads = listOf(
                sample("Fix flaky auth tests", "waiting"),
                sample("Port HUD renderer to 26.3", "running"),
                sample("Write release notes", "completed"),
            ),
        )
    }
}
