package io.github.zgxhzhr.superdbg.network;

import io.github.zgxhzhr.superdbg.entity.EntityEditorData;
import io.github.zgxhzhr.superdbg.entity.EntityEditorService;
import io.github.zgxhzhr.superdbg.entity.PlayerAttributeOverrides;
import io.github.zgxhzhr.superdbg.menu.EntityEditorMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 客户端→服务端：实体编辑器全量提交。
 * <p>
 * 一次性携带属性基础值、当前血量、效果列表、L2Hostility 词条/难度等级与移除标志，
 * 服务端重新取实体并复检权限后原子写回，所有数值在服务端重新校验/钳制，不信任客户端。
 */
public record SubmitEntityEditorPacket(int entityId,
                                       Map<ResourceLocation, Double> attrs,
                                       List<EntityEditorData.EffectEntry> effects,
                                       List<EntityEditorData.TraitEntry> traits,
                                       int hostilityLevel,
                                       float health,
                                       boolean removeEntity,
                                       boolean removalGuard,
                                       List<EntityEditorData.CuriosSlotEntry> curiosSlots,
                                       EntityEditorData.BondSnapshot bond,
                                       List<EntityEditorData.TradeEntry> trades,
                                       boolean hasLootPage,
                                       io.github.zgxhzhr.superdbg.loot.LootConfig entityLoot,
                                       io.github.zgxhzhr.superdbg.loot.LootConfig typeLoot,
                                       boolean hasGiftPage,
                                       io.github.zgxhzhr.superdbg.gift.GiftPoolConfig giftPool,
                                       String renderName,
                                       EntityEditorData.FoxMaidSnapshot foxMaid) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);

        buf.writeVarInt(attrs.size());
        for (Map.Entry<ResourceLocation, Double> e : attrs.entrySet()) {
            buf.writeResourceLocation(e.getKey());
            buf.writeDouble(e.getValue());
        }

        buf.writeVarInt(effects.size());
        for (EntityEditorData.EffectEntry entry : effects) {
            buf.writeResourceLocation(entry.id());
            buf.writeVarInt(entry.amplifier());
            buf.writeVarInt(entry.duration());
        }

        buf.writeVarInt(traits.size());
        for (EntityEditorData.TraitEntry entry : traits) {
            buf.writeResourceLocation(entry.id());
            buf.writeVarInt(entry.level());
        }
        buf.writeVarInt(hostilityLevel);

        buf.writeFloat(health);
        buf.writeBoolean(removeEntity);
        buf.writeBoolean(removalGuard);

        buf.writeVarInt(curiosSlots.size());
        for (EntityEditorData.CuriosSlotEntry entry : curiosSlots) {
            buf.writeUtf(entry.identifier());
            buf.writeVarInt(entry.amount());
        }
        writeBond(buf, bond);
        writeTrades(buf, trades);
        // 掉落覆盖：hasLootPage=false（玩家目标无掉落页）时不带配置，服务端整体跳过
        buf.writeBoolean(hasLootPage);
        if (hasLootPage) {
            io.github.zgxhzhr.superdbg.loot.LootConfig.write(buf, entityLoot);
            io.github.zgxhzhr.superdbg.loot.LootConfig.write(buf, typeLoot);
        }
        // 女仆回礼池：hasGiftPage=false（非女仆/未装 TMA）时不带，服务端整体跳过
        buf.writeBoolean(hasGiftPage);
        if (hasGiftPage) {
            io.github.zgxhzhr.superdbg.gift.GiftPoolConfig.write(buf, giftPool);
        }
        // 玩家渲染名：由调试器本体存储，非玩家为 null
        EntityEditorData.writeNullableUtf(buf, renderName);
        // 人是狐玩家快照：非玩家/未安装时为 null，服务端 PlayerMaidCompat 内部跳过
        EntityEditorData.writeFoxMaid(buf, foxMaid);
    }

    private static void writeTrades(FriendlyByteBuf buf, List<EntityEditorData.TradeEntry> trades) {
        if (trades == null) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeVarInt(trades.size());
        for (EntityEditorData.TradeEntry t : trades) {
            buf.writeResourceLocation(t.costA());
            buf.writeVarInt(t.countA());
            buf.writeNbt(t.costATag());
            buf.writeBoolean(t.hasCostB());
            if (t.hasCostB()) {
                buf.writeResourceLocation(t.costB());
                buf.writeVarInt(t.countB());
                buf.writeNbt(t.costBTag());
            }
            buf.writeResourceLocation(t.result());
            buf.writeVarInt(t.countR());
            buf.writeNbt(t.resultTag());
            buf.writeVarInt(t.maxUses());
            buf.writeVarInt(t.xp());
        }
    }

    private static List<EntityEditorData.TradeEntry> readTrades(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return null;
        }
        int count = buf.readVarInt();
        List<EntityEditorData.TradeEntry> trades = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ResourceLocation costA = buf.readResourceLocation();
            int countA = buf.readVarInt();
            net.minecraft.nbt.CompoundTag costATag = buf.readNbt();
            boolean hasB = buf.readBoolean();
            ResourceLocation costB = null;
            int countB = 0;
            net.minecraft.nbt.CompoundTag costBTag = null;
            if (hasB) {
                costB = buf.readResourceLocation();
                countB = buf.readVarInt();
                costBTag = buf.readNbt();
            }
            ResourceLocation result = buf.readResourceLocation();
            int countR = buf.readVarInt();
            net.minecraft.nbt.CompoundTag resultTag = buf.readNbt();
            int maxUses = buf.readVarInt();
            int xp = buf.readVarInt();
            trades.add(new EntityEditorData.TradeEntry(costA, countA, costATag, hasB, costB, countB, costBTag,
                    result, countR, resultTag, maxUses, xp));
        }
        return trades;
    }

    private static void writeBond(FriendlyByteBuf buf, EntityEditorData.BondSnapshot bond) {
        if (bond == null || !bond.present()) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeBoolean(bond.tmaLoaded());
        buf.writeVarInt(bond.favorability());
        buf.writeVarInt(bond.bondLevel());
        buf.writeBoolean(bond.bondUnlocked());
        buf.writeVarInt(bond.giftQueue());
        buf.writeVarInt(bond.abilities().size());
        for (EntityEditorData.BondAbilityEntry entry : bond.abilities()) {
            buf.writeUtf(entry.id());
            buf.writeBoolean(entry.unlocked());
        }
    }

    private static EntityEditorData.BondSnapshot readBond(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return null;
        }
        boolean tmaLoaded = buf.readBoolean();
        int favorability = buf.readVarInt();
        int bondLevel = buf.readVarInt();
        boolean bondUnlocked = buf.readBoolean();
        int giftQueue = buf.readVarInt();
        int abilityCount = buf.readVarInt();
        List<EntityEditorData.BondAbilityEntry> abilities = new ArrayList<>(abilityCount);
        for (int i = 0; i < abilityCount; i++) {
            abilities.add(new EntityEditorData.BondAbilityEntry(buf.readUtf(64), buf.readBoolean()));
        }
        return new EntityEditorData.BondSnapshot(true, tmaLoaded, favorability, bondLevel,
                bondUnlocked, giftQueue, abilities);
    }

    public static SubmitEntityEditorPacket decode(FriendlyByteBuf buf) {
        int id = buf.readVarInt();

        int attrCount = buf.readVarInt();
        Map<ResourceLocation, Double> attrs = new LinkedHashMap<>();
        for (int i = 0; i < attrCount; i++) {
            attrs.put(buf.readResourceLocation(), buf.readDouble());
        }

        int effectCount = buf.readVarInt();
        List<EntityEditorData.EffectEntry> effects = new ArrayList<>(effectCount);
        for (int i = 0; i < effectCount; i++) {
            effects.add(new EntityEditorData.EffectEntry(
                    buf.readResourceLocation(), buf.readVarInt(), buf.readVarInt()));
        }

        int traitCount = buf.readVarInt();
        List<EntityEditorData.TraitEntry> traits = new ArrayList<>(traitCount);
        for (int i = 0; i < traitCount; i++) {
            traits.add(new EntityEditorData.TraitEntry(
                    buf.readResourceLocation(), buf.readVarInt()));
        }
        int hostilityLevel = buf.readVarInt();

        float health = buf.readFloat();
        boolean remove = buf.readBoolean();
        boolean removalGuard = buf.readBoolean();

        int curiosCount = buf.readVarInt();
        List<EntityEditorData.CuriosSlotEntry> curiosSlots = new ArrayList<>(curiosCount);
        for (int i = 0; i < curiosCount; i++) {
            curiosSlots.add(new EntityEditorData.CuriosSlotEntry(buf.readUtf(64), buf.readVarInt()));
        }
        EntityEditorData.BondSnapshot bond = readBond(buf);
        List<EntityEditorData.TradeEntry> trades = readTrades(buf);
        boolean hasLootPage = buf.readBoolean();
        io.github.zgxhzhr.superdbg.loot.LootConfig entityLoot = null;
        io.github.zgxhzhr.superdbg.loot.LootConfig typeLoot = null;
        if (hasLootPage) {
            entityLoot = io.github.zgxhzhr.superdbg.loot.LootConfig.read(buf);
            typeLoot = io.github.zgxhzhr.superdbg.loot.LootConfig.read(buf);
        }
        boolean hasGiftPage = buf.readBoolean();
        io.github.zgxhzhr.superdbg.gift.GiftPoolConfig giftPool = null;
        if (hasGiftPage) {
            giftPool = io.github.zgxhzhr.superdbg.gift.GiftPoolConfig.read(buf);
        }
        String renderName = EntityEditorData.readNullableUtf(buf);
        EntityEditorData.FoxMaidSnapshot foxMaid = EntityEditorData.readFoxMaid(buf);
        return new SubmitEntityEditorPacket(id, attrs, effects, traits, hostilityLevel, health,
                remove, removalGuard, curiosSlots, bond, trades, hasLootPage, entityLoot, typeLoot,
                hasGiftPage, giftPool, renderName, foxMaid);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> handleOnServer(ctx));
        ctx.setPacketHandled(true);
    }

    private void handleOnServer(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) {
            return;
        }
        if (!player.isCreative()) {
            return;
        }
        // 必须仍然开着实体编辑器
        if (!(player.containerMenu instanceof EntityEditorMenu menu)) {
            return;
        }

        // 重新按 id 取实体（菜单构造后实体可能已消失/被替换）
        Entity entity;
        if (entityId == -1) {
            entity = player;
        } else {
            entity = player.level().getEntity(entityId);
        }
        if (!(entity instanceof LivingEntity target) || !target.isAlive()) {
            return;
        }
        if (entity.getId() != menu.getEntityId()) {
            return;
        }

        // 权限复检：目标可能中途失权（如被提升为 OP）
        if (!EntityEditorService.canEdit(target, player)) {
            return;
        }

        // 女仆保护：非法客户端即使勾选移除也强制忽略（玩家目标不受此限）
        boolean safeRemove = removeEntity && !EntityEditorService.isProtectedMaid(target);

        // 玩家属性会被部分模组每 tick 重算（如 extra_enchantments），
        // 把用户改动的属性记入持久化覆盖表，tick 末强制执行；非玩家生物直接写即可
        if (target instanceof ServerPlayer targetPlayer) {
            PlayerAttributeOverrides.updateFromSubmit(
                    targetPlayer, attrs, EntityEditorData.attrsToMap(menu.getData().attrs()));
        }

        EntityEditorService.apply(target, attrs, effects, safeRemove, health, removalGuard);

        // L2Hostility 词条/难度等级写回；未安装或目标无词条能力时内部直接跳过
        io.github.zgxhzhr.superdbg.compat.l2hostility.L2HostilityCompat.apply(target, traits, hostilityLevel);

        // Curios 饰品栏数量写回；未安装或目标无饰品栏时内部直接跳过
        io.github.zgxhzhr.superdbg.compat.curios.CuriosCompat.apply(target, curiosSlots);

        // 女仆好感度/羁绊写回；非女仆、未装 TMA 或主人离线时内部直接跳过
        io.github.zgxhzhr.superdbg.compat.tma.TmaBondCompat.apply(target, bond);

        // 村民交易列表全量写回；非村民类实体内部直接跳过
        EntityEditorService.applyTrades(target, trades);

        // 掉落覆盖写回；hasLootPage=false（玩家目标）时内部整体跳过
        EntityEditorService.applyLoot(target, hasLootPage, entityLoot, typeLoot);

        // 女仆回礼池写回；hasGiftPage=false（非女仆/未装 TMA）时内部整体跳过
        EntityEditorService.applyGiftPool(target, hasGiftPage, giftPool);

        // 人是狐玩家状态写回；非玩家/未装 playermaid 时内部直接跳过
        io.github.zgxhzhr.superdbg.compat.playermaid.PlayerMaidCompat.apply(target, foxMaid);

        if (target instanceof ServerPlayer targetPlayer) {
            // 玩家渲染名写回（调试器本体存储，不依赖任何第三方模组）
            io.github.zgxhzhr.superdbg.entity.RenderNameStore.set(targetPlayer, renderName);
            // 真实改名：通过 PlayerEvent.NameFormat 覆写显示名（setCustomName 对玩家无效——
            // 1.20.1 的 Player.getDisplayName 直接基于 GameProfile 名并缓存 displayname）。
            // 这里清除 displayname 缓存，使下一次 getDisplayName 重新走 NameFormat 事件拿到新名。
            io.github.zgxhzhr.superdbg.Constants.LOG.info(
                    "[SuperDbg] 渲染名提交 renderName={}", renderName);
            targetPlayer.refreshDisplayName();
            io.github.zgxhzhr.superdbg.Constants.LOG.info(
                    "[SuperDbg] setCustomName 完成 customName={}", targetPlayer.getDisplayName().getString());
            // 广播渲染名：SyncRenderNamePacket（头顶名牌/客户端缓存）+
            // PlayerInfoUpdatePacket（Tab 列表/社交屏幕显示名）
            io.github.zgxhzhr.superdbg.event.PlayerDisplayNameHandler.broadcast(player.getServer(), targetPlayer);
            // 立即再强制一次，保证提交当 tick 结束前值已稳定
            PlayerAttributeOverrides.enforce(targetPlayer);
        }

        if (safeRemove) {
            // 目标已移除，主动关闭菜单，不依赖 stillValid 下 tick 失效
            player.doCloseContainer();
        } else {
            player.containerMenu.broadcastChanges();
        }
    }
}
