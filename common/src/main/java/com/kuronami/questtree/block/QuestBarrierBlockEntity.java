package com.kuronami.questtree.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.server.level.ServerPlayer;

/**
 * A gate that opens for a completed quest ({@code node}) or a stage ({@code stage}). The key and mode are saved with
 * the block entity, and the collision shape asks this object on every player movement.
 */
public class QuestBarrierBlockEntity extends BlockEntity {

    private String mode = "node";
    private String key = "";

    public QuestBarrierBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.barrier(), pos, state);
    }

    public QuestBarrierBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public String mode() {
        return mode;
    }

    public String key() {
        return key;
    }

    public void setGate(String mode, String key) {
        this.mode = "stage".equals(mode) ? "stage" : "node";
        this.key = key == null ? "" : key;
        setChanged();
    }

    /** Whether this player may pass now. An empty key keeps the gate closed, so a misconfigured block is safe. */
    public boolean allows(Player player) {
        if (key.isEmpty()) {
            return false;
        }
        if (player instanceof ServerPlayer sp) {
            return com.kuronami.questtree.server.ProgressService.barrierAllows(sp, mode, key);
        }
        return com.kuronami.questtree.client.ClientBarrier.allows(mode, key);
    }

    /** Creative pick and middle-click carry the gate's key, not just a plain barrier block. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (key.isEmpty()) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("quest_key", key);
        tag.putString("quest_mode", mode);
        components.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput input) {
        super.applyImplicitComponents(input);
        CustomData data = input.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            setGate(data.copyTag().getString("quest_mode"), data.copyTag().getString("quest_key"));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("mode", mode);
        tag.putString("key", key);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        mode = "stage".equals(tag.getString("mode")) ? "stage" : "node";
        key = tag.getString("key");
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
