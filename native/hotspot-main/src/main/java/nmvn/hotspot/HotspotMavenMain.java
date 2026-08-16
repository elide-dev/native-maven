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
package nmvn.hotspot;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.codehaus.plexus.classworlds.launcher.Launcher;

/**
 * Entry point executed on the HOTSPOT side of the in-process JVM fallback (see
 * {@code nmvn.HotspotMavenRunner}). Runs one stock Maven invocation per {@code main} call and
 * reports the exit code through a file instead of {@code System.exit} — the HotSpot JVM shares the
 * process with the native image, so exiting here would kill the whole nmvn run.
 *
 * <p>Args: {@code args[0]} = path of the file to write the decimal exit code into,
 * {@code args[1..]} = the Maven command line. The classworlds {@link Launcher} is configured ONCE
 * (from {@code -Dclassworlds.conf}, exactly like the {@code mvn} script) and reused across calls:
 * HotSpot allows a single {@code JNI_CreateJavaVM} per process, so this class serves every
 * delegated goal of the whole build and re-loading the Maven realm per goal would only burn
 * metaspace and time.
 *
 * <p>Ships as {@code nmvn-hotspot-main.jar}, baked into the native image as a resource and
 * extracted onto the child JVM's app classpath (with {@code $MAVEN_HOME/boot}). Depends only
 * on classworlds and the JDK — Maven types load from the m2.conf realm. The jar must not also
 * sit on a realm load path ({@code lib/}): classworlds is parent-first.
 */
public final class HotspotMavenMain {

    /** Configured once, reused for every delegated goal. */
    private static Launcher launcher;

    private HotspotMavenMain() {}

    public static void main(String[] args) {
        int exitCode = 1;
        try {
            exitCode = run(Arrays.copyOfRange(args, 1, args.length));
        } catch (Throwable t) {
            t.printStackTrace();
        }
        try (OutputStream out = new FileOutputStream(args[0])) {
            out.write(Integer.toString(exitCode).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static synchronized int run(String[] mavenArgs) throws Exception {
        if (launcher == null) {
            Launcher configured = new Launcher();
            configured.setSystemClassLoader(HotspotMavenMain.class.getClassLoader());
            String conf = System.getProperty("classworlds.conf");
            try (InputStream in = new FileInputStream(conf)) {
                configured.configure(in);
            }
            launcher = configured;
        }
        // launch() invokes m2.conf's enhanced main — MavenCling.main(args, world) — which RETURNS
        // its exit code instead of calling System.exit (same seam NmvnLauncher uses natively).
        launcher.launch(mavenArgs);
        return launcher.getExitCode();
    }
}
