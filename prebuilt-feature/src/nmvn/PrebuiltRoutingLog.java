package nmvn;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.maven.model.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Once-per-plugin-id INFO logs for prebuilt routing decisions so a native binary run shows which
 * plugins are baked vs dynamic without spamming multi-module builds.
 *
 * <p>Disable with {@code -Dnmvn.routing.log=false}. Use DEBUG on this logger for every call site.
 */
public final class PrebuiltRoutingLog {

    private static final Logger LOG = LoggerFactory.getLogger(PrebuiltRoutingLog.class);

    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private PrebuiltRoutingLog() {}

    public static void baked(Plugin plugin) {
        logOnce(pluginId(plugin), true, null);
    }

    public static void dynamic(Plugin plugin, String reason) {
        logOnce(pluginId(plugin), false, reason);
    }

    private static void logOnce(String pluginId, boolean baked, String reason) {
        if (!Boolean.parseBoolean(System.getProperty("nmvn.routing.log", "true"))) {
            return;
        }
        if (!LOGGED.add(pluginId)) {
            LOG.debug("nmvn: route {} (already reported)", pluginId);
            return;
        }
        if (baked) {
            LOG.info("nmvn: plugin {} → BAKED (prebuilt realm)", pluginId);
        } else {
            LOG.info("nmvn: plugin {} → DYNAMIC ({})", pluginId, reason != null ? reason : "fallback");
        }
    }

    private static String pluginId(Plugin plugin) {
        if (plugin == null) {
            return "?:?:?";
        }
        return plugin.getGroupId() + ":" + plugin.getArtifactId() + ":" + plugin.getVersion();
    }
}
