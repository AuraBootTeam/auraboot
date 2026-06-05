package com.auraboot.framework.plugin.extension.iot;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Dry-run an IoT alarm rule over <em>archived</em> telemetry and report how many
 * frames <b>would</b> fire — with <b>zero</b> production side effects (no
 * {@code iot_alarm_event} write, no {@code iot.alarm.v1} publish, no BPM start).
 *
 * <p><b>Faithfulness (§2.2末 / §8 — no divergent evaluator):</b> the only
 * production-functional alarm path for {@code kind=SQL} rules is the EMQX
 * broker's rule engine (there is no Java SQL evaluator). A faithful dry-run
 * therefore replays each archived telemetry frame through EMQX's own
 * synchronous {@code POST /api/v5/rule_test} endpoint (the broker evaluates the
 * exact stored rule SQL; HTTP 200 = matched, 412 = not matched). No rule is
 * provisioned, nothing is published to a live topic, and no Kafka/BPM is
 * touched.
 *
 * <p>{@code kind=SMART_ENGINE} and {@code kind=CHAIN} are <b>not</b> production
 * functional today (see the BPM-external-events backlog item) so they are not
 * silently "simulated"; the implementation throws a structured
 * {@code iot.error.rule_kind_not_production_evaluated:<kind>} instead.
 *
 * <p>Null-fallback SPI contract: {@code @Autowired(required=false)} returns
 * {@code null} on older platforms; plugin code treats {@code null} as "feature
 * unavailable".
 *
 * @since 2.6.0
 */
public interface BackgroundRuleSimulator {

    /**
     * Dry-run {@code ruleCode} over the archived telemetry in {@code window}.
     *
     * @param tenantId owning tenant id (must be {@code > 0})
     * @param ruleCode tenant-unique rule code (not blank)
     * @param window   replay window + per-run sample cap (validated by the record)
     * @return a {@link SimResult} with the would-fire count + per-fire detail
     * @throws com.auraboot.framework.meta.exception.MetaServiceException
     *         {@code iot.error.rule_not_found:<code>} when the rule does not
     *         exist, or
     *         {@code iot.error.rule_kind_not_production_evaluated:<kind>} when
     *         the rule kind has no production-functional evaluation path.
     */
    SimResult simulate(long tenantId, String ruleCode, SimWindow window);

    /**
     * Replay window. Telemetry frames are read in {@code [from, to)} and the run
     * stops after {@code maxSamples} frames have been evaluated (bounded cost).
     */
    record SimWindow(Instant from, Instant to, int maxSamples) {
        public SimWindow {
            if (from == null || to == null) {
                throw new IllegalArgumentException("from/to must not be null");
            }
            if (!to.isAfter(from)) {
                throw new IllegalArgumentException("to must be after from");
            }
            if (maxSamples <= 0) {
                throw new IllegalArgumentException("maxSamples must be > 0");
            }
        }
    }

    /**
     * A single telemetry frame that the rule's SQL matched (i.e. it would have
     * produced an alarm in production).
     *
     * @param deviceCode tenant-unique device code the frame belongs to
     * @param ruleCode   the simulated rule's code
     * @param severity   the rule's configured severity (carried for caller UX)
     * @param at         the frame timestamp
     * @param payload    the reconstructed telemetry payload (datapoint code → value)
     */
    record WouldFire(String deviceCode, String ruleCode, String severity, Instant at, Map<String, Object> payload) {}

    /**
     * Simulation outcome.
     *
     * @param ruleCode      the simulated rule's code
     * @param kind          the rule kind (always {@code SQL} for a successful run)
     * @param samplesChecked number of telemetry frames evaluated through EMQX
     * @param wouldFire     the frames that matched (size = would-fire count)
     * @param note          human-readable provenance note (counts, method)
     */
    record SimResult(String ruleCode, String kind, int samplesChecked, List<WouldFire> wouldFire, String note) {}
}
