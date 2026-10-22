package com.kuronami.questtree;

import com.kuronami.questtree.block.ModBlockEntities;
import com.kuronami.questtree.block.ModBlocks;
import com.kuronami.questtree.block.QuestBarrierBlock;
import com.kuronami.questtree.block.QuestBarrierBlockEntity;
import com.kuronami.questtree.block.QuestDetectorBlock;
import com.kuronami.questtree.block.QuestDetectorBlockEntity;
import com.kuronami.questtree.block.QuestTaskBlock;
import com.kuronami.questtree.block.QuestTaskBlockEntity;
import com.kuronami.questtree.block.TaskScreenAuxBlockEntity;
import com.kuronami.questtree.block.TaskScreenBlock;
import com.kuronami.questtree.block.TaskScreenBlockEntity;
import com.kuronami.questtree.item.ModItems;
import com.kuronami.questtree.server.Restrictions;
import com.kuronami.questtree.item.QuestBlockItem;
import com.kuronami.questtree.item.QuestBookItem;
import com.kuronami.questtree.item.TaskScreenConfiguratorItem;
import com.kuronami.questtree.network.ActionPayload;
import com.kuronami.questtree.network.CuePayload;
import com.kuronami.questtree.network.EditorOpPayload;
import com.kuronami.questtree.network.EditorReplyPayload;
import com.kuronami.questtree.network.EditorStatePayload;
import com.kuronami.questtree.network.OpenPayload;
import com.kuronami.questtree.network.ProgressPayload;
import com.kuronami.questtree.network.ScreenBindOpenPayload;
import com.kuronami.questtree.network.ScreenBindPayload;
import com.kuronami.questtree.network.SyncPayload;
import com.kuronami.questtree.network.TeamPayload;
import com.kuronami.questtree.server.TaskScreenService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public class QuestTree implements ModInitializer {

    public static final Item QUEST_BOOK = Registry.register(BuiltInRegistries.ITEM,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "quest_book"),
            new QuestBookItem(new Item.Properties().stacksTo(1)));

    public static final Block QUEST_TASK_BLOCK = Registry.register(BuiltInRegistries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "quest_task_block"),
            new QuestTaskBlock(BlockBehaviour.Properties.of().strength(1.5F)));
    public static final Block QUEST_BARRIER_BLOCK = Registry.register(BuiltInRegistries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "quest_barrier"),
            new QuestBarrierBlock(BlockBehaviour.Properties.of().strength(-1.0F, 3600000.0F).noOcclusion()));
    public static final Block QUEST_DETECTOR_BLOCK = Registry.register(BuiltInRegistries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "quest_detector"),
            new QuestDetectorBlock(BlockBehaviour.Properties.of().strength(0.3F)));
    public static final Block TASK_SCREEN_1 = Registry.register(BuiltInRegistries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "task_screen_1"),
            new TaskScreenBlock(screenProps(), 1));
    public static final Block TASK_SCREEN_3 = Registry.register(BuiltInRegistries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "task_screen_3"),
            new TaskScreenBlock(screenProps(), 3));
    public static final Block TASK_SCREEN_5 = Registry.register(BuiltInRegistries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "task_screen_5"),
            new TaskScreenBlock(screenProps(), 5));
    public static final Block TASK_SCREEN_7 = Registry.register(BuiltInRegistries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "task_screen_7"),
            new TaskScreenBlock(screenProps(), 7));
    public static final Block TASK_SCREEN_AUX = Registry.register(BuiltInRegistries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "task_screen_aux"),
            new TaskScreenBlock.Aux(screenProps()));

    private static BlockBehaviour.Properties screenProps() {
        return BlockBehaviour.Properties.of().noOcclusion().strength(0.3F);
    }

    public static final Item QUEST_TASK_ITEM = Registry.register(BuiltInRegistries.ITEM,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "quest_task_block"),
            new QuestBlockItem(QUEST_TASK_BLOCK, new Item.Properties(),
                    "block.kuronami_quest_tree.quest_task_block.desc"));
    public static final Item QUEST_BARRIER_ITEM = Registry.register(BuiltInRegistries.ITEM,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "quest_barrier"),
            new QuestBlockItem(QUEST_BARRIER_BLOCK, new Item.Properties(),
                    "block.kuronami_quest_tree.quest_barrier.desc"));
    public static final Item QUEST_DETECTOR_ITEM = Registry.register(BuiltInRegistries.ITEM,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "quest_detector"),
            new QuestBlockItem(QUEST_DETECTOR_BLOCK, new Item.Properties(),
                    "block.kuronami_quest_tree.quest_detector.desc"));
    public static final Item TASK_SCREEN_1_ITEM = screenItem(1, TASK_SCREEN_1);
    public static final Item TASK_SCREEN_3_ITEM = screenItem(3, TASK_SCREEN_3);
    public static final Item TASK_SCREEN_5_ITEM = screenItem(5, TASK_SCREEN_5);
    public static final Item TASK_SCREEN_7_ITEM = screenItem(7, TASK_SCREEN_7);

    private static Item screenItem(int size, Block block) {
        return Registry.register(BuiltInRegistries.ITEM,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "task_screen_" + size),
                new QuestBlockItem(block, new Item.Properties(), "block.kuronami_quest_tree.task_screen.desc"));
    }

    public static final Item TASK_SCREEN_CONFIGURATOR = Registry.register(BuiltInRegistries.ITEM,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "task_screen_configurator"),
            new TaskScreenConfiguratorItem(new Item.Properties().stacksTo(1)));

    public static BlockEntityType<TaskScreenBlockEntity> SCREEN_BE;
    public static BlockEntityType<TaskScreenAuxBlockEntity> SCREEN_AUX_BE;

    @Override
    public void onInitialize() {
        CommonClass.init();
        ModItems.setQuestBook(QUEST_BOOK);
        ModBlocks.setTask(QUEST_TASK_BLOCK, QUEST_TASK_ITEM);
        ModBlocks.setBarrier(QUEST_BARRIER_BLOCK, QUEST_BARRIER_ITEM);
        ModBlocks.setDetector(QUEST_DETECTOR_BLOCK, QUEST_DETECTOR_ITEM);
        BlockEntityType<QuestTaskBlockEntity> taskBe = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "quest_task_block"),
                BlockEntityType.Builder.of(QuestTaskBlockEntity::new, QUEST_TASK_BLOCK).build(null));
        ModBlockEntities.setTask(taskBe);
        BlockEntityType<QuestBarrierBlockEntity> barrierBe = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "quest_barrier"),
                BlockEntityType.Builder.of(QuestBarrierBlockEntity::new, QUEST_BARRIER_BLOCK).build(null));
        ModBlockEntities.setBarrier(barrierBe);
        BlockEntityType<QuestDetectorBlockEntity> detectorBe = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "quest_detector"),
                BlockEntityType.Builder.of(QuestDetectorBlockEntity::new, QUEST_DETECTOR_BLOCK).build(null));
        ModBlockEntities.setDetector(detectorBe);
        for (int size : new int[]{1, 3, 5, 7}) {
            Block block = switch (size) {
                case 1 -> TASK_SCREEN_1;
                case 3 -> TASK_SCREEN_3;
                case 5 -> TASK_SCREEN_5;
                default -> TASK_SCREEN_7;
            };
            Item item = switch (size) {
                case 1 -> TASK_SCREEN_1_ITEM;
                case 3 -> TASK_SCREEN_3_ITEM;
                case 5 -> TASK_SCREEN_5_ITEM;
                default -> TASK_SCREEN_7_ITEM;
            };
            ModBlocks.setTaskScreen(size, block, item);
        }
        ModBlocks.setTaskScreenAux(TASK_SCREEN_AUX);
        ModItems.setTaskScreenConfigurator(TASK_SCREEN_CONFIGURATOR);
        SCREEN_BE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "task_screen"),
                BlockEntityType.Builder.of(TaskScreenBlockEntity::new, TASK_SCREEN_1, TASK_SCREEN_3,
                        TASK_SCREEN_5, TASK_SCREEN_7).build(null));
        ModBlockEntities.setTaskScreen(SCREEN_BE);
        SCREEN_AUX_BE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "task_screen_aux"),
                BlockEntityType.Builder.of(TaskScreenAuxBlockEntity::new, TASK_SCREEN_AUX).build(null));
        ModBlockEntities.setTaskScreenAux(SCREEN_AUX_BE);
        ItemStorage.SIDED.registerForBlockEntity((be, side) -> FabricTaskScreenStorages.item(be), SCREEN_BE);
        ItemStorage.SIDED.registerForBlockEntity((be, side) -> FabricTaskScreenStorages.item(be), SCREEN_AUX_BE);
        FluidStorage.SIDED.registerForBlockEntity((be, side) -> FabricTaskScreenStorages.fluid(be), SCREEN_BE);
        FluidStorage.SIDED.registerForBlockEntity((be, side) -> FabricTaskScreenStorages.fluid(be), SCREEN_AUX_BE);
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(e -> {
            e.accept(QUEST_BOOK);
            e.accept(QUEST_TASK_ITEM);
            e.accept(QUEST_BARRIER_ITEM);
            e.accept(QUEST_DETECTOR_ITEM);
            e.accept(TASK_SCREEN_1_ITEM);
            e.accept(TASK_SCREEN_3_ITEM);
            e.accept(TASK_SCREEN_5_ITEM);
            e.accept(TASK_SCREEN_7_ITEM);
            e.accept(TASK_SCREEN_CONFIGURATOR);
        });
        // the quest_book item is synced, so the client mod is required (like FTB Quests)
        PayloadTypeRegistry.playS2C().register(SyncPayload.TYPE, SyncPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ProgressPayload.TYPE, ProgressPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(OpenPayload.TYPE, OpenPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(TeamPayload.TYPE, TeamPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(CuePayload.TYPE, CuePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(EditorReplyPayload.TYPE, EditorReplyPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(EditorStatePayload.TYPE, EditorStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ActionPayload.TYPE, ActionPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(EditorOpPayload.TYPE, EditorOpPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ScreenBindOpenPayload.TYPE, ScreenBindOpenPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ScreenBindPayload.TYPE, ScreenBindPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, context) -> CommonClass.onAction(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(EditorOpPayload.TYPE, (payload, context) -> CommonClass.onEditorOp(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(ScreenBindPayload.TYPE, (payload, context) ->
                TaskScreenService.bind(context.player(), payload.pos(), payload.node(), payload.index()));
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "progression");
            }

            @Override
            public void onResourceManagerReload(ResourceManager rm) {
                CommonClass.onDataReload(rm);
            }
        });
        // fires for every player on join and after /reload
        ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.register((player, joined) -> CommonClass.onSyncToPlayer(player));
        ServerLifecycleEvents.SERVER_STARTED.register(CommonClass::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> CommonClass.onServerStopped());
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> CommonClass.registerCommands(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            CommonClass.onServerTick(server);
            server.getPlayerList().getPlayers().forEach(CommonClass::onPlayerTick);
        });
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((level, killer, killed) -> {
            if (killer instanceof ServerPlayer sp) {
                CommonClass.onKill(sp, killed);
            }
        });
        // a restricted block is refused before it breaks, so nothing drops and no break task counts it
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
                !(player instanceof ServerPlayer sp) || !Restrictions.blockedBreak(sp, state));
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer sp) {
                CommonClass.onBlockBroken(sp, state);
            }
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> CommonClass.onRespawn(newPlayer, !alive));
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer sp) {
                CommonClass.onDeath(sp);
            }
        });
        // damage experience: FabricActuallyHurtMixin (health actually lost, same as NeoForge)
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> CommonClass.onLogout(handler.player));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> CommonClass.onServerStopping());
    }
}
