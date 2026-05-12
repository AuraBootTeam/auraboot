package com.auraboot.framework.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathSafetyUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void requireSafeChildRejectsTraversalOutsideBase() {
        assertThatThrownBy(() -> PathSafetyUtils.requireSafeChild(tempDir, "../escape.txt", "test path"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes base directory");
    }

    @Test
    void requireSafeChildRejectsAbsoluteChild() {
        assertThatThrownBy(() -> PathSafetyUtils.requireSafeChild(tempDir, "/tmp/escape.txt", "test path"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be relative");
    }

    @Test
    void requireSafeChildAllowsNestedRelativePathInsideBase() {
        Path resolved = PathSafetyUtils.requireSafeChild(tempDir, "config/models.json", "test path");

        assertThat(resolved).isEqualTo(tempDir.resolve("config/models.json").toAbsolutePath().normalize());
    }

    @Test
    void requireSafeFileNameRejectsPathLikeUploadName() {
        assertThatThrownBy(() -> PathSafetyUtils.requireSafeFileName("../plugin.jar", ".jar", "plugin jar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a file name");
    }

    @Test
    void requireSafeFileNameRejectsReservedPathSegments() {
        assertThatThrownBy(() -> PathSafetyUtils.requireSafeFileName("..", null, "plugin directory"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a regular file name");
    }

    @Test
    void requireWithinBaseRejectsAbsolutePathOutsideBase() {
        assertThatThrownBy(() -> PathSafetyUtils.requireWithinBase(tempDir, Path.of("/tmp/escape.jar"), "plugin jar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes base directory");
    }

    @Test
    void requireExistingDirectoryNormalizesDirectory() throws Exception {
        Path nested = tempDir.resolve("docs");
        Files.createDirectory(nested);

        assertThat(PathSafetyUtils.requireExistingDirectory(nested, "docs")).isEqualTo(nested.toAbsolutePath());
    }
}
