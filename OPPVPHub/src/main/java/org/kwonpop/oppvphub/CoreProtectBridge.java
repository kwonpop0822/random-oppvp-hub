package org.kwonpop.oppvphub;

import java.lang.reflect.Method;
import org.bukkit.plugin.Plugin;

/** Reflection keeps CoreProtect optional and avoids packaging its API. */
final class CoreProtectBridge {
    private CoreProtectBridge() { }

    static Integer apiVersion(Plugin plugin) {
        try {
            Method getApi = plugin.getClass().getMethod("getAPI");
            Object api = getApi.invoke(plugin);
            Method apiVersion = api.getClass().getMethod("APIVersion");
            Object value = apiVersion.invoke(api);
            return value instanceof Integer number ? number : null;
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }
}
