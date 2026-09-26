package io.github.zgxhzhr.superdbg.client.gui;

import io.github.zgxhzhr.superdbg.client.entityclear.EntityClearClientState;
import io.github.zgxhzhr.superdbg.entityclear.EntityClearConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 清除前预览确认：列出将要清除的分类明细与总数，超过 1 万红字警告。
 * 数据来自最近一次服务端扫描（{@link EntityClearClientState}）。
 */
public final class EntityClearConfirmScreen extends Screen {

    private static final int PANEL_W = 300;
    private static final int PANEL_H = 240;
    private static final int ROW_H = 12;
    private static final int ROWS_VIEW = 13;
    private static final int LIST_Y = 48;
    private static final int WARNING_THRESHOLD = 10000;

    private int left;
    private int top;
    private int scroll;

    private final EntityClearConfig config = EntityClearClientState.scannedConfig();
    private final List<Map.Entry<ResourceLocation, Integer>> rows = new ArrayList<>();
    private int total;

    public EntityClearConfirmScreen() {
        super(Component.literal("清除预览"));
        boolean blacklist = config != null
                && config.mode == EntityClearConfig.MODE_BLACKLIST;
        for (Map.Entry<ResourceLocation, Integer> e :
                EntityClearClientState.counts().entrySet()) {
            boolean inList = config != null
                    && config.types.contains(e.getKey().toString());
            boolean affected = blacklist != inList; // 黑：不在名单才清除；白：在名单才清除
            if (affected && e.getValue() > 0) {
                rows.add(e);
                total += e.getValue();
            }
        }
        rows.sort(Comparator.comparingInt((Map.Entry<ResourceLocation, Integer> e) ->
                e.getValue()).reversed());
    }

    @Override
    protected void init() {
        super.init();
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;

        addRenderableWidget(Button.builder(Component.literal("取消"),
                        b -> onClose())
                .bounds(left + 60, top + PANEL_H - 22, 80, 18).build());
        addRenderableWidget(Button.builder(Component.literal("§c确认清除"), b -> {
            if (config != null) {
                io.github.zgxhzhr.superdbg.network.NetworkHandler.CHANNEL.sendToServer(
                        new io.github.zgxhzhr.superdbg.network.EntityClearPacket(true, config.copy()));
            }
        }).bounds(left + 160, top + PANEL_H - 22, 80, 18).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int max = Math.max(0, rows.size() - ROWS_VIEW);
        scroll = Math.max(0, Math.min(max, scroll - (delta > 0 ? 3 : -3)));
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(new EntityClearScreen());
    }

    private static String nameOf(ResourceLocation id) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        return type == null ? id.toString()
                : Component.translatable(type.getDescriptionId()).getString();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.fill(left, top, left + PANEL_W, top + PANEL_H, 0xFF555555);
        g.fill(left + 1, top + 1, left + PANEL_W - 1, top + PANEL_H - 1, 0xFFFFFFFF);
        g.fill(left + 2, top + 2, left + PANEL_W - 2, top + PANEL_H - 2, 0xFFC6C6C6);
        super.render(g, mouseX, mouseY, partialTick);

        g.drawString(font, Component.literal("清除预览（请确认明细）"),
                left + 10, top + 6, 0x404040, false);
        g.drawString(font, Component.literal("§c将清除 §e" + total + " §c个实体"),
                left + 10, top + 20, 0x404040, false);
        g.drawString(font, Component.literal(
                        "§7保护跳过 " + EntityClearClientState.protectedCount() + " 个（本人始终保护）"),
                left + 130, top + 20, 0x404040, false);

        if (total > WARNING_THRESHOLD) {
            g.drawString(font, Component.literal(
                            "§4§l警告：数量超过 10000，可能造成短暂卡顿，请确认范围！"),
                    left + 10, top + 34, 0, false);
        }

        g.enableScissor(left + 4, top + LIST_Y - 2, left + PANEL_W - 4,
                top + LIST_Y + ROWS_VIEW * ROW_H + 2);
        for (int i = 0; i < ROWS_VIEW; i++) {
            int idx = scroll + i;
            if (idx >= rows.size()) {
                break;
            }
            Map.Entry<ResourceLocation, Integer> e = rows.get(idx);
            int y = top + LIST_Y + i * ROW_H;
            g.drawString(font, Component.literal("§c×" + e.getValue()),
                    left + 10, y + 2, 0, false);
            g.drawString(font, Component.literal(nameOf(e.getKey())),
                    left + 56, y + 2, 0x000000, false);
            g.drawString(font, Component.literal("§8" + e.getKey()),
                    left + 170, y + 2, 0, false);
        }
        g.disableScissor();

        g.drawString(font, Component.literal(
                        rows.size() + " 种类型" + (rows.size() > ROWS_VIEW ? "（滚轮翻页）" : "")),
                left + 10, top + PANEL_H - 34, 0x707070, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
