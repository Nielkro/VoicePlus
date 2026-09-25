package ru.penik.voice.plus.gui

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import ru.penik.voice.plus.plugins.VoicePlusConfig

class VoicePlusConfigScreen(private val parent: Screen?) : Screen(Component.literal("Voice Plus Settings")) {
    private var enableMetricsCheckbox: Checkbox? = null

    override fun init() {
        val centerX = width / 2
        val startY = height / 3

        enableMetricsCheckbox = Checkbox.builder(
            Component.literal("Enable anonymous telemetry (mod usage & versions)"),
            font
        ).pos(centerX - 130, startY)
         .selected(VoicePlusConfig.enableMetrics)
         .build()
        addRenderableWidget(enableMetricsCheckbox!!)

        addRenderableWidget(
            Button.builder(Component.literal("Done")) {
                VoicePlusConfig.enableMetrics = enableMetricsCheckbox?.selected() ?: true
                VoicePlusConfig.save()
                minecraft?.setScreen(parent)
            }.bounds(centerX - 100, startY + 50, 200, 20).build()
        )
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(guiGraphics, mouseX, mouseY, partialTick)
        guiGraphics.drawCenteredString(font, title, width / 2, 25, 0xFFFFFF)
        guiGraphics.drawCenteredString(
            font,
            Component.literal("No personal data, IPs, or player IDs are ever collected."),
            width / 2,
            height / 3 + 24,
            0x888888
        )
    }

    override fun onClose() {
        minecraft?.setScreen(parent)
    }
}
