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
package org.apache.maven.di.impl;

import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.maven.api.annotations.Nullable;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.PreDestroy;
import org.apache.maven.api.di.Priority;
import org.apache.maven.api.di.Provider;
import org.apache.maven.api.di.Provides;
import org.apache.maven.api.di.Qualifier;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.api.di.Typed;
import org.apache.maven.di.Injector;
import org.apache.maven.di.Key;
import org.junit.jupiter.api.Test;

import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unused")
public class InjectorImplTest {

    @Test
    void markerQualifierTest() {
        Injector injector = Injector.create().bindImplicit(QualifierTest.class);
        QualifierTest.MyMojo mojo = injector.getInstance(QualifierTest.MyMojo.class);
        assertNotNull(mojo);
        assertInstanceOf(QualifierTest.MyQualifiedServiceImpl.class, mojo.service);
    }

    static class QualifierTest {
        @Qualifier
        @Retention(RUNTIME)
        @interface MyQualifier {}

        interface MyService {}

        @Named
        @Priority(10)
        static class MyNamedServiceImpl implements MyService {}

        @MyQualifier
        static class MyQualifiedServiceImpl implements MyService {}

        @Named
        static class MyMojo {
            @Inject
            @MyQualifier
            MyService service;
        }
    }

    @Test
    void priorityTest() {
        Injector injector = Injector.create().bindImplicit(PriorityTest.class);
        PriorityTest.MyMojo mojo = injector.getInstance(PriorityTest.MyMojo.class);
        assertNotNull(mojo);
        assertInstanceOf(PriorityTest.MyPriorityServiceImpl.class, mojo.service);
    }

    static class PriorityTest {

        interface MyService {}

        @Named
        static class MyServiceImpl implements MyService {}

        @Named
        @Priority(10)
        static class MyPriorityServiceImpl implements MyService {}

        @Named
        static class MyMojo {
            @Inject
            MyService service;
        }
    }

    @Test
    void mojoTest() {
        Injector injector = Injector.create().bindImplicit(MojoTest.class);
        MojoTest.MyMojo mojo = injector.getInstance(MojoTest.MyMojo.class);
        assertNotNull(mojo);
    }

    @SuppressWarnings("unused")
    static class MojoTest {
        @Qualifier
        @Retention(RUNTIME)
        @interface Mojo {}

        interface MyService {}

        @Named
        static class MyServiceImpl implements MyService {}

        @Mojo
        static class MyMojo {
            @Inject
            MyService service;
        }
    }

    @Test
    void typedTest() {
        Injector injector =
                Injector.create().bindImplicit(TypedTest.MyServiceImpl.class).bindImplicit(TypedTest.MyMojo.class);
        TypedTest.MyMojo mojo = injector.getInstance(TypedTest.MyMojo.class);
        assertNotNull(mojo);
        assertNotNull(mojo.service);
    }

    @SuppressWarnings("unused")
    static class TypedTest {

        interface MyService {}

        @Named
        @Typed
        static class MyServiceImpl implements MyService {}

        @Named
        static class MyMojo {
            @Inject
            MyService service;
        }
    }

    @Test
    public void bindInterfacesTest() {
        Injector injector = Injector.create().bindImplicit(BindInterfaces.class);
        BindInterfaces.TestInterface<String> inst =
                injector.getInstance(new Key<BindInterfaces.TestInterface<String>>() {});
        assertNotNull(inst);
    }

    static class BindInterfaces {

        interface TestInterface<T> {
            T getObj();
        }

        @Named
        static class ClassImpl implements TestInterface<String> {
            @Override
            public String getObj() {
                return null;
            }
        }

        @Named
        @Typed
        static class TypedClassImpl implements TestInterface<String> {
            @Override
            public String getObj() {
                return null;
            }
        }
    }

    @Test
    void injectListTest() {
        Injector injector = Injector.create().bindImplicit(InjectList.class);
        List<InjectList.MyService> services = injector.getInstance(new Key<List<InjectList.MyService>>() {});
        assertNotNull(services);
        assertEquals(2, services.size());

        assertNotNull(services.get(0));
        assertInstanceOf(InjectList.MyService.class, services.get(0));
        assertNotNull(services.get(1));
        assertInstanceOf(InjectList.MyService.class, services.get(1));
        assertNotSame(services.get(0).getClass(), services.get(1).getClass());
    }

    @Test
    void injectListWithPriorityTest() {
        Injector injector = Injector.create().bindImplicit(InjectListWithPriority.class);
        List<InjectListWithPriority.MyService> services =
                injector.getInstance(new Key<List<InjectListWithPriority.MyService>>() {});
        assertNotNull(services);
        assertEquals(3, services.size());

        // Verify services are ordered by priority (highest first)
        assertInstanceOf(InjectListWithPriority.HighPriorityServiceImpl.class, services.get(0));
        assertInstanceOf(InjectListWithPriority.MediumPriorityServiceImpl.class, services.get(1));
        assertInstanceOf(InjectListWithPriority.LowPriorityServiceImpl.class, services.get(2));
    }

    static class InjectList {

        interface MyService {}

        @Named("foo")
        static class MyServiceImpl implements MyService {}

        @Named("bar")
        static class AnotherServiceImpl implements MyService {}
    }

    static class InjectListWithPriority {

        interface MyService {}

        @Named
        @Priority(100)
        static class HighPriorityServiceImpl implements MyService {}

        @Named
        @Priority(50)
        static class MediumPriorityServiceImpl implements MyService {}

        @Named
        @Priority(10)
        static class LowPriorityServiceImpl implements MyService {}
    }

    @Test
    void injectMapTest() {
        Injector injector = Injector.create().bindImplicit(InjectMap.class);
        Map<String, InjectMap.MyService> services =
                injector.getInstance(new Key<Map<String, InjectMap.MyService>>() {});
        assertNotNull(services);
        assertEquals(2, services.size());

        List<Map.Entry<String, InjectMap.MyService>> entries = new ArrayList<>(services.entrySet());
        assertNotNull(entries.get(0));
        assertInstanceOf(InjectMap.MyService.class, entries.get(0).getValue());
        assertInstanceOf(String.class, entries.get(0).getKey());
        assertNotNull(entries.get(1));
        assertInstanceOf(String.class, entries.get(1).getKey());
        assertInstanceOf(InjectMap.MyService.class, entries.get(1).getValue());
        assertNotEquals(entries.get(0).getKey(), entries.get(1).getKey());
        assertNotSame(
                entries.get(0).getValue().getClass(), entries.get(1).getValue().getClass());

        InjectMap.MyMojo mojo = injector.getInstance(InjectMap.MyMojo.class);
        assertNotNull(mojo);
        assertNotNull(mojo.services);
        assertEquals(2, mojo.services.size());
    }

    static class InjectMap {

        interface MyService {}

        @Named("foo")
        static class MyServiceImpl implements MyService {}

        @Named("bar")
        static class AnotherServiceImpl implements MyService {}

        @Named
        static class MyMojo {
            @Inject
            Map<String, MyService> services;
        }
    }

    @Test
    void testSingleton() {
        Injector injector = Injector.create()
                .bindImplicit(SingletonContainer.Bean1.class)
                .bindImplicit(SingletonContainer.Bean2.class);

        SingletonContainer.Bean1 b1a = injector.getInstance(SingletonContainer.Bean1.class);
        assertNotNull(b1a);
        SingletonContainer.Bean1 b1b = injector.getInstance(SingletonContainer.Bean1.class);
        assertNotNull(b1b);
        assertEquals(b1a.num, b1b.num);

        SingletonContainer.Bean2 b2a = injector.getInstance(SingletonContainer.Bean2.class);
        assertNotNull(b2a);
        SingletonContainer.Bean2 b2b = injector.getInstance(SingletonContainer.Bean2.class);
        assertNotNull(b2b);
        assertNotEquals(b2a.num, b2b.num);
    }

    static class SingletonContainer {
        private static final AtomicInteger BEAN_1 = new AtomicInteger();
        private static final AtomicInteger BEAN_2 = new AtomicInteger();

        @Named
        @Singleton
        static class Bean1 {
            int num = BEAN_1.incrementAndGet();
        }

        @Named
        static class Bean2 {
            int num = BEAN_2.incrementAndGet();
        }
    }

    @Test
    void testProvides() {
        Injector injector = Injector.create().bindImplicit(ProvidesContainer.class);

        assertNotNull(injector.getInstance(String.class));
    }

    static class ProvidesContainer {

        @Provides
        static ArrayList<String> newStringList() {
            return new ArrayList<>(Arrays.asList("foo", "bar"));
        }

        @Provides
        static String newStringOfList(List<String> list) {
            return list.toString();
        }
    }

    @Test
    void testInjectConstructor() {
        Injector injector = Injector.create().bindImplicit(InjectConstructorContainer.class);

        assertNotNull(injector.getInstance(InjectConstructorContainer.Bean.class));
    }

    static class InjectConstructorContainer {
        @Named
        static class Bean {
            @Inject
            Bean(Another another, Third third) {}

            Bean() {}
        }

        @Named
        static class Another {}

        @Named
        static class Third {}
    }

    @Test
    void testNullableOnField() {
        Injector injector = Injector.create().bindImplicit(NullableOnField.class);
        NullableOnField.MyMojo mojo = injector.getInstance(NullableOnField.MyMojo.class);
        assertNotNull(mojo);
        assertNull(mojo.service);
    }

    static class NullableOnField {

        @Named
        interface MyService {}

        @Named
        static class MyMojo {
            @Inject
            @Nullable
            MyService service;
        }
    }

    @Test
    void testNullableOnConstructor() {
        Injector injector = Injector.create().bindImplicit(NullableOnConstructor.class);
        NullableOnConstructor.MyMojo mojo = injector.getInstance(NullableOnConstructor.MyMojo.class);
        assertNotNull(mojo);
        assertNull(mojo.service);
    }

    static class NullableOnConstructor {

        @Named
        interface MyService {}

        @Named
        static class MyMojo {
            private final MyService service;

            @Inject
            MyMojo(@Nullable MyService service) {
                this.service = service;
            }
        }
    }

    @Test
    void testCircularPriorityDependency() {
        Injector injector = Injector.create().bindImplicit(CircularPriorityTest.class);

        DIException exception = assertThrows(DIException.class, () -> {
            injector.getInstance(CircularPriorityTest.MyService.class);
        });
        assertInstanceOf(DIException.class, exception, "Expected exception to be DIException");
        assertTrue(
                exception.getMessage().contains("HighPriorityServiceImpl"),
                "Expected exception message to contain 'HighPriorityServiceImpl' but was: " + exception.getMessage());

        assertInstanceOf(DIException.class, exception.getCause(), "Expected cause to be DIException");
        assertTrue(
                exception.getCause().getMessage().contains("Cyclic dependency detected"),
                "Expected cause message to contain 'Cyclic dependency detected' but was: "
                        + exception.getCause().getMessage());
        assertTrue(
                exception.getCause().getMessage().contains("MyService"),
                "Expected cause message to contain 'MyService' but was: "
                        + exception.getCause().getMessage());
    }

    @Test
    void testListInjectionWithMixedPriorities() {
        Injector injector = Injector.create().bindImplicit(MixedPriorityTest.class);
        List<MixedPriorityTest.MyService> services =
                injector.getInstance(new Key<List<MixedPriorityTest.MyService>>() {});
        assertNotNull(services);
        assertEquals(4, services.size());

        // Verify services are ordered by priority (highest first)
        // Priority 200 (highest)
        assertInstanceOf(MixedPriorityTest.VeryHighPriorityServiceImpl.class, services.get(0));
        // Priority 100
        assertInstanceOf(MixedPriorityTest.HighPriorityServiceImpl.class, services.get(1));
        // Priority 50
        assertInstanceOf(MixedPriorityTest.MediumPriorityServiceImpl.class, services.get(2));
        // No priority annotation (default 0)
        assertInstanceOf(MixedPriorityTest.DefaultPriorityServiceImpl.class, services.get(3));
    }

    static class CircularPriorityTest {
        interface MyService {}

        @Named
        static class DefaultServiceImpl implements MyService {}

        @Named
        @Priority(10)
        static class HighPriorityServiceImpl implements MyService {
            @Inject
            MyService defaultService; // This tries to inject the default implementation
        }
    }

    static class MixedPriorityTest {

        interface MyService {}

        @Named
        @Priority(200)
        static class VeryHighPriorityServiceImpl implements MyService {}

        @Named
        @Priority(100)
        static class HighPriorityServiceImpl implements MyService {}

        @Named
        @Priority(50)
        static class MediumPriorityServiceImpl implements MyService {}

        @Named
        static class DefaultPriorityServiceImpl implements MyService {}
    }

    @Test
    void testDisposeClearsBindingsAndCache() {
        final Injector injector = Injector.create()
                // bind two simple beans
                .bindImplicit(DisposeTest.Foo.class)
                .bindImplicit(DisposeTest.Bar.class);

        // make sure they really get created
        assertNotNull(injector.getInstance(DisposeTest.Foo.class));
        assertNotNull(injector.getInstance(DisposeTest.Bar.class));

        // now dispose
        injector.dispose();

        // after dispose, bindings should be gone => DIException on lookup
        assertThrows(DIException.class, () -> injector.getInstance(DisposeTest.Foo.class));
        assertThrows(DIException.class, () -> injector.getInstance(DisposeTest.Bar.class));
    }

    /**
     * Simple test classes for dispose().
     */
    static class DisposeTest {
        @Named
        static class Foo {}

        @Named
        static class Bar {}
    }

    @Test
    void testProviderInjection() {
        Injector injector = Injector.create().bindImplicit(ProviderInjectionTest.class);
        ProviderInjectionTest.MyMojo mojo = injector.getInstance(ProviderInjectionTest.MyMojo.class);
        assertNotNull(mojo);
        assertNotNull(mojo.serviceProvider);
        // Provider should return an instance when get() is called
        ProviderInjectionTest.MyService service = mojo.serviceProvider.get();
        assertNotNull(service);
        assertInstanceOf(ProviderInjectionTest.MyServiceImpl.class, service);
    }

    @Test
    void testProviderIsLazy() {
        ProviderLazyTest.COUNTER.set(0);
        Injector injector = Injector.create().bindImplicit(ProviderLazyTest.class);
        ProviderLazyTest.MyMojo mojo = injector.getInstance(ProviderLazyTest.MyMojo.class);
        // Service should NOT have been created yet
        assertEquals(0, ProviderLazyTest.COUNTER.get());
        // Now call get() — service is created
        assertNotNull(mojo.serviceProvider.get());
        assertEquals(1, ProviderLazyTest.COUNTER.get());
    }

    static class ProviderInjectionTest {

        interface MyService {}

        @Named
        static class MyServiceImpl implements MyService {}

        @Named
        static class MyMojo {
            @Inject
            Provider<MyService> serviceProvider;
        }
    }

    static class ProviderLazyTest {
        static final AtomicInteger COUNTER = new AtomicInteger();

        @Named
        static class MyService {
            MyService() {
                COUNTER.incrementAndGet();
            }
        }

        @Named
        static class MyMojo {
            @Inject
            Provider<MyService> serviceProvider;
        }
    }

    @Test
    void testPreDestroyCalledOnDispose() {
        Injector injector = Injector.create().bindImplicit(PreDestroyTest.MyService.class);
        PreDestroyTest.MyService service = injector.getInstance(PreDestroyTest.MyService.class);
        assertNotNull(service);
        assertFalse(PreDestroyTest.MyService.destroyed);
        injector.dispose();
        assertTrue(PreDestroyTest.MyService.destroyed);
    }

    @Test
    void testPreDestroyOnNonSingletonThrows() {
        assertThrows(DIException.class, () -> {
            Injector.create().bindImplicit(PreDestroyNonSingletonTest.MyService.class);
        });
    }

    static class PreDestroyTest {
        @Named
        @Singleton
        static class MyService {
            static boolean destroyed = false;

            @PreDestroy
            void cleanup() {
                destroyed = true;
            }
        }
    }

    static class PreDestroyNonSingletonTest {
        @Named
        static class MyService {
            @PreDestroy
            void cleanup() {}
        }
    }
}
