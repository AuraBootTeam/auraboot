package com.auraboot.framework.test.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixtureRequestTest {

    @Test
    void effectiveNamePrefersNameWhenPresent() {
        FixtureRequest request = new FixtureRequest();
        request.setName("records");
        request.setFixture("dashboard");

        assertEquals("records", request.getEffectiveName());
    }

    @Test
    void effectiveNameFallsBackToFixtureAlias() {
        FixtureRequest request = new FixtureRequest();
        request.setFixture("native_fields");

        assertEquals("native_fields", request.getEffectiveName());
    }
}
