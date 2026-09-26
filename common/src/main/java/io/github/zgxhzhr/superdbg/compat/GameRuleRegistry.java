package io.github.zgxhzhr.superdbg.compat;

import net.minecraft.world.level.GameRules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GameRules 注册表：SERVER_STARTED 时遍历所有已注册 gamerule 类型，
 * 构建 "id string → GameRules.Key" 映射，供数据包 handler 按字符串键
 * 解析回 Key 并调用 {@code getRule(key).set(value, server)} 写回。
 * <p>
 * 客户端绝不持有 Key 对象——Key 只在服务端有意义，客户端只传 id 字符串和值字符串。
 */
public final class GameRuleRegistry {

    /** id → Key（含类型信息，运行时 instanceof 判断 boolean/int） */
    private static final Map<String, GameRules.Key<?>> BY_ID = new LinkedHashMap<>();

    private static boolean built = false;

    private GameRuleRegistry() {
    }

    /** SERVER_STARTED 时调用一次（幂等） */
    public static synchronized void build() {
        if (built) {
            return;
        }
        BY_ID.clear();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                BY_ID.put(key.getId(), key);
            }
        });
        built = true;
    }

    private static void ensureBuilt() {
        if (!built) {
            build();
        }
    }

    /** 枚举所有已注册 gamerule Key（服务端用） */
    public static List<GameRules.Key<?>> allKeys() {
        ensureBuilt();
        return new ArrayList<>(BY_ID.values());
    }

    /** 按 id 字符串查 Key（服务端写回时解析用） */
    @SuppressWarnings("unchecked")
    public static <T extends GameRules.Value<T>> GameRules.Key<T> getKey(String id) {
        ensureBuilt();
        return (GameRules.Key<T>) BY_ID.get(id);
    }

    /** 按 id 查值类型：true=boolean, false=integer */
    public static boolean isBoolean(String id) {
        GameRules.Key<?> key = getKey(id);
        if (key == null) {
            return false;
        }
        ensureBuilt();
        // visitGameRuleTypes 回调里各自写 found 标记
        boolean[] foundBool = {false};
        boolean[] foundInt = {false};
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public void visitBoolean(GameRules.Key<GameRules.BooleanValue> pKey, GameRules.Type<GameRules.BooleanValue> pType) {
                if (pKey.getId().equals(id)) foundBool[0] = true;
            }
            @Override
            public void visitInteger(GameRules.Key<GameRules.IntegerValue> pKey, GameRules.Type<GameRules.IntegerValue> pType) {
                if (pKey.getId().equals(id)) foundInt[0] = true;
            }
        });
        return foundBool[0] && !foundInt[0];
    }
}
