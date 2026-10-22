package com.kuronami.questtree.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The command arguments that name a chapter or a quest must accept an unquoted id: a resource location has a colon
 * and its path may hold a slash, which Brigadier's word reader does not take (2026-10-22 review, H1).
 */
class GiverCommandParsingTest {

    private static CommandSourceStack source() {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, 4, "", Component.empty(),
                null, null);
    }

    private static String parsedId(CommandNode<CommandSourceStack> argument, String name, String text) throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        CommandContextBuilder<CommandSourceStack> builder =
                new CommandContextBuilder<>(dispatcher, source(), dispatcher.getRoot(), 0);
        argument.parse(new StringReader(text), builder);
        return ResourceLocationArgument.getId(builder.build(text), name).toString();
    }

    @Test
    void giverSetTakesAChapterAndAQuestId() throws Exception {
        CommandNode<CommandSourceStack> argument = GiverCommands.node().getChild("set").getChild("target");
        assertEquals("fixture:roles", parsedId(argument, "target", "fixture:roles"));
        assertEquals("fixture:roles/one", parsedId(argument, "target", "fixture:roles/one"));
    }

    @Test
    void giverListAndClearByUuidAreRegistered() {
        CommandNode<CommandSourceStack> giver = GiverCommands.node();
        assertNotNull(giver.getChild("list"));
        assertNotNull(giver.getChild("clear").getChild("uuid"));
    }

    @Test
    void blockTaskAndBarrierNodeTakeAQuestId() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        PlayerCommands.register(dispatcher);
        CommandNode<CommandSourceStack> block = dispatcher.getRoot().getChild("quest_tree").getChild("block");

        CommandNode<CommandSourceStack> task = block.getChild("task").getChild("players").getChild("node");
        assertEquals("fixture:roles/one", parsedId(task, "node", "fixture:roles/one"));

        CommandNode<CommandSourceStack> barrier = block.getChild("barrier").getChild("players")
                .getChild("node").getChild("node");
        assertEquals("fixture:roles/one", parsedId(barrier, "node", "fixture:roles/one"));
    }
}
