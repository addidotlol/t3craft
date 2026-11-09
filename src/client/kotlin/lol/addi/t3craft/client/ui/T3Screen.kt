package lol.addi.t3craft.client.ui

import com.mojang.blaze3d.platform.InputConstants
import lol.addi.t3craft.client.t3.ConnectionState
import lol.addi.t3craft.client.t3.MessageRole
import lol.addi.t3craft.client.t3.ThreadAttention
import lol.addi.t3craft.client.t3.ThreadDetail
import lol.addi.t3craft.client.t3.ThreadMonitor
import lol.addi.t3craft.client.t3.ThreadRef
import lol.addi.t3craft.client.t3.ThreadSummary
import lol.addi.t3craft.client.t3.attentionFor
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.MultiLineEditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import java.time.Duration

class T3Screen(private val parent: Screen?) : Screen(Component.translatable("t3craft.list.title")) {
    private sealed interface SidebarEntry {
        val height: Int

        data class Section(val attention: ThreadAttention, val count: Int) : SidebarEntry {
            override val height = SECTION_HEIGHT
        }

        data class Row(val thread: ThreadSummary) : SidebarEntry {
            override val height = ROW_HEIGHT
        }
    }

    private data class MessageBlock(val role: MessageRole, val lines: List<FormattedCharSequence>, val bubbleWidth: Int) {
        val height: Int get() = lines.size * LINE_HEIGHT + if (role == MessageRole.USER) BUBBLE_PADDING * 2 else 0
    }

    private var sidebar: List<SidebarEntry> = emptyList()
    private var sidebarScroll = 0
    private var selected: ThreadRef? = null
    private var detail: ThreadDetail? = null
    private var loadError: String? = null
    private var loading = false
    private var sending = false
    private var ticksUntilRefresh = 0
    private var messageScrollFromBottom = 0
    private var blocks: List<MessageBlock> = emptyList()
    private var blocksFor: Pair<ThreadDetail?, Int>? = null

    private lateinit var replyBox: MultiLineEditBox
    private lateinit var sendButton: Button
    private lateinit var signInButton: Button

    private val sidebarWidth get() = (width * 0.3).toInt().coerceIn(130, 190)
    private val sidebarListTop get() = 24
    private val sidebarListBottom get() = height - 30
    private val paneLeft get() = sidebarWidth + 10
    private val paneRight get() = width - 8
    private val paneWidth get() = paneRight - paneLeft
    private val composerTop get() = height - 6 - COMPOSER_HEIGHT
    private val statusLineY get() = composerTop - 12
    private val messagesTop get() = 26
    private val messagesBottom get() = statusLineY - 4

    override fun init() {
        val draft = if (::replyBox.isInitialized) replyBox.value else ""
        replyBox = MultiLineEditBox.builder()
            .setX(paneLeft + 4)
            .setY(composerTop + 4)
            .setShowBackground(false)
            .setPlaceholder(Component.translatable("t3craft.thread.reply_hint"))
            .build(font, paneWidth - 8, 30, Component.translatable("t3craft.thread.reply"))
        replyBox.setCharacterLimit(20_000)
        replyBox.value = draft
        addRenderableWidget(replyBox)

        sendButton = addRenderableWidget(
            Button.builder(Component.translatable("t3craft.button.send")) { send() }
                .bounds(paneRight - 46, composerTop + COMPOSER_HEIGHT - 20, 42, 16)
                .build(),
        )
        signInButton = addRenderableWidget(
            Button.builder(Component.translatable("t3craft.button.sign_in")) { beginSignIn(this) }
                .bounds(paneLeft + paneWidth / 2 - 70, height / 2 - 10, 140, 20)
                .build(),
        )
        addRenderableWidget(
            Button.builder(Component.translatable("t3craft.button.settings")) {
                minecraft.gui.setScreen(SettingsScreen(this))
            }.bounds(6, height - 26, sidebarWidth - 8, 20).build(),
        )
        syncWithSnapshot()
        setInitialFocus(replyBox)
    }

    override fun tick() {
        syncWithSnapshot()
        if (selected != null && --ticksUntilRefresh <= 0) refreshDetail()
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        graphics.fill(0, 0, sidebarWidth, height, ThreadStyle.PANEL_BACKGROUND)
        graphics.verticalLine(sidebarWidth, 0, height, ThreadStyle.PANEL_BORDER)
        if (replyBox.visible) drawComposerFrame(graphics)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        drawSidebar(graphics, mouseX, mouseY)
        val snapshot = ThreadMonitor.snapshot
        when {
            snapshot.connection in SIGNED_OUT_STATES ->
                centeredInPane(graphics, Component.translatable("t3craft.list.signed_out"), height / 2 - 26, ThreadStyle.TEXT_PRIMARY)
            selected == null ->
                centeredInPane(graphics, Component.translatable("t3craft.thread.pick"), height / 2, ThreadStyle.TEXT_MUTED)
            else -> {
                drawHeader(graphics)
                drawMessages(graphics)
                drawStatusLine(graphics)
                drawComposerChips(graphics)
            }
        }
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val isEnter = event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER
        if (isEnter && !event.hasShiftDown() && focused === replyBox) {
            send()
            return true
        }
        return super.keyPressed(event)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            val row = sidebarRowAt(event.x(), event.y())
            if (row != null) {
                select(row.thread.ref)
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val step = (scrollY * LINE_HEIGHT * 3).toInt()
        when {
            mouseX < sidebarWidth -> sidebarScroll = (sidebarScroll - step).coerceIn(0, maxSidebarScroll())
            mouseY >= messagesTop && mouseY < messagesBottom ->
                messageScrollFromBottom = (messageScrollFromBottom + step).coerceIn(0, maxMessageScroll())
            else -> return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        }
        return true
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    override fun isPauseScreen(): Boolean = false

    private fun syncWithSnapshot() {
        val snapshot = ThreadMonitor.snapshot
        val signedOut = snapshot.connection in SIGNED_OUT_STATES
        sidebar = buildSidebar(snapshot.threads)
        sidebarScroll = sidebarScroll.coerceIn(0, maxSidebarScroll())
        if (selected == null) snapshot.threads.firstOrNull()?.let { select(it.ref) }
        val showConversation = !signedOut && selected != null
        signInButton.visible = signedOut
        replyBox.visible = showConversation
        sendButton.visible = showConversation
        sendButton.active = showConversation && !sending && replyBox.value.isNotBlank()
    }

    private fun select(ref: ThreadRef) {
        if (ref == selected) return
        selected = ref
        detail = null
        loadError = null
        blocks = emptyList()
        blocksFor = null
        messageScrollFromBottom = 0
        replyBox.value = ""
        refreshDetail()
    }

    private fun refreshDetail() {
        val ref = selected ?: return
        ticksUntilRefresh = if (currentAttention() == ThreadAttention.RUNNING) 40 else 200
        if (loading) return
        loading = true
        ThreadMonitor.readThread(ref).whenCompleteAsync({ result, failure ->
            loading = false
            if (ref != selected) return@whenCompleteAsync
            if (result != null) {
                detail = result
                loadError = null
            } else {
                loadError = (failure?.cause ?: failure)?.message
            }
        }, minecraft)
    }

    private fun send() {
        val ref = selected ?: return
        val message = replyBox.value.trim()
        if (message.isEmpty() || sending) return
        sending = true
        ThreadMonitor.sendMessage(ref, message).whenCompleteAsync({ _, failure ->
            sending = false
            if (failure == null) {
                if (ref == selected) replyBox.value = ""
                messageScrollFromBottom = 0
                refreshDetail()
            } else {
                T3Toasts.show(Component.translatable("t3craft.thread.send_failed"), (failure.cause ?: failure).message)
            }
        }, minecraft)
    }

    private fun selectedSummary(): ThreadSummary? = ThreadMonitor.snapshot.threads.firstOrNull { it.ref == selected }

    private fun currentStatus(): String? = detail?.status ?: selectedSummary()?.status

    private fun currentAttention(): ThreadAttention? = currentStatus()?.let(::attentionFor)

    private fun drawSidebar(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        graphics.text(font, Component.translatable("t3craft.hud.title"), 8, 8, ThreadStyle.TEXT_PRIMARY)
        val snapshot = ThreadMonitor.snapshot
        val connectionColor = if (snapshot.connection == ConnectionState.CONNECTED) ThreadStyle.color(ThreadAttention.READY) else ThreadStyle.TEXT_MUTED
        graphics.fill(sidebarWidth - 12, 10, sidebarWidth - 7, 15, connectionColor)

        graphics.enableScissor(0, sidebarListTop, sidebarWidth, sidebarListBottom)
        var y = sidebarListTop - sidebarScroll
        for (entry in sidebar) {
            if (y + entry.height >= sidebarListTop && y <= sidebarListBottom) {
                when (entry) {
                    is SidebarEntry.Section -> {
                        val label = ThreadStyle.sectionTitle(entry.attention).copy().append(" ${entry.count}")
                        graphics.text(font, label, 8, y + 4, ThreadStyle.TEXT_MUTED)
                    }
                    is SidebarEntry.Row -> drawSidebarRow(
                        graphics,
                        entry.thread,
                        y,
                        hovered = mouseX < sidebarWidth && mouseY >= y && mouseY < y + ROW_HEIGHT,
                    )
                }
            }
            y += entry.height
        }
        graphics.disableScissor()
    }

    private fun drawSidebarRow(graphics: GuiGraphicsExtractor, thread: ThreadSummary, y: Int, hovered: Boolean) {
        val isSelected = thread.ref == selected
        if (isSelected || hovered) {
            graphics.fill(4, y, sidebarWidth - 4, y + ROW_HEIGHT - 2, if (isSelected) SELECTED_ROW else ThreadStyle.ROW_HOVER)
        }
        if (isSelected) graphics.fill(4, y, 6, y + ROW_HEIGHT - 2, ThreadStyle.statusColor(thread.status))

        val (rightText, rightColor) = rowActivity(thread)
        val rightX = sidebarWidth - 8 - font.width(rightText)
        graphics.text(font, rightText, rightX, y + 3, rightColor)
        val location = if (thread.environment.isRemote) "${thread.projectTitle} · ${thread.environment.label}" else thread.projectTitle
        graphics.text(font, ellipsize(font, location, rightX - 18), 10, y + 3, ThreadStyle.TEXT_MUTED)
        graphics.text(font, ellipsize(font, thread.title, sidebarWidth - 20), 10, y + 13, ThreadStyle.TEXT_PRIMARY)
        graphics.text(font, ellipsize(font, Format.model(thread.model), sidebarWidth - 20), 10, y + 23, MODEL_COLOR)
    }

    private fun rowActivity(thread: ThreadSummary): Pair<String, Int> = when {
        thread.attention == ThreadAttention.RUNNING -> {
            val elapsed = thread.runStartedAt?.let { " " + Format.since(it) }.orEmpty()
            "Working$elapsed" to ThreadStyle.color(ThreadAttention.RUNNING)
        }
        thread.attention == ThreadAttention.NEEDS_INPUT -> "Needs you" to ThreadStyle.color(ThreadAttention.NEEDS_INPUT)
        thread.status == "failed" -> "Failed" to ThreadStyle.statusColor(thread.status)
        else -> Format.since(thread.updatedAt) to ThreadStyle.TEXT_MUTED
    }

    private fun drawHeader(graphics: GuiGraphicsExtractor) {
        val summary = selectedSummary()
        val title = detail?.title ?: summary?.title.orEmpty()
        val status = currentStatus()
        val statusLabel = status?.let(ThreadStyle::statusLabel)
        val statusWidth = statusLabel?.let(font::width) ?: 0
        statusLabel?.let { graphics.text(font, it, paneRight - statusWidth, 9, ThreadStyle.statusColor(status)) }

        val project = summary?.let {
            if (it.environment.isRemote) "${it.projectTitle} · ${it.environment.label}" else it.projectTitle
        }.orEmpty()
        val available = paneWidth - statusWidth - 10
        val projectText = if (project.isEmpty()) "" else ellipsize(font, project, available / 2) + "  /  "
        graphics.text(font, projectText, paneLeft, 9, ThreadStyle.TEXT_MUTED)
        val titleX = paneLeft + font.width(projectText)
        graphics.text(font, ellipsize(font, title, paneLeft + available - titleX), titleX, 9, ThreadStyle.TEXT_PRIMARY)
        graphics.horizontalLine(paneLeft, paneRight, 21, ThreadStyle.PANEL_BORDER)
    }

    private fun drawMessages(graphics: GuiGraphicsExtractor) {
        rebuildBlocksIfNeeded()
        if (blocks.isEmpty()) {
            val message = loadError?.let(Component::literal) ?: Component.translatable("t3craft.thread.loading")
            centeredInPane(graphics, message, (messagesTop + messagesBottom) / 2, ThreadStyle.TEXT_MUTED)
            return
        }
        graphics.enableScissor(paneLeft - 2, messagesTop, paneRight + 2, messagesBottom)
        val totalHeight = contentHeight()
        var y = messagesTop + 4 - (totalHeight - (messagesBottom - messagesTop)).coerceAtLeast(0) + messageScrollFromBottom
        for (block in blocks) {
            if (y + block.height >= messagesTop && y <= messagesBottom) drawBlock(graphics, block, y)
            y += block.height + BLOCK_GAP
        }
        graphics.disableScissor()
    }

    private fun drawBlock(graphics: GuiGraphicsExtractor, block: MessageBlock, top: Int) {
        if (block.role == MessageRole.USER) {
            val right = paneRight
            val left = right - block.bubbleWidth
            graphics.fill(left, top, right, top + block.height, USER_BUBBLE)
            graphics.outline(left, top, block.bubbleWidth, block.height, ThreadStyle.PANEL_BORDER)
            block.lines.forEachIndexed { index, line ->
                graphics.text(font, line, left + BUBBLE_PADDING, top + BUBBLE_PADDING + index * LINE_HEIGHT, ThreadStyle.TEXT_PRIMARY)
            }
        } else {
            block.lines.forEachIndexed { index, line ->
                graphics.text(font, line, paneLeft, top + index * LINE_HEIGHT, ASSISTANT_TEXT)
            }
        }
    }

    private fun drawStatusLine(graphics: GuiGraphicsExtractor) {
        val summary = selectedSummary()
        val model = Format.model(detail?.model ?: summary?.model.orEmpty())
        val (text, color) = when (currentAttention()) {
            ThreadAttention.RUNNING -> {
                val startedAt = detail?.latestRun?.startedAt ?: summary?.runStartedAt
                val elapsed = startedAt?.let { " for " + Format.since(it) }.orEmpty()
                "Working$elapsed" to ThreadStyle.color(ThreadAttention.RUNNING)
            }
            ThreadAttention.NEEDS_INPUT ->
                Component.translatable("t3craft.thread.pending_request").string to ThreadStyle.color(ThreadAttention.NEEDS_INPUT)
            else -> {
                val run = detail?.latestRun
                val finishedAt = run?.completedAt ?: summary?.updatedAt
                val took = run?.startedAt?.let { start -> run.completedAt?.let { end -> " in " + Format.duration(Duration.between(start, end)) } }.orEmpty()
                (finishedAt?.let { "Finished ${Format.since(it)} ago$took" } ?: "") to ThreadStyle.TEXT_MUTED
            }
        }
        var x = paneLeft
        if (currentAttention() == ThreadAttention.RUNNING) {
            val pulse = (System.currentTimeMillis() / 400 % 2 == 0L)
            graphics.fill(x, statusLineY + 2, x + 5, statusLineY + 7, if (pulse) color else color and 0x80FFFFFF.toInt())
            x += 9
        }
        graphics.text(font, text, x, statusLineY, color)
        x += font.width(text)
        if (model.isNotEmpty()) graphics.text(font, (if (text.isEmpty()) "" else "  ·  ") + model, x, statusLineY, MODEL_COLOR)
    }

    private fun drawComposerFrame(graphics: GuiGraphicsExtractor) {
        graphics.fill(paneLeft, composerTop, paneRight, composerTop + COMPOSER_HEIGHT, COMPOSER_BACKGROUND)
        graphics.outline(paneLeft, composerTop, paneWidth, COMPOSER_HEIGHT, if (focused === replyBox) COMPOSER_FOCUSED else ThreadStyle.PANEL_BORDER)
    }

    private fun drawComposerChips(graphics: GuiGraphicsExtractor) {
        val summary = selectedSummary()
        val chips = listOfNotNull(
            Format.model(detail?.model ?: summary?.model.orEmpty()).takeIf { it.isNotEmpty() },
            Format.runtimeMode(detail?.runtimeMode ?: summary?.runtimeMode.orEmpty()).takeIf { it.isNotEmpty() },
            detail?.branch?.takeIf { it.isNotEmpty() },
        )
        var x = paneLeft + 5
        val y = composerTop + COMPOSER_HEIGHT - 18
        val limit = paneRight - 52
        for (chip in chips) {
            val chipWidth = font.width(chip) + 8
            if (x + chipWidth > limit) break
            graphics.outline(x, y, chipWidth, 14, ThreadStyle.PANEL_BORDER)
            graphics.text(font, chip, x + 4, y + 3, ThreadStyle.TEXT_MUTED)
            x += chipWidth + 4
        }
        if (sending) graphics.text(font, Component.translatable("t3craft.thread.sending"), x + 2, y + 3, ThreadStyle.TEXT_MUTED)
    }

    private fun rebuildBlocksIfNeeded() {
        val key = detail to paneWidth
        if (blocksFor == key) return
        blocksFor = key
        val bubbleTextWidth = (paneWidth * 0.75).toInt() - BUBBLE_PADDING * 2
        blocks = detail?.messages.orEmpty().map { message ->
            val text = Component.literal(message.text.trim())
            if (message.role == MessageRole.USER) {
                val lines = font.split(text, bubbleTextWidth)
                val widest = lines.maxOfOrNull(font::width) ?: 0
                MessageBlock(message.role, lines, widest + BUBBLE_PADDING * 2)
            } else {
                MessageBlock(message.role, font.split(text, paneWidth), paneWidth)
            }
        }
        messageScrollFromBottom = messageScrollFromBottom.coerceIn(0, maxMessageScroll())
    }

    private fun contentHeight(): Int = blocks.sumOf { it.height + BLOCK_GAP } + 4

    private fun maxMessageScroll(): Int = (contentHeight() - (messagesBottom - messagesTop)).coerceAtLeast(0)

    private fun sidebarRowAt(x: Double, y: Double): SidebarEntry.Row? {
        if (x >= sidebarWidth || y < sidebarListTop || y >= sidebarListBottom) return null
        var top = sidebarListTop - sidebarScroll
        for (entry in sidebar) {
            if (y >= top && y < top + entry.height) return entry as? SidebarEntry.Row
            top += entry.height
        }
        return null
    }

    private fun maxSidebarScroll(): Int =
        (sidebar.sumOf { it.height } - (sidebarListBottom - sidebarListTop)).coerceAtLeast(0)

    private fun buildSidebar(threads: List<ThreadSummary>): List<SidebarEntry> =
        listOf(ThreadAttention.NEEDS_INPUT, ThreadAttention.RUNNING, ThreadAttention.READY).flatMap { attention ->
            val group = threads.filter { it.attention == attention }
            if (group.isEmpty()) emptyList()
            else listOf(SidebarEntry.Section(attention, group.size)) + group.map(SidebarEntry::Row)
        }

    private fun centeredInPane(graphics: GuiGraphicsExtractor, text: Component, y: Int, color: Int) {
        graphics.centeredText(font, text, paneLeft + paneWidth / 2, y, color)
    }

    private companion object {
        const val SECTION_HEIGHT = 14
        const val ROW_HEIGHT = 34
        const val LINE_HEIGHT = 10
        const val BUBBLE_PADDING = 5
        const val BLOCK_GAP = 8
        const val COMPOSER_HEIGHT = 58
        const val SELECTED_ROW = 0x40FFFFFF
        const val USER_BUBBLE = 0xC0262630.toInt()
        const val COMPOSER_BACKGROUND = 0xC0141418.toInt()
        const val COMPOSER_FOCUSED = 0x80B8A0FF.toInt()
        const val ASSISTANT_TEXT = 0xFFE6E6EA.toInt()
        const val MODEL_COLOR = 0xFFB39DFF.toInt()
        val SIGNED_OUT_STATES = setOf(ConnectionState.SIGNED_OUT, ConnectionState.SIGN_IN_EXPIRED, ConnectionState.SIGNING_IN)
    }
}
