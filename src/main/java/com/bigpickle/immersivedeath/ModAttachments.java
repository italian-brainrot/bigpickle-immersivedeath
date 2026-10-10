package com.bigpickle.immersivedeath;

import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, ImmersiveDeath.MODID);

    /**
     * The death penalty state. Persisted, copied on death and synchronised to the owning client so the
     * HUD can display how much maximum health has been lost.
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<DeathPenaltyState>> DEATH_PENALTY =
            ATTACHMENTS.register("death_penalty", () -> AttachmentType.builder(() -> DeathPenaltyState.EMPTY)
                    .serialize(DeathPenaltyState.CODEC)
                    .copyOnDeath()
                    .sync((holder, to) -> holder == to, DeathPenaltyState.STREAM_CODEC)
                    .build());

    /**
     * Extra drops captured on death that are not part of the vanilla player inventory (for example drops added by
     * other mods). The vanilla inventory itself is restored from {@link #INVENTORY_SNAPSHOT}; this list only holds
     * the overflow. It is persisted so that a server restart in between death and respawn does not lose anything,
     * and it is intentionally not copied on death because the death handler moves the values to the new player
     * entity itself.
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<List<ItemStack>>> KEPT_ITEMS =
            ATTACHMENTS.register("kept_items", () -> AttachmentType.<List<ItemStack>>builder(() -> List.of())
                    .serialize(ItemStack.OPTIONAL_CODEC.listOf())
                    .build());

    /**
     * Slot-by-slot snapshot of the player's vanilla inventory (main + armor + off-hand) taken at the moment of a
     * death this mod handles. On respawn it is written back into the exact same slots, so equipped armor and the
     * off-hand item are preserved and nothing can be lost to a full main inventory. Persisted so a server restart
     * in between death and respawn does not lose it; not copy-on-death because the handler relays it to the new
     * player itself.
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<List<ItemStack>>> INVENTORY_SNAPSHOT =
            ATTACHMENTS.register("inventory_snapshot", () -> AttachmentType.<List<ItemStack>>builder(() -> List.of())
                    .serialize(ItemStack.OPTIONAL_CODEC.listOf())
                    .build());

    private ModAttachments() {}
}