package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.currency.spi.CurrencyConversionSpi;
import com.auraboot.framework.plugin.extension.DocumentCurrencyAccessor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hermetic capability boundary; persistence and finance policy require separate real-stack proof. */
class DocumentCurrencyAccessorImplTest {
    private static final String CURRENCY="01M5DB6RC8P9DKDWT1HKSDCR7R";
    private static final LocalDate DATE=LocalDate.of(2026,10,25);
    @AfterEach void clear(){MetaContext.clear();}
    private Map<String,Object> settings(){return new HashMap<>(Map.of(DocumentCurrencyAccessor.OPT_IN_HANDLER_PARAM,true));}
    private DocumentCurrencyAccessor.Snapshot snapshot(){return new DocumentCurrencyAccessor.Snapshot(CURRENCY,"CNY",CURRENCY,"CNY",BigDecimal.ONE,DATE,2,RoundingMode.HALF_UP);}
    private DocumentCurrencyAccessor install(CurrencyConversionSpi service){
        MetaContext.setContext(1L,2L,"buyer-a","Buyer");var settings=settings();DocumentCurrencyAccessorImpl.install(settings,1L,service);
        return (DocumentCurrencyAccessor)settings.get(DocumentCurrencyAccessor.SETTINGS_KEY);
    }
    @Test void delegatesOnlyTheBoundTenantAndExactReferenceDate(){
        var service=mock(CurrencyConversionSpi.class);var expected=snapshot();when(service.verifyDocumentSourceCurrency(CURRENCY,"CNY",BigDecimal.ONE,DATE)).thenReturn(expected);
        assertSame(expected,install(service).verifySourceRate(CURRENCY,"CNY",BigDecimal.ONE,DATE));verify(service).verifyDocumentSourceCurrency(CURRENCY,"CNY",BigDecimal.ONE,DATE);verifyNoMoreInteractions(service);
    }
    @Test void forgedAccessorIsRemovedUnlessMetadataHasLiteralOptIn(){
        var service=mock(CurrencyConversionSpi.class);
        for(Object flag:List.of(false,"true",1)){
            var settings=settings();settings.put(DocumentCurrencyAccessor.OPT_IN_HANDLER_PARAM,flag);settings.put(DocumentCurrencyAccessor.SETTINGS_KEY,new Object());
            DocumentCurrencyAccessorImpl.install(settings,1L,service);assertFalse(settings.containsKey(DocumentCurrencyAccessor.SETTINGS_KEY));
        }
        verifyNoInteractions(service);
    }
    @Test void missingProviderFailsWithoutFallback(){
        var exception=assertThrows(DocumentCurrencyAccessor.Unavailable.class,()->DocumentCurrencyAccessorImpl.install(settings(),1L,null));
        assertEquals(DocumentCurrencyAccessor.Failure.SERVICE_UNAVAILABLE,exception.failure());
    }
    @Test void changedTenantOrMissingIdentityCannotReuseCapability(){
        var service=mock(CurrencyConversionSpi.class);var cap=install(service);
        MetaContext.setContext(2L,3L,"buyer-b","Other");assertThrows(SecurityException.class,()->cap.verifySourceRate(CURRENCY,"CNY",BigDecimal.ONE,DATE));
        MetaContext.clear();assertThrows(SecurityException.class,()->cap.verifySourceRate(CURRENCY,"CNY",BigDecimal.ONE,DATE));verifyNoInteractions(service);
    }
    @Test void codeInsteadOfPidAndMissingDateRejectBeforeProvider(){
        var service=mock(CurrencyConversionSpi.class);var cap=install(service);
        assertThrows(IllegalArgumentException.class,()->cap.verifySourceRate("CNY","CNY",BigDecimal.ONE,DATE));assertThrows(IllegalArgumentException.class,()->cap.verifySourceRate(CURRENCY,"CNY",BigDecimal.ONE,null));verifyNoInteractions(service);
    }
    @Test void providerCannotReturnAnotherCurrencyOrValuationDate(){
        var service=mock(CurrencyConversionSpi.class);var cap=install(service);when(service.verifyDocumentSourceCurrency(CURRENCY,"CNY",BigDecimal.ONE,DATE)).thenReturn(null);
        assertThrows(IllegalStateException.class,()->cap.verifySourceRate(CURRENCY,"CNY",BigDecimal.ONE,DATE));
        when(service.verifyDocumentSourceCurrency(CURRENCY,"CNY",BigDecimal.ONE,DATE)).thenReturn(new DocumentCurrencyAccessor.Snapshot(CURRENCY,"CNY",CURRENCY,"CNY",BigDecimal.ONE,DATE.plusDays(1),2,RoundingMode.HALF_UP));
        assertThrows(IllegalStateException.class,()->cap.verifySourceRate(CURRENCY,"CNY",BigDecimal.ONE,DATE));
    }
    @Test void businessRateFailureIsTranslatedOnceWithItsCauseRetained(){
        var service=mock(CurrencyConversionSpi.class);var cap=install(service);
        var cause=new com.auraboot.framework.exception.BusinessException("Synthetic absent rate");when(service.verifyDocumentSourceCurrency(CURRENCY,"CNY",BigDecimal.ONE,DATE)).thenThrow(cause);
        var error=assertThrows(DocumentCurrencyAccessor.Unavailable.class,()->cap.verifySourceRate(CURRENCY,"CNY",BigDecimal.ONE,DATE));
        assertEquals(DocumentCurrencyAccessor.Failure.CURRENCY_POLICY_INVALID,error.failure());assertSame(cause,error.getCause());
    }
    @Test void providerCannotReturnAnotherPublicCurrencyReference(){
        var service=mock(CurrencyConversionSpi.class);var cap=install(service);String other="01M5DB6RC8P9DKDWT1HKSDCR7U";
        when(service.verifyDocumentSourceCurrency(CURRENCY,"CNY",BigDecimal.ONE,DATE)).thenReturn(new DocumentCurrencyAccessor.Snapshot(other,"USD",other,"USD",BigDecimal.ONE,DATE,2,RoundingMode.HALF_UP));
        assertThrows(IllegalStateException.class,()->cap.verifySourceRate(CURRENCY,"CNY",BigDecimal.ONE,DATE));
    }

    @Test void providerCannotReplaceTheAuthoritativeRate(){
        var service=mock(CurrencyConversionSpi.class);var cap=install(service);String usd="01M5DB6RC8P9DKDWT1HKSDCR7U";
        var input=new BigDecimal("7.25");when(service.verifyDocumentSourceCurrency(usd,"CNY",input,DATE)).thenReturn(new DocumentCurrencyAccessor.Snapshot(usd,"USD",CURRENCY,"CNY",new BigDecimal("7.2"),DATE,2,RoundingMode.HALF_UP));
        assertThrows(IllegalStateException.class,()->cap.verifySourceRate(usd,"CNY",input,DATE));
    }
    @Test void sourceBaseAndPositiveRateAreRequiredBeforeCallingProvider(){
        var service=mock(CurrencyConversionSpi.class);var cap=install(service);
        assertThrows(IllegalArgumentException.class,()->cap.verifySourceRate(CURRENCY,"",BigDecimal.ONE,DATE));
        assertThrows(IllegalArgumentException.class,()->cap.verifySourceRate(CURRENCY,"CNY",BigDecimal.ZERO,DATE));verifyNoInteractions(service);
    }

}
