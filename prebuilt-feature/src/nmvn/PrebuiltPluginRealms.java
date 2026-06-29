package nmvn;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

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
         * Plugin-internal components (e.g. plexus-compiler's CompilerManager), parsed from the jars'
         * {@code META-INF/sisu/javax.inject.Named} indexes at build time with PINNED implementation
         * classes. Stock discovery would read the index and load these BY NAME through the realm at
         * runtime — both impossible on a frozen realm — so they are baked instead and registered via
         * ComponentDescriptorBeanModule (which takes the LoadedClass path for pinned descriptors).
         */
        public final List<ComponentDescriptor<?>> components;

        /** Runtime once-guard: set after the realm's beans are published to the container. */
        public volatile boolean published;

        Prebuilt(
                PluginDescriptor descriptor,
                ClassRealm realm,
                Map<String, Class<?>> classes,
                List<ComponentDescriptor<?>> components) {
            this.descriptor = descriptor;
            this.realm = realm;
            this.classes = classes;
            this.components = components;
        }
    }

    /**
     * Serves the baked class map by name. Installed as an IMPORT on the frozen realm for every
     * plugin package: classworlds consults imports BEFORE the (dead) self and the parent, which
     * makes {@code realm.loadClass(pluginClassName)} work again at runtime — required by sisu's
     * {@code ComponentDescriptor.getRoleClass()} (always by-name, unpinnable), requirement-role
     * deferral, and any lazy {@code Class.forName} inside plugin code.
     */
    static final class BakedClassLoader extends ClassLoader {
        private final Map<String, Class<?>> classes;

        BakedClassLoader(Map<String, Class<?>> classes) {
            super(null);
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            Class<?> c = classes.get(name);
            if (c == null) {
                throw new ClassNotFoundException(name);
            }
            return c;
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
            // runtime), bake plugin-internal components from the sisu indexes, and install the
            // import shim so by-name resolution of baked classes works again at runtime.
            Map<String, Class<?>> classes = loadAllClasses(realm, jars);
            List<ComponentDescriptor<?>> components = bakeComponents(realm, classes, jars);
            BakedClassLoader baked = new BakedClassLoader(classes);
            Set<String> packages = new LinkedHashSet<>();
            for (String className : classes.keySet()) {
                int dot = className.lastIndexOf('.');
                packages.add(dot > 0 ? className.substring(0, dot) : className);
            }
            for (String pkg : packages) {
                realm.importFrom(baked, pkg);
            }

            // Step 4: NOW swap the parent to the baked core realm. This is an ordinary heap
            // reference, so it survives the snapshot — the hierarchy is correct at build time and
            // needs no per-plugin runtime fixup. It also puts plexus.core into the realm's Sisu
            // visibility set (RealmManager.computeVisibleNames walks getParentRealm()).
            realm.setParentRealm(core);

            BY_KEY.put(groupId + ":" + artifactId, new Prebuilt(descriptor, realm, classes, components));
            report.append(groupId)
                    .append(':')
                    .append(artifactId)
                    .append("(mojos=")
                    .append(descriptor.getMojos().size())
                    .append(",classes=")
                    .append(classes.size())
                    .append(",components=")
                    .append(components.size())
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
                        classes.put(className, realm.loadClass(className));
                    } catch (Throwable t) {
                        // tolerated: classes referencing optional/absent dependencies
                    }
                }
            }
        }
        return classes;
    }

    /**
     * Parses each jar's {@code META-INF/sisu/javax.inject.Named} index into plexus
     * {@link ComponentDescriptor}s with PINNED implementation classes. Each component is bound
     * under itself and every non-JDK interface in its hierarchy (approximating sisu's wildcard
     * binding); the hint follows sisu's convention: explicit {@code @Named} value, else "default"
     * for Default-prefixed impls, else the FQCN.
     */
    private static List<ComponentDescriptor<?>> bakeComponents(
            ClassRealm realm, Map<String, Class<?>> classes, String jars) throws Exception {
        List<ComponentDescriptor<?>> components = new ArrayList<>();
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
                        if (impl == null) {
                            continue; // failed to load at build time; nothing we can do
                        }
                        String hint = hintOf(impl);
                        for (Class<?> role : roleClosure(impl)) {
                            ComponentDescriptor<Object> cd = new ComponentDescriptor<>();
                            cd.setRole(role.getName());
                            cd.setRoleHint(hint);
                            cd.setImplementation(impl.getName());
                            // ORDER: setRealm first — it nulls the cached implementation class.
                            cd.setRealm(realm);
                            cd.setImplementationClass(impl);
                            components.add(cd);
                        }
                    }
                }
            }
        }
        return components;
    }

    /** Sisu-style binding hint: explicit @Named value, else "default" for Default-prefixed impls, else FQCN. */
    private static String hintOf(Class<?> impl) {
        javax.inject.Named named = impl.getAnnotation(javax.inject.Named.class);
        if (named != null && !named.value().isEmpty()) {
            return named.value();
        }
        return impl.getSimpleName().startsWith("Default") ? "default" : impl.getName();
    }

    /** The impl itself plus every non-JDK interface in its type hierarchy. */
    private static Set<Class<?>> roleClosure(Class<?> impl) {
        Set<Class<?>> roles = new LinkedHashSet<>();
        roles.add(impl);
        for (Class<?> t = impl; t != null && t != Object.class; t = t.getSuperclass()) {
            collectInterfaces(t, roles);
        }
        roles.removeIf(r -> r.getName().startsWith("java.") || r.getName().startsWith("javax."));
        return roles;
    }

    private static void collectInterfaces(Class<?> type, Set<Class<?>> into) {
        for (Class<?> iface : type.getInterfaces()) {
            if (into.add(iface)) {
                collectInterfaces(iface, into);
            }
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
