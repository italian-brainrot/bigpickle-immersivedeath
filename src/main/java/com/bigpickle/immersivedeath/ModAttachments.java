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
     * Items that were dropped when the player died but which we keep for them. Persisted so that a server
     * restart in between death and respawn does not lose them; it is intentionally not copied automatically
     * because the handler moves the contents into the new player's inventory itself.
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<List<ItemStack>>> KEPT_ITEMS =
            ATTACHMENTS.register("kept_items", () -> AttachmentType.<List<ItemStack>>builder(() -> List.of())
                    .serialize(ItemStack.CODEC.listOf())
                    .build());

    private ModAttachments() {}
}
