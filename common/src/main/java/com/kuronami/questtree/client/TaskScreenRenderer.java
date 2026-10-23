package com.kuronami.questtree.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.kuronami.questtree.block.TaskScreenBlock;
import com.kuronami.questtree.block.TaskScreenBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;

/**
 * Draws a task screen (2026-10-22, FTB Quests parity): a face colour, the quest and task names, the task icon and the
 * progress, all from the core block entity's synced binding and progress. The face lies in the screen's width/height
 * plane, so the same renderer works for all four sizes.
 */
public class TaskScreenRenderer implements BlockEntityRenderer<TaskScreenBlockEntity> {
    /**
     * Glyph quads written into the level's shared source are flushed before the block entity pass is over, so the
     * letters are written into a source of their own and flushed at the end of the screen.
     */
    private static final MultiBufferSource.BufferSource TEXT =
            MultiBufferSource.immediate(new ByteBufferBuilder(4096));

    public TaskScreenRenderer(BlockEntityRendererProvider.Context context) {
    }

    /**
     * A 5x5 or 7x7 screen's core block can sit outside the frustum while its face is still in view; the screen is one
     * drawn surface, so it must not blink out when that block is culled.
     */
    @Override
    public boolean shouldRenderOffScreen(TaskScreenBlockEntity screen) {
        return true;
    }

    @Override
    public void render(TaskScreenBlockEntity screen, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        if (screen.node().isEmpty() || Minecraft.getInstance().level == null) {
            return;
        }
        ClientModel model = ClientProgress.model();
        if (model == null) {
            return;
        }
        ClientModel.Node node = model.nodesByKey.get(screen.node());
        if (node == null || screen.taskIndex() < 0 || screen.taskIndex() >= node.tasks.size()) {
            return;
        }
        ClientModel.Task task = node.tasks.get(screen.taskIndex());
        if (!(screen.getBlockState().getBlock() instanceof TaskScreenBlock block)) {
            return;
        }
        Direction facing = screen.getBlockState().getValue(TaskScreenBlock.FACING);
        int size = block.getSize();
        int half = size / 2;
        long target = Math.max(1, task.count);
        long progress = Math.min(screen.progress(), target);

        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(facing.toYRot() + 180));
        pose.translate(-0.5, -0.5, -0.5);
        // the core is the bottom-centre of the face (TaskScreenBlock.screenPositions), so the face grows upward
        pose.translate(-size / 2.0F, 0.0F, -0.02F);
        pose.scale(half * 2.0F + 1.0F, half * 2.0F + 1.0F, 1.0F);

        Matrix4f matrix = pose.last().pose();
        // Every quad is written before the text and the icon: a shared buffer type (debugQuads) is closed as soon as
        // another type asks the same source for a buffer, so a reference held across those calls can no longer be used.
        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        face(quads, matrix, 0.02F, 0.02F, 0.98F, 0.98F, Ui.PANEL_TRANSLUCENT);
        frame(quads, matrix, Ui.HAIRLINE);
        face(quads, matrix, 0.08F, 0.08F, 0.92F, 0.115F, Ui.DIVIDER);
        if (progress > 0) {
            face(quads, matrix, 0.08F, 0.08F, 0.08F + 0.84F * (progress / (float) target), 0.115F, Ui.ACCENT);
        }
        if (buffers instanceof MultiBufferSource.BufferSource source) {
            source.endBatch(RenderType.debugQuads());
        }

        pose.translate(0.5, 0.45, 0.0F);
        pose.scale(0.35F, 0.35F, 0.2F * half);
        pose.mulPose(Axis.XP.rotationDegrees(180));
        Minecraft.getInstance().getItemRenderer().renderStatic(task.icon(), ItemDisplayContext.GUI, light,
                OverlayTexture.NO_OVERLAY, pose, buffers, Minecraft.getInstance().level, 0);
        pose.popPose();

        Font font = Minecraft.getInstance().font;
        text(font, pose, facing, node.title, 0.88F, 0.09F, size, Ui.DIM);
        text(font, pose, facing, task.name(), 0.74F, 0.16F, size, Ui.BRIGHT);
        text(font, pose, facing, Component.literal(progress + " / " + target), 0.26F, 0.13F, size,
                progress >= target ? Ui.ACCENT : Ui.TEXT);
        TEXT.endBatch();
    }

    /**
     * One line lying on the face: {@code y} runs 0..1 from the bottom of the face and the line is centred. It is
     * placed in the screen's own frame rather than the face's, so the glyphs keep their aspect whatever the size and
     * whichever way the screen faces.
     */
    private static void text(Font font, PoseStack pose, Direction facing, Component text, float y, float textSize,
                             int size, int color) {
        if (text == null || text.getString().isEmpty()) {
            return;
        }
        int width = font.width(text);
        float scale = textSize * size / 9.0F;
        if (width * scale > size * 0.9F) {
            scale = size * 0.9F / width;
        }
        pose.pushPose();
        pose.translate(0.5, 0.0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(facing.toYRot() + 180));
        pose.translate(0.0, y * size, -0.53F);
        pose.scale(-scale, -scale, -scale);
        font.drawInBatch(text, -width / 2.0F, 0.0F, color, false, pose.last().pose(), TEXT,
                Font.DisplayMode.NORMAL, 0xF000F0, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    private static void face(VertexConsumer quads, Matrix4f matrix, float x1, float y1, float x2, float y2, int color) {
        quads.addVertex(matrix, x1, y1, 0).setColor(color);
        quads.addVertex(matrix, x1, y2, 0).setColor(color);
        quads.addVertex(matrix, x2, y2, 0).setColor(color);
        quads.addVertex(matrix, x2, y1, 0).setColor(color);
    }

    private static void frame(VertexConsumer quads, Matrix4f matrix, int color) {
        face(quads, matrix, 0.02F, 0.02F, 0.98F, 0.04F, color);
        face(quads, matrix, 0.02F, 0.96F, 0.98F, 0.98F, color);
        face(quads, matrix, 0.02F, 0.02F, 0.04F, 0.98F, color);
        face(quads, matrix, 0.96F, 0.02F, 0.98F, 0.98F, color);
    }
}
