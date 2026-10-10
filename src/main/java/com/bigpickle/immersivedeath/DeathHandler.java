package com.bigpickle.immersivedeath;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * All of the server-side logic that turns a vanilla death into the immersive alternative.
 *
 * <p>Design notes for compatibility with large mod packs:
 * <ul>
 *     <li>We never touch any event other mods use to react to death, so their death triggers keep working.</li>
 *     <li>We only cancel {@link LivingDropsEvent} when nobody else already handled it, so grave-stone style mods
 *     take priority and we never cause item duplication.</li>
 *     <li>Maximum health is changed through the attribute base value, which is non-invasive and stacks with
 *     attribute modifiers added by other mods.</li>
 * </ul>
 */
public final class DeathHandler {
    /** A second death within this window only costs experience, to avoid punishing death loops. */
    public static final long RAPID_WINDOW_MS = 60_000L;

    /** Health removed per death: 2 HP (one heart). */
    private static final int LOST_HP_PER_DEATH = 2;
    /** The player can never be reduced below a single point of maximum health. */
    private static final double MIN_MAX_HEALTH = 1.0D;

    private static final List<EffectSpec> EFFECTS = List.of(
            new EffectSpec(MobEffects.CONFUSION, DeathPenaltyState.CLEARED_NAUSEA, 9, 30_000L),
            new EffectSpec(MobEffects.BLINDNESS, DeathPenaltyState.CLEARED_BLINDNESS, 15, 60_000L),
            new EffectSpec(MobEffects.WEAKNESS, DeathPenaltyState.CLEARED_WEAKNESS, 18, 600_000L));

    /** Guards {@link #onEffectRemoved} so our own effect refreshes are not mistaken for an external cure. */
    private static boolean suppressingRemoval = false;

    private DeathHandler() {}

    // Death -----------------------------------------------------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player) || !penaltiesEnabled(player)) {
            return;
        }

        long now = System.currentTimeMillis();
        DeathPenaltyState state = player.getData(ModAttachments.DEATH_PENALTY.get());
        boolean rapid = state.lastDeath() > 0L && (now - state.lastDeath()) < RAPID_WINDOW_MS;

        int flags = state.flags() | DeathPenaltyState.PENDING;
        flags = rapid ? (flags | DeathPenaltyState.RAPID) : (flags & ~DeathPenaltyState.RAPID);

        setState(player, state.withLastDeath(now).withFlags(flags));
    }

    /**
     * Runs at the lowest priority so that any other mod (graves, souls, etc.) that cancels the event keeps ownership
     * of the drops. When we are the ones handling it we capture the items and cancel the event so nothing is
     * scattered on the ground.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDrops(LivingDropsEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player) || !penaltiesEnabled(player)) {
            return;
        }

        List<ItemStack> kept = new ArrayList<>();
        for (ItemEntity drop : event.getDrops()) {
            ItemStack stack = drop.getItem();
            if (!stack.isEmpty()) {
                kept.add(stack.copy());
            }
        }
        player.setData(ModAttachments.KEPT_ITEMS.get(), List.copyOf(kept));
        event.setCanceled(true);
    }

    // Respawn / clone / login ----------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) {
            return;
        }

        Player original = event.getOriginal();
        Player clone = event.getEntity();

        List<ItemStack> kept = original.getData(ModAttachments.KEPT_ITEMS.get());
        if (!kept.isEmpty()) {
            original.setData(ModAttachments.KEPT_ITEMS.get(), List.of());
            giveItems(clone, kept);
        }

        // Make sure the penalty state follows the player regardless of listener ordering.
        clone.setData(ModAttachments.DEATH_PENALTY.get(), original.getData(ModAttachments.DEATH_PENALTY.get()));
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            applyPendingPenalty(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // Handles logging back in while dead (e.g. after a restart): the items and penalty are still pending.
        List<ItemStack> kept = player.getData(ModAttachments.KEPT_ITEMS.get());
        if (!kept.isEmpty()) {
            player.setData(ModAttachments.KEPT_ITEMS.get(), List.of());
            giveItems(player, kept);
        }
        applyPendingPenalty(player);
    }

    // Effect decay ---------------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        DeathPenaltyState state = player.getExistingDataOrNull(ModAttachments.DEATH_PENALTY.get());
        if (state == null || state.penaltyStart() <= 0L) {
            return;
        }

        long elapsed = Math.max(0L, System.currentTimeMillis() - state.penaltyStart());
        if (!updateEffects(player, state, elapsed)) {
            setState(player, state.withPenaltyStart(0L).withoutCleared());
        }
    }

    /** Detects penalties being cleared by milk (or any other cure) so the decay does not immediately re-apply them. */
    @SubscribeEvent
    public static void onEffectRemoved(MobEffectEvent.Remove event) {
        if (suppressingRemoval) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        int bit = clearedBitFor(event.getEffect());
        if (bit == 0) {
            return;
        }
        DeathPenaltyState state = player.getExistingDataOrNull(ModAttachments.DEATH_PENALTY.get());
        if (state == null || state.penaltyStart() <= 0L || (state.flags() & bit) != 0) {
            return;
        }
        setState(player, state.withFlag(bit));
    }

    // Penalty application --------------------------------------------------------------------------------------------------

    private static void applyPendingPenalty(ServerPlayer player) {
        DeathPenaltyState state = player.getData(ModAttachments.DEATH_PENALTY.get());

        if (!state.isPending()) {
            refreshEffects(player, state);
            return;
        }

        if (!penaltiesEnabled(player)) {
            setState(player, state.withoutFlag(DeathPenaltyState.PENDING));
            return;
        }

        if (state.isRapid()) {
            // Only experience was lost; any penalty already running keeps decaying.
            setState(player, state.withoutFlag(DeathPenaltyState.PENDING));
            refreshEffects(player, state);
            return;
        }

        FoodData food = player.getFoodData();
        food.setFoodLevel(6);
        food.setSaturation(0.0F);

        int lost = 0;
        AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            double base = maxHealth.getBaseValue();
            double reduced = Math.max(MIN_MAX_HEALTH, base - LOST_HP_PER_DEATH);
            lost = (int) Math.round(base - reduced);
            if (lost > 0) {
                maxHealth.setBaseValue(reduced);
                if (player.getHealth() > reduced) {
                    player.setHealth((float) reduced);
                }
            }
        }

        int flags = state.withoutFlag(DeathPenaltyState.PENDING)
                .withoutFlag(DeathPenaltyState.RAPID)
                .withoutCleared()
                .flags();
        DeathPenaltyState applied = state.withLostHp(state.lostHp() + lost)
                .withPenaltyStart(System.currentTimeMillis())
                .withFlags(flags);

        setState(player, applied);
        updateEffects(player, applied, 0L);
        player.displayClientMessage(
                Component.translatable("immersivedeath.message.heart_lost").withStyle(ChatFormatting.RED), false);
    }

    /**
     * Gives back one heart of maximum health. Returns {@code true} if something was actually restored.
     */
    public static boolean restoreHeart(Player player, DeathPenaltyState state) {
        AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth == null || state.lostHp() <= 0) {
            return false;
        }

        double base = maxHealth.getBaseValue();
        double baseline = base + state.lostHp();
        double restored = Math.min(baseline, base + LOST_HP_PER_DEATH);
        int gained = (int) Math.round(restored - base);
        if (gained <= 0) {
            return false;
        }

        maxHealth.setBaseValue(restored);
        DeathPenaltyState updated = state.withLostHp(Math.max(0, state.lostHp() - gained));
        if (player instanceof ServerPlayer serverPlayer) {
            setState(serverPlayer, updated);
        } else {
            player.setData(ModAttachments.DEATH_PENALTY.get(), updated);
        }
        return true;
    }

    // Helpers --------------------------------------------------------------------------------------------------------------

    private static void refreshEffects(ServerPlayer player, DeathPenaltyState state) {
        if (state.penaltyStart() <= 0L) {
            return;
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - state.penaltyStart());
        if (!updateEffects(player, state, elapsed)) {
            setState(player, state.withPenaltyStart(0L).withoutCleared());
        }
    }

    private static boolean updateEffects(ServerPlayer player, DeathPenaltyState state, long elapsed) {
        int cleared = state.flags() & DeathPenaltyState.CLEARED_MASK;
        boolean anyActive = false;

        for (EffectSpec spec : EFFECTS) {
            if ((cleared & spec.clearedBit()) != 0) {
                removeIfPresent(player, spec.effect());
                continue;
            }

            int level = levelFor(spec, elapsed);
            if (level <= 0) {
                removeIfPresent(player, spec.effect());
                continue;
            }

            anyActive = true;
            MobEffectInstance current = player.getEffect(spec.effect());
            if (current == null || current.getAmplifier() != level - 1) {
                removeIfPresent(player, spec.effect());
                player.addEffect(new MobEffectInstance(spec.effect(), remainingTicks(spec, elapsed), level - 1, false, true, true));
            }
        }
        return anyActive;
    }

    /** Linearly decays {@code maxLevel -> 0} over {@code durationMs}. */
    private static int levelFor(EffectSpec spec, long elapsed) {
        if (elapsed >= spec.durationMs()) {
            return 0;
        }
        if (elapsed <= 0L) {
            return spec.maxLevel();
        }
        double fraction = 1.0D - (double) elapsed / (double) spec.durationMs();
        return Math.max(0, Math.min(spec.maxLevel(), (int) Math.ceil(spec.maxLevel() * fraction)));
    }

    private static int remainingTicks(EffectSpec spec, long elapsed) {
        long remainingMs = Math.max(50L, spec.durationMs() - elapsed);
        return (int) Math.min(Integer.MAX_VALUE, remainingMs / 50L);
    }

    private static int clearedBitFor(Holder<MobEffect> effect) {
        for (EffectSpec spec : EFFECTS) {
            if (spec.effect().equals(effect)) {
                return spec.clearedBit();
            }
        }
        return 0;
    }

    private static void removeIfPresent(ServerPlayer player, Holder<MobEffect> effect) {
        if (!player.hasEffect(effect)) {
            return;
        }
        suppressingRemoval = true;
        try {
            player.removeEffect(effect);
        } finally {
            suppressingRemoval = false;
        }
    }

    private static void giveItems(Player player, List<ItemStack> items) {
        for (ItemStack stack : items) {
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
    }

    private static boolean penaltiesEnabled(ServerPlayer player) {
        return !player.isCreative()
                && !player.isSpectator()
                && !player.serverLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
    }

    private static void setState(ServerPlayer player, DeathPenaltyState state) {
        player.setData(ModAttachments.DEATH_PENALTY.get(), state);
        player.syncData(ModAttachments.DEATH_PENALTY.get());
    }

    private record EffectSpec(Holder<MobEffect> effect, int clearedBit, int maxLevel, long durationMs) {}
}
