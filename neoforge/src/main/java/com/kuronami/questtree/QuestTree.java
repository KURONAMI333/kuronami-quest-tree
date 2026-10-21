package com.kuronami.questtree;

import com.kuronami.questtree.block.ModBlockEntities;
import com.kuronami.questtree.block.ModBlocks;
import com.kuronami.questtree.block.QuestBarrierBlock;
import com.kuronami.questtree.block.QuestBarrierBlockEntity;
import com.kuronami.questtree.block.QuestTaskBlock;
import com.kuronami.questtree.block.QuestTaskBlockEntity;
import com.kuronami.questtree.client.ClientProgress;
import com.kuronami.questtree.client.EditorSession;
import com.kuronami.questtree.item.ModItems;
import com.kuronami.questtree.item.QuestBookItem;
import com.kuronami.questtree.network.ActionPayload;
import com.kuronami.questtree.network.EditorOpPayload;
import com.kuronami.questtree.network.EditorStatePayload;
import com.kuronami.questtree.network.EditorReplyPayload;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
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

    public static final DeferredBlock<QuestTaskBlock> QUEST_TASK_BLOCK = BLOCKS.register("quest_task_block",
            () -> new QuestTaskBlock(BlockBehaviour.Properties.of().strength(1.5F)));
    public static final DeferredBlock<QuestBarrierBlock> QUEST_BARRIER_BLOCK = BLOCKS.register("quest_barrier",
            () -> new QuestBarrierBlock(BlockBehaviour.Properties.of().strength(-1.0F, 3600000.0F).noOcclusion()));

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

    public static final DeferredItem<BlockItem> QUEST_TASK_ITEM = ITEMS.register("quest_task_block", () -> {
        BlockItem item = new BlockItem(QUEST_TASK_BLOCK.get(), new Item.Properties());
        ModBlocks.setTask(QUEST_TASK_BLOCK.get(), item);
        return item;
    });
    public static final DeferredItem<BlockItem> QUEST_BARRIER_ITEM = ITEMS.register("quest_barrier", () -> {
        BlockItem item = new BlockItem(QUEST_BARRIER_BLOCK.get(), new Item.Properties());
        ModBlocks.setBarrier(QUEST_BARRIER_BLOCK.get(), item);
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
        modBus.addListener((net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
                e.accept(QUEST_BOOK);
                e.accept(QUEST_TASK_ITEM);
                e.accept(QUEST_BARRIER_ITEM);
            }
        });
        modBus.addListener(QuestTree::registerPayloads);
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
            if (e.getSource().getEntity() instanceof ServerPlayer sp) {
                CommonClass.onKill(sp, e.getEntity());
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
        // gained its state payload; 4: the team view), so an older client fails the handshake instead of silently
        // missing a feature.
        var r = event.registrar("4");
        r.playToClient(SyncPayload.TYPE, SyncPayload.STREAM_CODEC, (payload, context) -> ClientProgress.onSync(payload));
        r.playToClient(ProgressPayload.TYPE, ProgressPayload.STREAM_CODEC, (payload, context) -> ClientProgress.onProgress(payload));
        r.playToClient(TeamPayload.TYPE, TeamPayload.STREAM_CODEC, (payload, context) -> com.kuronami.questtree.client.ClientTeam.onTeam(payload));
        r.playToClient(EditorReplyPayload.TYPE, EditorReplyPayload.STREAM_CODEC,
                (payload, context) -> EditorSession.onReply(payload));
        r.playToClient(EditorStatePayload.TYPE, EditorStatePayload.STREAM_CODEC,
                (payload, context) -> EditorSession.setAllowed(payload.canEdit()));
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
