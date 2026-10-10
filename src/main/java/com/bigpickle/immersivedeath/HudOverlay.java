package com.bigpickle.immersivedeath;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Draws the permanently lost maximum health next to the vanilla health bar, so the player can always see how much
 * the Reclaimed Heart would restore.
 */
public final class HudOverlay {
    /** Reused item icon; created lazily because the registry is not ready at class-load time. */
    private static ItemStack heartIcon;

    private HudOverlay() {}

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.hideGui || player.isSpectator()) {
            return;
        }

        DeathPenaltyState state = player.getData(ModAttachments.DEATH_PENALTY.get());
        int lostHp = state.lostHp();
        if (lostHp <= 0) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        int x = graphics.guiWidth() / 2 - 91;
        int y = graphics.guiHeight() - 39 - 16;

        graphics.renderItem(icon(), x, y);
        graphics.drawString(minecraft.font, "-" + formatHearts(lostHp), x + 18, y + 5, 0xFFFF5555, true);
    }

    private static ItemStack icon() {
        if (heartIcon == null) {
            heartIcon = new ItemStack(ModItems.RECLAIMED_HEART.get());
        }
        return heartIcon;
    }

    /** Lost health is measured in half-hearts; show a whole number, or {@code .5} for the rare odd value. */
    private static String formatHearts(int lostHp) {
        if (lostHp % 2 == 0) {
            return Integer.toString(lostHp / 2);
        }
        return (lostHp / 2) + ".5";
    }
}
