package com.auraboot.framework.plugin.extension;

/** Host-owned verification for the current authenticated account; never grants membership or roles. */
public interface VerifiedPhoneGrantAccessor {
    String SETTINGS_KEY = "__verifiedPhoneGrantAccessor";
    String OPT_IN_HANDLER_PARAM = "verifiedPhoneGrant";

    /** Consumes fresh WeChat login/phone codes once and returns a verified E.164 phone. */
    String verifyCurrentPhone(String loginCode, String phoneCode);
}
