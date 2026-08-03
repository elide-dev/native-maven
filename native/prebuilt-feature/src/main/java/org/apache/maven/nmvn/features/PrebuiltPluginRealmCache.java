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
package org.apache.maven.org.apache.maven.nmvn.features.features;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.maven.execution.scope.internal.MojoExecutionScope;
import org.apache.maven.execution.scope.internal.MojoExecutionScopeModule;
import org.apache.maven.internal.impl.SisuDiBridgeModule;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.DefaultPluginRealmCache;
import org.apache.maven.plugin.PluginContainerException;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.project.MavenProject;
import org.apache.maven.session.scope.internal.SessionScope;
import org.apache.maven.session.scope.internal.SessionScopeModule;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.component.repository.ComponentDescriptor;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.sisu.Priority;
import org.eclipse.sisu.plexus.ComponentDescriptorBeanModule;
import org.eclipse.sisu.plexus.PlexusBeanModule;
import org.eclipse.sisu.space.QualifiedTypeBinder;
import org.eclipse.sisu.space.URLClassSpace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sidecar override of Maven's plugin realm cache. This is the second ROUTING seam: stock
 * {@code DefaultMavenPluginManager.setupPluginRealm} consults this cache before building a realm,
 * so serving the baked realm here means no dependency resolution and no dynamic realm construction
 * — with ZERO maven-core changes. On first serve it also performs the one-time Sisu PUBLICATION of
 * the realm's beans.
 *
 * <p>Publication deliberately bypasses {@code container.discoverComponents}: its scanning modules
 * read META-INF indexes through the realm, which on a frozen realm fall through to the parent chain
 * and find the image-embedded CORE indexes — re-binding core components under the plugin realm
 * (duplicate bindings; breaks sisu {@code Map<String, T>} injection). Instead ONE injector is built
 * via the public {@code addPlexusInjector} API from a single ComponentDescriptorBeanModule holding
 * the baked mojo descriptors (realm + implementation Class pinned at build time) plus the baked
 * plugin-internal components. {@code URLClassSpace(realm).toString()} equals the realm's toString,
 * which keeps sisu's realm-visibility filtering working.
 *
 * <p>Prebuilt routing applies only when the project adds no extra per-plugin {@code <dependencies>}
 * — those change the effective classpath, so such plugins fall back to stock dynamic resolution.
 */
@Named
@Singleton
@Priority(10)
public class PrebuiltPluginRealmCache extends DefaultPluginRealmCache {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final PlexusContainer container;

    @Inject
    public PrebuiltPluginRealmCache(PlexusContainer container) {
        this.container = container;
        logger.info("nmvn: realm cache override active");
    }

    /** Key marking a plugin that is served from the baked registry. */
    private static final class PrebuiltKey implements Key {
        final PrebuiltPluginRealms.Prebuilt prebuilt;
        final Plugin plugin;

        PrebuiltKey(PrebuiltPluginRealms.Prebuilt prebuilt, Plugin plugin) {
            this.prebuilt = prebuilt;
            this.plugin = plugin;
        }
    }

    @Override
    public Key createKey(
            Plugin plugin,
            ClassLoader parent,
            Map<String, ClassLoader> foreignImports,
            DependencyFilter dependencyFilter,
            List<RemoteRepository> repositories,
            RepositorySystemSession session) {
        // Per-plugin <dependencies> are allowed, but match() demands they be exactly the ones the
        // realm was baked with — they add jars, override versions and apply exclusions, so a
        // different set means a different realm (stock DefaultPluginRealmCache.createKey includes
        // them in its key for the same reason). Mismatch falls through to dynamic resolution.
        // Descriptor cache already logs the route; log here too so realm-only lookups still show up
        // (once-per-plugin-id via PrebuiltRoutingLog).
        PrebuiltPluginRealms.Route route = PrebuiltPluginRealms.route(
                plugin.getGroupId(),
                plugin.getArtifactId(),
                plugin.getVersion(),
                PrebuiltPluginRealms.dependencyKey(plugin.getDependencies()));
        if (route.isBaked()) {
            PrebuiltRoutingLog.baked(plugin);
            return new PrebuiltKey(route.prebuilt, plugin);
        }
        PrebuiltRoutingLog.dynamic(plugin, route.dynamicReason);
        return super.createKey(plugin, parent, foreignImports, dependencyFilter, repositories, session);
    }

    @Override
    public CacheRecord get(Key key) {
        if (key instanceof PrebuiltKey prebuiltKey) {
            return serve(prebuiltKey);
        }
        return super.get(key);
    }

    @Override
    public CacheRecord get(Key key, PluginRealmSupplier supplier)
            throws PluginResolutionException, PluginContainerException {
        if (key instanceof PrebuiltKey prebuiltKey) {
            // never invoke the supplier: it would resolve dependencies and build a dynamic realm
            return serve(prebuiltKey);
        }
        return super.get(key, supplier);
    }

    // NOTE: no put(...) override needed — put is only reached from the default get's cache-miss
    // handler, and prebuilt keys never miss (serve() returns before the supplier runs).

    @Override
    public void register(MavenProject project, Key key, CacheRecord record) {
        if (key instanceof PrebuiltKey) {
            return; // baked realms are never flushed/disposed per project
        }
        super.register(project, key, record);
    }

    private CacheRecord serve(PrebuiltKey key) {
        publishOnce(key.prebuilt, key.plugin);
        // Baked artifacts back pluginDescriptor.getArtifacts() — surefire's booter lookup needs them.
        return new CacheRecord(key.prebuilt.realm, new ArrayList<>(key.prebuilt.artifacts));
    }

    private void publishOnce(PrebuiltPluginRealms.Prebuilt prebuilt, Plugin plugin) {
        if (prebuilt.published) {
            return;
        }
        synchronized (prebuilt) {
            if (prebuilt.published) {
                return;
            }
            ClassRealm realm = prebuilt.realm;
            List<ComponentDescriptor<?>> descriptors = new ArrayList<>();
            for (MojoDescriptor mojo : prebuilt.descriptor.getMojos()) {
                if (!mojo.isV4Api()) {
                    // realm and implementation Class were pinned at build time, in the wipe-safe order
                    descriptors.add(mojo);
                }
            }
            descriptors.addAll(prebuilt.components);

            ClassLoader prevTccl = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(realm);
                URLClassSpace space = new URLClassSpace(realm);
                // Sisu-indexed classes go through QualifiedTypeBinder — the binder stock index
                // scanning uses — with the pinned Class objects standing in for the scan; the
                // space's toString (== realm toString) keeps realm-visibility filtering intact.
                PlexusBeanModule indexedModule = binder -> {
                    QualifiedTypeBinder qualifiedBinder = new QualifiedTypeBinder(binder);
                    for (Class<?> type : prebuilt.indexedClasses) {
                        qualifiedBinder.hear(type, space.toString());
                    }
                    return null;
                };
                ((DefaultPlexusContainer) container)
                        .addPlexusInjector(
                                List.of(new ComponentDescriptorBeanModule(space, descriptors), indexedModule),
                                new SessionScopeModule(container.lookup(SessionScope.class)),
                                new MojoExecutionScopeModule(container.lookup(MojoExecutionScope.class)),
                                new PrebuiltPluginConfigurationModule(plugin.getDelegate()),
                                new SisuDiBridgeModule(true));
                prebuilt.published = true;
                logger.info(
                        "nmvn: published PREBUILT plugin {} ({} descriptors, {} baked components, {} indexed) from {}",
                        plugin.getId(),
                        descriptors.size(),
                        prebuilt.components.size(),
                        prebuilt.indexedClasses.size(),
                        realm);
            } catch (ComponentLookupException e) {
                throw new IllegalStateException("nmvn: failed to publish prebuilt plugin " + plugin.getId(), e);
            } finally {
                Thread.currentThread().setContextClassLoader(prevTccl);
            }
        }
    }
}
