package com.auraboot.framework.agent.memory;

import java.util.regex.Pattern;

/**
 * Keeps credentials out of long-term memory.
 *
 * <p>Memory is written from model output and from turn content, and it is read
 * back as established fact and pre-recalled into later prompts. A credential
 * that lands here therefore does not stay where it was said — it is replayed
 * into future turns, of possibly other sessions, and lives past the moment the
 * user could still reason about it. The turn transcript is bounded by retention;
 * a memory row is not.
 *
 * <p>The decision is <b>reject the write</b>, not redact it. A redacted memory
 * still asserts that something was established, and the assistant reads it back
 * as fact — "the API key is [REDACTED]" is a worse artefact than no memory at
 * all. The answer still reaches the user; only the durable copy is refused.
 *
 * <p>Patterns are deliberately the same family {@code LogSanitizer} uses for
 * logs, plus the shapes that identify a token on sight regardless of any
 * labelling around it — a model summarising a tool result will not always write
 * {@code "api_key": "..."}.
 */
public final class MemorySecretGuard {

    private static final Pattern LABELLED_SECRET = Pattern.compile(
            "(?i)\\b(api[_-]?key|secret[_-]?key|access[_-]?key|password|passwd|secret|token|credential"
                    + "|authorization|private[_-]?key)\\b\\s*[:=]\\s*\\S{6,}");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{16,}");
    /** Vendor key shapes that are unmistakable without any surrounding label. */
    private static final Pattern VENDOR_KEY = Pattern.compile(
            "\\b(sk-[A-Za-z0-9]{16,}|ghp_[A-Za-z0-9]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}"
                    + "|xox[baprs]-[A-Za-z0-9-]{10,})\\b");
    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----");

    private MemorySecretGuard() {
    }

    /**
     * Whether this text must not be persisted as memory. Checks title and body
     * together because a secret pasted into either is equally durable.
     */
    public static boolean containsSecret(String... texts) {
        if (texts == null) {
            return false;
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            if (LABELLED_SECRET.matcher(text).find()
                    || BEARER.matcher(text).find()
                    || VENDOR_KEY.matcher(text).find()
                    || PRIVATE_KEY_BLOCK.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}
