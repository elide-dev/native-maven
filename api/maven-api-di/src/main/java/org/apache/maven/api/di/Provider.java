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
package org.apache.maven.api.di;

/**
 * Provides lazy access to a dependency injection binding.
 * <p>
 * Inject {@code Provider<T>} instead of {@code T} directly when you want to:
 * <ul>
 *   <li>Defer creation of an expensive dependency until it's actually needed</li>
 *   <li>Obtain multiple instances (for non-singleton scoped bindings)</li>
 *   <li>Break circular dependency chains</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>
 * public class MyService {
 *     private final Provider&lt;HeavyDependency&gt; heavy;
 *
 *     {@literal @}Inject
 *     public MyService(Provider&lt;HeavyDependency&gt; heavy) {
 *         this.heavy = heavy; // not created yet
 *     }
 *
 *     public void doWork() {
 *         heavy.get(); // created on first call
 *     }
 * }
 * </pre>
 *
 * @param <T> the type of the provided instance
 * @since 4.0.0
 */
@FunctionalInterface
public interface Provider<T> {

    /**
     * Provides an instance of {@code T}.
     *
     * @return the provided instance
     */
    T get();
}
