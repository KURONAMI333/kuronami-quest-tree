package com.kuronami.questtree.client;

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
        ClientModel.Node node = ClientProgress.model().nodesByKey.get(screen.node());
        if (node == null || screen.taskIndex() < 0 || screen.taskIndex() >= node.tasks.size()) {
            return;
        }
        ClientModel.Task task = node.tasks.get(screen.taskIndex());
        if (!(screen.getBlockState().getBlock() instanceof TaskScreenBlock block)) {
            return;
        }
        Direction facing = screen.getBlockState().getValue(TaskScreenBlock.FACING);
        int half = block.getSize() / 2;

        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(180));
        pose.mulPose(Axis.YP.rotationDegrees(facing.toYRot() + 180));
        pose.translate(-0.5, -0.5, -0.5);
        pose.translate(-half, -half * 2.0F, -0.02F);
        pose.scale(half * 2.0F + 1.0F, half * 2.0F + 1.0F, 1.0F);

        Matrix4f matrix = pose.last().pose();
        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        face(quads, matrix, 0.02F, 0.02F, 0.98F, 0.98F, Ui.PANEL_TRANSLUCENT);
        frame(quads, matrix, Ui.HAIRLINE);

        Font font = Minecraft.getInstance().font;
        text(font, buffers, pose, node.title, 0.15F, 0.15F, Ui.BRIGHT);
        text(font, buffers, pose, task.name(), 0.17F, 0.07F, Ui.TEXT);

        long target = Math.max(1, task.count);
        long progress = Math.min(screen.progress(), target);
        text(font, buffers, pose, Component.literal(progress + " / " + target), 0.83F, 0.15F,
                progress >= target ? Ui.ACCENT : Ui.TEXT);

        pose.pushPose();
        pose.translate(0.5, 0.54, -0.01);
        pose.scale(0.45F, 0.45F, 0.2F * half);
        pose.mulPose(Axis.XP.rotationDegrees(180));
        Minecraft.getInstance().getItemRenderer().renderStatic(task.icon(), ItemDisplayContext.GUI, light,
                OverlayTexture.NO_OVERLAY, pose, buffers, Minecraft.getInstance().level, 0);
        pose.popPose();

        float bar = 0.9F;
        face(quads, matrix, 0.08F, bar, 0.92F, bar + 0.035F, Ui.DIVIDER);
        if (progress > 0) {
            face(quads, matrix, 0.08F, bar, 0.08F + 0.84F * (progress / (float) target), bar + 0.035F, Ui.ACCENT);
        }

        pose.popPose();
    }

    private static void text(Font font, MultiBufferSource buffers, PoseStack pose, Component text, double y, float size,
                             int color) {
        if (text == null || text.getString().isEmpty()) {
            return;
        }
        pose.pushPose();
        pose.translate(0.5, y, 0.0);
        int width = font.width(text);
        float scale = size / 9.0F;
        float drawn = width * scale;
        if (drawn > 1.0F) {
            scale /= drawn;
            drawn = 1.0F;
        }
        if (drawn > 0.9F) {
            scale *= 0.9F;
        }
        pose.scale(scale, scale, 1.0F);
        font.drawInBatch(text, -width / 2.0F, 0.0F, color, false, pose.last().pose(), buffers,
                Font.DisplayMode.NORMAL, 0, 15728880);
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
