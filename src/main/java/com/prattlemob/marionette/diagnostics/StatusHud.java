package com.prattlemob.marionette.diagnostics;

import java.util.List;
import java.util.function.Supplier;

import com.prattlemob.marionette.bridge.protocol.StatusReport;
import com.prattlemob.marionette.config.MarionetteConfig;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.client.gui.GuiLayer;

/**
 * Top-left status overlay (M5.2): small text on a translucent backdrop, like
 * the F3 screen it gives way to. Hidden by the HUD toggle, F1 and the debug
 * screen. Client thread only.
 */
public final class StatusHud implements GuiLayer {
    private static final int MARGIN = 2;
    private static final int PADDING = 2;
    private static final int BACKDROP = 0x90000000;

    private final Supplier<StatusReport> report;
    private final Supplier<String> lockoutKey;
    private final Supplier<String> rearmKey;

    public StatusHud(Supplier<StatusReport> report, Supplier<String> lockoutKey, Supplier<String> rearmKey) {
        this.report = report;
        this.lockoutKey = lockoutKey;
        this.rearmKey = rearmKey;
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!MarionetteConfig.hudEnabled || minecraft.options.hideGui
                || minecraft.getDebugOverlay().showDebugScreen()) {
            return;
        }
        List<HudLines.Line> lines = HudLines.of(report.get(), lockoutKey.get(), rearmKey.get());
        Font font = minecraft.font;
        int y = MARGIN;
        for (HudLines.Line line : lines) {
            int width = font.width(line.text());
            graphics.fill(MARGIN, y, MARGIN + width + 2 * PADDING, y + font.lineHeight + 1, BACKDROP);
            graphics.drawString(font, line.text(), MARGIN + PADDING, y + 1, line.color(), false);
            y += font.lineHeight + 1;
        }
    }
}
