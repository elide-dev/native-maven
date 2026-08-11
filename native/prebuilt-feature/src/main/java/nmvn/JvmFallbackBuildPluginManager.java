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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import java.io.IOException;
import java.util.List;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.MojoExecutionListener;
import org.apache.maven.execution.scope.internal.MojoExecutionScope;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.DefaultBuildPluginManager;
import org.apache.maven.plugin.LegacySupport;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.PluginConfigurationException;
import org.apache.maven.plugin.PluginManagerException;
import org.eclipse.sisu.Priority;

/**
 * Sidecar override of Maven's build plugin manager ({@code @Priority} outranks the default
 * binding, same seam pattern as {@link PrebuiltPluginDescriptorCache}). This is the EXECUTION-TIME
 * fallback for the non-crema image: a mojo whose plugin is not baked cannot run in the frozen
 * image (no runtime class loading), so instead of letting {@code getConfiguredMojo} die on
 * ClassNotFoundException, the mojo execution is delegated — at its exact slot in the lifecycle
 * plan, interleaved with baked mojos — to a HotSpot JVM booted inside this process (see
 * {@link HotspotMavenRunner}).
 *
 * <p>Routing mirrors the descriptor/realm caches ({@link PrebuiltPluginRealms#route}): baked
 * plugins take the stock path (which the caches then serve from the image heap); everything else —
 * not baked, version mismatch, differing per-plugin {@code <dependencies>}, extensions plugins —
 * goes to the JVM. On plain-JVM runs (tests, dev) this class is inert: {@code
 * HotspotMavenRunner.enabled()} requires image runtime, and stock dynamic resolution handles
 * everything.
 */
@Named
@Singleton
@Priority(10)
public class JvmFallbackBuildPluginManager extends DefaultBuildPluginManager {

    @Inject
    public JvmFallbackBuildPluginManager(
            MavenPluginManager mavenPluginManager,
            LegacySupport legacySupport,
            MojoExecutionScope scope,
            List<MojoExecutionListener> mojoExecutionListeners) {
        super(mavenPluginManager, legacySupport, scope, mojoExecutionListeners);
    }

    @Override
    public void executeMojo(MavenSession session, MojoExecution mojoExecution)
            throws MojoFailureException, MojoExecutionException, PluginConfigurationException, PluginManagerException {
        if (HotspotMavenRunner.enabled() && !isBaked(mojoExecution)) {
            try {
                HotspotMavenRunner.execute(session, mojoExecution);
            } catch (HotspotMavenRunner.HotspotGoalFailedException e) {
                // the delegated plugin failed — surface it like a mojo failure, not an infra error
                throw new MojoExecutionException(e.getMessage(), e);
            } catch (IOException e) {
                throw new MojoExecutionException("JVM fallback for " + mojoExecution + " failed", e);
            }
            return;
        }
        super.executeMojo(session, mojoExecution);
    }

    private static boolean isBaked(MojoExecution mojoExecution) {
        Plugin plugin = mojoExecution.getPlugin();
        if (plugin == null || plugin.isExtensions()) {
            // extensions plugins are never baked (structurally different realm); if one made it
            // this far, only the JVM can run it
            return false;
        }
        return PrebuiltPluginRealms.route(
                        plugin.getGroupId(),
                        plugin.getArtifactId(),
                        plugin.getVersion(),
                        PrebuiltPluginRealms.dependencyKey(plugin.getDependencies()))
                .isBaked();
    }
}
