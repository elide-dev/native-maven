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

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.api.Constants;
import org.apache.maven.api.ProtoSession;
import org.apache.maven.api.cli.Logger;
import org.apache.maven.api.di.MojoExecutionScoped;
import org.apache.maven.api.di.SessionScoped;
import org.apache.maven.api.services.Lookup;
import org.apache.maven.api.services.MessageBuilderFactory;
import org.apache.maven.di.Injector;
import org.apache.maven.di.impl.InjectorImpl;
import org.apache.maven.execution.scope.internal.MojoExecutionScope;
import org.apache.maven.extension.internal.CoreExports;
import org.apache.maven.extension.internal.CoreExtensionEntry;
import org.apache.maven.internal.impl.InjectorLookup;
import org.apache.maven.session.scope.internal.SessionScope;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.slf4j.ILoggerFactory;

import static java.util.Objects.requireNonNull;

/**
 * Container capsule factory backed by Maven DI {@link Injector}.
 * Replaces {@link PlexusContainerCapsuleFactory} — no Guice, no Sisu, no Plexus container.
 *
 * @param <C> The context type.
 */
public class MavenDiContainerCapsuleFactory<C extends LookupContext> implements ContainerCapsuleFactory<C> {

    @Override
    public ContainerCapsule createContainerCapsule(
            LookupInvoker<C> invoker, C context, CoreExtensionSelector<C> coreExtensionSelector) throws Exception {
        requireNonNull(invoker, "invoker");
        requireNonNull(context, "context");
        requireNonNull(coreExtensionSelector, "coreExtensionSelector");

        ClassWorld classWorld = requireNonNull(invoker.protoLookup.lookup(ClassWorld.class), "classWorld");
        ClassRealm coreRealm = classWorld.getClassRealm("plexus.core");
        List<Path> extClassPath = parseExtClasspath(context);
        CoreExtensionEntry coreEntry = CoreExtensionEntry.discoverFrom(coreRealm);

        ClassRealm containerRealm = setupContainerRealm(context.logger, classWorld, coreRealm, extClassPath);

        CoreExports exports =
                new CoreExports(containerRealm, coreEntry.getExportedArtifacts(), coreEntry.getExportedPackages());

        ClassLoader previousClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(containerRealm);

        // Create the injector and wire everything up
        InjectorImpl injector = new InjectorImpl();

        // Register scopes
        SessionScope sessionScope = new SessionScope();
        MojoExecutionScope mojoExecutionScope = new MojoExecutionScope();
        injector.bindScope(SessionScoped.class, sessionScope);
        injector.bindScope(MojoExecutionScoped.class, mojoExecutionScope);

        // Bind scope instances so they can be injected
        injector.bindInstance(SessionScope.class, sessionScope);
        injector.bindInstance(MojoExecutionScope.class, mojoExecutionScope);

        // Bind core instances
        injector.bindInstance(ClassWorld.class, classWorld);
        injector.bindInstance(CoreExports.class, exports);
        injector.bindInstance(ILoggerFactory.class, context.loggerFactory);
        injector.bindInstance(MessageBuilderFactory.class, context.invokerRequest.messageBuilderFactory());

        // Bind the injector and lookup
        injector.bindInstance(Injector.class, injector);
        Lookup lookup = new InjectorLookup(injector);
        injector.bindInstance(Lookup.class, lookup);

        // Discover all beans from the classpath
        injector.discover(containerRealm);

        return new MavenDiContainerCapsule(previousClassLoader, containerRealm, injector);
    }

    protected List<Path> parseExtClasspath(C context) {
        ProtoSession protoSession = context.protoSession;
        String extClassPath = protoSession.getUserProperties().get(Constants.MAVEN_EXT_CLASS_PATH);
        if (extClassPath == null) {
            extClassPath = protoSession.getSystemProperties().get(Constants.MAVEN_EXT_CLASS_PATH);
        }
        ArrayList<Path> jars = new ArrayList<>();
        if (extClassPath != null && !extClassPath.isEmpty()) {
            for (String jar : extClassPath.split(File.pathSeparator)) {
                Path file = context.cwd.resolve(jar);
                jars.add(file);
            }
        }
        return jars;
    }

    protected ClassRealm setupContainerRealm(
            Logger logger, ClassWorld classWorld, ClassRealm coreRealm, List<Path> extClassPath) throws Exception {
        if (!extClassPath.isEmpty()) {
            ClassRealm extRealm = classWorld.newRealm("maven.ext", null);
            extRealm.setParentRealm(coreRealm);
            for (Path file : extClassPath) {
                logger.debug("  included '" + file + "'");
                extRealm.addURL(file.toUri().toURL());
            }
            return extRealm;
        }
        return coreRealm;
    }
}
