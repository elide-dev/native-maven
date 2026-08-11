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

import java.io.IOException;
import java.util.Arrays;

import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

/**
 * Proxy mojo to execute a goal in a completely different context.
 */
@Mojo(name = "nmvn")
public final class NmvnMojo extends AbstractMojo {
    private final NmvnOther other;
    private final String[] args;

    public NmvnMojo(NmvnOther other, String[] args) {
        this.other = other;
        this.args = args;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        System.err.println("  - running " + Arrays.toString(args) + " now");
        try {
            other.getOther().run(args, null, null, null, true);
        } catch (IOException ex) {
            ex.printStackTrace();
        } catch (Error err) {
            err.printStackTrace();
        }
        System.err.println("   - execution over " + Arrays.toString(args));
    }
}
