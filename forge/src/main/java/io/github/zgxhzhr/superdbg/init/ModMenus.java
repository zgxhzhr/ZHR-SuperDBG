package io.github.zgxhzhr.superdbg.init;

import io.github.zgxhzhr.superdbg.Constants;
import io.github.zgxhzhr.superdbg.menu.EntityEditorMenu;
import io.github.zgxhzhr.superdbg.menu.ItemEditorMenu;
import io.github.zgxhzhr.superdbg.menu.PotionEditorMenu;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 模组菜单类型注册。
 * <p>
 * {@link MenuType} 属于注册表内容，必须双端注册；
 * 仅 Screen 的注册放在客户端初始化事件中。
 */
public final class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, Constants.MOD_ID);

    public static final RegistryObject<MenuType<PotionEditorMenu>> POTION_EDITOR =
            MENU_TYPES.register("potion_editor", () ->
                    new MenuType<>((windowId, inventory) -> new PotionEditorMenu(windowId, inventory),
                            FeatureFlags.VANILLA_SET));

    public static final RegistryObject<MenuType<ItemEditorMenu>> ITEM_EDITOR =
            MENU_TYPES.register("item_editor", () ->
                    new MenuType<>((windowId, inventory) -> new ItemEditorMenu(windowId, inventory),
                            FeatureFlags.VANILLA_SET));

    /**
     * 实体编辑器：客户端菜单需要从 extraData 读取实体 id 与数据快照，
     * 必须用 IForgeMenuType.create 的三参工厂（服务端菜单由 provider.createMenu 直接构造）。
     */
    public static final RegistryObject<MenuType<EntityEditorMenu>> ENTITY_EDITOR =
            MENU_TYPES.register("entity_editor", () ->
                    IForgeMenuType.create((windowId, inventory, buf) ->
                            new EntityEditorMenu(windowId, inventory, buf)));

    private ModMenus() {
    }
}
