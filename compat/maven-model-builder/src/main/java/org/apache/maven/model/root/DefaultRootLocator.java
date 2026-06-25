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
package org.apache.maven.model.root;

import javax.inject.Named;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.api.xml.XmlService;

/**
 * @deprecated use {@code org.apache.maven.api.services.model.RootLocator} instead
 */
@Named
@Deprecated(since = "4.0.0")
public class DefaultRootLocator implements RootLocator {

    @Override
    public boolean isRootDirectory(Path dir) {
        if (Files.isDirectory(dir.resolve(".mvn"))) {
            return true;
        }
        // we're too early to use the modelProcessor ...
        Path pom = dir.resolve("pom.xml");
        debugClassLoaders();
        try (InputStream is = Files.newInputStream(pom)) {
            XMLStreamReader parser = XmlService.newXMLInputFactory().createXMLStreamReader(is);
            if (parser.nextTag() == XMLStreamReader.START_ELEMENT
                    && parser.getLocalName().equals("project")) {
                for (int i = 0; i < parser.getAttributeCount(); i++) {
                    if ("root".equals(parser.getAttributeLocalName(i))) {
                        return Boolean.parseBoolean(parser.getAttributeValue(i));
                    }
                }
            }
        } catch (IOException | XMLStreamException e) {
            // The root locator can be used very early during the setup of Maven,
            // even before the arguments from the command line are parsed.  Any exception
            // that would happen here should cause the build to fail at a later stage
            // (when actually parsing the POM) and will lead to a better exception being
            // displayed to the user, so just bail out and return false.
        }
        return false;
    }

    private static void debugClassLoaders() {
        String svc = "META-INF/services/javax.xml.stream.XMLInputFactory";
        String cls = "com.ctc.wstx.stax.WstxInputFactory";
        System.err.println("==== DefaultRootLocator XMLInputFactory diagnostic ====");
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        probe("TCCL", tccl, svc, cls);
        probe("DefaultRootLocator.class.getClassLoader()", DefaultRootLocator.class.getClassLoader(), svc, cls);
        probe("XMLInputFactory.class.getClassLoader()", XMLInputFactory.class.getClassLoader(), svc, cls);
        probe("system", ClassLoader.getSystemClassLoader(), svc, cls);
        System.err.println("==== end diagnostic ====");
    }

    private static void probe(String label, ClassLoader cl, String resource, String className) {
        System.err.println("-- " + label + " = " + cl);
        ClassLoader walker = cl;
        int depth = 0;
        while (walker != null && depth < 10) {
            System.err.println("    parent[" + depth + "] = " + walker);
            walker = walker.getParent();
            depth++;
        }
        if (cl == null) {
            return;
        }
        try {
            java.net.URL u = cl.getResource(resource);
            System.err.println("    getResource(" + resource + ") = " + u);
            java.util.Enumeration<java.net.URL> en = cl.getResources(resource);
            int n = 0;
            while (en.hasMoreElements()) {
                System.err.println("    getResources[" + (n++) + "] = " + en.nextElement());
            }
            System.err.println("    getResources count = " + n);
        } catch (Throwable t) {
            System.err.println("    resource lookup threw: " + t);
        }
        try {
            Class<?> c = Class.forName(className, false, cl);
            System.err.println("    Class.forName(" + className + ") = " + c + "  module=" + c.getModule());
        } catch (Throwable t) {
            System.err.println("    Class.forName(" + className + ") threw: " + t);
        }
    }
}
