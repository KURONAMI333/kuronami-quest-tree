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
    /** Null until the barrier is given a teleport destination. */
    private BarrierTeleport teleport;

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

    public BarrierTeleport teleport() {
        return teleport;
    }

    public void setTeleport(BarrierTeleport teleport) {
        this.teleport = teleport;
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
        writeTeleport(tag);
        components.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput input) {
        super.applyImplicitComponents(input);
        CustomData data = input.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            CompoundTag tag = data.copyTag();
            setGate(tag.getString("quest_mode"), tag.getString("quest_key"));
            setTeleport(teleportFrom(tag));
        }
    }

    /** Copies only the teleport part of an item's data, so the gate key and destination travel together. */
    public void readTeleport(CompoundTag tag) {
        setTeleport(teleportFrom(tag));
    }

    private void writeTeleport(CompoundTag tag) {
        if (teleport == null) {
            return;
        }
        CompoundTag saved = new CompoundTag();
        saved.putBoolean("relative", teleport.relative());
        saved.putString("dimension", teleport.dimension());
        saved.putInt("x", teleport.x());
        saved.putInt("y", teleport.y());
        saved.putInt("z", teleport.z());
        if (teleport.yaw() != null) {
            saved.putFloat("yaw", teleport.yaw());
        }
        if (teleport.pitch() != null) {
            saved.putFloat("pitch", teleport.pitch());
        }
        tag.put("teleport", saved);
    }

    private static BarrierTeleport teleportFrom(CompoundTag tag) {
        if (!tag.contains("teleport")) {
            return null;
        }
        CompoundTag saved = tag.getCompound("teleport");
        return new BarrierTeleport(saved.getBoolean("relative"), saved.getString("dimension"),
                saved.getInt("x"), saved.getInt("y"), saved.getInt("z"),
                saved.contains("yaw") ? saved.getFloat("yaw") : null,
                saved.contains("pitch") ? saved.getFloat("pitch") : null);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("mode", mode);
        tag.putString("key", key);
        writeTeleport(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        mode = "stage".equals(tag.getString("mode")) ? "stage" : "node";
        key = tag.getString("key");
        teleport = teleportFrom(tag);
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
