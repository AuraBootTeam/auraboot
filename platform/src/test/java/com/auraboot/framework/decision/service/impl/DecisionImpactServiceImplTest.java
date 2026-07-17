package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.decision.dto.DecisionImpactRefDTO;
import com.auraboot.framework.decision.dto.DecisionFieldPreflightDTO;
import com.auraboot.framework.decision.dto.DecisionFieldPreflightRequest;
import com.auraboot.framework.decision.dto.DecisionIntegrationImpactDTO;
import com.auraboot.framework.decision.service.DecisionImpactAckService;
import com.auraboot.framework.decision.service.DecisionUsageIndexService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DecisionImpactServiceImplTest {

    private final DecisionUsageIndexService usageIndexService = mock(DecisionUsageIndexService.class);
    private final DecisionImpactAckService impactAckService = mock(DecisionImpactAckService.class);
    private final DecisionImpactServiceImpl service =
            new DecisionImpactServiceImpl(usageIndexService, impactAckService);

    @Test
    void getIntegrationImpactReturnsConnectorConsumersAndManagementUrl() {
        DecisionImpactRefDTO ref = new DecisionImpactRefDTO();
        ref.setSourceType("AUTOMATION");
        ref.setSourceCode("auto-1");
        ref.setSourceName("Escalation Flow");
        ref.setTargetType("CONNECTOR");
        ref.setTargetCode("api-1");
        ref.setTargetPath("enrich");
        ref.setMetadata(Map.of("actionType", "call_api"));
        when(usageIndexService.findTargetRefs("CONNECTOR", "api-1")).thenReturn(List.of(ref));

        DecisionIntegrationImpactDTO impact = service.getIntegrationImpact("CONNECTOR", "api-1");

        assertThat(impact.getTargetType()).isEqualTo("CONNECTOR");
        assertThat(impact.getTargetCode()).isEqualTo("api-1");
        assertThat(impact.getManageUrl()).isEqualTo("/p/api_connector");
        assertThat(impact.getReferences()).containsExactly(ref);
        assertThat(impact.getRisk().getBlocking()).isTrue();
        assertThat(impact.getRisk().getCounts()).containsEntry("AUTOMATION", 1);
        assertThat(impact.getRisk().getSummary()).isEqualTo("Used by 1 automation");
    }

    @Test
    void getIntegrationImpactDoesNotRebuildWhenTargetHasNoConsumers() {
        when(usageIndexService.findTargetRefs("WEBHOOK", "wh-1")).thenReturn(List.of());

        DecisionIntegrationImpactDTO impact = service.getIntegrationImpact("webhook", "wh-1");

        assertThat(impact.getTargetType()).isEqualTo("WEBHOOK");
        assertThat(impact.getTargetCode()).isEqualTo("wh-1");
        assertThat(impact.getManageUrl()).isEqualTo("/p/webhook");
        assertThat(impact.getReferences()).isEmpty();
        assertThat(impact.getRisk().getBlocking()).isFalse();
        assertThat(impact.getRisk().getSummary()).isEqualTo("No integration consumers");
        verify(usageIndexService, never()).rebuild();
    }

    @Test
    void preflightFieldChangeSupportsDictPermissionAndVirtualSourceActions() {
        DecisionImpactRefDTO ref = new DecisionImpactRefDTO();
        ref.setSourceType("SLA_RULE");
        ref.setSourceCode("wd_manager_approve_sla");
        ref.setSourceName("Manager approval SLA");
        ref.setTargetPath("record.data.wd_req_type");
        when(usageIndexService.findFieldRefs("record.data.wd_req_type")).thenReturn(List.of(ref));

        DecisionFieldPreflightRequest dictRequest = new DecisionFieldPreflightRequest();
        dictRequest.setFieldRef("record.data.wd_req_type");
        dictRequest.setAction("delete_dict_item");
        dictRequest.setDictCode("leave_type");
        dictRequest.setDictValue("annual");
        DecisionFieldPreflightDTO dictPreflight = service.preflightFieldChange(dictRequest);

        assertThat(dictPreflight.getAction()).isEqualTo("DELETE_DICT_ITEM");
        assertThat(dictPreflight.getDictCode()).isEqualTo("leave_type");
        assertThat(dictPreflight.getDictValue()).isEqualTo("annual");
        assertThat(dictPreflight.getBlocked()).isTrue();
        assertThat(dictPreflight.getRequiresAcknowledgement()).isTrue();
        assertThat(dictPreflight.getRisk().getSummary()).isEqualTo("Used by 1 SLA rule");

        DecisionFieldPreflightRequest permissionRequest = new DecisionFieldPreflightRequest();
        permissionRequest.setFieldRef("record.data.wd_req_type");
        permissionRequest.setAction("CHANGE_PERMISSION");
        permissionRequest.setNextPermission("manager.visible");
        DecisionFieldPreflightDTO permissionPreflight = service.preflightFieldChange(permissionRequest);

        assertThat(permissionPreflight.getAction()).isEqualTo("CHANGE_PERMISSION");
        assertThat(permissionPreflight.getNextPermission()).isEqualTo("manager.visible");
        assertThat(permissionPreflight.getBlocked()).isTrue();

        DecisionFieldPreflightRequest virtualRequest = new DecisionFieldPreflightRequest();
        virtualRequest.setFieldRef("record.data.wd_req_type");
        virtualRequest.setAction("CHANGE_VIRTUAL_SOURCE");
        virtualRequest.setNextSourceRef("virtual.leave_request_summary.v2");
        DecisionFieldPreflightDTO virtualPreflight = service.preflightFieldChange(virtualRequest);

        assertThat(virtualPreflight.getAction()).isEqualTo("CHANGE_VIRTUAL_SOURCE");
        assertThat(virtualPreflight.getNextSourceRef()).isEqualTo("virtual.leave_request_summary.v2");
        assertThat(virtualPreflight.getReferences()).containsExactly(ref);
    }

    @Test
    void preflightFieldChangeSupportsDataTypeChangesAndLegacyAlias() {
        DecisionImpactRefDTO ref = new DecisionImpactRefDTO();
        ref.setSourceType("DECISION_VERSION");
        ref.setSourceCode("approval_routing");
        ref.setSourceName("Approval routing");
        ref.setTargetPath("process.nodeId");
        when(usageIndexService.findFieldRefs("process.nodeId")).thenReturn(List.of(ref));

        DecisionFieldPreflightRequest blockedRequest = new DecisionFieldPreflightRequest();
        blockedRequest.setFieldRef("process.nodeId");
        blockedRequest.setAction("CHANGE_DATA_TYPE");
        blockedRequest.setCurrentDataType("string");
        blockedRequest.setNextDataType("decimal");

        DecisionFieldPreflightDTO blocked = service.preflightFieldChange(blockedRequest);

        assertThat(blocked.getAction()).isEqualTo("CHANGE_DATA_TYPE");
        assertThat(blocked.getCurrentDataType()).isEqualTo("string");
        assertThat(blocked.getNextDataType()).isEqualTo("decimal");
        assertThat(blocked.getBlocked()).isTrue();
        assertThat(blocked.getRequiresAcknowledgement()).isTrue();
        assertThat(blocked.getReferences()).containsExactly(ref);

        DecisionFieldPreflightRequest allowedRequest = new DecisionFieldPreflightRequest();
        allowedRequest.setFieldRef("process.nodeId");
        allowedRequest.setAction("CHANGE_DATA_TYPE");
        allowedRequest.setCurrentDataType("string");
        allowedRequest.setNextDataType("decimal");
        allowedRequest.setImpactAcknowledged(true);
        allowedRequest.setNote("type migration approved");

        DecisionFieldPreflightDTO allowed = service.preflightFieldChange(allowedRequest);

        assertThat(allowed.getAction()).isEqualTo("CHANGE_DATA_TYPE");
        assertThat(allowed.getAllowed()).isTrue();
        assertThat(allowed.getBlocked()).isFalse();
        verify(impactAckService).recordAcknowledgement(
                eq("FIELD_TYPE_CHANGE"),
                eq("FIELD"),
                eq(null),
                eq(null),
                eq("process.nodeId"),
                eq("Used by 1 decision version"),
                any(DecisionFieldPreflightDTO.class),
                eq("type migration approved"));

        DecisionFieldPreflightRequest legacyAlias = new DecisionFieldPreflightRequest();
        legacyAlias.setFieldRef("process.nodeId");
        legacyAlias.setAction("CHANGE_TYPE");
        legacyAlias.setCurrentDataType("string");
        legacyAlias.setNextDataType("string");

        DecisionFieldPreflightDTO noOp = service.preflightFieldChange(legacyAlias);

        assertThat(noOp.getAction()).isEqualTo("CHANGE_DATA_TYPE");
        assertThat(noOp.getAllowed()).isTrue();
        assertThat(noOp.getBlocked()).isFalse();
        assertThat(noOp.getRequiresAcknowledgement()).isFalse();
        assertThat(noOp.getRisk().getSummary()).isEqualTo("No schema type change detected");
    }
}
