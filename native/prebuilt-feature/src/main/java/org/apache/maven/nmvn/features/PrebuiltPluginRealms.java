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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptorBuilder;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.component.repository.ComponentDescriptor;

/**
 * Build-time-initialized "baked world" for the GraalVM native image (nmvn): ONE {@link ClassWorld}
 * containing a {@code plexus.core} realm and one isolated child realm per prebuilt plugin,
 * snapshotted into the image heap. Lives in the nmvn SIDECAR (not maven-core) — stock Maven is
 * wired to it exclusively through public seams: {@code MavenCling.main(args, world)} for the boot
 * (NmvnLauncher) and {@code @Priority}-overridden plugin caches for descriptor/realm routing
 * (PrebuiltPluginDescriptorCache / PrebuiltPluginRealmCache).
 *
 * <p><b>Topology (why a whole world, not leaf realms).</b> A snapshotted field can only reference
 * objects that exist in the image heap. Realm parents that point at HOSTED class loaders are nulled
 * by SVM (verified for both the image classloader and {@code ClassLoader.getSystemClassLoader()},
 * 2026-07-03) — but a parent that is ANOTHER BAKED REALM is an ordinary heap reference and survives
 * verbatim. So the plugin realms are parented to a baked {@code plexus.core} realm at build time,
 * and the ONLY runtime fixup left in the whole hierarchy is re-attaching {@code plexus.core}'s own
 * parent to the runtime system loader (done by {@link NmvnLauncher}).
 *
 * <p><b>Class identity (why plugins are LOADED self-first with the hosted loader as strategy
 * parent, then re-parented).</b> Plugin classes link against shared API types ({@code Mojo},
 * {@code AbstractMojo}) during build-time loading. Those must resolve to the image-classpath
 * copies — the single canonical classes that are AOT-compiled and that the runtime container
 * uses. If the plugin realms' parent were the baked core realm DURING loading, the core realm
 * would have to carry the lib jars and would then DEFINE DUPLICATE copies of core classes,
 * breaking {@code instanceof} and Guice keys at runtime. Hence the two-step dance in
 * {@link #buildAll}: load and pin self-first with the hosted loader as the STRATEGY parent
 * (reached only for classes absent from the realm jars — exactly the shared API types, which the
 * build script strips from realm classpaths), then {@code setParentRealm(core)} before the
 * snapshot. The hosted loader must NOT be the realm's URLClassLoader parent: that order is
 * parent-first and mixes image-classpath and realm copies of doubly-present libraries (see the
 * step-1 comment in buildAll). The baked core realm itself carries NO jar URLs.
 *
 * <p><b>Frozen realms cannot load by name at runtime</b> — not even under Crema with the jar
 * present on disk (verified 2026-07-02). Every plugin class is therefore force-loaded at build time
 * into {@link Prebuilt#classes}, and by-name lookups are revived by the IMPORT SHIM: every plugin
 * package gets a classworlds import pointing at a {@link BakedClassLoader} over that map, consulted
 * BEFORE the dead self. Mojo implementation classes are additionally pinned on their descriptors.
 * ORDER MATTERS when pinning: sisu's {@code ComponentDescriptor.setRealm(...)} nulls the cached
 * implementation class, so {@code setRealm} must precede {@code setImplementationClass}.
 *
 * <p><b>Configuration.</b> {@code -Dorg.apache.maven.nmvn.features.prebuilt.plugins} (or {@code -Dorg.apache.maven.nmvn.features.prebuilt.pluginsFile})
 * is a <em>newline</em>-separated list of
 * {@code groupId:artifactId:version=jar1{File.pathSeparator}jar2...} entries (first jar = plugin
 * artifact, rest = resolved runtime classpath). Newlines are required because on Windows
 * {@code File.pathSeparator} is {@code ';'} — using {@code ';'} as the entry separator would split
 * jar paths. When absent (normal JVM run, tests) the registry is empty and everything falls back
 * to stock dynamic resolution.
 */
public final class PrebuiltPluginRealms {

    /** One prebuilt plugin: its parsed descriptor, isolated realm, and baked class/component data. */
    public static final class Prebuilt {
        public final PluginDescriptor descriptor;
        public final ClassRealm realm;

        /** EVERY class of every realm jar, force-loaded at build time (frozen realms cannot load at runtime). */
        public final Map<String, Class<?>> classes;

        /**
         * Plugin-internal components declared in legacy {@code META-INF/plexus/components.xml}
         * (e.g. org.sonatype plexus-build-api's BuildContext, injected by maven-filtering), parsed
         * at build time with PINNED implementation classes. Stock discovery would parse the XML
         * through the realm at runtime — impossible on a frozen realm — so they are baked instead
         * and registered via ComponentDescriptorBeanModule (LoadedClass path for pinned
         * descriptors).
         */
        public final List<ComponentDescriptor<?>> components;

        /**
         * Classes listed in the jars' {@code META-INF/sisu/javax.inject.Named} indexes (e.g.
         * plexus-compiler's CompilerManager), pinned at build time. Published via
         * QualifiedTypeBinder — the SAME binder stock sisu index scanning uses — because
         * synthesizing ComponentDescriptors for them changes provisioning semantics: a descriptor
         * whose role IS the implementation binds {@code named(hint) -> raw type}, and a raw
         * injection point (surefire's ProviderDetector injecting the concrete ServiceLoader) then
         * resolves through LocatorWiring back into that same binding — a self-referential circle
         * Guice can only break by proxying, impossible for a concrete class.
         */
        public final List<Class<?>> indexedClasses;

        /**
         * The realm jars as resolved plugin {@link Artifact}s (GAV from each jar's pom.properties,
         * local-repo path layout as fallback). Served in the realm CacheRecord so
         * {@code pluginDescriptor.getArtifacts()} — and with it surefire's pluginArtifactMap
         * lookup of surefire-booter for the forked JVM — works like on a dynamic realm.
         */
        public final List<Artifact> artifacts;

        /**
         * Canonical form of the per-plugin {@code <dependencies>} this realm was baked WITH, as
         * produced by {@link #dependencyKey}. A project's per-plugin dependencies add jars, override
         * versions and apply exclusions, so they change what the realm must contain — stock Maven puts
         * them in the realm cache key for exactly that reason. Routing therefore requires an exact
         * match: serving a realm baked for a different dependency set would run the plugin with a
         * classpath Maven never built, and it would fail SILENTLY (e.g. kotlin-maven-plugin without
         * kotlin-maven-allopen still compiles, it just stops applying the 'spring' compiler plugin).
         * Empty string means the plugin declared none.
         */
        public final String dependencyKey;

        /** Runtime once-guard: set after the realm's beans are published to the container. */
        public volatile boolean published;

        Prebuilt(
                PluginDescriptor descriptor,
                ClassRealm realm,
                Map<String, Class<?>> classes,
                List<ComponentDescriptor<?>> components,
                List<Class<?>> indexedClasses,
                List<Artifact> artifacts,
                String dependencyKey) {
            this.descriptor = descriptor;
            this.realm = realm;
            this.classes = classes;
            this.components = components;
            this.indexedClasses = indexedClasses;
            this.artifacts = artifacts;
            this.dependencyKey = dependencyKey;
        }
    }

    /**
     * Serves the baked class map by name and the baked jar RESOURCES by path. Installed as an
     * IMPORT on the frozen realm for every plugin package and resource directory: classworlds
     * consults imports BEFORE the (dead) self and the parent, which makes
     * {@code realm.loadClass(pluginClassName)} work again at runtime — required by sisu's
     * {@code ComponentDescriptor.getRoleClass()} (always by-name, unpinnable), requirement-role
     * deferral, and any lazy {@code Class.forName} inside plugin code — and makes
     * {@code realm.getResource*} work for mojos that read resources from their own jar (e.g.
     * spring-boot repackage loading META-INF/loader/spring-boot-loader.jar): the frozen realm
     * cannot open its jars at runtime, so the bytes are baked and served from in-memory URLs.
     */
    static final class BakedClassLoader extends ClassLoader {
        private final Map<String, Class<?>> classes;

        /** Non-class jar entries, multi-valued per path in realm classpath order. */
        private final Map<String, List<byte[]>> resources;

        BakedClassLoader(Map<String, Class<?>> classes, Map<String, List<byte[]>> resources) {
            super(null);
            this.classes = classes;
            this.resources = resources;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            Class<?> c = classes.get(name);
            if (c == null) {
                throw new ClassNotFoundException(name);
            }
            return c;
        }

        @Override
        protected URL findResource(String name) {
            List<byte[]> data = resources.get(name);
            return data == null || data.isEmpty() ? null : toUrl(name, data.get(0));
        }

        @Override
        protected Enumeration<URL> findResources(String name) {
            List<URL> urls = new ArrayList<>();
            for (byte[] bytes : resources.getOrDefault(name, List.of())) {
                urls.add(toUrl(name, bytes));
            }
            return Collections.enumeration(urls);
        }

        private static URL toUrl(String name, byte[] bytes) {
            try {
                // handler passed directly to the URL — no protocol registry involved
                return new URL("nmvn-baked", null, -1, "/" + name, new URLStreamHandler() {
                    @Override
                    protected URLConnection openConnection(URL url) {
                        return new URLConnection(url) {
                            @Override
                            public void connect() {}

                            @Override
                            public InputStream getInputStream() {
                                return new ByteArrayInputStream(bytes);
                            }

                            @Override
                            public int getContentLength() {
                                return bytes.length;
                            }
                        };
                    }
                });
            } catch (MalformedURLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * Build-time realm that is SELF-FIRST for lookup while having the image class loader as its real
     * JDK parent ({@code getParent()}).
     *
     * <p><b>Why the JDK parent matters.</b> SVM decides link-at-build-time per class in
     * {@code LinkAtBuildTimeSupport.isIncluded}: a class whose loader is not "a native image class
     * loader" is forced to link at build time with NO opt-out, and
     * {@code ClassLoaderSupport.isNativeImageClassLoader} answers that by walking the
     * {@code getParent()} chain for the image loader. A realm created with a {@code null} base loader
     * therefore has {@code getParent() == null}, the walk finds nothing, and every class it loads
     * must resolve its ENTIRE reference closure at build time — which is what turns each absent
     * optional dependency (jansi, kotlin-reflect, ...) into a cascade of dropped classes. Giving the
     * realm the image loader as its JDK parent puts its classes on the ordinary classpath footing,
     * where an unresolved reference becomes a runtime LinkageError exactly as on HotSpot.
     *
     * <p><b>Why lookup must still be self-first.</b> {@code ClassRealm.unsynchronizedLoadClass} calls
     * {@code URLClassLoader.loadClass} (parent-FIRST) and only falls back to the self-first strategy
     * on ClassNotFoundException. With a non-null parent that makes any library present on BOTH the
     * image classpath and the realm jars (gson, guava, old plexus-utils, ...) resolve its outer
     * classes from the image classpath and its inner classes from the realm jar — mixed versions in
     * one realm. Overriding the single choke point restores import -> self -> parent order, i.e.
     * exactly {@code SelfFirstStrategy} semantics, so shared API types ({@code Mojo}) still come from
     * the image classpath while everything the realm ships wins over it.
     */
    static final class SelfFirstRealm extends ClassRealm {

        SelfFirstRealm(ClassWorld world, String id, ClassLoader imageLoader) {
            super(world, id, imageLoader);
            // loadClassFromParent() consults the classworlds parent field, not getParent().
            setParentClassLoader(imageLoader);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> c = loadClassFromImport(name);
            if (c == null) {
                c = loadClassFromSelf(name); // takes the loading lock + findLoadedClass first
            }
            if (c == null) {
                c = loadClassFromParent(name);
            }
            if (c == null) {
                throw new ClassNotFoundException(name);
            }
            return c;
        }
    }

    /**
     * Creates a {@link SelfFirstRealm} and registers it in the world's realm map. {@code newRealm}
     * would register it for us but hardcodes the {@code ClassRealm} type, so the map is populated
     * directly to keep {@code world.getClassRealm}/{@code disposeRealm} working as before.
     */
    private static ClassRealm newSelfFirstRealm(ClassWorld world, String id, ClassLoader imageLoader) throws Exception {
        SelfFirstRealm realm = new SelfFirstRealm(world, id, imageLoader);
        java.lang.reflect.Field realmsField = ClassWorld.class.getDeclaredField("realms");
        realmsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ClassRealm> realms = (Map<String, ClassRealm>) realmsField.get(world);
        realms.put(id, realm);
        return realm;
    }

    /**
     * Re-enables the old whole-constant-pool closure requirement ({@code -Dorg.apache.maven.nmvn.features.prebuilt.strictClosure=true}).
     * Only needed if {@link SelfFirstRealm}'s escape from forced link-at-build-time ever stops holding.
     */
    private static final boolean STRICT_CLOSURE = Boolean.getBoolean("org.apache.maven.nmvn.features.prebuilt.strictClosure");

    /**
     * The {@code <exportedPackage>} entries of every {@code META-INF/maven/extension.xml} on the image
     * classpath — i.e. maven-core's (plus any core extension's). These are the packages whose class
     * identity is shared between the core realm and every plugin realm; see the import loop in
     * {@link #buildAll}. Read as a classpath RESOURCE so the list stays in sync with whatever
     * maven-core is being baked, with no build-script plumbing.
     */
    private static final List<String> CORE_EXPORTS = loadCoreExports();

    private static List<String> loadCoreExports() {
        List<String> exports = new ArrayList<>();
        try {
            javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            Enumeration<URL> urls =
                    PrebuiltPluginRealms.class.getClassLoader().getResources("META-INF/maven/extension.xml");
            while (urls.hasMoreElements()) {
                try (InputStream in = urls.nextElement().openStream()) {
                    org.w3c.dom.NodeList nodes =
                            dbf.newDocumentBuilder().parse(in).getElementsByTagName("exportedPackage");
                    for (int i = 0; i < nodes.getLength(); i++) {
                        String text = nodes.item(i).getTextContent();
                        if (text != null && !text.isBlank()) {
                            exports.add(text.trim());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("nmvn prebuilt: cannot read core exported packages: " + e);
        }
        return exports;
    }

    private static final String DESCRIPTOR_LOCATION = "META-INF/maven/plugin.xml";

    public static final String CORE_REALM_ID = "plexus.core";

    /** Keyed by {@code groupId:artifactId} (version-agnostic key; version match is enforced in {@link #match}). */
    private static final Map<String, Prebuilt> BY_KEY = new LinkedHashMap<>();

    /** The baked world: {@code plexus.core} + one child realm per prebuilt plugin. */
    private static final ClassWorld WORLD;

    /** The baked {@code plexus.core} realm — becomes Maven's container realm at runtime. */
    private static final ClassRealm CORE_REALM;

    /** Human-readable status of the build-time initialization, surfaced for debugging. */
    public static final String STATUS;

    static {
        ClassWorld world = null;
        ClassRealm core = null;
        String status;
        try {
            world = new ClassWorld();
            // This hosted parent gets NULLED in the snapshot (no hosted loader reference survives);
            // NmvnLauncher re-attaches the runtime system loader as the single runtime fixup. At
            // build time the hosted parent makes the (empty) core realm delegate to the image
            // classpath.
            core = world.newRealm(CORE_REALM_ID, PrebuiltPluginRealms.class.getClassLoader());
            status = buildAll(world, core);
        } catch (Throwable t) {
            // Never let a build-time failure here abort the whole image build silently; record it loudly.
            status = "PrebuiltPluginRealms init FAILED: " + t;
            t.printStackTrace();
        }
        WORLD = world;
        CORE_REALM = core;
        STATUS = status;
    }

    private PrebuiltPluginRealms() {}

    private static String buildAll(ClassWorld world, ClassRealm core) throws Exception {
        String spec = loadPluginsSpec();
        if (spec == null || spec.isBlank()) {
            return "no org.apache.maven.nmvn.features.prebuilt.plugins set — registry empty";
        }

        Map<String, Set<String>> unlinkableAll = loadUnlinkable();
        StringBuilder report = new StringBuilder("prebuilt: ");
        for (String entry : splitPluginEntries(spec)) {
            int eq = entry.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("Malformed org.apache.maven.nmvn.features.prebuilt.plugins entry: " + entry);
            }
            // Left of '=' is "groupId:artifactId:version" optionally followed by
            // "|<canonical per-plugin dependencies>" (see Prebuilt.dependencyKey).
            String coordinates = entry.substring(0, eq).trim();
            String jars = entry.substring(eq + 1).trim();
            int bar = coordinates.indexOf(DEPENDENCY_SEPARATOR);
            String dependencyKey = bar < 0 ? "" : coordinates.substring(bar + 1).trim();
            String ga3 = bar < 0 ? coordinates : coordinates.substring(0, bar).trim();

            String[] gav = ga3.split(":");
            if (gav.length != 3) {
                throw new IllegalArgumentException("Expected groupId:artifactId:version, got: " + ga3);
            }
            String groupId = gav[0];
            String artifactId = gav[1];

            // Realms are keyed (and routed) by groupId:artifactId — a second VERSION of the same
            // plugin cannot be baked alongside the first (realm id collision, BY_KEY collision).
            // First entry wins; later versions resolve dynamically at runtime like any unbaked
            // plugin (prebuilt routing falls back on version mismatch anyway).
            if (BY_KEY.containsKey(groupId + ":" + artifactId)) {
                System.out.println("nmvn prebuilt: skipping " + ga3 + " (another version of " + groupId + ":"
                        + artifactId + " is already baked; it will resolve dynamically)");
                continue;
            }

            // Step 1: create the realm. SelfFirstRealm gives it the image loader as its JDK parent
            // (so SVM does NOT force link-at-build-time on its whole reference closure) while
            // keeping import -> self -> parent lookup order (so doubly-present libraries are not
            // mixed between the image classpath and the realm jars). See SelfFirstRealm's javadoc —
            // both halves are load-bearing and the reasons are independent.
            ClassRealm realm = newSelfFirstRealm(
                    world, "prebuilt>" + groupId + ":" + artifactId, PrebuiltPluginRealms.class.getClassLoader());
            for (String jar : jars.split(File.pathSeparator)) {
                if (!jar.isBlank()) {
                    realm.addURL(new File(jar).toURI().toURL());
                }
            }

            // Maven core EXPORTS packages to every plugin realm (DefaultClassRealmManager passes them
            // as foreignImports), and classworlds consults imports before self — so a plugin realm
            // that also ships plexus-utils still resolves org.codehaus.plexus.util.xml.Xpp3Dom to
            // CORE's copy. Without these imports the realm defines its own Xpp3Dom and any object
            // handed over from core fails to cast ("Xpp3Dom cannot be cast to Xpp3Dom"), which is
            // exactly what maven-archiver's BuildHelper.getPluginParameter does when reading the
            // compiler plugin's configuration. Stripping exportedArtifacts from the realm classpath
            // (done by the build script) is NOT equivalent: the exported PACKAGES are a separate list
            // and are what pins shared class identity.
            for (String exported : CORE_EXPORTS) {
                realm.importFrom(core, exported);
            }

            PluginDescriptor descriptor = parseDescriptor(realm);

            // Step 2: force-load EVERY class of every realm jar (the frozen realm cannot load at
            // runtime). Anything that would not survive SVM's build-time linking is dropped here.
            // Keyed by the FULL left side of the spec entry (GAV plus any |dependencies), because
            // that is what SanitizeRealmJars writes into unlinkable.txt — looking up the bare GAV
            // would silently find nothing and stop dropping classes that fail real JVM linking.
            Map<String, Class<?>> classes =
                    loadAllClasses(realm, jars, unlinkableAll.getOrDefault(coordinates, Set.of()));

            // Step 2b: BAKE-OR-FALL-BACK GATE. Dropping a class is only acceptable for the
            // optional-dependency integration points a Maven build never executes. If a MOJO's own
            // implementation class did not survive, this plugin cannot be served from a frozen realm
            // at all — so bake nothing for it and let the runtime resolve it dynamically (stock
            // realms + Crema), which is a supported, isolated, verified path. A per-plugin fallback
            // is always better than aborting the whole image build.
            List<String> missingMojos = new ArrayList<>();
            for (MojoDescriptor mojo : descriptor.getMojos()) {
                if (!classes.containsKey(mojo.getImplementation())) {
                    missingMojos.add(mojo.getGoal() + " -> " + mojo.getImplementation());
                }
            }
            if (!missingMojos.isEmpty()) {
                System.out.println("nmvn prebuilt: NOT baking " + ga3 + " — mojo implementations did not"
                        + " survive build-time linking, will resolve dynamically at runtime: " + missingMojos);
                world.disposeRealm(realm.getId());
                report.append(groupId).append(':').append(artifactId).append("(SKIPPED->dynamic) ");
                continue;
            }

            // Step 3: pin each mojo's Class on its descriptor (a frozen realm cannot loadClass at
            // runtime). ORDER: setRealm first — sisu's ComponentDescriptor.setRealm nulls the
            // cached implementationClass.
            for (MojoDescriptor mojo : descriptor.getMojos()) {
                mojo.setRealm(realm);
                mojo.setImplementationClass(classes.get(mojo.getImplementation()));
                mojo.setPluginDescriptor(descriptor);
            }
            // Deliberately NOT calling descriptor.setClassRealm(realm): stock semantics keep the
            // descriptor realm-less until setupPluginRealm; a pre-attached realm makes
            // getConfiguredMojo/getPluginRealm skip setupPluginRealm entirely, so the realm-cache
            // seam (and with it the Sisu publication) would never be consulted.

            // Step 4: bake plugin-internal components (components.xml) plus the sisu-indexed class
            // list plus the jar resources, and install the import shim so by-name resolution of
            // baked classes and resources works again at runtime.
            List<ComponentDescriptor<?>> components = bakeComponents(realm, classes, jars);
            List<Class<?>> indexedClasses = bakeIndexedClasses(classes, jars);
            List<Artifact> artifacts = bakeArtifacts(jars);
            Map<String, List<byte[]>> resources = loadAllResources(jars);
            BakedClassLoader baked = new BakedClassLoader(classes, resources);
            Set<String> imports = new LinkedHashSet<>();
            for (String className : classes.keySet()) {
                int dot = className.lastIndexOf('.');
                imports.add(dot > 0 ? className.substring(0, dot) : className);
            }
            for (String resourceName : resources.keySet()) {
                // Entry.matches converts a dotted import to a path for resource lookups, so a
                // directory containing dots (META-INF/maven/<groupId>/...) would be mangled —
                // import those (and root-level files) by their exact resource name, which
                // Entry.matches checks first, before any conversion.
                int slash = resourceName.lastIndexOf('/');
                String dir = slash > 0 ? resourceName.substring(0, slash) : null;
                imports.add(dir == null || dir.indexOf('.') >= 0 ? resourceName : dir);
            }
            for (String importSpec : imports) {
                realm.importFrom(baked, importSpec);
            }

            // Step 5: NOW swap the parent to the baked core realm. This is an ordinary heap
            // reference, so it survives the snapshot — the hierarchy is correct at build time and
            // needs no per-plugin runtime fixup. It also puts plexus.core into the realm's Sisu
            // visibility set (RealmManager.computeVisibleNames walks getParentRealm()).
            realm.setParentRealm(core);

            BY_KEY.put(
                    groupId + ":" + artifactId,
                    new Prebuilt(descriptor, realm, classes, components, indexedClasses, artifacts, dependencyKey));
            report.append(groupId)
                    .append(':')
                    .append(artifactId)
                    .append("(mojos=")
                    .append(descriptor.getMojos().size())
                    .append(",classes=")
                    .append(classes.size())
                    .append(",components=")
                    .append(components.size())
                    .append(",indexed=")
                    .append(indexedClasses.size())
                    .append(") ");
        }
        return report.toString();
    }

    /**
     * Force-loads every class of every realm jar through the realm — keeping ONLY classes that
     * fully LINK at build time. SVM classifies classes from custom class loaders as
     * link-at-build-time "by system default" (LinkAtBuildTimeSupport.isIncluded, no opt-out), so
     * for every class that lands in the image universe:
     * <ul>
     * <li>hub creation resolves its declaring/enclosing chain (fatal
     * "getDeclaringClass0 cannot be called" on LinkageError), and</li>
     * <li>its methods, once reachable (all are: the Feature registers them for reflection) or
     * pulled in by class-initializer simulation, are PARSED with eager constant-pool resolution
     * (fatal "Discovered unresolved type during parsing" on any reference to an absent optional
     * dependency — e.g. spring-core's Kotlin DSL enums calling kotlin.enums, or signatures naming
     * a type whose outer implements a missing interface).</li>
     * </ul>
     * So a class is baked only if it loads, its declaring chain links, and every class named in
     * its constant pool loads with a linkable declaring chain too. Anything else is dropped:
     * narrower than HotSpot's lazy linking, but the dropped classes are exactly the
     * optional-dependency integration points a Maven build never executes. Drops are logged;
     * a dropped MOJO class would mean the plugin must not be baked at all.
     */
    private static Map<String, Class<?>> loadAllClasses(ClassRealm realm, String jars, Set<String> unlinkable)
            throws Exception {
        Map<String, Class<?>> classes = new LinkedHashMap<>();
        Map<String, Set<String>> referencedTypes = new LinkedHashMap<>();
        // EVERY class name the realm jars contain, whether or not it loaded. A reference to a name
        // in here can only be satisfied by a class THIS realm bakes: if it never loaded, or gets
        // dropped below, the reference is dead and its referrers must go too. Names absent from
        // here (shared API types reached through the strategy parent) are backed by the image
        // classpath and stand on their own.
        Set<String> inJars = new LinkedHashSet<>();
        for (String jar : jars.split(File.pathSeparator)) {
            if (jar.isBlank()) {
                continue;
            }
            try (JarFile jarFile = new JarFile(new File(jar))) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.endsWith(".class")
                            || name.endsWith("module-info.class")
                            || name.startsWith("META-INF/")) {
                        continue;
                    }
                    String className =
                            name.substring(0, name.length() - ".class".length()).replace('/', '.');
                    inJars.add(className);
                    try {
                        Class<?> c = realm.loadClass(className);
                        if (!isHostedOnly(c) && !classes.containsKey(className)) {
                            classes.put(className, c);
                            try (java.io.InputStream in = jarFile.getInputStream(entry)) {
                                referencedTypes.put(className, constantPoolClassRefs(in.readAllBytes()));
                            }
                        }
                    } catch (Throwable t) {
                        // tolerated: classes referencing optional/absent dependencies
                    }
                }
            }
        }
        // Memoized JVM-side probe: does this name load through the realm with a linkable
        // declaring/enclosing chain (hub creation requirement, see javadoc)? Necessary but NOT
        // sufficient on its own — see poisoned() for what the JVM cannot tell us.
        Map<String, Boolean> probed = new LinkedHashMap<>();
        java.util.function.Function<String, Boolean> probe = typeName -> probed.computeIfAbsent(typeName, n -> {
            try {
                Class<?> ref = realm.loadClass(n);
                ref.getDeclaringClass();
                ref.getEnclosingClass();
                ref.getEnclosingMethod();
                ref.getEnclosingConstructor();
                return true;
            } catch (Throwable t) {
                return false;
            }
        });

        // SVM fully LINKS every registered class (ReflectionDataBuilder.linkType -> JVMCI
        // ensureLinked), which resolves types appearing only in member DESCRIPTORS — those have no
        // CONSTANT_Class entry, so the constant-pool scan above cannot see them. Fold those (plus
        // the supertype closure, each member of which becomes a hub with the same declaring-chain
        // requirement) into the per-class reference set ONCE, so the fixpoint below is pure set
        // arithmetic rather than a reflective re-walk every round.
        // STRUCTURAL references — supertypes and member descriptors. These are resolved regardless of
        // link-at-build-time: hub creation walks the supertype/declaring chain, and
        // ReflectionDataBuilder.linkType -> ensureLinked resolves member descriptors of every class
        // registered for reflection (which is all of them). Kept separate from method-BODY references,
        // which only have to resolve when the class is forced to link at build time.
        Map<String, Set<String>> structuralTypes = new LinkedHashMap<>();
        for (Map.Entry<String, Class<?>> entry : classes.entrySet()) {
            Set<String> refs = structuralTypes.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>());
            String unreflectable = collectMemberAndSupertypeNames(entry.getValue(), refs);
            if (unreflectable != null) {
                // getDeclaredMethods()/getInterfaces() itself threw: the descriptors name types
                // that do not resolve at all. Poison with a name nothing can ever satisfy.
                refs.add(POISON + unreflectable);
            }
        }

        // Fixpoint drop. A referenced name is POISONED if the JVM probe fails, or the
        // SanitizeRealmJars link probe rejected it, or it is a realm-jar class that is not (or no
        // longer) baked. Dropping a class poisons its own name, which can poison its referrers in
        // turn — hence iterate to a fixpoint. The previous single-pass check missed exactly that
        // transitivity: commons-compress Coders$1 fails the link probe, so Coders (whose <clinit>
        // does `new Coders$1()`) has to go too, or SVM's build-time linking of Coders aborts the
        // whole image build.
        Map<String, String> dropped = new LinkedHashMap<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            Iterator<Map.Entry<String, Class<?>>> it = classes.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Class<?>> kept = it.next();
                String className = kept.getKey();
                String reason = null;
                // Classes the SanitizeRealmJars pre-step found to fail REAL JVM linking (the same
                // JVMCI ResolvedJavaType.link() SVM runs on registered classes). That probe runs in
                // a separate JVM: calling JVMCI from this (image-baked, build-time-initialized)
                // class makes the JVMCIRuntime singleton heap-reachable, which SVM rejects.
                if (unlinkable.contains(className)) {
                    reason = "JVM linking failed in probe";
                } else if (!probe.apply(className)) {
                    reason = "does not link at build time";
                } else {
                    for (String ref : structuralTypes.getOrDefault(className, Set.of())) {
                        if (poisoned(ref, unlinkable, inJars, classes, probe)) {
                            reason = "member/supertype does not link: " + ref;
                            break;
                        }
                    }
                    // Method-BODY references only need to resolve when the class is forced to link at
                    // build time. SelfFirstRealm avoids that (see its javadoc), so a guarded branch
                    // into an absent optional dependency stays an unresolved symbolic reference —
                    // just as it does on HotSpot — instead of poisoning every class that leads to it.
                    if (reason == null && STRICT_CLOSURE) {
                        for (String ref : referencedTypes.getOrDefault(className, Set.of())) {
                            if (poisoned(ref, unlinkable, inJars, classes, probe)) {
                                reason = "references unlinkable " + ref;
                                break;
                            }
                        }
                    }
                }
                if (reason != null) {
                    dropped.put(className, reason);
                    it.remove();
                    changed = true;
                }
            }
        }
        dropped.forEach(
                (className, reason) -> System.out.println("nmvn prebuilt: dropped " + className + " (" + reason + ")"));
        return classes;
    }

    /** Prefix marking a reference name that can never be satisfied (not a legal binary name). */
    private static final String POISON = "unreflectable:";

    /**
     * Is a referenced type name unsatisfiable in the baked realm? Cheap set checks first, the JVM
     * probe (which loads the class) last.
     */
    private static boolean poisoned(
            String name,
            Set<String> unlinkable,
            Set<String> inJars,
            Map<String, Class<?>> baked,
            java.util.function.Function<String, Boolean> probe) {
        if (name.startsWith(POISON) || unlinkable.contains(name)) {
            return true;
        }
        if (inJars.contains(name)) {
            return !baked.containsKey(name); // only this realm can supply it, so it must be baked
        }
        return !probe.apply(name);
    }

    /**
     * Per-plugin class names that failed the SanitizeRealmJars link probe, from the file named by
     * {@code -Dorg.apache.maven.nmvn.features.prebuilt.unlinkable} (lines: {@code g:a:v<TAB>className}). Absent on plain
     * JVM runs — there the realms are live and linking stays lazy.
     */
    private static Map<String, Set<String>> loadUnlinkable() {
        String path = System.getProperty("org.apache.maven.nmvn.features.prebuilt.unlinkable");
        if (path == null) {
            return Map.of();
        }
        Map<String, Set<String>> perPlugin = new LinkedHashMap<>();
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(path))) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    perPlugin
                            .computeIfAbsent(line.substring(0, tab), k -> new LinkedHashSet<>())
                            .add(line.substring(tab + 1));
                }
            }
        } catch (IOException e) {
            System.out.println("nmvn prebuilt: cannot read unlinkable list: " + e);
        }
        return perPlugin;
    }

    /**
     * Adds the binary name of every supertype, and of every type named in a member descriptor, of
     * {@code c} into {@code sink} — the types SVM resolves when it links a registered class.
     *
     * @return null on success, or a description if reflection itself threw (descriptors naming types
     *     that do not resolve at all), in which case {@code sink} is incomplete and the class must
     *     be treated as unbakeable.
     */
    private static String collectMemberAndSupertypeNames(Class<?> c, Set<String> sink) {
        try {
            List<Class<?>> types = new ArrayList<>();
            for (Class<?> s = c.getSuperclass(); s != null; s = s.getSuperclass()) {
                types.add(s);
            }
            java.util.ArrayDeque<Class<?>> queue = new java.util.ArrayDeque<>(List.of(c));
            while (!queue.isEmpty()) {
                for (Class<?> itf : queue.poll().getInterfaces()) {
                    types.add(itf);
                    queue.add(itf);
                }
            }
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                Collections.addAll(types, m.getParameterTypes());
                types.add(m.getReturnType());
                Collections.addAll(types, m.getExceptionTypes());
            }
            for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) {
                Collections.addAll(types, k.getParameterTypes());
                Collections.addAll(types, k.getExceptionTypes());
            }
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                types.add(f.getType());
            }
            for (Class<?> type : types) {
                while (type.isArray()) {
                    type = type.getComponentType();
                }
                if (!type.isPrimitive()) {
                    sink.add(type.getName());
                }
            }
            return null;
        } catch (Throwable t) {
            return t.toString();
        }
    }

    /**
     * Binary class names referenced by the constant pool: CONSTANT_Class entries (arrays skipped)
     * PLUS every object type named in CONSTANT_NameAndType/CONSTANT_MethodType descriptors. The
     * latter matter because bytecode VERIFICATION (JVMCI ensureLinked, run by SVM when linking
     * registered classes) resolves types from invoked members' descriptors for assignability
     * checks — those types have no CONSTANT_Class entry of their own in the referencing class.
     */
    private static Set<String> constantPoolClassRefs(byte[] classBytes) throws IOException {
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(classBytes));
        in.readInt(); // magic
        in.readUnsignedShort(); // minor
        in.readUnsignedShort(); // major
        int count = in.readUnsignedShort();
        Map<Integer, String> utf8 = new LinkedHashMap<>();
        List<Integer> classNameIndices = new ArrayList<>();
        List<Integer> descriptorIndices = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8.put(i, in.readUTF());
                case 7 -> classNameIndices.add(in.readUnsignedShort());
                case 16 -> descriptorIndices.add(in.readUnsignedShort()); // MethodType
                case 12 -> { // NameAndType: name_index, descriptor_index
                    in.skipBytes(2);
                    descriptorIndices.add(in.readUnsignedShort());
                }
                case 8, 19, 20 -> in.skipBytes(2);
                case 15 -> in.skipBytes(3);
                case 3, 4, 9, 10, 11, 17, 18 -> in.skipBytes(4);
                case 5, 6 -> {
                    in.skipBytes(8);
                    i++; // longs/doubles take two constant-pool slots
                }
                default -> throw new IOException("Unknown constant pool tag " + tag);
            }
        }
        Set<String> refs = new LinkedHashSet<>();
        for (int index : classNameIndices) {
            String internalName = utf8.get(index);
            if (internalName == null || internalName.startsWith("[")) {
                continue; // array types resolve through their (separately referenced) element type
            }
            refs.add(internalName.replace('/', '.'));
        }
        for (int index : descriptorIndices) {
            String descriptor = utf8.get(index);
            if (descriptor != null) {
                addDescriptorObjectTypes(descriptor, refs);
            }
        }
        return refs;
    }

    /** Adds every L<name>; object type of a field/method descriptor to refs. */
    private static void addDescriptorObjectTypes(String descriptor, Set<String> refs) {
        int i = 0;
        while ((i = descriptor.indexOf('L', i)) >= 0) {
            char prev = i == 0 ? '(' : descriptor.charAt(i - 1);
            int end = descriptor.indexOf(';', i);
            if (end < 0) {
                return; // not a descriptor (plain name that happens to contain 'L')
            }
            // 'L' opens a type only at a type position: start, after (/)/[/; or a primitive tag
            if (prev == '(' || prev == ')' || prev == '[' || prev == ';' || "BZCSIJFD".indexOf(prev) >= 0) {
                refs.add(descriptor.substring(i + 1, end).replace('/', '.'));
                i = end + 1;
            } else {
                i++;
            }
        }
    }

    /**
     * Bakes every non-class entry of every realm jar (the frozen realm cannot open its jars at
     * runtime), multi-valued per path in classpath order so {@code getResources} enumerations
     * behave like on a live realm.
     */
    private static Map<String, List<byte[]>> loadAllResources(String jars) throws Exception {
        Map<String, List<byte[]>> resources = new LinkedHashMap<>();
        for (String jar : jars.split(File.pathSeparator)) {
            if (jar.isBlank()) {
                continue;
            }
            try (JarFile jarFile = new JarFile(new File(jar))) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || name.endsWith(".class")) {
                        continue;
                    }
                    try (InputStream in = jarFile.getInputStream(entry)) {
                        resources.computeIfAbsent(name, k -> new ArrayList<>()).add(in.readAllBytes());
                    }
                }
            }
        }
        return resources;
    }

    /**
     * Some plugin classpaths carry classes meant for the GraalVM image BUILDER, not for any
     * runtime — e.g. spring-aot's {@code PreComputeFieldFeature implements
     * org.graalvm.nativeimage.hosted.Feature}. Hosted-only interfaces are rejected by the image
     * heap scanner, so such Class objects must not enter the baked class map. Detect them by
     * walking the supertype hierarchy for anything under {@code org.graalvm.nativeimage.}; an
     * uninspectable hierarchy is treated as hosted-only (not baked), matching the tolerated-failure
     * policy of {@link #loadAllClasses}.
     */
    private static boolean isHostedOnly(Class<?> c) {
        try {
            Set<Class<?>> pending = new LinkedHashSet<>();
            for (Class<?> t = c; t != null; t = t.getSuperclass()) {
                Collections.addAll(pending, t.getInterfaces());
            }
            Set<Class<?>> seen = new LinkedHashSet<>();
            while (!pending.isEmpty()) {
                Class<?> iface = pending.iterator().next();
                pending.remove(iface);
                if (!seen.add(iface)) {
                    continue;
                }
                if (iface.getName().startsWith("org.graalvm.nativeimage.")) {
                    return true;
                }
                Collections.addAll(pending, iface.getInterfaces());
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Parses each jar's legacy {@code META-INF/plexus/components.xml} (still shipped by e.g.
     * org.sonatype plexus-build-api, injected by maven-filtering) into plexus
     * {@link ComponentDescriptor}s with PINNED implementation classes, bound under their declared
     * role/hint. Publication bypasses {@code discoverComponents}, so anything not baked here is
     * invisible at runtime. Sisu-indexed components are handled separately — see
     * {@link Prebuilt#indexedClasses}.
     */
    private static List<ComponentDescriptor<?>> bakeComponents(
            ClassRealm realm, Map<String, Class<?>> classes, String jars) throws Exception {
        List<ComponentDescriptor<?>> components = new ArrayList<>();
        Set<String> bound = new LinkedHashSet<>();
        for (String jar : jars.split(File.pathSeparator)) {
            if (jar.isBlank()) {
                continue;
            }
            try (JarFile jarFile = new JarFile(new File(jar))) {
                JarEntry plexusXml = jarFile.getJarEntry("META-INF/plexus/components.xml");
                if (plexusXml != null) {
                    try (InputStream in = jarFile.getInputStream(plexusXml)) {
                        bakeComponentsXml(components, bound, realm, classes, in);
                    }
                }
            }
        }
        return components;
    }

    /** The pinned classes of each jar's {@code META-INF/sisu/javax.inject.Named} index, in index order. */
    private static List<Class<?>> bakeIndexedClasses(Map<String, Class<?>> classes, String jars) throws Exception {
        List<Class<?>> indexed = new ArrayList<>();
        for (String jar : jars.split(File.pathSeparator)) {
            if (jar.isBlank()) {
                continue;
            }
            try (JarFile jarFile = new JarFile(new File(jar))) {
                JarEntry index = jarFile.getJarEntry("META-INF/sisu/javax.inject.Named");
                if (index == null) {
                    continue;
                }
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(jarFile.getInputStream(index), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String className = line.trim();
                        if (className.isEmpty() || className.startsWith("#")) {
                            continue;
                        }
                        Class<?> impl = classes.get(className);
                        if (impl != null) { // absent: failed to load at build time; nothing we can do
                            indexed.add(impl);
                        }
                    }
                }
            }
        }
        return indexed;
    }

    /** Bakes the components of one legacy {@code META-INF/plexus/components.xml}. */
    private static void bakeComponentsXml(
            List<ComponentDescriptor<?>> components,
            Set<String> bound,
            ClassRealm realm,
            Map<String, Class<?>> classes,
            InputStream in)
            throws Exception {
        javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        org.w3c.dom.Document doc = dbf.newDocumentBuilder().parse(in);
        org.w3c.dom.NodeList nodes = doc.getElementsByTagName("component");
        for (int i = 0; i < nodes.getLength(); i++) {
            org.w3c.dom.Element component = (org.w3c.dom.Element) nodes.item(i);
            String role = childText(component, "role");
            String implementation = childText(component, "implementation");
            if (role == null || implementation == null) {
                continue;
            }
            Class<?> impl = classes.get(implementation);
            if (impl == null) {
                continue; // failed to load at build time; nothing we can do
            }
            String hint = childText(component, "role-hint");
            addComponent(components, bound, realm, role, hint == null ? "default" : hint, impl, component);
        }
    }

    /** First direct child element's trimmed text, or null. */
    private static String childText(org.w3c.dom.Element parent, String name) {
        for (org.w3c.dom.Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof org.w3c.dom.Element e && name.equals(e.getTagName())) {
                String text = e.getTextContent();
                return text == null || text.isBlank() ? null : text.trim();
            }
        }
        return null;
    }

    private static void addComponent(
            List<ComponentDescriptor<?>> components,
            Set<String> bound,
            ClassRealm realm,
            String role,
            String hint,
            Class<?> impl,
            org.w3c.dom.Element componentXml) {
        if (!bound.add(role + "/" + hint + "/" + impl.getName())) {
            return; // already baked (e.g. declared in both formats)
        }
        ComponentDescriptor<Object> cd = new ComponentDescriptor<>();
        cd.setRole(role);
        cd.setRoleHint(hint);
        cd.setImplementation(impl.getName());
        if (componentXml != null) {
            String strategy = childText(componentXml, "instantiation-strategy");
            if (strategy != null) {
                cd.setInstantiationStrategy(strategy);
            }
            org.w3c.dom.NodeList requirements = componentXml.getElementsByTagName("requirement");
            for (int i = 0; i < requirements.getLength(); i++) {
                org.w3c.dom.Element requirement = (org.w3c.dom.Element) requirements.item(i);
                org.codehaus.plexus.component.repository.ComponentRequirement req =
                        new org.codehaus.plexus.component.repository.ComponentRequirement();
                req.setRole(childText(requirement, "role"));
                String reqHint = childText(requirement, "role-hint");
                if (reqHint != null) {
                    req.setRoleHint(reqHint);
                }
                req.setFieldName(childText(requirement, "field-name"));
                cd.addRequirement(req);
            }
        }
        // ORDER: setRealm first — it nulls the cached implementation class.
        cd.setRealm(realm);
        cd.setImplementationClass(impl);
        components.add(cd);
    }

    /** The realm jars as resolved plugin artifacts — see {@link Prebuilt#artifacts}. */
    private static List<Artifact> bakeArtifacts(String jars) {
        List<Artifact> artifacts = new ArrayList<>();
        for (String jar : jars.split(File.pathSeparator)) {
            if (jar.isBlank()) {
                continue;
            }
            Artifact artifact = toArtifact(new File(jar));
            if (artifact != null) {
                artifacts.add(artifact);
            }
        }
        return artifacts;
    }

    private static Artifact toArtifact(File jar) {
        String[] gav = gavOf(jar);
        if (gav == null) {
            return null;
        }
        String base = gav[1] + "-" + gav[2];
        String fileName = jar.getName().replaceFirst("\\.jar$", "");
        String classifier = fileName.startsWith(base + "-") ? fileName.substring(base.length() + 1) : null;
        DefaultArtifact artifact = new DefaultArtifact(
                gav[0], gav[1], gav[2], Artifact.SCOPE_RUNTIME, "jar", classifier, new DefaultArtifactHandler("jar"));
        artifact.setFile(jar);
        artifact.setResolved(true);
        return artifact;
    }

    /**
     * groupId/artifactId/version of a jar: local-repo path layout first (bake-time classpaths are
     * always repo-resolved, and shaded jars like surefire-shared-utils carry only the SHADED
     * dependency's pom.properties), embedded pom.properties as fallback.
     */
    private static String[] gavOf(File jar) {
        // local-repo layout: .../repository/<group dirs>/<artifactId>/<version>/<file>.jar
        File versionDir = jar.getParentFile();
        File artifactDir = versionDir == null ? null : versionDir.getParentFile();
        if (artifactDir != null) {
            StringBuilder group = new StringBuilder();
            for (File dir = artifactDir.getParentFile(); dir != null; dir = dir.getParentFile()) {
                if (dir.getName().equals("repository")) {
                    if (!group.isEmpty()) {
                        return new String[] {group.toString(), artifactDir.getName(), versionDir.getName()};
                    }
                    break;
                }
                group.insert(0, group.isEmpty() ? dir.getName() : dir.getName() + ".");
            }
        }
        try (JarFile jarFile = new JarFile(jar)) {
            String[] first = null;
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("META-INF/maven/") || !name.endsWith("/pom.properties")) {
                    continue;
                }
                Properties p = new Properties();
                try (InputStream in = jarFile.getInputStream(entry)) {
                    p.load(in);
                }
                String g = p.getProperty("groupId");
                String a = p.getProperty("artifactId");
                String v = p.getProperty("version");
                if (g == null || a == null || v == null) {
                    continue;
                }
                // shaded jars can carry several pom.properties; prefer the one matching the file name
                if (jar.getName().startsWith(a + "-")) {
                    return new String[] {g, a, v};
                }
                if (first == null) {
                    first = new String[] {g, a, v};
                }
            }
            return first;
        } catch (Exception e) {
            return null;
        }
    }

    private static PluginDescriptor parseDescriptor(ClassRealm realm) throws Exception {
        PluginDescriptorBuilder builder = new PluginDescriptorBuilder();
        PluginDescriptorBuilder.StreamSupplier supplier = () -> {
            InputStream in = realm.getResourceAsStream(DESCRIPTOR_LOCATION);
            if (in == null) {
                throw new IllegalStateException("No " + DESCRIPTOR_LOCATION + " in realm " + realm.getId());
            }
            return in;
        };
        return builder.build(supplier, realm.getId());
    }

    /** @return the baked world ({@code plexus.core} + plugin realms), or {@code null} if init failed. */
    public static ClassWorld world() {
        return WORLD;
    }

    /** @return the baked {@code plexus.core} realm, or {@code null} if init failed. */
    public static ClassRealm coreRealm() {
        return CORE_REALM;
    }

    /**
     * Load the prebuilt plugins spec from {@code -Dorg.apache.maven.nmvn.features.prebuilt.pluginsFile} (preferred; avoids
     * huge / multiline {@code -D} values on Windows) or {@code -Dorg.apache.maven.nmvn.features.prebuilt.plugins}.
     */
    private static String loadPluginsSpec() {
        String file = System.getProperty("org.apache.maven.nmvn.features.prebuilt.pluginsFile");
        if (file != null && !file.isBlank()) {
            try {
                return java.nio.file.Files.readString(java.nio.file.Path.of(file));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot read org.apache.maven.nmvn.features.prebuilt.pluginsFile=" + file, e);
            }
        }
        return System.getProperty("org.apache.maven.nmvn.features.prebuilt.plugins");
    }

    /**
     * Split realm entries on newlines. On Unix, also accept legacy {@code ';'} entry separators
     * when pathSeparator is not {@code ';'} (Windows uses {@code ';'} for jar lists).
     *
     * <p>Splits on LF only, not {@code \R}: the spec is written with LF endings, so a CR in it is
     * data corruption (a Windows pipeline leaking {@code \r} into a coordinate). Splitting on
     * {@code \R} would cut the entry at that CR and report a bare {@code g:a:v} that merely looks
     * truncated; the per-line {@code trim()} below absorbs it instead (and handles CRLF files).
     */
    private static java.util.List<String> splitPluginEntries(String spec) {
        java.util.List<String> entries = new java.util.ArrayList<>();
        for (String line : spec.split("\n", -1)) {
            String t = line.trim();
            if (!t.isEmpty()) {
                entries.add(t);
            }
        }
        if (!entries.isEmpty()) {
            return entries;
        }
        if (java.io.File.pathSeparatorChar != ';') {
            for (String part : spec.split(";")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    entries.add(t);
                }
            }
        }
        return entries;
    }

    /** Separates the GAV from the canonical dependency key inside one {@code org.apache.maven.nmvn.features.prebuilt.plugins} entry. */
    private static final char DEPENDENCY_SEPARATOR = '|';

    /**
     * Routing decision used by the seeded caches: the prebuilt entry for the requested plugin, or
     * {@code null} to fall back to stock dynamic resolution. A baked plugin is used only on an exact
     * match of BOTH the version and the per-plugin {@code <dependencies>} — it must never silently
     * stand in for a plugin whose realm Maven would have built differently. Any doubt falls back to
     * dynamic resolution, which is always correct (just slower), so the comparison errs strict.
     */
    /**
     * Outcome of prebuilt routing for one plugin request. {@link #prebuilt} non-null means use the
     * baked realm/descriptor; otherwise fall back to dynamic resolution for {@link #dynamicReason}.
     */
    public static final class Route {
        public final Prebuilt prebuilt;
        /** Null when baked; otherwise a short reason for dynamic fallback. */
        public final String dynamicReason;

        Route(Prebuilt prebuilt, String dynamicReason) {
            this.prebuilt = prebuilt;
            this.dynamicReason = dynamicReason;
        }

        public boolean isBaked() {
            return prebuilt != null;
        }
    }

    public static Prebuilt match(
            String groupId, String artifactId, String requestedVersion, String requestedDependencyKey) {
        return route(groupId, artifactId, requestedVersion, requestedDependencyKey).prebuilt;
    }

    /**
     * Same decision as {@link #match}, but keeps the reason when falling back to dynamic resolution
     * (for runtime logging of baked vs dynamic plugins).
     */
    public static Route route(
            String groupId, String artifactId, String requestedVersion, String requestedDependencyKey) {
        Prebuilt prebuilt = BY_KEY.get(groupId + ":" + artifactId);
        if (prebuilt == null) {
            if (BY_KEY.isEmpty()) {
                return new Route(null, "no prebuilt plugins in this image");
            }
            return new Route(null, "not baked in this image");
        }
        String baked = prebuilt.descriptor.getVersion();
        if (requestedVersion != null && baked != null && !requestedVersion.equals(baked)) {
            return new Route(null, "version mismatch (requested " + requestedVersion + ", baked " + baked + ")");
        }
        String reqDeps = requestedDependencyKey == null ? "" : requestedDependencyKey;
        if (!prebuilt.dependencyKey.equals(reqDeps)) {
            return new Route(null, "per-plugin <dependencies> differ from baked set");
        }
        return new Route(prebuilt, null);
    }

    /**
     * Canonical, order-independent encoding of a plugin's {@code <dependencies>}, used on both sides of
     * the routing comparison: the build script emits it into {@code org.apache.maven.nmvn.features.prebuilt.plugins} from the
     * effective pom, and the caches compute it here from the runtime model. Encoding MUST stay in sync
     * with the {@code canonical_dependencies} helper in build-nmvn-for-pom.sh.
     *
     * <p>Form: {@code g:a:v:type:classifier:scope} per dependency (defaults applied so an omitted
     * {@code <scope>} and an explicit {@code compile} agree), exclusions appended as
     * {@code ^g:a} sorted, then the dependency strings sorted and joined with {@code ,}. Sorting makes
     * declaration order irrelevant, which matches Maven — order affects resolution tie-breaks, not
     * realm membership, and a mismatch here only costs us a fallback.
     */
    public static String dependencyKey(List<org.apache.maven.model.Dependency> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            return "";
        }
        List<String> encoded = new ArrayList<>(dependencies.size());
        for (org.apache.maven.model.Dependency d : dependencies) {
            StringBuilder sb = new StringBuilder()
                    .append(orEmpty(d.getGroupId()))
                    .append(':')
                    .append(orEmpty(d.getArtifactId()))
                    .append(':')
                    .append(orEmpty(d.getVersion()))
                    .append(':')
                    .append(d.getType() == null || d.getType().isEmpty() ? "jar" : d.getType())
                    .append(':')
                    .append(orEmpty(d.getClassifier()))
                    .append(':')
                    .append(d.getScope() == null || d.getScope().isEmpty() ? "compile" : d.getScope());
            List<String> exclusions = new ArrayList<>();
            if (d.getExclusions() != null) {
                for (org.apache.maven.model.Exclusion e : d.getExclusions()) {
                    exclusions.add(orEmpty(e.getGroupId()) + ":" + orEmpty(e.getArtifactId()));
                }
            }
            Collections.sort(exclusions);
            for (String exclusion : exclusions) {
                sb.append('^').append(exclusion);
            }
            encoded.add(sb.toString());
        }
        Collections.sort(encoded);
        return String.join(",", encoded);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    public static Map<String, Prebuilt> all() {
        return Collections.unmodifiableMap(BY_KEY);
    }
}
