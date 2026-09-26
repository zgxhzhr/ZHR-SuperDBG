package io.github.zgxhzhr.superdbg.client.gui;

import io.github.zgxhzhr.superdbg.item.EditableAttribute;
import io.github.zgxhzhr.superdbg.item.ItemEditorData;
import io.github.zgxhzhr.superdbg.item.ItemEditorService;
import io.github.zgxhzhr.superdbg.menu.ItemEditorMenu;
import io.github.zgxhzhr.superdbg.network.NetworkHandler;
import io.github.zgxhzhr.superdbg.network.SubmitItemEditorPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用物品编辑器屏幕。
 * <p>
 * 两个页签：
 * <ul>
 *   <li>基础：重命名、无法破坏、调试斧（仅金斧可见）、8 项原版属性直接输入框</li>
 *   <li>附魔：全部注册附魔的等级编辑（任意物品可附加任意附魔，留空=移除）</li>
 * </ul>
 * 属性使用精确数值输入而非滑块：滑块拖动会把不在步进网格上的默认值
 * （如钻石剑攻速 -2.4）吸附成别的值，且难以输入精确数值。
 * 点击"确认"时全量原子提交。
 */
public class ItemEditorScreen extends AbstractContainerScreen<ItemEditorMenu> {

    private static final int ENCH_PAGE_SIZE = 9;

    private final List<Enchantment> allEnchantments = new ArrayList<>();

    private EditBox nameBox;
    private Checkbox unbreakableBox;
    private Checkbox debugBox;
    private final List<AttrBox> attrBoxes = new ArrayList<>();
    private final List<EditBox> enchantBoxes = new ArrayList<>();
    private CycleButton<Boolean> tabButton;
    private Button confirmButton;
    private Button cancelButton;

    private final List<net.minecraft.client.gui.components.AbstractWidget> baseWidgets = new ArrayList<>();
    private final List<net.minecraft.client.gui.components.AbstractWidget> enchantWidgets = new ArrayList<>();

    private boolean enchantTab = false;
    private int enchPage = 0;
    /** 是否金斧（决定调试框显隐，页签切换后也必须保持） */
    private boolean goldenAxe = false;

    public ItemEditorScreen(ItemEditorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 240;
        this.imageHeight = 246;
        this.titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        baseWidgets.clear();
        enchantWidgets.clear();
        attrBoxes.clear();
        enchantBoxes.clear();
        allEnchantments.clear();
        BuiltInRegistries.ENCHANTMENT.iterator().forEachRemaining(allEnchantments::add);

        ItemEditorData data = menu.getData();
        goldenAxe = minecraft.player.getOffhandItem().is(Items.GOLDEN_AXE);

        // ---- 页签切换 ----
        tabButton = CycleButton.booleanBuilder(Component.literal("附魔"), Component.literal("基础"))
                .withInitialValue(false)
                .create(leftPos + 158, topPos + 3, 70, 16,
                        Component.literal("页签"), (button, isEnch) -> setTab(isEnch));
        addRenderableWidget(tabButton);

        // ---- 基础页：名称 ----
        nameBox = new EditBox(font, leftPos + 12, topPos + 22, 216, 18,
                Component.literal("名称"));
        nameBox.setMaxLength(ItemEditorService.MAX_NAME_LENGTH);
        nameBox.setValue(data.customName);
        addRenderableWidget(nameBox);
        baseWidgets.add(nameBox);

        // ---- 基础页：标志位 ----
        unbreakableBox = new Checkbox(leftPos + 12, topPos + 44, 100, 18,
                Component.literal("无法破坏"), data.unbreakable);
        addRenderableWidget(unbreakableBox);
        baseWidgets.add(unbreakableBox);

        debugBox = new Checkbox(leftPos + 124, topPos + 44, 104, 18,
                Component.literal("调试状态"), data.debug);
        // 显隐统一由 setTab 控制（非金斧永远隐藏）
        addRenderableWidget(debugBox);
        baseWidgets.add(debugBox);

        // ---- 基础页：属性直接输入框 ----
        for (int i = 0; i < EditableAttribute.ALL.size(); i++) {
            EditableAttribute ea = EditableAttribute.ALL.get(i);
            ResourceLocation rl = BuiltInRegistries.ATTRIBUTE.getKey(ea.attribute());
            double current = data.attributes.getOrDefault(rl, 0.0D);
            AttrBox box = new AttrBox(font, leftPos + 146, topPos + 68 + i * 19 + 1, 82, 16, ea, current);
            addRenderableWidget(box);
            attrBoxes.add(box);
            baseWidgets.add(box);
        }

        // ---- 附魔页：每个附魔一个等级框 ----
        for (int i = 0; i < allEnchantments.size(); i++) {
            Enchantment ench = allEnchantments.get(i);
            ResourceLocation rl = BuiltInRegistries.ENCHANTMENT.getKey(ench);
            EditBox box = new EditBox(font, leftPos + 172, topPos + 31 + (i % ENCH_PAGE_SIZE) * 20,
                    56, 16, Component.literal("等级"));
            // Mixin 已放开 EnchantmentHelper 序列化两端瓶颈，支持 0-Integer.MAX_VALUE
            box.setMaxLength(10);
            box.setFilter(s -> s.isEmpty() || s.matches("\\d{1,10}"));
            Integer level = data.enchantments.get(rl);
            if (level != null && level > 0) {
                box.setValue(String.valueOf(level));
            }
            addRenderableWidget(box);
            enchantBoxes.add(box);
            enchantWidgets.add(box);
        }

        // ---- 底部按钮 ----
        confirmButton = Button.builder(Component.literal("确认"), b -> submit())
                .bounds(leftPos + 56, topPos + 222, 60, 18)
                .build();
        addRenderableWidget(confirmButton);

        cancelButton = Button.builder(Component.literal("取消"), b -> onClose())
                .bounds(leftPos + 124, topPos + 222, 60, 18)
                .build();
        addRenderableWidget(cancelButton);

        setTab(false);
    }

    private void setTab(boolean ench) {
        this.enchantTab = ench;
        for (var w : baseWidgets) {
            w.visible = !ench;
        }
        for (var w : enchantWidgets) {
            w.visible = ench;
        }
        // 修复：从附魔页切回基础页时，必须显式按金斧判断设回 false，
        // 否则非金斧的调试框会因 baseWidgets 批量置可见而残留显示
        debugBox.visible = !ench && goldenAxe;
        updateEnchPageVisibility();
        setFocused(null);
    }

    private int maxEnchPage() {
        return Math.max(0, (allEnchantments.size() - 1) / ENCH_PAGE_SIZE);
    }

    private void updateEnchPageVisibility() {
        for (int i = 0; i < enchantBoxes.size(); i++) {
            enchantBoxes.get(i).visible = enchantTab
                    && i / ENCH_PAGE_SIZE == enchPage;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (enchantTab) {
            int next = enchPage - (delta > 0 ? 1 : -1);
            next = Math.max(0, Math.min(maxEnchPage(), next));
            if (next != enchPage) {
                enchPage = next;
                updateEnchPageVisibility();
                setFocused(null);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /**
     * 收集界面数据并全量提交。
     */
    private void submit() {
        ItemEditorData data = menu.getData();
        data.customName = nameBox.getValue();
        data.unbreakable = unbreakableBox.selected();
        data.debug = debugBox.selected();

        data.enchantments.clear();
        for (int i = 0; i < allEnchantments.size(); i++) {
            String raw = enchantBoxes.get(i).getValue().trim();
            if (raw.isEmpty()) continue;
            try {
                int level = Integer.parseInt(raw);
                level = Math.max(1, Math.min(ItemEditorService.MAX_ENCHANT_LEVEL, level));
                data.enchantments.put(
                        BuiltInRegistries.ENCHANTMENT.getKey(allEnchantments.get(i)), level);
            } catch (NumberFormatException ignored) {
                // 非法输入直接忽略该项
            }
        }

        data.attributes.clear();
        for (AttrBox box : attrBoxes) {
            data.attributes.put(
                    BuiltInRegistries.ATTRIBUTE.getKey(box.def.attribute()), box.value());
        }

        NetworkHandler.CHANNEL.sendToServer(new SubmitItemEditorPacket(
                data.customName,
                data.unbreakable,
                data.debug,
                new LinkedHashMap<>(data.enchantments),
                new LinkedHashMap<>(data.attributes)
        ));
        onClose();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        // 与药水编辑器一致的灰色斜面面板
        graphics.fill(x, y, x + imageWidth, y + imageHeight, 0xFF555555);
        graphics.fill(x + 1, y + 1, x + imageWidth - 1, y + imageHeight - 1, 0xFFFFFFFF);
        graphics.fill(x + 2, y + 2, x + imageWidth - 2, y + imageHeight - 2, 0xFFC6C6C6);
        // 底部分隔线
        graphics.fill(x + 4, y + 216, x + imageWidth - 4, y + 217, 0xFF555555);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 6, 0x404040, false);

        if (!enchantTab) {
            graphics.drawString(font, Component.literal("名称"), 12, 13, 0x404040, false);
            // 属性行标签（输入框在右侧，不重叠）
            for (int i = 0; i < EditableAttribute.ALL.size(); i++) {
                EditableAttribute ea = EditableAttribute.ALL.get(i);
                graphics.drawString(font, Component.translatable(ea.attribute().getDescriptionId()),
                        12, 68 + i * 19 + 5, 0x404040, false);
            }
            return;
        }

        // 附魔页
        graphics.drawString(font,
                Component.literal("附魔等级（留空=移除，1-" + ItemEditorService.MAX_ENCHANT_LEVEL + "）"),
                12, 14, 0x404040, false);

        int start = enchPage * ENCH_PAGE_SIZE;
        int end = Math.min(allEnchantments.size(), start + ENCH_PAGE_SIZE);
        for (int i = start; i < end; i++) {
            int rowY = 31 + (i % ENCH_PAGE_SIZE) * 20;
            Enchantment ench = allEnchantments.get(i);
            String text = Component.translatable(ench.getDescriptionId()).getString();
            // 超长名称裁剪
            if (font.width(text) > 150) {
                text = font.plainSubstrByWidth(text, 144) + "…";
            }
            graphics.drawString(font, text, 12, rowY + 4, 0x404040, false);
        }

        graphics.drawString(font,
                Component.literal((enchPage + 1) + "/" + (maxEnchPage() + 1) + "（滚轮翻页）"),
                12, 213, 0x404040, false);
    }

    /**
     * 属性输入框：直接输入精确数值，不做步进吸附（避免 -2.4 这类默认值被改动）。
     * 留空或非法输入按 0 处理，提交时服务端会再次 clamp。
     */
    private static class AttrBox extends EditBox {

        private final EditableAttribute def;

        AttrBox(Font font, int x, int y, int w, int h, EditableAttribute def, double current) {
            super(font, x, y, w, h, Component.translatable(def.attribute().getDescriptionId()));
            this.def = def;
            setMaxLength(18);
            setFilter(s -> s.isEmpty() || s.equals("-")
                    || s.matches("-?\\d{1,10}(\\.\\d{0,6})?"));
            setValue(formatAttr(current));
        }

        double value() {
            String raw = getValue().trim();
            if (raw.isEmpty() || raw.equals("-")) {
                return 0.0D;
            }
            try {
                return def.normalize(Double.parseDouble(raw));
            } catch (NumberFormatException e) {
                return 0.0D;
            }
        }
    }

    /**
     * 格式化属性显示：最多 6 位小数并去掉尾随零（6.0 → "6"，-2.4 → "-2.4"）。
     */
    private static String formatAttr(double value) {
        BigDecimal bd = BigDecimal.valueOf(value)
                .setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return bd.toPlainString();
    }
}
