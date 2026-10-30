package com.auraboot.framework.workbench.service;

import org.springframework.stereotype.Component;
import java.time.Instant;

/** Host time boundary for deterministic calendar/retention tests without changing database or system clocks. */
@Component
public class AnnouncementReminderClock {
    public Instant now() {return Instant.now();}
}
