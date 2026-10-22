package com.kuronami.questtree.block;

import com.kuronami.questtree.server.ProgressService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * A detector's target and radius. A redstone rising edge calls {@link #pulse}: every real player inside the box gets
 * the same force-progress the {@code /quest_tree complete} command performs, so a quest can be finished by walking past
 * a door, a pressure plate or a machine's output.
 */
public class QuestDetectorBlockEntity extends BlockEntity {

    public static final String TARGET_KEY = "quest_target";
    public static final String RADIUS_KEY = "quest_radius";
    public static final int DEFAULT_RADIUS = 8;
    public static final int MAX_RADIUS = 64;

    private String target = "";
    private int radius = DEFAULT_RADIUS;

    public QuestDetectorBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.detector(), pos, state);
    }

    public QuestDetectorBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public String target() {
        return target;
    }

    public int radius() {
        return radius;
    }

    public void configure(String target, int radius) {
        this.target = target == null ? "" : target;
        this.radius = Mth.clamp(radius, 1, MAX_RADIUS);
        setChanged();
    }

    /** Force-progresses the target for every player in range. A detector without a target does nothing. */
    public void pulse(Level level, BlockPos pos) {
        if (target.isEmpty()) {
            return;
        }
        AABB box = new AABB(pos).inflate(radius);
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, box)) {
            ProgressService.forceProgress(player, target);
        }
    }

    /** Creative pick and middle-click keep the target and radius. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (target.isEmpty()) {
            return;
        }
        components.set(DataComponents.CUSTOM_DATA, CustomData.of(data()));
    }

    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput input) {
        super.applyImplicitComponents(input);
        CustomData data = input.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            read(data.copyTag());
        }
    }

    /** The item form of this detector, used by the block's drops and by the command. */
    public CompoundTag data() {
        CompoundTag tag = new CompoundTag();
        tag.putString(TARGET_KEY, target);
        tag.putInt(RADIUS_KEY, radius);
        return tag;
    }

    public void read(CompoundTag tag) {
        configure(tag.getString(TARGET_KEY), tag.contains(RADIUS_KEY) ? tag.getInt(RADIUS_KEY) : DEFAULT_RADIUS);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("target", target);
        tag.putInt("radius", radius);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        target = tag.getString("target");
        radius = Mth.clamp(tag.contains("radius") ? tag.getInt("radius") : DEFAULT_RADIUS, 1, MAX_RADIUS);
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
