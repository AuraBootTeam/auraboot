package com.auraboot.framework.commerce.publicapi.dto;

import java.util.List;

public record CreateStorefrontCartRequest(List<StorefrontCartLineInput> lines) {}
