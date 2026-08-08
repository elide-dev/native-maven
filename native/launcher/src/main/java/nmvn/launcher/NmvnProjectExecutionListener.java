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
package nmvn.launcher;

import javax.inject.Named;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.apache.maven.api.cli.Invoker;
import org.apache.maven.cling.ClingSupport;
import org.apache.maven.cling.MavenCling;
import org.apache.maven.cling.invoker.ProtoLookup;
import org.apache.maven.cling.invoker.mvn.MavenContext;
import org.apache.maven.cling.invoker.mvn.MavenInvoker;
import org.apache.maven.execution.ProjectExecutionEvent;
import org.apache.maven.execution.ProjectExecutionListener;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.jline.terminal.TerminalBuilder;

@Named
public class NmvnProjectExecutionListener implements ProjectExecutionListener {
    private ClingSupport other;
    private static final Set<String> replaced = new HashSet<>();

    @Override
    public void beforeProjectExecution(ProjectExecutionEvent event) throws LifecycleExecutionException {}

    @Override
    public void beforeProjectLifecycleExecution(ProjectExecutionEvent event) throws LifecycleExecutionException {
        for (var it = event.getExecutionPlan().iterator(); it.hasNext(); ) {
            var mojo = it.next();
            var mojoId = mojo.getArtifactId().replace('-', '_').toUpperCase();
            System.err.println("found " + mojoId);
            if (!replaced.contains(mojoId) && "remove".equals(System.getenv("MOJO_" + mojoId))) {
                replaced.add(mojoId);
                it.remove();
                System.err.println("  - removed");
                var args = new String[] {mojo.getMojoDescriptor().getFullGoalName()};
                System.err.println("  - running " + args[0] + " now");
                try {
                    getOther().run(args, null, null, null, true);
                } catch (IOException ex) {
                    ex.printStackTrace();
                } catch (Error err) {
                    err.printStackTrace();
                }
                System.err.println("   - execution over " + mojoId);
            }
        }
    }

    @Override
    public void afterProjectExecutionSuccess(ProjectExecutionEvent event) throws LifecycleExecutionException {}

    @Override
    public void afterProjectExecutionFailure(ProjectExecutionEvent event) {}

    /**
     * @return the other
     */
    public synchronized ClingSupport getOther() {
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
