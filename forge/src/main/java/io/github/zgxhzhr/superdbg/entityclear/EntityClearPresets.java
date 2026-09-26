package io.github.zgxhzhr.superdbg.entityclear;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.github.zgxhzhr.superdbg.Constants;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 实体清除器"常用方案"的本地存档（配置目录 config/superdbg/entity_clear_presets.json）。
 * <p>
 * 纯客户端本地文件，与具体存档无关，换世界/换整合包仍在。
 * 旧版 config/tiaoshi 目录下的方案文件首次读取时自动迁移。
 */
public final class EntityClearPresets {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<TreeMap<String, EntityClearConfig>>() {
    }.getType();

    private static Map<String, EntityClearConfig> cache;

    private EntityClearPresets() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("superdbg").resolve("entity_clear_presets.json");
    }

    /** 改名前的旧路径（仅用于首次读取迁移） */
    private static Path legacyFile() {
        return FMLPaths.CONFIGDIR.get().resolve("tiaoshi").resolve("entity_clear_presets.json");
    }

    private static Map<String, EntityClearConfig> map() {
        if (cache != null) {
            return cache;
        }
        cache = new TreeMap<>();
        Path path = file();
        Path legacy = legacyFile();
        Path loadFrom = Files.exists(path) ? path
                : (Files.exists(legacy) ? legacy : null);
        if (loadFrom != null) {
            try {
                String json = Files.readString(loadFrom, StandardCharsets.UTF_8);
                Map<String, EntityClearConfig> loaded = GSON.fromJson(json, MAP_TYPE);
                if (loaded != null) {
                    cache.putAll(loaded);
                }
                if (loadFrom == path) {
                    return cache;
                }
                save(); // 从旧目录加载后立即落盘到新目录，完成迁移
            } catch (Exception e) {
                Constants.LOG.warn("[SuperDbg] 读取实体清除方案失败: {}", loadFrom, e);
            }
        }
        return cache;
    }

    private static void save() {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(map(), MAP_TYPE), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Constants.LOG.warn("[SuperDbg] 保存实体清除方案失败: {}", path, e);
        }
    }

    public static List<String> names() {
        return new ArrayList<>(map().keySet());
    }

    public static EntityClearConfig get(String name) {
        EntityClearConfig c = map().get(name);
        return c == null ? null : c.copy();
    }

    public static void put(String name, EntityClearConfig config) {
        map().put(name, config.copy());
        save();
    }

    public static void delete(String name) {
        if (map().remove(name) != null) {
            save();
        }
    }
}
