package com.bigpickle.immersivedeath;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Per-player, persistent record of the penalties caused by dying.
 *
 * <p>It is stored as a NeoForge data attachment so that it is saved to disk, copied across death
 * (see {@code copyOnDeath()}) and synchronised to the owning client for the HUD overlay.
 *
 * @param lostHp       total health permanently removed from the player's maximum health, measured in health points
 *                     (2.0 HP = one heart)
 * @param penaltyStart wall-clock time the currently decaying penalty started, or {@code 0} if none is active
 * @param flags        bit field combining the "cleared by a cure" bits and the rapid/pending flags
 * @param lastDeath    wall-clock time of the player's most recent death, or {@code 0} if they never died
 */
public record DeathPenaltyState(int lostHp, long penaltyStart, int flags, long lastDeath) {
    /** Set when the nausea penalty has been removed by a cure such as milk. */
    public static final int CLEARED_NAUSEA = 1;
    /** Set when the blindness penalty has been removed by a cure such as milk. */
    public static final int CLEARED_BLINDNESS = 1 << 1;
    /** Set when the weakness penalty has been removed by a cure such as milk. */
    public static final int CLEARED_WEAKNESS = 1 << 2;
    /** Mask covering every "cleared" bit. */
    public static final int CLEARED_MASK = CLEARED_NAUSEA | CLEARED_BLINDNESS | CLEARED_WEAKNESS;
    /** Set when the pending respawn counts as a rapid death (within the death-loop window). */
    public static final int RAPID = 1 << 3;
    /** Set when the player died and the penalty has not been applied at respawn yet. */
    public static final int PENDING = 1 << 4;

    public static final DeathPenaltyState EMPTY = new DeathPenaltyState(0, 0L, 0, 0L);

    public static final Codec<DeathPenaltyState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("lost_hp").forGetter(DeathPenaltyState::lostHp),
            Codec.LONG.fieldOf("penalty_start").forGetter(DeathPenaltyState::penaltyStart),
            Codec.INT.fieldOf("flags").forGetter(DeathPenaltyState::flags),
            Codec.LONG.fieldOf("last_death").forGetter(DeathPenaltyState::lastDeath)
    ).apply(instance, DeathPenaltyState::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, DeathPenaltyState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DeathPenaltyState::lostHp,
            ByteBufCodecs.VAR_LONG, DeathPenaltyState::penaltyStart,
            ByteBufCodecs.VAR_INT, DeathPenaltyState::flags,
            ByteBufCodecs.VAR_LONG, DeathPenaltyState::lastDeath,
            DeathPenaltyState::new);

    public boolean isCleared(int bit) {
        return (flags & bit) != 0;
    }

    public boolean isRapid() {
        return (flags & RAPID) != 0;
    }

    public boolean isPending() {
        return (flags & PENDING) != 0;
    }

    public DeathPenaltyState withLostHp(int value) {
        return new DeathPenaltyState(value, penaltyStart, flags, lastDeath);
    }

    public DeathPenaltyState withPenaltyStart(long value) {
        return new DeathPenaltyState(lostHp, value, flags, lastDeath);
    }

    public DeathPenaltyState withFlags(int value) {
        return new DeathPenaltyState(lostHp, penaltyStart, value, lastDeath);
    }

    public DeathPenaltyState withLastDeath(long value) {
        return new DeathPenaltyState(lostHp, penaltyStart, flags, value);
    }

    public DeathPenaltyState withFlag(int bit) {
        return withFlags(flags | bit);
    }

    public DeathPenaltyState withoutFlag(int bit) {
        return withFlags(flags & ~bit);
    }

    public DeathPenaltyState withoutCleared() {
        return withFlags(flags & ~CLEARED_MASK);
    }
}
