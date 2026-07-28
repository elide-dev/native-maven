package nmvn;

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
 * <out-dir> <unlinkable-out>} — reads the ';'-separated {@code g:a:v=jar:jar:...} realm spec,
 * rewrites only the jars containing broken classes (into out-dir, sources never touched), prints
 * the rewritten spec to stdout (logs go to stderr), and writes {@code g:a:v<TAB>className} lines
 * for link-probe failures.
 */
public final class SanitizeRealmJars {

    public static void main(String[] args) throws Exception {
        String spec = Files.readString(Path.of(args[0])).trim();
        Path outDir = Path.of(args[1]);
        Path unlinkableOut = Path.of(args[2]);
        Files.createDirectories(outDir);
        List<String> outEntries = new ArrayList<>();
        List<String> unlinkable = new ArrayList<>();
        Object[] jvmci = initJvmciLinkProbe();
        int outIndex = 0;
        int realmIndex = 0;
        org.codehaus.plexus.classworlds.ClassWorld world = new org.codehaus.plexus.classworlds.ClassWorld();
        for (String entry : spec.split(";")) {
            int eq = entry.indexOf('=');
            String gav = entry.substring(0, eq);
            String[] jars = entry.substring(eq + 1).split(File.pathSeparator);
            URL[] urls = new URL[jars.length];
            for (int i = 0; i < jars.length; i++) {
                urls[i] = Path.of(jars[i]).toUri().toURL();
            }
            List<String> realmJars = new ArrayList<>();
            // Isolated probe loader (platform parent — NOT the app loader, whose classpath holds
            // Maven's own copies of gson/guava and would recreate exactly the parent-first
            // version-mixing this pipeline exists to avoid): a broken attribute is only real if
            // it fails against the jar's own consistent classes.
            try (URLClassLoader probeLoader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
                for (String jarPath : jars) {
                    Set<String> broken = brokenEntries(jarPath, probeLoader);
                    if (broken.isEmpty()) {
                        realmJars.add(jarPath);
                    } else {
                        Path out = outDir.resolve(outIndex++ + "-" + Path.of(jarPath).getFileName());
                        rewrite(Path.of(jarPath), out, broken);
                        System.err.println("sanitize: " + gav + " / " + Path.of(jarPath).getFileName()
                                + ": stripped EnclosingMethod/Signature from " + broken.size() + " classes " + broken);
                        realmJars.add(out.toString());
                    }
                }
            }
            unlinkable.addAll(linkProbe(world, "probe#" + realmIndex++ + ">" + gav, gav, realmJars, jvmci));
            outEntries.add(gav + "=" + String.join(File.pathSeparator, realmJars));
        }
        Files.write(unlinkableOut, unlinkable);
        System.err.println("sanitize: link probe wrote " + unlinkable.size() + " unlinkable classes to " + unlinkableOut);
        System.out.println(String.join(";", outEntries));
    }

    /** {metaAccess, lookupJavaType, link} or null when JVMCI is unavailable (probe degrades, loudly). */
    private static Object[] initJvmciLinkProbe() {
        try {
            Object runtime = Class.forName("jdk.vm.ci.runtime.JVMCI").getMethod("getRuntime").invoke(null);
            Object backend = Class.forName("jdk.vm.ci.runtime.JVMCIRuntime")
                    .getMethod("getHostJVMCIBackend").invoke(runtime);
            Object metaAccess = Class.forName("jdk.vm.ci.runtime.JVMCIBackend")
                    .getMethod("getMetaAccess").invoke(backend);
            java.lang.reflect.Method lookup = Class.forName("jdk.vm.ci.meta.MetaAccessProvider")
                    .getMethod("lookupJavaType", Class.class);
            java.lang.reflect.Method link = Class.forName("jdk.vm.ci.meta.ResolvedJavaType").getMethod("link");
            return new Object[] {metaAccess, lookup, link};
        } catch (Throwable t) {
            System.err.println("sanitize: WARNING — JVMCI link probe unavailable (" + t
                    + "); unlinkable classes will only surface as image build failures");
            return null;
        }
    }

    /** Replica realm over the (sanitized) jars; returns "gav\tclassName" for classes failing link(). */
    private static List<String> linkProbe(org.codehaus.plexus.classworlds.ClassWorld world, String realmId,
            String gav, List<String> jars, Object[] jvmci) throws Exception {
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
                    if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.endsWith("module-info.class")) {
                        continue;
                    }
                    String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
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
                String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
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
        ClassTransform strip = ClassTransform
                .dropping(e -> e instanceof EnclosingMethodAttribute || e instanceof SignatureAttribute)
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