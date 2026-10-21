package com.kuronami.questtree.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.component.CustomData;

/** Remembers which quest a task block shows. The key is sent with the block so the client can open that quest. */
public class QuestTaskBlockEntity extends BlockEntity {

    private String node = "";

    public QuestTaskBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.task(), pos, state);
    }

    public QuestTaskBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public String node() {
        return node;
    }

    public void setNode(String node) {
        this.node = node == null ? "" : node;
        setChanged();
    }

    /** Creative pick and middle-click keep the quest this block shows. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (node.isEmpty()) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString(QuestNodeKey.KEY, node);
        components.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput input) {
        super.applyImplicitComponents(input);
        CustomData data = input.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            CompoundTag tag = data.copyTag();
            setNode(QuestNodeKey.read(tag::contains, tag::getString));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(QuestNodeKey.KEY, node);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        node = QuestNodeKey.read(tag::contains, tag::getString);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
