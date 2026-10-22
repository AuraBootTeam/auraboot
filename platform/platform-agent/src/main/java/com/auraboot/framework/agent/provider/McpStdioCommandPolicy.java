package com.auraboot.framework.agent.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fail-closed policy for MCP stdio child processes.
 *
 * <p>The official SDK ultimately uses {@link ProcessBuilder}; AuraBoot never
 * invokes a shell. Deployment owners must explicitly allow executable names or
 * absolute paths with {@code AURA_MCP_STDIO_ALLOWED_COMMANDS}. Shell
 * executables remain forbidden even when listed because accepting them would
 * turn tenant configuration into arbitrary command execution.
 */
@Component
public class McpStdioCommandPolicy {

    private static final Set<String> FORBIDDEN_SHELLS = Set.of(
            "sh", "bash", "zsh", "fish", "dash", "ksh",
            "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "pwsh.exe");

    private final Set<String> allowedCommands;

    public McpStdioCommandPolicy(
            @Value("${agent.mcp.stdio.allowed-commands:${AURA_MCP_STDIO_ALLOWED_COMMANDS:}}")
            String configuredCommands) {
        this.allowedCommands = Arrays.stream(
                        configuredCommands == null ? new String[0] : configuredCommands.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public void validate(McpServerTarget target) {
        if (target == null || !target.isStdioTransport()) {
            return;
        }
        String command = target.serverUrl();
        if (command == null || command.isBlank()
                || command.indexOf('\0') >= 0 || command.contains("\n") || command.contains("\r")) {
            throw new IllegalArgumentException("MCP stdio executable must be a single non-empty command");
        }

        String basename;
        try {
            Path path = Path.of(command);
            Path fileName = path.getFileName();
            basename = fileName == null ? command : fileName.toString();
        } catch (RuntimeException invalidPath) {
            throw new IllegalArgumentException("MCP stdio executable is not a valid path", invalidPath);
        }
        if (FORBIDDEN_SHELLS.contains(basename.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("MCP stdio shell executables are forbidden: " + basename);
        }

        if (!allowedCommands.contains(command)) {
            throw new IllegalArgumentException(
                    "MCP stdio executable is not allowlisted: " + command
                            + ". Add the exact executable to AURA_MCP_STDIO_ALLOWED_COMMANDS.");
        }

        if (target.stdioArgs() != null) {
            for (String argument : target.stdioArgs()) {
                if (argument == null || argument.indexOf('\0') >= 0
                        || argument.contains("\n") || argument.contains("\r")) {
                    throw new IllegalArgumentException("MCP stdio arguments must not contain nulls or newlines");
                }
            }
        }
        if (target.stdioEnv() != null) {
            for (var entry : target.stdioEnv().entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()
                        || !entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("Invalid MCP stdio environment variable name");
                }
                if (entry.getValue() == null || entry.getValue().indexOf('\0') >= 0) {
                    throw new IllegalArgumentException(
                            "MCP stdio environment variable values must not be null or contain null bytes");
                }
            }
        }
    }
}
