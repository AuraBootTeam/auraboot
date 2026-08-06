package com.auraboot.plugins.crm.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure marketing-attribution revenue distribution (CRM gap #13).
 *
 * <p>Distributes a won deal's revenue across the ordered campaign touchpoints
 * that influenced it, by attribution model. Separated from the handler so the
 * rules are unit-testable without a Spring context or database.
 *
 * <p>Models:
 * <ul>
 *   <li>{@code first_touch} — 100% to the first touchpoint;</li>
 *   <li>{@code last_touch} — 100% to the last touchpoint;</li>
 *   <li>{@code linear} — split evenly across all touchpoints (remainder to the
 *       last so the parts sum exactly to revenue).</li>
 * </ul>
 * Empty touchpoints yield an empty map; a single touchpoint always gets 100%.
 */
public final class AttributionEngine {

    private AttributionEngine() {
    }

    public static Map<String, BigDecimal> distribute(List<String> touchpoints, BigDecimal revenue, String model) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (touchpoints == null || touchpoints.isEmpty()) {
            return out;
        }
        BigDecimal rev = revenue == null ? BigDecimal.ZERO : revenue;
        if (rev.signum() < 0) {
            rev = BigDecimal.ZERO;
        }
        int n = touchpoints.size();
        String m = model == null ? "last_touch" : model;

        if (n == 1) {
            out.put(touchpoints.get(0), rev);
            return out;
        }
        switch (m) {
            case "first_touch" -> {
                for (String t : touchpoints) {
                    out.merge(t, BigDecimal.ZERO, BigDecimal::add);
                }
                out.put(touchpoints.get(0), out.get(touchpoints.get(0)).add(rev));
            }
            case "linear" -> {
                BigDecimal share = rev.divide(new BigDecimal(n), 2, RoundingMode.DOWN);
                BigDecimal allocated = BigDecimal.ZERO;
                for (int i = 0; i < n; i++) {
                    BigDecimal part = (i == n - 1) ? rev.subtract(allocated) : share;
                    out.merge(touchpoints.get(i), part, BigDecimal::add);
                    allocated = allocated.add(part);
                }
            }
            default -> { // last_touch
                for (String t : touchpoints) {
                    out.merge(t, BigDecimal.ZERO, BigDecimal::add);
                }
                String last = touchpoints.get(n - 1);
                out.put(last, out.get(last).add(rev));
            }
        }
        return out;
    }
}
