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

import java.util.HashSet;
import java.util.Set;

import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.execution.ProjectExecutionEvent;
import org.apache.maven.execution.ProjectExecutionListener;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.model.Plugin;
import org.apache.maven.nativeplugin.NmvnMojo;
import org.apache.maven.nativeplugin.NmvnOther;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.artifact.PluginArtifact;

@Named
public class NmvnProjectExecutionListener implements ProjectExecutionListener {
    private final NmvnOther other = new NmvnOther();
    private static final Set<String> replaced = new HashSet<>();

    @Override
    public void beforeProjectExecution(ProjectExecutionEvent event) throws LifecycleExecutionException {}

    @Override
    public void beforeProjectLifecycleExecution(ProjectExecutionEvent event) throws LifecycleExecutionException {
        for (var it = event.getExecutionPlan().listIterator(); it.hasNext(); ) {
            var mojo = it.next();
            var mojoId = mojo.getArtifactId().replace('-', '_').toUpperCase();
            System.err.println("found " + mojoId);
            if (!replaced.contains(mojoId) && "remove".equals(System.getenv("MOJO_" + mojoId))) {
                replaced.add(mojoId);
                var goal = mojo.getMojoDescriptor().getFullGoalName();
                var args = new String[] {goal};
                var nativeMojo = new NmvnMojo(other, args);
                Plugin plugin = new Plugin();
                plugin.setGroupId("org.apache.maven");
                plugin.setArtifactId("native-maven-plugin");
                plugin.setVersion("4.1.0-SNAPSHOT");
                var descriptor = new org.apache.maven.plugin.descriptor.MojoDescriptor();
                var pd = new PluginDescriptor();
                pd.setPlugin(plugin);
                pd.setPluginArtifact(new PluginArtifact(
                        plugin,
                        new DefaultArtifact(
                                plugin.getGroupId(),
                                plugin.getArtifactId(),
                                plugin.getVersion(),
                                "nmvn",
                                "nmvn",
                                "nmvn",
                                null)));
                pd.setGroupId(plugin.getGroupId());
                pd.setArtifactId(plugin.getArtifactId());
                pd.setVersion(plugin.getVersion());
                descriptor.setPluginDescriptor(pd);
                descriptor.setGoal("nmvn");
                var delegate = new MojoExecution(descriptor, mojoId);
                it.set(delegate);
                System.err.println("  - replaced");
            }
        }
    }

    @Override
    public void afterProjectExecutionSuccess(ProjectExecutionEvent event) throws LifecycleExecutionException {}

    @Override
    public void afterProjectExecutionFailure(ProjectExecutionEvent event) {}
}
