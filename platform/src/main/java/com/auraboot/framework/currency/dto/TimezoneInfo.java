package com.auraboot.framework.currency.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing timezone information.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TimezoneInfo {

    /** IANA timezone ID, e.g. "Asia/Shanghai" */
    private String id;

    /** Display name, e.g. "China Standard Time" */
    private String displayName;

    /** UTC offset string, e.g. "+08:00" */
    private String utcOffset;

    /** Offset in total seconds from UTC */
    private int offsetSeconds;
}
