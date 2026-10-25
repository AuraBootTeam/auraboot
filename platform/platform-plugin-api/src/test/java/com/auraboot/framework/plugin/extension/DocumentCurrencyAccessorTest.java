package com.auraboot.framework.plugin.extension;

import java.math.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentCurrencyAccessorTest {
    private static final String CNY="01M5DB6RC8P9DKDWT1HKSDCR7R",USD="01M5DB6RC8P9DKDWT1HKSDCR7U";
    private static final LocalDate DATE=LocalDate.of(2026,10,25);
    private DocumentCurrencyAccessor.Snapshot create(String pid,String code,String base,String baseCode,BigDecimal rate,int scale,RoundingMode rounding){
        return new DocumentCurrencyAccessor.Snapshot(pid,code,base,baseCode,rate,DATE,scale,rounding);
    }
    @Test void nonIdentityRetainsExactSourceRateAndPolicyFacts(){
        var rate=new BigDecimal("7.123456789012");var snapshot=create(USD,"USD",CNY,"CNY",rate,3,RoundingMode.HALF_EVEN);
        assertSame(rate,snapshot.rate());assertEquals(3,snapshot.amountScale());assertEquals(DATE,snapshot.valuationDate());
        assertEquals(CNY,snapshot.baseCurrencyPid());assertEquals("CNY",snapshot.baseCurrencyCode());
    }
    @Test void sameCurrencyRequiresRateOneAndMatchingPublicIdentity(){
        create(CNY,"CNY",CNY,"CNY",BigDecimal.ONE,2,RoundingMode.HALF_UP);
        create(USD,"USD",CNY,"CNY",BigDecimal.ONE,2,RoundingMode.HALF_UP);
        assertThrows(IllegalArgumentException.class,()->create(CNY,"CNY",CNY,"CNY",BigDecimal.TEN,2,RoundingMode.HALF_UP));
        assertThrows(IllegalArgumentException.class,()->create(USD,"CNY",CNY,"CNY",BigDecimal.TEN,2,RoundingMode.HALF_UP));
    }
    @Test void missingReferencesRateAndInvalidPoliciesAreRejected(){
        for(String pid:Arrays.asList(null,"CNY",""))assertThrows(IllegalArgumentException.class,()->create(pid,"CNY",CNY,"CNY",BigDecimal.ONE,2,RoundingMode.HALF_UP));
        for(BigDecimal rate:Arrays.asList(null,BigDecimal.ZERO,BigDecimal.ONE.negate()))assertThrows(IllegalArgumentException.class,()->create(USD,"USD",CNY,"CNY",rate,2,RoundingMode.HALF_UP));
        for(int scale:List.of(-1,7))assertThrows(IllegalArgumentException.class,()->create(USD,"USD",CNY,"CNY",BigDecimal.TEN,scale,RoundingMode.HALF_UP));
        for(RoundingMode mode:Arrays.asList(null,RoundingMode.DOWN))assertThrows(IllegalArgumentException.class,()->create(USD,"USD",CNY,"CNY",BigDecimal.TEN,2,mode));
    }
    @Test void commandContextExposesOnlyTheTypedServerCapability(){
        DocumentCurrencyAccessor cap=(pid,base,rate,date)->create(CNY,"CNY",CNY,"CNY",rate,2,RoundingMode.HALF_UP);
        var context=new CommandHandlerExtension.CommandContext(1L,"plugin","ns","command","model","record",Map.of(),Map.of(DocumentCurrencyAccessor.SETTINGS_KEY,cap),false);
        assertSame(cap,context.documentCurrencyAccessor());
        var invalid=new CommandHandlerExtension.CommandContext(1L,"plugin","ns","command","model","record",Map.of(),Map.of(DocumentCurrencyAccessor.SETTINGS_KEY,"forged"),false);
        assertNull(invalid.documentCurrencyAccessor());
    }
}
