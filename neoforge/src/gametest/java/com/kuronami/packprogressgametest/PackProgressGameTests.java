package com.kuronami.packprogressgametest;

import com.kuronami.packprogress.CommonClass;
import com.kuronami.packprogress.network.ActionPayload;
import com.kuronami.packprogress.progress.PlayerProgress;
import com.kuronami.packprogress.server.ProgressService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Uses the test data pack under {@code data/pack_progress_gametest/progression/}. */
@GameTestHolder("pack_progress")
@PrefixGameTestTemplate(false)
public final class PackProgressGameTests {

    private static final String Q = "pack_progress_gametest:quests/";
    private static final String S = "pack_progress_gametest:skills";
    private static final BlockPos POS = new BlockPos(1, 1, 1);

    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        Vec3 at = Vec3.atCenterOf(helper.absolutePos(POS));
        p.moveTo(at.x, at.y, at.z);
        return p;
    }

    private static void act(ServerPlayer p, String action, String node, int index) {
        CommonClass.onAction(p, new ActionPayload(action, node, index));
    }

    private static int count(ServerPlayer p, net.minecraft.world.item.Item item) {
        return p.getInventory().countItem(item);
    }

    /** Holding the items completes the quest; the reward waits for the claim; requirements open the next quests. */
    @GameTest(template = "empty3x3x3")
    public static void itemTaskThenClaim(GameTestHelper helper) {
        ServerPlayer p = player(helper);
        PlayerProgress pr = ProgressService.progress(p);
        p.getInventory().add(new ItemStack(Items.IRON_INGOT, 2));
        ProgressService.checkNow(p);
        helper.assertTrue(!pr.completed.contains(Q + "ingots"), "completed with 2 of 3 ingots");
        helper.assertTrue(pr.task(Q + "ingots", 0) == 2, "task counter should be 2, was " + pr.task(Q + "ingots", 0));
        p.getInventory().add(new ItemStack(Items.IRON_INGOT, 1));
        ProgressService.checkNow(p);
        helper.assertTrue(pr.completed.contains(Q + "ingots"), "not completed with 3 ingots");
        helper.assertTrue(count(p, Items.DIAMOND) == 0, "reward given before claiming");
        helper.assertTrue(count(p, Items.IRON_INGOT) == 3, "non-consuming task took the items");
        act(p, "claim", Q + "ingots", -1);
        helper.assertTrue(count(p, Items.DIAMOND) == 2, "claim did not give 2 diamonds");
        act(p, "claim", Q + "ingots", -1);
        helper.assertTrue(count(p, Items.DIAMOND) == 2, "claimed twice");
        // the checkmark quest opened by "ingots": auto reward of 10 xp
        int xp = p.totalExperience;
        act(p, "check", Q + "tick", 0);
        helper.assertTrue(pr.completed.contains(Q + "tick") && pr.claimed.contains(Q + "tick"), "checkmark + auto reward failed");
        helper.assertTrue(p.totalExperience == xp + 10, "auto xp reward missing");
        helper.succeed();
    }

    /** A locked quest's tasks do not move; a consuming task takes items only on submit. */
    @GameTest(template = "empty3x3x3")
    public static void submitConsumesOnlyWhenOpen(GameTestHelper helper) {
        ServerPlayer p = player(helper);
        PlayerProgress pr = ProgressService.progress(p);
        p.getInventory().add(new ItemStack(Items.GOLD_INGOT, 5));
        act(p, "submit", Q + "hand_in", 0);
        helper.assertTrue(count(p, Items.GOLD_INGOT) == 5, "submitted to a locked quest");
        act(p, "check", Q + "tick", 0);
        helper.assertTrue(!pr.completed.contains(Q + "tick"), "checked a locked quest");
        p.getInventory().add(new ItemStack(Items.IRON_INGOT, 3));
        ProgressService.checkNow(p);
        ProgressService.checkNow(p);
        helper.assertTrue(count(p, Items.GOLD_INGOT) == 5, "consuming task took items without submit");
        act(p, "submit", Q + "hand_in", 0);
        helper.assertTrue(count(p, Items.GOLD_INGOT) == 3, "submit should take exactly 2, left " + count(p, Items.GOLD_INGOT));
        helper.assertTrue(pr.completed.contains(Q + "hand_in"), "hand_in not completed");
        act(p, "claim", Q + "hand_in", -1);
        helper.assertTrue(p.getTags().contains("pp_cmd"), "command reward did not run");
        helper.succeed();
    }

    /** Kills count; an exclusive pick closes the other side; a quest without tasks completes and auto-claims. */
    @GameTest(template = "empty3x3x3")
    public static void killExclusiveAndFree(GameTestHelper helper) {
        ServerPlayer p = player(helper);
        PlayerProgress pr = ProgressService.progress(p);
        Zombie z = helper.spawn(EntityType.ZOMBIE, POS);
        CommonClass.onKill(p, z);
        helper.assertTrue(pr.completed.contains(Q + "zombie"), "kill task not completed");
        act(p, "check", Q + "left", 0);
        act(p, "check", Q + "right", 0);
        helper.assertTrue(pr.completed.contains(Q + "left") && !pr.completed.contains(Q + "right"), "exclusive group not enforced");
        helper.assertTrue(pr.completed.contains(Q + "free") && count(p, Items.APPLE) == 1, "task-less quest did not complete with auto reward");
        z.discard();
        helper.succeed();
    }

    /** A choice table needs a pick; the pick decides the reward. */
    @GameTest(template = "empty3x3x3")
    public static void choiceReward(GameTestHelper helper) {
        ServerPlayer p = player(helper);
        PlayerProgress pr = ProgressService.progress(p);
        act(p, "check", Q + "pick", 0);
        act(p, "claim_all", "", -1);
        helper.assertTrue(!pr.claimed.contains(Q + "pick"), "choice reward claimed without a pick");
        act(p, "claim", Q + "pick", 1);
        helper.assertTrue(count(p, Items.LAPIS_LAZULI) == 5 && count(p, Items.EMERALD) == 0, "wrong choice given");
        helper.succeed();
    }

    /** Mining logs and ripe wheat gives experience with Pufferfish's Skills' conditions; points buy skills with attributes. */
    @GameTest(template = "empty3x3x3")
    public static void skillExperienceAndTake(GameTestHelper helper) {
        ServerPlayer p = player(helper);
        PlayerProgress pr = ProgressService.progress(p);
        CommonClass.onBlockBroken(p, Blocks.STONE.defaultBlockState());
        helper.assertTrue(pr.xp.getOrDefault(S, 0.0) == 0 && pr.level.getOrDefault(S, 0) == 0, "stone gave experience");
        CommonClass.onBlockBroken(p, Blocks.WHEAT.defaultBlockState());
        helper.assertTrue(pr.xp.getOrDefault(S, 0.0) == 0, "unripe wheat gave experience");
        CommonClass.onBlockBroken(p, Blocks.OAK_LOG.defaultBlockState());
        helper.assertTrue(pr.xp.getOrDefault(S, 0.0) == 5, "log should give 5, got " + pr.xp.get(S));
        CommonClass.onBlockBroken(p, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        helper.assertTrue(pr.level.getOrDefault(S, 0) == 1 && pr.xp.getOrDefault(S, 0.0) == 5,
                "ripe wheat should level up once (level " + pr.level.get(S) + ", xp " + pr.xp.get(S) + ")");
        double health = p.getAttributeValue(Attributes.MAX_HEALTH);
        act(p, "take", S + "/next", 0);
        helper.assertTrue(!pr.taken.containsKey(S + "/next"), "took a locked skill");
        act(p, "take", S + "/root", 0);
        helper.assertTrue(pr.taken.getOrDefault(S + "/root", 0) == 1, "root not taken");
        helper.assertTrue(p.getAttributeValue(Attributes.MAX_HEALTH) == health + 2, "attribute reward missing");
        act(p, "take", S + "/next", 0);
        helper.assertTrue(!pr.taken.containsKey(S + "/next"), "took a skill without points");
        helper.succeed();
    }

    /** The mixins fire: crafting, smelting, eating and fishing give experience (separate chapter, 1000 per level). */
    @GameTest(template = "empty3x3x3")
    public static void mixinSources(GameTestHelper helper) {
        ServerPlayer p = player(helper);
        PlayerProgress pr = ProgressService.progress(p);
        String c = "pack_progress_gametest:crafts";
        var craft = new net.minecraft.world.inventory.ResultSlot(p, new net.minecraft.world.inventory.TransientCraftingContainer(p.inventoryMenu, 2, 2),
                new net.minecraft.world.inventory.ResultContainer(), 0, 0, 0);
        craft.onTake(p, new ItemStack(Items.STICK, 4));
        helper.assertTrue(pr.xp.getOrDefault(c, 0.0) == 1, "craft gave " + pr.xp.get(c));
        var furnace = new net.minecraft.world.inventory.FurnaceResultSlot(p, new net.minecraft.world.SimpleContainer(3), 2, 0, 0);
        furnace.onTake(p, new ItemStack(Items.IRON_INGOT));
        helper.assertTrue(pr.xp.getOrDefault(c, 0.0) == 21, "smelt: " + pr.xp.get(c));
        ItemStack bread = new ItemStack(Items.BREAD);
        p.eat(p.level(), bread, bread.get(net.minecraft.core.component.DataComponents.FOOD));
        helper.assertTrue(pr.xp.getOrDefault(c, 0.0) == 521, "bread (5 nutrition) should add 500: " + pr.xp.get(c));
        var hook = new net.minecraft.world.entity.projectile.FishingHook(p, p.level(), 0, 0);
        net.minecraft.advancements.CriteriaTriggers.FISHING_ROD_HOOKED.trigger(p, new ItemStack(Items.FISHING_ROD), hook,
                java.util.List.of(new ItemStack(Items.COD)));
        hook.discard();
        helper.assertTrue(pr.level.getOrDefault(c, 0) == 1 && pr.xp.getOrDefault(c, 0.0) == 521, "fish should level up: level "
                + pr.level.get(c) + " xp " + pr.xp.get(c));
        helper.succeed();
    }
}
