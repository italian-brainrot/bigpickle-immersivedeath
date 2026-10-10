package com.bigpickle.immersivedeath;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Immersive Death: an alternative to {@code keepInventory=true}.
 */
@Mod(ImmersiveDeath.MODID)
public class ImmersiveDeath {
    public static final String MODID = "immersivedeath";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB =
            CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.immersivedeath"))
                    .icon(() -> new ItemStack(ModItems.RECLAIMED_HEART.get()))
                    .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
                    .displayItems((parameters, output) -> output.accept(ModItems.RECLAIMED_HEART.get()))
                    .build());

    public ImmersiveDeath(IEventBus modEventBus, ModContainer modContainer) {
        ModItems.ITEMS.register(modEventBus);
        ModAttachments.ATTACHMENTS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);

        // Server-side (and common) game events.
        NeoForge.EVENT_BUS.register(DeathHandler.class);
    }
}
