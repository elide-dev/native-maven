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

import javax.inject.Named;
import javax.inject.Singleton;

import java.util.List;

import nmvn.launcher.PrebuiltPluginRealms;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.DefaultPluginDescriptorCache;
import org.apache.maven.plugin.InvalidPluginDescriptorException;
import org.apache.maven.plugin.PluginDescriptorParsingException;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.sisu.Priority;

/**
 * Sidecar override of Maven's plugin descriptor cache ({@code @Priority} outranks the default
 * binding; discovered via this jar's META-INF/sisu index). This is the ROUTING seam for prebuilt
 * plugins: stock {@code DefaultMavenPluginManager.getPluginDescriptor} consults this cache before
 * resolving anything, so serving the baked descriptor here means no repository resolution and no
 * runtime plugin.xml parsing — with ZERO maven-core changes.
 *
 * <p>Serves a CLONE per get, exactly like the default cache (clone-on-get keeps descriptors
 * realm-less until setupPluginRealm and avoids shared mutable state; the shallow MojoDescriptor
 * clone preserves the build-time-pinned implementation classes). Non-prebuilt plugins delegate to
 * the stock implementation unchanged.
 *
 * <p>Prebuilt routing applies only when the project adds no extra per-plugin {@code <dependencies>}
 * and the plugin is not an extensions plugin — the same predicate as {@link PrebuiltPluginRealmCache},
 * so a plugin is either fully prebuilt (descriptor + realm) or fully dynamic, never mixed.
 */
@Named
@Singleton
@Priority(10)
public class PrebuiltPluginDescriptorCache extends DefaultPluginDescriptorCache {

    public PrebuiltPluginDescriptorCache() {
        org.slf4j.LoggerFactory.getLogger(getClass())
                .info("nmvn: descriptor cache override active ({})", PrebuiltPluginRealms.STATUS);
    }

    /** Key marking a plugin that is served from the baked registry. */
    private static final class PrebuiltKey implements Key {
        final PrebuiltPluginRealms.Prebuilt prebuilt;

        PrebuiltKey(PrebuiltPluginRealms.Prebuilt prebuilt) {
            this.prebuilt = prebuilt;
        }
    }

    @Override
    public Key createKey(Plugin plugin, List<RemoteRepository> repositories, RepositorySystemSession session) {
        // Same routing predicate as PrebuiltPluginRealmCache: a plugin is either fully prebuilt or
        // fully dynamic. Serving a baked descriptor (which has no pluginArtifact) while the realm
        // side falls back to stock createPluginRealm would NPE on getPluginArtifact().
        //
        // Per-plugin <dependencies> are ALLOWED here (a pom-specialized image knows them at build
        // time and bakes them into the realm), but match() requires them to be identical to the ones
        // the realm was baked with. <extensions>true</extensions> stays dynamic unconditionally:
        // extension plugins get a structurally different realm — different imports and visibility —
        // not merely different contents.
        if (plugin.isExtensions()) {
            PrebuiltRoutingLog.dynamic(plugin, "extensions=true (not bakeable)");
            return super.createKey(plugin, repositories, session);
        }
        PrebuiltPluginRealms.Route route = PrebuiltPluginRealms.route(
                plugin.getGroupId(),
                plugin.getArtifactId(),
                plugin.getVersion(),
                PrebuiltPluginRealms.dependencyKey(plugin.getDependencies()));
        if (route.isBaked()) {
            PrebuiltRoutingLog.baked(plugin);
            return new PrebuiltKey(route.prebuilt);
        }
        PrebuiltRoutingLog.dynamic(plugin, route.dynamicReason);
        return super.createKey(plugin, repositories, session);
    }

    @Override
    public PluginDescriptor get(Key key) {
        if (key instanceof PrebuiltKey prebuiltKey) {
            return new PluginDescriptor(prebuiltKey.prebuilt.descriptor);
        }
        return super.get(key);
    }

    @Override
    public PluginDescriptor get(Key key, PluginDescriptorSupplier supplier)
            throws PluginResolutionException, PluginDescriptorParsingException, InvalidPluginDescriptorException {
        if (key instanceof PrebuiltKey prebuiltKey) {
            // never invoke the supplier: it would resolve the plugin from the repository
            return new PluginDescriptor(prebuiltKey.prebuilt.descriptor);
        }
        return super.get(key, supplier);
    }

    @Override
    public void put(Key key, PluginDescriptor descriptor) {
        if (key instanceof PrebuiltKey) {
            return; // baked entries are immutable; nothing to store
        }
        super.put(key, descriptor);
    }
}
