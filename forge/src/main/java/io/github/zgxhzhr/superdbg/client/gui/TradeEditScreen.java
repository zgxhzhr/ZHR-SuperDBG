package io.github.zgxhzhr.superdbg.client.gui;

import io.github.zgxhzhr.superdbg.entity.EntityEditorData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * 单条村民交易编辑屏：买入物 A（必填）、买入物 B（可选）、卖出物（必填）
 * 三个槽位 + 各自数量 + 最大交易次数 + 村民经验。
 * <p>
 * 点槽位按钮打开 {@link ItemPickerScreen}；保存后通过
 * {@link EntityEditorScreen#upsertTrade} 回写父屏幕草稿。
 * 屏幕实例在「编辑器→物品选择器」往返中复用：物品与文本字段保留、控件重建。
 */
public final class TradeEditScreen extends Screen {

    private static final int PANEL_W = 260;
    private static final int PANEL_H = 180;

    private final EntityEditorScreen parent;
    /** -1 表示新增，否则为 tradesDraft 中的行号 */
    private final int index;

    private Item costA;
    private Item costB;
    private Item result;
    // 三槽位物品 NBT：编辑现有交易时从原报价带入（附魔书的具体附魔就在这里），
    // 在物品选择器里改选别的物品则清空（新物品无 NBT）
    private net.minecraft.nbt.CompoundTag costATag;
    private net.minecraft.nbt.CompoundTag costBTag;
    private net.minecraft.nbt.CompoundTag resultTag;
    // 文本字段始终持有最新输入，切到物品选择器再返回（init 重建控件）不丢值
    private String countAText = "1";
    private String countBText = "1";
    private String countRText = "1";
    private String maxUsesText = "12";
    private String xpText = "0";

    // 三个数量框直接持有引用（Screen.children 是 private，不能靠遍历）
    private EditBox countABox;
    private EditBox countBBox;
    private EditBox countRBox;

    private int left;
    private int top;
    private String error;

    TradeEditScreen(EntityEditorScreen parent, int index, EntityEditorData.TradeEntry init) {
        super(Component.literal("编辑交易"));
        this.parent = parent;
        this.index = index;
        if (init != null) {
            this.costA = resolveItem(init.costA());
            this.costATag = init.costATag();
            this.result = resolveItem(init.result());
            this.resultTag = init.resultTag();
            if (init.hasCostB()) {
                this.costB = resolveItem(init.costB());
                this.costBTag = init.costBTag();
            }
            countAText = String.valueOf(Math.max(1, init.countA()));
            countRText = String.valueOf(Math.max(1, init.countR()));
            countBText = String.valueOf(Math.max(1, init.countB()));
            maxUsesText = String.valueOf(Math.max(1, init.maxUses()));
            xpText = String.valueOf(Math.max(0, init.xp()));
        }
    }

    private static Item resolveItem(ResourceLocation id) {
        if (id == null) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? null : item;
    }

    @Override
    protected void init() {
        super.init();
        // 从物品选择器返回时本实例会被二次 init（setScreen 不清控件），先清空再按字段重建
        clearWidgets();
        // 重选物品后上次的保存校验红字不再适用
        error = null;
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;

        int row1 = top + 26;
        int row2 = top + 52;
        int row3 = top + 78;

        // 三行：槽位按钮 + 数量框（B 行额外带清除按钮）
        countABox = numBox(left + 220, row1, countAText);
        countBBox = numBox(left + 220, row2, countBText);
        countRBox = numBox(left + 220, row3, countRText);
        addSlotRow(row1, costA, costATag, true, countAText, stack -> {
            costA = stack.getItem();
            costATag = stack.getTag();
        }, 110, countABox);
        addSlotRow(row2, costB, costBTag, false, countBText, stack -> {
            costB = stack.getItem();
            costBTag = stack.getTag();
        }, 76, countBBox);
        addRenderableWidget(Button.builder(Component.literal("清除B"), b -> {
            syncTexts();
            costB = null;
            costBTag = null;
            minecraft.setScreen(this);
        }).bounds(left + 180, row2 - 1, 34, 18).build());
        addSlotRow(row3, result, resultTag, true, countRText, stack -> {
            result = stack.getItem();
            resultTag = stack.getTag();
        }, 110, countRBox);

        int row4 = top + 108;
        EditBox maxUsesBox = numBox(left + 100, row4, maxUsesText);
        EditBox xpBox = numBox(left + 210, row4, xpText);
        addRenderableWidget(maxUsesBox);
        addRenderableWidget(xpBox);

        addRenderableWidget(Button.builder(Component.literal("保存"), b -> {
            // 直接点保存（没走物品选择器）时也要先把框中最新文本回写
            syncTexts();
            maxUsesText = maxUsesBox.getValue();
            xpText = xpBox.getValue();
            save();
        }).bounds(left + 68, top + 140, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"),
                        b -> Minecraft.getInstance().setScreen(parent))
                .bounds(left + 134, top + 140, 60, 20).build());
    }

    /**
     * 构建一行槽位控件。
     *
     * @param tag    该槽位当前物品的 NBT（决定标签是否显示附魔明细）
     * @param picker 点击槽位按钮时打开物品选择器的动作（回传带 NBT 的完整物品堆）
     */
    private void addSlotRow(int y, Item current, net.minecraft.nbt.CompoundTag tag, boolean required,
                            String countText,
                            java.util.function.Consumer<net.minecraft.world.item.ItemStack> picker,
                            int slotWidth, EditBox countBox) {
        Button slot = Button.builder(slotLabel(current, tag, required), b -> {
            syncTexts();
            // 统一选择器返回完整物品堆：搜索附魔名可直接选中带具体附魔与等级的附魔书
            ItemPickerScreen.open(stack -> {
                picker.accept(stack);
                Minecraft.getInstance().setScreen(TradeEditScreen.this);
            });
        }).bounds(left + 100, y - 1, slotWidth, 18).build();
        // 物品名可能超出窄按钮，悬停显示全名（含附魔明细）
        if (current != null) {
            slot.setTooltip(Tooltip.create(Component.literal(
                    EntityEditorScreen.itemNameWithTag(BuiltInRegistries.ITEM.getKey(current), tag))));
        }
        addRenderableWidget(slot);
        addRenderableWidget(countBox);
    }

    private EditBox numBox(int x, int y, String def) {
        EditBox box = new EditBox(font, x, y, 36, 18, Component.literal("数量"));
        box.setMaxLength(3);
        box.setFilter(s -> s.isEmpty() || s.matches("\\d{1,3}"));
        box.setValue(def == null || def.isEmpty() ? "1" : def);
        return box;
    }

    private static Component slotLabel(Item item, net.minecraft.nbt.CompoundTag tag, boolean required) {
        if (item == null) {
            return Component.literal(required ? "点击选择…" : "无（点击选择）");
        }
        // 附魔书等带 NBT 物品直接显示"附魔书（锋利 V）"，宽度不足时由悬停提示补全
        return Component.literal(EntityEditorScreen.itemNameWithTag(
                BuiltInRegistries.ITEM.getKey(item), tag));
    }

    /** 切去物品选择器前，把当前三个数量框文本回写字段 */
    private void syncTexts() {
        countAText = countABox.getValue();
        countBText = countBBox.getValue();
        countRText = countRBox.getValue();
    }

    private void save() {
        if (costA == null || result == null) {
            error = "买入物 A 和卖出物必须选择";
            return;
        }
        int countA = clampCount(parseIntOr(countAText, 1));
        int countR = clampCount(parseIntOr(countRText, 1));
        boolean hasB = costB != null;
        int countB = hasB ? clampCount(parseIntOr(countBText, 1)) : 0;
        int maxUses = Math.max(1, Math.min(9999, parseIntOr(maxUsesText, 12)));
        int xp = Math.max(0, Math.min(1000, parseIntOr(xpText, 0)));

        EntityEditorData.TradeEntry entry = new EntityEditorData.TradeEntry(
                BuiltInRegistries.ITEM.getKey(costA), countA, costATag,
                hasB, hasB ? BuiltInRegistries.ITEM.getKey(costB) : null, countB, costBTag,
                BuiltInRegistries.ITEM.getKey(result), countR, resultTag,
                maxUses, xp);
        parent.upsertTrade(index, entry);
        Minecraft.getInstance().setScreen(parent);
    }

    private static int clampCount(int c) {
        return Math.max(1, Math.min(64, c));
    }

    private static int parseIntOr(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(left, top, left + PANEL_W, top + PANEL_H, 0xFF555555);
        graphics.fill(left + 1, top + 1, left + PANEL_W - 1, top + PANEL_H - 1, 0xFFFFFFFF);
        graphics.fill(left + 2, top + 2, left + PANEL_W - 2, top + PANEL_H - 2, 0xFFC6C6C6);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, Component.literal(index < 0 ? "新增交易" : "编辑交易"),
                left + 10, top + 8, 0x404040, false);
        graphics.drawString(font, Component.literal("买入物 A（价格）"), left + 10, top + 31, 0x404040, false);
        graphics.drawString(font, Component.literal("买入物 B（可选）"), left + 10, top + 57, 0x404040, false);
        graphics.drawString(font, Component.literal("卖出物（商品）"), left + 10, top + 83, 0x404040, false);
        graphics.drawString(font, Component.literal("数量"), left + 220, top + 13, 0x707070, false);
        graphics.drawString(font, Component.literal("最大交易次数"), left + 10, top + 113, 0x404040, false);
        graphics.drawString(font, Component.literal("村民经验"), left + 160, top + 113, 0x404040, false);
        if (error != null) {
            graphics.drawString(font, Component.literal(error), left + 10, top + 128, 0xCC0000, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
