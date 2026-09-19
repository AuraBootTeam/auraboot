package com.auraboot.framework.auth.dto;

import lombok.Data;

import java.util.List;

/**
 * Pure-wechat school join (FR-077/078): an unbound WeChat account plus a valid
 * tenant invitation code auto-provisions the platform user, joins the tenant and
 * assigns the requested roles (e.g. xy_teacher) in one step.
 */
@Data
public class WechatJoinRequest {
    /** wx.login() code — single use, exchanged exactly once. */
    private String code;
    /** Tenant invitation code — the pointer to the school tenant. */
    private String inviteCode;
    /** Teacher real name (optional; WeChat nicknames are not trusted as real names). */
    private String realName;
    /** Role codes to assign in the tenant (optional; e.g. ["xy_teacher"]). */
    private List<String> roleCodes;
}
