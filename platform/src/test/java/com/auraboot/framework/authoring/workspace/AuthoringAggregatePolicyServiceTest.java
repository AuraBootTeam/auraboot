package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringChangeSetSplitter.ChangeItem;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.AggregatePolicy;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringAggregatePolicyServiceTest {

    private final AuthoringAggregatePolicyService service = new AuthoringAggregatePolicyService();

    @Test
    void mixedItemsAlwaysUseTheMostRestrictiveRiskRouteAndPublishPolicy() {
        AggregatePolicy aggregate = service.aggregateItems(List.of(
                item("l0", "L0", "INLINE", "DIRECT_ALLOWED"),
                item("l2", "L2", "GUIDED_INLINE", "REQUIRED_REVIEW"),
                item("l3", "L3", "HANDOFF_STUDIO", "STUDIO_APPROVAL")));

        assertThat(aggregate.riskLevel()).isEqualTo("L3");
        assertThat(aggregate.route()).isEqualTo("HANDOFF_STUDIO");
        assertThat(aggregate.publishPolicy()).isEqualTo("STUDIO_APPROVAL");
        assertThat(aggregate.approvalState()).isEqualTo("PENDING");
    }

    @Test
    void anL2PartitionRemainsReviewRequiredEvenWithoutStudioChanges() {
        AggregatePolicy aggregate = service.aggregateItems(List.of(
                item("l0", "L0", "INLINE", "DIRECT_ALLOWED"),
                item("l2", "L2", "GUIDED_INLINE", "REQUIRED_REVIEW"),
                item("later-l0", "L0", "INLINE", "DIRECT_ALLOWED")));

        assertThat(aggregate.riskLevel()).isEqualTo("L2");
        assertThat(aggregate.publishPolicy()).isEqualTo("REQUIRED_REVIEW");
        assertThat(aggregate.approvalState()).isEqualTo("PENDING");
    }

    private ChangeItem item(String pid, String risk, String route, String publishPolicy) {
        return new ChangeItem(
                1, pid, "table-1", "/props/" + pid, "REPLACE",
                JsonNodeFactory.instance.textNode("old"),
                JsonNodeFactory.instance.textNode("new"),
                JsonNodeFactory.instance.arrayNode(),
                risk, route, publishPolicy, "REVERSIBLE", "manifest",
                1, 2, 7, Instant.parse("2026-08-09T00:00:00Z"),
                null, null, JsonNodeFactory.instance.arrayNode());
    }
}
