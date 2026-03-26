package com.auraboot.module.mrp.engine;

import com.auraboot.module.mrp.port.BomQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class LeadTimeService {

    private final BomQueryPort bomPort;
    private static final int LONG_LEAD_TIME_THRESHOLD_WEEKS = 12;

    /**
     * Calculate order release date by offsetting need date by lead time.
     */
    public LocalDate calculateOrderDate(Long materialId, LocalDate needDate) {
        int leadTimeDays = bomPort.getLeadTime(materialId);
        return needDate.minusDays(leadTimeDays);
    }

    /**
     * Check if material has long lead time (PCBA: IC >12 weeks is common).
     */
    public boolean isLongLeadTime(Long materialId) {
        int leadTimeDays = bomPort.getLeadTime(materialId);
        return leadTimeDays > LONG_LEAD_TIME_THRESHOLD_WEEKS * 7;
    }

    public int getLeadTimeDays(Long materialId) {
        return bomPort.getLeadTime(materialId);
    }
}
