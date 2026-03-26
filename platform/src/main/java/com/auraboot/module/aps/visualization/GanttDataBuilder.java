package com.auraboot.module.aps.visualization;

import com.auraboot.module.aps.dto.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GanttDataBuilder {

    public GanttData build(ScheduleResult result) {
        List<GanttRow> rows = result.getOperations().stream()
            .map(op -> new GanttRow(op.getResourceId(), op.getResourceName()))
            .distinct()
            .collect(Collectors.toList());

        List<GanttTask> tasks = result.getOperations().stream()
            .map(op -> GanttTask.builder()
                .id(op.getJobId() + "-" + op.getOperationName())
                .resourceId(op.getResourceId())
                .jobCode(op.getJobCode())
                .productName(op.getProductName())
                .operationName(op.getOperationName())
                .startTime(op.getStartTime())
                .endTime(op.getEndTime())
                .setupTimeMin(op.getSetupTimeMin())
                .processingTimeMin(op.getProcessingTimeMin())
                .color(colorByOperation(op.getOperationName()))
                .build())
            .collect(Collectors.toList());

        Set<GanttMilestone> milestones = new LinkedHashSet<>();
        if (result.getConflicts() != null) {
            for (ScheduleConflict conflict : result.getConflicts()) {
                if (conflict.getRequestedBy() != null) {
                    milestones.add(new GanttMilestone(conflict.getRequestedBy(), "Due: " + conflict.getJobId()));
                }
            }
        }

        Map<Long, Double> utilization = result.getResourceUtilization();

        return new GanttData(
            result.getStrategy(),
            rows,
            tasks,
            new ArrayList<>(milestones),
            utilization,
            result.getConflicts()
        );
    }

    private String colorByOperation(String opName) {
        if (opName == null) return "#8c8c8c";
        return switch (opName.toLowerCase()) {
            case "smt" -> "#1890ff";
            case "reflow" -> "#fa8c16";
            case "aoi" -> "#52c41a";
            case "tht" -> "#722ed1";
            case "wave" -> "#13c2c2";
            case "ict" -> "#eb2f96";
            case "fct" -> "#f5222d";
            case "assembly" -> "#2f54eb";
            case "packaging" -> "#595959";
            default -> "#8c8c8c";
        };
    }
}
