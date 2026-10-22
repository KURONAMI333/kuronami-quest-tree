package com.kuronami.questtree.item;

import com.kuronami.questtree.server.LootCrates;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A crate that rolls its reward table when opened (FTB Quests' {@code LootCrateItem}). Sneaking opens the whole held
 * stack; the stack carries the table id, so the name, colour and glow come from the pack's table data.
 */
public class LootCrateItem extends Item {

    public LootCrateItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        String table = stack.get(ModDataComponents.lootCrate());
        if (table == null || table.isEmpty()) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.pass(stack);
        }
        int count = player.isShiftKeyDown() ? stack.getCount() : 1;
        if (count <= 0 || !LootCrates.open(serverPlayer, table, count)) {
            return InteractionResultHolder.pass(stack);
        }
        stack.shrink(count);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 0.8F + level.random.nextFloat() * 0.4F);
        return InteractionResultHolder.success(stack);
    }
}
