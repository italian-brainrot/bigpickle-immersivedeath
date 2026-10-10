package com.bigpickle.immersivedeath;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ImmersiveDeath.MODID);

    public static final DeferredItem<ReclaimedHeartItem> RECLAIMED_HEART =
            ITEMS.registerItem("reclaimed_heart", ReclaimedHeartItem::new, new Item.Properties());

    private ModItems() {}
}
