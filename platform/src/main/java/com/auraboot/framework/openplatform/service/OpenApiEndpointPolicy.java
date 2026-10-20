package com.auraboot.framework.openplatform.service;

/** Endpoint invocation permission never replaces its resource/query/action grants. */
public record OpenApiEndpointPolicy(boolean invoke) { }
