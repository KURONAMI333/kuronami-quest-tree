package com.kuronami.questtree.item;

import com.kuronami.questtree.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The quest book (2026-10-20): a portable way into the progression screen, like FTB Quests' book. Holding it is not
 * needed; using it opens the same screen the K key does. The screen class is client-only, so the call goes through the
 * platform helper and only runs on the client.
 */
public class QuestBookItem extends Item {

    public QuestBookItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.kuronami_quest_tree.quest_book.desc").withStyle(ChatFormatting.GRAY));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            Services.PLATFORM.openProgressionScreen();
        }
        return InteractionResultHolder.success(stack);
    }
}
