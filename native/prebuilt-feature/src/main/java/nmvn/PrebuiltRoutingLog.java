/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package nmvn;

import java.io.OutputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.maven.model.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Once-per-plugin-id INFO logs for prebuilt routing decisions so a native binary run shows which
 * plugins are running directly vs dynamic without spamming multi-module builds.
 *
 * <p>Disable with {@code -Dnmvn.routing.log=false}. Use DEBUG on this logger for every call site.
 */
public final class PrebuiltRoutingLog {

    private static final Logger LOG = LoggerFactory.getLogger(PrebuiltRoutingLog.class);

    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
    /** captures original output stream, if any */
    static OutputStream originalStream = System.out;

    private PrebuiltRoutingLog() {}

    public static void reset() {
        LOGGED.clear();
    }

    public static synchronized void originalStdOut(OutputStream os) {
        if (os != null && originalStream == null) {
            originalStream = os;
        }
    }

    static void log(Plugin plugin, PrebuiltPluginRealms.Route route) {
        var id = pluginId(plugin);
        if (route.isDirect()) {
            var reason = route.dynamicReason;
            if (reason == null) {
                reason = route.isBaked() ? "prebuilt realm" : "no other jvm";
            }
            logOnce(id, true, reason);
        } else {
            logOnce(id, false, route.dynamicReason);
        }
    }

    static void dynamic(Plugin plugin, String reason) {
        logOnce(pluginId(plugin), false, reason);
    }

    private static void logOnce(String pluginId, boolean direct, String reason) {
        if (!Boolean.parseBoolean(System.getProperty("nmvn.routing.log", "true"))) {
            return;
        }
        if (!LOGGED.add(pluginId)) {
            LOG.debug("nmvn: route {} (already reported)", pluginId);
            return;
        }
        if (reason == null) {
            reason = "fallback";
        }
        if (direct) {
            LOG.info("nmvn: plugin {} → DIRECT ({})", pluginId, reason);
        } else {
            LOG.info("nmvn: plugin {} → DYNAMIC ({})", pluginId, reason);
        }
    }

    private static String pluginId(Plugin plugin) {
        if (plugin == null) {
            return "?:?:?";
        }
        return plugin.getGroupId() + ":" + plugin.getArtifactId() + ":" + plugin.getVersion();
    }
}
