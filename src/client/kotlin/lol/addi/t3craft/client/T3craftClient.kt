package lol.addi.t3craft.client

import com.mojang.blaze3d.platform.InputConstants
import lol.addi.t3craft.client.config.ConfigStore
import lol.addi.t3craft.client.t3.ThreadMonitor
import lol.addi.t3craft.client.ui.T3Toasts
import lol.addi.t3craft.client.ui.ThreadHud
import lol.addi.t3craft.client.ui.T3Screen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier

class T3craftClient : ClientModInitializer {
    private val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"))

    private val openThreadsKey = KeyMappingHelper.registerKeyMapping(
        KeyMapping("key.t3craft.open_threads", InputConstants.Type.KEYBOARD, InputConstants.KEY_Y, category),
    )
    private val toggleHudKey = KeyMappingHelper.registerKeyMapping(
        KeyMapping("key.t3craft.toggle_hud", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.value, category),
    )

    override fun onInitializeClient() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "threads"), ThreadHud)
        ClientTickEvents.END_CLIENT_TICK.register(::handleKeys)
        ThreadMonitor.onTransitions = { transitions ->
            val minecraft = Minecraft.getInstance()
            if (ConfigStore.config.notifyOnChange) minecraft.execute { T3Toasts.announce(transitions) }
        }
        ClientLifecycleEvents.CLIENT_STARTED.register { ThreadMonitor.start() }
    }

    private fun handleKeys(minecraft: Minecraft) {
        while (openThreadsKey.consumeClick()) {
            if (minecraft.gui.screen() == null) minecraft.gui.setScreen(T3Screen(null))
        }
        while (toggleHudKey.consumeClick()) {
            ThreadHud.hiddenByKey = !ThreadHud.hiddenByKey
        }
    }

    private companion object {
        const val MOD_ID = "t3craft"
    }
}
