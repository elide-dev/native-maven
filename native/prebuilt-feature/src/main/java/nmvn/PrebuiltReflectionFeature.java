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

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.graalvm.nativeimage.hosted.RuntimeResourceAccess;

/**
 * Build-time Feature that registers runtime reflection for prebuilt plugin realms.
 *
 * <p>Why a Feature and not JSON reachability metadata: the JSON parser resolves type names against
 * the image classpath, but prebuilt plugin classes exist ONLY inside build-time ClassRealms — the
 * names cannot be resolved, so registration would silently miss the realm-loaded {@link Class}
 * objects (Guice then fails at runtime). A Feature holds the actual realm-loaded classes.
 *
 * <p>Registration is <b>demand-driven</b> ({@link PrebuiltReflectionDemand}): Sisu/Guice inject
 * points, mojo {@code plugin.xml} parameters (fields/setters + nested config beans), and component
 * requirements — not every method of every class in fat jars (hibernate/vaadin/kotlin, …).
 */
public final class PrebuiltReflectionFeature implements Feature {

    /** Sidecar components discovered by name via the sisu index — need full reflection. */
    private static final Class<?>[] SIDECAR_COMPONENTS = {
        PrebuiltPluginDescriptorCache.class,
        PrebuiltPluginRealmCache.class,
        PrebuiltPluginConfigurationModule.class,
        JvmFallbackBuildPluginManager.class
    };

    private static final String[] CLASSPATH_INDEXES = {
        "META-INF/sisu/javax.inject.Named", "META-INF/maven/org.apache.maven.api.di.Inject"
    };

    private static final String[] ANNOTATION_RESOURCE_PREFIXES = {
        "javax/inject/", "org/apache/maven/api/di/", "org/apache/maven/api/annotations/"
    };

    private static final Set<String> ROOT_RESOURCES = Set.of(
            "maven.logger.properties",
            "simplelogger.properties",
            "org/apache/maven/messages/build.properties",
            "org/apache/maven/model/pom-4.0.0.xml",
            "org/apache/maven/model/pom-4.1.0.xml",
            "org/jline/nativ/Mac/arm64/libjlinenative.jnilib",
            "org/jline/utils/capabilities.txt");

    private static final String[][] MODULE_RESOURCES = {
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
            Map<Class<?>, PrebuiltReflectionDemand.Surface> demand =
                    new java.util.LinkedHashMap<>(PrebuiltReflectionDemand.compute(prebuilt));
            // Packages that use Class.forName / ServiceLoader / heavy self-reflection outside DI.
            // Byte Buddy (Hibernate enhance) fails with BindingPriority$Resolver size=0 without this.
            int infra = expandInfrastructureSurfaces(prebuilt, demand);

            int types = 0;
            int ctors = 0;
            int fields = 0;
            int methods = 0;
            int failed = 0;

            for (Map.Entry<Class<?>, PrebuiltReflectionDemand.Surface> e : demand.entrySet()) {
                Class<?> c = e.getKey();
                PrebuiltReflectionDemand.Surface surface = e.getValue();
                if (!probeGenericSignatures(c, key)) {
                    failed++;
                    continue;
                }
                try {
                    int[] counts = registerSurface(c, surface);
                    types++;
                    ctors += counts[0];
                    fields += counts[1];
                    methods += counts[2];
                } catch (Throwable t) {
                    failed++;
                    System.out.println("nmvn feature: SKIPPED demand type " + c.getName() + " in " + key + ": " + t);
                }
            }

            System.out.println("nmvn feature: demand-reflect " + key
                    + " realmClasses=" + prebuilt.classes.size()
                    + " types=" + types
                    + " ctors=" + ctors
                    + " fields=" + fields
                    + " methods=" + methods
                    + " infraTypes=" + infra
                    + " failed=" + failed);
        });
        System.out.println("nmvn feature: " + PrebuiltPluginRealms.STATUS);
    }

    /**
     * Full reflective surface for small-but-critical infrastructure packages present in a realm.
     * Demand analysis never sees Class.forName / annotation-method introspection these libraries do.
     */
    private static int expandInfrastructureSurfaces(
            PrebuiltPluginRealms.Prebuilt prebuilt, Map<Class<?>, PrebuiltReflectionDemand.Surface> demand) {
        int n = 0;
        for (Class<?> c : prebuilt.classes.values()) {
            if (!isReflectiveInfrastructure(c.getName())) {
                continue;
            }
            PrebuiltReflectionDemand.Surface surface =
                    demand.computeIfAbsent(c, k -> new PrebuiltReflectionDemand.Surface());
            surface.allMembers = true;
            try {
                for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                    surface.constructors.add(ctor);
                }
            } catch (Throwable ignored) {
                // incomplete type
            }
            n++;
        }
        // Image-classpath copies (Preserve / parent loader) for commons-logging factories.
        for (String name : new String[] {
            "org.apache.commons.logging.impl.LogFactoryImpl",
            "org.apache.commons.logging.impl.Slf4jLogFactory",
            "org.apache.commons.logging.impl.Log4jApiLogFactory",
            "org.apache.commons.logging.LogFactory"
        }) {
            try {
                Class<?> c = Class.forName(name, false, PrebuiltReflectionFeature.class.getClassLoader());
                PrebuiltReflectionDemand.Surface surface =
                        demand.computeIfAbsent(c, k -> new PrebuiltReflectionDemand.Surface());
                surface.allMembers = true;
                for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                    surface.constructors.add(ctor);
                }
                n++;
            } catch (ClassNotFoundException | LinkageError ignored) {
                // not on image classpath
            }
        }
        return n;
    }

    private static boolean isReflectiveInfrastructure(String className) {
        return className.startsWith("org.apache.commons.logging.")
                // Byte Buddy: Hibernate enhance / bytecode provider static init introspects its own
                // annotations and methods (BindingPriority$Resolver → size=0 without this).
                || className.startsWith("net.bytebuddy.")
                // Hibernate's ByteBuddy integration + SPI glue
                || className.startsWith("org.hibernate.bytecode.");
    }

    /** @return int[]{ctors, fields, methods} registered */
    private static int[] registerSurface(Class<?> c, PrebuiltReflectionDemand.Surface surface) {
        int ctors = 0;
        int fields = 0;
        int methods = 0;
        RuntimeReflection.register(c);
        if (PrebuiltReflectionDemand.isReflectivelyInstantiable(c)) {
            try {
                RuntimeReflection.registerForReflectiveInstantiation(c);
            } catch (Throwable ignored) {
                // not all types / Graal versions
            }
        }
        if (surface.allMembers) {
            Constructor<?>[] allCtors = c.getDeclaredConstructors();
            if (allCtors.length > 0) {
                RuntimeReflection.register(allCtors);
                ctors += allCtors.length;
            }
            Method[] allMethods = c.getDeclaredMethods();
            if (allMethods.length > 0) {
                RuntimeReflection.register(allMethods);
                methods += allMethods.length;
            }
            Field[] allFields = c.getDeclaredFields();
            if (allFields.length > 0) {
                RuntimeReflection.register(allFields);
                fields += allFields.length;
            }
            // Annotations on methods/fields are read as Class objects by Byte Buddy's description
            // layer — ensure annotation types themselves are registered.
            try {
                for (java.lang.annotation.Annotation a : c.getDeclaredAnnotations()) {
                    RuntimeReflection.register(a.annotationType());
                }
            } catch (Throwable ignored) {
                // ignore
            }
            return new int[] {ctors, fields, methods};
        }
        if (!surface.constructors.isEmpty()) {
            Constructor<?>[] arr = surface.constructors.toArray(Constructor<?>[]::new);
            RuntimeReflection.register(arr);
            ctors += arr.length;
        }
        if (!surface.fields.isEmpty()) {
            Field[] arr = surface.fields.toArray(Field[]::new);
            RuntimeReflection.register(arr);
            fields += arr.length;
        }
        if (!surface.methods.isEmpty()) {
            Method[] arr = surface.methods.toArray(Method[]::new);
            RuntimeReflection.register(arr);
            methods += arr.length;
        }
        return new int[] {ctors, fields, methods};
    }

    /**
     * Dry-runs generic-signature queries ReflectionDataBuilder performs on a registered class.
     * Returns false if InternalError would abort the image build (broken bytecode metadata).
     */
    private static boolean probeGenericSignatures(Class<?> c, Object realmKey) {
        try {
            c.getEnclosingMethod();
            c.getEnclosingConstructor();
            c.getEnclosingClass();
            reifyTypes(new HashSet<>(), c.getTypeParameters());
            reifyTypes(new HashSet<>(), c.getGenericSuperclass());
            reifyTypes(new HashSet<>(), c.getGenericInterfaces());
            for (Method m : c.getDeclaredMethods()) {
                reifyTypes(new HashSet<>(), m.getTypeParameters());
                reifyTypes(new HashSet<>(), m.getGenericParameterTypes());
                reifyTypes(new HashSet<>(), m.getGenericReturnType());
                reifyTypes(new HashSet<>(), m.getGenericExceptionTypes());
            }
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                reifyTypes(new HashSet<>(), k.getTypeParameters());
                reifyTypes(new HashSet<>(), k.getGenericParameterTypes());
                reifyTypes(new HashSet<>(), k.getGenericExceptionTypes());
            }
            for (Field f : c.getDeclaredFields()) {
                reifyTypes(new HashSet<>(), f.getGenericType());
            }
            return true;
        } catch (Throwable t) {
            for (Throwable x = t; x != null; x = x.getCause()) {
                if (x instanceof InternalError) {
                    System.out.println("nmvn feature: SKIPPED " + c.getName() + " in " + realmKey
                            + " (broken generic metadata: " + t + ")");
                    return false;
                }
            }
            return true;
        }
    }

    private static void reifyTypes(Set<java.lang.reflect.Type> seen, java.lang.reflect.Type... types) {
        for (java.lang.reflect.Type type : types) {
            if (type == null || !seen.add(type)) {
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

    private static void registerCoreResources(BeforeAnalysisAccess access) {
        Module unnamed = PrebuiltReflectionFeature.class.getModule();
        Set<String> added = new HashSet<>();
        int indexed = 0;
        int extra = 0;
        for (Path classpathEntry : access.getApplicationClassPath()) {
            File file = classpathEntry.toFile();
            if (!file.isFile()) {
                continue;
            }
            try (JarFile jar = new JarFile(file)) {
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
                                        unnamed,
                                        resource,
                                        jar.getInputStream(classEntry).readAllBytes());
                                indexed++;
                            }
                        }
                    }
                }
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

    private static Module findModule(String name) {
        Module module = ModuleLayer.boot().findModule(name).orElse(null);
        if (module == null && "jdk.compiler".equals(name)) {
            try {
                module = Class.forName("com.sun.tools.javac.api.JavacTool").getModule();
            } catch (ClassNotFoundException ignored) {
                // remains null
            }
        }
        return module;
    }
}
