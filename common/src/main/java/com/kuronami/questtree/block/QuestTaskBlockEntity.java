package com.kuronami.questtree.block;

import com.kuronami.questtree.giver.GiverTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Remembers what a task block opens: one quest or, since 2026-10-22, one chapter. The target is kept with the block
 * so the server can open the book for the player who clicked it.
 */
public class QuestTaskBlockEntity extends BlockEntity {

    private String node = "";
    private String chapter = "";

    public QuestTaskBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.task(), pos, state);
    }

    public QuestTaskBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public String node() {
        return node;
    }

    public String chapter() {
        return chapter;
    }

    /** The quest or chapter this block opens, or null when it is unlinked. */
    public GiverTarget target() {
        if (!chapter.isEmpty()) {
            return GiverTarget.chapter(chapter);
        }
        return node.isEmpty() ? null : GiverTarget.node(node);
    }

    public void setNode(String node) {
        this.node = node == null ? "" : node;
        this.chapter = "";
        setChanged();
    }

    public void setTarget(GiverTarget target) {
        this.node = target == null || target.chapter() ? "" : target.id();
        this.chapter = target == null || !target.chapter() ? "" : target.id();
        setChanged();
    }

    /** Creative pick and middle-click keep the target this block opens. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (node.isEmpty() && chapter.isEmpty()) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        if (!chapter.isEmpty()) {
            tag.putString(QuestNodeKey.CHAPTER_KEY, chapter);
        } else {
            tag.putString(QuestNodeKey.KEY, node);
        }
        components.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput input) {
        super.applyImplicitComponents(input);
        CustomData data = input.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            CompoundTag tag = data.copyTag();
            String savedChapter = tag.getString(QuestNodeKey.CHAPTER_KEY);
            if (!savedChapter.isEmpty()) {
                setTarget(GiverTarget.chapter(savedChapter));
            } else {
                setNode(QuestNodeKey.read(tag::contains, tag::getString));
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(QuestNodeKey.KEY, node);
        if (!chapter.isEmpty()) {
            tag.putString(QuestNodeKey.CHAPTER_KEY, chapter);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        node = QuestNodeKey.read(tag::contains, tag::getString);
        chapter = tag.getString(QuestNodeKey.CHAPTER_KEY);
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
