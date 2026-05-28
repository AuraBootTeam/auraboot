package com.auraboot.framework.chatbi.v2.compiler;

import com.auraboot.framework.chatbi.v2.dto.Operator;
import com.auraboot.framework.chatbi.v2.dto.SearchToken;
import com.auraboot.framework.chatbi.v2.dto.TokenType;
import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Compiles a verified {@link SearchToken} sequence into a
 * {@link SemanticQueryRequest} suitable for {@code POST /api/semantic/query}.
 *
 * <p>PRD 17 §7.2. Pure function; no DB, no I/O. Throws
 * {@link TokenCompileException} on unmapped Tokens; the caller is expected
 * to convert into a disambiguation prompt or a 400 response.
 *
 * <p>Mapping rules (W2):
 * <ul>
 *   <li>{@link TokenType#METRIC} → appended to {@code metrics}, qualified as
 *       {@code <semanticModelCode>.<code>} when not already qualified.</li>
 *   <li>{@link TokenType#DIMENSION} → appended to {@code dimensions} (with
 *       {@code __<grain>} suffix if {@code dateBucket} is set). If the same
 *       token also carries an {@code operator}+{@code value}, an entry is
 *       appended to {@code filters}.</li>
 *   <li>{@link TokenType#TIME_RANGE} → sets {@code timeRange.preset}; the
 *       semantic layer resolves the primary-time dimension. {@code field}
 *       is left null at this layer (W3 resolves it from catalog).</li>
 *   <li>{@link TokenType#TOP_N} → sets {@code limit} (value coerced to int).</li>
 *   <li>{@link TokenType#KEYWORD}, {@link TokenType#OPERATOR},
 *       {@link TokenType#AGGREGATION}, {@link TokenType#DATE_BUCKET},
 *       {@link TokenType#COLUMN}, {@link TokenType#VALUE} — informational
 *       only at this layer; their semantic effect is folded into the
 *       sibling METRIC / DIMENSION token during lexing (W3).</li>
 * </ul>
 *
 * <p>{@code semanticModelCode} may be null; in that case METRIC / DIMENSION
 * Tokens whose {@code resolvedCode} is not already dot-qualified will be
 * emitted as bare codes (downstream catalog lookup will then fail loudly).
 */
@Component
public class TokenCompiler {

    /**
     * @throws TokenCompileException if a METRIC / DIMENSION Token has a null
     *         or blank {@code resolvedCode}, or a TIME_RANGE preset is not
     *         one of the allowed set.
     */
    public SemanticQueryRequest compile(List<SearchToken> tokens, String semanticModelCode) {
        SemanticQueryRequest req = new SemanticQueryRequest();
        if (tokens == null || tokens.isEmpty()) {
            return req;
        }
        for (SearchToken t : tokens) {
            if (t == null || t.type() == null) {
                continue;
            }
            switch (t.type()) {
                case METRIC -> handleMetric(t, semanticModelCode, req);
                case DIMENSION -> handleDimension(t, semanticModelCode, req);
                case TIME_RANGE -> handleTimeRange(t, req);
                case TOP_N -> handleTopN(t, req);
                case KEYWORD, OPERATOR, AGGREGATION, DATE_BUCKET, COLUMN, VALUE -> {
                    // No direct slot — folded into METRIC / DIMENSION upstream.
                }
            }
        }
        return req;
    }

    private void handleMetric(SearchToken t, String modelCode, SemanticQueryRequest req) {
        String code = t.resolvedCode();
        if (code == null || code.isBlank()) {
            throw new TokenCompileException(
                    "UNKNOWN_METRIC",
                    "METRIC token has no resolvedCode (rawText=" + t.rawText() + ")");
        }
        req.getMetrics().add(qualify(code, modelCode));
    }

    private void handleDimension(SearchToken t, String modelCode, SemanticQueryRequest req) {
        String code = t.resolvedCode();
        if (code == null || code.isBlank()) {
            throw new TokenCompileException(
                    "UNKNOWN_DIMENSION",
                    "DIMENSION token has no resolvedCode (rawText=" + t.rawText() + ")");
        }
        String qualified = qualify(code, modelCode);
        if (t.dateBucket() != null && !t.dateBucket().isBlank()) {
            qualified = qualified + "__" + t.dateBucket();
        }
        req.getDimensions().add(qualified);

        // If the dimension carries an inline (op, value), record a filter
        // against the un-bucketed code — bucket only affects projection.
        if (t.operator() != null && t.value() != null) {
            req.getFilters().add(new SemanticQueryRequest.Filter(
                    qualify(code, modelCode),
                    t.operator().wire(),
                    t.value()));
        }
    }

    private void handleTimeRange(SearchToken t, SemanticQueryRequest req) {
        String preset = t.resolvedCode();
        if (preset == null || preset.isBlank()) {
            throw new TokenCompileException(
                    "BAD_TIME_RANGE",
                    "TIME_RANGE token missing preset (rawText=" + t.rawText() + ")");
        }
        if (!isKnownPreset(preset)) {
            throw new TokenCompileException(
                    "BAD_TIME_RANGE",
                    "Unknown TIME_RANGE preset: " + preset);
        }
        req.setTimeRange(new SemanticQueryRequest.TimeRange(null, preset, null, null));
    }

    private void handleTopN(SearchToken t, SemanticQueryRequest req) {
        Object v = t.value();
        if (v instanceof Number n) {
            req.setLimit(n.intValue());
        } else if (v instanceof String s) {
            try {
                req.setLimit(Integer.parseInt(s.trim()));
            } catch (NumberFormatException nfe) {
                // Best-effort: ignore malformed top-N rather than fail the whole compile.
            }
        }
    }

    private static String qualify(String code, String modelCode) {
        if (modelCode == null || modelCode.isBlank()) {
            return code;
        }
        if (code.contains(".")) {
            return code;
        }
        return modelCode + "." + code;
    }

    private static boolean isKnownPreset(String preset) {
        return switch (preset) {
            case "ytd", "mtd", "qtd", "last_7_days", "last_30_days", "last_month", "custom" -> true;
            default -> false;
        };
    }

    /** Exposed for symmetry with the design doc; not used internally. */
    public Operator nullSafeOperator(Operator op) {
        return op == null ? Operator.EQ : op;
    }
}
