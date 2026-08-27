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
 * binding, same seam pattern as {@link PrebuiltPluginDescriptorCache}). This is the
 * EXECUTION-TIME seam where the launcher's {@code --mode} policy ({@link NmvnMode}) is applied to
 * a mojo whose plugin is not baked — a NON-CREMA concern only: the frozen image cannot load the
 * plugin's classes, so instead of letting {@code getConfiguredMojo} die on ClassNotFoundException
 * the execution is either delegated — at its exact slot in the lifecycle plan, interleaved with
 * baked mojos — to a HotSpot JVM booted inside this process ({@code --mode=mixed}, see
 * {@link HotspotMavenRunner}), or failed fast with follow-up suggestions ({@code --mode=native}).
 * {@code --mode=legacy} never reaches this seam: the launcher runs the whole build on the HotSpot
 * JVM before Maven boots natively. On crema ({@link PrebuiltPluginRealms#RUNTIME_CLASS_LOADING})
 * the whole mode machinery is disabled — the launcher rejects {@code --mode}, this seam stays
 * inert, and runtime class loading serves non-baked plugins natively through the stock path;
 * crema IS the JVM in that sense.
 *
 * <p>Baked/non-baked routing mirrors the descriptor/realm caches ({@link
 * PrebuiltPluginRealms#route}): baked plugins take the stock path (which the caches then serve
 * from the image heap); everything else — not baked, version mismatch, differing per-plugin
 * {@code <dependencies>}, extensions plugins — is non-baked. On plain-JVM runs (tests, dev) this
 * class is inert — mode handling requires image runtime ({@link NmvnMode#imageRuntime()}), and
 * stock dynamic resolution handles everything — UNLESS Mock Dual JVM Mode activates the seam
 * ({@code nmvn.plugins.<artifactId>=dynamic} properties, see
 * {@code HotspotMavenRunner.mockDualJvm} and JVM-FALLBACK.md "Mock Mode"), which always behaves
 * like {@code --mode=mixed}.
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
        if (HotspotMavenRunner.enabled() && !isExecuteDirect(mojoExecution)) {
            // Mock Dual JVM Mode (plain JVM) exists to exercise the delegation itself, so it
            // always behaves like --mode=mixed; inside the image the launcher's mode applies.
            NmvnMode mode = NmvnMode.imageRuntime() ? NmvnMode.current() : NmvnMode.MIXED;
            switch (mode) {
                case NATIVE:
                    throw notBakedInNativeMode(mojoExecution);
                case MIXED:
                case LEGACY: // unreachable via the launcher (legacy short-circuits before Maven
                    // boots); a forced -Dnmvn.mode=legacy degrades to mixed
                    try {
                        HotspotMavenRunner.execute(session, mojoExecution);
                    } catch (HotspotMavenRunner.HotspotGoalFailedException e) {
                        // the delegated plugin failed — surface it like a mojo failure, not an
                        // infra error
                        throw new MojoExecutionException(e.getMessage(), e);
                    } catch (IOException e) {
                        throw new MojoExecutionException("JVM fallback for " + mojoExecution + " failed", e);
                    }
                    return;
            }
        }
        super.executeMojo(session, mojoExecution);
    }

    /** The {@code --mode=native} contract: a non-baked plugin ends the build with follow-up suggestions. */
    private static MojoExecutionException notBakedInNativeMode(MojoExecution mojoExecution) {
        Plugin plugin = mojoExecution.getPlugin();
        StringBuilder gav =
                new StringBuilder().append(plugin.getGroupId()).append(':').append(plugin.getArtifactId());
        if (plugin.getVersion() != null) {
            gav.append(':').append(plugin.getVersion());
        }
        return new MojoExecutionException("Plugin " + gav + " is not baked into this Native Maven binary,"
                + " and the current mode (--mode=native) runs baked-in plugins only. Possible next steps:\n"
                + "  - rerun with --mode=mixed to run this plugin on an embedded HotSpot JVM"
                + " (baked plugins keep running natively)\n"
                + "  - use a Native Maven flavor that bakes this plugin (--flavor=..., see NATIVEMVN.md)\n"
                + "  - rerun with --mode=legacy to run the whole build on a HotSpot JVM, like stock Apache Maven");
    }

    private static boolean isExecuteDirect(MojoExecution mojoExecution) {
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
                .isDirect();
    }
}
