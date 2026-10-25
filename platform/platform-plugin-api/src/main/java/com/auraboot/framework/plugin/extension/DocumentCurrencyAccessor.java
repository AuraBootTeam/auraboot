package com.auraboot.framework.plugin.extension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/** Read-only tenant currency policy. The integration caller owns source-document authenticity. */
public interface DocumentCurrencyAccessor {
    String SETTINGS_KEY="__documentCurrencyAccessor";
    String OPT_IN_HANDLER_PARAM="documentCurrencyRead";

    /** Validates an authoritative source rate without replacing it with another rate lookup. */
    Snapshot verifySourceRate(String currencyPid,String sourceBaseCurrencyCode,BigDecimal sourceRate,LocalDate valuationDate);

    enum Failure {
        SERVICE_UNAVAILABLE,CURRENCY_UNAVAILABLE,BASE_CURRENCY_UNCONFIGURED,
        BASE_CURRENCY_AMBIGUOUS,BASE_CURRENCY_MISMATCH,CURRENCY_POLICY_INVALID,RATE_INVALID
    }

    final class Unavailable extends IllegalStateException {
        private final Failure failure;
        public Unavailable(Failure failure){super(Objects.requireNonNull(failure).toString());this.failure=failure;}
        public Unavailable(Failure failure,Throwable cause){super(Objects.requireNonNull(failure).toString(),cause);this.failure=failure;}
        public Failure failure(){return failure;}
    }

    record Snapshot(String currencyPid,String currencyCode,String baseCurrencyPid,String baseCurrencyCode,
                    BigDecimal rate,LocalDate valuationDate,int amountScale,RoundingMode roundingMode) {
        public Snapshot {
            if(!publicPid(currencyPid) || !publicPid(baseCurrencyPid)
                    || currencyCode==null || !currencyCode.matches("[A-Z]{3}")
                    || baseCurrencyCode==null || !baseCurrencyCode.matches("[A-Z]{3}")
                    || rate==null || rate.signum()<=0 || valuationDate==null
                    || amountScale<0 || amountScale>6
                    || !(roundingMode==RoundingMode.HALF_UP || roundingMode==RoundingMode.HALF_EVEN))
                throw new IllegalArgumentException("DOCUMENT_CURRENCY_SNAPSHOT_INVALID");
            if(currencyPid.equals(baseCurrencyPid)!=currencyCode.equals(baseCurrencyCode)
                    || currencyPid.equals(baseCurrencyPid) && rate.compareTo(BigDecimal.ONE)!=0)
                throw new IllegalArgumentException("DOCUMENT_CURRENCY_SNAPSHOT_INVALID");
        }
        private static boolean publicPid(String value){return value!=null && value.matches("[A-Za-z0-9]{26}");}
    }
}
