package com.auraboot.framework.user.dto;

public record EmailLinkStatusResponse(boolean linked, boolean verified, String maskedEmail) {
}
