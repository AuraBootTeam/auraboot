package com.auraboot.framework.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Collector-independent trace retention; registered alongside any remote exporter. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "management.tracing.enabled", matchIfMissing = true)
public class LocalTraceConfig {
    @Bean
    SpanExporter localTraceExporter(ObjectMapper mapper,
            @Value("${auraboot.observability.trace-log-dir:${AURA_TRACE_LOG_DIR:./logs/traces}}") String directory) {
        return new LocalExporter(mapper, Path.of(directory), 10 * 1024 * 1024);
    }

    static final class LocalExporter implements SpanExporter {
        private static final Logger LOG = LoggerFactory.getLogger(LocalExporter.class);
        private static final Set<String> ATTRIBUTES = Set.of("request.id", "url.path", "http.method",
                "http.request.method", "http.response.status_code", "http.status_code", "db.system");
        private final ObjectMapper mapper;
        private final Path directory;
        private final Path file;
        private final long maxBytes;
        private boolean warned;

        LocalExporter(ObjectMapper mapper, Path directory, long maxBytes) {
            this.mapper = mapper;
            this.directory = directory;
            this.file = directory.resolve("auraboot-backend.jsonl");
            this.maxBytes = maxBytes;
        }

        @Override
        public synchronized CompletableResultCode export(Collection<SpanData> spans) {
            try {
                Files.createDirectories(directory);
                if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
                }
                for (SpanData span : spans) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("event", "trace-span");
                    row.put("traceId", span.getTraceId());
                    row.put("spanId", span.getSpanId());
                    row.put("parentSpanId", span.getParentSpanId());
                    row.put("name", safeName(span.getName()));
                    row.put("kind", span.getKind().name());
                    row.put("startEpochNanos", span.getStartEpochNanos());
                    row.put("durationNanos", span.getEndEpochNanos() - span.getStartEpochNanos());
                    row.put("status", span.getStatus().getStatusCode().name());
                    Map<String, String> attributes = new LinkedHashMap<>();
                    span.getAttributes().forEach((key, value) -> {
                        if (ATTRIBUTES.contains(key.getKey())) attributes.put(key.getKey(), safeName(String.valueOf(value)));
                    });
                    row.put("attributes", attributes);
                    // Bodies, headers, SQL text and exception messages belong to neither this index nor its IDs.
                    byte[] bytes = (mapper.writeValueAsString(row) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    if (Files.exists(file) && Files.size(file) + bytes.length > maxBytes) rotate();
                    if (!Files.exists(file)) {
                        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                        } else Files.createFile(file);
                    }
                    Files.write(file, bytes, StandardOpenOption.APPEND);
                    if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                    }
                }
                return CompletableResultCode.ofSuccess();
            } catch (Exception exception) {
                if (!warned) {
                    LOG.error("Local trace retention unavailable: {}", exception.getClass().getSimpleName());
                    warned = true;
                }
                return CompletableResultCode.ofFailure();
            }
        }

        private static String safeName(String value) {
            String name = value.split("\\?", 2)[0];
            if (name.matches("(?is)^(select|insert|update|delete)\\s.*")) return "database.query";
            return name.substring(0, Math.min(name.length(), 256));
        }

        private void rotate() throws java.io.IOException {
            Files.deleteIfExists(directory.resolve("auraboot-backend.jsonl.4"));
            for (int index = 3; index >= 0; index--) {
                Path source = index == 0 ? file : directory.resolve("auraboot-backend.jsonl." + index);
                if (Files.exists(source)) Files.move(source, directory.resolve("auraboot-backend.jsonl." + (index + 1)),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }

        @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }
}
