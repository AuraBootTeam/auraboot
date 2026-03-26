package com.auraboot.module.mrp.engine;

import com.auraboot.module.mrp.dto.MrpExceptionMessage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MrpExceptionReporter {

    private final List<MrpExceptionMessage> exceptions = new ArrayList<>();

    public void reportPastDue(Long materialId, String materialName, LocalDate orderDate, LocalDate needDate) {
        exceptions.add(MrpExceptionMessage.builder()
            .materialId(materialId).materialName(materialName)
            .type("past_due").severity("critical")
            .description(String.format("Order date %s is in the past. Need date: %s", orderDate, needDate))
            .suggestedAction("Expedite order or find alternative supply")
            .build());
    }

    public void reportLongLeadTime(Long materialId, String materialName, int leadTimeDays) {
        exceptions.add(MrpExceptionMessage.builder()
            .materialId(materialId).materialName(materialName)
            .type("long_lead_time").severity("warning")
            .description(String.format("Lead time is %d days (%.1f weeks)", leadTimeDays, leadTimeDays / 7.0))
            .suggestedAction("Place order early or find local alternative supplier")
            .build());
    }

    public void reportShortage(Long materialId, String materialName, BigDecimal netDemand) {
        exceptions.add(MrpExceptionMessage.builder()
            .materialId(materialId).materialName(materialName)
            .type("shortage").severity("critical")
            .description(String.format("Material shortage: need %.2f units", netDemand))
            .suggestedAction("Check alternative materials or increase safety stock")
            .build());
    }

    public List<MrpExceptionMessage> getExceptions() {
        return Collections.unmodifiableList(exceptions);
    }

    public void clear() {
        exceptions.clear();
    }
}
