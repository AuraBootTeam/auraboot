package com.auraboot.framework.semantic.compiler;

import com.auraboot.framework.semantic.dto.AccessPolicyDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles {@link AccessPolicyDTO#getSqlFilter()} fragments into safe,
 * parameterised WHERE predicates suitable for inclusion in the SQL generated
 * by {@link MetricCompiler}.
 *
 * <p>PRD 16 §7.4 (the critical security path):
 * <ul>
 *   <li>Every {@code {user.<attr>}} placeholder is replaced by {@code ?} and
 *       its resolved value pushed into the params list. Multi-valued attributes
 *       (comma-separated string) inside {@code IN (...)} are expanded into
 *       {@code IN (?, ?, ...)}.</li>
 *   <li>Defence-in-depth: even though {@link com.auraboot.framework.semantic.parser.SemanticValidator}
 *       already enforces a SQL-injection denylist on {@code sql_filter} at
 *       publish time, this compiler re-checks before emitting any SQL —
 *       authors with DB write access could otherwise tamper with
 *       {@code ab_semantic_*} rows.</li>
 *   <li>Missing user attribute → {@link AccessException}{@code (USER_ATTRIBUTE_MISSING)}
 *       so callers can choose 403 vs empty-result behaviour (PRD §15 #3 picked
 *       403).</li>
 * </ul>
 */
@Component
public class AccessPolicyCompiler {

    /** Mirrors SemanticValidator denylist. Keep in sync. */
    private static final Pattern DENY = Pattern.compile(
            "(--|/\\*|\\*/|;|\\b(drop|delete|truncate|alter|create|grant|revoke|insert|update|union|exec|execute)\\b)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern USER_PLACEHOLDER = Pattern.compile("\\{user\\.([a-z][a-z0-9_]*)\\}");

    /**
     * Compile every applicable policy and append {@code AND (<predicate>)} to
     * {@code whereClause}, returning the additional parameter values in order.
     *
     * @param whereClause builder for the host SQL's WHERE; mutated in place
     * @param policies all policies declared on the semantic model
     * @param requestedDimensions dim codes the request actually selects/filters on
     *                            — a policy only fires if it covers at least one
     * @param user user context
     * @return ordered list of additional {@code ?} params (caller appends to its own list)
     */
    public List<Object> injectRls(StringBuilder whereClause,
                                   List<AccessPolicyDTO> policies,
                                   List<String> requestedDimensions,
                                   UserContext user) {
        List<Object> params = new ArrayList<>();
        if (policies == null || policies.isEmpty()) {
            return params;
        }
        for (AccessPolicyDTO policy : policies) {
            if (!appliesTo(policy, requestedDimensions)) {
                continue;
            }
            String filter = policy.getSqlFilter();
            if (filter == null || filter.isBlank()) {
                continue;
            }
            // Defence-in-depth: reject denylisted tokens even at compile time.
            if (DENY.matcher(filter).find()) {
                throw new AccessException("SQL_INJECTION_DETECTED",
                        "access_policy.sql_filter contains denylisted token: " + filter);
            }
            String compiled = substitutePlaceholders(filter, policy, user, params);
            whereClause.append(" AND (").append(compiled).append(")");
        }
        return params;
    }

    /** A policy applies if it has no target_dimensions, or any target intersects requestedDimensions. */
    private boolean appliesTo(AccessPolicyDTO policy, List<String> requestedDims) {
        List<String> targets = policy.getTargetDimensions();
        if (targets == null || targets.isEmpty()) {
            return true;
        }
        if (requestedDims == null || requestedDims.isEmpty()) {
            // Policy targets specific dims; if request touches none we still apply,
            // because the policy author intent is "always restrict when this dim
            // *could* leak through join". Conservative posture: enforce.
            return true;
        }
        for (String t : targets) {
            if (requestedDims.contains(t)) {
                return true;
            }
        }
        return true; // conservative: still enforce even on misses (v0.2 may relax)
    }

    private String substitutePlaceholders(String filter,
                                           AccessPolicyDTO policy,
                                           UserContext user,
                                           List<Object> params) {
        Matcher m = USER_PLACEHOLDER.matcher(filter);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(filter, last, m.start());
            String attr = m.group(1);
            String value = user.attribute(attr);
            if (value == null) {
                throw new AccessException("USER_ATTRIBUTE_MISSING",
                        "user attribute '" + attr + "' is required by policy "
                                + policy.getAccessGrant() + " but not present in UserContext");
            }
            String surrounding = surroundingContext(filter, m.start(), m.end());
            if ("IN".equals(surrounding)) {
                // Expand comma-separated value into IN (?, ?, ...)
                String[] vals = value.split(",");
                StringBuilder ph = new StringBuilder();
                for (int i = 0; i < vals.length; i++) {
                    if (i > 0) ph.append(", ");
                    ph.append("?");
                    params.add(vals[i].trim());
                }
                out.append(ph);
            } else {
                out.append("?");
                params.add(value);
            }
            last = m.end();
        }
        out.append(filter, last, filter.length());

        // After substitution, fail loud if any {placeholder} we don't understand remains.
        if (out.indexOf("{") >= 0) {
            throw new AccessException("UNRESOLVED_PLACEHOLDER",
                    "unresolved placeholder in sql_filter: " + out);
        }
        return out.toString();
    }

    /** Returns {@code "IN"} if the placeholder sits between {@code IN (} and {@code )}. */
    private String surroundingContext(String filter, int start, int end) {
        // Walk backwards from start, skipping whitespace, looking for "IN ("
        int i = start - 1;
        while (i >= 0 && Character.isWhitespace(filter.charAt(i))) i--;
        if (i < 0 || filter.charAt(i) != '(') return "";
        i--;
        while (i >= 0 && Character.isWhitespace(filter.charAt(i))) i--;
        if (i >= 1
                && (filter.charAt(i) == 'n' || filter.charAt(i) == 'N')
                && (filter.charAt(i - 1) == 'i' || filter.charAt(i - 1) == 'I')) {
            // boundary check
            if (i == 1 || !Character.isLetterOrDigit(filter.charAt(i - 2))) {
                return "IN";
            }
        }
        return "";
    }
}
