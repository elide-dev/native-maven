# [native-image] Crema/RuntimeClassLoading: GC segfaults after loading a class with mixed-width primitive static fields

## Describe the Issue

With `-H:+RuntimeClassLoading` (Crema), loading a class at run time whose static fields mix a
**sub-8-byte primitive with an 8-byte primitive** (e.g. `static int` + `static long`) corrupts GC
metadata. The next collection crashes with SIGBUS/SIGSEGV in
`GreyToBlackObjRefVisitor.visitObjectReferences` while scanning a
`com.oracle.svm.interpreter.metadata.CremaResolvedObjectType` instance: a slot that holds primitive
static data is visited as if it were an object reference, and the decoded "reference" points into
the protected zone above the heap base (`si_addr = heapBase + <raw field bits>`).

The crash is 100% deterministic and needs no reference fields, no methods, and no constructors in
the loaded class — two primitive statics of different widths are sufficient:

```java
package p;

public class ZS {
    static int SIZE = 2;
    static long serialVersionUID = 1L;
}
```

Characterization (each variant loaded via `Class.forName(..., true, urlClassLoader)` followed by
`System.gc()` in the same image):

| Static fields of the runtime-loaded class | Result |
|---|---|
| `int` + `long` (either declaration order; `final` or not) | segfault |
| `int` + `double` | segfault |
| `boolean` + `long` | segfault |
| `int` + `int` | OK |
| `long` + `long` | OK |
| any single primitive static | OK |
| reference statics only | OK |

Notes:

* `final` vs non-`final` makes no difference (i.e. `ConstantValue` attributes vs `<clinit>`
  assignment both crash).
* Class file version makes no difference (verified with major version 52 and 69).
* `System.gc()` is only used to make the reproducer instant; naturally triggered collections crash
  identically (this was originally found running Apache Maven inside a RuntimeClassLoading image,
  where the build deterministically segfaulted in `maven-jar-plugin` the moment an
  allocation-triggered GC ran after commons-compress 1.28.0's `ZipShort` — `static int SIZE` +
  `static long serialVersionUID` — was initialized).
* The faulting address is stable per class layout, e.g. `heapBase + 32` for the `int SIZE = 2` +
  `long serialVersionUID = 1L` case, consistent with primitive static data being misread as a
  compressed reference.
* Only `-H:+RuntimeClassLoading` is needed; reproduces without `-H:+GraalJITCompileAtRuntime`.

## Steps to Reproduce

1. Create the two source files:

   ```bash
   mkdir -p repro/p && cd repro

   cat > Repro.java <<'EOF'
   import java.io.File;
   import java.net.URL;
   import java.net.URLClassLoader;

   public class Repro {
       public static void main(String[] args) throws Exception {
           URLClassLoader cl = new URLClassLoader(new URL[]{new File(args[0]).toURI().toURL()});
           Class<?> c = Class.forName("p.ZS", true, cl);
           System.gc();
           System.out.println("survived: " + c);
       }
   }
   EOF

   cat > p/ZS.java <<'EOF'
   package p;

   public class ZS {
       static int SIZE = 2;
       static long serialVersionUID = 1L;
   }
   EOF
   ```

2. Compile. `Repro` goes on the image classpath; `p.ZS` is compiled into a separate directory
   `app/` that is deliberately NOT on the image classpath, so it is only seen at run time:

   ```bash
   javac Repro.java
   mkdir -p app && javac -d app p/ZS.java
   ```

3. Build the native image (only `RuntimeClassLoading` is needed):

   ```bash
   native-image -H:+UnlockExperimentalVMOptions -H:+RuntimeClassLoading --no-fallback -cp . Repro repro-native
   ```

4. Run the image, passing the directory containing `p/ZS.class` as the argument (it becomes the
   `URLClassLoader`'s classpath):

   ```bash
   ./repro-native app
   ```

Expected: prints `survived: class p.ZS` and exits 0.

Actual: segfault (exit code 134, SVM segfault handler report as below). Changing `ZS` to hold two
`int`s (or two `long`s, or a single field) makes the same image print `survived`.

## Crash Output (excerpt)

```
[ [ SegfaultHandler caught a segfault in thread 0x0000000108032300 ] ]
siginfo: si_signo: 10, si_code: 1, si_addr: 0x0000007000000020 (heapBase + 32)

General purpose register values:
  ...
  R3  0x000000700440ed18 points into aligned chunk 0x0000007004400000 (O)
    is an object of type com.oracle.svm.interpreter.metadata.CremaResolvedObjectType
  R4  0x000000700440ed18 points into aligned chunk 0x0000007004400000 (O)
    is an object of type com.oracle.svm.interpreter.metadata.CremaResolvedObjectType
  ...

Stacktrace for the failing thread 0x0000000108032300 (A=AOT compiled, J=JIT compiled, D=deoptimized, i=inlined, C=native):
  A  SP 0x000000016b7ad8d0 IP 0x00000001046f59f0 size=288   com.oracle.svm.core.genscavenge.GreyToBlackObjRefVisitor.visitObjectReferences(GreyToBlackObjRefVisitor.java)
  i  SP 0x000000016b7ad9f0 IP 0x00000001046eb2fc size=512   com.oracle.svm.core.heap.InstanceReferenceMapDecoder.callVisitor(InstanceReferenceMapDecoder.java:103)
  i  SP 0x000000016b7ad9f0 IP 0x00000001046eb2fc size=512   com.oracle.svm.core.heap.InstanceReferenceMapDecoder.walkReferencesInline(InstanceReferenceMapDecoder.java:76)
  i  SP 0x000000016b7ad9f0 IP 0x00000001046eb2fc size=512   com.oracle.svm.core.hub.InteriorObjRefWalker.walkInstanceInline(InteriorObjRefWalker.java:143)
  i  SP 0x000000016b7ad9f0 IP 0x00000001046eb2fc size=512   com.oracle.svm.core.hub.InteriorObjRefWalker.walkObjectInline(InteriorObjRefWalker.java:77)
  i  SP 0x000000016b7ad9f0 IP 0x00000001046eb2fc size=512   com.oracle.svm.core.genscavenge.GreyToBlackObjectVisitor.visitObject(GreyToBlackObjectVisitor.java:56)
  A  SP 0x000000016b7ad9f0 IP 0x00000001046eb2fc size=512   com.oracle.svm.core.genscavenge.CompactingOldGeneration.scanGreyObjects(CompactingOldGeneration.java:177)
  A  SP 0x000000016b7adbf0 IP 0x00000001046f1890 size=64    com.oracle.svm.core.genscavenge.GCImpl.scanGreyObjects(GCImpl.java:1049)
  A  SP 0x000000016b7adc30 IP 0x00000001046f17f8 size=64    com.oracle.svm.core.genscavenge.GCImpl.scanFromRoots(GCImpl.java:759)
  A  SP 0x000000016b7adc70 IP 0x00000001046f16d0 size=64    com.oracle.svm.core.genscavenge.GCImpl.scan(GCImpl.java:705)
  A  SP 0x000000016b7adcb0 IP 0x00000001046ef318 size=128   com.oracle.svm.core.genscavenge.GCImpl.doCollectCore(GCImpl.java:574)
  ...
```

In larger applications the same corruption is also observed from allocation-triggered incremental
collections of the young generation
(`GCImpl.maybeCollectOnAllocation` → ... → `GreyToBlackObjRefVisitor.visitObjectReferences`),
with the interpreter (`com.oracle.svm.interpreter.Interpreter`) and
`CremaSupportImpl.prepareAndVerify` / class initialization further down the failing thread's stack.

## Environment

* GraalVM: GraalVM CE 25.3.4-dev+7.1 (build 25.0.4+7-jvmci-25.2-b20)
* native-image: `native-image 25.0.4 2026-07-21` — `Substrate VM GraalVM CE 25.3.4-dev+7.1 (build 25.0.4+7, serial gc, compressed references)`
* OS / arch: macOS (Darwin 25.5.0), aarch64
