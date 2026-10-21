package com.kuronami.questtree.client.compat;

import com.kuronami.questtree.client.RecipeViewer;
import me.shedaniel.math.Point;
import me.shedaniel.rei.api.client.ClientHelper;
import me.shedaniel.rei.api.client.REIRuntime;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.plugins.PluginManager;
import me.shedaniel.rei.api.common.registry.ReloadStage;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

/**
 * REI's side of {@link RecipeViewer}. The class carries no annotation: the Fabric entrypoint names it, and the NeoForge
 * subclass is the one REI's annotation scanner finds. It is only loaded when REI is installed.
 */
public class QuestTreeReiPlugin implements REIClientPlugin {

    @Override
    public void preStage(PluginManager<REIClientPlugin> manager, ReloadStage stage) {
        if (stage == ReloadStage.START) {
            RecipeViewer.Holder.set(null);
        }
    }

    @Override
    public void postStage(PluginManager<REIClientPlugin> manager, ReloadStage stage) {
        if (stage == ReloadStage.END) {
            RecipeViewer.Holder.set(new Viewer());
        }
    }

    private static final class Viewer implements RecipeViewer {

        @Override
        public void showRecipes(ItemStack stack) {
            show(stack, false);
        }

        @Override
        public void showUses(ItemStack stack) {
            show(stack, true);
        }

        private static void show(ItemStack stack, boolean uses) {
            if (stack.isEmpty()) {
                return;
            }
            ViewSearchBuilder builder = ViewSearchBuilder.builder();
            EntryStack<ItemStack> entry = EntryStacks.of(stack.copyWithCount(1));
            if (uses) {
                builder.addUsagesFor(entry);
            } else {
                builder.addRecipesFor(entry);
            }
            ClientHelper.getInstance().openView(builder);
        }

        @Override
        public boolean typing() {
            var field = REIRuntime.getInstance().getSearchTextField();
            return field != null && field.isFocused();
        }

        @Override
        public ItemStack underMouse() {
            Minecraft mc = Minecraft.getInstance();
            Screen screen = mc.screen;
            if (screen == null) {
                return ItemStack.EMPTY;
            }
            double sx = mc.getWindow().getGuiScaledWidth() / (double) mc.getWindow().getScreenWidth();
            double sy = mc.getWindow().getGuiScaledHeight() / (double) mc.getWindow().getScreenHeight();
            Point point = new Point((int) (mc.mouseHandler.xpos() * sx), (int) (mc.mouseHandler.ypos() * sy));
            EntryStack<?> stack = ScreenRegistry.getInstance().getFocusedStack(screen, point);
            return stack != null && stack.getValue() instanceof ItemStack item ? item : ItemStack.EMPTY;
        }
    }
}
