package io.github.zgxhzhr.superdbg.client.gui;

import io.github.zgxhzhr.superdbg.network.NetworkHandler;
import io.github.zgxhzhr.superdbg.network.OpenGameRuleEditorPacket;
import io.github.zgxhzhr.superdbg.network.SubmitGameRuleEditorPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 游戏规则编辑屏幕：按 Category 分组，boolean 用 CycleButton（开/关），
 * integer 用 EditBox。鼠标悬停时显示原始键 id 和默认值（vanilla 风格）。
 */
public class GameRuleEditorScreen extends Screen {

    /** Category 显示顺序（先原版 7 个固定顺序，模组自定义的归到"模组"组） */
    private static final String[] VANILLA_CATEGORY_ORDER = {
            "gamerule.category.player",
            "gamerule.category.mobs",
            "gamerule.category.spawning",
            "gamerule.category.drops",
            "gamerule.category.updates",
            "gamerule.category.chat",
            "gamerule.category.misc",
    };

    /** 模组自定义 category 统一归入此分组 */
    private static final String MODDED_GROUP_KEY = "superdbg.gamerule.category.modded";

    private static final int ROW_HEIGHT = 19;
    private static final int CONTENT_TOP = 24;
    private static final int VISIBLE_ROWS = 10;
    private static final int ROW_LABEL_MAX_WIDTH = 190;
    private static final int PANEL_WIDTH = 340;
    private static final int PANEL_HEIGHT = 300;

    private final OpenGameRuleEditorPacket data;

    private int leftPos;
    private int topPos;

    /** 所有行（扁平化，滚轮用） */
    private final List<GameRuleRow> allRows = new ArrayList<>();

    private Button confirmButton;
    private Button cancelButton;

    private int scroll = 0;

    public GameRuleEditorScreen(OpenGameRuleEditorPacket data) {
        super(Component.literal("编辑游戏规则"));
        this.data = data;
    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = (width - PANEL_WIDTH) / 2;
        this.topPos = (height - PANEL_HEIGHT) / 2;
        try {
            superdbg$initContent();
        } catch (Exception e) {
            io.github.zgxhzhr.superdbg.Constants.LOG.error("[GameruleEditor] init 异常", e);
        }
    }

    private void superdbg$initContent() {
        // ---- 分组 ----
        Map<String, List<OpenGameRuleEditorPacket.GameRuleEntry>> byCategory = new LinkedHashMap<>();
        for (OpenGameRuleEditorPacket.GameRuleEntry entry : data.entries()) {
            String cat = entry.categoryKey();
            if (!byCategory.containsKey(cat)) {
                byCategory.put(cat, new ArrayList<>());
            }
            byCategory.get(cat).add(entry);
        }

        // 分组按顺序展开
        List<String> orderedCats = new ArrayList<>();
        for (String van : VANILLA_CATEGORY_ORDER) {
            if (byCategory.containsKey(van)) {
                orderedCats.add(van);
            }
        }
        // 模组自定义 category 合并到"模组"组
        List<OpenGameRuleEditorPacket.GameRuleEntry> modded = new ArrayList<>();
        for (Map.Entry<String, List<OpenGameRuleEditorPacket.GameRuleEntry>> e : byCategory.entrySet()) {
            boolean isVanilla = false;
            for (String v : VANILLA_CATEGORY_ORDER) {
                if (v.equals(e.getKey())) {
                    isVanilla = true;
                    break;
                }
            }
            if (!isVanilla) {
                modded.addAll(e.getValue());
            }
        }

        int yCur = topPos + CONTENT_TOP;
        for (String cat : orderedCats) {
            String headerLabel = Component.translatable(cat).getString();
            allRows.add(new GameRuleRow(cat, headerLabel, true, null, null, yCur, null));
            yCur += ROW_HEIGHT;
            for (OpenGameRuleEditorPacket.GameRuleEntry entry : byCategory.get(cat)) {
                boolean[] state = null;
                AbstractWidget control;
                if (entry.isBoolean()) {
                    state = new boolean[]{Boolean.parseBoolean(entry.currentValue())};
                    final boolean[] st = state;
                    control = Button.builder(
                            Component.literal(st[0] ? "开" : "关"),
                            b -> {
                                st[0] = !st[0];
                                b.setMessage(Component.literal(st[0] ? "开" : "关"));
                            }).bounds(0, 0, 70, 18).build();
                } else {
                    EditBox box = new EditBox(font, 0, 0, 70, 16, Component.literal("数值"));
                    box.setMaxLength(12);
                    box.setFilter(s -> s.isEmpty() || s.equals("-") || s.matches("-?\\d{1,12}"));
                    box.setValue(entry.currentValue());
                    control = box;
                }
                addRenderableWidget(control);
                GameRuleRow row = new GameRuleRow(entry.id(), entry.descriptionId(),
                        false, entry, control, yCur, state);
                allRows.add(row);
                yCur += ROW_HEIGHT;
            }
        }
        if (!modded.isEmpty()) {
            allRows.add(new GameRuleRow(MODDED_GROUP_KEY, "模组", true, null, null, yCur, null));
            yCur += ROW_HEIGHT;
            for (OpenGameRuleEditorPacket.GameRuleEntry entry : modded) {
                boolean[] state = null;
                AbstractWidget control;
                if (entry.isBoolean()) {
                    state = new boolean[]{Boolean.parseBoolean(entry.currentValue())};
                    final boolean[] st = state;
                    control = Button.builder(
                            Component.literal(st[0] ? "开" : "关"),
                            b -> {
                                st[0] = !st[0];
                                b.setMessage(Component.literal(st[0] ? "开" : "关"));
                            }).bounds(0, 0, 70, 18).build();
                } else {
                    EditBox box = new EditBox(font, 0, 0, 70, 16, Component.literal("数值"));
                    box.setMaxLength(12);
                    box.setFilter(s -> s.isEmpty() || s.equals("-") || s.matches("-?\\d{1,12}"));
                    box.setValue(entry.currentValue());
                    control = box;
                }
                addRenderableWidget(control);
                GameRuleRow row = new GameRuleRow(entry.id(), entry.descriptionId(),
                        false, entry, control, yCur, state);
                allRows.add(row);
                yCur += ROW_HEIGHT;
            }
        }

        // ---- 底部按钮 ----
        confirmButton = Button.builder(Component.literal("完成"), b -> submit())
                .bounds(leftPos + 80, topPos + PANEL_HEIGHT - 28, 80, 18)
                .build();
        addRenderableWidget(confirmButton);

        cancelButton = Button.builder(Component.literal("取消"), b -> onClose())
                .bounds(leftPos + 180, topPos + PANEL_HEIGHT - 28, 80, 18)
                .build();
        addRenderableWidget(cancelButton);

        updateVisibility();
    }

    private int maxScroll() {
        return Math.max(0, allRows.size() - VISIBLE_ROWS);
    }

    private void updateVisibility() {
        for (int i = 0; i < allRows.size(); i++) {
            GameRuleRow row = allRows.get(i);
            boolean visible = i >= scroll && i < scroll + VISIBLE_ROWS;
            if (row.control != null) {
                row.control.visible = visible && !row.isHeader;
                if (visible && !row.isHeader) {
                    row.control.setX(leftPos + ROW_LABEL_MAX_WIDTH + 8);
                    row.control.setY(topPos + CONTENT_TOP + (i - scroll) * ROW_HEIGHT + 1);
                }
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (delta > 0 ? 1 : -1)));
        updateVisibility();
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void submit() {
        Map<String, String> changes = new HashMap<>();
        for (GameRuleRow row : allRows) {
            if (row.isHeader || row.control == null || row.entry == null) {
                continue;
            }
            String val;
            if (row.state != null) {
                // 布尔规则：直接从 state 数组读
                val = row.state[0] ? "true" : "false";
            } else if (row.control instanceof EditBox eb) {
                val = eb.getValue().trim();
                if (val.isEmpty()) continue;
            } else {
                continue;
            }
            if (!val.equals(row.entry.currentValue())) {
                changes.put(row.id, val);
            }
        }
        if (!changes.isEmpty()) {
            NetworkHandler.CHANNEL.sendToServer(new SubmitGameRuleEditorPacket(changes));
        }
        onClose();
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 灰色斜面面板
        graphics.fill(leftPos, topPos, leftPos + PANEL_WIDTH, topPos + PANEL_HEIGHT, 0xFF555555);
        graphics.fill(leftPos + 1, topPos + 1, leftPos + PANEL_WIDTH - 1, topPos + PANEL_HEIGHT - 1, 0xFFFFFFFF);
        graphics.fill(leftPos + 2, topPos + 2, leftPos + PANEL_WIDTH - 2, topPos + PANEL_HEIGHT - 2, 0xFFC6C6C6);

        // 标题
        graphics.drawString(font, title, leftPos + 8, topPos + 6, 0x404040, false);

        // 内容行
        for (int i = 0; i < allRows.size(); i++) {
            if (i < scroll || i >= scroll + VISIBLE_ROWS) {
                continue;
            }
            GameRuleRow row = allRows.get(i);
            int yDraw = topPos + CONTENT_TOP + (i - scroll) * ROW_HEIGHT + 5;
            if (row.isHeader) {
                graphics.drawString(font, row.label, leftPos + 12, yDraw, 0xB06000, false);
            } else {
                String label;
                if (row.entry != null) {
                    label = Component.translatable(row.entry.descriptionId()).getString();
                } else {
                    label = row.label;
                }
                if (font.width(label) > ROW_LABEL_MAX_WIDTH - 4) {
                    label = font.plainSubstrByWidth(label, ROW_LABEL_MAX_WIDTH - 8) + "…";
                }
                graphics.drawString(font, label, leftPos + 12, yDraw, 0x404040, false);
            }
        }
        // 分隔线
        graphics.fill(leftPos + 4, topPos + PANEL_HEIGHT - 36,
                leftPos + PANEL_WIDTH - 4, topPos + PANEL_HEIGHT - 35, 0xFF555555);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    // ==================== 数据结构 ====================

    private static class GameRuleRow {
        final String id;
        final String label;
        final boolean isHeader;
        final OpenGameRuleEditorPacket.GameRuleEntry entry;
        final AbstractWidget control;
        final int y;
        /** 布尔规则的当前值引用（null=非布尔规则或 header） */
        final boolean[] state;

        GameRuleRow(String id, String label, boolean isHeader,
                    OpenGameRuleEditorPacket.GameRuleEntry entry, AbstractWidget control, int y,
                    boolean[] state) {
            this.id = id;
            this.label = label;
            this.isHeader = isHeader;
            this.entry = entry;
            this.control = control;
            this.y = y;
            this.state = state;
        }
    }
}
