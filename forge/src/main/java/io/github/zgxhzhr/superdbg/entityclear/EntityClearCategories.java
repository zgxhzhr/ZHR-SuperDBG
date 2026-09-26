package io.github.zgxhzhr.superdbg.entityclear;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 实体大类划分（仅供清除器界面做快捷批量勾选）。
 * <p>
 * 非生物类别按原版注册 id 识别；生物类别按 {@link MobCategory} 归并，
 * 因此模组生物只要用了标准类别就能自动进入敌对/被动/水生环境分组，
 * 其余模组实体一律落入"其它"，保证不漏。
 */
public enum EntityClearCategories {

    HOSTILE("敌对怪物"),
    PASSIVE("被动动物"),
    WATER("水生/环境生物"),
    VEHICLE("载具（船/矿车）"),
    DISPLAY("展示（画/展示框/盔甲架）"),
    ITEM("掉落物"),
    XP("经验球"),
    PROJECTILE("投射物（箭/法球等）"),
    OTHER("其它");

    /** 界面显示名 */
    public final String displayName;

    EntityClearCategories(String displayName) {
        this.displayName = displayName;
    }

    private static final Map<EntityClearCategories, Set<String>> VANILLA_IDS = new LinkedHashMap<>();

    static {
        VANILLA_IDS.put(VEHICLE, Set.of(
                "minecraft:boat", "minecraft:chest_boat",
                "minecraft:minecart", "minecraft:chest_minecart",
                "minecraft:command_block_minecart", "minecraft:furnace_minecart",
                "minecraft:hopper_minecart", "minecraft:spawner_minecart",
                "minecraft:tnt_minecart"));
        VANILLA_IDS.put(DISPLAY, Set.of(
                "minecraft:item_frame", "minecraft:glow_item_frame",
                "minecraft:painting", "minecraft:armor_stand", "minecraft:leash_knot"));
        VANILLA_IDS.put(ITEM, Set.of("minecraft:item"));
        VANILLA_IDS.put(XP, Set.of("minecraft:experience_orb"));
        VANILLA_IDS.put(PROJECTILE, Set.of(
                "minecraft:arrow", "minecraft:spectral_arrow", "minecraft:trident",
                "minecraft:snowball", "minecraft:egg", "minecraft:ender_pearl",
                "minecraft:eye_of_ender", "minecraft:potion",
                "minecraft:experience_bottle", "minecraft:fireball",
                "minecraft:small_fireball", "minecraft:dragon_fireball",
                "minecraft:wither_skull", "minecraft:firework_rocket",
                "minecraft:llama_spit", "minecraft:shulker_bullet",
                "minecraft:fishing_bobber"));
    }

    /** 判定某个实体类型属于哪个大类 */
    public static EntityClearCategories of(EntityType<?> type) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        String idStr = id.toString();
        for (Map.Entry<EntityClearCategories, Set<String>> e : VANILLA_IDS.entrySet()) {
            if (e.getValue().contains(idStr)) {
                return e.getKey();
            }
        }
        MobCategory mc;
        try {
            mc = type.getCategory();
        } catch (Exception ex) {
            return OTHER;
        }
        return switch (mc) {
            case MONSTER -> HOSTILE;
            case CREATURE -> PASSIVE;
            case AMBIENT, WATER_CREATURE, WATER_AMBIENT,
                 AXOLOTLS, UNDERGROUND_WATER_CREATURE -> WATER;
            default -> OTHER;
        };
    }
}
