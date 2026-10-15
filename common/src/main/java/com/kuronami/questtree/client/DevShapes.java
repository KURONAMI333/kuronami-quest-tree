package com.kuronami.questtree.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Development only (opened by {@link DevTour}): every shape in every look, at map size and at Now size, so shapes can be
 * judged in the game's own renderer. Never reachable in a normal game.
 */
final class DevShapes extends Screen {

    private static final String[] SHAPES = {"square", "octagon", "circle", "diamond", "pentagon"};
    private static final Tiles.Look[] LOOKS = {Tiles.Look.DONE_REWARD, Tiles.Look.DONE, Tiles.Look.OPEN, Tiles.Look.PROGRESS,
            Tiles.Look.LOCKED_NEAR};

    DevShapes() {
        super(Component.literal("shapes"));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xFF0C0C0E);
        ItemStack[] icons = {new ItemStack(Items.BREAD), new ItemStack(Items.HONEYCOMB), new ItemStack(Items.IRON_INGOT),
                new ItemStack(Items.WHEAT), new ItemStack(Items.CAMPFIRE)};
        int y = 8;
        for (String shape : SHAPES) {
            g.drawString(font, shape, 8, y + 10, 0xFFA8A8A8, true);
            int x = 64;
            for (int size : new int[]{20, 32}) {
                for (int i = 0; i < LOOKS.length; i++) {
                    Tiles.tile(g, shape, x, y + (32 - size) / 2, size, LOOKS[i], 0.5, icons[i], false);
                    x += size + 8;
                }
                x += 12;
            }
            y += 42;
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }
}
