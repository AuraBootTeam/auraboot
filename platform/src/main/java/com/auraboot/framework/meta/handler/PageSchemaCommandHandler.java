package com.auraboot.framework.meta.handler;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.PageSchemaCreateRequest;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.service.CommandHandler;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.auraboot.framework.meta.service.PageSchemaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command handler for page schema state transitions and custom operations.
 *
 * For publish/archive: the DSL engine's EFFECT phase handles the status column
 * update. This handler supplements it by setting published_at timestamp.
 *
 * For duplicate: creates a copy of the page with a new page_key.
 *
 * Supported command codes:
 * - pgm:publish_page_schema  → set published_at timestamp
 * - pgm:archive_page_schema  → clear published_at timestamp
 * - pgm:duplicate_page_schema → duplicate a page with "(Copy)" suffix
 *
 * @author AuraBoot Team
 * @since 4.0.0
 */
@Slf4j
@Component("pageSchemaCommandHandler")
@RequiredArgsConstructor
public class PageSchemaCommandHandler implements CommandHandler {

    private final PageSchemaService pageSchemaService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public String getHandlerName() {
        return "pageSchemaCommandHandler";
    }

    @Override
    public Map<String, Object> execute(CommandHandlerContext context) {
        String commandCode = context.getCommandCode();
        log.info("PageSchemaCommandHandler executing: {}", commandCode);

        Map<String, Object> result = new HashMap<>();

        String pid = context.getTargetRecordId();
        if (pid == null || pid.isBlank()) {
            throw new BusinessException("Target record ID (pid) is required");
        }

        switch (commandCode) {
            case "pgm:publish_page_schema" -> handlePublish(pid, result);
            case "pgm:archive_page_schema" -> handleArchive(pid, result);
            case "pgm:duplicate_page_schema" -> handleDuplicate(pid, result);
            default -> {
                log.warn("Unknown command code for PageSchemaCommandHandler: {}", commandCode);
                result.put("handlerExecuted", false);
                return result;
            }
        }

        result.put("handlerExecuted", true);
        log.info("PageSchemaCommandHandler completed: {} for pid={}", commandCode, pid);
        return result;
    }

    private void handlePublish(String pid, Map<String, Object> result) {
        // DSL EFFECT phase updates status → published.
        // Handler supplements by setting published_at timestamp.
        jdbcTemplate.update(
                "UPDATE ab_page_schema SET published_at = ?, updated_at = ? WHERE pid = ? AND deleted_flag = false",
                Instant.now(), Instant.now(), pid);
        result.put("pid", pid);
        result.put("published_at", Instant.now().toString());
    }

    private void handleArchive(String pid, Map<String, Object> result) {
        // DSL EFFECT phase updates status → archived.
        // Handler supplements by clearing published_at.
        jdbcTemplate.update(
                "UPDATE ab_page_schema SET published_at = NULL, updated_at = ? WHERE pid = ? AND deleted_flag = false",
                Instant.now(), pid);
        result.put("pid", pid);
    }

    private void handleDuplicate(String pid, Map<String, Object> result) {
        PageSchemaDTO source = pageSchemaService.findByPid(pid);
        if (source == null) {
            throw new BusinessException("Page not found: " + pid);
        }

        PageSchemaCreateRequest request = new PageSchemaCreateRequest();
        request.setName(source.getName() + " (Copy)");
        request.setPageKey(source.getPageKey() + "_copy_" + System.currentTimeMillis());
        request.setKind(source.getKind());
        request.setTitle(source.getName() + " (Copy)");
        request.setModelCode(source.getModelCode());
        request.setProfile(source.getProfile());
        request.setDescription(source.getDescription());
        request.setBlocks(source.getBlocks() != null ? source.getBlocks() : List.of());
        request.setLayout(source.getLayout());
        request.setIsTemplate(source.getIsTemplate());
        request.setExtension(source.getExtension());

        PageSchemaDTO copy = pageSchemaService.create(request);
        result.put("pid", copy.getPid());
        result.put("pageKey", copy.getPageKey());
    }
}
