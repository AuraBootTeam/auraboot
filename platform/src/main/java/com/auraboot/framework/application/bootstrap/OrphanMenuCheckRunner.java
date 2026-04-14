package com.auraboot.framework.application.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Guards the bootstrap-vs-plugin menu boundary at startup.
 *
 * <p>Scans {@code ab_menu} for rows with {@code plugin_pid IS NULL} (bootstrap-owned menus).
 * Any distinct menu code not present in {@code seed/platform-menu-whitelist.json} indicates
 * a functional menu leaked into {@code default-bootstrap.json} — the process aborts so the
 * orphan is fixed before it accumulates.
 */
@Slf4j
@Component
@Order(5)
@RequiredArgsConstructor
@Profile("!integration-test")
public class OrphanMenuCheckRunner implements ApplicationRunner {

    private static final String WHITELIST_RESOURCE = "seed/platform-menu-whitelist.json";
    private static final String ORPHAN_QUERY =
            "SELECT DISTINCT code FROM ab_menu "
                    + "WHERE plugin_pid IS NULL AND deleted_flag = FALSE AND status = 'active'";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Set<String> whitelist = loadWhitelist();
        List<String> orphanCodes = jdbcTemplate.queryForList(ORPHAN_QUERY, String.class);
        List<String> violations = orphanCodes.stream()
                .filter(code -> !whitelist.contains(code))
                .sorted()
                .collect(Collectors.toList());

        if (violations.isEmpty()) {
            log.info("OrphanMenuCheck: OK ({} bootstrap menu codes, all whitelisted)", orphanCodes.size());
            return;
        }

        String message = String.format(
                "Orphan bootstrap menus detected (plugin_pid IS NULL, not in whitelist): %s. "
                        + "Move these to the owning plugin's menus.json, or add to %s if truly platform-level. "
                        + "See docs/system-reference/reference/menu-seed-mechanism.md.",
                violations, WHITELIST_RESOURCE);
        log.error(message);
        throw new IllegalStateException(message);
    }

    private Set<String> loadWhitelist() throws Exception {
        ClassPathResource resource = new ClassPathResource(WHITELIST_RESOURCE);
        if (!resource.exists()) {
            return new HashSet<>();
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            JsonNode arr = root.path("allowedCodes");
            Set<String> codes = new HashSet<>();
            if (arr.isArray()) {
                arr.forEach(n -> codes.add(n.asText()));
            }
            return codes;
        }
    }
}
