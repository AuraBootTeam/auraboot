package com.auraboot.framework.application.database.mybatis;

import java.io.IOException;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.Properties;

/** Reject split implementations instead of relying on JAR/classloader ordering. */
public final class MybatisCarrierIntegrity {
    private static final String CONFIGURATION = "org/apache/ibatis/session/Configuration.class";
    private static final String OGNL = "org/apache/ibatis/ognl/Ognl.class";
    private static final String MARKER = "META-INF/maven/com.auraboot/mybatis-compat/pom.properties";

    private MybatisCarrierIntegrity() {}

    public static void verify(ClassLoader loader) {
        if (loader == null) throw new IllegalStateException("A verified MyBatis application classloader is required");
        try {
            URL configuration = unique(loader, CONFIGURATION);
            URL ognl = unique(loader, OGNL);
            URL marker = unique(loader, MARKER);
            String expected = origin(marker, MARKER);
            if (!expected.equals(origin(configuration, CONFIGURATION)) || !expected.equals(origin(ognl, OGNL))) {
                throw new IllegalStateException("MyBatis and relocated OGNL must come from one AuraBoot compatible carrier");
            }
            Properties properties = new Properties();
            try (var input = marker.openStream()) { properties.load(input); }
            if (!"com.auraboot".equals(properties.getProperty("groupId"))
                    || !"mybatis-compat".equals(properties.getProperty("artifactId"))) {
                throw new IllegalStateException("Unexpected compatible MyBatis carrier identity");
            }
        } catch (IOException failure) {
            // CATCH: unreadable dependency metadata must block startup/plugin loading, never fall back.
            throw new IllegalStateException("Cannot verify the compatible MyBatis carrier", failure);
        }
    }

    private static URL unique(ClassLoader loader, String resource) throws IOException {
        var resources = new LinkedHashSet<URL>();
        var found = loader.getResources(resource);
        while (found.hasMoreElements()) resources.add(found.nextElement());
        if (resources.size() != 1) {
            throw new IllegalStateException("Expected one compatible MyBatis resource: " + resource
                    + "; found " + resources.size() + ". Exclude org.mybatis:mybatis and do not bundle framework classes in plugins.");
        }
        return resources.iterator().next();
    }

    private static String origin(URL source, String resource) {
        String path = source.toExternalForm();
        if (!path.endsWith(resource)) throw new IllegalStateException("Unexpected MyBatis resource location");
        return path.substring(0, path.length() - resource.length());
    }
}
