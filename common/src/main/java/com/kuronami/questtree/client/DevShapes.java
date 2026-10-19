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

    private static final String[] SHAPES = {"square", "rsquare", "circle", "diamond", "octagon", "pentagon", "gear", "heart"};
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
        // the fill at several levels, where it actually appears: map size and Now size
        g.drawString(font, "fill", 8, y + 10, 0xFFA8A8A8, true);
        int fx = 64;
        for (double f : new double[]{0.15, 0.35, 0.55, 0.75, 0.9}) {
            for (int size : new int[]{20, 32}) {
                Tiles.tile(g, "square", fx, y + (32 - size) / 2, size, Tiles.Look.PROGRESS, f, new ItemStack(Items.WHEAT), false);
                fx += size + 8;
            }
            fx += 10;
        }
        y += 42;
        g.drawString(font, "fill shapes", 8, y + 10, 0xFFA8A8A8, true);
        fx = 64;
        for (String shape : new String[]{"circle", "octagon", "diamond", "heart", "gear"}) {
            for (int size : new int[]{20, 32}) {
                Tiles.tile(g, shape, fx, y + (32 - size) / 2, size, Tiles.Look.PROGRESS, 0.45, new ItemStack(Items.WHEAT), false);
                fx += size + 8;
            }
            fx += 10;
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }
}
