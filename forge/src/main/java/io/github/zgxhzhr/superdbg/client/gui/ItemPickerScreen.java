package io.github.zgxhzhr.superdbg.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * 全物品选择器：搜索框 + 滚动图标网格，供交易/掉落/获取/赠礼等所有选物品场景共用。
 * <p>
 * 唯一入口 {@link #open(Consumer)} 返回完整 {@link ItemStack}（保留 NBT）。
 * 除全部注册物品外，输入附魔名（中文译名如"锋利"/"海之眷顾"，或英文 id
 * 如"sharpness"）时还能搜到每一种附魔、每一个等级的附魔书；无搜索词时不展示
 * 这些虚拟条目，避免与普通物品混排。
 * <p>
 * Esc 取消时回到打开前的屏幕。
 */
public final class ItemPickerScreen extends Screen {

    private static final int PANEL_W = 200;
    private static final int PANEL_H = 204;
    private static final int COLS = 9;
    private static final int ROWS_VIEW = 7;
    private static final int CELL = 20;
    private static final int GRID_X = 10;
    private static final int GRID_Y = 40;

    /** 选中回调（仅客户端使用，打开选择器时设置），回传完整物品堆 */
    private static Consumer<ItemStack> callback;
    /** 取消（Esc）时返回的屏幕（打开前的屏幕实例） */
    private static Screen parentScreen;

    /** 网格中的一个可选项：显示用物品堆 + 附加搜索文本（如附魔注册 id 与译名） */
    private record Entry(ItemStack stack, String extraSearch) {
    }

    /** 全部注册物品（不含虚拟附魔书），跨界面实例只构建一次 */
    private static final List<Entry> ALL_ITEMS = new ArrayList<>();
    /** 虚拟附魔书（每个注册附魔 × 每个等级），首次搜索时懒加载 */
    private static volatile List<Entry> enchantedBooks;

    private final List<Entry> filtered = new ArrayList<>();
    private EditBox searchBox;
    private int left;
    private int top;
    private int rowScroll;

    private ItemPickerScreen() {
        super(Component.literal("选择物品"));
    }

    /**
     * 打开选择器。所有选物品场景共用此入口，回调收到的物品堆保留完整 NBT
     * （如附魔书的 StoredEnchantments），调用方自行决定取用 item 还是整个 stack。
     *
     * @param onPicked 选中物品堆后的回调（回调内自行切回原屏幕）
     */
    public static void open(Consumer<ItemStack> onPicked) {
        Minecraft mc = Minecraft.getInstance();
        callback = onPicked;
        parentScreen = mc.screen;
        mc.setScreen(new ItemPickerScreen());
    }

    @Override
    protected void init() {
        super.init();
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;

        if (ALL_ITEMS.isEmpty()) {
            for (Item item : BuiltInRegistries.ITEM) {
                if (item != Items.AIR) {
                    ALL_ITEMS.add(new Entry(new ItemStack(item), ""));
                }
            }
            ALL_ITEMS.sort(Comparator.comparing(
                    e -> e.stack().getHoverName().getString()));
        }

        searchBox = new EditBox(font, left + GRID_X, top + 18, PANEL_W - 2 * GRID_X, 16,
                Component.literal("搜索物品"));
        searchBox.setHint(Component.literal("搜索物品名、id 或附魔…"));
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
        if (query.isEmpty()) {
            // 无搜索词：只列真实物品，虚拟附魔书不参与混排
            filtered.addAll(ALL_ITEMS);
            return;
        }
        for (Entry entry : ALL_ITEMS) {
            if (matches(entry, query)) {
                filtered.add(entry);
            }
        }
        for (Entry book : enchantedBooks()) {
            if (matches(book, query)) {
                filtered.add(book);
            }
        }
        // 虚拟附魔书 hoverName 全是"附魔书"，次级按附魔名+等级排序，
        // 使同一附魔的 I~V 连续聚合（悬停 tooltip 显示具体附魔与等级）
        filtered.sort(Comparator.comparing((Entry e) -> e.stack().getHoverName().getString())
                .thenComparing(e -> e.extraSearch() == null ? "" : e.extraSearch()));
    }

    /** 名称/物品 id/附加文本（附魔 id 与本地化译名、含等级）任一包含查询词即命中 */
    private static boolean matches(Entry entry, String query) {
        String name = entry.stack().getHoverName().getString().toLowerCase();
        String id = BuiltInRegistries.ITEM.getKey(entry.stack().getItem()).toString().toLowerCase();
        return name.contains(query) || id.contains(query)
                || (entry.extraSearch() != null && entry.extraSearch().contains(query));
    }

    /** 懒加载全部附魔书虚拟条目（含模组附魔；物品选择器首次带词搜索时构建一次） */
    private static List<Entry> enchantedBooks() {
        List<Entry> cached = enchantedBooks;
        if (cached != null) {
            return cached;
        }
        synchronized (ItemPickerScreen.class) {
            if (enchantedBooks == null) {
                List<Entry> books = new ArrayList<>();
                for (Enchantment enchantment : BuiltInRegistries.ENCHANTMENT) {
                    ResourceLocation enchId = BuiltInRegistries.ENCHANTMENT.getKey(enchantment);
                    String idPart = enchId == null ? "" : enchId.toString().toLowerCase();
                    for (int lvl = 1; lvl <= enchantment.getMaxLevel(); lvl++) {
                        EnchantmentInstance instance = new EnchantmentInstance(enchantment, lvl);
                        ItemStack book = EnchantedBookItem.createForEnchantment(instance);
                        // 附魔书自身 hoverName 只有"附魔书"，必须把附魔的本地化译名
                        // （如"海之眷顾 III"，语言随客户端）并入搜索文本，
                        // 否则中文/非 id 搜索词永远命不中虚拟书
                        String displayName = enchantment.getFullname(lvl)
                                .getString().toLowerCase();
                        books.add(new Entry(book, idPart + " " + displayName));
                    }
                }
                enchantedBooks = books;
            }
            return enchantedBooks;
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
                Consumer<ItemStack> cb = callback;
                ItemStack picked = filtered.get(index).stack().copy();
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

        graphics.drawString(font, Component.literal("选择物品（点击选中，Esc 返回）"),
                left + GRID_X, top + 6, 0x404040, false);

        int startIndex = rowScroll * COLS;
        ItemStack hovered = null;
        int hoverX = 0;
        int hoverY = 0;
        for (int i = 0; i < COLS * ROWS_VIEW; i++) {
            int index = startIndex + i;
            if (index >= filtered.size()) {
                break;
            }
            int col = i % COLS;
            int row = i / COLS;
            int x = left + GRID_X + col * CELL + 2;
            int y = top + GRID_Y + row * CELL + 2;
            ItemStack stack = filtered.get(index).stack();
            graphics.renderItem(stack, x, y);
            graphics.renderItemDecorations(font, stack, x, y);
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                hovered = stack;
                hoverX = mouseX;
                hoverY = mouseY;
            }
        }

        int total = filtered.size();
        graphics.drawString(font,
                Component.literal("共 " + total + " 项" + (total > COLS * ROWS_VIEW ? "（滚轮翻页）" : "")),
                left + GRID_X, top + PANEL_H - 14, 0x707070, false);

        if (hovered != null) {
            graphics.renderTooltip(font, hovered, hoverX, hoverY);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
