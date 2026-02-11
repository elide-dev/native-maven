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
package org.apache.maven.internal.impl;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.api.services.Lookup;
import org.apache.maven.api.services.LookupException;
import org.apache.maven.di.Injector;
import org.apache.maven.di.Key;
import org.apache.maven.di.impl.DIException;
import org.apache.maven.di.impl.Types;

/**
 * {@link Lookup} implementation backed by a Maven DI {@link Injector}.
 * Replacement for {@link DefaultLookup} which wraps a {@code PlexusContainer}.
 */
public class InjectorLookup implements Lookup {

    private final Injector injector;

    public InjectorLookup(Injector injector) {
        this.injector = injector;
    }

    @Override
    public <T> T lookup(Class<T> type) {
        try {
            return injector.getInstance(type);
        } catch (DIException e) {
            throw new LookupException(e);
        }
    }

    @Override
    public <T> T lookup(Class<T> type, String name) {
        try {
            return injector.getInstance(Key.ofType(type, name));
        } catch (DIException e) {
            throw new LookupException(e);
        }
    }

    @Override
    public <T> Optional<T> lookupOptional(Class<T> type) {
        try {
            return Optional.of(injector.getInstance(type));
        } catch (DIException e) {
            return Optional.empty();
        }
    }

    @Override
    public <T> Optional<T> lookupOptional(Class<T> type, String name) {
        try {
            return Optional.of(injector.getInstance(Key.ofType(type, name)));
        } catch (DIException e) {
            return Optional.empty();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> lookupList(Class<T> type) {
        try {
            return injector.getInstance(Key.ofType(Types.parameterizedType(List.class, type)));
        } catch (DIException e) {
            throw new LookupException(e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Map<String, T> lookupMap(Class<T> type) {
        try {
            return injector.getInstance(Key.ofType(Types.parameterizedType(Map.class, String.class, type)));
        } catch (DIException e) {
            throw new LookupException(e);
        }
    }
}
