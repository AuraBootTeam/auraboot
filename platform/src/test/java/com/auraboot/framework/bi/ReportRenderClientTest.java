package com.auraboot.framework.bi;

import com.auraboot.framework.bi.service.impl.ReportRenderClient;
import com.auraboot.framework.bi.service.impl.ReportRenderException;
import com.auraboot.framework.bi.service.impl.ReportRenderProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-level verification of the JVM↔Node subprocess plumbing (Phase 3 slice 3).
 * Uses a stub shell command in place of the real Node renderer so the test
 * exercises stdin write / exit-code / output handling without depending on Node
 * or a browser. The real renderer is exercised by the real-stack PDF golden.
 */
class ReportRenderClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Object> model = Map.of("title", "t", "body", List.of());

    private ReportRenderClient client(List<String> command) {
        ReportRenderProperties props = new ReportRenderProperties();
        props.setEnabled(true);
        props.setCommand(command);
        props.setTimeoutSeconds(20);
        return new ReportRenderClient(objectMapper, props);
    }

    @Test
    void renderPdf_returnsNull_whenDisabled() {
        ReportRenderProperties props = new ReportRenderProperties();
        props.setEnabled(false);
        assertThat(new ReportRenderClient(objectMapper, props).renderPdf(model, Map.of())).isNull();
    }

    @Test
    void renderPdf_returnsNull_whenCommandEmpty() {
        assertThat(client(List.of()).renderPdf(model, Map.of())).isNull();
    }

    @Test
    void renderPdf_returnsPdfBytes_fromStubRenderer(@TempDir Path dir) throws IOException {
        Path stub = executableScript(dir,
                "#!/bin/sh",
                "cat > /dev/null",          // drain the request JSON on stdin
                "out=\"\"",
                "while [ $# -gt 0 ]; do",
                "  if [ \"$1\" = \"--out\" ]; then out=\"$2\"; fi",
                "  shift",
                "done",
                "echo '%PDF-1.4 stub-render' > \"$out\"");

        byte[] pdf = client(List.of(stub.toString())).renderPdf(model, Map.of());

        assertThat(pdf).startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F', (byte) '-');
        assertThat(new String(pdf)).contains("stub-render");
    }

    @Test
    void renderPdf_throws_whenRendererExitsNonZero(@TempDir Path dir) throws IOException {
        Path stub = executableScript(dir,
                "#!/bin/sh",
                "cat > /dev/null",
                "echo 'render boom' 1>&2",
                "exit 3");

        assertThatThrownBy(() -> client(List.of(stub.toString())).renderPdf(model, Map.of()))
                .isInstanceOf(ReportRenderException.class)
                .hasMessageContaining("exited 3");
    }

    @Test
    void renderPdf_throws_whenRendererProducesNonPdf(@TempDir Path dir) throws IOException {
        Path stub = executableScript(dir,
                "#!/bin/sh",
                "cat > /dev/null",
                "out=\"\"",
                "while [ $# -gt 0 ]; do",
                "  if [ \"$1\" = \"--out\" ]; then out=\"$2\"; fi",
                "  shift",
                "done",
                "echo 'not a pdf' > \"$out\"");

        assertThatThrownBy(() -> client(List.of(stub.toString())).renderPdf(model, Map.of()))
                .isInstanceOf(ReportRenderException.class)
                .hasMessageContaining("did not produce a PDF");
    }

    private Path executableScript(Path dir, String... lines) throws IOException {
        Path script = dir.resolve("stub-renderer.sh");
        Files.writeString(script, String.join("\n", lines) + "\n");
        Files.setPosixFilePermissions(script, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        return script;
    }
}
