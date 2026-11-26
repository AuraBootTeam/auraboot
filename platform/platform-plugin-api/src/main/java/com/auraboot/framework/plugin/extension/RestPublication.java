package com.auraboot.framework.plugin.extension;

/** Opt-in lookup of one published document from a named path variable on a public GET. */
public record RestPublication(String kind, String pathVariable) {}
