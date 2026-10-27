package com.auraboot.framework.plugin.extension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** One shared versioned document encoding. This custom canonical JSON is not RFC 8785/JCS. */
public final class AnnouncementDocumentCanonicalizer {
    public static final String SCHEMA = "operating-policy-document.v1";
    public static final String BODY_FORMAT = "announcement-html-raw.v1";
    private static final String DOMAIN = "auraboot.announcement.document.v1\n";
    private AnnouncementDocumentCanonicalizer() {}

    public enum SourceIdentityKind { SEAL_OBSERVED_CONTENT }

    /** Source identity is observed at official sealing, not a provider object generation. */
    public record Attachment(String filePid, SourceIdentityKind sourceIdentityKind, String sourceVersionIdentity,
                             String name, String mediaType, long byteLength, String sha256) {
        public Attachment {
            requirePid(filePid);
            if (sourceIdentityKind != SourceIdentityKind.SEAL_OBSERVED_CONTENT) invalid();
            requireHash(sourceVersionIdentity); requireHash(sha256);
            requireUnicode(name); requireUnicode(mediaType);
            if (byteLength < 0) invalid();
        }
    }

    public record Document(String title, String body, List<Attachment> attachments) {
        public Document {
            requireUnicode(title); requireUnicode(body);
            if (attachments == null || attachments.size() > 1000) invalid();
            var sorted = new ArrayList<>(attachments);
            sorted.sort(Comparator.comparing(Attachment::filePid));
            String previous = null;
            for (var item : sorted) {
                if (item.filePid().equals(previous)) invalid();
                previous = item.filePid();
            }
            attachments = List.copyOf(sorted);
        }
    }

    public record Encoded(String schemaId, String canonicalJson, String documentDigest) {}

    public static Encoded encode(Document document) {
        Objects.requireNonNull(document, "document");
        var out = new StringBuilder("{\"schemaId\":"); string(out, SCHEMA);
        out.append(",\"title\":"); string(out, document.title());
        out.append(",\"bodyFormat\":"); string(out, BODY_FORMAT);
        out.append(",\"body\":"); string(out, document.body());
        out.append(",\"attachments\":[");
        boolean first = true;
        for (var item : document.attachments()) {
            if (!first) out.append(','); first = false;
            out.append("{\"filePid\":"); string(out, item.filePid());
            out.append(",\"sourceIdentityKind\":"); string(out, item.sourceIdentityKind().name());
            out.append(",\"sourceVersionIdentity\":"); string(out, item.sourceVersionIdentity());
            out.append(",\"name\":"); string(out, item.name());
            out.append(",\"mediaType\":"); string(out, item.mediaType());
            out.append(",\"byteLength\":").append(item.byteLength());
            out.append(",\"sha256\":"); string(out, item.sha256()); out.append('}');
        }
        String canonical = out.append("]}").toString();
        return new Encoded(SCHEMA, canonical, sha256((DOMAIN + canonical).getBytes(StandardCharsets.UTF_8)));
    }

    /** Strict Unicode scalar strings, preserved without trimming, normalization or HTML rewriting. */
    private static void string(StringBuilder out, String value) {
        requireUnicode(value); out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) {
                out.append("\\u00").append("0123456789abcdef".charAt(c >>> 4))
                        .append("0123456789abcdef".charAt(c & 15));
            } else out.append(c);
        }
        out.append('"');
    }

    private static void requireUnicode(String value) {
        if (value == null) invalid();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) invalid();
            } else if (Character.isLowSurrogate(c)) invalid();
        }
    }

    public static void requirePid(String value) {
        if (value == null || !value.matches("[0-9A-HJKMNP-TV-Z]{26}")) invalid();
    }
    public static void requireHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) invalid();
    }
    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void invalid() { throw new IllegalArgumentException("Invalid sealed document projection"); }
}
