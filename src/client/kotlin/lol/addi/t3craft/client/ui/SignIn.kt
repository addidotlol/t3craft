package lol.addi.t3craft.client.ui

import lol.addi.t3craft.client.t3.ThreadMonitor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

fun beginSignIn(returnTo: Screen) {
    val minecraft = Minecraft.getInstance()
    ThreadMonitor.signIn().whenCompleteAsync({ login, failure ->
        if (login != null) ConfirmLinkScreen.confirmLinkNow(returnTo, login.authorizeUri, true)
        else T3Toasts.show(Component.translatable("t3craft.signin.failed"), (failure?.cause ?: failure)?.message)
    }, minecraft)
}
