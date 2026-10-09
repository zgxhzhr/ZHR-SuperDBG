package io.github.zgxhzhr.superdbg.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * 药水效果选择器：搜索框 + 滚动图标网格，供药水编辑器「添加效果」选用。
 * <p>
 * 列出全部已注册药水效果（含模组效果），可按本地化译名或注册 id 搜索。
 * 唯一入口 {@link #open(Consumer)} 回传选中的 {@link MobEffect}。
 * Esc 取消时回到打开前的屏幕。
 */
public final class MobEffectPickerScreen extends Screen {

    private static final int PANEL_W = 200;
    private static final int PANEL_H = 204;
    private static final int COLS = 9;
    private static final int ROWS_VIEW = 7;
    private static final int CELL = 20;
    private static final int GRID_X = 10;
    private static final int GRID_Y = 40;

    /** 选中回调（仅客户端使用，打开选择器时设置） */
    private static Consumer<MobEffect> callback;
    /** 取消（Esc）时返回的屏幕（打开前的屏幕实例） */
    private static Screen parentScreen;

    /** 网格中的一个可选项：效果类型 + 搜索文本（注册 id 与本地化译名） */
    private record Entry(MobEffect effect, String search) {
    }

    /** 全部注册效果（含模组效果），跨界面实例只构建一次 */
    private static final List<Entry> ALL_EFFECTS = new ArrayList<>();

    private final List<Entry> filtered = new ArrayList<>();
    private EditBox searchBox;
    private int left;
    private int top;
    private int rowScroll;

    private MobEffectPickerScreen() {
        super(Component.translatable("superdbg.gui.pick_effect"));
    }

    /**
     * 打开选择器，回调收到选中的药水效果（回调内自行切回原屏幕）。
     *
     * @param onPicked 选中效果后的回调
     */
    public static void open(Consumer<MobEffect> onPicked) {
        Minecraft mc = Minecraft.getInstance();
        callback = onPicked;
        parentScreen = mc.screen;
        mc.setScreen(new MobEffectPickerScreen());
    }

    @Override
    protected void init() {
        super.init();
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;

        if (ALL_EFFECTS.isEmpty()) {
            for (MobEffect effect : BuiltInRegistries.MOB_EFFECT) {
                ResourceLocation id = BuiltInRegistries.MOB_EFFECT.getKey(effect);
                String idPart = id == null ? "" : id.toString().toLowerCase();
                // 必须并入本地化译名，否则中文/非 id 搜索词永远命不中
                String name = Component.translatable(effect.getDescriptionId())
                        .getString().toLowerCase();
                ALL_EFFECTS.add(new Entry(effect, idPart + " " + name));
            }
            ALL_EFFECTS.sort(Comparator.comparing(Entry::search));
        }

        searchBox = new EditBox(font, left + GRID_X, top + 18, PANEL_W - 2 * GRID_X, 16,
                Component.translatable("superdbg.gui.pick_effect"));
        searchBox.setHint(Component.translatable("superdbg.gui.search_effect"));
        searchBox.setResponder(s -> {
            rowScroll = 0;
            rebuildFiltered();
        });
        addRenderableWidget(searchBox);
        rebuildFiltered();
        setInitialFocus(searchBox);
    }

    private void rebuildFiltered() {
        filtered.clear();
        String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase();
        for (Entry entry : ALL_EFFECTS) {
            if (query.isEmpty() || entry.search().contains(query)) {
                filtered.add(entry);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxRowScroll = Math.max(0, (filtered.size() + COLS - 1) / COLS - ROWS_VIEW);
        rowScroll = Math.max(0, Math.min(maxRowScroll, rowScroll - (delta > 0 ? 1 : -1)));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        int gx = left + GRID_X;
        int gy = top + GRID_Y;
        int gridW = COLS * CELL;
        int gridH = ROWS_VIEW * CELL;
        if (mouseX >= gx && mouseX < gx + gridW && mouseY >= gy && mouseY < gy + gridH) {
            int col = (int) ((mouseX - gx) / CELL);
            int row = (int) ((mouseY - gy) / CELL) + rowScroll;
            int index = row * COLS + col;
            if (index >= 0 && index < filtered.size()) {
                Consumer<MobEffect> cb = callback;
                MobEffect picked = filtered.get(index).effect();
                // 先清静态引用再回调，避免回调内再次打开选择器时状态错乱
                callback = null;
                parentScreen = null;
                if (cb != null) {
                    cb.accept(picked);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void onClose() {
        callback = null;
        Screen back = parentScreen;
        parentScreen = null;
        Minecraft.getInstance().setScreen(back);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(left, top, left + PANEL_W, top + PANEL_H, 0xFF555555);
        graphics.fill(left + 1, top + 1, left + PANEL_W - 1, top + PANEL_H - 1, 0xFFFFFFFF);
        graphics.fill(left + 2, top + 2, left + PANEL_W - 2, top + PANEL_H - 2, 0xFFC6C6C6);

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, Component.translatable("superdbg.gui.pick_effect_hint"),
                left + GRID_X, top + 6, 0x404040, false);

        int startIndex = rowScroll * COLS;
        MobEffect hovered = null;
        for (int i = 0; i < COLS * ROWS_VIEW; i++) {
            int index = startIndex + i;
            if (index >= filtered.size()) {
                break;
            }
            int col = i % COLS;
            int row = i / COLS;
            int x = left + GRID_X + col * CELL + 2;
            int y = top + GRID_Y + row * CELL + 2;
            MobEffect effect = filtered.get(index).effect();
            TextureAtlasSprite icon = minecraft.getMobEffectTextures().get(effect);
            graphics.blit(x, y, 0, 18, 18, icon);
            if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) {
                hovered = effect;
            }
        }

        int total = filtered.size();
        Component countLine = Component.translatable("superdbg.gui.picker_count", total);
        if (total > COLS * ROWS_VIEW) {
            countLine = countLine.copy().append(Component.translatable("superdbg.gui.picker_scroll"));
        }
        graphics.drawString(font, countLine, left + GRID_X, top + PANEL_H - 14, 0x707070, false);

        if (hovered != null) {
            graphics.renderTooltip(font,
                    Component.translatable(hovered.getDescriptionId()), mouseX, mouseY);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
