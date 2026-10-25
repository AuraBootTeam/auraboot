package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.currency.spi.CurrencyConversionSpi;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.plugin.extension.DocumentCurrencyAccessor;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.Map;
import static com.auraboot.framework.plugin.extension.DocumentCurrencyAccessor.Failure;

/** Tenant-bound bridge; command payloads cannot install or widen this capability. */
public final class DocumentCurrencyAccessorImpl implements DocumentCurrencyAccessor {
    private final Long tenantId;
    private final CurrencyConversionSpi service;
    private DocumentCurrencyAccessorImpl(Long tenantId,CurrencyConversionSpi service){this.tenantId=tenantId;this.service=service;}

    public static void install(Map<String,Object> settings,Long tenantId,CurrencyConversionSpi service){
        settings.remove(SETTINGS_KEY);
        if(!Boolean.TRUE.equals(settings.get(OPT_IN_HANDLER_PARAM)))return;
        if(tenantId==null || tenantId<=0)throw new SecurityException("DOCUMENT_CURRENCY_TENANT_REQUIRED");
        if(service==null)throw new Unavailable(Failure.SERVICE_UNAVAILABLE);
        settings.put(SETTINGS_KEY,new DocumentCurrencyAccessorImpl(tenantId,service));
    }

    @Override public Snapshot verifySourceRate(String currencyPid,String sourceBaseCurrencyCode,BigDecimal sourceRate,LocalDate valuationDate){
        if(!MetaContext.exists() || !tenantId.equals(MetaContext.getCurrentTenantId()) || MetaContext.getCurrentUserPid()==null
                || MetaContext.getCurrentUserPid().isBlank())throw new SecurityException("DOCUMENT_CURRENCY_CONTEXT_MISMATCH");
        if(currencyPid==null || !currencyPid.matches("[A-Za-z0-9]{26}") || valuationDate==null
                || sourceBaseCurrencyCode==null || !sourceBaseCurrencyCode.matches("[A-Z]{3}")
                || sourceRate==null || sourceRate.signum()<=0)
            throw new IllegalArgumentException("DOCUMENT_CURRENCY_REQUEST_INVALID");
        Snapshot result;
        try {result=service.verifyDocumentSourceCurrency(currencyPid,sourceBaseCurrencyCode,sourceRate,valuationDate);}
        catch(BusinessException unavailable){
            // Expected host business rejection is translated once at the public plugin boundary; preserve the cause.
            throw new Unavailable(Failure.CURRENCY_POLICY_INVALID,unavailable);
        }
        if(result==null || !currencyPid.equals(result.currencyPid()) || !valuationDate.equals(result.valuationDate())
                || !sourceBaseCurrencyCode.equals(result.baseCurrencyCode()) || sourceRate.compareTo(result.rate())!=0)
            throw new IllegalStateException("DOCUMENT_CURRENCY_RESULT_MISMATCH");
        return result;
    }
}
