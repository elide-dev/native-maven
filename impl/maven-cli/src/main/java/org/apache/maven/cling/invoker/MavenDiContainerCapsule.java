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
package org.apache.maven.cling.invoker;

import java.util.Optional;

import org.apache.maven.api.services.Lookup;
import org.apache.maven.di.Injector;
import org.apache.maven.internal.impl.InjectorLookup;

import static java.util.Objects.requireNonNull;

/**
 * Container capsule backed by Maven DI {@link Injector}.
 */
public class MavenDiContainerCapsule implements ContainerCapsule {
    private final ClassLoader previousClassLoader;
    private final ClassLoader containerClassLoader;
    private final Injector injector;
    private final Lookup lookup;

    public MavenDiContainerCapsule(
            ClassLoader previousClassLoader, ClassLoader containerClassLoader, Injector injector) {
        this.previousClassLoader = requireNonNull(previousClassLoader, "previousClassLoader");
        this.containerClassLoader = requireNonNull(containerClassLoader, "containerClassLoader");
        this.injector = requireNonNull(injector, "injector");
        this.lookup = new InjectorLookup(injector);
    }

    @Override
    public void updateLogging(LookupContext context) {
        // no-op: logging is configured via SLF4J, no Plexus LoggerManager to update
    }

    @Override
    public Lookup getLookup() {
        return lookup;
    }

    @Override
    public Optional<ClassLoader> currentThreadClassLoader() {
        return Optional.of(containerClassLoader);
    }

    @Override
    public void close() {
        try {
            injector.dispose();
        } finally {
            Thread.currentThread().setContextClassLoader(previousClassLoader);
        }
    }
}
