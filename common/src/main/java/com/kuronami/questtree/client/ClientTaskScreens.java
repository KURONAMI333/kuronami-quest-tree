package com.kuronami.questtree.client;

import com.kuronami.questtree.network.ScreenBindOpenPayload;
import com.kuronami.questtree.network.ScreenBindPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The client's side of task screens (2026-10-22): the server asks for the task picker, and the pick goes back as one
 * small payload. The loader's client sets {@link #sender}, so common code never names a networking class.
 */
public final class ClientTaskScreens {

    public static Consumer<ScreenBindPayload> sender = payload -> {
    };

    private ClientTaskScreens() {
    }

    public static void onOpen(ScreenBindOpenPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientModel model = ClientProgress.model();
        if (minecraft.level == null || model == null) {
            return;
        }
        BlockPos pos = payload.pos();
        List<EditPickers.Option> options = new ArrayList<>();
        options.add(new EditPickers.Option("", Component.translatable("screen.kuronami_quest_tree.task_screen.none"),
                ItemStack.EMPTY));
        for (ClientModel.Chapter chapter : model.chapters) {
            for (ClientModel.Node node : chapter.nodes.values()) {
                for (int i = 0; i < node.tasks.size(); i++) {
                    ClientModel.Task task = node.tasks.get(i);
                    if (!screenCapable(task.type)) {
                        continue;
                    }
                    String id = node.key + "#" + i;
                    Component label = Component.literal(node.title.getString() + " · " + task.name().getString());
                    options.add(new EditPickers.Option(id, label, task.icon()));
                }
            }
        }
        Component title = Component.translatable("screen.kuronami_quest_tree.task_screen.pick");
        minecraft.setScreen(new EditPickerScreen(minecraft.screen, title, options, false, id -> {
            if (id.isEmpty()) {
                sender.accept(new ScreenBindPayload(pos, "", -1));
                return;
            }
            int hash = id.lastIndexOf('#');
            if (hash <= 0) {
                return;
            }
            String node = id.substring(0, hash);
            try {
                sender.accept(new ScreenBindPayload(pos, node, Integer.parseInt(id.substring(hash + 1))));
            } catch (NumberFormatException ignored) {
                // an id the list did not make
            }
        }));
    }

    private static boolean screenCapable(String type) {
        return type.equals("item") || type.equals("fluid") || type.equals("energy");
    }
}
