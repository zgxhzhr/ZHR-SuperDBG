package io.github.zgxhzhr.superdbg.client.gui;

import io.github.zgxhzhr.superdbg.client.entityclear.EntityClearClientState;
import io.github.zgxhzhr.superdbg.entityclear.EntityClearCategories;
import io.github.zgxhzhr.superdbg.entityclear.EntityClearConfig;
import io.github.zgxhzhr.superdbg.entityclear.EntityClearPresets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;

import java.util.List;

/**
 * 实体清除器主界面：范围 / 目标选取（大类 + 精细类型，支持"全选但排除指定"）/
 * 保护名单 / 清除方式 / 预览与执行 / 常用方案存取。全部动作服务端复检创造模式。
 */
public final class EntityClearScreen extends Screen {

    private static final int PANEL_W = 320;
    private static final int PANEL_H = 282;

    /** 本次会话内保留当前编辑配置，重开界面不丢 */
    private static EntityClearConfig working;

    private int left;
    private int top;
    private EditBox radiusBox;
    private EditBox presetBox;
    private CycleButton<String> presetCycle;

    public EntityClearScreen() {
        super(Component.literal("实体清除器"));
        if (working == null) {
            working = new EntityClearConfig();
        }
    }

    /** 当前配置（类型选择子界面直接编辑同一份） */
    public EntityClearConfig config() {
        return working;
    }

    @Override
    protected void init() {
        super.init();
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
        int x = left + 10;

        // ---- 范围 + 方式 ----
        addRenderableWidget(CycleButton
                .<Integer>builder(v -> Component.literal(switch (v) {
                    case EntityClearConfig.SCOPE_DIMENSION -> "范围：当前维度全部";
                    case EntityClearConfig.SCOPE_ALL_DIMENSIONS -> "范围：所有已加载维度";
                    default -> "范围：以我为中心";
                }))
                .withValues(EntityClearConfig.SCOPE_AROUND,
                        EntityClearConfig.SCOPE_DIMENSION,
                        EntityClearConfig.SCOPE_ALL_DIMENSIONS)
                .withInitialValue(working.scope)
                .create(x, top + 18, 120, 18, Component.literal("范围"),
                        (b, v) -> working.scope = v));

        radiusBox = new EditBox(font, left + 136, top + 19, 46, 16,
                Component.literal("半径"));
        radiusBox.setMaxLength(4);
        radiusBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,4}"));
        radiusBox.setValue(String.valueOf(working.radius));
        radiusBox.setResponder(s -> {
            try {
                working.radius = s.isEmpty() ? 0 : Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        });
        addRenderableWidget(radiusBox);

        addRenderableWidget(CycleButton
                .<Integer>builder(v -> Component.literal(
                        v == EntityClearConfig.METHOD_DISCARD ? "方式：直接抹除" : "方式：正常击杀"))
                .withValues(EntityClearConfig.METHOD_KILL, EntityClearConfig.METHOD_DISCARD)
                .withInitialValue(working.method)
                .create(left + 196, top + 18, 114, 18, Component.literal("方式"),
                        (b, v) -> working.method = v));

        // ---- 目标选取模式 ----
        addRenderableWidget(CycleButton
                .<Integer>builder(v -> Component.literal(
                        v == EntityClearConfig.MODE_BLACKLIST
                                ? "目标：清除全部，但不清除下面/精细名单中勾选的类型"
                                : "目标：只清除勾选的类型"))
                .withValues(EntityClearConfig.MODE_WHITELIST, EntityClearConfig.MODE_BLACKLIST)
                .withInitialValue(working.mode)
                .create(x, top + 40, 300, 18, Component.literal("模式"),
                        (b, v) -> {
                            working.mode = v;
                            rebuildWidgets();
                        }));

        // ---- 大类快捷勾选（2列×5行）----
        EntityClearCategories[] cats = EntityClearCategories.values();
        for (int i = 0; i < cats.length; i++) {
            EntityClearCategories cat = cats[i];
            int col = i % 2;
            int row = i / 2;
            int bx = x + col * 152;
            int by = top + 70 + row * 16;
            addRenderableWidget(Button.builder(categoryLabel(cat), b -> {
                toggleCategory(cat);
                rebuildWidgets();
            }).bounds(bx, by, 148, 14).build());
        }

        // ---- 精细类型选择 ----
        addRenderableWidget(Button.builder(
                        Component.literal("按实体类型精细选择（支持搜索/模组筛选/查看数量） ▸"),
                        b -> minecraft.setScreen(new EntityTypeSelectScreen(working, this)))
                .bounds(x, top + 152, 300, 18).build());

        // ---- 保护名单（3列×2行）----
        addProtectButton(x + 0 * 100, top + 184, EntityClearConfig.PROTECT_PLAYERS, "玩家");
        addProtectButton(x + 1 * 100, top + 184, EntityClearConfig.PROTECT_TAMED, "已驯服");
        addProtectButton(x + 2 * 100, top + 184, EntityClearConfig.PROTECT_NAMED, "名字牌");
        addProtectButton(x + 0 * 100, top + 200, EntityClearConfig.PROTECT_BABY, "幼年");
        addProtectButton(x + 1 * 100, top + 200, EntityClearConfig.PROTECT_RIDING, "骑乘中");
        addProtectButton(x + 2 * 100, top + 200, EntityClearConfig.PROTECT_GUARDED, "防移除");

        // ---- 预览 / 执行 ----
        addRenderableWidget(Button.builder(Component.literal("🔍 预览将要清除"),
                        b -> {
                            syncRadius();
                            EntityClearClientState.requestScan(working, true);
                        })
                .bounds(x, top + 222, 146, 20).build());
        addRenderableWidget(Button.builder(Component.literal("⚡ 立即清除"),
                        b -> {
                            syncRadius();
                            io.github.zgxhzhr.superdbg.network.NetworkHandler.CHANNEL.sendToServer(
                                    new io.github.zgxhzhr.superdbg.network.EntityClearPacket(true, working.copy()));
                        })
                .bounds(x + 154, top + 222, 146, 20).build());

        // ---- 常用方案 ----
        presetBox = new EditBox(font, x, top + 248, 92, 16, Component.literal("方案名称"));
        presetBox.setMaxLength(24);
        presetBox.setHint(Component.literal("方案名称"));
        addRenderableWidget(presetBox);
        addRenderableWidget(Button.builder(Component.literal("保存"), b -> {
            String name = presetBox.getValue().trim();
            if (!name.isEmpty()) {
                EntityClearPresets.put(name, working);
                rebuildWidgets();
            }
        }).bounds(x + 96, top + 248, 40, 16).build());

        List<String> names = EntityClearPresets.names();
        presetCycle = CycleButton.<String>builder(Component::literal)
                .withValues(names.isEmpty() ? List.of("（无方案）") : names)
                .create(x + 140, top + 248, 100, 16, Component.literal("方案"));
        addRenderableWidget(presetCycle);
        addRenderableWidget(Button.builder(Component.literal("载入"), b -> {
            String name = presetCycle.getValue();
            EntityClearConfig loaded = EntityClearPresets.get(name);
            if (loaded != null) {
                copyInto(loaded, working);
                rebuildWidgets();
            }
        }).bounds(x + 244, top + 248, 28, 16).build());
        addRenderableWidget(Button.builder(Component.literal("删除"), b -> {
            String name = presetCycle.getValue();
            if (EntityClearPresets.get(name) != null) {
                EntityClearPresets.delete(name);
                rebuildWidgets();
            }
        }).bounds(x + 276, top + 248, 34, 16).build());

        // ---- 完成 ----
        addRenderableWidget(Button.builder(Component.literal("完成"), b -> onClose())
                .bounds(left + (PANEL_W - 60) / 2, top + PANEL_H - 20, 60, 16).build());

        setInitialFocus(radiusBox);
        // 打开时后台刷一次各类型数量（不弹预览）
        EntityClearClientState.requestScan(working, false);
    }

    /** 扫描结果后台到达后刷新（当前无动态文字依赖，留作计数按钮扩展点） */
    public void onCountsUpdated() {
    }

    private void syncRadius() {
        try {
            working.radius = radiusBox == null || radiusBox.getValue().isEmpty()
                    ? 0 : Integer.parseInt(radiusBox.getValue());
        } catch (NumberFormatException ignored) {
        }
    }

    private void addProtectButton(int x, int y, int bit, String name) {
        boolean on = working.isProtected(bit);
        addRenderableWidget(Button.builder(
                        Component.literal((on ? "§a[√] " : "§7[ ] ") + name), b -> {
                    working.setProtected(bit, !working.isProtected(bit));
                    rebuildWidgets();
                }).bounds(x, y, 96, 14).build());
    }

    private Component categoryLabel(EntityClearCategories cat) {
        int state = categoryState(cat);
        String mark = switch (state) {
            case 1 -> "§a[√]";
            case -1 -> "§e[-]";
            default -> "§7[ ]";
        };
        String suffix = working.mode == EntityClearConfig.MODE_BLACKLIST ? "（排除）" : "";
        return Component.literal(mark + " " + cat.displayName + suffix);
    }

    /** @return 1=该类全部在名单；-1=部分在名单；0=全不在 */
    private int categoryState(EntityClearCategories cat) {
        int total = 0;
        int selected = 0;
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (EntityClearCategories.of(type) != cat) {
                continue;
            }
            total++;
            if (working.types.contains(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString())) {
                selected++;
            }
        }
        if (total == 0) {
            return 0;
        }
        return selected == 0 ? 0 : selected == total ? 1 : -1;
    }

    private void toggleCategory(EntityClearCategories cat) {
        boolean allIn = categoryState(cat) == 1;
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            if (EntityClearCategories.of(type) != cat) {
                continue;
            }
            if (allIn) {
                working.types.remove(id);
            } else {
                working.types.add(id);
            }
        }
    }

    private static void copyInto(EntityClearConfig src, EntityClearConfig dst) {
        dst.scope = src.scope;
        dst.radius = src.radius;
        dst.mode = src.mode;
        dst.method = src.method;
        dst.protectBits = src.protectBits;
        dst.types.clear();
        dst.types.addAll(src.types);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(left, top, left + PANEL_W, top + PANEL_H, 0xFF555555);
        graphics.fill(left + 1, top + 1, left + PANEL_W - 1, top + PANEL_H - 1, 0xFFFFFFFF);
        graphics.fill(left + 2, top + 2, left + PANEL_W - 2, top + PANEL_H - 2, 0xFFC6C6C6);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, Component.literal("实体清除器"), left + 10, top + 6, 0x404040, false);
        graphics.drawString(font, Component.literal("格"), left + 186, top + 23, 0x404040, false);
        graphics.drawString(font,
                        Component.literal(working.mode == EntityClearConfig.MODE_BLACKLIST
                                ? "勾选 = 加入排除名单（该类型保留）"
                                : "勾选 = 清除目标（可按大类或逐种选择）"),
                left + 10, top + 61, 0x404040, false);
        graphics.drawString(font, Component.literal("保护名单（勾选的保留不清；本人始终保护）"),
                left + 10, top + 174, 0x404040, false);
        graphics.drawString(font, Component.literal("常用方案（本地保存）"),
                left + 244, top + 238, 0x707070, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
