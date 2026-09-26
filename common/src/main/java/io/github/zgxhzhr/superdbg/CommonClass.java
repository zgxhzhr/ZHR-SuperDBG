package io.github.zgxhzhr.superdbg;

/**
 * 跨加载器公共初始化入口。
 * <p>
 * common 模块只能引用原版代码与加载器无关的库，
 * Forge/Fabric 特有的事件、网络、注册表等必须放在对应加载器模块中。
 */
public class CommonClass {

    public static void init() {
        Constants.LOG.info("{} common init", Constants.MOD_NAME);
    }
}
