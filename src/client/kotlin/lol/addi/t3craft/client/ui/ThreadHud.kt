package lol.addi.t3craft.client.ui

import lol.addi.t3craft.client.config.ConfigStore
import lol.addi.t3craft.client.config.T3craftConfig
import lol.addi.t3craft.client.t3.ConnectionState
import lol.addi.t3craft.client.t3.MonitorSnapshot
import lol.addi.t3craft.client.t3.ThreadAttention
import lol.addi.t3craft.client.t3.ThreadMonitor
import lol.addi.t3craft.client.t3.ThreadSummary
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

data class HudBox(val x: Int, val y: Int, val width: Int, val height: Int)

object ThreadHud : HudElement {
    private const val WIDTH = 220
    private const val PADDING = 4
    private const val ROW_HEIGHT = 11
    private const val DOT_SIZE = 5

    var hiddenByKey = false

    override fun extractRenderState(graphics: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        val config = ConfigStore.config
        if (!config.hudEnabled || hiddenByKey) return
        val minecraft = Minecraft.getInstance()
        val screen = minecraft.gui.screen()
        if (screen is HudPlacementScreen || screen is T3Screen) return
        val snapshot = ThreadMonitor.snapshot
        if (snapshot.connection == ConnectionState.SIGNED_OUT) return
        draw(graphics, minecraft.font, snapshot, config)
    }

    fun draw(graphics: GuiGraphicsExtractor, font: Font, snapshot: MonitorSnapshot, config: T3craftConfig): HudBox {
        val rows = visibleRows(snapshot, config)
        val hiddenCount = (eligibleThreads(snapshot, config).size - rows.size).coerceAtLeast(0)
        val box = boxFor(graphics.guiWidth(), graphics.guiHeight(), contentHeight(font, snapshot, rows, hiddenCount), config)

        graphics.pose().pushMatrix()
        graphics.pose().translate(box.x.toFloat(), box.y.toFloat())
        graphics.pose().scale(config.hudScale, config.hudScale)
        drawPanel(graphics, font, snapshot, rows, hiddenCount)
        graphics.pose().popMatrix()
        return box
    }

    fun boxFor(screenWidth: Int, screenHeight: Int, unscaledHeight: Int, config: T3craftConfig): HudBox {
        val width = (WIDTH * config.hudScale).toInt()
        val height = (unscaledHeight * config.hudScale).toInt()
        val anchor = config.hudAnchor
        val x = if (anchor.right) screenWidth - width - config.hudOffsetX else config.hudOffsetX
        val y = if (anchor.bottom) screenHeight - height - config.hudOffsetY else config.hudOffsetY
        return HudBox(
            x.coerceIn(0, (screenWidth - width).coerceAtLeast(0)),
            y.coerceIn(0, (screenHeight - height).coerceAtLeast(0)),
            width,
            height,
        )
    }

    private fun contentHeight(font: Font, snapshot: MonitorSnapshot, rows: List<ThreadSummary>, hiddenCount: Int): Int {
        val bodyLines = when {
            snapshot.connection != ConnectionState.CONNECTED -> 1
            rows.isEmpty() -> 1
            else -> rows.size + if (hiddenCount > 0) 1 else 0
        }
        return PADDING * 2 + font.lineHeight + 3 + bodyLines * ROW_HEIGHT
    }

    private fun drawPanel(
        graphics: GuiGraphicsExtractor,
        font: Font,
        snapshot: MonitorSnapshot,
        rows: List<ThreadSummary>,
        hiddenCount: Int,
    ) {
        val height = contentHeight(font, snapshot, rows, hiddenCount)
        graphics.fill(0, 0, WIDTH, height, ThreadStyle.PANEL_BACKGROUND)
        graphics.outline(0, 0, WIDTH, height, ThreadStyle.PANEL_BORDER)
        graphics.text(font, Component.translatable("t3craft.hud.title"), PADDING, PADDING, ThreadStyle.TEXT_PRIMARY)
        drawCounts(graphics, font, snapshot)

        var y = PADDING + font.lineHeight + 3
        graphics.horizontalLine(PADDING, WIDTH - PADDING - 1, y - 2, ThreadStyle.PANEL_BORDER)

        if (snapshot.connection != ConnectionState.CONNECTED) {
            graphics.text(font, ThreadStyle.connectionLabel(snapshot.connection), PADDING, y + 1, ThreadStyle.TEXT_MUTED)
            return
        }
        if (rows.isEmpty()) {
            graphics.text(font, Component.translatable("t3craft.hud.all_quiet"), PADDING, y + 1, ThreadStyle.TEXT_MUTED)
            return
        }
        for (thread in rows) {
            drawRow(graphics, font, thread, y)
            y += ROW_HEIGHT
        }
        if (hiddenCount > 0) {
            graphics.text(font, Component.translatable("t3craft.hud.more", hiddenCount), PADDING, y + 1, ThreadStyle.TEXT_MUTED)
        }
    }

    private fun drawCounts(graphics: GuiGraphicsExtractor, font: Font, snapshot: MonitorSnapshot) {
        if (snapshot.connection != ConnectionState.CONNECTED) return
        var right = WIDTH - PADDING
        for (attention in listOf(ThreadAttention.READY, ThreadAttention.RUNNING, ThreadAttention.NEEDS_INPUT)) {
            val count = snapshot.threadsWith(attention).size
            if (count == 0) continue
            val label = count.toString()
            right -= font.width(label)
            graphics.text(font, label, right, PADDING, ThreadStyle.color(attention))
            right -= DOT_SIZE + 2
            graphics.fill(right, PADDING + 2, right + DOT_SIZE, PADDING + 2 + DOT_SIZE, ThreadStyle.color(attention))
            right -= 6
        }
    }

    private fun drawRow(graphics: GuiGraphicsExtractor, font: Font, thread: ThreadSummary, y: Int) {
        val color = ThreadStyle.statusColor(thread.status)
        graphics.fill(PADDING, y + 3, PADDING + DOT_SIZE, y + 3 + DOT_SIZE, color)
        val status = rowStatus(thread)
        val statusWidth = font.width(status)
        graphics.text(font, status, WIDTH - PADDING - statusWidth, y + 1, color)
        val titleX = PADDING + DOT_SIZE + 4
        val titleWidth = WIDTH - PADDING - statusWidth - 6 - titleX
        graphics.text(font, ellipsize(font, thread.title, titleWidth), titleX, y + 1, ThreadStyle.TEXT_PRIMARY)
    }

    private fun rowStatus(thread: ThreadSummary): Component {
        if (thread.attention != ThreadAttention.RUNNING) return ThreadStyle.statusLabel(thread.status)
        val parts = listOfNotNull(
            Format.model(thread.model).takeIf { it.isNotEmpty() },
            thread.runStartedAt?.let(Format::since),
        )
        return if (parts.isEmpty()) ThreadStyle.statusLabel(thread.status) else Component.literal(parts.joinToString(" · "))
    }

    private fun eligibleThreads(snapshot: MonitorSnapshot, config: T3craftConfig): List<ThreadSummary> =
        snapshot.threads.filter {
            it.attention == ThreadAttention.NEEDS_INPUT ||
                it.attention == ThreadAttention.RUNNING ||
                (it.attention == ThreadAttention.READY && config.showReadyThreads)
        }

    private fun visibleRows(snapshot: MonitorSnapshot, config: T3craftConfig): List<ThreadSummary> =
        if (snapshot.connection == ConnectionState.CONNECTED) eligibleThreads(snapshot, config).take(config.hudMaxRows) else emptyList()
}

fun ellipsize(font: Font, text: String, maxWidth: Int): String {
    if (font.width(text) <= maxWidth) return text
    val ellipsis = "…"
    return font.plainSubstrByWidth(text, (maxWidth - font.width(ellipsis)).coerceAtLeast(0)).trimEnd() + ellipsis
}
