package com.auraboot.framework.plugin.dto.imports;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A field-mask declaration shipped by a plugin in {@code config/fieldMasks.json} and imported into
 * {@code ab_field_mask_config}. Lets a plugin declare which of its fields are sensitive, how they
 * are masked, and who is exempt — including by permission code (capability-driven unmask, e.g.
 * {@code crm.account.contact_unmask} unmasks {@code crm_account_common.phone}).
 *
 * <p>Imported additively/idempotently: re-import upserts by (tenant, modelCode, fieldCode).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldMaskDefinitionDTO {

    /** Model whose field is masked, e.g. {@code crm_account_common}. */
    private String modelCode;

    /** Field code within the model, e.g. {@code phone}. */
    private String fieldCode;

    /** PHONE / EMAIL / ID_CARD / BANK_CARD / NAME / PARTIAL / FULL / CUSTOM / HASH. */
    private String maskType;

    /** CUSTOM: literal text; PARTIAL: "head,tail" e.g. "3,4". Optional. */
    private String maskPattern;

    @Builder.Default
    private String replacementChar = "*";

    @Builder.Default
    private Boolean applyToList = Boolean.TRUE;

    @Builder.Default
    private Boolean applyToDetail = Boolean.TRUE;

    @Builder.Default
    private Boolean applyToExport = Boolean.TRUE;

    @Builder.Default
    private Boolean enabled = Boolean.TRUE;

    /** Comma-separated role codes that see the unmasked value. Optional. */
    private String exemptRoles;

    /** Comma-separated permission codes that see the unmasked value (capability-driven). Optional. */
    private String exemptPermissionCodes;

    public boolean isValid() {
        return modelCode != null && !modelCode.isBlank()
                && fieldCode != null && !fieldCode.isBlank()
                && maskType != null && !maskType.isBlank();
    }
}
