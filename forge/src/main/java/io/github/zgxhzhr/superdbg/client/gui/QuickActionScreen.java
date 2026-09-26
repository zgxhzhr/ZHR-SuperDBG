package io.github.zgxhzhr.superdbg.client.gui;

import io.github.zgxhzhr.superdbg.network.NetworkHandler;
import io.github.zgxhzhr.superdbg.network.QuickActionPacket;
import io.github.zgxhzhr.superdbg.network.RequestDimensionsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 快捷指令面板（创造模式手持调试斧 Shift+右键空气打开）。
 * <ul>
 *   <li>跨维度传送：维度列表由服务端 {@code levelKeys()} 下发（含静态注册的模组维度，
 *       玩家没去过也能选），可指定坐标；</li>
 *   <li>清除当前维度全部已加载非玩家生物（走正常死亡流程）；</li>
 *   <li>杀死全部玩家（含自己）；</li>
 *   <li>快速调时间：清晨/正午/黄昏/午夜 + 自定义 tick。</li>
 * </ul>
 * 纯客户端界面，所有动作经 {@link QuickActionPacket} 由服务端执行并复检创造模式。
 */
public final class QuickActionScreen extends Screen {

    private static final int PANEL_W = 260;
    private static final int PANEL_H = 236;

    /** 服务端最近一次下发的全量维度列表（全客户端共享缓存，可能为 null=尚未收到） */
    private static List<ResourceLocation> serverDimensions;

    /** 由 S2C 包调用：缓存服务端维度列表 */
    public static void setServerDimensions(List<ResourceLocation> dimensions) {
        serverDimensions = new ArrayList<>(dimensions);
    }

    private int left;
    private int top;

    private CycleButton<ResourceLocation> dimButton;
    private EditBox xBox;
    private EditBox yBox;
    private EditBox zBox;
    private EditBox timeBox;

    private QuickActionScreen() {
        super(Component.literal("快捷指令"));
    }

    /** 由交互事件调用：打开面板并向服务端请求全量维度列表（创造/调试斧条件已在事件处校验） */
    public static void open() {
        NetworkHandler.CHANNEL.sendToServer(new RequestDimensionsPacket());
        Minecraft.getInstance().setScreen(new QuickActionScreen());
    }

    @Override
    protected void init() {
        super.init();
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;

        dimButton = buildDimButton(resolveDimensions(), currentDimension());

        int y = top + 54;
        xBox = coordBox(left + 10, y, String.valueOf(blockCoordX()));
        yBox = coordBox(left + 94, y, String.valueOf(blockCoordY()));
        zBox = coordBox(left + 178, y, String.valueOf(blockCoordZ()));
        addRenderableWidget(xBox);
        addRenderableWidget(yBox);
        addRenderableWidget(zBox);

        y = top + 76;
        addRenderableWidget(Button.builder(Component.literal("填入当前坐标"), b -> fillCurrentCoords())
                .bounds(left + 10, y, 118, 18).build());
        addRenderableWidget(Button.builder(Component.literal("传送"), b -> doTeleport())
                .bounds(left + 132, y, 118, 18).build());

        // ---- 实体清除器 ----
        y = top + 112;
        addRenderableWidget(Button.builder(Component.literal("实体清除器（可选范围/类型/保护，支持预览）"),
                        b -> minecraft.setScreen(new EntityClearScreen()))
                .bounds(left + 10, y, 240, 20).build());

        // ---- 时间 ----
        y = top + 172;
        addTimeButton(left + 10, y, 56, "清晨", 1000);
        addTimeButton(left + 72, y, 56, "正午", 6000);
        addTimeButton(left + 134, y, 56, "黄昏", 12000);
        addTimeButton(left + 196, y, 54, "午夜", 18000);

        y = top + 194;
        timeBox = new EditBox(font, left + 10, y, 118, 18, Component.literal("时刻 tick"));
        timeBox.setMaxLength(7);
        timeBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,7}"));
        timeBox.setValue("6000");
        addRenderableWidget(timeBox);
        addRenderableWidget(Button.builder(Component.literal("应用时刻"), b -> {
            int t = parseIntOr(timeBox.getValue(), -1);
            if (t >= 0) {
                NetworkHandler.CHANNEL.sendToServer(new QuickActionPacket(
                        QuickActionPacket.ACTION_TIME, null, 0, 0, 0, t));
            }
        }).bounds(left + 132, y, 118, 18).build());

        // ---- 关闭 ----
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(left + 100, top + PANEL_H - 24, 60, 18).build());
    }

    /**
     * S2C 维度列表到达：用服务端数据重建维度按钮（保留当前选择），
     * 面板已打开时即时生效，不必重开。
     */
    public void updateDimensions(List<ResourceLocation> dimensions) {
        ResourceLocation keep = dimButton != null ? dimButton.getValue() : currentDimension();
        removeWidget(dimButton);
        List<ResourceLocation> merged = new ArrayList<>(dimensions);
        ResourceLocation current = currentDimension();
        if (!merged.contains(current)) {
            merged.add(0, current);
        }
        dimButton = buildDimButton(merged, merged.contains(keep) ? keep : current);
    }

    private CycleButton<ResourceLocation> buildDimButton(List<ResourceLocation> dimensions,
                                                         ResourceLocation selected) {
        CycleButton<ResourceLocation> button =
                CycleButton.<ResourceLocation>builder(rl -> Component.literal(rl.toString()))
                        .withValues(dimensions.toArray(new ResourceLocation[0]))
                        .withInitialValue(selected)
                        .create(left + 10, top + 22, 240, 18, Component.literal("目标维度"));
        addRenderableWidget(button);
        return button;
    }

    /** 优先用服务端下发列表；未收到前用客户端本地枚举兜底 */
    private List<ResourceLocation> resolveDimensions() {
        List<ResourceLocation> out;
        if (serverDimensions != null) {
            out = new ArrayList<>(serverDimensions);
        } else {
            out = collectDimensions();
        }
        ResourceLocation current = currentDimension();
        if (!out.contains(current)) {
            out.add(0, current);
        }
        return out;
    }

    private ResourceLocation currentDimension() {
        return minecraft.level != null
                ? minecraft.level.dimension().location()
                : Level.OVERWORLD.location();
    }

    private void addTimeButton(int x, int y, int w, String label, int time) {
        addRenderableWidget(Button.builder(Component.literal(label),
                        b -> NetworkHandler.CHANNEL.sendToServer(new QuickActionPacket(
                                QuickActionPacket.ACTION_TIME, null, 0, 0, 0, time)))
                .bounds(x, y, w, 18).build());
    }

    private EditBox coordBox(int x, int y, String value) {
        EditBox box = new EditBox(font, x, y, 72, 18, Component.literal("坐标"));
        box.setMaxLength(10);
        box.setFilter(s -> s.isEmpty() || s.equals("-") || s.matches("-?\\d{1,7}(\\.\\d{0,2})?"));
        box.setValue(value);
        return box;
    }

    private void fillCurrentCoords() {
        xBox.setValue(String.valueOf(blockCoordX()));
        yBox.setValue(String.valueOf(blockCoordY()));
        zBox.setValue(String.valueOf(blockCoordZ()));
    }

    private void doTeleport() {
        double x = parseDoubleOr(xBox.getValue(), blockCoordX());
        double y = parseDoubleOr(yBox.getValue(), blockCoordY());
        double z = parseDoubleOr(zBox.getValue(), blockCoordZ());
        ResourceLocation dim = dimButton.getValue();
        NetworkHandler.CHANNEL.sendToServer(new QuickActionPacket(
                QuickActionPacket.ACTION_TP, dim, x, y, z, 0));
    }

    private int blockCoordX() {
        return minecraft.player == null ? 0 : minecraft.player.blockPosition().getX();
    }

    private int blockCoordY() {
        return minecraft.player == null ? 64 : minecraft.player.blockPosition().getY();
    }

    private int blockCoordZ() {
        return minecraft.player == null ? 0 : minecraft.player.blockPosition().getZ();
    }

    /** 本地兜底：尝试枚举客户端已知维度注册表（客户端 dimension 注册表通常不同步，
     *  只能拿到当前维度，真正的全量列表以服务端下发为准） */
    private List<ResourceLocation> collectDimensions() {
        List<ResourceLocation> out = new ArrayList<>();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                mc.getConnection().registryAccess()
                        .registryOrThrow(Registries.DIMENSION)
                        .registryKeySet().stream()
                        .map(ResourceKey::location)
                        .sorted(Comparator.comparing(ResourceLocation::toString))
                        .forEach(out::add);
            }
        } catch (Exception ignored) {
        }
        if (out.isEmpty() && minecraft.level != null) {
            out.add(minecraft.level.dimension().location());
        }
        return out;
    }

    private static int parseIntOr(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double parseDoubleOr(String raw, double fallback) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        // 灰色斜面面板（与实体编辑器同款）
        graphics.fill(left, top, left + PANEL_W, top + PANEL_H, 0xFF555555);
        graphics.fill(left + 1, top + 1, left + PANEL_W - 1, top + PANEL_H - 1, 0xFFFFFFFF);
        graphics.fill(left + 2, top + 2, left + PANEL_W - 2, top + PANEL_H - 2, 0xFFC6C6C6);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, Component.literal("快捷指令（调试斧 Shift+右键空气）"),
                left + 10, top + 8, 0x404040, false);
        graphics.drawString(font, Component.literal("目标维度"), left + 10, top + 14, 0x404040, false);

        // 坐标框（top+54）上方的 X/Y/Z 小标签
        graphics.drawString(font, Component.literal("X"), left + 10, top + 44, 0x707070, false);
        graphics.drawString(font, Component.literal("Y"), left + 94, top + 44, 0x707070, false);
        graphics.drawString(font, Component.literal("Z"), left + 178, top + 44, 0x707070, false);

        graphics.fill(left + 2, top + 98, left + PANEL_W - 2, top + 99, 0xFF999999);
        graphics.drawString(font, Component.literal("实体清除"),
                left + 10, top + 102, 0x404040, false);

        graphics.fill(left + 2, top + 158, left + PANEL_W - 2, top + 159, 0xFF999999);
        graphics.drawString(font, Component.literal("时间（tick：清晨1000 / 正午6000 / 黄昏12000 / 午夜18000）"),
                left + 10, top + 162, 0x404040, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
