package com.auraboot.framework.im.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Member info returned by GET /api/im/conversations/{id}/members.
 * IM owns membership and human identity; the optional agent directory adds agent details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConversationMemberInfo {
    private String memberType;   // human | agent
    private Long memberId;       // user ID or agent definition ID
    private Long userId;         // backward compat alias for memberId (human members)
    private String name;
    private String displayName;
    private String avatarUrl;
    private String role;
    // Agent-specific fields (null for human members)
    private String agentCode;
    private String employeeTitle;
    // Only emitted when an agent membership cannot be used without the optional module.
    private Boolean agentAvailable;
}
