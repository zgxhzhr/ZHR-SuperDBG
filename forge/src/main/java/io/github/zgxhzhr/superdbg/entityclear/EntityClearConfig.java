package io.github.zgxhzhr.superdbg.entityclear;

import net.minecraft.network.FriendlyByteBuf;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 实体清除器的一份完整配置（客户端编辑 → 打包发服务端；亦可作为常用方案持久化）。
 *
 * <ul>
 *   <li>{@link #scope} 清除范围；{@link #radius} 仅"以我为中心"时生效；</li>
 *   <li>{@link #mode} 目标选取模式：白名单（只清除勾选类型）或排除（全选但不清勾选类型）；</li>
 *   <li>{@link #types} 类型名单（注册 id 字符串），语义随 {@link #mode} 而变；</li>
 *   <li>{@link #protectBits} 保护名单位掩码；</li>
 *   <li>{@link #method} 清除方式。</li>
 * </ul>
 * 大类快捷勾选只在界面层批量改写 {@link #types}，服务端不感知大类。
 */
public final class EntityClearConfig {

    // ---------- 范围 ----------
    public static final int SCOPE_AROUND = 0;
    public static final int SCOPE_DIMENSION = 1;
    public static final int SCOPE_ALL_DIMENSIONS = 2;

    // ---------- 选取模式 ----------
    /** 只清除勾选的类型（名单=清除目标） */
    public static final int MODE_WHITELIST = 0;
    /** 清除全部，但不清除勾选的类型（名单=排除目标） */
    public static final int MODE_BLACKLIST = 1;

    // ---------- 保护位 ----------
    public static final int PROTECT_PLAYERS = 1;
    public static final int PROTECT_TAMED = 1 << 1;
    public static final int PROTECT_NAMED = 1 << 2;
    public static final int PROTECT_BABY = 1 << 3;
    public static final int PROTECT_RIDING = 1 << 4;
    /** 开启了防移除的实体（守卫 NPC 等） */
    public static final int PROTECT_GUARDED = 1 << 5;

    // ---------- 清除方式 ----------
    /** 走正常死亡流程（掉落战利品与经验） */
    public static final int METHOD_KILL = 0;
    /** 直接抹除（不掉落） */
    public static final int METHOD_DISCARD = 1;

    public int scope = SCOPE_AROUND;
    public int radius = 64;
    public int mode = MODE_WHITELIST;
    public int method = METHOD_KILL;
    public int protectBits = PROTECT_PLAYERS | PROTECT_TAMED | PROTECT_NAMED
            | PROTECT_BABY | PROTECT_RIDING | PROTECT_GUARDED;
    public final Set<String> types = new LinkedHashSet<>();

    public EntityClearConfig() {
    }

    public boolean isProtected(int bit) {
        return (protectBits & bit) != 0;
    }

    public void setProtected(int bit, boolean on) {
        if (on) {
            protectBits |= bit;
        } else {
            protectBits &= ~bit;
        }
    }

    public EntityClearConfig copy() {
        EntityClearConfig c = new EntityClearConfig();
        c.scope = scope;
        c.radius = radius;
        c.mode = mode;
        c.method = method;
        c.protectBits = protectBits;
        c.types.addAll(types);
        return c;
    }

    // ---------- 网络序列化 ----------

    public void writeToBuf(FriendlyByteBuf buf) {
        buf.writeVarInt(scope);
        buf.writeVarInt(radius);
        buf.writeVarInt(mode);
        buf.writeVarInt(method);
        buf.writeVarInt(protectBits);
        buf.writeVarInt(types.size());
        for (String id : types) {
            buf.writeUtf(id);
        }
    }

    public static EntityClearConfig readFromBuf(FriendlyByteBuf buf) {
        EntityClearConfig c = new EntityClearConfig();
        c.scope = buf.readVarInt();
        c.radius = buf.readVarInt();
        c.mode = buf.readVarInt();
        c.method = buf.readVarInt();
        c.protectBits = buf.readVarInt();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++) {
            c.types.add(buf.readUtf());
        }
        return c;
    }
}
