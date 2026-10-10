package com.bigpickle.immersivedeath;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only entry point. This class is not loaded on a dedicated server.
 */
@Mod(value = ImmersiveDeath.MODID, dist = Dist.CLIENT)
public class ImmersiveDeathClient {
    public ImmersiveDeathClient(ModContainer modContainer) {
        NeoForge.EVENT_BUS.register(HudOverlay.class);
    }
}
