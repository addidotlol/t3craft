package lol.addi.t3craft.client.ui

import lol.addi.t3craft.client.config.ConfigStore
import lol.addi.t3craft.client.config.HudAnchor
import lol.addi.t3craft.client.t3.ConnectionState
import lol.addi.t3craft.client.t3.ThreadMonitor
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.CommonComponents

class SettingsScreen(private val parent: Screen?) : Screen(Component.translatable("t3craft.settings.title")) {
    private val config = ConfigStore.config
    private val serverUrlAtOpen = config.normalizedServerUrl()
    private lateinit var serverField: EditBox
    private lateinit var accountButton: Button

    override fun init() {
        val columnLeft = width / 2 - 155
        val columnRight = width / 2 + 5
        var y = 40

        serverField = EditBox(font, columnLeft, y, 310, 20, Component.translatable("t3craft.settings.server"))
        serverField.setMaxLength(256)
        serverField.value = config.serverUrl
        serverField.setResponder { config.serverUrl = it }
        addRenderableWidget(serverField)
        y += 24

        accountButton = addRenderableWidget(
            Button.builder(accountLabel()) { toggleAccount() }.bounds(columnLeft, y, 310, 20).build(),
        )
        y += 32

        addRenderableWidget(
            onOffToggle("t3craft.settings.hud_enabled", config.hudEnabled, columnLeft, y) { config.hudEnabled = it },
        )
        addRenderableWidget(
            CycleButton.builder({ anchor: HudAnchor -> Component.translatable(anchor.labelKey) }, config.hudAnchor)
                .withValues(HudAnchor.entries)
                .create(columnRight, y, 150, 20, Component.translatable("t3craft.settings.anchor")) { _, value ->
                    config.hudAnchor = value
                },
        )
        y += 24

        addRenderableWidget(
            CycleButton.builder({ scale: Float -> Component.literal("${(scale * 100).toInt()}%") }, closest(SCALES, config.hudScale))
                .withValues(SCALES)
                .create(columnLeft, y, 150, 20, Component.translatable("t3craft.settings.scale")) { _, value ->
                    config.hudScale = value
                },
        )
        addRenderableWidget(
            CycleButton.builder({ rows: Int -> Component.literal(rows.toString()) }, closest(ROW_COUNTS, config.hudMaxRows))
                .withValues(ROW_COUNTS)
                .create(columnRight, y, 150, 20, Component.translatable("t3craft.settings.rows")) { _, value ->
                    config.hudMaxRows = value
                },
        )
        y += 24

        addRenderableWidget(
            onOffToggle("t3craft.settings.show_ready", config.showReadyThreads, columnLeft, y) { config.showReadyThreads = it },
        )
        addRenderableWidget(
            onOffToggle("t3craft.settings.notify", config.notifyOnChange, columnRight, y) { config.notifyOnChange = it },
        )
        y += 24

        addRenderableWidget(
            Button.builder(Component.translatable("t3craft.button.move_hud")) {
                ConfigStore.save()
                minecraft.gui.setScreen(HudPlacementScreen(this))
            }.bounds(columnLeft, y, 310, 20).build(),
        )

        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build(),
        )
    }

    override fun tick() {
        accountButton.message = accountLabel()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        graphics.centeredText(font, title, width / 2, 12, ThreadStyle.TEXT_PRIMARY)
        graphics.text(font, Component.translatable("t3craft.settings.server"), width / 2 - 155, 29, ThreadStyle.TEXT_MUTED)
        val snapshot = ThreadMonitor.snapshot
        val status = snapshot.error?.let { Component.literal(it) } ?: ThreadStyle.connectionLabel(snapshot.connection)
        graphics.centeredText(font, status, width / 2, height - 42, ThreadStyle.TEXT_MUTED)
    }

    override fun onClose() {
        ConfigStore.save()
        if (config.normalizedServerUrl() != serverUrlAtOpen) ThreadMonitor.reconnect()
        minecraft.gui.setScreen(parent)
    }

    override fun isPauseScreen(): Boolean = false

    private fun toggleAccount() {
        when (ThreadMonitor.snapshot.connection) {
            ConnectionState.SIGNED_OUT, ConnectionState.SIGN_IN_EXPIRED -> {
                ConfigStore.save()
                beginSignIn(this)
            }
            else -> ThreadMonitor.signOut()
        }
    }

    private fun accountLabel(): Component = when (ThreadMonitor.snapshot.connection) {
        ConnectionState.SIGNED_OUT, ConnectionState.SIGN_IN_EXPIRED -> Component.translatable("t3craft.button.sign_in")
        ConnectionState.SIGNING_IN -> Component.translatable("t3craft.button.cancel_sign_in")
        else -> Component.translatable("t3craft.button.sign_out")
    }

    private fun onOffToggle(key: String, initial: Boolean, x: Int, y: Int, apply: (Boolean) -> Unit): CycleButton<Boolean> =
        CycleButton.onOffBuilder(initial).create(x, y, 150, 20, Component.translatable(key)) { _, value -> apply(value) }

    private fun <T : Comparable<T>> closest(options: List<T>, value: T): T =
        options.firstOrNull { it >= value } ?: options.last()

    private companion object {
        val SCALES = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val ROW_COUNTS = listOf(3, 4, 6, 8, 10, 12)
    }
}
