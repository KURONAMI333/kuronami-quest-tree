package com.kuronami.questtree.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The slot under the pointer in an inventory screen, so the open key over an item can open the quest that wants it. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    @Accessor("hoveredSlot")
    Slot questTree$hoveredSlot();

    @Accessor("leftPos")
    int questTree$leftPos();

    @Accessor("topPos")
    int questTree$topPos();

    @Accessor("imageWidth")
    int questTree$imageWidth();
}
