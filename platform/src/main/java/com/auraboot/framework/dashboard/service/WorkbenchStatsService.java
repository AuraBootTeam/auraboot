package com.auraboot.framework.dashboard.service;

import com.auraboot.framework.dashboard.dto.WorkbenchBpmStatsDTO;
import com.auraboot.framework.dashboard.dto.WorkbenchPipelineDTO;
import com.auraboot.framework.dashboard.dto.WorkbenchStatsDTO;

import java.util.List;

/**
 * Service for computing workbench dashboard statistics.
 */
public interface WorkbenchStatsService {

    /**
     * Get aggregated statistics for the workbench.
     *
     * @param keys optional list of stat keys to return; if null/empty, returns all default keys
     * @return WorkbenchStatsDTO containing the requested statistics
     */
    WorkbenchStatsDTO getStats(List<String> keys);

    /**
     * Get CRM opportunity pipeline grouped by stage.
     *
     * @return pipeline stages with counts and amounts
     */
    WorkbenchPipelineDTO getPipeline();

    /**
     * Get BPM process statistics.
     *
     * @return BPM stats including running count, completion rate, etc.
     */
    WorkbenchBpmStatsDTO getBpmStats();
}
