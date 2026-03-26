package com.auraboot.framework.currency.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.currency.dao.entity.ExchangeRate;
import com.auraboot.framework.currency.dao.mapper.ExchangeRateMapper;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches daily EUR-based exchange rates from the European Central Bank (ECB) XML feed.
 *
 * <p>Scheduled to run every weekday at 16:30 UTC — shortly after ECB publishes the daily rates
 * (typically around 16:00 CET / 15:00 UTC). Disabled by default; enable via:
 * {@code currency.ecb.enabled=true}.
 *
 * <p>All fetched rates are stored with {@code source=ECB} in {@code ab_exchange_rate}.
 * Since exchange rates are tenant-scoped, rates are saved for every ACTIVE tenant.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EcbRateFetcher {

    private static final String SOURCE_ECB = "ecb";
    private static final String BASE_CURRENCY = "eur";

    private final ExchangeRateMapper exchangeRateMapper;
    private final TenantMapper tenantMapper;

    @Value("${currency.ecb.enabled:false}")
    private boolean ecbEnabled;

    @Value("${currency.ecb.url:https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml}")
    private String ecbUrl;

    /**
     * Scheduled trigger: every weekday at 16:30 UTC.
     * Guarded by {@code currency.ecb.enabled} — when false the bean still exists (for the
     * manual /sync-ecb endpoint) but will skip the scheduled execution.
     */
    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "UTC")
    public void scheduledFetch() {
        if (!ecbEnabled) {
            log.debug("ECB rate fetch is disabled (currency.ecb.enabled=false). Skipping scheduled run.");
            return;
        }
        fetchAndSave();
    }

    /**
     * Fetch ECB XML, parse rates, and persist for every active tenant.
     *
     * @return number of rate records saved (across all tenants)
     */
    public int fetchAndSave() {
        log.info("Starting ECB exchange rate fetch from {}", ecbUrl);

        String xml;
        try {
            xml = fetchXml();
        } catch (Exception e) {
            log.error("Failed to fetch ECB exchange rate XML from {}: {}", ecbUrl, e.getMessage(), e);
            throw new EcbFetchException("HTTP request to ECB failed: " + e.getMessage(), e);
        }

        List<RateEntry> entries;
        try {
            entries = parseXml(xml);
        } catch (Exception e) {
            log.error("Failed to parse ECB XML response: {}", e.getMessage(), e);
            throw new EcbFetchException("ECB XML parsing failed: " + e.getMessage(), e);
        }

        if (entries.isEmpty()) {
            log.warn("ECB XML parsed but contained no rate entries — nothing saved");
            return 0;
        }

        log.info("Parsed {} ECB rate entries for date {}", entries.size(), entries.get(0).date());

        List<Tenant> activeTenants = tenantMapper.findByStatus("active");
        if (activeTenants.isEmpty()) {
            log.warn("No active tenants found — ECB rates not saved");
            return 0;
        }

        int savedCount = 0;
        for (Tenant tenant : activeTenants) {
            try {
                savedCount += saveRatesForTenant(tenant.getId(), entries);
            } catch (Exception e) {
                log.error("Failed to save ECB rates for tenant {}: {}", tenant.getId(), e.getMessage(), e);
                // Continue with other tenants; partial failure is acceptable
            }
        }

        log.info("ECB rate sync complete: {} records saved across {} tenants", savedCount, activeTenants.size());
        return savedCount;
    }

    // ─── Internal helpers ────────────────────────────────────────────────────

    /**
     * Fetch raw XML string from the ECB endpoint.
     * Uses Java's built-in {@link java.net.http.HttpClient} to avoid requiring a Spring context
     * with a specific RestTemplate bean in unit tests.
     */
    public String fetchXml() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(ecbUrl))
                .timeout(java.time.Duration.ofSeconds(30))
                .GET()
                .build();

        java.net.http.HttpResponse<String> response = client.send(
                request, java.net.http.HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new EcbFetchException("ECB API returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    /**
     * Parse ECB Envelope XML and extract currency/rate pairs.
     *
     * <p>Expected structure (simplified):
     * <pre>
     * &lt;gesmes:Envelope&gt;
     *   &lt;Cube&gt;
     *     &lt;Cube time="2026-03-18"&gt;
     *       &lt;Cube currency="usd" rate="1.0842"/&gt;
     *     &lt;/Cube&gt;
     *   &lt;/Cube&gt;
     * &lt;/gesmes:Envelope&gt;
     * </pre>
     */
    public List<RateEntry> parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Disable external entity processing for security
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(false);

        Document doc = factory.newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)));

        // Find the outer Cube (the one with a "time" attribute)
        NodeList cubes = doc.getElementsByTagName("Cube");
        LocalDate date = LocalDate.now();
        List<RateEntry> entries = new ArrayList<>();

        for (int i = 0; i < cubes.getLength(); i++) {
            Element cube = (Element) cubes.item(i);
            String timeAttr = cube.getAttribute("time");
            if (!timeAttr.isBlank()) {
                date = LocalDate.parse(timeAttr);
                continue;
            }

            String currency = cube.getAttribute("currency");
            String rateStr = cube.getAttribute("rate");
            if (!currency.isBlank() && !rateStr.isBlank()) {
                entries.add(new RateEntry(currency, new BigDecimal(rateStr), date));
            }
        }

        return entries;
    }

    /**
     * Upsert ECB rates for a single tenant.
     * If a record with the same (tenant_id, base_currency, target_currency, effective_date, source=ECB)
     * already exists it is updated; otherwise a new record is inserted.
     *
     * @return number of records saved
     */
    private int saveRatesForTenant(Long tenantId, List<RateEntry> entries) {
        // Set up a minimal MetaContext for this tenant (no user — system task)
        MetaContext.setContext(tenantId, null, null, "ecb-scheduler");
        try {
            int count = 0;
            Instant now = Instant.now();

            for (RateEntry entry : entries) {
                // Check for existing record to avoid duplicates
                com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ExchangeRate> qw =
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
                qw.eq("tenant_id", tenantId)
                  .eq("base_currency", BASE_CURRENCY)
                  .eq("target_currency", entry.currency())
                  .eq("effective_date", entry.date())
                  .eq("source", SOURCE_ECB)
                  .eq("deleted_flag", false);

                ExchangeRate existing = exchangeRateMapper.selectOne(qw);

                if (existing != null) {
                    existing.setRate(entry.rate());
                    existing.setUpdatedAt(now);
                    exchangeRateMapper.updateById(existing);
                } else {
                    ExchangeRate rate = new ExchangeRate();
                    rate.setPid(UniqueIdGenerator.generate());
                    rate.setTenantId(tenantId);
                    rate.setBaseCurrency(BASE_CURRENCY);
                    rate.setTargetCurrency(entry.currency());
                    rate.setRate(entry.rate());
                    rate.setEffectiveDate(entry.date());
                    rate.setSource(SOURCE_ECB);
                    rate.setCreatedAt(now);
                    rate.setUpdatedAt(now);
                    rate.setDeletedFlag(false);
                    exchangeRateMapper.insert(rate);
                }
                count++;
            }
            return count;
        } finally {
            MetaContext.clear();
        }
    }

    // ─── Value objects ────────────────────────────────────────────────────────

    /**
     * Parsed ECB rate entry: target currency, rate vs EUR, and effective date.
     */
    public record RateEntry(String currency, BigDecimal rate, LocalDate date) {}

    /**
     * Checked exception for ECB fetch/parse failures.
     */
    public static class EcbFetchException extends RuntimeException {
        public EcbFetchException(String message) {
            super(message);
        }
        public EcbFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
