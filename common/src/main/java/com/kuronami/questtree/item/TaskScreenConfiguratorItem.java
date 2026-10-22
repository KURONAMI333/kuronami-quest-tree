package com.kuronami.questtree.item;

import com.kuronami.questtree.server.TaskScreenService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;

/**
 * Links a task to a screen (2026-10-22, FTB Quests' configurator). Using it on a screen opens the same task picker as
 * an empty hand; the server checks that the player owns the screen or is in the owner's team.
 */
public class TaskScreenConfiguratorItem extends Item {

    public TaskScreenConfiguratorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() instanceof ServerPlayer player) {
            TaskScreenService.requestOpen(player, context.getClickedPos());
        }
        return context.getLevel().isClientSide ? InteractionResult.SUCCESS : InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.kuronami_quest_tree.task_screen_configurator.desc")
                .withStyle(ChatFormatting.GRAY));
    }
}
