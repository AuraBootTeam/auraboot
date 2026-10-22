package com.auraboot.framework.agent.dto.replay;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Replay UI MVP — minimal pagination envelope for
 * {@code GET /api/admin/agent-runs}.
 *
 * <p>Independent of {@code org.springframework.data.domain.Page} (Spring
 * Data is not on the platform classpath here) and of any project-wide
 * pagination DTO so the contract is fully self-describing for the
 * frontend. Field shape mirrors what the page-designer DataTable already
 * consumes: {@code items} + {@code total} + {@code page} + {@code size}.
 */
@Data
@Builder
public class AgentRunPage {

    private List<AgentRunListItem> items;
    private long total;
    private int page;
    private int size;
}
