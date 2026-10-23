package com.kuronami.questtree.block;

import com.google.gson.JsonObject;
import com.kuronami.questtree.item.ModDataComponents;
import com.kuronami.questtree.server.LootCrates;
import com.kuronami.questtree.server.ProgressService;
import com.kuronami.questtree.server.ProgressStore;
import com.kuronami.questtree.server.Rewards;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The opener's crate input and its accumulated output; a hopper may feed the top and pull from any side. */
public class LootCrateOpenerBlockEntity extends BlockEntity implements WorldlyContainer {

    /** FTB Quests keeps one output stack per distinct item; the same cap keeps NBT small. */
    private static final int MAX_OUTPUT_KINDS = 64;

    private final List<ItemStack> outputs = new ArrayList<>();
    private UUID owner;

    public LootCrateOpenerBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.lootCrateOpener(), pos, state);
    }

    public LootCrateOpenerBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos,
                                      BlockState state) {
        super(type, pos, state);
    }

    public void setOwner(UUID owner) {
        this.owner = owner;
        setChanged();
    }

    public int stored() {
        int n = 0;
        for (ItemStack stack : outputs) {
            n += stack.getCount();
        }
        return n;
    }

    @Nullable
    private ServerPlayer ownerPlayer() {
        if (owner == null || level == null || level.getServer() == null) {
            return null;
        }
        return level.getServer().getPlayerList().getPlayer(owner);
    }

    private boolean accepts(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != com.kuronami.questtree.item.ModItems.lootCrate()
                || !stack.has(ModDataComponents.lootCrate()) || ownerPlayer() == null) {
            return false;
        }
        String tableId = stack.get(ModDataComponents.lootCrate());
        // a hopper must not hand the crate over while the table cannot be read: setItem would drop it without a roll
        return level == null || level.isClientSide || LootCrates.canRoll(tableId);
    }

    /** Rolls the crate into the output. The input is consumed only when the table is readable. */
    private boolean open(ItemStack stack) {
        ServerPlayer player = ownerPlayer();
        String tableId = stack.get(ModDataComponents.lootCrate());
        if (player == null || tableId == null || tableId.isEmpty()) {
            return false;
        }
        List<JsonObject> rewards = LootCrates.roll(player, tableId, stack.getCount());
        if (rewards == null) {
            return false;
        }
        var progress = ProgressService.progress(player);
        for (JsonObject reward : rewards) {
            String type = ProgressService.type(reward);
            if (type.isEmpty() || type.equals("item")) {
                for (ItemStack out : Rewards.itemStacks(player, reward)) {
                    addOutput(out);
                }
            } else {
                Rewards.crateEntry(player, progress, reward, tableId);
            }
        }
        ProgressStore.get(player.server).setDirty();
        setChanged();
        return true;
    }

    private void addOutput(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        for (ItemStack out : outputs) {
            if (ItemStack.isSameItemSameComponents(out, stack)) {
                int room = out.getMaxStackSize() - out.getCount();
                int moved = Math.min(room, stack.getCount());
                out.grow(moved);
                stack.shrink(moved);
                if (stack.isEmpty()) {
                    setChanged();
                    return;
                }
            }
        }
        while (!stack.isEmpty() && outputs.size() < MAX_OUTPUT_KINDS) {
            int take = Math.min(stack.getCount(), stack.getMaxStackSize());
            outputs.add(stack.copyWithCount(take));
            stack.shrink(take);
        }
        if (!stack.isEmpty()) {
            if (ownerPlayer() != null) {
                ownerPlayer().drop(stack, false);
            }
        }
        setChanged();
    }

    @Override
    public int getContainerSize() {
        return 2;
    }

    @Override
    public boolean isEmpty() {
        return outputs.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        if (slot == 1 && !outputs.isEmpty()) {
            return outputs.get(0).copy();
        }
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot != 1 || amount <= 0 || outputs.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack first = outputs.get(0);
        int take = Math.min(amount, first.getCount());
        ItemStack out = first.copyWithCount(take);
        first.shrink(take);
        if (first.isEmpty()) {
            outputs.remove(0);
        }
        setChanged();
        return out;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return removeItem(slot, Integer.MAX_VALUE);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot == 0 && !level.isClientSide && accepts(stack) && open(stack)) {
            // this slot is the hopper's trigger, not storage: a successful roll consumes what was inserted
            stack.setCount(0);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return level != null && level.getBlockEntity(worldPosition) == this
                && player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void clearContent() {
        outputs.clear();
        setChanged();
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return new int[]{0, 1};
    }

    @Override
    public boolean canPlaceItemThroughFace(int index, ItemStack stack, @Nullable Direction direction) {
        return index == 0 && accepts(stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int index, ItemStack stack, Direction direction) {
        return index == 1;
    }

    @Override
    public boolean canPlaceItem(int index, ItemStack stack) {
        return index == 0 && accepts(stack);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) {
            tag.putUUID("owner", owner);
        }
        CompoundTag out = new CompoundTag();
        for (int i = 0; i < outputs.size(); i++) {
            out.put("s" + i, outputs.get(i).save(registries));
        }
        tag.put("outputs", out);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        outputs.clear();
        CompoundTag out = tag.getCompound("outputs");
        for (String key : out.getAllKeys()) {
            ItemStack.parse(registries, out.getCompound(key)).ifPresent(outputs::add);
        }
    }

}
