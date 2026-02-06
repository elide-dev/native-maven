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

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Denotes that a bean should be created as a singleton and instantiated eagerly
 * at container startup, rather than lazily on first use.
 * <p>
 * This is equivalent to {@link Singleton} but guarantees that the instance is
 * created during container initialization. Use this for beans that must register
 * lifecycle hooks (e.g., {@link PreDestroy}) or perform setup at startup.
 * <p>
 * Example usage:
 * <pre>
 * {@literal @}Named
 * {@literal @}EagerSingleton
 * public class ResolverLifecycle {
 *     {@literal @}PreDestroy
 *     public void shutdown() {
 *         // cleanup on container dispose
 *     }
 * }
 * </pre>
 *
 * @see Singleton
 * @see PreDestroy
 * @since 4.0.0
 */
@Scope
@Documented
@Retention(RUNTIME)
public @interface EagerSingleton {}
