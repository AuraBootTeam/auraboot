package com.auraboot.security;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MybatisCarrierTest {
    @TempDir Path temporary;

    @Test
    void preservesEveryOriginalMybatisImplementationClassByteForByte() throws Exception {
        try (var original = new ZipFile(System.getProperty("carrier.original"));
             var patched = new ZipFile(System.getProperty("carrier.patched"))) {
            int verified = 0;
            for (var entry : original.stream().toList()) {
                String name = entry.getName();
                if (!name.endsWith(".class") || name.startsWith("org/apache/ibatis/ognl/")
                        || name.startsWith("org/apache/ibatis/javassist/")) continue;
                var replacement = patched.getEntry(name);
                assertNotNull(replacement, name);
                assertArrayEquals(original.getInputStream(entry).readAllBytes(),
                        patched.getInputStream(replacement).readAllBytes(), name);
                verified++;
            }
            assertTrue(verified > 400, "A nonempty, complete original implementation must be checked");
        }
    }

    @Test
    void containsOneRelocatedDependencyAndTruthfulVersionMetadata() throws Exception {
        try (var jar = new ZipFile(System.getProperty("carrier.patched"))) {
            Set<String> names = new HashSet<>();
            for (var entry : jar.stream().toList()) assertTrue(names.add(entry.getName()), entry.getName());
            assertNotNull(jar.getEntry("org/apache/ibatis/ognl/Ognl.class"));
            assertNotNull(jar.getEntry("org/apache/ibatis/javassist/ClassPool.class"));
            assertTrue(names.stream().noneMatch(n -> n.startsWith("ognl/") || n.startsWith("javassist/")));
            assertEquals("3.4.17", properties(jar, "META-INF/maven/ognl/ognl/pom.properties").getProperty("version"));
            assertEquals("3.33.0-GA", properties(jar, "META-INF/maven/org.javassist/javassist/pom.properties").getProperty("version"));
            assertEquals(System.getProperty("carrier.version"), properties(jar, "META-INF/maven/com.auraboot/mybatis-compat/pom.properties").getProperty("version"));
            assertNull(jar.getEntry("META-INF/maven/org.mybatis/mybatis/pom.properties"), "Do not masquerade as an official upstream artifact");
            var manifest = new java.util.jar.Manifest(jar.getInputStream(jar.getEntry("META-INF/MANIFEST.MF")));
            assertEquals("AuraBoot", manifest.getMainAttributes().getValue("Implementation-Vendor"));
            assertEquals(System.getProperty("carrier.version"), manifest.getMainAttributes().getValue("Implementation-Version"));
            assertEquals("3.5.19", manifest.getMainAttributes().getValue("MyBatis-Original-Version"));
            String provenance = new String(jar.getInputStream(jar.getEntry("META-INF/auraboot/mybatis-compat/inputs.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(provenance.contains("ognl:ognl:3.4.17"));
            assertTrue(provenance.contains("org.mybatis:mybatis:3.5.19"));
            assertNotNull(jar.getEntry("META-INF/auraboot/mybatis-compat/NOTICE.auraboot"));
            assertTrue(names.stream().anyMatch(n -> n.contains("licenses/ognl-3.4.17/")));
            assertTrue(names.stream().anyMatch(n -> n.contains("licenses/javassist-3.33.0-GA/")));
        }
    }

    @Test
    void patchedCarrierPreservesOrdinaryExpressionEvaluation() throws Exception {
        for (String property : new String[] {"carrier.original", "carrier.patched"}) {
            try (var evaluator = new IsolatedEvaluator(property)) {
                assertEquals(Boolean.TRUE, evaluator.evaluate("amount > 10 and amount < 20", Map.of("amount", 12)));
                assertEquals(13, evaluator.evaluate("amount + 1", Map.of("amount", 12)));
            }
        }
    }

    @Test
    void originalReflectionProxyReachesProcessStartButPatchedCarrierRejectsIt() throws Exception {
        Path before = temporary.resolve("original-created.txt");
        try (var evaluator = new IsolatedEvaluator("carrier.original")) {
            Object result = evaluator.evaluate(reflectionExpression(before), Map.of());
            assertInstanceOf(Process.class, result).waitFor();
            assertTrue(Files.isRegularFile(before), "The old carrier must demonstrate the real harmless side effect");
        }
        Path after = temporary.resolve("patched-must-not-exist.txt");
        try (var evaluator = new IsolatedEvaluator("carrier.patched")) {
            var failure = assertThrows(InvocationTargetException.class,
                    () -> evaluator.evaluate(reflectionExpression(after), Map.of()));
            assertNotNull(failure.getCause());
            String diagnostics = "";
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) diagnostics += cause.toString();
            assertTrue(diagnostics.contains("stricter invocation mode"), diagnostics);
            assertFalse(Files.exists(after), "A rejected expression must leave no subprocess side effect");
        }
    }

    private static String reflectionExpression(Path output) {
        String safe = output.toAbsolutePath().toString().replace("\\", "\\\\").replace("\"", "\\\"");
        return "(#pb=new java.lang.ProcessBuilder({\"/usr/bin/touch\",\"" + safe
                + "\"}),#methods=@java.lang.ProcessBuilder@class.getMethods(),"
                + "#names=#methods.{#this.getName()},#startIdx=#names.indexOf(\"start\"),"
                + "#methods[#startIdx].invoke(#pb,null))";
    }

    private static Properties properties(ZipFile zip, String name) throws Exception {
        var properties = new Properties();
        var entry = zip.getEntry(name);
        assertNotNull(entry, name);
        try (var input = zip.getInputStream(entry)) { properties.load(input); }
        return properties;
    }

    /** No application parent loader: neither original nor repaired classes can hide the other. */
    private static final class IsolatedEvaluator implements AutoCloseable {
        private final URLClassLoader loader;
        private final Class<?> ognl;
        private final Class<?> contextType;
        private final Class<?> memberAccessType;
        private final Object memberAccess;

        IsolatedEvaluator(String property) throws Exception {
            loader = new URLClassLoader(new URL[] {Path.of(System.getProperty(property)).toUri().toURL()}, null);
            ognl = loader.loadClass("org.apache.ibatis.ognl.Ognl");
            contextType = loader.loadClass("org.apache.ibatis.ognl.OgnlContext");
            memberAccessType = loader.loadClass("org.apache.ibatis.ognl.MemberAccess");
            memberAccess = Proxy.newProxyInstance(loader, new Class<?>[] {memberAccessType}, (proxy, method, args) -> {
                if (method.getName().equals("isAccessible")) return Modifier.isPublic(((Member) args[2]).getModifiers());
                return null;
            });
        }

        Object evaluate(String expression, Object root) throws Exception {
            Object context = ognl.getMethod("createDefaultContext", Object.class, memberAccessType).invoke(null, root, memberAccess);
            return ognl.getMethod("getValue", String.class, contextType, Object.class).invoke(null, expression, context, root);
        }

        @Override public void close() throws Exception { loader.close(); }
    }
}
