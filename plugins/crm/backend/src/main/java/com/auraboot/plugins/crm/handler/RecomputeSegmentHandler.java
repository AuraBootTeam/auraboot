package com.auraboot.plugins.crm.handler;

import com.auraboot.plugins.crm.engine.SegmentCriteriaEngine;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recomputes a dynamic segment's member count (CRM gap #11).
 *
 * <p>Wired as the {@code handler} of {@code crm:recompute_segment} (custom).
 * Parses the segment's criteria JSON, evaluates the pure
 * {@link SegmentCriteriaEngine} against the contact population, and writes the
 * matching member count + last-computed timestamp. Fail-loud (red line §8) on a
 * missing segment or unparseable criteria.
 */
@Extension
public class RecomputeSegmentHandler implements CommandHandlerExtension {

    private static final Logger log = LoggerFactory.getLogger(RecomputeSegmentHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String COMMAND_TYPE = "crm:recompute_segment";

    private static final String MODEL = "crm_segment";
    private static final String POPULATION = "crm_contact_common";

    @Override
    public String getCommandType() {
        return COMMAND_TYPE;
    }

    @Override
    public Set<String> getSupportedCommandTypes() {
        return Set.of(COMMAND_TYPE);
    }

    @Override
    public boolean supports(String commandType) {
        return COMMAND_TYPE.equals(commandType);
    }

    @Override
    public Object execute(CommandContext context) throws Exception {
        DataAccessor db = context.dataAccessor();
        if (db == null) {
            throw new IllegalStateException("DataAccessor unavailable; cannot recompute segment");
        }
        String recordId = context.recordId();
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalStateException("No segment record id on context");
        }
        Map<String, Object> seg = db.getById(MODEL, recordId);
        if (seg == null) {
            throw new IllegalStateException("Segment not found: " + recordId);
        }

        List<SegmentCriteriaEngine.Criterion> criteria = parseCriteria(seg.get("crm_seg_criteria"));
        List<Map<String, Object>> population = db.query(POPULATION, Collections.emptyMap());
        int count = SegmentCriteriaEngine.countMatches(population, criteria);

        Map<String, Object> update = new java.util.HashMap<>();
        update.put("crm_seg_member_count", count);
        update.put("crm_seg_last_computed", Instant.now().toString());
        db.update(MODEL, recordId, update);

        log.info("Segment {} recomputed: {} members match {} criteria (population={})",
                recordId, count, criteria.size(), population == null ? 0 : population.size());

        return Map.of("segmentId", recordId, "memberCount", count);
    }

    private static List<SegmentCriteriaEngine.Criterion> parseCriteria(Object raw) {
        if (raw == null) {
            return List.of();
        }
        String json = raw.toString().trim();
        if (json.isEmpty() || "[]".equals(json) || "null".equals(json)) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = MAPPER.readValue(json, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, Map.class));
            List<SegmentCriteriaEngine.Criterion> out = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                out.add(new SegmentCriteriaEngine.Criterion(
                        str(r.get("field")), str(r.get("op")), str(r.get("value"))));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Segment criteria JSON is invalid (red line §8 — no silent skip): "
                    + e.getMessage(), e);
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
