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
import net.minecraft.world.entity.player.Inventory;
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
 *     take priority and we never cause item duplication. If another mod cancels the drops we explicitly discard our
 *     inventory snapshot so we cannot duplicate what the other mod kept.</li>
 *     <li>Instead of collecting the dropped items, the vanilla inventory (main + armor + off-hand) is snapshotted
 *     slot by slot at the moment of death and written back into the exact same slots at respawn. Equipped armor and
 *     the off-hand item are preserved and nothing can be lost to a full main inventory. Only drops that other mods
 *     added to the event are captured separately.</li>
 *     <li>Maximum health is changed through the attribute base value, which is non-invasive and stacks with
 *     attribute modifiers added by other mods.</li>
 *     <li>All timings use world game ticks, so the penalty decay pauses with the game (e.g. in single-player) and
 *     is unaffected by system clock changes.</li>
 * </ul>
 */
public final class DeathHandler {
    /** A second death within this window only costs experience, to avoid punishing death loops. In game ticks. */
    public static final long RAPID_WINDOW_TICKS = 60L * 20L;

    /** Health removed per death: 2 HP (one heart). */
    private static final int LOST_HP_PER_DEATH = 2;
    /** The player can never be reduced below a single point of maximum health. */
    private static final double MIN_MAX_HEALTH = 1.0D;

    private static final List<EffectSpec> EFFECTS = List.of(
            new EffectSpec(MobEffects.CONFUSION, DeathPenaltyState.CLEARED_NAUSEA, 9, 30L * 20L),
            new EffectSpec(MobEffects.BLINDNESS, DeathPenaltyState.CLEARED_BLINDNESS, 15, 60L * 20L),
            new EffectSpec(MobEffects.WEAKNESS, DeathPenaltyState.CLEARED_WEAKNESS, 18, 600L * 20L));

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

        long now = now(player);
        DeathPenaltyState state = player.getData(ModAttachments.DEATH_PENALTY.get());
        boolean rapid = state.lastDeath() > 0L && (now - state.lastDeath()) < RAPID_WINDOW_TICKS;

        int flags = state.flags() | DeathPenaltyState.PENDING;
        flags = rapid ? (flags | DeathPenaltyState.RAPID) : (flags & ~DeathPenaltyState.RAPID);

        setState(player, state.withLastDeath(now).withFlags(flags));
        // The inventory is still intact here: dropAllDeathLoot() runs after the death event.
        snapshotInventory(player);
    }

    /**
     * Runs at the lowest priority so that any other mod (graves, souls, etc.) that cancels the event keeps ownership
     * of the drops. When we are the ones handling it we cancel the event so nothing is scattered on the ground; the
     * inventory snapshot and the separately captured extra drops keep everything for respawn.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDrops(LivingDropsEvent event) {
        if (event.isCanceled()) {
            // Someone else took ownership of the drops (a grave, a soul, ...). Discard our snapshot so the items are
            // not restored a second time at respawn.
            if (event.getEntity() instanceof ServerPlayer player) {
                player.setData(ModAttachments.INVENTORY_SNAPSHOT.get(), List.of());
            }
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player) || !penaltiesEnabled(player)) {
            return;
        }

        List<ItemStack> snapshot = player.getData(ModAttachments.INVENTORY_SNAPSHOT.get());
        if (snapshot.isEmpty()) {
            // Safety net: no snapshot was recorded (should not happen). Keep every drop so the player never loses
            // items; they are simply handed back into the inventory or dropped at the respawn point.
            List<ItemStack> captured = new ArrayList<>();
            for (ItemEntity drop : event.getDrops()) {
                ItemStack stack = drop.getItem();
                if (!stack.isEmpty()) {
                    captured.add(stack.copy());
                }
            }
            if (!captured.isEmpty()) {
                player.setData(ModAttachments.KEPT_ITEMS.get(), mergeKept(
                        player.getData(ModAttachments.KEPT_ITEMS.get()), captured));
            }
            event.setCanceled(true);
            return;
        }

        // Vanilla spawns exactly one drop per non-empty inventory slot, holding the same ItemStack instance that was
        // in the slot. Match the drops back against the snapshot so we know what belongs to the inventory (restored
        // into its original slots) and what other mods added (handed back into the inventory or dropped at respawn).
        boolean[] consumed = new boolean[snapshot.size()];
        List<ItemStack> extras = new ArrayList<>();
        for (ItemEntity drop : event.getDrops()) {
            ItemStack stack = drop.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            boolean wasPartOfInventory = false;
            for (int i = 0; i < snapshot.size(); i++) {
                if (!consumed[i] && !snapshot.get(i).isEmpty() && ItemStack.matches(snapshot.get(i), stack)) {
                    consumed[i] = true;
                    wasPartOfInventory = true;
                    break;
                }
            }
            if (!wasPartOfInventory) {
                extras.add(stack.copy());
            }
        }

        if (!extras.isEmpty() || !player.getData(ModAttachments.KEPT_ITEMS.get()).isEmpty()) {
            player.setData(ModAttachments.KEPT_ITEMS.get(),
                    mergeKept(player.getData(ModAttachments.KEPT_ITEMS.get()), extras));
        }
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

        // The item data and the penalty state follow the player regardless of listener ordering. Items are handed out
        // in {@link #onPlayerRespawn} (and the login fallback), at which point the new player is at their respawn
        // position and can receive them in their original slots.
        clone.setData(ModAttachments.INVENTORY_SNAPSHOT.get(), List.copyOf(original.getData(ModAttachments.INVENTORY_SNAPSHOT.get())));
        clone.setData(ModAttachments.KEPT_ITEMS.get(), List.copyOf(original.getData(ModAttachments.KEPT_ITEMS.get())));
        original.setData(ModAttachments.INVENTORY_SNAPSHOT.get(), List.of());
        original.setData(ModAttachments.KEPT_ITEMS.get(), List.of());

        clone.setData(ModAttachments.DEATH_PENALTY.get(), original.getData(ModAttachments.DEATH_PENALTY.get()));
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            restoreItems(player);
            applyPendingPenalty(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // If the player logs in while still dead (for example after a restart), the pending penalty, inventory
        // snapshot and kept items must survive until respawn: the current entity is going to be discarded, so
        // restoring anything now would lose it. Clone/respawn handles it instead.
        if (!player.isAlive()) {
            return;
        }

        restoreItems(player);
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

        long elapsed = Math.max(0L, now(player) - state.penaltyStart());
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
                .withPenaltyStart(now(player))
                .withFlags(flags);

        setState(player, applied);
        updateEffects(player, applied, 0L);
        if (lost > 0) {
            player.displayClientMessage(
                    Component.translatable("immersivedeath.message.heart_lost").withStyle(ChatFormatting.RED), false);
        }
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

    // Item restoration -----------------------------------------------------------------------------------------------------

    /**
     * Records the player's vanilla inventory (main + armor + off-hand, in slot order) before the death drops clear it.
     */
    private static void snapshotInventory(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        List<ItemStack> snapshot = new ArrayList<>(inventory.getContainerSize());
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            snapshot.add(inventory.getItem(i));
        }
        player.setData(ModAttachments.INVENTORY_SNAPSHOT.get(), snapshot);
    }

    /**
     * Restores the inventory snapshot into its original slots and hands back the extra drops that other mods added
     * to the death drops. Safe to call more than once: both attachments are cleared as soon as they are consumed.
     */
    private static void restoreItems(ServerPlayer player) {
        List<ItemStack> snapshot = player.getData(ModAttachments.INVENTORY_SNAPSHOT.get());
        if (!snapshot.isEmpty()) {
            Inventory inventory = player.getInventory();
            int slots = Math.min(snapshot.size(), inventory.getContainerSize());
            for (int i = 0; i < slots; i++) {
                inventory.setItem(i, snapshot.get(i));
            }
            player.setData(ModAttachments.INVENTORY_SNAPSHOT.get(), List.of());
        }

        List<ItemStack> extras = player.getData(ModAttachments.KEPT_ITEMS.get());
        if (!extras.isEmpty()) {
            player.setData(ModAttachments.KEPT_ITEMS.get(), List.of());
            giveItems(player, extras);
        }
    }

    // Helpers --------------------------------------------------------------------------------------------------------------

    private static void refreshEffects(ServerPlayer player, DeathPenaltyState state) {
        if (state.penaltyStart() <= 0L) {
            return;
        }
        long elapsed = Math.max(0L, now(player) - state.penaltyStart());
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
                player.addEffect(new MobEffectInstance(spec.effect(), (int) remainingTicks(spec, elapsed), level - 1, false, true, true));
            }
        }
        return anyActive;
    }

    /** Linearly decays {@code maxLevel -> 0} over {@code durationTicks}. */
    private static int levelFor(EffectSpec spec, long elapsed) {
        if (elapsed >= spec.durationTicks()) {
            return 0;
        }
        if (elapsed <= 0L) {
            return spec.maxLevel();
        }
        double fraction = 1.0D - (double) elapsed / (double) spec.durationTicks();
        return Math.max(0, Math.min(spec.maxLevel(), (int) Math.ceil(spec.maxLevel() * fraction)));
    }

    private static long remainingTicks(EffectSpec spec, long elapsed) {
        // At least one tick so the effect never expires a tick before the next refresh re-applies it.
        return Math.max(1L, spec.durationTicks() - elapsed);
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

    /** The current world game time in ticks, used for every timing decision so the decay pauses with the game. */
    private static long now(ServerPlayer player) {
        return player.serverLevel().getGameTime();
    }

    private static void setState(ServerPlayer player, DeathPenaltyState state) {
        player.setData(ModAttachments.DEATH_PENALTY.get(), state);
        player.syncData(ModAttachments.DEATH_PENALTY.get());
    }

    /**
     * Copies the non-empty stacks of {@code previous} and {@code additions} into a single list. The kept list is
     * slot agnostic (it is handed back with {@link #giveItems}), so empties can be dropped safely.
     */
    private static List<ItemStack> mergeKept(List<ItemStack> previous, List<ItemStack> additions) {
        List<ItemStack> merged = new ArrayList<>(previous.size() + additions.size());
        for (ItemStack stack : previous) {
            if (!stack.isEmpty()) {
                merged.add(stack.copy());
            }
        }
        for (ItemStack stack : additions) {
            if (!stack.isEmpty()) {
                merged.add(stack.copy());
            }
        }
        return List.copyOf(merged);
    }

    private record EffectSpec(Holder<MobEffect> effect, int clearedBit, int maxLevel, long durationTicks) {}
}