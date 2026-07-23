package nmvn;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
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
 * <p><b>Class identity (why plugins are LOADED through the hosted loader, then re-parented).</b>
 * Plugin classes link against shared API types ({@code Mojo}, {@code AbstractMojo}) during
 * build-time loading. Those must resolve to the image-classpath copies — the single canonical
 * classes that are AOT-compiled and that the runtime container uses. If the plugin realms' parent
 * were the baked core realm DURING loading, the core realm would have to carry the lib jars and
 * would then DEFINE DUPLICATE copies of core classes, breaking {@code instanceof} and Guice keys at
 * runtime. Hence the two-step dance in {@link #buildAll}: load and pin with the hosted loader as
 * parent, then {@code setParentRealm(core)} before the snapshot. The baked core realm itself
 * carries NO jar URLs.
 *
 * <p><b>Frozen realms cannot load by name at runtime</b> — not even under Crema with the jar
 * present on disk (verified 2026-07-02). Every plugin class is therefore force-loaded at build time
 * into {@link Prebuilt#classes}, and by-name lookups are revived by the IMPORT SHIM: every plugin
 * package gets a classworlds import pointing at a {@link BakedClassLoader} over that map, consulted
 * BEFORE the dead self. Mojo implementation classes are additionally pinned on their descriptors.
 * ORDER MATTERS when pinning: sisu's {@code ComponentDescriptor.setRealm(...)} nulls the cached
 * implementation class, so {@code setRealm} must precede {@code setImplementationClass}.
 *
 * <p><b>Configuration.</b> {@code -Dnmvn.prebuilt.plugins} is a {@code ';'}-separated list of
 * {@code groupId:artifactId:version=jar1{File.pathSeparator}jar2...} entries (first jar = plugin
 * artifact, rest = resolved runtime classpath). When absent (normal JVM run, tests) the registry is
 * empty and everything falls back to stock dynamic resolution.
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

        /** Runtime once-guard: set after the realm's beans are published to the container. */
        public volatile boolean published;

        Prebuilt(
                PluginDescriptor descriptor,
                ClassRealm realm,
                Map<String, Class<?>> classes,
                List<ComponentDescriptor<?>> components,
                List<Class<?>> indexedClasses,
                List<Artifact> artifacts) {
            this.descriptor = descriptor;
            this.realm = realm;
            this.classes = classes;
            this.components = components;
            this.indexedClasses = indexedClasses;
            this.artifacts = artifacts;
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
        String spec = System.getProperty("nmvn.prebuilt.plugins");
        if (spec == null || spec.isBlank()) {
            return "no nmvn.prebuilt.plugins set — registry empty";
        }

        StringBuilder report = new StringBuilder("prebuilt: ");
        for (String entry : spec.split(";")) {
            if (entry.isBlank()) {
                continue;
            }
            int eq = entry.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("Malformed nmvn.prebuilt.plugins entry: " + entry);
            }
            String ga3 = entry.substring(0, eq).trim(); // groupId:artifactId:version
            String jars = entry.substring(eq + 1).trim();

            String[] gav = ga3.split(":");
            if (gav.length != 3) {
                throw new IllegalArgumentException("Expected groupId:artifactId:version, got: " + ga3);
            }
            String groupId = gav[0];
            String artifactId = gav[1];

            // Step 1: create the realm with the HOSTED loader as parent, so that all class loading
            // below links shared API types against the image-classpath (AOT) copies. See class doc.
            ClassRealm realm = world.newRealm(
                    "prebuilt>" + groupId + ":" + artifactId, PrebuiltPluginRealms.class.getClassLoader());
            for (String jar : jars.split(File.pathSeparator)) {
                if (!jar.isBlank()) {
                    realm.addURL(new File(jar).toURI().toURL());
                }
            }

            PluginDescriptor descriptor = parseDescriptor(realm);

            // Step 2: force-load each mojo class through THIS realm and pin the Class on the
            // descriptor (a frozen realm cannot loadClass at runtime). ORDER: setRealm first —
            // sisu's ComponentDescriptor.setRealm nulls the cached implementationClass.
            for (MojoDescriptor mojo : descriptor.getMojos()) {
                Class<?> impl = realm.loadClass(mojo.getImplementation());
                mojo.setRealm(realm);
                mojo.setImplementationClass(impl);
                mojo.setPluginDescriptor(descriptor);
            }
            // Deliberately NOT calling descriptor.setClassRealm(realm): stock semantics keep the
            // descriptor realm-less until setupPluginRealm; a pre-attached realm makes
            // getConfiguredMojo/getPluginRealm skip setupPluginRealm entirely, so the realm-cache
            // seam (and with it the Sisu publication) would never be consulted.

            // Step 3: force-load EVERY class of every realm jar (the frozen realm cannot load at
            // runtime), bake plugin-internal components (components.xml) plus the sisu-indexed
            // class list plus the jar resources, and install the import shim so by-name
            // resolution of baked classes and resources works again at runtime.
            Map<String, Class<?>> classes = loadAllClasses(realm, jars);
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

            // Step 4: NOW swap the parent to the baked core realm. This is an ordinary heap
            // reference, so it survives the snapshot — the hierarchy is correct at build time and
            // needs no per-plugin runtime fixup. It also puts plexus.core into the realm's Sisu
            // visibility set (RealmManager.computeVisibleNames walks getParentRealm()).
            realm.setParentRealm(core);

            BY_KEY.put(
                    groupId + ":" + artifactId,
                    new Prebuilt(descriptor, realm, classes, components, indexedClasses, artifacts));
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

    /** Force-loads every class of every realm jar through the realm; failures are tolerated (optional deps). */
    private static Map<String, Class<?>> loadAllClasses(ClassRealm realm, String jars) throws Exception {
        Map<String, Class<?>> classes = new LinkedHashMap<>();
        for (String jar : jars.split(File.pathSeparator)) {
            if (jar.isBlank()) {
                continue;
            }
            try (JarFile jarFile = new JarFile(new File(jar))) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.endsWith(".class")
                            || name.endsWith("module-info.class")
                            || name.startsWith("META-INF/")) {
                        continue;
                    }
                    String className =
                            name.substring(0, name.length() - ".class".length()).replace('/', '.');
                    try {
                        Class<?> c = realm.loadClass(className);
                        if (!isHostedOnly(c)) {
                            classes.put(className, c);
                        }
                    } catch (Throwable t) {
                        // tolerated: classes referencing optional/absent dependencies
                    }
                }
            }
        }
        return classes;
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
                        resources
                                .computeIfAbsent(name, k -> new ArrayList<>())
                                .add(in.readAllBytes());
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
     * Routing decision used by the seeded caches: the prebuilt entry for the requested plugin, or
     * {@code null} to fall back to stock dynamic resolution. A baked plugin is used only on an
     * exact version match — it must never silently stand in for a different requested version.
     */
    public static Prebuilt match(String groupId, String artifactId, String requestedVersion) {
        Prebuilt prebuilt = BY_KEY.get(groupId + ":" + artifactId);
        if (prebuilt == null) {
            return null;
        }
        String baked = prebuilt.descriptor.getVersion();
        if (requestedVersion != null && baked != null && !requestedVersion.equals(baked)) {
            return null;
        }
        return prebuilt;
    }

    public static Map<String, Prebuilt> all() {
        return Collections.unmodifiableMap(BY_KEY);
    }
}
