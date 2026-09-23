package com.auraboot.framework.auth.dto;

import lombok.Data;

/**
 * WeChat invitation join: an unbound WeChat account plus a valid
 * tenant invitation code auto-provisions the platform user, joins the tenant and
 * assigns only the roles stored on the server-side invitation.
 */
@Data
public class WechatInvitationJoinRequest {
    /** wx.login() code — single use, exchanged exactly once. */
    private String code;
    /** Tenant invitation code. */
    private String inviteCode;
    /** Optional profile display name. */
    private String displayName;
}
