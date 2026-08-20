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

/**
 * The launcher's {@code --mode} flag (NATIVEMVN.md "Modes"): what happens when a goal's plugin is
 * NOT baked into the image. This is the ONE knob for that decision — it replaces the old boolean
 * {@code -Dnmvn.jvm.fallback}, which could not express {@link #LEGACY}.
 *
 * <p>Two of the three answers (fail fast, per-goal HotSpot delegation) are applied at the
 * mojo-execution seam ({@link JvmFallbackBuildPluginManager}); {@link #LEGACY} never gets there —
 * the launcher short-circuits it before the baked world boots.
 *
 * <p>Transport is the {@code nmvn.mode} system property: {@code nmvn.launcher.NmvnLauncher} sets
 * it from the parsed {@code --mode} flag (a plain {@code -Dnmvn.mode=...} works too, the flag
 * wins); absent both, the baked default {@link PrebuiltPluginRealms#MODE_DEFAULT} applies.
 *
 * <p>{@code --mode} is a NON-CREMA feature. A crema image
 * ({@link PrebuiltPluginRealms#RUNTIME_CLASS_LOADING}) has one behavior only — baked plugins from
 * their realms, everything else natively via runtime class loading; crema IS the JVM — so its
 * launcher rejects the flag and the execution seam ignores the mode entirely.
 */
public enum NmvnMode {
    /**
     * Baked plugins only: a non-baked plugin fails the build with follow-up suggestions instead
     * of dying on a raw ClassNotFoundException.
     */
    NATIVE,

    /** Baked plugins natively, every other goal one by one on the in-process HotSpot JVM. */
    MIXED,

    /**
     * The whole build in one batch on the in-process HotSpot JVM — plain Apache Maven, no native
     * involvement, useful as a speed baseline for the other modes.
     */
    LEGACY;

    /** System property carrying the effective mode. */
    public static final String PROPERTY = "nmvn.mode";

    /** The effective mode: {@code -Dnmvn.mode} (the launcher mirrors {@code --mode} into it) or the baked default. */
    public static NmvnMode current() {
        return parse(System.getProperty(PROPERTY, PrebuiltPluginRealms.MODE_DEFAULT));
    }

    /** @throws IllegalArgumentException naming the supported values, suitable for user-facing output */
    public static NmvnMode parse(String value) {
        for (NmvnMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "unknown Native Maven mode '" + value + "' — supported: --mode=native, --mode=mixed, --mode=legacy");
    }

    /**
     * Mode only steers behavior inside the native image; on plain-JVM runs (tests, dev) every
     * plugin loads dynamically and the mode machinery must stay inert.
     */
    public static boolean imageRuntime() {
        return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
    }
}
