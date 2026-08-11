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
package org.apache.maven.nativeplugin;

import org.apache.maven.api.cli.Invoker;
import org.apache.maven.cling.ClingSupport;
import org.apache.maven.cling.MavenCling;
import org.apache.maven.cling.invoker.ProtoLookup;
import org.apache.maven.cling.invoker.mvn.MavenContext;
import org.apache.maven.cling.invoker.mvn.MavenInvoker;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.jline.terminal.TerminalBuilder;

public final class NmvnOther {
    private ClingSupport other;

    synchronized ClingSupport getOther() {
        if (other == null) {
            other = new MavenCling() {
                @Override
                protected Invoker createInvoker() {
                    return new MavenInvoker(
                            ProtoLookup.builder()
                                    .addMapping(ClassWorld.class, classWorld)
                                    .build(),
                            null) {
                        @Override
                        protected void doCreateTerminal(MavenContext context, TerminalBuilder builder) {}

                        @Override
                        protected int doInvoke(MavenContext context) throws Exception {
                            validate(context);
                            pushCoreProperties(context);
                            pushUserProperties(context);
                            setupGuiceClassLoading(context);
                            configureLogging(context);
                            // createTerminal(context);
                            context.terminal = TerminalBuilder.terminal();
                            activateLogging(context);
                            helpOrVersionAndMayExit(context);
                            preCommands(context);
                            container(context);
                            postContainer(context);
                            pushUserProperties(context); // after PropertyContributor SPI
                            lookup(context);
                            init(context);
                            postCommands(context);
                            settings(context);
                            return execute(context);
                        }
                    };
                }
            };
        }
        return other;
    }
}
