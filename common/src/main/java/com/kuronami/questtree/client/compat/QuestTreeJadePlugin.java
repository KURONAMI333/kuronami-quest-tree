package com.kuronami.questtree.client.compat;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.block.QuestBarrierBlock;
import com.kuronami.questtree.block.QuestBarrierBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade shows what a quest gate asks for and whether the local player may pass. Jade is optional: this class is only
 * loaded by Jade itself (the annotation on NeoForge, the {@code jade} entrypoint on Fabric), like the recipe viewers.
 */
@WailaPlugin
public class QuestTreeJadePlugin implements IWailaPlugin {

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(BarrierProvider.INSTANCE, QuestBarrierBlock.class);
    }

    private enum BarrierProvider implements IBlockComponentProvider {
        INSTANCE;

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!(accessor.getBlockEntity() instanceof QuestBarrierBlockEntity barrier) || barrier.key().isEmpty()) {
                return;
            }
            String key = "jade." + Constants.MOD_ID + "."
                    + ("stage".equals(barrier.mode()) ? "barrier.stage" : "barrier.quest");
            tooltip.add(Component.translatable(key, barrier.key()));
            if (Minecraft.getInstance().player != null) {
                boolean open = barrier.allows(Minecraft.getInstance().player);
                tooltip.add(Component.translatable("jade." + Constants.MOD_ID
                        + (open ? ".barrier.open" : ".barrier.locked")));
            }
        }

        @Override
        public ResourceLocation getUid() {
            return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "barrier");
        }
    }
}
