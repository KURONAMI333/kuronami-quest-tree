package com.kuronami.questtree;

import com.kuronami.questtree.block.ModBlockEntities;
import com.kuronami.questtree.block.ModBlocks;
import com.kuronami.questtree.block.QuestBarrierBlock;
import com.kuronami.questtree.block.QuestBarrierBlockEntity;
import com.kuronami.questtree.block.QuestDetectorBlock;
import com.kuronami.questtree.block.QuestDetectorBlockEntity;
import com.kuronami.questtree.block.LootCrateOpenerBlock;
import com.kuronami.questtree.block.LootCrateOpenerBlockEntity;
import com.kuronami.questtree.block.QuestTaskBlock;
import com.kuronami.questtree.block.QuestTaskBlockEntity;
import com.kuronami.questtree.block.TaskScreenAuxBlockEntity;
import com.kuronami.questtree.block.TaskScreenBlock;
import com.kuronami.questtree.block.TaskScreenBlockEntity;
import com.kuronami.questtree.client.ClientProgress;
import com.kuronami.questtree.client.EditorSession;
import com.kuronami.questtree.item.LootCrateItem;
import com.kuronami.questtree.item.ModDataComponents;
import com.kuronami.questtree.item.ModItems;
import com.kuronami.questtree.item.QuestBlockItem;
import com.kuronami.questtree.item.QuestBookItem;
import com.kuronami.questtree.item.TaskScreenConfiguratorItem;
import com.kuronami.questtree.network.ScreenBindOpenPayload;
import com.kuronami.questtree.network.ScreenBindPayload;
import com.kuronami.questtree.server.TaskScreenService;
import com.kuronami.questtree.server.LootCrates;
import com.kuronami.questtree.server.Restrictions;
import com.kuronami.questtree.network.ActionPayload;
import com.kuronami.questtree.network.EditorOpPayload;
import com.kuronami.questtree.network.EditorStatePayload;
import com.kuronami.questtree.network.EditorReplyPayload;
import com.kuronami.questtree.network.OpenPayload;
import com.kuronami.questtree.network.ProgressPayload;
import com.kuronami.questtree.network.SyncPayload;
import com.kuronami.questtree.network.TeamPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(Constants.MOD_ID)
public class QuestTree {

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Constants.MOD_ID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Constants.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, Constants.MOD_ID);
    private static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, Constants.MOD_ID);

    public static final DeferredBlock<QuestTaskBlock> QUEST_TASK_BLOCK = BLOCKS.register("quest_task_block",
            () -> new QuestTaskBlock(BlockBehaviour.Properties.of().strength(1.5F)));
    public static final DeferredBlock<QuestBarrierBlock> QUEST_BARRIER_BLOCK = BLOCKS.register("quest_barrier",
            () -> new QuestBarrierBlock(BlockBehaviour.Properties.of().strength(-1.0F, 3600000.0F).noOcclusion()));
    public static final DeferredBlock<QuestDetectorBlock> QUEST_DETECTOR_BLOCK = BLOCKS.register("quest_detector",
            () -> new QuestDetectorBlock(BlockBehaviour.Properties.of().strength(0.3F)));
    public static final DeferredBlock<TaskScreenBlock> TASK_SCREEN_1 = BLOCKS.register("task_screen_1",
            () -> new TaskScreenBlock(screenProps(), 1));
    public static final DeferredBlock<TaskScreenBlock> TASK_SCREEN_3 = BLOCKS.register("task_screen_3",
            () -> new TaskScreenBlock(screenProps(), 3));
    public static final DeferredBlock<TaskScreenBlock> TASK_SCREEN_5 = BLOCKS.register("task_screen_5",
            () -> new TaskScreenBlock(screenProps(), 5));
    public static final DeferredBlock<TaskScreenBlock> TASK_SCREEN_7 = BLOCKS.register("task_screen_7",
            () -> new TaskScreenBlock(screenProps(), 7));
    public static final DeferredBlock<TaskScreenBlock.Aux> TASK_SCREEN_AUX = BLOCKS.register("task_screen_aux",
            () -> new TaskScreenBlock.Aux(screenProps()));

    private static BlockBehaviour.Properties screenProps() {
        return BlockBehaviour.Properties.of().noOcclusion().strength(0.3F);
    }
    public static final DeferredBlock<LootCrateOpenerBlock> LOOT_CRATE_OPENER = BLOCKS.register("loot_crate_opener",
            () -> new LootCrateOpenerBlock(BlockBehaviour.Properties.of().strength(1.8F)));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<QuestTaskBlockEntity>> QUEST_TASK_BE =
            BLOCK_ENTITIES.register("quest_task_block", () -> {
                BlockEntityType<QuestTaskBlockEntity> type = BlockEntityType.Builder
                        .of(QuestTaskBlockEntity::new, QUEST_TASK_BLOCK.get()).build(null);
                ModBlockEntities.setTask(type);
                return type;
            });
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<QuestBarrierBlockEntity>> QUEST_BARRIER_BE =
            BLOCK_ENTITIES.register("quest_barrier", () -> {
                BlockEntityType<QuestBarrierBlockEntity> type = BlockEntityType.Builder
                        .of(QuestBarrierBlockEntity::new, QUEST_BARRIER_BLOCK.get()).build(null);
                ModBlockEntities.setBarrier(type);
                return type;
            });
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<QuestDetectorBlockEntity>> QUEST_DETECTOR_BE =
            BLOCK_ENTITIES.register("quest_detector", () -> {
                BlockEntityType<QuestDetectorBlockEntity> type = BlockEntityType.Builder
                        .of(QuestDetectorBlockEntity::new, QUEST_DETECTOR_BLOCK.get()).build(null);
                ModBlockEntities.setDetector(type);
                return type;
            });
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TaskScreenBlockEntity>> TASK_SCREEN_BE =
            BLOCK_ENTITIES.register("task_screen", () -> {
                BlockEntityType<TaskScreenBlockEntity> type = BlockEntityType.Builder
                        .of(TaskScreenBlockEntity::new, TASK_SCREEN_1.get(), TASK_SCREEN_3.get(),
                                TASK_SCREEN_5.get(), TASK_SCREEN_7.get()).build(null);
                ModBlockEntities.setTaskScreen(type);
                return type;
            });
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TaskScreenAuxBlockEntity>> TASK_SCREEN_AUX_BE =
            BLOCK_ENTITIES.register("task_screen_aux", () -> {
                BlockEntityType<TaskScreenAuxBlockEntity> type = BlockEntityType.Builder
                        .of(TaskScreenAuxBlockEntity::new, TASK_SCREEN_AUX.get()).build(null);
                ModBlockEntities.setTaskScreenAux(type);
                return type;
            });
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LootCrateOpenerBlockEntity>> LOOT_CRATE_OPENER_BE =
            BLOCK_ENTITIES.register("loot_crate_opener", () -> {
                BlockEntityType<LootCrateOpenerBlockEntity> type = BlockEntityType.Builder
                        .of(LootCrateOpenerBlockEntity::new, LOOT_CRATE_OPENER.get()).build(null);
                ModBlockEntities.setLootCrateOpener(type);
                return type;
            });

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> LOOT_CRATE_COMPONENT =
            DATA_COMPONENTS.register("loot_crate", () -> DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                    .build());

    public static final DeferredItem<BlockItem> QUEST_TASK_ITEM = ITEMS.register("quest_task_block", () -> {
        BlockItem item = new QuestBlockItem(QUEST_TASK_BLOCK.get(), new Item.Properties(),
                "block.kuronami_quest_tree.quest_task_block.desc");
        ModBlocks.setTask(QUEST_TASK_BLOCK.get(), item);
        return item;
    });
    public static final DeferredItem<BlockItem> QUEST_BARRIER_ITEM = ITEMS.register("quest_barrier", () -> {
        BlockItem item = new QuestBlockItem(QUEST_BARRIER_BLOCK.get(), new Item.Properties(),
                "block.kuronami_quest_tree.quest_barrier.desc");
        ModBlocks.setBarrier(QUEST_BARRIER_BLOCK.get(), item);
        return item;
    });
    public static final DeferredItem<BlockItem> QUEST_DETECTOR_ITEM = ITEMS.register("quest_detector", () -> {
        BlockItem item = new QuestBlockItem(QUEST_DETECTOR_BLOCK.get(), new Item.Properties(),
                "block.kuronami_quest_tree.quest_detector.desc");
        ModBlocks.setDetector(QUEST_DETECTOR_BLOCK.get(), item);
        return item;
    });
    public static final DeferredItem<BlockItem> TASK_SCREEN_1_ITEM = screenItem(1, TASK_SCREEN_1);
    public static final DeferredItem<BlockItem> TASK_SCREEN_3_ITEM = screenItem(3, TASK_SCREEN_3);
    public static final DeferredItem<BlockItem> TASK_SCREEN_5_ITEM = screenItem(5, TASK_SCREEN_5);
    public static final DeferredItem<BlockItem> TASK_SCREEN_7_ITEM = screenItem(7, TASK_SCREEN_7);

    private static DeferredItem<BlockItem> screenItem(int size, DeferredBlock<TaskScreenBlock> block) {
        return ITEMS.register("task_screen_" + size, () -> {
            BlockItem item = new QuestBlockItem(block.get(), new Item.Properties(),
                    "block.kuronami_quest_tree.task_screen.desc");
            ModBlocks.setTaskScreen(size, block.get(), item);
            return item;
        });
    }

    public static final DeferredItem<TaskScreenConfiguratorItem> TASK_SCREEN_CONFIGURATOR =
            ITEMS.register("task_screen_configurator", () -> {
                TaskScreenConfiguratorItem item = new TaskScreenConfiguratorItem(new Item.Properties().stacksTo(1));
                ModItems.setTaskScreenConfigurator(item);
                return item;
            });
    public static final DeferredItem<BlockItem> LOOT_CRATE_OPENER_ITEM = ITEMS.register("loot_crate_opener", () -> {
        BlockItem item = new BlockItem(LOOT_CRATE_OPENER.get(), new Item.Properties());
        ModBlocks.setLootCrateOpener(LOOT_CRATE_OPENER.get(), item);
        return item;
    });

    /** The loot crate item. Its table id lives in {@link #LOOT_CRATE_COMPONENT}. */
    public static final DeferredItem<LootCrateItem> LOOT_CRATE = ITEMS.register("loot_crate", () -> {
        LootCrateItem item = new LootCrateItem(new Item.Properties());
        ModItems.setLootCrate(item);
        return item;
    });

    /** The quest book. The supplier also fills the common holder, so common code needs no loader reference. */
    public static final DeferredItem<QuestBookItem> QUEST_BOOK = ITEMS.register("quest_book", () -> {
        QuestBookItem item = new QuestBookItem(new Item.Properties().stacksTo(1));
        ModItems.setQuestBook(item);
        return item;
    });

    public QuestTree(IEventBus modBus) {
        CommonClass.init();
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        ITEMS.register(modBus);
        DATA_COMPONENTS.register(modBus);
        ModDataComponents.setLootCrate(LOOT_CRATE_COMPONENT);
        modBus.addListener((net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
                e.accept(QUEST_BOOK);
                e.accept(QUEST_TASK_ITEM);
                e.accept(QUEST_BARRIER_ITEM);
                e.accept(QUEST_DETECTOR_ITEM);
                e.accept(TASK_SCREEN_1_ITEM);
                e.accept(TASK_SCREEN_3_ITEM);
                e.accept(TASK_SCREEN_5_ITEM);
                e.accept(TASK_SCREEN_7_ITEM);
                e.accept(TASK_SCREEN_CONFIGURATOR);
                e.accept(LOOT_CRATE_OPENER_ITEM);
                e.accept(LOOT_CRATE);
            }
        });
        modBus.addListener(QuestTree::registerPayloads);
        modBus.addListener(QuestTree::registerCapabilities);
        NeoForge.EVENT_BUS.addListener(QuestTree::addReloadListener);
        NeoForge.EVENT_BUS.addListener((OnDatapackSyncEvent e) -> e.getRelevantPlayers().forEach(CommonClass::onSyncToPlayer));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> CommonClass.registerCommands(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> CommonClass.onServerStarted(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> CommonClass.onServerStopped());
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> CommonClass.onServerTick(e.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerTickEvent.Post e) -> {
            if (e.getEntity() instanceof ServerPlayer sp) {
                CommonClass.onPlayerTick(sp);
            }
        });
        // the book drop runs last, after every mod that may cancel the death: a cancelled death drops nothing
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, (LivingDeathEvent e) -> {
            if (e.getEntity().level().isClientSide() || e.isCanceled()) {
                return;
            }
            if (e.getEntity() instanceof ServerPlayer dead) {
                CommonClass.onDeath(dead);
            }
        });
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent e) -> {
            if (e.getEntity().level().isClientSide() || e.isCanceled()) {
                return;
            }
            if (e.getSource().getEntity() instanceof ServerPlayer sp && e.getEntity() != sp) {
                // a player's own death is not a kill: do not count suicide as a kill task (2026-10-22 review)
                CommonClass.onKill(sp, e.getEntity());
            }
        });
        // a restricted block is refused before it breaks; the counting listener below only sees breaks that happen
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.living.LivingDropsEvent e) -> {
            if (!e.getEntity().level().isClientSide()
                    && e.getEntity().level() instanceof net.minecraft.server.level.ServerLevel level) {
                LootCrates.onLivingDrops(level, e.getEntity(), e.getDrops());
            }
        });
        NeoForge.EVENT_BUS.addListener((BlockEvent.BreakEvent e) -> {
            if (e.getPlayer() instanceof ServerPlayer sp && Restrictions.blockedBreak(sp, e.getState())) {
                e.setCanceled(true);
            }
        });
        // lowest priority and not canceled: only breaks that really happen give experience
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, (BlockEvent.BreakEvent e) -> {
            if (!e.isCanceled() && e.getPlayer() instanceof ServerPlayer sp) {
                CommonClass.onBlockBroken(sp, e.getState());
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post e) -> {
            if (e.getEntity().level().isClientSide() || e.getNewDamage() <= 0) {
                return;
            }
            if (e.getEntity() instanceof ServerPlayer sp) {
                CommonClass.onTakeDamage(sp, e.getSource(), e.getNewDamage());
            }
            if (e.getSource().getEntity() instanceof ServerPlayer sp && e.getEntity() != sp) {
                CommonClass.onDealDamage(sp, e.getEntity(), e.getSource(), e.getNewDamage());
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer sp) {
                CommonClass.onRespawn(sp, !e.isEndConquered());
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer sp) {
                CommonClass.onLogout(sp);
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> CommonClass.onServerStopping());
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // the quest_book item is synced as a registry entry, so the client mod is required (like FTB Quests). The
        // version is bumped when a payload changes shape (2: ActionPayload gained its choice field; 3: the editor
        // gained its state payload; 4: the team view; 5: quest givers; 6: non-chat cues and restriction tooltips), so
        // an older client fails the handshake instead of silently missing a feature.
        var r = event.registrar("8");
        r.playToClient(SyncPayload.TYPE, SyncPayload.STREAM_CODEC, (payload, context) -> ClientProgress.onSync(payload));
        r.playToClient(com.kuronami.questtree.network.CrateLootPayload.TYPE,
                com.kuronami.questtree.network.CrateLootPayload.STREAM_CODEC,
                (payload, context) -> ClientProgress.onCrateLoot(payload));
        r.playToClient(ProgressPayload.TYPE, ProgressPayload.STREAM_CODEC, (payload, context) -> ClientProgress.onProgress(payload));
        r.playToClient(OpenPayload.TYPE, OpenPayload.STREAM_CODEC, (payload, context) -> ClientProgress.onOpen(payload));
        r.playToClient(TeamPayload.TYPE, TeamPayload.STREAM_CODEC, (payload, context) -> com.kuronami.questtree.client.ClientTeam.onTeam(payload));
        r.playToClient(com.kuronami.questtree.network.CuePayload.TYPE, com.kuronami.questtree.network.CuePayload.STREAM_CODEC,
                (payload, context) -> com.kuronami.questtree.client.ClientCues.onCue(payload));
        r.playToClient(EditorReplyPayload.TYPE, EditorReplyPayload.STREAM_CODEC,
                (payload, context) -> EditorSession.onReply(payload));
        r.playToClient(EditorStatePayload.TYPE, EditorStatePayload.STREAM_CODEC,
                (payload, context) -> EditorSession.setAllowed(payload.canEdit()));
        r.playToClient(com.kuronami.questtree.network.EditorChangePayload.TYPE,
                com.kuronami.questtree.network.EditorChangePayload.STREAM_CODEC,
                (payload, context) -> com.kuronami.questtree.client.EditorChangelog.on(payload));
        r.playToServer(ActionPayload.TYPE, ActionPayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer sp) {
                CommonClass.onAction(sp, payload);
            }
        });
        r.playToServer(EditorOpPayload.TYPE, EditorOpPayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer sp) {
                CommonClass.onEditorOp(sp, payload);
            }
        });
        r.playToClient(ScreenBindOpenPayload.TYPE, ScreenBindOpenPayload.STREAM_CODEC,
                (payload, context) -> com.kuronami.questtree.client.ClientTaskScreens.onOpen(payload));
        r.playToServer(ScreenBindPayload.TYPE, ScreenBindPayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer sp) {
                TaskScreenService.bind(sp, payload.pos(), payload.node(), payload.index());
            }
        });
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, TASK_SCREEN_BE.get(),
                (be, side) -> NeoForgeTaskScreenCapabilities.item(be));
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, TASK_SCREEN_BE.get(),
                (be, side) -> NeoForgeTaskScreenCapabilities.fluid(be));
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, TASK_SCREEN_BE.get(),
                (be, side) -> NeoForgeTaskScreenCapabilities.energy(be));
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, TASK_SCREEN_AUX_BE.get(),
                (be, side) -> NeoForgeTaskScreenCapabilities.item(be));
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, TASK_SCREEN_AUX_BE.get(),
                (be, side) -> NeoForgeTaskScreenCapabilities.fluid(be));
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, TASK_SCREEN_AUX_BE.get(),
                (be, side) -> NeoForgeTaskScreenCapabilities.energy(be));
    }

    private static void addReloadListener(AddReloadListenerEvent event) {
        event.addListener(new SimplePreparableReloadListener<Void>() {
            @Override
            protected Void prepare(ResourceManager rm, ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(Void nothing, ResourceManager rm, ProfilerFiller profiler) {
                CommonClass.onDataReload(rm);
            }
        });
    }
}
