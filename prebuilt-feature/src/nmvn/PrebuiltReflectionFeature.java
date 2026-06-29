package nmvn;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

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
            int failed = 0;
            for (Class<?> c : prebuilt.classes.values()) {
                try {
                    RuntimeReflection.register(c);
                    RuntimeReflection.register(c.getDeclaredConstructors());
                    RuntimeReflection.register(c.getDeclaredMethods());
                    RuntimeReflection.register(c.getDeclaredFields());
                } catch (Throwable t) {
                    failed++;
                }
            }
            System.out.println("nmvn feature: registered " + (prebuilt.classes.size() - failed) + " classes ("
                    + failed + " failed) for " + key);
        });
        System.out.println("nmvn feature: " + PrebuiltPluginRealms.STATUS);
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
