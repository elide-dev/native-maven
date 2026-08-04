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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.Parameter;
import org.codehaus.plexus.component.repository.ComponentDescriptor;
import org.codehaus.plexus.component.repository.ComponentRequirement;

/**
 * Computes the minimal reflective surface Maven/Sisu/Plexus need for a prebuilt plugin realm:
 *
 * <ul>
 *   <li>Sisu/Guice: {@code @Inject} / {@code @Requirement} constructors, fields, methods</li>
 *   <li>Mojo parameters from {@code plugin.xml}: matching fields/setters and nested config beans</li>
 *   <li>Component/sisu-index seeds and their superclass chains</li>
 *   <li>Reflective factories: {@code META-INF/services/*}, commons-logging LogFactory discovery</li>
 * </ul>
 *
 * <p>Everything else in a fat realm (kotlin/hibernate/vaadin jars, …) stays off the reflection
 * demand set — still force-loaded into the baked map for normal AOT calls, but not registered as
 * reflection roots for native-image analysis.
 */
public final class PrebuiltReflectionDemand {

    /**
     * Types commons-logging / Spring discover via Class.forName + newInstance (no @Inject). Present
     * in spring-boot-maven-plugin's realm through spring-core → commons-logging.
     */
    private static final String[] COMMONS_LOGGING_FACTORIES = {
        "org.apache.commons.logging.impl.LogFactoryImpl",
        "org.apache.commons.logging.impl.Slf4jLogFactory",
        "org.apache.commons.logging.impl.Log4jApiLogFactory",
        "org.apache.commons.logging.impl.Log4JLogger",
        "org.apache.commons.logging.impl.Jdk14Logger",
        "org.apache.commons.logging.impl.Jdk13LumberjackLogger",
        "org.apache.commons.logging.impl.SimpleLog",
        "org.apache.commons.logging.impl.NoOpLog",
        "org.apache.commons.logging.impl.WeakHashtable",
    };

    /** Per-class members that must be registered for reflective access. */
    public static final class Surface {
        public final Set<Constructor<?>> constructors = new LinkedHashSet<>();
        public final Set<Field> fields = new LinkedHashSet<>();
        public final Set<Method> methods = new LinkedHashSet<>();
        /** When true, also register every declared field/method (used sparingly). */
        public boolean allMembers;
    }

    private final Map<String, Class<?>> realmClasses;
    private final Map<Class<?>, Surface> surfaces = new HashMap<>();
    private final Set<Class<?>> visited = new HashSet<>();
    private final Queue<Class<?>> queue = new ArrayDeque<>();

    private PrebuiltReflectionDemand(Map<String, Class<?>> realmClasses) {
        this.realmClasses = realmClasses;
    }

    public static Map<Class<?>, Surface> compute(PrebuiltPluginRealms.Prebuilt prebuilt) {
        PrebuiltReflectionDemand d = new PrebuiltReflectionDemand(prebuilt.classes);
        d.seed(prebuilt);
        d.drain();
        return Collections.unmodifiableMap(d.surfaces);
    }

    private void seed(PrebuiltPluginRealms.Prebuilt prebuilt) {
        for (MojoDescriptor mojo : prebuilt.descriptor.getMojos()) {
            Class<?> impl = mojo.getImplementationClass();
            if (impl == null && mojo.getImplementation() != null) {
                impl = resolve(mojo.getImplementation());
            }
            enqueueHierarchy(impl);
            demandInstantiable(impl);
            // Guice/Sisu need the mojo type fully injectable; scan inject points on hierarchy.
            for (Class<?> c = impl; c != null && c != Object.class; c = c.getSuperclass()) {
                scanInjectPoints(c);
            }
            demandMojoParameters(mojo, impl);
        }
        for (ComponentDescriptor<?> component : prebuilt.components) {
            Class<?> impl = component.getImplementationClass();
            if (impl == null && component.getImplementation() != null) {
                impl = resolve(component.getImplementation());
            }
            enqueueHierarchy(impl);
            demandInstantiable(impl);
            for (Class<?> c = impl; c != null && c != Object.class; c = c.getSuperclass()) {
                scanInjectPoints(c);
            }
            demandComponentRequirements(component, impl);
        }
        for (Class<?> indexed : prebuilt.indexedClasses) {
            enqueueHierarchy(indexed);
            demandInstantiable(indexed);
            for (Class<?> c = indexed; c != null && c != Object.class; c = c.getSuperclass()) {
                scanInjectPoints(c);
            }
        }
        // Reflective Class.forName + ctor.newInstance factories (not visible via @Inject).
        seedKnownReflectiveFactories();
        seedServiceLoaderImplementations();
    }

    /** commons-logging LogFactory static init: Class.forName(LogFactoryImpl).newInstance(). */
    private void seedKnownReflectiveFactories() {
        boolean loggingPresent =
                realmClasses.keySet().stream().anyMatch(n -> n.startsWith("org.apache.commons.logging."));
        // Always try these when the plugin realm has spring/commons-logging, and also when they
        // resolve from the image classpath (Preserve package=org.apache.commons.logging.impl.*).
        for (String name : COMMONS_LOGGING_FACTORIES) {
            Class<?> c = realmClasses.get(name);
            if (c == null) {
                c = resolve(name);
            }
            if (c != null && (loggingPresent || isInRealm(c) || name.contains("commons.logging"))) {
                enqueue(c);
                demandInstantiable(c);
            }
        }
        // Unconditionally demand LogFactoryImpl if it exists anywhere — spring-boot repackage hits
        // this on first Spring JCL/commons-logging use inside the prebuilt realm.
        Class<?> logFactoryImpl = realmClasses.get("org.apache.commons.logging.impl.LogFactoryImpl");
        if (logFactoryImpl == null) {
            logFactoryImpl = resolve("org.apache.commons.logging.impl.LogFactoryImpl");
        }
        if (logFactoryImpl != null) {
            enqueue(logFactoryImpl);
            demandInstantiable(logFactoryImpl);
        }
    }

    /**
     * Register every type listed under {@code META-INF/services/} in realm jars — Jackson modules,
     * HTTP clients, etc. use ServiceLoader / equivalent reflective instantiation.
     */
    private void seedServiceLoaderImplementations() {
        Set<URI> jarUris = new LinkedHashSet<>();
        for (Class<?> c : realmClasses.values()) {
            try {
                if (c.getProtectionDomain() == null || c.getProtectionDomain().getCodeSource() == null) {
                    continue;
                }
                URL location = c.getProtectionDomain().getCodeSource().getLocation();
                if (location != null) {
                    jarUris.add(location.toURI());
                }
            } catch (Throwable ignored) {
                // ignore incomplete protection domains
            }
        }
        for (URI uri : jarUris) {
            try {
                if ("file".equals(uri.getScheme())
                        && uri.getPath() != null
                        && uri.getPath().endsWith(".jar")) {
                    try (JarFile jar = new JarFile(Path.of(uri).toFile())) {
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            JarEntry entry = entries.nextElement();
                            String name = entry.getName();
                            if (!name.startsWith("META-INF/services/") || name.endsWith("/")) {
                                continue;
                            }
                            try (InputStream in = jar.getInputStream(entry);
                                    BufferedReader reader =
                                            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                                demandServiceLines(reader);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
                // skip unreadable jars
            }
        }
    }

    private void demandServiceLines(BufferedReader reader) throws java.io.IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash).trim();
            }
            if (line.isEmpty()) {
                continue;
            }
            Class<?> c = realmClasses.get(line);
            if (c == null) {
                c = resolve(line);
            }
            if (c != null) {
                enqueue(c);
                demandInstantiable(c);
            }
        }
    }

    private void drain() {
        while (!queue.isEmpty()) {
            Class<?> c = queue.poll();
            if (c == null || !visited.add(c)) {
                continue;
            }
            if (shouldSkipType(c)) {
                continue;
            }
            // Config-bean expansion: Plexus sets nested objects via setters/fields without @Inject.
            if (isConfigBeanCandidate(c)) {
                demandConfigBeanMembers(c);
            }
            scanInjectPoints(c);
            Class<?> superClass = c.getSuperclass();
            if (superClass != null && superClass != Object.class) {
                enqueue(superClass);
            }
        }
    }

    private void demandMojoParameters(MojoDescriptor mojo, Class<?> impl) {
        if (impl == null) {
            return;
        }
        List<Parameter> parameters = mojo.getParameters();
        if (parameters == null) {
            return;
        }
        for (Parameter parameter : parameters) {
            String name = parameter.getName();
            String alias = parameter.getAlias();
            demandParameterBinding(impl, name);
            if (alias != null && !alias.isEmpty() && !alias.equals(name)) {
                demandParameterBinding(impl, alias);
            }
            Class<?> type = resolve(parameter.getType());
            if (type == null && parameter.getImplementation() != null) {
                type = resolve(parameter.getImplementation());
            }
            if (type != null) {
                enqueue(type);
                demandInstantiable(type);
            }
        }
    }

    private void demandParameterBinding(Class<?> type, String parameterName) {
        if (parameterName == null || parameterName.isEmpty()) {
            return;
        }
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            Field field = findField(c, parameterName);
            if (field != null) {
                demandField(field);
                enqueueType(field.getGenericType());
            }
            for (Method method : findMutators(c, parameterName)) {
                demandMethod(method);
                for (Type p : method.getGenericParameterTypes()) {
                    enqueueType(p);
                }
            }
        }
    }

    private void demandComponentRequirements(ComponentDescriptor<?> component, Class<?> impl) {
        if (impl == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<ComponentRequirement> requirements = component.getRequirements();
        if (requirements == null) {
            return;
        }
        for (ComponentRequirement req : requirements) {
            String fieldName = req.getFieldName();
            if (fieldName != null && !fieldName.isEmpty()) {
                demandParameterBinding(impl, fieldName);
            }
            Class<?> role = resolve(req.getRole());
            if (role != null) {
                enqueue(role);
            }
        }
    }

    private void demandConfigBeanMembers(Class<?> c) {
        for (Field field : c.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            demandField(field);
            enqueueType(field.getGenericType());
        }
        for (Method method : c.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            String name = method.getName();
            if (!(name.startsWith("set") || name.startsWith("add") || name.startsWith("with"))
                    || method.getParameterCount() != 1) {
                continue;
            }
            // Prefer public/protected mutators (Plexus uses them); still allow package-private.
            if (Modifier.isPrivate(method.getModifiers())) {
                continue;
            }
            demandMethod(method);
            enqueueType(method.getGenericParameterTypes()[0]);
        }
        demandInstantiable(c);
    }

    private boolean isConfigBeanCandidate(Class<?> c) {
        // Only expand types that live in this plugin realm — avoids walking half the JDK / image CP.
        if (!isInRealm(c) || shouldSkipType(c) || c.isEnum() || c.isAnnotation() || c.isInterface()) {
            return false;
        }
        return true;
    }

    private void scanInjectPoints(Class<?> c) {
        if (c == null || shouldSkipType(c)) {
            return;
        }
        try {
            for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                if (hasInjectAnnotation(ctor.getDeclaredAnnotations())
                        || hasInjectAnnotation(ctor.getParameterAnnotations())) {
                    demandConstructor(ctor);
                    for (Type p : ctor.getGenericParameterTypes()) {
                        enqueueType(p);
                    }
                }
            }
            // Guice JIT: single public constructor is injectable without annotations.
            Constructor<?>[] ctors = c.getDeclaredConstructors();
            if (ctors.length == 1 && Modifier.isPublic(ctors[0].getModifiers())) {
                demandConstructor(ctors[0]);
                for (Type p : ctors[0].getGenericParameterTypes()) {
                    enqueueType(p);
                }
            }
            for (Field field : c.getDeclaredFields()) {
                if (hasInjectAnnotation(field.getDeclaredAnnotations())) {
                    demandField(field);
                    enqueueType(field.getGenericType());
                }
            }
            for (Method method : c.getDeclaredMethods()) {
                if (hasInjectAnnotation(method.getDeclaredAnnotations())
                        || hasInjectAnnotation(method.getParameterAnnotations())) {
                    demandMethod(method);
                    for (Type p : method.getGenericParameterTypes()) {
                        enqueueType(p);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Incomplete types in fat realms — skip inject scan for this class.
        }
    }

    private void demandInstantiable(Class<?> c) {
        if (c == null || !isReflectivelyInstantiable(c)) {
            return;
        }
        try {
            for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                demandConstructor(ctor);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private void demandConstructor(Constructor<?> ctor) {
        surface(ctor.getDeclaringClass()).constructors.add(ctor);
        enqueue(ctor.getDeclaringClass());
    }

    private void demandField(Field field) {
        surface(field.getDeclaringClass()).fields.add(field);
        enqueue(field.getDeclaringClass());
    }

    private void demandMethod(Method method) {
        surface(method.getDeclaringClass()).methods.add(method);
        enqueue(method.getDeclaringClass());
    }

    private Surface surface(Class<?> c) {
        return surfaces.computeIfAbsent(c, k -> new Surface());
    }

    private void enqueueHierarchy(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            enqueue(c);
        }
    }

    private void enqueue(Class<?> c) {
        if (c == null || shouldSkipType(c)) {
            return;
        }
        // Prefer realm-loaded copy when present (same name, correct loader).
        Class<?> realm = realmClasses.get(c.getName());
        if (realm != null) {
            c = realm;
        } else if (!isInRealm(c)) {
            // Image-classpath / foreign types: record the class + inject annotations only; do not
            // treat them as Plexus config beans (would walk the world).
            surface(c);
            if (visited.add(c)) {
                scanInjectPoints(c);
            }
            return;
        }
        surface(c);
        if (!visited.contains(c)) {
            queue.add(c);
        }
    }

    private boolean isInRealm(Class<?> c) {
        return realmClasses.get(c.getName()) == c;
    }

    private void enqueueType(Type type) {
        for (Class<?> raw : rawClasses(type)) {
            enqueue(raw);
        }
    }

    private static Set<Class<?>> rawClasses(Type type) {
        Set<Class<?>> out = new LinkedHashSet<>();
        collectRaw(type, out, 0);
        return out;
    }

    private static void collectRaw(Type type, Set<Class<?>> out, int depth) {
        if (type == null || depth > 8) {
            return;
        }
        if (type instanceof Class<?> c) {
            if (c.isArray()) {
                collectRaw(c.getComponentType(), out, depth + 1);
            } else {
                out.add(c);
            }
        } else if (type instanceof ParameterizedType p) {
            collectRaw(p.getRawType(), out, depth + 1);
            for (Type arg : p.getActualTypeArguments()) {
                collectRaw(arg, out, depth + 1);
            }
        }
    }

    private Class<?> resolve(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        // plugin.xml sometimes uses java.util.List without generics.
        Class<?> realm = realmClasses.get(name);
        if (realm != null) {
            return realm;
        }
        try {
            return Class.forName(name, false, PrebuiltReflectionDemand.class.getClassLoader());
        } catch (Throwable t) {
            try {
                // Try any realm class's loader
                if (!realmClasses.isEmpty()) {
                    ClassLoader cl = realmClasses.values().iterator().next().getClassLoader();
                    return Class.forName(name, false, cl);
                }
            } catch (Throwable ignored) {
                // fall through
            }
            return null;
        }
    }

    private static Field findField(Class<?> c, String name) {
        try {
            return c.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static List<Method> findMutators(Class<?> c, String parameterName) {
        List<Method> found = new java.util.ArrayList<>();
        String cap = capitalize(parameterName);
        String[] prefixes = {"set" + cap, "add" + cap, "with" + cap};
        for (Method m : c.getDeclaredMethods()) {
            if (m.getParameterCount() != 1 || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            for (String p : prefixes) {
                if (m.getName().equals(p)) {
                    found.add(m);
                }
            }
            // Plexus also matches setparameterName with exact property mapping
            if (m.getName().equals("set" + parameterName) || m.getName().equals("add" + parameterName)) {
                found.add(m);
            }
        }
        return found;
    }

    private static String capitalize(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1))) {
            return name; // already unusual (URL → setURL)
        }
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    private static boolean hasInjectAnnotation(Annotation[] annotations) {
        if (annotations == null) {
            return false;
        }
        for (Annotation a : annotations) {
            if (isInjectLike(a.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInjectAnnotation(Annotation[][] parameterAnnotations) {
        if (parameterAnnotations == null) {
            return false;
        }
        for (Annotation[] annos : parameterAnnotations) {
            if (hasInjectAnnotation(annos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInjectLike(String name) {
        return "javax.inject.Inject".equals(name)
                || "jakarta.inject.Inject".equals(name)
                || "com.google.inject.Inject".equals(name)
                || "org.apache.maven.api.di.Inject".equals(name)
                || "org.codehaus.plexus.component.annotations.Requirement".equals(name)
                || "org.codehaus.plexus.component.annotations.Component".equals(name)
                || (name.endsWith(".Requirement") && name.contains("plexus"))
                || (name.endsWith(".Inject") && (name.contains("inject") || name.contains("guice")));
    }

    private static boolean shouldSkipType(Class<?> c) {
        if (c == null || c.isPrimitive() || c == Object.class || c == void.class || c == Void.class) {
            return true;
        }
        String name = c.getName();
        if (name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jakarta.")
                || name.startsWith("jdk.")
                || name.startsWith("sun.")
                || name.startsWith("com.sun.")) {
            return true;
        }
        return false;
    }

    static boolean isReflectivelyInstantiable(Class<?> c) {
        if (c == null || c.isInterface() || c.isAnnotation() || c.isArray() || c.isPrimitive()) {
            return false;
        }
        if (Modifier.isAbstract(c.getModifiers())) {
            return false;
        }
        if (c.isHidden() || c.isLocalClass() || c.isAnonymousClass()) {
            return false;
        }
        return true;
    }
}
