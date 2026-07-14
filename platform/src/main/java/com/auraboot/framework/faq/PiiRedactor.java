package com.auraboot.framework.faq;

import java.util.regex.Pattern;

/**
 * Removes structured personal data from text on its way into a published FAQ.
 *
 * <p>An FAQ distilled from a real conversation carries whatever the customer typed — an order line
 * with a phone number, a password reset naming an email, an ID quoted back for verification. Once
 * published, that pair is read to every future visitor who asks a similar question. The distilled
 * answer is reusable; the phone number in it is not, and must not travel with it.
 *
 * <p>This is the deterministic half of the defence, and it runs at the earliest point the data
 * exists as a candidate — so a candidate is redacted before anyone reviews it, and the reviewer
 * never has to catch what a regex already catches. It covers the shapes a regex can be trusted on
 * (phone, email, national ID, bank card). It deliberately does not attempt names or free-form
 * account handles: those have no reliable shape, and a regex that tried would either miss most of
 * them or shred ordinary words. Those are the extraction prompt's job (it is told not to carry
 * them), and the reviewer's.
 *
 * <p>Order matters. Email is redacted first, so a phone number that is the local part of an address
 * is removed whole rather than leaving a bare domain. Then national ID before bank card (an 18-digit
 * ID is also a digit run a card pattern would claim); the phone and card patterns are anchored so
 * they do not bite into a longer number another pattern already replaced.
 */
public final class PiiRedactor {

    private PiiRedactor() {}

    // China mainland ID: 17 digits then a check digit that may be X. Anchored so it is not a slice
    // of a longer digit run.
    private static final Pattern NATIONAL_ID = Pattern.compile("(?<![0-9Xx])[0-9]{17}[0-9Xx](?![0-9Xx])");
    // Bank card: 13–19 digits. Runs AFTER national ID so an 18-digit ID is not taken as a card.
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{13,19}(?!\\d)");
    // China mobile: 1, then 3–9, then 9 more digits. Anchored against surrounding digits.
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");

    /**
     * Replace structured PII with a labelled placeholder. Null in, null out; the label stays so a
     * reader (and a test) can see that something was removed rather than that text simply went
     * missing.
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // Email first: a phone number that is the local part of an address is redacted whole rather
        // than leaving a bare domain. Then national ID before bank card (an 18-digit ID is also a
        // digit run a card pattern would claim), then phone.
        String out = EMAIL.matcher(text).replaceAll("[邮箱]");
        out = NATIONAL_ID.matcher(out).replaceAll("[身份证]");
        out = BANK_CARD.matcher(out).replaceAll("[银行卡]");
        out = PHONE.matcher(out).replaceAll("[手机号]");
        return out;
    }
}
