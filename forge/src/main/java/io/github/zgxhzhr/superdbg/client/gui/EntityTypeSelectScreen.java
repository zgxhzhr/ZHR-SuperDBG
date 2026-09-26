package io.github.zgxhzhr.superdbg.client.gui;

import io.github.zgxhzhr.superdbg.client.entityclear.EntityClearClientState;
import io.github.zgxhzhr.superdbg.entityclear.EntityClearConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 实体类型精细选择：全部已注册实体（含模组实体），中文名 + 注册 id，
 * 支持搜索、按模组（命名空间）筛选、查看当前范围内各类型可清除数量。
 * <p>
 * 名单语义随配置模式变化：白名单模式=勾选的将被清除；
 * 排除模式=勾选的被保留（"全选但不清除指定"）。
 */
public final class EntityTypeSelectScreen extends Screen {

    private static final int PANEL_W = 320;
    private static final int PANEL_H = 240;
    private static final int ROW_H = 13;
    private static final int ROWS_VIEW = 13;
    private static final int LIST_Y = 40;

    private final EntityClearConfig config;
    private final Screen parent;

    private final List<ResourceLocation> all = new ArrayList<>();
    private final List<ResourceLocation> filtered = new ArrayList<>();
    private final List<String> namespaces = new ArrayList<>();
    private EditBox searchBox;
    private CycleButton<String> nsButton;
    private int left;
    private int top;
    private int scroll;
    private String namespace = "全部";

    public EntityTypeSelectScreen(EntityClearConfig config, Screen parent) {
        super(Component.literal("选择实体类型"));
        this.config = config;
        this.parent = parent;
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            all.add(BuiltInRegistries.ENTITY_TYPE.getKey(type));
        }
        all.sort(Comparator.comparing(rl -> nameOf(rl).toLowerCase()));
        for (ResourceLocation rl : all) {
            if (!namespaces.contains(rl.getNamespace())) {
                namespaces.add(rl.getNamespace());
            }
        }
        namespaces.sort(Comparator.naturalOrder());
    }

    private static String nameOf(ResourceLocation id) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        return type == null ? id.toString()
                : Component.translatable(type.getDescriptionId()).getString();
    }

    @Override
    protected void init() {
        super.init();
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;

        searchBox = new EditBox(font, left + 10, top + 18, 198, 16,
                Component.literal("搜索"));
        searchBox.setHint(Component.literal("搜索中文名或 id…"));
        searchBox.setResponder(s -> {
            scroll = 0;
            rebuildFiltered();
        });
        addRenderableWidget(searchBox);

        List<String> nsValues = new ArrayList<>();
        nsValues.add("全部");
        nsValues.addAll(namespaces);
        nsButton = CycleButton.<String>builder(Component::literal)
                .withValues(nsValues)
                .create(left + 214, top + 18, 96, 16, Component.literal("模组"),
                        (b, v) -> {
                            namespace = v;
                            scroll = 0;
                            rebuildFiltered();
                        });
        addRenderableWidget(nsButton);

        int by = top + 212;
        addRenderableWidget(Button.builder(Component.literal("全选"),
                        b -> setAllVisible(true)).bounds(left + 10, by, 50, 16).build());
        addRenderableWidget(Button.builder(Component.literal("全不选"),
                        b -> setAllVisible(false)).bounds(left + 64, by, 56, 16).build());
        addRenderableWidget(Button.builder(Component.literal("刷新数量"),
                        b -> EntityClearClientState.requestScan(config, false))
                .bounds(left + 124, by, 76, 16).build());
        addRenderableWidget(Button.builder(Component.literal("返回"),
                        b -> onClose())
                .bounds(left + 250, by, 60, 16).build());

        rebuildFiltered();
        setInitialFocus(searchBox);
    }

    private void rebuildFiltered() {
        filtered.clear();
        String q = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase();
        for (ResourceLocation id : all) {
            if (!"全部".equals(namespace) && !namespace.equals(id.getNamespace())) {
                continue;
            }
            if (!q.isEmpty()) {
                String cn = nameOf(id).toLowerCase();
                if (!cn.contains(q) && !id.toString().toLowerCase().contains(q)) {
                    continue;
                }
            }
            filtered.add(id);
        }
    }

    private void setAllVisible(boolean add) {
        for (ResourceLocation id : filtered) {
            if (add) {
                config.types.add(id.toString());
            } else {
                config.types.remove(id.toString());
            }
        }
    }

    /** 扫描结果后台到达（行内数量每帧从共享状态读取，无需重建） */
    public void onCountsUpdated() {
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int max = Math.max(0, filtered.size() - ROWS_VIEW);
        scroll = Math.max(0, Math.min(max, scroll - (delta > 0 ? 3 : -3)));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        int x0 = left + 10;
        int w = PANEL_W - 20;
        if (mouseX >= x0 && mouseX < x0 + w
                && mouseY >= top + LIST_Y && mouseY < top + LIST_Y + ROWS_VIEW * ROW_H) {
            int row = (int) ((mouseY - (top + LIST_Y)) / ROW_H) + scroll;
            if (row >= 0 && row < filtered.size()) {
                String id = filtered.get(row).toString();
                if (!config.types.remove(id)) {
                    config.types.add(id);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.fill(left, top, left + PANEL_W, top + PANEL_H, 0xFF555555);
        g.fill(left + 1, top + 1, left + PANEL_W - 1, top + PANEL_H - 1, 0xFFFFFFFF);
        g.fill(left + 2, top + 2, left + PANEL_W - 2, top + PANEL_H - 2, 0xFFC6C6C6);
        super.render(g, mouseX, mouseY, partialTick);

        boolean blacklist = config.mode == EntityClearConfig.MODE_BLACKLIST;
        g.drawString(font, Component.literal(
                        blacklist ? "精细选择：勾选 = 排除（保留该类型）"
                                  : "精细选择：勾选 = 清除目标"),
                left + 10, top + 6, 0x404040, false);

        // 列表裁剪区背景
        g.enableScissor(left + 4, top + LIST_Y - 2, left + PANEL_W - 4,
                top + LIST_Y + ROWS_VIEW * ROW_H + 2);
        var counts = EntityClearClientState.counts();
        for (int i = 0; i < ROWS_VIEW; i++) {
            int idx = scroll + i;
            if (idx >= filtered.size()) {
                break;
            }
            ResourceLocation id = filtered.get(idx);
            int y = top + LIST_Y + i * ROW_H;
            boolean selected = config.types.contains(id.toString());
            boolean hover = mouseX >= left + 10 && mouseX < left + PANEL_W - 10
                    && mouseY >= y && mouseY < y + ROW_H;
            if (hover) {
                g.fill(left + 4, y, left + PANEL_W - 4, y + ROW_H, 0x55FFFF00);
            }
            String mark = (blacklist ? (selected ? "§a[保留]" : "§7[清除]")
                                     : (selected ? "§c[清除]" : "§7[保留]"));
            g.drawString(font, Component.literal(mark), left + 8, y + 2, 0x404040, false);
            g.drawString(font, Component.literal("§" + (selected ? "c" : "0") + nameOf(id)),
                    left + 46, y + 2, 0x000000, false);
            Integer n = counts.get(id);
            if (n != null && n > 0) {
                String s = "×" + n;
                g.drawString(font, Component.literal("§2" + s),
                        left + PANEL_W - 12 - font.width(s), y + 2, 0, false);
            }
            g.drawString(font, Component.literal("§8" + id),
                    left + 160, y + 2, 0, false);
        }
        g.disableScissor();

        g.drawString(font, Component.literal(
                        "已勾选 " + config.types.size() + " 种 / 列表 " + filtered.size()
                                + " 种" + (filtered.size() > ROWS_VIEW ? "（滚轮翻页）" : "")),
                left + 10, top + PANEL_H - 12, 0x707070, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
