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
package org.apache.maven.nmvn.features;

import java.io.File;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.FieldTransform;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.attribute.EnclosingMethodAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Build-time pre-processor for prebuilt plugin realm jars: strips broken generic metadata that
 * would abort the native-image build.
 *
 * <p>Some ancient/odd bytecode (gson's anonymous classes, 2010-era plexus/aether) carries
 * EnclosingMethod/Signature attributes whose reflective parsing throws
 * {@code InternalError: Enclosing method not found}. On JVM Maven this never matters (the queries
 * are lazy and nothing performs them), but SVM's ReflectionDataBuilder parses generic signatures
 * of every class reachable in the image heap — and treats InternalError as fatal
 * (VMError.shouldNotReachHere). Skipping registration in the Feature is NOT enough: heap-reachable
 * classes are processed regardless.
 *
 * <p>Stripping the attributes is behavior-preserving: the exact queries that would read them
 * already throw InternalError today (deterministic, bytecode-defined), on JVM and native alike —
 * no plugin can depend on them. Post-strip, the queries return raw types / null instead.
 *
 * <p>Additionally runs the LINK PROBE: for every class of every realm, an EXACT REPLICA of the
 * baked realm (same classworlds ClassRealm, null base loader, system loader as strategy parent —
 * the tool runs with the image classpath on -cp) attempts the very JVMCI
 * {@code ResolvedJavaType.link()} that SVM performs on registered classes. Classes that fail are
 * written to the unlinkable list, which PrebuiltPluginRealms reads (-Dnmvn.prebuilt.unlinkable)
 * to drop them from the baked map. The probe runs HERE, in a throwaway JVM, because touching
 * JVMCI from image-baked code makes the JVMCIRuntime singleton heap-reachable, which SVM rejects
 * ("JVMCIRuntime should not appear in the image").
 *
 * <p>Usage: {@code java -XX:+EnableJVMCI --add-exports... nmvn.SanitizeRealmJars <spec-file>
 * <out-dir> <unlinkable-out> [spec-out]} — reads a newline-separated
 * {@code g:a:v=jar{pathSep}jar...} realm spec (entry separator is newline, NOT {@code ';'},
 * because on Windows {@link File#pathSeparator} is {@code ';'} and would split jar paths
 * mid-entry). Rewrites only the jars containing broken classes (into out-dir, sources never
 * touched). Writes the rewritten spec to {@code spec-out} if given, otherwise stdout (logs go to
 * stderr), and writes {@code g:a:v<TAB>className} lines for link-probe failures.
 */
public final class SanitizeRealmJars {

    /**
     * Separates one plugin realm entry from the next. Must not be {@link File#pathSeparatorChar}
     * (on Windows that is {@code ';'}, which also joins jar paths).
     */
    static final String ENTRY_SEPARATOR = "\n";

    public static void main(String[] args) throws Exception {
        Path specIn = Path.of(args[0]);
        String spec = Files.readString(specIn).trim();
        Path outDir = Path.of(args[1]);
        Path unlinkableOut = Path.of(args[2]);
        Path specOut = args.length > 3 ? Path.of(args[3]) : null;
        Files.createDirectories(outDir);
        List<String> outEntries = new ArrayList<>();
        List<String> unlinkable = new ArrayList<>();
        Object[] jvmci = initJvmciLinkProbe();
        int outIndex = 0;
        int realmIndex = 0;
        org.codehaus.plexus.classworlds.ClassWorld world = new org.codehaus.plexus.classworlds.ClassWorld();
        for (String entry : splitEntries(spec)) {
            int eq = entry.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("Malformed prebuilt realm entry (missing '='): ["
                        + escape(entry)
                        + "] (len="
                        + entry.length()
                        + "). Each line must be g:a:v=jar"
                        + File.pathSeparator
                        + "jar... ; jar lists use pathSeparator='"
                        + File.pathSeparator
                        + "'. If you only see g:a:v, the line ended right where '=' should be: either the"
                        + " spec line was truncated when written, or a line terminator (CR from a Windows"
                        + " pipeline) sits inside the coordinates and split the entry here.");
            }
            // trim(): a CR that leaked into a coordinate (Windows python stdout -> `read -r`) would
            // otherwise become part of the realm key and never match at runtime.
            String gav = entry.substring(0, eq).trim();
            String[] jars = entry.substring(eq + 1).split(File.pathSeparator);
            List<String> jarList = new ArrayList<>();
            for (String j : jars) {
                if (!j.isBlank()) {
                    jarList.add(j.trim());
                }
            }
            if (jarList.isEmpty()) {
                throw new IllegalArgumentException("Malformed prebuilt realm entry (no jars): " + entry);
            }
            URL[] urls = new URL[jarList.size()];
            for (int i = 0; i < jarList.size(); i++) {
                urls[i] = Path.of(jarList.get(i)).toUri().toURL();
            }
            List<String> realmJars = new ArrayList<>();
            // Isolated probe loader (platform parent — NOT the app loader, whose classpath holds
            // Maven's own copies of gson/guava and would recreate exactly the parent-first
            // version-mixing this pipeline exists to avoid): a broken attribute is only real if
            // it fails against the jar's own consistent classes.
            try (URLClassLoader probeLoader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
                for (String jarPath : jarList) {
                    Set<String> broken = brokenEntries(jarPath, probeLoader);
                    if (broken.isEmpty()) {
                        realmJars.add(jarPath);
                    } else {
                        Path out = outDir.resolve(
                                outIndex++ + "-" + Path.of(jarPath).getFileName());
                        rewrite(Path.of(jarPath), out, broken);
                        System.err.println("sanitize: " + gav + " / "
                                + Path.of(jarPath).getFileName() + ": stripped EnclosingMethod/Signature from "
                                + broken.size() + " classes " + broken);
                        realmJars.add(out.toString());
                    }
                }
            }
            unlinkable.addAll(linkProbe(world, "probe#" + realmIndex++ + ">" + gav, gav, realmJars, jvmci));
            outEntries.add(gav + "=" + String.join(File.pathSeparator, realmJars));
        }
        Files.write(unlinkableOut, unlinkable);
        System.err.println(
                "sanitize: link probe wrote " + unlinkable.size() + " unlinkable classes to " + unlinkableOut);
        String rewritten = String.join(ENTRY_SEPARATOR, outEntries);
        if (!outEntries.isEmpty()) {
            rewritten = rewritten + ENTRY_SEPARATOR;
        }
        if (specOut != null) {
            Files.writeString(specOut, rewritten);
            System.err.println("sanitize: wrote rewritten spec (" + outEntries.size() + " realms) to " + specOut);
        } else {
            System.out.print(rewritten);
        }
    }

    /** Render control characters visibly, so a CR/LF inside a spec entry is readable in the error. */
    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < ' ') {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Split realm entries on newlines; also accept legacy ';' only when pathSeparator is not ';'. */
    static List<String> splitEntries(String spec) {
        List<String> entries = new ArrayList<>();
        // LF only, NOT \R: the spec is always written with LF endings, so any CR present is data
        // corruption (a Windows pipeline leaking \r into a coordinate) — and \R would split the
        // entry there, reporting a bare "g:a:v" that looks truncated instead. Trimming each line
        // absorbs a CR from CRLF endings and from such a leak. Skip blanks.
        for (String line : spec.split("\n", -1)) {
            String t = line.trim();
            if (!t.isEmpty()) {
                entries.add(t);
            }
        }
        if (!entries.isEmpty()) {
            return entries;
        }
        // Empty after line split: fall back to legacy ';' on Unix only.
        if (File.pathSeparatorChar != ';') {
            for (String part : spec.split(";")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    entries.add(t);
                }
            }
        }
        return entries;
    }

    /** {metaAccess, lookupJavaType, link} or null when JVMCI is unavailable (probe degrades, loudly). */
    private static Object[] initJvmciLinkProbe() {
        try {
            Object runtime = Class.forName("jdk.vm.ci.runtime.JVMCI")
                    .getMethod("getRuntime")
                    .invoke(null);
            Object backend = Class.forName("jdk.vm.ci.runtime.JVMCIRuntime")
                    .getMethod("getHostJVMCIBackend")
                    .invoke(runtime);
            Object metaAccess = Class.forName("jdk.vm.ci.runtime.JVMCIBackend")
                    .getMethod("getMetaAccess")
                    .invoke(backend);
            java.lang.reflect.Method lookup =
                    Class.forName("jdk.vm.ci.meta.MetaAccessProvider").getMethod("lookupJavaType", Class.class);
            java.lang.reflect.Method link =
                    Class.forName("jdk.vm.ci.meta.ResolvedJavaType").getMethod("link");
            return new Object[] {metaAccess, lookup, link};
        } catch (Throwable t) {
            System.err.println("sanitize: WARNING — JVMCI link probe unavailable (" + t
                    + "); unlinkable classes will only surface as image build failures");
            return null;
        }
    }

    /** Replica realm over the (sanitized) jars; returns "gav\tclassName" for classes failing link(). */
    private static List<String> linkProbe(
            org.codehaus.plexus.classworlds.ClassWorld world,
            String realmId,
            String gav,
            List<String> jars,
            Object[] jvmci)
            throws Exception {
        if (jvmci == null) {
            return List.of();
        }
        org.codehaus.plexus.classworlds.realm.ClassRealm realm = world.newRealm(realmId, null);
        realm.setParentClassLoader(ClassLoader.getSystemClassLoader());
        for (String jar : jars) {
            realm.addURL(Path.of(jar).toUri().toURL());
        }
        List<String> failures = new ArrayList<>();
        for (String jarPath : jars) {
            try (JarFile jar = new JarFile(jarPath)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.endsWith(".class")
                            || name.startsWith("META-INF/")
                            || name.endsWith("module-info.class")) {
                        continue;
                    }
                    String className =
                            name.substring(0, name.length() - ".class".length()).replace('/', '.');
                    Class<?> c;
                    try {
                        c = Class.forName(className, false, realm);
                    } catch (Throwable t) {
                        continue; // unloadable classes are dropped by loadAllClasses' own gates
                    }
                    try {
                        Object resolvedType = ((java.lang.reflect.Method) jvmci[1]).invoke(jvmci[0], c);
                        ((java.lang.reflect.Method) jvmci[2]).invoke(resolvedType);
                    } catch (Throwable t) {
                        Throwable cause = t.getCause() != null ? t.getCause() : t;
                        failures.add(gav + "\t" + className);
                        System.err.println("sanitize: link probe: " + gav + " " + className + " -> " + cause);
                    }
                }
            }
        }
        return failures;
    }

    /** Entry names (x/y/Z.class) within the jar whose generic metadata throws InternalError. */
    private static Set<String> brokenEntries(String jarPath, ClassLoader probeLoader) throws Exception {
        Set<String> broken = new LinkedHashSet<>();
        try (JarFile jar = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.endsWith("module-info.class")) {
                    continue;
                }
                String className =
                        name.substring(0, name.length() - ".class".length()).replace('/', '.');
                try {
                    probeGenericSignatures(Class.forName(className, false, probeLoader));
                } catch (Throwable t) {
                    if (hasInternalError(t)) {
                        broken.add(name);
                    }
                    // anything else (LinkageError, TypeNotPresentException, ...) is tolerated by SVM
                }
            }
        }
        return broken;
    }

    private static boolean hasInternalError(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof InternalError) {
                return true;
            }
        }
        return false;
    }

    /** Same queries ReflectionDataBuilder performs; throws if the class's metadata is unreadable. */
    private static void probeGenericSignatures(Class<?> c) {
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
    }

    private static void reifyTypes(Set<Type> seen, Type... types) {
        for (Type type : types) {
            if (type == null || !seen.add(type)) { // add() hashes: forces lazy bound reification
                continue;
            }
            if (type instanceof ParameterizedType p) {
                reifyTypes(seen, p.getActualTypeArguments());
                reifyTypes(seen, p.getRawType(), p.getOwnerType());
            } else if (type instanceof WildcardType w) {
                reifyTypes(seen, w.getUpperBounds());
                reifyTypes(seen, w.getLowerBounds());
            } else if (type instanceof GenericArrayType a) {
                reifyTypes(seen, a.getGenericComponentType());
            } else if (type instanceof TypeVariable<?> v) {
                reifyTypes(seen, v.getBounds());
            }
        }
    }

    /** Copies the jar, stripping EnclosingMethod + all Signature attributes from broken entries. */
    private static void rewrite(Path source, Path target, Set<String> broken) throws Exception {
        ClassFile classFile = ClassFile.of();
        ClassTransform strip = ClassTransform.dropping(
                        e -> e instanceof EnclosingMethodAttribute || e instanceof SignatureAttribute)
                .andThen(ClassTransform.transformingMethods(
                        MethodTransform.dropping(e -> e instanceof SignatureAttribute)))
                .andThen(ClassTransform.transformingFields(
                        FieldTransform.dropping(e -> e instanceof SignatureAttribute)));
        try (JarFile jar = new JarFile(source.toFile());
                JarOutputStream out = new JarOutputStream(Files.newOutputStream(target))) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.matches("META-INF/[^/]+\\.(SF|DSA|RSA|EC)")) {
                    continue; // modified classes would fail signature verification
                }
                out.putNextEntry(new JarEntry(name));
                byte[] bytes = jar.getInputStream(entry).readAllBytes();
                if (broken.contains(name)) {
                    bytes = classFile.transformClass(classFile.parse(bytes), strip);
                }
                out.write(bytes);
                out.closeEntry();
            }
        }
    }

    private SanitizeRealmJars() {}
}
