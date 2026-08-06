package com.auraboot.plugins.crm.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden-standard coverage for {@link OmniRoutingEngine}:
 * happy path, sad path, edge case, corner case (CRM gap #16).
 */
class OmniRoutingEngineTest {

    // ---------- happy path ----------
    @Test
    void emailNonVipRoutesToEmailQueueNormal() {
        var r = OmniRoutingEngine.route("email", false);
        assertEquals("email_queue", r.queue());
        assertEquals("normal", r.priority());
    }

    // ---------- sad path (unknown channel falls back) ----------
    @Test
    void unknownChannelRoutesToGeneralQueue() {
        var r = OmniRoutingEngine.route("carrier_pigeon", false);
        assertEquals("general_queue", r.queue());
        assertEquals("normal", r.priority());
    }

    @Test
    void nullChannelRoutesToGeneralQueue() {
        var r = OmniRoutingEngine.route(null, false);
        assertEquals("general_queue", r.queue());
    }

    // ---------- edge case ----------
    @Test
    void vipNonPhoneGetsHighPriority() {
        var r = OmniRoutingEngine.route("chat", true);
        assertEquals("chat_queue", r.queue());
        assertEquals("high", r.priority());
    }

    // ---------- corner cases ----------
    @Test
    void phoneIsAlwaysUrgentEvenNonVip() {
        var r = OmniRoutingEngine.route("phone", false);
        assertEquals("phone_queue", r.queue());
        assertEquals("urgent", r.priority());
    }

    @Test
    void phoneVipStaysUrgentNotHigh() {
        // phone synchronous beats VIP-high
        var r = OmniRoutingEngine.route("PHONE", true);
        assertEquals("phone_queue", r.queue());
        assertEquals("urgent", r.priority());
    }

    @Test
    void channelIsCaseInsensitiveAndTrimmed() {
        var r = OmniRoutingEngine.route("  Social  ", true);
        assertEquals("social_queue", r.queue());
        assertEquals("high", r.priority());
    }
}
