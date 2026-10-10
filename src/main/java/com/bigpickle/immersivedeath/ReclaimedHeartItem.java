package com.bigpickle.immersivedeath;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * Consumable that gives back exactly one heart of the maximum health lost to dying. It can never push the
 * player above their normal (undiminished) limit, because the amount to restore is derived from the recorded loss.
 */
public class ReclaimedHeartItem extends Item {
    public ReclaimedHeartItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // The penalty state is synced to the owning client, so both sides can agree on whether anything can be
        // restored. This prevents the client from playing a use animation for a heart that the server rejects.
        DeathPenaltyState state = player.getData(ModAttachments.DEATH_PENALTY.get());
        if (state.lostHp() <= 0) {
            if (!level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable("immersivedeath.message.no_hearts").withStyle(ChatFormatting.GRAY), true);
            }
            return InteractionResultHolder.fail(stack);
        }

        if (!level.isClientSide) {
            if (!DeathHandler.restoreHeart(player, state)) {
                player.displayClientMessage(
                        Component.translatable("immersivedeath.message.no_hearts").withStyle(ChatFormatting.GRAY), true);
                return InteractionResultHolder.fail(stack);
            }

            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.8F, 1.4F);
            player.displayClientMessage(
                    Component.translatable("immersivedeath.message.heart_restored").withStyle(ChatFormatting.GREEN), true);
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("item.immersivedeath.reclaimed_heart.tooltip").withStyle(ChatFormatting.GRAY));
    }
}
