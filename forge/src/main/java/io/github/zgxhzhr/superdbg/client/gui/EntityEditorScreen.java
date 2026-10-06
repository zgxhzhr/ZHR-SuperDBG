package io.github.zgxhzhr.superdbg.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.zgxhzhr.superdbg.entity.EntityEditorData;
import io.github.zgxhzhr.superdbg.loot.LootConfig;
import io.github.zgxhzhr.superdbg.loot.LootMath;
import io.github.zgxhzhr.superdbg.menu.EntityEditorMenu;
import io.github.zgxhzhr.superdbg.network.NetworkHandler;
import io.github.zgxhzhr.superdbg.network.SubmitEntityEditorPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体编辑器屏幕。
 * <p>
 * 页签（右上角「页面 ▾」下拉列表切换，按目标类型裁剪）：
 * <ul>
 *   <li>属性：该实体已注册的全部属性（含模组属性）基础值直接输入框，滚动列表；
 *       底部可直接编辑当前生命值</li>
 *   <li>效果：药水效果增删改（等级 0-2147483646、时长 -1 永久），滚动列表 + 效果选择覆盖层</li>
 *   <li>词条：L2Hostility 词条等级（0=无该词条，最高 255）与难度等级；
 *       仅在安装 L2Hostility 且目标拥有词条能力时出现</li>
 *   <li>饰品/羁绊/交易：按目标类型（Curios/女仆/村民）出现</li>
 *   <li>人是狐：仅目标为玩家且安装人是狐时出现；开关、主人、
 *       好感度点数（0-384）、日程、无敌</li>
 *   <li>渲染名：目标为玩家时出现（由调试器本体存储与渲染，不依赖任何第三方模组），
 *       位于面板底部公共区，任意页签可见，仅替换头顶名牌不影响真实名字</li>
 *   <li>获取：仅目标为玩家时出现；快速发放物品（物品 + 总数 + 每组个数，
 *       每组可超原版上限至 999），独立「发放给玩家」按钮即发，不进主提交</li>
 *   <li>杂项：移除实体（仅可移除目标）、防移除、伪创造模式（仅玩家目标；
 *       受击流程正常但生命值不掉，勾选即时发送持久化，不进主提交）</li>
 * </ul>
 * 杂项页"移除实体"勾选后点确认：生物直接移除，玩家走死亡流程。
 * 点击"确认"时全量原子提交。
 */
public class EntityEditorScreen extends AbstractContainerScreen<EntityEditorMenu> {

    private static final int ROW_HEIGHT = 19;
    /** 内容区可视行数 */
    private static final int VISIBLE_ROWS = 8;
    /** 内容区相对 topPos 的起始偏移 */
    private static final int CONTENT_TOP = 24;

    /** 编辑器页签 */
    private enum Tab {
        ATTR("属性"),
        EFFECT("效果"),
        TRAIT("词条"),
        CURIOS("饰品"),
        BOND("羁绊"),
        TRADE("交易"),
        LOOT("掉落"),
        GIFT("赠礼"),
        FOX("人是狐"),
        GIVE("获取"),
        MISC("杂项");

        private final String label;

        Tab(String label) {
            this.label = label;
        }

        Component label() {
            return Component.literal(label);
        }
    }

    /** 属性页每行的注册名，与 attrBoxes 一一对应 */
    private final List<ResourceLocation> attrIds = new ArrayList<>();
    private final List<AttrBox> attrBoxes = new ArrayList<>();
    private final List<AbstractWidget> attrWidgets = new ArrayList<>();

    /** 效果页行数据 */
    private final List<EffectRow> effectRows = new ArrayList<>();
    private final List<AbstractWidget> effectWidgets = new ArrayList<>();

    /** 词条页：每个已注册词条一个等级输入框（0=无该词条，最高 255） */
    private final List<ResourceLocation> traitIds = new ArrayList<>();
    private final List<EditBox> traitBoxes = new ArrayList<>();
    private final List<AbstractWidget> traitWidgets = new ArrayList<>();

    /** 饰品页：每个 Curios 槽位类型一个数量输入框（0-999） */
    private final List<String> curiosIds = new ArrayList<>();
    private final List<EditBox> curiosBoxes = new ArrayList<>();
    private final List<AbstractWidget> curiosWidgets = new ArrayList<>();

    /** 羁绊页：按显示顺序排列的行标签与控件（每行一个控件） */
    private final List<String> bondRowLabels = new ArrayList<>();
    private final List<AbstractWidget> bondWidgets = new ArrayList<>();
    private EditBox bondFavorBox;
    private EditBox bondLevelBox;
    private EditBox bondGiftBox;
    /** 羁绊解锁开关状态（null=未装 TMA，无此行） */
    private boolean[] bondUnlockedState;
    /** 能力行：id + 开关状态，顺序与 bondWidgets 中对应行一致 */
    private final List<String> bondAbilityIds = new ArrayList<>();
    private final List<boolean[]> bondAbilityStates = new ArrayList<>();

    /** 交易页：村民交易草稿（仅初始化一次，子屏编辑返回后保留）；非村民为 null */
    private List<EntityEditorData.TradeEntry> tradesDraft;
    private final List<AbstractWidget> tradeWidgets = new ArrayList<>();
    private int tradeScroll = 0;
    /** 全量控件是否已构建过（用于区分子屏返回与首次/resize 初始化） */
    private boolean built;

    /** 效果选择覆盖层按钮（与 allEffects 一一对应） */
    private final List<MobEffect> allEffects = new ArrayList<>();
    private final List<AbstractWidget> pickerWidgets = new ArrayList<>();

    /** 获取页草稿：静态字段跨开关界面保留（最近一次配置，按用户确认不随关闭清空） */
    private static List<GiveRowData> giveDrafts;
    private final List<AbstractWidget> giveWidgets = new ArrayList<>();
    private int giveScroll = 0;

    /** 掉落页：本实体/全类型两份草稿（仅非玩家目标初始化），物品选择器返回后保留 */
    private List<LootRowData> lootEntityDraft;
    private List<LootRowData> lootTypeDraft;
    private boolean lootEntityEnabled;
    private boolean lootTypeEnabled;
    private LootConfig.Mode lootEntityMode = LootConfig.Mode.REPLACE;
    private LootConfig.Mode lootTypeMode = LootConfig.Mode.REPLACE;
    /** 掉落页当前作用域：0=本实体，1=全类型 */
    private int lootScope = 0;
    private final List<AbstractWidget> lootWidgets = new ArrayList<>();
    private int lootScroll = 0;
    /** 掉落行控件重建中标志：屏蔽 setValue 触发的 responder，避免一打开界面就自动开覆盖 */
    private boolean buildingLootRows = false;
    private Button lootScopeButton;
    private Button lootModeButton;
    private Button lootEnabledButton;
    private Button addLootButton;

    /** 赠礼页：女仆个体的 TMA 回赠礼物池草稿（仅女仆+TMA 初始化），物品选择器返回后保留 */
    private List<GiftRowData> giftDraft;
    private final List<AbstractWidget> giftWidgets = new ArrayList<>();
    private int giftScroll = 0;
    private Button addGiftPoolButton;

    /** 人是狐页：行标签与控件（每行一个），仅玩家目标且安装 playermaid 时构建 */
    private final List<String> foxRowLabels = new ArrayList<>();
    private final List<AbstractWidget> foxWidgets = new ArrayList<>();
    private Checkbox foxActiveBox;
    private EditBox foxRenderBox;
    /** 上次已发送到服务端的渲染名（trim 后）；用于关闭界面时判断是否需要补发 */
    private String lastSentRenderName = "";
    private EditBox foxOwnerBox;
    private EditBox foxFavorBox;
    private Button foxSlabModelButton;
    /** 魂符展示模型可选列表（客户端反射自车万女仆（Touhou Little Maid，作者 TartaricAcid，MIT 协议开源）），点击按钮循环切换 */
    private final List<String> foxSlabModels = new ArrayList<>();
    /** 当前选中的魂符展示模型 id（空白表示不渲染模型） */
    private String foxSlabModelSelected = "";
    private Button foxScheduleButton;
    /** 日程模式名（DAY/NIGHT/ALL），随日程按钮本地循环切换 */
    private String foxSchedule = "DAY";
    private Checkbox foxInvulnBox;
    private int foxScroll = 0;

    /** 当前可用的页签（按目标实体类型裁剪），下拉列表按此构建 */
    private final List<Tab> availableTabs = new ArrayList<>();
    private Button tabButton;
    private boolean tabListOpen = false;
    /** 下拉列表展开时的选项按钮（与 availableTabs 一一对应） */
    private final List<AbstractWidget> tabListWidgets = new ArrayList<>();
    private Button addEffectButton;
    private Button addTradeButton;
    private Button addGiveButton;
    private Button giveButton;
    private Checkbox removeBox;
    private Checkbox removalGuardBox;
    /** 伪创造模式开关（仅玩家目标构建；即时发送，不走主提交） */
    private Checkbox pseudoCreativeBox;
    private Button confirmButton;
    private Button cancelButton;
    /** 跳转 gamerule 编辑器按钮（仅 creative+OP 可见） */
    private Button gameruleButton;
    /** 属性页底部的当前生命值输入框 */
    private EditBox healthBox;
    /** 目标为车万女仆时：血量输入置灰，提交后服务端一律回满 */
    private boolean maidFullHealth;
    /** 词条页的 L2Hostility 难度等级输入框（决定生命缩放倍率） */
    private EditBox levelBox;

    private Tab tab = Tab.ATTR;
    private boolean pickerOpen = false;
    private int attrScroll = 0;
    private int effectScroll = 0;
    private int traitScroll = 0;
    private int curiosScroll = 0;
    private int bondScroll = 0;
    private int pickerScroll = 0;

    public EntityEditorScreen(EntityEditorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 260;
        this.imageHeight = 254;
        this.titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        try {
            // 从交易编辑/物品选择子屏返回时，本实例控件仍在（setScreen 不清控件），
            // 只重建行控件，保留其他页未提交的编辑；首次/resize（renderables 已被清空）走全量构建
            if (built && !renderables.isEmpty()) {
                rebuildTradeRows();
                rebuildGiveRows();
                rebuildLootRows();
                rebuildGiftRows();
                setTab(tab);
                return;
            }
            built = true;
            superdbg$initContent();
            // 保留当前页签：resize/全量重建后仍停在原页（如获取页选物品返回后不应跳回属性页）
            setTab(availableTabs.contains(tab) ? tab : Tab.ATTR);
        } catch (Exception e) {
            // 兜底：任何异常（如模组注入的异常属性值）只记录不扩散，避免控件静默消失
            io.github.zgxhzhr.superdbg.Constants.LOG.error("[EntityEditor] init 异常，界面可能不完整", e);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 本界面继承 AbstractContainerScreen，原版会把背包键（默认 E）当成“打开/关闭背包”，
        // 在编辑界面里打字或误按都会把面板直接关掉。这里吞掉背包键使其不触发关闭；
        // 文本框仍由 charTyped 单独接收字符，界面可正常用 ESC 或“取消”关闭。
        if (this.minecraft != null && this.minecraft.options.keyInventory.isActiveAndMatches(
                InputConstants.getKey(keyCode, scanCode))) {
            return true;
        }
        // 渲染名框处于焦点时按回车：发送当前值到服务端（不再每敲一键发包）
        if (foxRenderBox != null && foxRenderBox.isFocused()
                && (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
            superdbg$sendRenderName();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 界面关闭（ESC/取消/切屏）时：若渲染名有未发送的变化则补发一次。 */
    @Override
    public void removed() {
        if (foxRenderBox != null) {
            superdbg$sendRenderName();
        }
        super.removed();
    }

    /** 渲染名值有变化时发送到服务端并记录基线（空白即清除语义由服务端处理）。 */
    private void superdbg$sendRenderName() {
        if (foxRenderBox == null) {
            return;
        }
        String value = foxRenderBox.getValue().trim();
        if (!value.equals(lastSentRenderName)) {
            lastSentRenderName = value;
            NetworkHandler.CHANNEL.sendToServer(
                    new io.github.zgxhzhr.superdbg.network.SetRenderNamePacket(menu.getEntityId(), value));
        }
    }

    private void superdbg$initContent() {
        BuiltInRegistries.MOB_EFFECT.iterator().forEachRemaining(allEffects::add);
        allEffects.sort((a, b) -> a.getDisplayName().getString().compareTo(b.getDisplayName().getString()));

        // 全量重建（resize 等）：旧下拉选项按钮已不在控件列表里，清缓存防展开时显示死控件
        tabListOpen = false;
        tabListWidgets.clear();

        // ---- 页签切换（下拉列表）----（无词条能力/无饰品栏/非女仆等时隐藏对应页签）
        boolean hasTraits = !menu.getData().traits().isEmpty();
        boolean hasCurios = !menu.getData().curiosSlots().isEmpty();
        boolean hasBond = menu.getData().bond() != null && menu.getData().bond().present();
        boolean hasTrades = menu.getData().trades() != null;
        // 获取页仅对玩家目标开放（服务端按实体 id 在玩家列表里查，查不到拒发）
        int selfEntityId = menu.getData().entityId();
        net.minecraft.world.entity.Entity selfTarget =
                minecraft.level == null ? null : minecraft.level.getEntity(selfEntityId);
        boolean isPlayerTarget = selfTarget instanceof net.minecraft.world.entity.player.Player;
        // 女仆不提供掉落页；安装 TMA 时该位置改为「赠礼」页（配置女仆回礼池）
        boolean isMaidTarget = selfTarget instanceof net.minecraft.world.entity.LivingEntity selfLiving
                && io.github.zgxhzhr.superdbg.entity.EntityEditorService.isTouhouMaid(selfLiving);
        boolean hasGiftPage = isMaidTarget
                && io.github.zgxhzhr.superdbg.compat.tma.TmaBondCompat.LOADED;
        // 人是狐页：玩家目标 + 已安装人是狐 + 服务端快照存在
        boolean hasFoxPage = isPlayerTarget
                && io.github.zgxhzhr.superdbg.compat.playermaid.PlayerMaidCompat.LOADED
                && menu.getData().foxMaid() != null
                && menu.getData().foxMaid().present();
        availableTabs.add(Tab.ATTR);
        availableTabs.add(Tab.EFFECT);
        if (hasTraits) {
            availableTabs.add(Tab.TRAIT);
        }
        if (hasCurios) {
            availableTabs.add(Tab.CURIOS);
        }
        if (hasBond) {
            availableTabs.add(Tab.BOND);
        }
        if (hasTrades) {
            availableTabs.add(Tab.TRADE);
        }
        // 掉落页对玩家/女仆无意义（玩家无战利品表，女仆死亡掉落不应由调试器改）：
        // 女仆在安装 TMA 时该位置改为赠礼页
        if (!isPlayerTarget && !isMaidTarget) {
            availableTabs.add(Tab.LOOT);
        }
        if (hasGiftPage) {
            availableTabs.add(Tab.GIFT);
        }
        if (hasFoxPage) {
            availableTabs.add(Tab.FOX);
        }
        if (isPlayerTarget) {
            availableTabs.add(Tab.GIVE);
        }
        // 杂项页所有目标类型均可用：移除/防移除仅对可移除目标（removable）显示，
        // 伪创造开关仅对玩家目标显示（服务端另有校验）
        availableTabs.add(Tab.MISC);
        // 点击展开页签下拉列表（页签多了以后 CycleButton 逐个点太费劲）
        tabButton = Button.builder(
                Component.literal("页面：" + Tab.ATTR.label + " ▾"),
                b -> toggleTabList())
                .bounds(leftPos + 182, topPos + 3, 66, 16).build();
        addRenderableWidget(tabButton);

        // ---- 属性页：每个已注册属性一个基础值输入框 ----
        var attrEntries = menu.getData().attrs();
        for (var entry : attrEntries) {
            attrIds.add(entry.id());
            AttrBox box = new AttrBox(font, leftPos + 148, 0, 100, 16, entry.baseValue());
            addRenderableWidget(box);
            attrBoxes.add(box);
            attrWidgets.add(box);
        }

        // ---- 效果页：现有效果行 ----
        for (var entry : menu.getData().effects()) {
            MobEffect effect = BuiltInRegistries.MOB_EFFECT.get(entry.id());
            if (effect != null) {
                addEffectRow(effect, entry.amplifier(), entry.duration());
            }
        }

        // ---- 效果选择覆盖层：全部注册效果各一个按钮 ----
        for (MobEffect effect : allEffects) {
            ResourceLocation rl = BuiltInRegistries.MOB_EFFECT.getKey(effect);
            Button btn = Button.builder(effect.getDisplayName(), b -> pickEffect(effect, rl))
                    .bounds(leftPos + 40, 0, 180, 16)
                    .build();
            addRenderableWidget(btn);
            pickerWidgets.add(btn);
            btn.visible = false;
        }

        // ---- 词条页：全部已注册词条各一个等级框（0=无，最高 255） ----
        for (var entry : menu.getData().traits()) {
            traitIds.add(entry.id());
            EditBox box = new EditBox(font, leftPos + 150, 0, 60, 16, Component.literal("词条等级"));
            box.setMaxLength(3);
            box.setFilter(s -> s.isEmpty() || s.matches("\\d{1,3}"));
            box.setValue(String.valueOf(Math.max(0, Math.min(255, entry.level()))));
            addRenderableWidget(box);
            traitBoxes.add(box);
            traitWidgets.add(box);
        }

        // 难度等级（仅词条页显示），服务端按 L2Hostility 配置钳制
        levelBox = new EditBox(font, leftPos + 150, topPos + 178, 60, 16,
                Component.literal("难度等级"));
        levelBox.setMaxLength(4);
        levelBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,4}"));
        int lv = menu.getData().hostilityLevel();
        if (lv >= 0) {
            levelBox.setValue(String.valueOf(lv));
        }
        addRenderableWidget(levelBox);

        // ---- 饰品页：每个 Curios 槽位类型一个数量输入框（0-999） ----
        for (var entry : menu.getData().curiosSlots()) {
            curiosIds.add(entry.identifier());
            EditBox box = new EditBox(font, leftPos + 150, 0, 60, 16,
                    Component.literal("槽位数量"));
            box.setMaxLength(3);
            box.setFilter(s -> s.isEmpty() || s.matches("\\d{1,3}"));
            box.setValue(String.valueOf(Math.max(0, Math.min(999, entry.amount()))));
            addRenderableWidget(box);
            curiosBoxes.add(box);
            curiosWidgets.add(box);
        }

        // ---- 羁绊页：好感度点数 + TMA 羁绊（等级缓存/解锁/礼物/能力开关） ----
        if (hasBond) {
            var bond = menu.getData().bond();
            // 行 0：TLM 好感度点数（0-384，等级由点数派生）
            bondFavorBox = new EditBox(font, leftPos + 150, 0, 60, 16,
                    Component.literal("好感度点数"));
            bondFavorBox.setMaxLength(3);
            bondFavorBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,3}"));
            bondFavorBox.setValue(String.valueOf(Math.max(0,
                    Math.min(io.github.zgxhzhr.superdbg.compat.tma.TmaBondCompat.MAX_FAVORABILITY, bond.favorability()))));
            addRenderableWidget(bondFavorBox);
            addBondRow("好感度点数(0-384)", bondFavorBox);

            if (bond.tmaLoaded()) {
                // 羁绊解锁开关（调试可强开，不受等级联动限制）
                bondUnlockedState = new boolean[]{bond.bondUnlocked()};
                final boolean[] unlocked = bondUnlockedState;
                Button unlockedBtn = Button.builder(
                        Component.literal(unlocked[0] ? "开" : "关"),
                        b -> {
                            unlocked[0] = !unlocked[0];
                            b.setMessage(Component.literal(unlocked[0] ? "开" : "关"));
                        }).bounds(leftPos + 150, 0, 60, 16).build();
                addRenderableWidget(unlockedBtn);
                addBondRow("羁绊解锁(等级≥3)", unlockedBtn);

                // 羁绊等级缓存（TMA 打开羁绊界面时会按好感度等级覆写）
                bondLevelBox = new EditBox(font, leftPos + 150, 0, 60, 16,
                        Component.literal("羁绊等级"));
                bondLevelBox.setMaxLength(3);
                bondLevelBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,3}"));
                bondLevelBox.setValue(String.valueOf(Math.max(0, bond.bondLevel())));
                bondLevelBox.setTooltip(Tooltip.create(Component.literal(
                        "TMA 每次打开羁绊界面时会按好感度等级覆写此缓存；真正源头是上方好感度点数")));
                addRenderableWidget(bondLevelBox);
                addBondRow("羁绊等级(缓存)", bondLevelBox);

                // 待发随机礼物数量
                bondGiftBox = new EditBox(font, leftPos + 150, 0, 60, 16,
                        Component.literal("待发礼物"));
                bondGiftBox.setMaxLength(3);
                bondGiftBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,3}"));
                bondGiftBox.setValue(String.valueOf(Math.max(0, bond.giftQueue())));
                addRenderableWidget(bondGiftBox);
                addBondRow("待发礼物数量", bondGiftBox);

                // 能力开关（显示名取 TMA 翻译键 bond.ability.*，无翻译时回退原始 id）
                for (var ability : bond.abilities()) {
                    final boolean[] st = {ability.unlocked()};
                    Button btn = Button.builder(
                            Component.literal(st[0] ? "开" : "关"),
                            b -> {
                                st[0] = !st[0];
                                b.setMessage(Component.literal(st[0] ? "开" : "关"));
                            }).bounds(leftPos + 150, 0, 60, 16).build();
                    addRenderableWidget(btn);
                    bondAbilityIds.add(ability.id());
                    bondAbilityStates.add(st);
                    addBondRow("能力：" + abilityDisplayName(ability.id()), btn);
                }
            }
        }

        // ---- 交易页：村民交易列表（草稿初始化一次，子屏编辑返回后保留） ----
        if (hasTrades && tradesDraft == null) {
            tradesDraft = new ArrayList<>(menu.getData().trades());
        }
        if (hasTrades) {
            rebuildTradeRows();
        }

        // ---- 获取页：快速发放物品（草稿存静态字段，关界面重开仍在） ----
        if (isPlayerTarget) {
            superdbg$ensureGiveDrafts();
            rebuildGiveRows();
        }

        // ---- 掉落页：本实体/全类型掉落覆盖（草稿初始化一次，物品选择器返回后保留） ----
        if (!isPlayerTarget && !isMaidTarget) {
            superdbg$ensureLootDrafts();
            // 控制行：范围切换 + 模式切换 + 覆盖开关
            lootScopeButton = Button.builder(Component.literal("范围：本实体"), b -> {
                superdbg$syncLootTexts();
                lootScope = 1 - lootScope;
                lootScroll = 0;
                superdbg$refreshLootControls();
                rebuildLootRows();
            }).bounds(leftPos + 12, topPos + CONTENT_TOP, 74, 16).build();
            addRenderableWidget(lootScopeButton);
            lootScopeButton.visible = false;

            lootModeButton = Button.builder(Component.literal("替换原版"), b -> {
                superdbg$syncLootTexts();
                if (lootScope == 0) {
                    lootEntityMode = lootEntityMode == LootConfig.Mode.REPLACE
                            ? LootConfig.Mode.APPEND : LootConfig.Mode.REPLACE;
                } else {
                    lootTypeMode = lootTypeMode == LootConfig.Mode.REPLACE
                            ? LootConfig.Mode.APPEND : LootConfig.Mode.REPLACE;
                }
                superdbg$refreshLootControls();
            }).bounds(leftPos + 90, topPos + CONTENT_TOP, 74, 16).build();
            lootModeButton.setTooltip(Tooltip.create(Component.literal(
                    "替换：原版战利品表不掉，只掉本列表（装备/经验/特殊掉落不受影响）；追加：原版照掉，额外追加本列表")));
            addRenderableWidget(lootModeButton);
            lootModeButton.visible = false;

            lootEnabledButton = Button.builder(Component.literal("覆盖：关"), b -> {
                superdbg$syncLootTexts();
                if (lootScope == 0) {
                    lootEntityEnabled = !lootEntityEnabled;
                } else {
                    lootTypeEnabled = !lootTypeEnabled;
                }
                superdbg$refreshLootControls();
            }).bounds(leftPos + 168, topPos + CONTENT_TOP, 60, 16).build();
            lootEnabledButton.setTooltip(Tooltip.create(Component.literal(
                    "开=确认后写入覆盖配置；关=确认后删除该范围的覆盖")));
            addRenderableWidget(lootEnabledButton);
            lootEnabledButton.visible = false;

            addLootButton = Button.builder(Component.literal("添加一行"), b -> {
                superdbg$syncLootTexts();
                superdbg$currentLootDraft().add(new LootRowData());
                markLootEdited();
                lootScroll = maxLootScroll();
                rebuildLootRows();
            }).bounds(leftPos + 160, topPos + 190, 88, 18).build();
            addRenderableWidget(addLootButton);
            addLootButton.visible = false;

            superdbg$refreshLootControls();
            rebuildLootRows();
        }

        // ---- 赠礼页：女仆个体的 TMA 回赠礼物池（仅女仆 + TMA，草稿初始化一次） ----
        if (hasGiftPage) {
            if (giftDraft == null) {
                giftDraft = new ArrayList<>();
                io.github.zgxhzhr.superdbg.gift.GiftPoolConfig saved = menu.getData().giftPool();
                if (saved != null) {
                    for (io.github.zgxhzhr.superdbg.gift.GiftPoolConfig.GiftEntry e : saved.entries()) {
                        GiftRowData row = new GiftRowData();
                        row.item = BuiltInRegistries.ITEM.get(e.item());
                        if (row.item == net.minecraft.world.item.Items.AIR) {
                            continue;
                        }
                        row.tag = e.tag() == null ? null : e.tag().copy();
                        row.minText = String.valueOf(e.minCount());
                        row.maxText = e.maxCount() == e.minCount() ? "" : String.valueOf(e.maxCount());
                        row.weightText = String.valueOf(e.weight());
                        giftDraft.add(row);
                    }
                }
            }
            addGiftPoolButton = Button.builder(Component.literal("添加一行"), b -> {
                syncGiftTexts();
                giftDraft.add(new GiftRowData());
                giftScroll = maxGiftScroll();
                rebuildGiftRows();
            }).bounds(leftPos + 160, topPos + 190, 88, 18).build();
            addRenderableWidget(addGiftPoolButton);
            addGiftPoolButton.visible = false;
            rebuildGiftRows();
        }

        // ---- 人是狐页：玩家专属（playermaid 已加载），初值取打开时的服务端快照 ----
        if (hasFoxPage) {
            var fox = menu.getData().foxMaid();

            foxActiveBox = new Checkbox(leftPos + 150, 0, 20, 16,
                    Component.empty(), fox.active());
            foxActiveBox.setTooltip(Tooltip.create(Component.literal(
                    "勾选后该玩家按 E 打开女仆界面，其他玩家非潜行右键可查看/操作")));
            addRenderableWidget(foxActiveBox);
            addFoxRow("人是狐开关", foxActiveBox);

            foxOwnerBox = new EditBox(font, leftPos + 148, 0, 100, 16,
                    Component.literal("主人"));
            foxOwnerBox.setMaxLength(64);
            if (fox.ownerName() != null) {
                foxOwnerBox.setValue(fox.ownerName());
            }
            addRenderableWidget(foxOwnerBox);
            addFoxRow("主人", foxOwnerBox);

            foxFavorBox = new EditBox(font, leftPos + 150, 0, 60, 16,
                    Component.literal("好感度点数"));
            foxFavorBox.setMaxLength(3);
            foxFavorBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,3}"));
            foxFavorBox.setValue(String.valueOf(Math.max(0,
                    Math.min(io.github.zgxhzhr.superdbg.compat.playermaid.PlayerMaidCompat.MAX_FAVORABILITY,
                            fox.favorability()))));
            foxFavorBox.setTooltip(Tooltip.create(Component.literal(
                    "0-384；阈值 64/192/384 对应等级 0/1/2/3")));
            addRenderableWidget(foxFavorBox);
            addFoxRow("好感度点数(0-384)", foxFavorBox);

            // 魂符展示模型：打开车万女仆（Touhou Little Maid，作者 TartaricAcid，MIT 协议开源）
            // 的可视化模型选择界面（模型列表反射自 CustomPackLoader.MAID_MODELS）
            foxSlabModels.addAll(collectMaidModelIds());
            boolean hasModels = !foxSlabModels.isEmpty();
            foxSlabModelSelected = (fox != null && fox.slabModelId() != null) ? fox.slabModelId() : "";
            // 已存值不在列表里则补进列表，保证可视化界面里能重新选回原值
            if (hasModels && !foxSlabModelSelected.isEmpty() && !foxSlabModels.contains(foxSlabModelSelected)) {
                foxSlabModels.add(foxSlabModelSelected);
            }
            foxSlabModelButton = Button.builder(
                    Component.literal(truncateForButton(foxSlabModelSelected)), b -> {
                        if (!foxSlabModels.isEmpty()) {
                            minecraft.setScreen(new MaidSkinPickerScreen(
                                    foxSlabModels, foxSlabModelSelected, this::onSlabModelPicked));
                        }
                    }).bounds(leftPos + 148, 0, 100, 16).build();
            if (!hasModels) {
                // 车万女仆未安装或模型列表读取失败：按钮退化为禁用态
                foxSlabModelButton.active = false;
                foxSlabModelButton.setMessage(Component.literal("（无模型可选）"));
            }
            foxSlabModelButton.setTooltip(Tooltip.create(Component.literal(
                    "被收容进魂符后，魂符预览展示的车万女仆模型 id；点击打开可视化选择界面，留空则不渲染模型")));
            addRenderableWidget(foxSlabModelButton);
            addFoxRow("魂符展示模型", foxSlabModelButton);

            foxSchedule = fox.schedule() == null ? "DAY" : fox.schedule();
            foxScheduleButton = Button.builder(
                    Component.literal(scheduleDisplayName(foxSchedule)), b -> {
                        foxSchedule = nextSchedule(foxSchedule);
                        b.setMessage(Component.literal(scheduleDisplayName(foxSchedule)));
                    }).bounds(leftPos + 150, 0, 60, 16).build();
            addRenderableWidget(foxScheduleButton);
            addFoxRow("日程模式", foxScheduleButton);

            foxInvulnBox = new Checkbox(leftPos + 150, 0, 20, 16,
                    Component.empty(), fox.invulnerable());
            addRenderableWidget(foxInvulnBox);
            addFoxRow("无敌(展示属性)", foxInvulnBox);
        }

        // ---- 杂项页：移除实体 / 防移除 / 伪创造（原底部公共区的移除与防移除迁移至此）----
        removeBox = new Checkbox(leftPos + 12, topPos + CONTENT_TOP + 1, 110, 18,
                Component.literal("移除实体"), false);
        // 女仆受保护：界面不提供移除入口（服务端另有兜底校验）
        removeBox.setTooltip(Tooltip.create(Component.literal(
                "勾选后点确认：生物直接移除，玩家走死亡流程")));
        addRenderableWidget(removeBox);

        // 防移除：指令/其他模组清除失效，正常伤害仍可杀死
        removalGuardBox = new Checkbox(leftPos + 12, topPos + CONTENT_TOP + ROW_HEIGHT + 1,
                110, 18, Component.literal("防移除"), menu.getData().removalGuard());
        removalGuardBox.setTooltip(Tooltip.create(Component.literal(
                "指令/其他模组清除失效，正常伤害仍可杀死")));
        addRenderableWidget(removalGuardBox);

        // 伪创造模式：仅玩家目标显示；受击流程正常但生命值不掉（勾选即时发送，不进主提交）
        if (isPlayerTarget) {
            pseudoCreativeBox = new Checkbox(leftPos + 12, topPos + CONTENT_TOP + ROW_HEIGHT * 2 + 1,
                    110, 18, Component.literal("伪创造模式"), menu.getData().pseudoCreative()) {
                @Override
                public void onPress() {
                    super.onPress();
                    superdbg$sendPseudoCreative();
                }
            };
            pseudoCreativeBox.setTooltip(Tooltip.create(Component.literal(
                    "开启后受击流程完全正常（索敌/命中/音效/击退/红闪照旧），只是生命值不掉："
                            + "创造模式锁血为开启瞬间值，生存模式受击后自动拉回；"
                            + "重进世界仍生效（持久化）")));
            addRenderableWidget(pseudoCreativeBox);
        }

        // 当前生命值（仅属性页显示），整数输入，服务端钳制到 [0, 最大生命]
        healthBox = new EditBox(font, leftPos + 190, topPos + 191, 54, 16,
                Component.literal("当前生命"));
        healthBox.setMaxLength(10);
        healthBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,10}"));
        healthBox.setValue(String.valueOf(Math.max(0, Math.round(menu.getData().health()))));
        // 女仆：输入无意义，服务端固定回满，置灰提示
        int targetId = menu.getData().entityId();
        net.minecraft.world.entity.Entity targetEntity =
                minecraft.level == null ? null : minecraft.level.getEntity(targetId);
        maidFullHealth = targetEntity instanceof net.minecraft.world.entity.LivingEntity le
                && io.github.zgxhzhr.superdbg.entity.EntityEditorService.isTouhouMaid(le);
        if (maidFullHealth) {
            healthBox.setEditable(false);
            healthBox.setTooltip(Tooltip.create(Component.literal("女仆的当前血量始终回满，无需输入")));
        }
        addRenderableWidget(healthBox);

        // 玩家渲染名：由调试器本体存储与渲染，不依赖任何第三方模组；任意页签都可见可改。
        // 更改玩家显示名（头顶名牌、Tab 列表、聊天与死亡消息同步），不改真实名字/UUID。
        // 发送时机：焦点框按回车，或关闭界面（ESC/取消）时若值有变化再补发，避免每敲一键发包。
        if (isPlayerTarget) {
            foxRenderBox = new EditBox(font, leftPos + 154, topPos + 210, 96, 16,
                    Component.literal("渲染名"));
            foxRenderBox.setMaxLength(64);
            if (menu.getData().renderName() != null) {
                foxRenderBox.setValue(menu.getData().renderName());
            }
            // 初始化后记录“已发送”基线（快照值），避免初始误发
            lastSentRenderName = menu.getData().renderName() == null
                    ? "" : menu.getData().renderName().trim();
            foxRenderBox.setTooltip(Tooltip.create(Component.literal(
                    "更改玩家显示名，同步到头顶名牌、Tab 列表、聊天与死亡消息；留空则恢复真实名")));
            addRenderableWidget(foxRenderBox);
        }

        addEffectButton = Button.builder(Component.literal("添加效果"), b -> openPicker())
                .bounds(leftPos + 160, topPos + 190, 88, 18)
                .build();
        addRenderableWidget(addEffectButton);
        addEffectButton.visible = false;

        addTradeButton = Button.builder(Component.literal("新增交易"),
                        b -> minecraft.setScreen(new TradeEditScreen(this, -1, null)))
                .bounds(leftPos + 160, topPos + 190, 88, 18)
                .build();
        addRenderableWidget(addTradeButton);
        addTradeButton.visible = false;

        addGiveButton = Button.builder(Component.literal("添加一行"), b -> {
                    superdbg$ensureGiveDrafts();
                    giveDrafts.add(new GiveRowData());
                    giveScroll = maxGiveScroll();
                    rebuildGiveRows();
                }).bounds(leftPos + 124, topPos + 190, 56, 18).build();
        addRenderableWidget(addGiveButton);
        addGiveButton.visible = false;

        giveButton = Button.builder(Component.literal("发放给玩家"), b -> giveItems())
                .bounds(leftPos + 186, topPos + 190, 62, 18).build();
        giveButton.setTooltip(Tooltip.create(Component.literal(
                "立即按列表发放，不进主提交；物品进背包空槽，满了掉在脚下")));
        addRenderableWidget(giveButton);
        giveButton.visible = false;

        confirmButton = Button.builder(Component.literal("确认"), b -> submit())
                .bounds(leftPos + 56, topPos + 230, 60, 18)
                .build();
        addRenderableWidget(confirmButton);

        cancelButton = Button.builder(Component.literal("取消"), b -> onClose())
                .bounds(leftPos + 124, topPos + 230, 60, 18)
                .build();
        addRenderableWidget(cancelButton);

        // 跳转 gamerule 编辑器：面板外紧贴左侧，独立按钮。仅 creative 可见。
        net.minecraft.world.entity.player.Player self = minecraft.player;
        boolean canEditGamerule = self != null && self.isCreative();
        gameruleButton = Button.builder(Component.literal("游戏规则"), b -> {
            NetworkHandler.CHANNEL.sendToServer(new io.github.zgxhzhr.superdbg.network.RequestGameRuleEditorPacket());
        }).bounds(leftPos - 62, topPos + 208, 60, 18).build();
        gameruleButton.visible = canEditGamerule;
        gameruleButton.setTooltip(Tooltip.create(Component.literal("编辑世界 gamerule（仅 OP 可用）")));
        addRenderableWidget(gameruleButton);
    }

    private void setTab(Tab newTab) {
        this.tab = newTab;
        closePicker();
        closeTabList();
        tabButton.setMessage(Component.literal("页面：" + newTab.label + " ▾"));
        for (var w : attrWidgets) {
            w.visible = newTab == Tab.ATTR;
        }
        healthBox.visible = newTab == Tab.ATTR;
        addEffectButton.visible = newTab == Tab.EFFECT;
        for (var w : traitWidgets) {
            w.visible = newTab == Tab.TRAIT;
        }
        levelBox.visible = newTab == Tab.TRAIT;
        for (var w : curiosWidgets) {
            w.visible = newTab == Tab.CURIOS;
        }
        for (var w : bondWidgets) {
            w.visible = newTab == Tab.BOND;
        }
        if (addTradeButton != null) {
            addTradeButton.visible = newTab == Tab.TRADE;
        }
        boolean give = newTab == Tab.GIVE;
        if (addGiveButton != null) {
            addGiveButton.visible = give;
        }
        if (giveButton != null) {
            giveButton.visible = give;
        }
        boolean loot = newTab == Tab.LOOT;
        if (lootScopeButton != null) {
            lootScopeButton.visible = loot;
        }
        if (lootModeButton != null) {
            lootModeButton.visible = loot;
        }
        if (lootEnabledButton != null) {
            lootEnabledButton.visible = loot;
        }
        if (addLootButton != null) {
            addLootButton.visible = loot;
        }
        boolean gift = newTab == Tab.GIFT;
        if (addGiftPoolButton != null) {
            addGiftPoolButton.visible = gift;
        }
        for (var w : foxWidgets) {
            w.visible = newTab == Tab.FOX;
        }
        updateAttrVisibility();
        updateEffectVisibility();
        updateTraitVisibility();
        updateCuriosVisibility();
        updateBondVisibility();
        updateTradeVisibility();
        updateGiveVisibility();
        updateLootVisibility();
        updateGiftVisibility();
        updateFoxVisibility();
        updateMiscVisibility();
        setFocused(null);
    }

    // ==================== 页签下拉列表 ====================

    /** 展开/收起页签下拉列表（展开时内容区遮挡由渲染层处理） */
    private void toggleTabList() {
        if (tabListOpen) {
            closeTabList();
            return;
        }
        // 效果选择覆盖层与下拉列表同区会重叠，互斥
        closePicker();
        tabListOpen = true;
        // 展开期间隐藏全部内容区控件：下拉选项与属性/效果等输入框坐标重叠，
        // 不隐藏的话输入框在控件列表里靠前，点击会先被输入框吃掉（点不到选项）
        for (AbstractWidget w : attrWidgets) {
            w.visible = false;
        }
        healthBox.visible = false;
        for (AbstractWidget w : effectWidgets) {
            w.visible = false;
        }
        addEffectButton.visible = false;
        for (AbstractWidget w : traitWidgets) {
            w.visible = false;
        }
        levelBox.visible = false;
        for (AbstractWidget w : curiosWidgets) {
            w.visible = false;
        }
        for (AbstractWidget w : bondWidgets) {
            w.visible = false;
        }
        for (AbstractWidget w : tradeWidgets) {
            w.visible = false;
        }
        if (addTradeButton != null) {
            addTradeButton.visible = false;
        }
        for (AbstractWidget w : giveWidgets) {
            w.visible = false;
        }
        if (addGiveButton != null) {
            addGiveButton.visible = false;
        }
        if (giveButton != null) {
            giveButton.visible = false;
        }
        for (AbstractWidget w : lootWidgets) {
            w.visible = false;
        }
        if (lootScopeButton != null) {
            lootScopeButton.visible = false;
        }
        if (lootModeButton != null) {
            lootModeButton.visible = false;
        }
        if (lootEnabledButton != null) {
            lootEnabledButton.visible = false;
        }
        if (addLootButton != null) {
            addLootButton.visible = false;
        }
        for (AbstractWidget w : giftWidgets) {
            w.visible = false;
        }
        if (addGiftPoolButton != null) {
            addGiftPoolButton.visible = false;
        }
        for (AbstractWidget w : foxWidgets) {
            w.visible = false;
        }
        // 杂项页控件位于内容区（与下拉列表重叠），展开时必须隐藏
        if (removeBox != null) {
            removeBox.visible = false;
        }
        if (removalGuardBox != null) {
            removalGuardBox.visible = false;
        }
        if (pseudoCreativeBox != null) {
            pseudoCreativeBox.visible = false;
        }
        // 每次展开都重建选项按钮：若复用旧按钮（上次 init 全量重建后已不在控件列表里），
        // 会再次 addRenderableWidget 造成 children 重复注册——每开关一次同一按钮多一份，
        // 渲染与命中检测迭代重复控件，表现为下拉框"越来越长"且点不中
        for (AbstractWidget w : tabListWidgets) {
            removeWidget(w);
        }
        tabListWidgets.clear();
        int y = topPos + 20;
        for (Tab t : availableTabs) {
            Button opt = Button.builder(t.label(), b -> pickTab(t))
                    .bounds(leftPos + 182, y, 66, 14).build();
            addRenderableWidget(opt);
            tabListWidgets.add(opt);
            y += 15;
        }
        for (AbstractWidget w : tabListWidgets) {
            w.visible = true;
        }
        // 当前页签对应的选项置灰，当作高亮指示
        for (int i = 0; i < tabListWidgets.size() && i < availableTabs.size(); i++) {
            tabListWidgets.get(i).active = availableTabs.get(i) != tab;
        }
    }

    private void closeTabList() {
        if (!tabListOpen) {
            return;
        }
        tabListOpen = false;
        for (AbstractWidget w : tabListWidgets) {
            w.visible = false;
        }
        // 恢复当前页签的内容区控件可见性（setTab 内会再次调用 closeTabList，此时已闭合直接返回）
        setTab(tab);
    }

    private void pickTab(Tab t) {
        closeTabList();
        setTab(t);
    }

    // ==================== 属性页滚动 ====================

    private int maxAttrScroll() {
        return Math.max(0, attrBoxes.size() - VISIBLE_ROWS);
    }

    private void updateAttrVisibility() {
        for (int i = 0; i < attrBoxes.size(); i++) {
            boolean visible = tab == Tab.ATTR && i >= attrScroll && i < attrScroll + VISIBLE_ROWS;
            attrBoxes.get(i).visible = visible;
            if (visible) {
                attrBoxes.get(i).setY(topPos + CONTENT_TOP + (i - attrScroll) * ROW_HEIGHT + 1);
            }
        }
    }

    // ==================== 效果页 ====================

    private void addEffectRow(MobEffect effect, int amplifier, int duration) {
        ResourceLocation rl = BuiltInRegistries.MOB_EFFECT.getKey(effect);

        EditBox ampBox = new EditBox(font, leftPos + 118, 0, 40, 16, Component.literal("等级"));
        // MobEffectInstanceMixin 已放开 byte 序列化瓶颈，支持 0-Integer.MAX_VALUE
        ampBox.setMaxLength(10);
        ampBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,10}"));
        ampBox.setValue(String.valueOf(amplifier));

        EditBox durBox = new EditBox(font, leftPos + 168, 0, 44, 16, Component.literal("时长"));
        durBox.setMaxLength(7);
        durBox.setFilter(s -> s.isEmpty() || s.equals("-") || s.matches("-?\\d{1,7}"));
        durBox.setValue(String.valueOf(duration));

        // 删除按钮通过 holder 闭包捕获行对象（而非行索引），删中间行无需重建其它按钮
        EffectRow[] holder = new EffectRow[1];
        Button delBtn = Button.builder(Component.literal("删"), b -> {
            EffectRow row = holder[0];
            if (row != null) {
                removeEffectRow(row);
            }
        }).bounds(leftPos + 218, 0, 30, 16).build();

        addRenderableWidget(ampBox);
        addRenderableWidget(durBox);
        addRenderableWidget(delBtn);

        effectWidgets.add(ampBox);
        effectWidgets.add(durBox);
        effectWidgets.add(delBtn);
        EffectRow row = new EffectRow(rl, ampBox, durBox, delBtn);
        effectRows.add(row);
        holder[0] = row;

        if (tab == Tab.EFFECT) {
            updateEffectVisibility();
        }
    }

    private void removeEffectRow(EffectRow row) {
        effectRows.remove(row);
        removeWidget(row.amplifier());
        removeWidget(row.duration());
        removeWidget(row.delete());
        effectWidgets.remove(row.amplifier());
        effectWidgets.remove(row.duration());
        effectWidgets.remove(row.delete());
        if (effectScroll > maxEffectScroll()) {
            effectScroll = maxEffectScroll();
        }
        updateEffectVisibility();
        setFocused(null);
    }

    private int maxEffectScroll() {
        return Math.max(0, effectRows.size() - VISIBLE_ROWS);
    }

    private void updateEffectVisibility() {
        for (int i = 0; i < effectRows.size(); i++) {
            EffectRow row = effectRows.get(i);
            boolean visible = tab == Tab.EFFECT && !pickerOpen
                    && i >= effectScroll && i < effectScroll + VISIBLE_ROWS;
            row.amplifier().visible = visible;
            row.duration().visible = visible;
            row.delete().visible = visible;
            if (visible) {
                int y = topPos + CONTENT_TOP + (i - effectScroll) * ROW_HEIGHT + 1;
                row.amplifier().setY(y);
                row.duration().setY(y);
                row.delete().setY(y);
            }
        }
    }

    // ==================== 词条页滚动 ====================

    private int maxTraitScroll() {
        return Math.max(0, traitBoxes.size() - VISIBLE_ROWS);
    }

    private void updateTraitVisibility() {
        for (int i = 0; i < traitBoxes.size(); i++) {
            EditBox box = traitBoxes.get(i);
            boolean visible = tab == Tab.TRAIT && i >= traitScroll && i < traitScroll + VISIBLE_ROWS;
            box.visible = visible;
            if (visible) {
                box.setY(topPos + CONTENT_TOP + (i - traitScroll) * ROW_HEIGHT + 1);
            }
        }
    }

    // ==================== 饰品页滚动 ====================

    private int maxCuriosScroll() {
        return Math.max(0, curiosBoxes.size() - VISIBLE_ROWS);
    }

    private void updateCuriosVisibility() {
        for (int i = 0; i < curiosBoxes.size(); i++) {
            EditBox box = curiosBoxes.get(i);
            boolean visible = tab == Tab.CURIOS && i >= curiosScroll && i < curiosScroll + VISIBLE_ROWS;
            box.visible = visible;
            if (visible) {
                box.setY(topPos + CONTENT_TOP + (i - curiosScroll) * ROW_HEIGHT + 1);
            }
        }
    }

    // ==================== 羁绊页 ====================

    private void addBondRow(String label, AbstractWidget widget) {
        bondRowLabels.add(label);
        bondWidgets.add(widget);
        widget.visible = false;
    }

    /** 能力 id → TMA 翻译显示名；翻译缺失时回退原始 id */
    private static String abilityDisplayName(String id) {
        String key = switch (id) {
            case "lap_pillow" -> "bond.ability.lap";
            case "emergency_heal" -> "bond.ability.heal";
            case "morning_kiss" -> "bond.ability.kiss";
            case "random_gift" -> "bond.ability.gift";
            case "ysm_action" -> "bond.ability.ysm";
            default -> null;
        };
        if (key == null) {
            return id;
        }
        String text = Component.translatable(key).getString();
        // 翻译缺失时 translatable 原样返回键名
        return key.equals(text) ? id : text;
    }

    private int maxBondScroll() {
        return Math.max(0, bondWidgets.size() - VISIBLE_ROWS);
    }

    private void updateBondVisibility() {
        for (int i = 0; i < bondWidgets.size(); i++) {
            AbstractWidget widget = bondWidgets.get(i);
            boolean visible = tab == Tab.BOND && i >= bondScroll && i < bondScroll + VISIBLE_ROWS;
            widget.visible = visible;
            if (visible) {
                widget.setY(topPos + CONTENT_TOP + (i - bondScroll) * ROW_HEIGHT + 1);
            }
        }
    }

    // ==================== 人是狐页 ====================

    private void addFoxRow(String label, AbstractWidget widget) {
        foxRowLabels.add(label);
        foxWidgets.add(widget);
        widget.visible = false;
    }

    private int maxFoxScroll() {
        return Math.max(0, foxWidgets.size() - VISIBLE_ROWS);
    }

    private void updateFoxVisibility() {
        for (int i = 0; i < foxWidgets.size(); i++) {
            AbstractWidget widget = foxWidgets.get(i);
            boolean visible = tab == Tab.FOX && i >= foxScroll && i < foxScroll + VISIBLE_ROWS;
            widget.visible = visible;
            if (visible) {
                widget.setY(topPos + CONTENT_TOP + (i - foxScroll) * ROW_HEIGHT + 1);
            }
        }
    }

    // ==================== 杂项页 ====================

    /** 杂项页控件可见性：移除仅对可移除目标，伪创造仅对玩家目标（控件未构建时跳过） */
    private void updateMiscVisibility() {
        boolean visible = tab == Tab.MISC && !tabListOpen;
        if (removeBox != null) {
            removeBox.visible = visible && menu.getData().removable();
        }
        if (removalGuardBox != null) {
            removalGuardBox.visible = visible;
        }
        if (pseudoCreativeBox != null) {
            pseudoCreativeBox.visible = visible;
        }
    }

    /** 伪创造开关变化即时发送（不进主提交、不关界面） */
    private void superdbg$sendPseudoCreative() {
        if (pseudoCreativeBox == null) {
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(
                new io.github.zgxhzhr.superdbg.network.SetPseudoCreativePacket(
                        menu.getEntityId(), pseudoCreativeBox.selected()));
    }

    /** 日程模式名 → 中文显示 */
    private static String scheduleDisplayName(String name) {
        return switch (name) {
            case "NIGHT" -> "夜晚";
            case "ALL" -> "全天";
            default -> "白天";
        };
    }

    /** 日程按 白天 → 夜晚 → 全天 → 白天 循环 */
    private static String nextSchedule(String name) {
        return switch (name) {
            case "DAY" -> "NIGHT";
            case "NIGHT" -> "ALL";
            default -> "DAY";
        };
    }

    /** 可视化模型选择界面的选中回调：把选中的模型 id 存为当前选中值并刷新按钮文本。 */
    private void onSlabModelPicked(String modelId) {
        foxSlabModelSelected = modelId;
        if (foxSlabModelButton != null) {
            foxSlabModelButton.setMessage(Component.literal(truncateForButton(foxSlabModelSelected)));
        }
        // 点选后立即发送到服务端持久化（不依赖保存按钮）；保存按钮的全量提交仍会再写一次，幂等
        NetworkHandler.CHANNEL.sendToServer(
                new io.github.zgxhzhr.superdbg.network.SetFoxSlabModelPacket(menu.getEntityId(), modelId));
    }

    /** 按钮文本截断：按按钮宽度（100px）截断，避免超长模型 id 溢出按钮。 */
    private String truncateForButton(String text) {
        if (text.isEmpty()) {
            return text;
        }
        return font.plainSubstrByWidth(text, 94);
    }

    /**
     * 反射读取车万女仆（Touhou Little Maid，作者 TartaricAcid，MIT 协议开源）
     * 已加载的女仆模型 id 列表（元素为 {@code namespace:path} 完整模型 id，
     * 与 {@code EntityMaid#setModelId} 期望的格式一致）。
     *
     * <p>取 {@code CustomPackLoader.MAID_MODELS}（MaidModels 单例）上「无参且返回
     * Collection/Set」的方法调用。优先精确方法名 {@code getModelIdSet()}（车万女仆
     * 自有方法，生产不混淆）；签名兜底时优先泛型元素为 String / ResourceLocation
     * 的方法，避免误取到返回模型包列表的 {@code getPackList()}（其泛型元素是包对象
     * 而非模型 id）。任一环节失败均返回空列表，由调用方把按钮置为禁用态。</p>
     */
    @SuppressWarnings("unchecked")
    private static List<String> collectMaidModelIds() {
        try {
            Class<?> loader = Class.forName("com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader");
            Field maidModelsField = loader.getDeclaredField("MAID_MODELS");
            maidModelsField.setAccessible(true);
            Object maidModels = maidModelsField.get(null);
            if (maidModels == null) {
                return List.of();
            }
            Method best = null;
            // 优先精确方法名 getModelIdSet（车万女仆自有方法名，生产 jar 不混淆）
            try {
                best = maidModels.getClass().getMethod("getModelIdSet");
            } catch (NoSuchMethodException ignored) {
            }
            if (best == null) {
                // 按签名兜底：无参且返回 Collection/Set；优先泛型元素为 String 或 ResourceLocation 的方法
                for (Method method : maidModels.getClass().getMethods()) {
                    if (method.getParameterCount() != 0) {
                        continue;
                    }
                    Class<?> ret = method.getReturnType();
                    if (!Collection.class.isAssignableFrom(ret)) {
                        continue;
                    }
                    if (best == null) {
                        best = method;
                    }
                    if (isStringOrResourceLocationCollection(method) && !isStringOrResourceLocationCollection(best)) {
                        best = method;
                    }
                }
            }
            if (best == null) {
                return List.of();
            }
            Object result = best.invoke(maidModels);
            if (!(result instanceof Collection<?> coll)) {
                return List.of();
            }
            List<String> ids = new ArrayList<>();
            for (Object item : coll) {
                if (item != null) {
                    ids.add(item.toString());
                }
            }
            // 打印收集到的完整模型 id 列表概况，便于确认可选模型（如 winefox 系列）是否在列
            io.github.zgxhzhr.superdbg.Constants.LOG.info(
                    "[SuperDbg] EntityEditorScreen.collectMaidModelIds: 收集到 {} 个模型 id，前 20 项: {}",
                    ids.size(), ids.subList(0, Math.min(20, ids.size())));
            return ids;
        } catch (Throwable t) {
            // 车万女仆未安装 / 字段或方法不可用：返回空列表，调用方禁用按钮
            return List.of();
        }
    }

    /** 判断方法返回类型是否为「泛型元素为 String 或 ResourceLocation 的 Collection/Set」。 */
    private static boolean isStringOrResourceLocationCollection(Method method) {
        Type generic = method.getGenericReturnType();
        if (generic instanceof ParameterizedType type && type.getActualTypeArguments().length == 1) {
            Type arg = type.getActualTypeArguments()[0];
            if (arg instanceof Class<?> clazz) {
                return String.class.isAssignableFrom(clazz) || ResourceLocation.class.isAssignableFrom(clazz);
            }
        }
        return false;
    }

    // ==================== 交易页 ====================

    /**
     * 按 {@link #tradesDraft} 重建交易行控件（首次构建与子屏返回/删除后调用）。
     * 每行两个按钮：改 / 删。
     */
    private void rebuildTradeRows() {
        for (AbstractWidget w : tradeWidgets) {
            removeWidget(w);
        }
        tradeWidgets.clear();
        if (tradesDraft == null) {
            return;
        }
        for (int i = 0; i < tradesDraft.size(); i++) {
            final int idx = i;
            Button editBtn = Button.builder(Component.literal("改"),
                            b -> minecraft.setScreen(new TradeEditScreen(this, idx, tradesDraft.get(idx))))
                    .bounds(leftPos + 150, 0, 46, 16).build();
            Button delBtn = Button.builder(Component.literal("删"), b -> {
                tradesDraft.remove(idx);
                if (tradeScroll > maxTradeScroll()) {
                    tradeScroll = maxTradeScroll();
                }
                rebuildTradeRows();
                updateTradeVisibility();
                setFocused(null);
            }).bounds(leftPos + 204, 0, 40, 16).build();
            addRenderableWidget(editBtn);
            addRenderableWidget(delBtn);
            tradeWidgets.add(editBtn);
            tradeWidgets.add(delBtn);
        }
        if (tradeScroll > maxTradeScroll()) {
            tradeScroll = maxTradeScroll();
        }
        updateTradeVisibility();
    }

    /** 交易编辑子屏保存回调：新增（index &lt; 0）或替换一行 */
    public void upsertTrade(int index, EntityEditorData.TradeEntry entry) {
        if (tradesDraft == null) {
            return;
        }
        if (index < 0) {
            tradesDraft.add(entry);
        } else if (index < tradesDraft.size()) {
            tradesDraft.set(index, entry);
        }
    }

    private int maxTradeScroll() {
        // 每行两个控件
        return Math.max(0, tradeWidgets.size() / 2 - VISIBLE_ROWS);
    }

    private void updateTradeVisibility() {
        int rows = tradeWidgets.size() / 2;
        for (int i = 0; i < rows; i++) {
            boolean visible = tab == Tab.TRADE && i >= tradeScroll && i < tradeScroll + VISIBLE_ROWS;
            AbstractWidget edit = tradeWidgets.get(i * 2);
            AbstractWidget del = tradeWidgets.get(i * 2 + 1);
            edit.visible = visible;
            del.visible = visible;
            if (visible) {
                int y = topPos + CONTENT_TOP + (i - tradeScroll) * ROW_HEIGHT + 1;
                edit.setY(y);
                del.setY(y);
            }
        }
    }

    /** 交易行文字描述：买入物A xN [+ 买入物B xM] → 卖出物 xK（次数） */
    private String tradeDescription(EntityEditorData.TradeEntry t) {
        StringBuilder sb = new StringBuilder();
        sb.append(itemNameWithTag(t.costA(), t.costATag())).append('x').append(t.countA());
        if (t.hasCostB() && t.costB() != null) {
            sb.append(" + ").append(itemNameWithTag(t.costB(), t.costBTag())).append('x').append(t.countB());
        }
        sb.append(" → ").append(itemNameWithTag(t.result(), t.resultTag())).append('x').append(t.countR());
        sb.append("（").append(Math.max(0, t.maxUses())).append("次");
        if (t.xp() > 0) {
            sb.append("/经验").append(t.xp());
        }
        sb.append('）');
        return sb.toString();
    }

    /**
     * 物品显示名；带 NBT 时追加附魔明细，如"附魔书（锋利 V、耐久 III）"。
     * 附魔书读 StoredEnchantments，其他附魔物品读 Enchantments。
     * 供本屏交易列表与 {@link TradeEditScreen} 槽位共用。
     */
    public static String itemNameWithTag(ResourceLocation id, net.minecraft.nbt.CompoundTag tag) {
        if (id == null) {
            return "?";
        }
        net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(id);
        String base = item == net.minecraft.world.item.Items.AIR ? id.getPath()
                : item.getDescription().getString();
        if (tag == null) {
            return base;
        }
        net.minecraft.nbt.ListTag ench;
        if (item == net.minecraft.world.item.Items.ENCHANTED_BOOK) {
            net.minecraft.world.item.ItemStack probe = new net.minecraft.world.item.ItemStack(item);
            probe.setTag(tag.copy());
            ench = net.minecraft.world.item.EnchantedBookItem.getEnchantments(probe);
        } else {
            ench = tag.getList("Enchantments", net.minecraft.nbt.Tag.TAG_COMPOUND);
        }
        if (ench.isEmpty()) {
            return base;
        }
        java.util.List<String> names = new ArrayList<>();
        for (int i = 0; i < ench.size(); i++) {
            net.minecraft.nbt.CompoundTag et = ench.getCompound(i);
            ResourceLocation enchId = ResourceLocation.tryParse(et.getString("id"));
            net.minecraft.world.item.enchantment.Enchantment enchantment = enchId == null ? null
                    : BuiltInRegistries.ENCHANTMENT.get(enchId);
            if (enchantment != null) {
                names.add(enchantment.getFullname(et.getInt("lvl")).getString());
            }
        }
        return names.isEmpty() ? base : base + "（" + String.join("、", names) + "）";
    }

    private static String itemName(ResourceLocation id) {
        if (id == null) {
            return "?";
        }
        var item = BuiltInRegistries.ITEM.get(id);
        return item == net.minecraft.world.item.Items.AIR ? id.getPath()
                : item.getDescription().getString();
    }

    // ==================== 获取页 ====================

    /** 首次使用初始化草稿（静态字段为 null 时给一行空白行） */
    private static void superdbg$ensureGiveDrafts() {
        if (giveDrafts == null) {
            giveDrafts = new ArrayList<>();
            giveDrafts.add(new GiveRowData());
        }
    }

    /** 获取页一行草稿：物品（含 NBT）+ 总数/每组文本（文本留空在发放时按 1 处理） */
    private static final class GiveRowData {
        net.minecraft.world.item.Item item;
        net.minecraft.nbt.CompoundTag tag;
        String countText = "64";
        String stackText = "";
    }

    /** 按 {@link #giveDrafts} 重建获取页行控件（首次构建与物品选择器返回后调用） */
    private void rebuildGiveRows() {
        for (AbstractWidget w : giveWidgets) {
            removeWidget(w);
        }
        giveWidgets.clear();
        if (giveDrafts == null) {
            return;
        }
        for (int i = 0; i < giveDrafts.size(); i++) {
            final int idx = i;
            GiveRowData data = giveDrafts.get(i);

            // 物品选择按钮：选择器返回完整物品堆，物品与附魔等 NBT 一并保存；
            // 选中后默认每组=原版堆叠上限（按钮文字按宽度截断防溢出，全名见悬停提示）
            String itemLabel = data.item == null ? "点击选择…"
                    : itemNameWithTag(BuiltInRegistries.ITEM.getKey(data.item), data.tag);
            if (font.width(itemLabel) > 96) {
                itemLabel = font.plainSubstrByWidth(itemLabel, 90) + "…";
            }
            Button itemBtn = Button.builder(
                    Component.literal(itemLabel),
                    b -> {
                        superdbg$syncGiveTexts();
                        ItemPickerScreen.open(stack -> {
                            data.item = stack.getItem();
                            data.tag = stack.getTag();
                            if (data.stackText.isEmpty()) {
                                data.stackText = String.valueOf(
                                        Math.max(1, Math.min(999, stack.getMaxStackSize())));
                            }
                            minecraft.setScreen(EntityEditorScreen.this);
                        });
                    }).bounds(leftPos + 12, 0, 104, 16).build();
            if (data.item != null) {
                itemBtn.setTooltip(Tooltip.create(Component.literal(
                        itemNameWithTag(BuiltInRegistries.ITEM.getKey(data.item), data.tag))));
            }

            EditBox countBox = new EditBox(font, leftPos + 122, 0, 44, 16,
                    Component.literal("总数"));
            countBox.setMaxLength(6);
            countBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,6}"));
            countBox.setValue(data.countText);

            EditBox stackBox = new EditBox(font, leftPos + 172, 0, 32, 16,
                    Component.literal("每组"));
            stackBox.setMaxLength(3);
            stackBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,3}"));
            stackBox.setValue(data.stackText);

            Button delBtn = Button.builder(Component.literal("删"), b -> {
                superdbg$syncGiveTexts();
                giveDrafts.remove(idx);
                if (giveScroll > maxGiveScroll()) {
                    giveScroll = maxGiveScroll();
                }
                rebuildGiveRows();
                setFocused(null);
            }).bounds(leftPos + 210, 0, 34, 16).build();

            addRenderableWidget(itemBtn);
            addRenderableWidget(countBox);
            addRenderableWidget(stackBox);
            addRenderableWidget(delBtn);
            giveWidgets.add(itemBtn);
            giveWidgets.add(countBox);
            giveWidgets.add(stackBox);
            giveWidgets.add(delBtn);
        }
        if (giveScroll > maxGiveScroll()) {
            giveScroll = maxGiveScroll();
        }
        updateGiveVisibility();
    }

    /** 切去物品选择器前把控件文本回写草稿（控件重建不丢输入） */
    private void superdbg$syncGiveTexts() {
        if (giveDrafts == null) {
            return;
        }
        for (int i = 0; i < giveDrafts.size(); i++) {
            GiveRowData data = giveDrafts.get(i);
            int base = i * 4 + 1;
            if (base + 1 >= giveWidgets.size()) {
                break;
            }
            if (giveWidgets.get(base) instanceof EditBox countBox) {
                data.countText = countBox.getValue();
            }
            if (giveWidgets.get(base + 1) instanceof EditBox stackBox) {
                data.stackText = stackBox.getValue();
            }
        }
    }

    private int maxGiveScroll() {
        // 每行 4 个控件
        return Math.max(0, giveWidgets.size() / 4 - VISIBLE_ROWS);
    }

    private void updateGiveVisibility() {
        int rows = giveWidgets.size() / 4;
        for (int i = 0; i < rows; i++) {
            boolean visible = tab == Tab.GIVE && !tabListOpen
                    && i >= giveScroll && i < giveScroll + VISIBLE_ROWS;
            for (int c = 0; c < 4; c++) {
                AbstractWidget w = giveWidgets.get(i * 4 + c);
                w.visible = visible;
                if (visible) {
                    w.setY(topPos + CONTENT_TOP + (i - giveScroll) * ROW_HEIGHT + 1);
                }
            }
        }
    }

    /** 发放：收齐草稿校验后发 GiveItemPacket，不进主提交、不关界面 */
    private void giveItems() {
        superdbg$syncGiveTexts();
        if (giveDrafts == null) {
            return;
        }
        List<io.github.zgxhzhr.superdbg.network.GiveItemPacket.Entry> entries = new ArrayList<>();
        for (GiveRowData data : giveDrafts) {
            if (data.item == null) {
                continue;
            }
            int count = Math.max(1, parseIntOr(data.countText, 1));
            // 每组留空按原版堆叠上限（如雪球 16、末影珍珠 16、普通物品 64）
            int stackSize = data.stackText.isEmpty()
                    ? Math.max(1, Math.min(999, data.item.getMaxStackSize()))
                    : Math.max(1, Math.min(999, parseIntOr(data.stackText, 1)));
            entries.add(new io.github.zgxhzhr.superdbg.network.GiveItemPacket.Entry(
                    BuiltInRegistries.ITEM.getKey(data.item), count, stackSize,
                    data.tag == null ? null : data.tag.copy()));
        }
        if (entries.isEmpty()) {
            minecraft.player.displayClientMessage(
                    Component.literal("§e获取页没有可发放的行（先点「添加一行」并选择物品）"), false);
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(
                new io.github.zgxhzhr.superdbg.network.GiveItemPacket(
                        menu.getData().entityId(), entries));
    }

    // ==================== 掉落页 ====================

    /** 掉落页数据行可视行数（控制行占一行，比常规页少一行） */
    private static final int LOOT_VISIBLE_ROWS = 7;

    /** 掉落页一行草稿：物品（含 NBT）+ 最少/最多/概率/抢夺加成文本（留空语义见提交收集） */
    private static final class LootRowData {
        net.minecraft.world.item.Item item;
        net.minecraft.nbt.CompoundTag tag;
        String minText = "1";
        String maxText = "";
        String chanceText = "100";
        String lootChanceText = "0";
    }

    private List<LootRowData> superdbg$currentLootDraft() {
        return lootScope == 0 ? lootEntityDraft : lootTypeDraft;
    }

    /** 首次打开时从服务端快照初始化两份草稿（仅一次；玩家目标不调用） */
    private void superdbg$ensureLootDrafts() {
        if (lootEntityDraft != null) {
            return;
        }
        // 无覆盖配置时，草稿直接预填原版战利品表内容：
        // 行里显示的就是该生物现在实际会掉的东西，可直接改；
        // 覆盖开关仍为"关"，只有用户打开开关并保存才会真正写入覆盖。
        LootConfig entityConfig = menu.getData().entityLoot();
        lootEntityDraft = entityConfig != null
                ? lootRowsFrom(entityConfig)
                : lootRowsFromVanilla(menu.getData().vanillaLoot());
        lootEntityEnabled = entityConfig != null;
        if (entityConfig != null) {
            lootEntityMode = entityConfig.mode();
        }
        LootConfig typeConfig = menu.getData().typeLoot();
        lootTypeDraft = typeConfig != null
                ? lootRowsFrom(typeConfig)
                : lootRowsFromVanilla(menu.getData().vanillaLoot());
        lootTypeEnabled = typeConfig != null;
        if (typeConfig != null) {
            lootTypeMode = typeConfig.mode();
        }
        // 默认作用域对齐到"实际生效中"的覆盖：本实体覆盖优先（其存在即屏蔽全类型），
        // 否则若全类型覆盖开启则直接落在全类型页——
        // 不然打开同类另一只实体时停在预填原版掉落的本实体页，看不到已生效的全类型逻辑
        if (!lootEntityEnabled && lootTypeEnabled) {
            lootScope = 1;
        }
    }

    /** 原版战利品表快照 → 可编辑行（权重换算成百分比；多个池子的条目顺序拼接） */
    private static List<LootRowData> lootRowsFromVanilla(
            io.github.zgxhzhr.superdbg.loot.VanillaLootSnapshot vanilla) {
        List<LootRowData> rows = new ArrayList<>();
        if (vanilla == null) {
            return rows;
        }
        for (io.github.zgxhzhr.superdbg.loot.VanillaLootSnapshot.PoolView pool : vanilla.pools()) {
            for (io.github.zgxhzhr.superdbg.loot.VanillaLootSnapshot.ItemView iv : pool.items()) {
                if (rows.size() >= io.github.zgxhzhr.superdbg.loot.LootMath.MAX_ENTRIES) {
                    return rows;
                }
                net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(iv.item());
                if (item == net.minecraft.world.item.Items.AIR) {
                    continue;
                }
                LootRowData row = new LootRowData();
                row.item = item;
                row.minText = String.valueOf(iv.minCount());
                row.maxText = iv.maxCount() == iv.minCount()
                        ? "" : String.valueOf(iv.maxCount());
                // 概率已在服务端按"池内权重 × random_chance 条件"折算成实际掉落百分比
                row.chanceText = formatChance(iv.chancePercent());
                row.lootChanceText = formatChance(iv.lootingBonusPercent());
                rows.add(row);
            }
        }
        return rows;
    }

    private static List<LootRowData> lootRowsFrom(LootConfig config) {
        List<LootRowData> rows = new ArrayList<>();
        if (config == null) {
            return rows;
        }
        for (LootConfig.LootEntry entry : config.entries()) {
            net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(entry.item());
            if (item == net.minecraft.world.item.Items.AIR) {
                continue;
            }
            LootRowData row = new LootRowData();
            row.item = item;
            row.tag = entry.tag() == null ? null : entry.tag().copy();
            row.minText = String.valueOf(entry.minCount());
            row.maxText = entry.maxCount() == entry.minCount()
                    ? "" : String.valueOf(entry.maxCount());
            row.chanceText = formatChance(entry.chance());
            row.lootChanceText = formatChance(entry.lootingChanceBonus());
            rows.add(row);
        }
        return rows;
    }

    /** 概率显示：整数去小数点（100.0 → "100"，12.5 → "12.5"） */
    private static String formatChance(float value) {
        return value == (long) value ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** 按当前作用域刷新范围/模式/开关按钮文字与提示 */
    private void superdbg$refreshLootControls() {
        boolean entityScope = lootScope == 0;
        lootScopeButton.setMessage(Component.literal(entityScope ? "范围：本实体" : "范围：全类型"));
        ResourceLocation typeId = null;
        if (minecraft.level != null
                && minecraft.level.getEntity(menu.getData().entityId())
                        instanceof net.minecraft.world.entity.LivingEntity le) {
            typeId = BuiltInRegistries.ENTITY_TYPE.getKey(le.getType());
        }
        final ResourceLocation scopeTypeId = typeId;
        lootScopeButton.setTooltip(Tooltip.create(Component.literal(
                entityScope ? "只影响当前这一只实体"
                        : "影响世界上全部该类型实体（" + (scopeTypeId == null ? "未知类型" : scopeTypeId) + "）")));
        LootConfig.Mode mode = entityScope ? lootEntityMode : lootTypeMode;
        lootModeButton.setMessage(Component.literal(
                mode == LootConfig.Mode.REPLACE ? "替换原版" : "追加原版"));
        boolean enabled = entityScope ? lootEntityEnabled : lootTypeEnabled;
        lootEnabledButton.setMessage(Component.literal(enabled ? "覆盖：开" : "覆盖：关"));
    }

    /** 按当前作用域草稿重建掉落页行控件（首次构建与物品选择器返回后调用） */
    private void rebuildLootRows() {
        buildingLootRows = true;
        try {
            superdbg$rebuildLootRowsInner();
        } finally {
            buildingLootRows = false;
        }
    }

    /**
     * 用户对掉落行做了任何实质编辑（改文本/换物品/增删行）时，自动打开当前作用域的覆盖开关。
     * 否则界面预填的是原版掉落，用户改完直接保存会因"覆盖：关"而静默不生效。
     * 控件重建（setValue 触发 responder）期间由 {@link #buildingLootRows} 屏蔽。
     */
    private void markLootEdited() {
        if (buildingLootRows) {
            return;
        }
        if (lootScope == 0) {
            lootEntityEnabled = true;
        } else {
            lootTypeEnabled = true;
        }
        superdbg$refreshLootControls();
    }

    private void superdbg$rebuildLootRowsInner() {
        for (AbstractWidget w : lootWidgets) {
            removeWidget(w);
        }
        lootWidgets.clear();
        List<LootRowData> draft = superdbg$currentLootDraft();
        if (draft == null) {
            return;
        }
        for (int i = 0; i < draft.size(); i++) {
            final int idx = i;
            LootRowData data = draft.get(i);

            // 物品选择按钮：选择器返回完整物品堆，物品与附魔等 NBT 一并保存
            String itemLabel = data.item == null ? "点击选择…"
                    : itemNameWithTag(BuiltInRegistries.ITEM.getKey(data.item), data.tag);
            if (font.width(itemLabel) > 80) {
                itemLabel = font.plainSubstrByWidth(itemLabel, 74) + "…";
            }
            Button itemBtn = Button.builder(
                    Component.literal(itemLabel),
                    b -> {
                        superdbg$syncLootTexts();
                        ItemPickerScreen.open(stack -> {
                            data.item = stack.getItem();
                            data.tag = stack.getTag();
                            markLootEdited();
                            minecraft.setScreen(EntityEditorScreen.this);
                        });
                    }).bounds(leftPos + 12, 0, 86, 16).build();
            if (data.item != null) {
                itemBtn.setTooltip(Tooltip.create(Component.literal(
                        itemNameWithTag(BuiltInRegistries.ITEM.getKey(data.item), data.tag))));
            }

            EditBox minBox = new EditBox(font, leftPos + 100, 0, 26, 16,
                    Component.literal("最少"));
            minBox.setMaxLength(3);
            minBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,3}"));
            minBox.setValue(data.minText);
            minBox.setResponder(s -> markLootEdited());
            minBox.setTooltip(Tooltip.create(Component.literal("最少数量（0-999），留空按 0")));

            EditBox maxBox = new EditBox(font, leftPos + 130, 0, 26, 16,
                    Component.literal("最多"));
            maxBox.setMaxLength(3);
            maxBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,3}"));
            maxBox.setValue(data.maxText);
            maxBox.setResponder(s -> markLootEdited());
            maxBox.setTooltip(Tooltip.create(Component.literal("最大数量（0-999），留空=同最少")));

            EditBox chanceBox = new EditBox(font, leftPos + 160, 0, 32, 16,
                    Component.literal("概率"));
            chanceBox.setMaxLength(5);
            chanceBox.setFilter(s -> s.isEmpty() || s.matches("\\d{0,3}\\.?\\d{0,1}"));
            chanceBox.setValue(data.chanceText);
            chanceBox.setResponder(s -> markLootEdited());
            chanceBox.setTooltip(Tooltip.create(Component.literal("概率百分比（0-100，支持一位小数），留空按 100")));

            EditBox lootChanceBox = new EditBox(font, leftPos + 196, 0, 26, 16,
                    Component.literal("抢夺加成"));
            lootChanceBox.setMaxLength(5);
            lootChanceBox.setFilter(s -> s.isEmpty() || s.matches("\\d{0,3}\\.?\\d{0,1}"));
            lootChanceBox.setValue(data.lootChanceText);
            lootChanceBox.setResponder(s -> markLootEdited());
            lootChanceBox.setTooltip(Tooltip.create(Component.literal("每级抢夺追加的概率%，留空按 0")));

            Button delBtn = Button.builder(Component.literal("删"), b -> {
                superdbg$syncLootTexts();
                superdbg$currentLootDraft().remove(idx);
                markLootEdited();
                if (lootScroll > maxLootScroll()) {
                    lootScroll = maxLootScroll();
                }
                rebuildLootRows();
                setFocused(null);
            }).bounds(leftPos + 226, 0, 22, 16).build();

            addRenderableWidget(itemBtn);
            addRenderableWidget(minBox);
            addRenderableWidget(maxBox);
            addRenderableWidget(chanceBox);
            addRenderableWidget(lootChanceBox);
            addRenderableWidget(delBtn);
            lootWidgets.add(itemBtn);
            lootWidgets.add(minBox);
            lootWidgets.add(maxBox);
            lootWidgets.add(chanceBox);
            lootWidgets.add(lootChanceBox);
            lootWidgets.add(delBtn);
        }
        if (lootScroll > maxLootScroll()) {
            lootScroll = maxLootScroll();
        }
        updateLootVisibility();
    }

    /** 切作用域/物品选择器前把控件文本回写当前草稿（控件重建不丢输入） */
    private void superdbg$syncLootTexts() {
        List<LootRowData> draft = superdbg$currentLootDraft();
        if (draft == null) {
            return;
        }
        for (int i = 0; i < draft.size(); i++) {
            LootRowData data = draft.get(i);
            int base = i * 6;
            if (base + 4 >= lootWidgets.size()) {
                break;
            }
            if (lootWidgets.get(base + 1) instanceof EditBox minBox) {
                data.minText = minBox.getValue();
            }
            if (lootWidgets.get(base + 2) instanceof EditBox maxBox) {
                data.maxText = maxBox.getValue();
            }
            if (lootWidgets.get(base + 3) instanceof EditBox chanceBox) {
                data.chanceText = chanceBox.getValue();
            }
            if (lootWidgets.get(base + 4) instanceof EditBox lootChanceBox) {
                data.lootChanceText = lootChanceBox.getValue();
            }
        }
    }

    /** 草稿转提交用配置列表：跳过未选物品的行；数值在此做客户端侧钳制（服务端再钳一次） */
    private static List<LootConfig.LootEntry> lootEntriesFrom(List<LootRowData> rows) {
        List<LootConfig.LootEntry> out = new ArrayList<>();
        for (LootRowData row : rows) {
            if (row.item == null) {
                continue;
            }
            int min = row.minText.isEmpty() ? 0 : parseIntOr(row.minText, 0);
            int max = row.maxText.isEmpty() ? min : parseIntOr(row.maxText, min);
            float chance = row.chanceText.isEmpty() ? 100.0F : parseFloatOr(row.chanceText, 100.0F);
            float lootChance = row.lootChanceText.isEmpty() ? 0.0F : parseFloatOr(row.lootChanceText, 0.0F);
            int[] range = LootMath.normalizeRange(min, max);
            out.add(new LootConfig.LootEntry(BuiltInRegistries.ITEM.getKey(row.item),
                    range[0], range[1],
                    LootMath.clampChance(chance), LootMath.clampChance(lootChance),
                    row.tag == null ? null : row.tag.copy()));
        }
        return out;
    }

    private int maxLootScroll() {
        // 每行 6 个控件
        return Math.max(0, lootWidgets.size() / 6 - LOOT_VISIBLE_ROWS);
    }

    private void updateLootVisibility() {
        int rows = lootWidgets.size() / 6;
        for (int i = 0; i < rows; i++) {
            boolean visible = tab == Tab.LOOT && !tabListOpen
                    && i >= lootScroll && i < lootScroll + LOOT_VISIBLE_ROWS;
            for (int c = 0; c < 6; c++) {
                AbstractWidget w = lootWidgets.get(i * 6 + c);
                w.visible = visible;
                if (visible) {
                    // 数据行从控制行（CONTENT_TOP 行）的下一行开始
                    w.setY(topPos + CONTENT_TOP + ROW_HEIGHT + (i - lootScroll) * ROW_HEIGHT + 1);
                }
            }
        }
    }

    // ==================== 赠礼页（女仆 TMA 回礼池） ====================

    /** 赠礼页一行草稿：物品（含 NBT）+ 最少/最多数量 + 权重 */
    private static final class GiftRowData {
        net.minecraft.world.item.Item item;
        net.minecraft.nbt.CompoundTag tag;
        String minText = "1";
        String maxText = "";
        String weightText = "1";
    }

    /** 按草稿重建赠礼页行控件（首次构建、增删行与物品选择器返回后调用） */
    private void rebuildGiftRows() {
        for (AbstractWidget w : giftWidgets) {
            removeWidget(w);
        }
        giftWidgets.clear();
        if (giftDraft == null) {
            return;
        }
        for (int i = 0; i < giftDraft.size(); i++) {
            final int idx = i;
            GiftRowData data = giftDraft.get(i);

            // 物品按钮：打开支持附魔书的选择器，选中后物品与 NBT 一并回填
            String itemLabel = data.item == null ? "点击选择…"
                    : itemNameWithTag(BuiltInRegistries.ITEM.getKey(data.item), data.tag);
            if (font.width(itemLabel) > 80) {
                itemLabel = font.plainSubstrByWidth(itemLabel, 74) + "…";
            }
            Button itemBtn = Button.builder(
                    Component.literal(itemLabel),
                    b -> {
                        syncGiftTexts();
                        ItemPickerScreen.open(stack -> {
                            data.item = stack.getItem();
                            data.tag = stack.getTag();
                            minecraft.setScreen(EntityEditorScreen.this);
                        });
                    }).bounds(leftPos + 12, 0, 86, 16).build();
            if (data.item != null) {
                itemBtn.setTooltip(Tooltip.create(Component.literal(
                        itemNameWithTag(BuiltInRegistries.ITEM.getKey(data.item), data.tag))));
            }

            EditBox minBox = new EditBox(font, leftPos + 100, 0, 26, 16,
                    Component.literal("最少"));
            minBox.setMaxLength(3);
            minBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,3}"));
            minBox.setValue(data.minText);
            minBox.setTooltip(Tooltip.create(Component.literal("最少数量（1-64）")));

            EditBox maxBox = new EditBox(font, leftPos + 130, 0, 26, 16,
                    Component.literal("最多"));
            maxBox.setMaxLength(3);
            maxBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,3}"));
            maxBox.setValue(data.maxText);
            maxBox.setTooltip(Tooltip.create(Component.literal("最大数量（1-64），留空=同最少")));

            EditBox weightBox = new EditBox(font, leftPos + 162, 0, 32, 16,
                    Component.literal("权重"));
            weightBox.setMaxLength(4);
            weightBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,4}"));
            weightBox.setValue(data.weightText);
            weightBox.setTooltip(Tooltip.create(Component.literal(
                    "抽取权重（1-1000，留空按 1）：权重越大越容易被选中")));

            Button delBtn = Button.builder(Component.literal("删"), b -> {
                syncGiftTexts();
                giftDraft.remove(idx);
                if (giftScroll > maxGiftScroll()) {
                    giftScroll = maxGiftScroll();
                }
                rebuildGiftRows();
                setFocused(null);
            }).bounds(leftPos + 202, 0, 22, 16).build();

            addRenderableWidget(itemBtn);
            addRenderableWidget(minBox);
            addRenderableWidget(maxBox);
            addRenderableWidget(weightBox);
            addRenderableWidget(delBtn);
            giftWidgets.add(itemBtn);
            giftWidgets.add(minBox);
            giftWidgets.add(maxBox);
            giftWidgets.add(weightBox);
            giftWidgets.add(delBtn);
        }
        if (giftScroll > maxGiftScroll()) {
            giftScroll = maxGiftScroll();
        }
        updateGiftVisibility();
    }

    /** 切去物品选择器前把控件文本回写草稿（控件重建不丢输入） */
    private void syncGiftTexts() {
        if (giftDraft == null) {
            return;
        }
        for (int i = 0; i < giftDraft.size(); i++) {
            GiftRowData data = giftDraft.get(i);
            int base = i * 5;
            if (base + 3 >= giftWidgets.size()) {
                break;
            }
            if (giftWidgets.get(base + 1) instanceof EditBox minBox) {
                data.minText = minBox.getValue();
            }
            if (giftWidgets.get(base + 2) instanceof EditBox maxBox) {
                data.maxText = maxBox.getValue();
            }
            if (giftWidgets.get(base + 3) instanceof EditBox weightBox) {
                data.weightText = weightBox.getValue();
            }
        }
    }

    /** 草稿转提交用条目：跳过未选物品的行；数量/权重客户端侧钳制（服务端再钳一次） */
    private static List<io.github.zgxhzhr.superdbg.gift.GiftPoolConfig.GiftEntry> giftEntriesFrom(
            List<GiftRowData> rows) {
        List<io.github.zgxhzhr.superdbg.gift.GiftPoolConfig.GiftEntry> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (GiftRowData row : rows) {
            if (row.item == null) {
                continue;
            }
            int min = row.minText.isEmpty() ? 1 : parseIntOr(row.minText, 1);
            int max = row.maxText.isEmpty() ? min : parseIntOr(row.maxText, min);
            int[] range = io.github.zgxhzhr.superdbg.gift.GiftPoolConfig.normalizeRange(min, max);
            int weight = io.github.zgxhzhr.superdbg.gift.GiftPoolConfig
                    .clampWeight(parseIntOr(row.weightText, 1));
            out.add(new io.github.zgxhzhr.superdbg.gift.GiftPoolConfig.GiftEntry(
                    BuiltInRegistries.ITEM.getKey(row.item), range[0], range[1], weight,
                    row.tag == null ? null : row.tag.copy()));
        }
        return out;
    }

    private int maxGiftScroll() {
        // 每行 5 个控件；赠礼页无控制行，可显示 VISIBLE_ROWS 行
        return Math.max(0, giftWidgets.size() / 5 - VISIBLE_ROWS);
    }

    private void updateGiftVisibility() {
        int rows = giftWidgets.size() / 5;
        for (int i = 0; i < rows; i++) {
            boolean visible = tab == Tab.GIFT && !tabListOpen
                    && i >= giftScroll && i < giftScroll + VISIBLE_ROWS;
            for (int c = 0; c < 5; c++) {
                AbstractWidget w = giftWidgets.get(i * 5 + c);
                w.visible = visible;
                if (visible) {
                    w.setY(topPos + CONTENT_TOP + (i - giftScroll) * ROW_HEIGHT + 1);
                }
            }
        }
    }

    // ==================== 效果选择覆盖层 ====================

    private void openPicker() {
        pickerOpen = true;
        pickerScroll = 0;
        closeTabList();
        addEffectButton.visible = false;
        healthBox.visible = false;
        levelBox.visible = false;
        confirmButton.visible = false;
        cancelButton.visible = false;
        updateEffectVisibility();
        updatePickerVisibility();
        setFocused(null);
    }

    private void closePicker() {
        if (!pickerOpen) {
            return;
        }
        pickerOpen = false;
        for (var w : pickerWidgets) {
            w.visible = false;
        }
        levelBox.visible = tab == Tab.TRAIT;
        confirmButton.visible = true;
        cancelButton.visible = true;
        if (tab == Tab.EFFECT) {
            addEffectButton.visible = true;
        }
        updateEffectVisibility();
    }

    private void pickEffect(MobEffect effect, ResourceLocation rl) {
        closePicker();
        addEffectRow(effect, 0, 200);
        // 新行追加在列表末尾，滚到底部让它可见
        effectScroll = maxEffectScroll();
        updateEffectVisibility();
    }

    private int maxPickerScroll() {
        return Math.max(0, allEffects.size() - VISIBLE_ROWS);
    }

    private void updatePickerVisibility() {
        for (int i = 0; i < pickerWidgets.size(); i++) {
            AbstractWidget btn = pickerWidgets.get(i);
            boolean visible = pickerOpen && i >= pickerScroll && i < pickerScroll + VISIBLE_ROWS;
            btn.visible = visible;
            if (visible) {
                btn.setY(topPos + CONTENT_TOP + (i - pickerScroll) * ROW_HEIGHT + 1);
            }
        }
    }

    // ==================== 滚轮与点击 ====================

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        // 下拉列表展开期间禁止内容区滚动（滚动会把刚隐藏的控件重新摆回来）
        if (tabListOpen) {
            return true;
        }
        if (pickerOpen) {
            pickerScroll = Math.max(0, Math.min(maxPickerScroll(), pickerScroll - (delta > 0 ? 1 : -1)));
            updatePickerVisibility();
            return true;
        }
        if (tab == Tab.EFFECT) {
            effectScroll = Math.max(0, Math.min(maxEffectScroll(), effectScroll - (delta > 0 ? 1 : -1)));
            updateEffectVisibility();
            setFocused(null);
            return true;
        }
        if (tab == Tab.TRAIT) {
            traitScroll = Math.max(0, Math.min(maxTraitScroll(), traitScroll - (delta > 0 ? 1 : -1)));
            updateTraitVisibility();
            setFocused(null);
            return true;
        }
        if (tab == Tab.CURIOS) {
            curiosScroll = Math.max(0, Math.min(maxCuriosScroll(), curiosScroll - (delta > 0 ? 1 : -1)));
            updateCuriosVisibility();
            setFocused(null);
            return true;
        }
        if (tab == Tab.BOND) {
            bondScroll = Math.max(0, Math.min(maxBondScroll(), bondScroll - (delta > 0 ? 1 : -1)));
            updateBondVisibility();
            setFocused(null);
            return true;
        }
        if (tab == Tab.FOX) {
            foxScroll = Math.max(0, Math.min(maxFoxScroll(), foxScroll - (delta > 0 ? 1 : -1)));
            updateFoxVisibility();
            setFocused(null);
            return true;
        }
        if (tab == Tab.TRADE) {
            tradeScroll = Math.max(0, Math.min(maxTradeScroll(), tradeScroll - (delta > 0 ? 1 : -1)));
            updateTradeVisibility();
            setFocused(null);
            return true;
        }
        if (tab == Tab.GIVE) {
            giveScroll = Math.max(0, Math.min(maxGiveScroll(), giveScroll - (delta > 0 ? 1 : -1)));
            updateGiveVisibility();
            setFocused(null);
            return true;
        }
        if (tab == Tab.LOOT) {
            lootScroll = Math.max(0, Math.min(maxLootScroll(), lootScroll - (delta > 0 ? 1 : -1)));
            updateLootVisibility();
            setFocused(null);
            return true;
        }
        if (tab == Tab.GIFT) {
            giftScroll = Math.max(0, Math.min(maxGiftScroll(), giftScroll - (delta > 0 ? 1 : -1)));
            updateGiftVisibility();
            setFocused(null);
            return true;
        }
        if (tab == Tab.MISC) {
            // 杂项页控件固定布局、不超过一屏，无需滚动
            return true;
        }
        attrScroll = Math.max(0, Math.min(maxAttrScroll(), attrScroll - (delta > 0 ? 1 : -1)));
        updateAttrVisibility();
        setFocused(null);
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 页签下拉列表打开时，点击选项区外关闭（选项按钮自身由 super 分发处理）
        if (tabListOpen && (mouseX < leftPos + 182 || mouseX > leftPos + 248
                || mouseY < topPos + 20
                || mouseY > topPos + 20 + availableTabs.size() * 15)) {
            closeTabList();
            return true;
        }
        // 效果选择覆盖层打开时，点击列表区域外关闭
        if (pickerOpen && (mouseX < leftPos + 30 || mouseX > leftPos + 230
                || mouseY < topPos + CONTENT_TOP
                || mouseY > topPos + CONTENT_TOP + VISIBLE_ROWS * ROW_HEIGHT)) {
            closePicker();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ==================== 提交 ====================

    private void submit() {
        Map<ResourceLocation, Double> attrs = new LinkedHashMap<>();
        for (int i = 0; i < attrBoxes.size(); i++) {
            attrs.put(attrIds.get(i), attrBoxes.get(i).value());
        }

        // 当前血量：留空时按打开时的快照值提交，服务端再钳制到 [0, 最大生命]
        float health = parseIntOr(healthBox.getValue(),
                Math.max(0, Math.round(menu.getData().health())));

        List<EntityEditorData.EffectEntry> effects = new ArrayList<>();
        for (EffectRow row : effectRows) {
            int amp = parseIntOr(row.amplifier().getValue(), 0);
            int dur = parseIntOr(row.duration().getValue(), 200);
            amp = Math.max(0, Math.min(io.github.zgxhzhr.superdbg.potion.PotionEffectData.MAX_AMPLIFIER, amp));
            if (dur < -1) {
                dur = -1;
            }
            effects.add(new EntityEditorData.EffectEntry(row.id(), amp, dur));
        }

        // 词条全量提交（含等级 0 = 无该词条）；未安装 L2Hostility 时列表为空
        List<EntityEditorData.TraitEntry> traits = new ArrayList<>();
        for (int i = 0; i < traitBoxes.size(); i++) {
            int level = parseIntOr(traitBoxes.get(i).getValue(), 0);
            level = Math.max(0, Math.min(255, level));
            traits.add(new EntityEditorData.TraitEntry(traitIds.get(i), level));
        }
        // 难度等级留空时保持打开时的快照值；-1 表示服务端不写回
        int hostilityLevel;
        if (menu.getData().hostilityLevel() < 0) {
            hostilityLevel = -1;
        } else {
            hostilityLevel = parseIntOr(levelBox.getValue(), menu.getData().hostilityLevel());
        }

        // Curios 饰品栏数量全量提交（0-999，服务端再钳制）
        List<EntityEditorData.CuriosSlotEntry> curiosSlots = new ArrayList<>();
        for (int i = 0; i < curiosBoxes.size(); i++) {
            int amount = parseIntOr(curiosBoxes.get(i).getValue(), 0);
            amount = Math.max(0, Math.min(999, amount));
            curiosSlots.add(new EntityEditorData.CuriosSlotEntry(curiosIds.get(i), amount));
        }

        // 女仆好感度/羁绊快照（非女仆时为 null，控件未构建）
        EntityEditorData.BondSnapshot bond = null;
        var snapBond = menu.getData().bond();
        if (snapBond != null && snapBond.present() && bondFavorBox != null) {
            int favorability = Math.max(0, Math.min(
                    io.github.zgxhzhr.superdbg.compat.tma.TmaBondCompat.MAX_FAVORABILITY,
                    parseIntOr(bondFavorBox.getValue(), snapBond.favorability())));
            if (snapBond.tmaLoaded()) {
                int bondLevel = parseIntOr(bondLevelBox.getValue(), snapBond.bondLevel());
                int giftQueue = parseIntOr(bondGiftBox.getValue(), snapBond.giftQueue());
                List<EntityEditorData.BondAbilityEntry> abilities = new ArrayList<>();
                for (int i = 0; i < bondAbilityIds.size(); i++) {
                    abilities.add(new EntityEditorData.BondAbilityEntry(
                            bondAbilityIds.get(i), bondAbilityStates.get(i)[0]));
                }
                bond = new EntityEditorData.BondSnapshot(true, true, favorability,
                        Math.max(0, bondLevel),
                        bondUnlockedState != null && bondUnlockedState[0],
                        Math.max(0, giftQueue), abilities);
            } else {
                bond = new EntityEditorData.BondSnapshot(true, false, favorability,
                        0, false, 0, List.of());
            }
        }

        // 村民交易全量提交；非村民（tradesDraft 为 null）提交 null，服务端跳过
        List<EntityEditorData.TradeEntry> trades = tradesDraft == null
                ? null : List.copyOf(tradesDraft);

        // 掉落覆盖全量提交：覆盖开关开=写配置，关=删覆盖；玩家目标无掉落页，服务端整体跳过
        boolean hasLootPage = availableTabs.contains(Tab.LOOT);
        LootConfig entityLoot = null;
        LootConfig typeLoot = null;
        if (hasLootPage) {
            superdbg$syncLootTexts();
            if (lootEntityEnabled) {
                entityLoot = new LootConfig(lootEntityMode, lootEntriesFrom(lootEntityDraft));
            }
            if (lootTypeEnabled) {
                typeLoot = new LootConfig(lootTypeMode, lootEntriesFrom(lootTypeDraft));
            }
        }

        // 女仆回礼池全量提交：列表空=删自定义池回退 TMA 默认；非女仆/未装 TMA 整体跳过
        boolean hasGiftPage = availableTabs.contains(Tab.GIFT);
        io.github.zgxhzhr.superdbg.gift.GiftPoolConfig giftPool = null;
        if (hasGiftPage) {
            syncGiftTexts();
            List<io.github.zgxhzhr.superdbg.gift.GiftPoolConfig.GiftEntry> giftEntries =
                    giftEntriesFrom(giftDraft);
            if (!giftEntries.isEmpty()) {
                giftPool = new io.github.zgxhzhr.superdbg.gift.GiftPoolConfig(giftEntries);
            }
        }

        // 玩家渲染名：由调试器本体存储与同步，不依赖任何第三方模组；控件未构建（非玩家）时为 null
        String renderName = null;
        if (foxRenderBox != null) {
            String value = foxRenderBox.getValue().trim();
            renderName = value.isEmpty() ? null : value;
        }

        // 人是狐快照全量提交；非玩家/未安装 playermaid（控件未构建）时为 null，服务端跳过。
        // 人是狐自身的渲染名原样回传（该功能已由调试器本体接管，这里不改动它）
        EntityEditorData.FoxMaidSnapshot foxMaid = null;
        var foxSnap = menu.getData().foxMaid();
        if (foxSnap != null && foxSnap.present()
                && foxActiveBox != null && foxOwnerBox != null
                && foxFavorBox != null && foxInvulnBox != null
                && foxSlabModelButton != null) {
            String ownerName = foxOwnerBox.getValue().trim();
            int favorability = Math.max(0, Math.min(
                    io.github.zgxhzhr.superdbg.compat.playermaid.PlayerMaidCompat.MAX_FAVORABILITY,
                    parseIntOr(foxFavorBox.getValue(), foxSnap.favorability())));
            // 取当前选中的模型 id，空白归一化为 null（清除语义）
            String slabModelId = foxSlabModelSelected.isEmpty() ? null : foxSlabModelSelected;
            foxMaid = new EntityEditorData.FoxMaidSnapshot(true,
                    foxActiveBox.selected(),
                    foxSnap.renderName(),
                    ownerName.isEmpty() ? null : ownerName,
                    favorability, foxSchedule, foxInvulnBox.selected(),
                    slabModelId);
        }

        NetworkHandler.CHANNEL.sendToServer(new SubmitEntityEditorPacket(
                menu.getEntityId(), attrs, effects, traits, hostilityLevel,
                health, removeBox.selected(), removalGuardBox.selected(), curiosSlots, bond,
                trades, hasLootPage, entityLoot, typeLoot, hasGiftPage, giftPool, renderName, foxMaid));
        onClose();
    }

    private static float parseFloatOr(String raw, float fallback) {
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseIntOr(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ==================== 渲染 ====================

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        // 与物品编辑器一致的灰色斜面面板
        graphics.fill(x, y, x + imageWidth, y + imageHeight, 0xFF555555);
        graphics.fill(x + 1, y + 1, x + imageWidth - 1, y + imageHeight - 1, 0xFFFFFFFF);
        graphics.fill(x + 2, y + 2, x + imageWidth - 2, y + imageHeight - 2, 0xFFC6C6C6);
        graphics.fill(x + 4, y + 208, x + imageWidth - 4, y + 209, 0xFF555555);
        // 页签下拉列表展开时的背板（盖住下方内容区；选项按钮由控件层在其后绘制）
        if (tabListOpen) {
            graphics.fill(x + 182, y + 20, x + 248, y + 20 + availableTabs.size() * 15, 0xFF555555);
            graphics.fill(x + 183, y + 21, x + 247, y + 19 + availableTabs.size() * 15, 0xFFC6C6C6);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // renderLabels 内坐标均为相对 leftPos/topPos 的面板坐标
        graphics.drawString(font, title, 8, 6, 0x404040, false);

        // 底部公共区：玩家渲染名标签（输入框在 superdbg$initContent 中创建，仅玩家目标出现）
        if (foxRenderBox != null) {
            graphics.drawString(font, Component.literal("渲染名"),
                    154 - 4 - font.width("渲染名"), 214, 0x404040, false);
        }

        if (pickerOpen) {
            graphics.fill(6, 8, imageWidth - 6, 21, 0x88000000);
            graphics.drawString(font, Component.literal("选择效果（点击添加，点外部关闭）"),
                    10, 12, 0xFFFFFF, false);
            return;
        }

        if (tab == Tab.ATTR) {
            // 属性行标签（随滚动移动，与输入框对齐）
            for (int i = 0; i < attrIds.size(); i++) {
                if (i < attrScroll || i >= attrScroll + VISIBLE_ROWS) {
                    continue;
                }
                Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(attrIds.get(i));
                String text = attribute == null ? attrIds.get(i).getPath()
                        : Component.translatable(attribute.getDescriptionId()).getString();
                if (font.width(text) > 130) {
                    text = font.plainSubstrByWidth(text, 124) + "…";
                }
                graphics.drawString(font, text, 12, CONTENT_TOP + (i - attrScroll) * ROW_HEIGHT + 5,
                        0x404040, false);
            }
            if (attrBoxes.isEmpty()) {
                graphics.drawString(font, Component.literal("（该实体没有已注册属性）"),
                        12, 28, 0x808080, false);
            }
            // 当前生命值标签（与底部输入框对齐）；女仆固定回满
            graphics.drawString(font,
                    Component.literal(maidFullHealth ? "女仆回满" : "当前生命"),
                    150, 195, maidFullHealth ? 0xB06000 : 0x404040, false);
            return;
        }

        if (tab == Tab.TRAIT) {
            // 词条页表头
            graphics.drawString(font, Component.literal("词条"), 12, 14, 0x404040, false);
            graphics.drawString(font, Component.literal("等级(0=无)"), 150, 14, 0x404040, false);
            for (int i = 0; i < traitIds.size(); i++) {
                if (i < traitScroll || i >= traitScroll + VISIBLE_ROWS) {
                    continue;
                }
                ResourceLocation id = traitIds.get(i);
                // L2Hostility 词条翻译键规则：trait.<命名空间>.<路径>
                String text = Component.translatable(
                        "trait." + id.getNamespace() + "." + id.getPath()).getString();
                if (font.width(text) > 130) {
                    text = font.plainSubstrByWidth(text, 124) + "…";
                }
                graphics.drawString(font, text, 12,
                        CONTENT_TOP + (i - traitScroll) * ROW_HEIGHT + 5, 0x404040, false);
            }
            // 难度等级标签
            graphics.drawString(font, Component.literal("难度等级(生命缩放)"), 12, 182, 0x404040, false);
            return;
        }

        if (tab == Tab.CURIOS) {
            // 饰品页表头
            graphics.drawString(font, Component.literal("槽位类型"), 12, 14, 0x404040, false);
            graphics.drawString(font, Component.literal("数量"), 155, 14, 0x404040, false);
            for (int i = 0; i < curiosIds.size(); i++) {
                if (i < curiosScroll || i >= curiosScroll + VISIBLE_ROWS) {
                    continue;
                }
                // 查 curios.identifier.<id> 翻译键；无翻译时 fallback 显示原始 ID
                Component label = Component.translatable("curios.identifier." + curiosIds.get(i));
                String text = label.getString();
                if (font.width(text) > 130) {
                    text = font.plainSubstrByWidth(text, 124) + "…";
                }
                graphics.drawString(font, text, 12,
                        CONTENT_TOP + (i - curiosScroll) * ROW_HEIGHT + 5, 0x404040, false);
            }
            if (curiosIds.isEmpty()) {
                graphics.drawString(font, Component.literal("（该实体没有 Curios 饰品栏）"),
                        12, 28, 0x808080, false);
            }
            return;
        }

        if (tab == Tab.BOND) {
            // 羁绊页表头
            graphics.drawString(font, Component.literal("项目"), 12, 14, 0x404040, false);
            graphics.drawString(font, Component.literal("值"), 155, 14, 0x404040, false);
            for (int i = 0; i < bondRowLabels.size(); i++) {
                if (i < bondScroll || i >= bondScroll + VISIBLE_ROWS) {
                    continue;
                }
                String text = bondRowLabels.get(i);
                if (font.width(text) > 130) {
                    text = font.plainSubstrByWidth(text, 124) + "…";
                }
                graphics.drawString(font, text, 12,
                        CONTENT_TOP + (i - bondScroll) * ROW_HEIGHT + 5, 0x404040, false);
            }
            // 底部说明：好感度等级由点数实时派生
            int favPoints = parseIntOr(bondFavorBox == null ? "" : bondFavorBox.getValue(),
                    menu.getData().bond() == null ? 0 : menu.getData().bond().favorability());
            int favLevel = io.github.zgxhzhr.superdbg.compat.tma.TmaBondCompat.favorabilityLevel(favPoints);
            graphics.drawString(font,
                    Component.literal("好感度等级（点数派生）：" + favLevel
                            + "（阈值 64/192/384）"),
                    12, 178, 0x707070, false);
            return;
        }

        if (tab == Tab.FOX) {
            // 人是狐页表头
            graphics.drawString(font, Component.literal("项目"), 12, 14, 0x404040, false);
            graphics.drawString(font, Component.literal("值"), 155, 14, 0x404040, false);
            for (int i = 0; i < foxRowLabels.size(); i++) {
                if (i < foxScroll || i >= foxScroll + VISIBLE_ROWS) {
                    continue;
                }
                String text = foxRowLabels.get(i);
                if (font.width(text) > 130) {
                    text = font.plainSubstrByWidth(text, 124) + "…";
                }
                graphics.drawString(font, text, 12,
                        CONTENT_TOP + (i - foxScroll) * ROW_HEIGHT + 5, 0x404040, false);
            }
            // 底部说明：好感度等级与距下一级所需点数实时派生（阈值 64/192/384）
            int foxFavSnapshot = menu.getData().foxMaid() == null
                    ? 0 : menu.getData().foxMaid().favorability();
            int foxPoints = Math.max(0, Math.min(
                    io.github.zgxhzhr.superdbg.compat.playermaid.PlayerMaidCompat.MAX_FAVORABILITY,
                    parseIntOr(foxFavorBox == null ? "" : foxFavorBox.getValue(), foxFavSnapshot)));
            String hint;
            if (foxPoints < 64) {
                hint = "好感度等级：0（距 1 级还需 " + (64 - foxPoints) + " 点）";
            } else if (foxPoints < 192) {
                hint = "好感度等级：1（距 2 级还需 " + (192 - foxPoints) + " 点）";
            } else if (foxPoints < 384) {
                hint = "好感度等级：2（距 3 级还需 " + (384 - foxPoints) + " 点）";
            } else {
                hint = "好感度等级：3（已满级）";
            }
            graphics.drawString(font, Component.literal(hint + "（阈值 64/192/384）"),
                    12, 178, 0x707070, false);
            return;
        }

        if (tab == Tab.TRADE) {
            // 交易页表头
            graphics.drawString(font, Component.literal("交易列表（改/删，底部新增）"),
                    12, 14, 0x404040, false);
            int rows = tradesDraft == null ? 0 : tradesDraft.size();
            for (int i = 0; i < rows; i++) {
                if (i < tradeScroll || i >= tradeScroll + VISIBLE_ROWS) {
                    continue;
                }
                String text = tradeDescription(tradesDraft.get(i));
                if (font.width(text) > 134) {
                    text = font.plainSubstrByWidth(text, 128) + "…";
                }
                graphics.drawString(font, text, 12,
                        CONTENT_TOP + (i - tradeScroll) * ROW_HEIGHT + 5, 0x404040, false);
            }
            if (rows == 0) {
                graphics.drawString(font, Component.literal("（暂无交易，点「新增交易」添加）"),
                        12, 28, 0x808080, false);
            }
            return;
        }

        if (tab == Tab.LOOT) {
            graphics.drawString(font, Component.literal("物品"), 12, 14, 0x404040, false);
            graphics.drawString(font, Component.literal("最少"), 100, 14, 0x404040, false);
            graphics.drawString(font, Component.literal("最多"), 130, 14, 0x404040, false);
            graphics.drawString(font, Component.literal("概率%"), 160, 14, 0x404040, false);
            graphics.drawString(font, Component.literal("抢+%"), 196, 14, 0x404040, false);
            if (lootWidgets.isEmpty()) {
                graphics.drawString(font, Component.literal("（该生物无原版掉落，点「添加一行」自定义）"),
                        12, 47, 0x808080, false);
            }
            // 覆盖关：行内是原版掉落的可编辑副本；任意编辑会自动把覆盖开关切到"开"
            boolean enabled = lootScope == 0 ? lootEntityEnabled : lootTypeEnabled;
            // 另一作用域存在覆盖时优先提示，避免打开同类实体时看不到已生效的全类型逻辑
            String crossHint = null;
            if (lootScope == 0 && lootTypeEnabled) {
                crossHint = "该类型已配置「全类型」覆盖（对所有同类生效），点「范围」切换查看";
            } else if (lootScope == 1 && lootEntityEnabled) {
                crossHint = "当前这只另有「本实体」覆盖并优先生效，点「范围」切换查看";
            }
            graphics.drawString(font, Component.literal(crossHint != null ? crossHint
                            : (enabled ? "替换只换战利品表，装备/经验不受影响"
                                    : "当前为原版掉落，可直接修改；改动后「覆盖」会自动开启，保存即生效")),
                    12, 178, crossHint != null ? 0xB06000 : 0x707070, false);
            return;
        }

        if (tab == Tab.GIFT) {
            graphics.drawString(font, Component.literal("礼物物品"), 12, 14, 0x404040, false);
            graphics.drawString(font, Component.literal("最少"), 100, 14, 0x404040, false);
            graphics.drawString(font, Component.literal("最多"), 130, 14, 0x404040, false);
            graphics.drawString(font, Component.literal("权重"), 162, 14, 0x404040, false);
            if (giftWidgets.isEmpty()) {
                graphics.drawString(font,
                        Component.literal("（当前使用 TMA 默认礼物池；点「添加一行」自定义）"),
                        12, 28, 0x808080, false);
            }
            graphics.drawString(font, Component.literal(
                            "按权重抽取；清空全部行后保存=恢复 TMA 默认池"),
                    12, 178, 0x707070, false);
            return;
        }

        if (tab == Tab.GIVE) {
            graphics.drawString(font, Component.literal("物品"), 12, 14, 0x404040, false);
            graphics.drawString(font, Component.literal("总数"), 122, 14, 0x404040, false);
            graphics.drawString(font, Component.literal("每组"), 172, 14, 0x404040, false);
            if (giveWidgets.isEmpty()) {
                graphics.drawString(font, Component.literal("（点「添加一行」开始配置物品）"),
                        12, 28, 0x808080, false);
            }
            graphics.drawString(font, Component.literal("留空按原版上限"), 172, 178, 0x707070, false);
            return;
        }

        if (tab == Tab.MISC) {
            // 杂项页表头（复选框自带文字标签，此处只放说明）
            graphics.drawString(font, Component.literal("杂项"), 12, 14, 0x404040, false);
            if (pseudoCreativeBox != null) {
                graphics.drawString(font, Component.literal(
                                "伪创造：受击正常但不掉血；创造模式锁血，生存模式受击拉回"),
                        12, 178, 0x707070, false);
            } else {
                graphics.drawString(font, Component.literal("移除实体：直接删除（玩家走死亡）；防移除：免疫指令/清除"),
                        12, 178, 0x707070, false);
            }
            return;
        }

        // 效果页表头
        graphics.drawString(font, Component.literal("效果"), 12, 14, 0x404040, false);
        graphics.drawString(font, Component.literal("等级"), 118, 14, 0x404040, false);
        graphics.drawString(font, Component.literal("时长"), 168, 14, 0x404040, false);
        graphics.drawString(font, Component.literal("-1=永久"), 214, 14, 0x707070, false);

        for (int i = 0; i < effectRows.size(); i++) {
            if (i < effectScroll || i >= effectScroll + VISIBLE_ROWS) {
                continue;
            }
            MobEffect effect = BuiltInRegistries.MOB_EFFECT.get(effectRows.get(i).id());
            String text = effect == null ? effectRows.get(i).id().getPath()
                    : effect.getDisplayName().getString();
            if (font.width(text) > 100) {
                text = font.plainSubstrByWidth(text, 94) + "…";
            }
            graphics.drawString(font, text, 12, CONTENT_TOP + (i - effectScroll) * ROW_HEIGHT + 5,
                    0x404040, false);
        }
        if (effectRows.isEmpty()) {
            graphics.drawString(font, Component.literal("（无效果，点「添加效果」新增）"),
                    12, 28, 0x808080, false);
        }
    }

    // ==================== 控件内部类 ====================

    /**
     * 属性基础值输入框：直接输入精确数值，留空按 0 处理，
     * 提交时服务端会再次钳制。
     */
    private static class AttrBox extends EditBox {

        AttrBox(Font font, int x, int y, int w, int h, double current) {
            super(font, x, y, w, h, Component.literal("基础值"));
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
                return Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                return 0.0D;
            }
        }
    }

    private record EffectRow(ResourceLocation id, EditBox amplifier, EditBox duration, Button delete) {
    }

    /**
     * 格式化数值显示：最多 6 位小数并去掉尾随零（20.0 → "20"）。
     * 非有限值（NaN/Infinity）按 0 处理——BigDecimal.valueOf 会对其抛
     * NumberFormatException，导致 init 中断、编辑器控件全部消失。
     */
    private static String formatAttr(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        return BigDecimal.valueOf(value)
                .setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }
}
