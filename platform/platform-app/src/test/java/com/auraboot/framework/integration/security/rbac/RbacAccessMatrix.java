package com.auraboot.framework.integration.security.rbac;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Loader for the RBAC SOT matrix (test resource {@code rbac/rbac-access-matrix.json}).
 *
 * <p>The matrix is the single, independent declaration of INTENDED access — it is deliberately NOT
 * derived from {@code default-bootstrap.json}, so a drift between "what we intend" and "what
 * bootstrap actually seeds" surfaces as a failing test instead of hiding. Both the backend
 * enforcement IT (A layer) and the Playwright per-role golden (B layer) read this same file.
 *
 * <p>See {@code docs/agent-rules/rbac-golden-and-cross-cutting-regression.md}.
 */
public final class RbacAccessMatrix {

    private final JsonNode root;

    private RbacAccessMatrix(JsonNode root) {
        this.root = root;
    }

    /** Load from the classpath (test resources). */
    public static RbacAccessMatrix load() {
        try (InputStream in = RbacAccessMatrix.class.getClassLoader()
                .getResourceAsStream("rbac/rbac-access-matrix.json")) {
            if (in == null) {
                throw new IllegalStateException("rbac/rbac-access-matrix.json not found on test classpath");
            }
            return new RbacAccessMatrix(new ObjectMapper().readTree(in));
        } catch (Exception e) {
            throw new IllegalStateException("failed to load rbac-access-matrix.json", e);
        }
    }

    /** Access a single role entry within a deployment (e.g. {@code "platform-baseline"}, {@code "tenant_member"}). */
    public RoleEntry role(String deployment, String roleCode) {
        JsonNode roleNode = root.path("deployments").path(deployment).path("roles").path(roleCode);
        if (roleNode.isMissingNode()) {
            throw new IllegalArgumentException("no matrix entry for " + deployment + "/" + roleCode);
        }
        return new RoleEntry(roleCode, roleNode);
    }

    /** All role codes declared for a deployment. */
    public List<String> roleCodes(String deployment) {
        List<String> codes = new ArrayList<>();
        root.path("deployments").path(deployment).path("roles").fieldNames().forEachRemaining(codes::add);
        return codes;
    }

    /** Permission codes gated by a named special rule (e.g. {@code "REG-5-6-assignment"}). */
    public List<String> specialRuleCodes(String rule) {
        return stringList(root.path("specialRules").path(rule).path("codes"));
    }

    /** A named cross-cutting special rule (e.g. {@code "REG-3-anon-discovery"}). */
    public SpecialRule specialRule(String rule) {
        JsonNode node = root.path("specialRules").path(rule);
        if (node.isMissingNode()) {
            throw new IllegalArgumentException("no special rule '" + rule + "' in matrix");
        }
        return new SpecialRule(rule, node);
    }

    /** A cross-cutting RBAC special rule (REG-2 / REG-3 / REG-5-6 / cross-tenant). */
    public static final class SpecialRule {
        private final String name;
        private final JsonNode node;

        private SpecialRule(String name, JsonNode node) {
            this.name = name;
            this.node = node;
        }

        public String name() {
            return name;
        }

        /** Endpoint pattern the rule guards (e.g. {@code /.well-known/agent.json}); empty if not endpoint-shaped. */
        public String endpoint() {
            return node.path("endpoint").asText();
        }

        /** HTTP status an anonymous caller must receive; 0 if the rule declares no anonymous expectation. */
        public int anonymousStatus() {
            return node.path("anonymous").asInt();
        }
    }

    /** One role's intended access. */
    public static final class RoleEntry {
        private final String code;
        private final JsonNode node;

        private RoleEntry(String code, JsonNode node) {
            this.code = code;
            this.node = node;
        }

        public String code() {
            return code;
        }

        public String layer() {
            return node.path("layer").asText();
        }

        public boolean isWildcardAllow() {
            List<String> allow = allow();
            return allow.size() == 1 && "*".equals(allow.get(0));
        }

        /** Permission codes this role MUST resolve (excludes the {@code "*"} wildcard sentinel). */
        public List<String> allow() {
            return stringList(node.path("allow"));
        }

        /** Permission codes this role MUST NOT resolve. */
        public List<String> deny() {
            return stringList(node.path("deny"));
        }
    }

    private static List<String> stringList(JsonNode arrayNode) {
        List<String> out = new ArrayList<>();
        if (arrayNode.isArray()) {
            arrayNode.forEach(n -> out.add(n.asText()));
        }
        return out;
    }
}
