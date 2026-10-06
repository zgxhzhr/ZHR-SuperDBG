package io.github.zgxhzhr.superdbg.client;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端持有的玩家自定义渲染名缓存（由服务端同步），按玩家 UUID 索引。
 *
 * <p>玩家死亡重生后实体 id 会变化，因此不能按实体 id 缓存；UUID 稳定，
 * 重生后服务端重发同步包即可刷新。</p>
 */
public final class ClientRenderNameCache {

    private static final Map<UUID, String> NAMES = new ConcurrentHashMap<>();

    private ClientRenderNameCache() {
    }

    /** 更新玩家渲染名；null/空白表示清除。 */
    public static void set(UUID playerUuid, @Nullable String renderName) {
        if (renderName == null || renderName.isBlank()) {
            NAMES.remove(playerUuid);
        } else {
            NAMES.put(playerUuid, renderName);
        }
    }

    /** 读取玩家渲染名；无则返回 null。 */
    @Nullable
    public static String get(UUID playerUuid) {
        return NAMES.get(playerUuid);
    }

    /** 断开连接时清空缓存，避免残留到下个世界。 */
    public static void clear() {
        NAMES.clear();
    }
}
