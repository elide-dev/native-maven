package nmvn;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.codehaus.plexus.component.repository.ComponentDescriptor;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.graalvm.nativeimage.hosted.RuntimeResourceAccess;

/**
 * Build-time Feature that registers every class of every prebuilt plugin realm for runtime
 * reflection.
 *
 * <p>Why a Feature and not JSON reachability metadata: the JSON parser resolves type names against
 * the image classpath, but prebuilt plugin classes exist ONLY inside build-time ClassRealms -- the
 * names cannot be resolved, so the registration silently never attaches to the realm-loaded Class
 * objects (Guice then sees no constructors at runtime). A Feature runs on the builder JVM, holds
 * the actual realm-loaded Class objects, and can register exactly those.
 *
 * <p>The classes themselves are force-loaded by PrebuiltPluginRealms.buildAll (touching the
 * registry here triggers that build-time static init; the class is in --initialize-at-build-time,
 * so the initialized state is what gets snapshotted). This Feature only adds the reflection
 * registration, which is builder API and cannot live in maven-core.
 */
public final class PrebuiltReflectionFeature implements Feature {

    /** Sidecar components discovered by name via the sisu index — need full reflection (incl. annotations). */
    private static final Class<?>[] SIDECAR_COMPONENTS = {
        PrebuiltPluginDescriptorCache.class, PrebuiltPluginRealmCache.class, PrebuiltPluginConfigurationModule.class
    };

    /**
     * Component indexes on the classpath. Sisu's IndexedClassFinder requires each indexed class's
     * .class FILE to be readable AS A RESOURCE (and silently skips the entry otherwise), so every
     * listed class's bytes are embedded. Replaces the agent-recorded *.class resource entries of the
     * old reachability-metadata.json — and generalizes to any lib jar automatically.
     */
    private static final String[] CLASSPATH_INDEXES = {
        "META-INF/sisu/javax.inject.Named", "META-INF/maven/org.apache.maven.api.di.Inject"
    };

    /** Annotation packages whose .class bytes are introspected as streams by the DI scanners. */
    private static final String[] ANNOTATION_RESOURCE_PREFIXES = {
        "javax/inject/", "org/apache/maven/api/di/", "org/apache/maven/api/annotations/"
    };

    /** Exact classpath resources read at runtime (logging config, super POMs, jline terminal data). */
    private static final Set<String> ROOT_RESOURCES = Set.of(
            "maven.logger.properties",
            "simplelogger.properties",
            "org/apache/maven/messages/build.properties",
            "org/apache/maven/model/pom-4.0.0.xml",
            "org/apache/maven/model/pom-4.1.0.xml",
            "org/jline/nativ/Mac/arm64/libjlinenative.jnilib",
            "org/jline/utils/capabilities.txt");

    /**
     * Module-encapsulated resources (read via the jrt filesystem — Module.getResourceAsStream is
     * blocked by resource encapsulation for these): javac's message bundles and java.base's ICU
     * normalization data, plus annotation class bytes introspected by the scanners.
     */
    private static final String[][] MODULE_RESOURCES = {
        // NOTE: the old agent-generated JSON also listed javac's *_en.properties bundles — those
        // paths do not exist in the JDK (the agent recorded ResourceBundle fallback MISSES); the
        // real bundles work without embedding (javac messages verified in earlier builds).
        {"java.base", "jdk/internal/icu/impl/data/icudt76b/nfc.nrm"},
        {"java.base", "jdk/internal/icu/impl/data/icudt76b/nfkc.nrm"},
        {"java.base", "jdk/internal/icu/impl/data/icudt76b/uprops.icu"},
        {"java.base", "sun/net/idn/uidna.spp"},
        {"java.base", "java/lang/Deprecated.class"},
    };

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        for (Class<?> c : SIDECAR_COMPONENTS) {
            RuntimeReflection.register(c);
            RuntimeReflection.register(c.getDeclaredConstructors());
            RuntimeReflection.register(c.getDeclaredMethods());
            RuntimeReflection.register(c.getDeclaredFields());
        }
        registerCoreResources(access);
        PrebuiltPluginRealms.all().forEach((key, prebuilt) -> {
            Set<Class<?>> diRoots = diRoots(prebuilt);
            boolean bulk = isBulkPlugin(key);
            int failed = 0;
            int full = 0;
            int classOnly = 0;
            for (Class<?> c : prebuilt.classes.values()) {
                // Probe BEFORE registering: ReflectionDataBuilder parses each registered class's
                // generic signatures asynchronously during analysis, and ancient bytecode with
                // broken EnclosingMethod/Signature attributes (e.g. aether-util 1.7) makes that
                // throw InternalError where SVM has no catch — failing the whole image build.
                // The class stays in the baked realm (still loadable at runtime), it just gets
                // no reflection metadata.
                if (!probeGenericSignatures(c, key)) {
                    failed++;
                    continue;
                }
                try {
                    // Full method/field registration on EVERY baked class makes each method an
                    // analysis root. With kotlin (~30k) + spotbugs (~17k) + site (~6k) that is
                    // enough to OOM a 50GiB native-image analysis. For "bulk" plugins we only fully
                    // reflect DI roots (mojos / components / sisu index); everything else is
                    // class-only (still in the baked map for normal AOT calls). Smaller plugins
                    // (spotless, spring-boot, …) keep full surfaces so nested Plexus config beans
                    // keep working.
                    boolean fullSurface = !bulk || diRoots.contains(c);
                    registerReflectiveSurface(c, fullSurface);
                    if (fullSurface) {
                        full++;
                    } else {
                        classOnly++;
                    }
                } catch (Throwable t) {
                    failed++;
                }
            }
            System.out.println("nmvn feature: registered " + (prebuilt.classes.size() - failed) + " classes for "
                    + key + " (full=" + full + ", class-only=" + classOnly + ", failed=" + failed
                    + (bulk ? ", bulk-plugin" : "") + ")");
        });
        System.out.println("nmvn feature: " + PrebuiltPluginRealms.STATUS);
    }

    /**
     * Plugins whose realms are tens of thousands of classes (compilers, static analyzers, site).
     * Full reflective surfaces there are not needed for Maven DI and dominate native-image RAM.
     */
    private static boolean isBulkPlugin(String pluginKey) {
        // pluginKey is groupId:artifactId
        return pluginKey.endsWith(":kotlin-maven-plugin")
                || pluginKey.endsWith(":spotbugs-maven-plugin")
                || pluginKey.endsWith(":maven-site-plugin")
                || pluginKey.endsWith(":maven-surefire-plugin")
                || pluginKey.endsWith(":maven-failsafe-plugin");
    }

    /** Classes Guice/Sisu must construct or that are known plugin-internal components. */
    private static Set<Class<?>> diRoots(PrebuiltPluginRealms.Prebuilt prebuilt) {
        Set<Class<?>> roots = new HashSet<>();
        for (MojoDescriptor mojo : prebuilt.descriptor.getMojos()) {
            Class<?> impl = mojo.getImplementationClass();
            if (impl != null) {
                roots.add(impl);
            }
        }
        for (ComponentDescriptor<?> component : prebuilt.components) {
            Class<?> impl = component.getImplementationClass();
            if (impl != null) {
                roots.add(impl);
            }
        }
        roots.addAll(prebuilt.indexedClasses);
        return roots;
    }

    /**
     * @param fullSurface if true: class + methods + fields + safe constructors (Plexus config /
     *     Guice). if false: class only — type stays heap-reachable from the baked map for normal
     *     calls, but does not explode points-to with every method as a reflection root.
     */
    private static void registerReflectiveSurface(Class<?> c, boolean fullSurface) {
        RuntimeReflection.register(c);
        if (!fullSurface) {
            return;
        }

        Method[] methods = c.getDeclaredMethods();
        if (methods.length > 0) {
            RuntimeReflection.register(methods);
        }

        Field[] fields = c.getDeclaredFields();
        if (fields.length > 0) {
            RuntimeReflection.register(fields);
        }

        if (!isReflectivelyInstantiable(c) || !needsReflectiveInstantiation(c)) {
            return;
        }
        Constructor<?>[] constructors = c.getDeclaredConstructors();
        if (constructors.length == 0) {
            return;
        }
        List<Constructor<?>> safe = new ArrayList<>(constructors.length);
        for (Constructor<?> ctor : constructors) {
            if (isReflectivelyInstantiable(ctor.getDeclaringClass())) {
                safe.add(ctor);
            }
        }
        if (!safe.isEmpty()) {
            RuntimeReflection.register(safe.toArray(Constructor<?>[]::new));
        }
    }

    /**
     * Types for which {@code Constructor.newInstance} is legal on HotSpot and for which SVM's
     * {@code FactoryMethodSupport} accepts a factory accessor. Mirrors the non-error branch of
     * {@code ReflectionFeature.createAccessor} for constructors — abstract classes, interfaces,
     * arrays, primitives, and abstract enums (enum constants with bodies make the enum ACC_ABSTRACT)
     * must not be registered for reflective instantiation.
     */
    private static boolean isReflectivelyInstantiable(Class<?> c) {
        if (c == null || c.isInterface() || c.isAnnotation() || c.isArray() || c.isPrimitive()) {
            return false;
        }
        // Abstract class or abstract enum (enum with constant-specific class bodies).
        if (Modifier.isAbstract(c.getModifiers())) {
            return false;
        }
        // Hidden / local / anonymous types are almost never DI roots; skipping them avoids edge
        // cases with custom realm loaders and FactoryMethodSupport classification.
        if (c.isHidden() || c.isLocalClass() || c.isAnonymousClass()) {
            return false;
        }
        return true;
    }

    /**
     * Whether Guice/Plexus (or similar) might {@code newInstance} this type. Compiler internals
     * from kotlin-compiler-embeddable are called via normal (AOT) call edges once loaded — they are
     * never constructed by Maven's DI. Registering their constructors is what blew up native-image
     * with {@code FactoryMethodSupport: Must be a non-abstract instance class} once Kotlin was
     * actually baked (~30k types, ~155k reflect registrations).
     *
     * <p>Keep constructors for: mojos, Maven/Plexus glue, and typical plugin config beans. Skip the
     * Kotlin/IntelliJ compiler bulk (still fully registered for methods/fields so config-style
     * reflective access works if it ever appears).
     */
    private static boolean needsReflectiveInstantiation(Class<?> c) {
        String name = c.getName();
        // Kotlin stdlib / compiler / IntelliJ-shaded-in-compiler — not Maven DI components.
        if (name.startsWith("kotlin.")
                || name.startsWith("kotlinx.")
                || name.startsWith("org.jetbrains.kotlin.")
                || name.startsWith("org.jetbrains.kotlinx.")) {
            // Exception: the Maven plugin mojos themselves live under org.jetbrains.kotlin.maven
            // and MUST be instantiable via Guice.
            return name.startsWith("org.jetbrains.kotlin.maven.");
        }
        return true;
    }

    /**
     * Dry-runs every generic-signature query ReflectionDataBuilder performs on a registered class
     * (enclosing method/class, generic supertypes, per-member generic types), recursing into type
     * arguments and forcing wildcard/variable bound reification the same way SVM's type hashing
     * does. Returns false — and logs — if any query throws (InternalError, LinkageError, ...).
     */
    private static boolean probeGenericSignatures(Class<?> c, Object realmKey) {
        try {
            c.getEnclosingMethod();
            c.getEnclosingConstructor();
            c.getEnclosingClass();
            reifyTypes(new HashSet<>(), c.getTypeParameters());
            reifyTypes(new HashSet<>(), c.getGenericSuperclass());
            reifyTypes(new HashSet<>(), c.getGenericInterfaces());
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                reifyTypes(new HashSet<>(), m.getTypeParameters());
                reifyTypes(new HashSet<>(), m.getGenericParameterTypes());
                reifyTypes(new HashSet<>(), m.getGenericReturnType());
                reifyTypes(new HashSet<>(), m.getGenericExceptionTypes());
            }
            for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) {
                reifyTypes(new HashSet<>(), k.getTypeParameters());
                reifyTypes(new HashSet<>(), k.getGenericParameterTypes());
                reifyTypes(new HashSet<>(), k.getGenericExceptionTypes());
            }
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                reifyTypes(new HashSet<>(), f.getGenericType());
            }
            return true;
        } catch (Throwable t) {
            // Only InternalError (broken EnclosingMethod/Signature bytecode) is fatal to SVM's
            // analysis — SanitizeRealmJars should have stripped it; this is belt-and-suspenders.
            // LinkageError/TypeNotPresentException (absent optional deps) are tolerated by SVM,
            // so those classes still get registered — matching live-realm behavior.
            for (Throwable x = t; x != null; x = x.getCause()) {
                if (x instanceof InternalError) {
                    System.out.println("nmvn feature: SKIPPED " + c.getName() + " in " + realmKey
                            + " (broken generic metadata survived sanitization: " + t + ")");
                    return false;
                }
            }
            return true;
        }
    }

    private static void reifyTypes(Set<java.lang.reflect.Type> seen, java.lang.reflect.Type... types) {
        for (java.lang.reflect.Type type : types) {
            if (type == null || !seen.add(type)) { // add() hashes: forces the same lazy reification SVM triggers
                continue;
            }
            if (type instanceof java.lang.reflect.ParameterizedType p) {
                reifyTypes(seen, p.getActualTypeArguments());
                reifyTypes(seen, p.getRawType(), p.getOwnerType());
            } else if (type instanceof java.lang.reflect.WildcardType w) {
                reifyTypes(seen, w.getUpperBounds());
                reifyTypes(seen, w.getLowerBounds());
            } else if (type instanceof java.lang.reflect.GenericArrayType a) {
                reifyTypes(seen, a.getGenericComponentType());
            } else if (type instanceof java.lang.reflect.TypeVariable<?> v) {
                reifyTypes(seen, v.getBounds());
            }
        }
    }

    /** Programmatic replacement for the old reachability-metadata.json (except the foreign section). */
    private static void registerCoreResources(BeforeAnalysisAccess access) {
        Module unnamed = PrebuiltReflectionFeature.class.getModule();
        Set<String> added = new HashSet<>();
        int indexed = 0;
        int extra = 0;
        // NOTE: System.getProperty("java.class.path") is the BUILDER's own classpath, not the
        // image's — the image classpath comes from the Feature access object.
        for (Path classpathEntry : access.getApplicationClassPath()) {
            File file = classpathEntry.toFile();
            if (!file.isFile()) {
                continue;
            }
            try (JarFile jar = new JarFile(file)) {
                // .class resources for every index-listed component (sisu scanner requirement)
                for (String indexPath : CLASSPATH_INDEXES) {
                    JarEntry index = jar.getJarEntry(indexPath);
                    if (index == null) {
                        continue;
                    }
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(jar.getInputStream(index), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String className = line.trim();
                            if (className.isEmpty() || className.startsWith("#")) {
                                continue;
                            }
                            String resource = className.replace('.', '/') + ".class";
                            JarEntry classEntry = jar.getJarEntry(resource);
                            if (classEntry != null && added.add(resource)) {
                                RuntimeResourceAccess.addResource(
                                        unnamed, resource, jar.getInputStream(classEntry).readAllBytes());
                                indexed++;
                            }
                        }
                    }
                }
                // annotation class bytes + root config resources
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry jarEntry = entries.nextElement();
                    String name = jarEntry.getName();
                    boolean annotation = false;
                    for (String prefix : ANNOTATION_RESOURCE_PREFIXES) {
                        if (name.startsWith(prefix) && name.endsWith(".class")) {
                            annotation = true;
                            break;
                        }
                    }
                    if ((annotation || ROOT_RESOURCES.contains(name)) && added.add(name)) {
                        RuntimeResourceAccess.addResource(
                                unnamed, name, jar.getInputStream(jarEntry).readAllBytes());
                        extra++;
                    }
                }
            } catch (Exception e) {
                System.out.println("nmvn feature: cannot process " + classpathEntry + ": " + e);
            }
        }
        int moduleResources = 0;
        try {
            FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
            for (String[] moduleResource : MODULE_RESOURCES) {
                Path path = jrt.getPath("/modules/" + moduleResource[0] + "/" + moduleResource[1]);
                Module module = findModule(moduleResource[0]);
                if (module != null && Files.exists(path)) {
                    RuntimeResourceAccess.addResource(module, moduleResource[1], Files.readAllBytes(path));
                    moduleResources++;
                } else {
                    System.out.println("nmvn feature: SKIPPED module resource " + moduleResource[0] + "/"
                            + moduleResource[1] + " (module=" + module + ", exists=" + Files.exists(path) + ")");
                }
            }
        } catch (Exception e) {
            System.out.println("nmvn feature: module resources failed: " + e);
        }
        System.out.println("nmvn feature: core resources embedded — " + indexed + " indexed .class, " + extra
                + " annotation/config, " + moduleResources + " module resources");
    }

    /** Boot-layer lookup with a fallback through a loaded class (jdk.compiler may not be boot-resolved). */
    private static Module findModule(String name) {
        Module module = ModuleLayer.boot().findModule(name).orElse(null);
        if (module == null && "jdk.compiler".equals(name)) {
            try {
                module = Class.forName("com.sun.tools.javac.api.JavacTool").getModule();
            } catch (ClassNotFoundException ignored) {
                // remains null; caller logs the skip
            }
        }
        return module;
    }
}
