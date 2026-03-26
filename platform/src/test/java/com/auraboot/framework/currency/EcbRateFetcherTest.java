package com.auraboot.framework.currency;

import com.auraboot.framework.currency.dao.entity.ExchangeRate;
import com.auraboot.framework.currency.dao.mapper.ExchangeRateMapper;
import com.auraboot.framework.currency.service.impl.EcbRateFetcher;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.mapper.TenantMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EcbRateFetcher}.
 *
 * <p>HTTP call is overridden via a Spy to return a hard-coded XML response —
 * no real network access, no Spring context required.
 *
 * <p>Strictness is set to LENIENT because some tests call {@code parseXml} directly
 * and do not exercise the {@code fetchXml} stub set up in {@code setUp}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EcbRateFetcherTest {

    private static final String SAMPLE_ECB_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01"
                             xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
              <gesmes:subject>Reference rates</gesmes:subject>
              <gesmes:Sender><gesmes:name>European Central Bank</gesmes:name></gesmes:Sender>
              <Cube>
                <Cube time="2026-03-18">
                  <Cube currency="usd" rate="1.0842"/>
                  <Cube currency="cny" rate="7.8567"/>
                  <Cube currency="jpy" rate="161.23"/>
                  <Cube currency="gbp" rate="0.8321"/>
                </Cube>
              </Cube>
            </gesmes:Envelope>
            """;

    @Mock
    private ExchangeRateMapper exchangeRateMapper;

    @Mock
    private TenantMapper tenantMapper;

    @Spy
    @InjectMocks
    private EcbRateFetcher ecbRateFetcher;

    @BeforeEach
    void setUp() throws Exception {
        // Override HTTP call — return sample XML without network access.
        // LENIENT strictness means tests that don't call fetchAndSave() won't fail
        // with UnnecessaryStubbingException.
        doReturn(SAMPLE_ECB_XML).when(ecbRateFetcher).fetchXml();
    }

    // ─── XML parsing tests ────────────────────────────────────────────────────

    @Test
    void parseXml_shouldExtractCorrectNumberOfEntries() throws Exception {
        List<EcbRateFetcher.RateEntry> entries = ecbRateFetcher.parseXml(SAMPLE_ECB_XML);

        assertThat(entries).hasSize(4);
    }

    @Test
    void parseXml_shouldExtractCorrectDate() throws Exception {
        List<EcbRateFetcher.RateEntry> entries = ecbRateFetcher.parseXml(SAMPLE_ECB_XML);

        assertThat(entries).allMatch(e -> e.date().equals(LocalDate.of(2026, 3, 18)));
    }

    @Test
    void parseXml_shouldExtractUsdRate() throws Exception {
        List<EcbRateFetcher.RateEntry> entries = ecbRateFetcher.parseXml(SAMPLE_ECB_XML);

        EcbRateFetcher.RateEntry usd = entries.stream()
                .filter(e -> "usd".equals(e.currency()))
                .findFirst()
                .orElseThrow();

        assertThat(usd.rate()).isEqualByComparingTo(new BigDecimal("1.0842"));
    }

    @Test
    void parseXml_shouldExtractCnyRate() throws Exception {
        List<EcbRateFetcher.RateEntry> entries = ecbRateFetcher.parseXml(SAMPLE_ECB_XML);

        EcbRateFetcher.RateEntry cny = entries.stream()
                .filter(e -> "cny".equals(e.currency()))
                .findFirst()
                .orElseThrow();

        assertThat(cny.rate()).isEqualByComparingTo(new BigDecimal("7.8567"));
    }

    @Test
    void parseXml_emptyXml_shouldReturnEmptyList() throws Exception {
        String emptyEnvelope = """
                <?xml version="1.0" encoding="UTF-8"?>
                <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01">
                  <Cube/>
                </gesmes:Envelope>
                """;

        List<EcbRateFetcher.RateEntry> entries = ecbRateFetcher.parseXml(emptyEnvelope);

        assertThat(entries).isEmpty();
    }

    // ─── fetchAndSave integration (with mocked DB) ───────────────────────────

    @Test
    void fetchAndSave_shouldInsertRatesForAllActiveTenants() {
        Tenant tenant1 = tenant(100L);
        Tenant tenant2 = tenant(200L);
        when(tenantMapper.findByStatus("active")).thenReturn(List.of(tenant1, tenant2));
        // No existing record → insert path
        when(exchangeRateMapper.selectOne(any())).thenReturn(null);

        int saved = ecbRateFetcher.fetchAndSave();

        // 4 currencies × 2 tenants = 8 inserts
        assertThat(saved).isEqualTo(8);
        verify(exchangeRateMapper, times(8)).insert(any(ExchangeRate.class));
    }

    @Test
    void fetchAndSave_shouldUpdateExistingEcbRecord() {
        Tenant tenant = tenant(100L);
        when(tenantMapper.findByStatus("active")).thenReturn(List.of(tenant));

        // First selectOne call → return an existing record (update path)
        // Subsequent calls → return null (insert path)
        ExchangeRate existing = new ExchangeRate();
        existing.setId(1L);
        existing.setRate(new BigDecimal("1.0800")); // stale rate
        when(exchangeRateMapper.selectOne(any()))
                .thenReturn(existing)    // first call → update
                .thenReturn(null)        // remaining → insert
                .thenReturn(null)
                .thenReturn(null);

        ecbRateFetcher.fetchAndSave();

        // updateById called at least once
        verify(exchangeRateMapper, atLeastOnce()).updateById(any(ExchangeRate.class));
    }

    @Test
    void fetchAndSave_noActiveTenants_shouldReturnZero() {
        when(tenantMapper.findByStatus("active")).thenReturn(List.of());

        int saved = ecbRateFetcher.fetchAndSave();

        assertThat(saved).isZero();
        verify(exchangeRateMapper, never()).insert(any(ExchangeRate.class));
    }

    @Test
    void fetchAndSave_insertedRecords_shouldHaveEcbSource() {
        Tenant tenant = tenant(100L);
        when(tenantMapper.findByStatus("active")).thenReturn(List.of(tenant));
        when(exchangeRateMapper.selectOne(any())).thenReturn(null);

        ecbRateFetcher.fetchAndSave();

        ArgumentCaptor<ExchangeRate> captor = ArgumentCaptor.forClass(ExchangeRate.class);
        verify(exchangeRateMapper, atLeastOnce()).insert(captor.capture());
        assertThat(captor.getAllValues())
                .allMatch(r -> "ecb".equals(r.getSource()))
                .allMatch(r -> "eur".equals(r.getBaseCurrency()))
                .allMatch(r -> r.getTenantId() != null)
                .allMatch(r -> r.getRate() != null);
    }

    @Test
    void scheduledFetch_whenDisabled_shouldNotCallFetchXml() throws Exception {
        // ecbEnabled field is false by default (not injected via Spring in a plain unit test).
        // clearInvocations ensures the setUp() stub doesn't count as a prior call.
        clearInvocations(ecbRateFetcher);

        ecbRateFetcher.scheduledFetch();

        verify(ecbRateFetcher, never()).fetchXml();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static Tenant tenant(Long id) {
        Tenant t = new Tenant();
        t.setId(id);
        t.setStatus("active");
        return t;
    }
}
