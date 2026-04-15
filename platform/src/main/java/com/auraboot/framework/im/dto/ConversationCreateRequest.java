package com.auraboot.framework.im.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ConversationCreateRequest {

    @NotNull
    private String type; // private | group | bot

    private String name; // required for GROUP

    // Optional — creator is always auto-added as OWNER.
    // May be empty when creating a self-only group (e.g. personal notes group).
    private List<Long> memberIds;

    // Agent IDs to add as agent members (for AI group chat)
    private List<Long> agentIds;

    private String boundModelCode; // required when type=object
    private Long boundRecordId;    // required when type=object
}
