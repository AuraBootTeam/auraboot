package com.auraboot.framework.faq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PiiRedactor removes structured personal data before it can be published")
class PiiRedactorTest {

    @Test
    @DisplayName("a China mobile number is replaced, and its digits are gone")
    void redactsPhone() {
        String out = PiiRedactor.redact("请把货发到我这，电话13800138000，谢谢");
        assertThat(out).contains("[手机号]").doesNotContain("13800138000");
    }

    @Test
    @DisplayName("an email address is replaced whole")
    void redactsEmail() {
        String out = PiiRedactor.redact("账号 alice.wong+cs@example.com 已重置");
        assertThat(out).contains("[邮箱]").doesNotContain("alice.wong").doesNotContain("example.com");
    }

    @Test
    @DisplayName("an 18-digit national ID is redacted as an ID, not mistaken for a bank card")
    void redactsNationalIdBeforeCard() {
        String out = PiiRedactor.redact("身份证110101199003074577核对无误");
        assertThat(out).contains("[身份证]").doesNotContain("110101199003074577").doesNotContain("[银行卡]");
    }

    @Test
    @DisplayName("an ID ending in X is redacted too")
    void redactsNationalIdWithXCheckDigit() {
        String out = PiiRedactor.redact("证件号11010119900307457X");
        assertThat(out).contains("[身份证]").doesNotContain("11010119900307457X");
    }

    @Test
    @DisplayName("a bank card number is replaced")
    void redactsBankCard() {
        String out = PiiRedactor.redact("退款打到卡6222020200112233445就行");
        assertThat(out).contains("[银行卡]").doesNotContain("6222020200112233445");
    }

    @Test
    @DisplayName("a phone number in the local part of an email is redacted with the email, not left as a bare domain")
    void redactsPhoneShapedEmailWhole() {
        String out = PiiRedactor.redact("联系13800138000@example.com");
        assertThat(out).contains("[邮箱]").doesNotContain("13800138000").doesNotContain("example.com");
    }

    @Test
    @DisplayName("several kinds of PII in one line are all removed")
    void redactsMixed() {
        String out = PiiRedactor.redact("张三 电话13912345678 邮箱zhangsan@x.cn 卡号6222001122334455");
        assertThat(out)
                .contains("[手机号]").contains("[邮箱]").contains("[银行卡]")
                .doesNotContain("13912345678").doesNotContain("zhangsan@x.cn").doesNotContain("6222001122334455");
    }

    @Test
    @DisplayName("ordinary text with no PII is returned unchanged — the guard does not shred normal answers")
    void leavesCleanTextAlone() {
        String clean = "退货政策是37个月，从购买日起算。";
        assertThat(PiiRedactor.redact(clean)).isEqualTo(clean);
    }

    @Test
    @DisplayName("a 7-digit order number is not a phone number and is left alone")
    void doesNotOverMatchShortNumbers() {
        String out = PiiRedactor.redact("您的订单号是1234567，已发货");
        assertThat(out).isEqualTo("您的订单号是1234567，已发货");
    }

    @Test
    @DisplayName("null and empty pass through")
    void nullAndEmpty() {
        assertThat(PiiRedactor.redact(null)).isNull();
        assertThat(PiiRedactor.redact("")).isEmpty();
    }
}
