package io.github.zgxhzhr.superdbg.platform;

import io.github.zgxhzhr.superdbg.Constants;
import io.github.zgxhzhr.superdbg.platform.services.IPlatformHelper;

import java.util.ServiceLoader;

/**
 * 服务加载器，用于在 common 代码中访问加载器特定的实现。
 */
public class Services {

    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    public static <T> T load(Class<T> clazz) {
        final T loadedService = ServiceLoader.load(clazz)
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
        Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz);
        return loadedService;
    }
}
