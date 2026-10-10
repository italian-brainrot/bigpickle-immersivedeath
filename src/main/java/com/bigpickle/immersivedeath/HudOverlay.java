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
        String label = "-" + formatHearts(lostHp);
        // Right-align the icon + label group against the left edge of the vanilla health bar (which starts at
        // guiWidth/2 - 91) so it never overlaps the health, armor or food rows.
        int groupWidth = 18 + minecraft.font.width(label);
        int x = graphics.guiWidth() / 2 - 91 - groupWidth - 2;
        int y = graphics.guiHeight() - 42;

        graphics.renderItem(icon(), x, y);
        graphics.drawString(minecraft.font, label, x + 18, y + 5, 0xFFFF5555, true);
    }

    private static ItemStack icon() {
        if (heartIcon == null) {
            heartIcon = new ItemStack(ModItems.RECLAIMED_HEART.get());
        }
        return heartIcon;
    }

    /** Lost health is measured in health points (2.0 HP = one heart); show a whole number, or {@code .5} for odd amounts. */
    private static String formatHearts(int lostHp) {
        if (lostHp % 2 == 0) {
            return Integer.toString(lostHp / 2);
        }
        return (lostHp / 2) + ".5";
    }
}
