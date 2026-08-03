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

import org.apache.maven.cling.MavenCling;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.classworlds.realm.ClassRealm;

/**
 * nmvn image entry point. Replaces the classworlds Launcher: instead of building a ClassWorld from
 * m2.conf at runtime, it takes the BAKED world (plexus.core + prebuilt plugin realms, constructed at
 * image build time by PrebuiltPluginRealms), applies the single runtime fixup the snapshot needs,
 * and hands the world to stock Maven via {@code MavenCling.main(args, world)} — the "enhanced"
 * entry point Maven already exposes for external worlds.
 *
 * <p>The one fixup: no hosted class loader reference survives the heap snapshot (verified for both
 * the image classloader and the system loader, 2026-07-03), so the baked plexus.core wakes up with
 * a null parent. Re-attaching the runtime system loader restores delegation to the AOT core classes
 * and the embedded META-INF resources. Everything below plexus.core (the plugin realms) was
 * parented to it at build time — baked-realm-to-baked-realm references survive verbatim, so no
 * per-plugin fixup exists anymore.
 */
public final class NmvnLauncher {

    private NmvnLauncher() {}

    public static void main(String[] args) throws Exception {
        ClassWorld world = PrebuiltPluginRealms.world();
        ClassRealm core = world == null ? null : world.getClassRealm(PrebuiltPluginRealms.CORE_REALM_ID);
        if (core == null) {
            // Build-time init failed or was skipped — fall back to a plain world so Maven still
            // boots (all plugins resolve dynamically).
            world = new ClassWorld(PrebuiltPluginRealms.CORE_REALM_ID, ClassLoader.getSystemClassLoader());
            core = world.getClassRealm(PrebuiltPluginRealms.CORE_REALM_ID);
        } else {
            // THE single runtime fixup of the baked hierarchy (see class doc).
            core.setParentClassLoader(ClassLoader.getSystemClassLoader());
        }
        Thread.currentThread().setContextClassLoader(core);
        diagnose(core);
        System.exit(MavenCling.main(args, world));
    }

    /** Startup diagnosis for the sidecar-activation path (temporary; prints to stderr). */
    private static void diagnose(ClassRealm core) {
        try {
            int indexes = 0;
            int nmvnIndexes = 0;
            java.util.Enumeration<java.net.URL> urls = core.getResources("META-INF/sisu/javax.inject.Named");
            while (urls.hasMoreElements()) {
                java.net.URL url = urls.nextElement();
                indexes++;
                try (java.io.InputStream in = url.openStream()) {
                    if (new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                            .contains("org.apache.maven.nmvn.features.")) {
                        nmvnIndexes++;
                    }
                }
            }
            Class<?> cache = Class.forName("org.apache.maven.nmvn.features.PrebuiltPluginDescriptorCache");
            System.err.println("nmvn diag: sisu indexes visible=" + indexes + " (containing nmvn entries="
                    + nmvnIndexes + "), cache @Named=" + (cache.getAnnotation(javax.inject.Named.class) != null)
                    + " @Priority=" + (cache.getAnnotation(org.eclipse.sisu.Priority.class) != null));
        } catch (Throwable t) {
            System.err.println("nmvn diag FAILED: " + t);
        }
    }
}
