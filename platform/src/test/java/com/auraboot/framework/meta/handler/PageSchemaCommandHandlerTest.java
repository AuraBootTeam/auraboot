package com.auraboot.framework.meta.handler;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.PageSchemaCreateRequest;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.auraboot.framework.meta.service.PageSchemaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PageSchemaCommandHandler")
class PageSchemaCommandHandlerTest {

    @Mock private PageSchemaService pageSchemaService;
    @Mock private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private PageSchemaCommandHandler handler;

    private CommandHandlerContext ctx(String code, String pid) {
        return CommandHandlerContext.builder()
                .commandCode(code)
                .targetRecordId(pid)
                .build();
    }

    @Test
    @DisplayName("getHandlerName is stable")
    void name() {
        assertEquals("pageSchemaCommandHandler", handler.getHandlerName());
    }

    @Test
    @DisplayName("execute requires non-blank target record id")
    void requireTargetId() {
        assertThrows(BusinessException.class,
                () -> handler.execute(ctx("pgm:publish_page_schema", null)));
        assertThrows(BusinessException.class,
                () -> handler.execute(ctx("pgm:publish_page_schema", "")));
        assertThrows(BusinessException.class,
                () -> handler.execute(ctx("pgm:publish_page_schema", "   ")));
    }

    @Test
    @DisplayName("publish updates published_at and updated_at")
    void publish() {
        when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(1);
        Map<String, Object> result = handler.execute(ctx("pgm:publish_page_schema", "p1"));
        assertEquals("p1", result.get("pid"));
        assertEquals(true, result.get("handlerExecuted"));
        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCap.capture(), any(), any(), any());
        assertTrue(sqlCap.getValue().contains("published_at = ?"));
    }

    @Test
    @DisplayName("archive clears published_at via SQL")
    void archive() {
        when(jdbcTemplate.update(anyString(), any(), any())).thenReturn(1);
        Map<String, Object> result = handler.execute(ctx("pgm:archive_page_schema", "p2"));
        assertEquals("p2", result.get("pid"));
        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCap.capture(), any(), any());
        assertTrue(sqlCap.getValue().contains("published_at = NULL"));
    }

    @Test
    @DisplayName("duplicate throws when source page missing")
    void duplicateMissing() {
        when(pageSchemaService.findByPid("p3")).thenReturn(null);
        assertThrows(BusinessException.class,
                () -> handler.execute(ctx("pgm:duplicate_page_schema", "p3")));
    }

    @Test
    @DisplayName("duplicate creates copy with derived name/page key")
    void duplicate() {
        PageSchemaDTO src = new PageSchemaDTO();
        src.setName("Orders");
        src.setPageKey("orders");
        src.setKind("LIST");
        src.setModelCode("order");
        src.setProfile("default");
        src.setDescription("desc");
        src.setBlocks(null); // forces empty list branch
        src.setLayout(Map.of("rows", 1));
        src.setIsTemplate(false);

        when(pageSchemaService.findByPid("p4")).thenReturn(src);

        PageSchemaDTO copy = new PageSchemaDTO();
        copy.setPid("copy-pid");
        copy.setPageKey("orders_copy_123");
        when(pageSchemaService.create(any(PageSchemaCreateRequest.class))).thenReturn(copy);

        Map<String, Object> result = handler.execute(ctx("pgm:duplicate_page_schema", "p4"));
        assertEquals("copy-pid", result.get("pid"));
        assertEquals("orders_copy_123", result.get("pageKey"));

        ArgumentCaptor<PageSchemaCreateRequest> cap = ArgumentCaptor.forClass(PageSchemaCreateRequest.class);
        verify(pageSchemaService).create(cap.capture());
        PageSchemaCreateRequest req = cap.getValue();
        assertEquals("Orders (Copy)", req.getName());
        assertTrue(req.getPageKey().startsWith("orders_copy_"));
        assertEquals(List.of(), req.getBlocks());
    }

    @Test
    @DisplayName("duplicate forwards non-null blocks")
    void duplicateBlocks() {
        PageSchemaDTO src = new PageSchemaDTO();
        src.setName("X");
        src.setPageKey("x");
        src.setKind("PAGE");
        List<Object> blocks = List.of("blk1", "blk2");
        src.setBlocks(blocks);

        when(pageSchemaService.findByPid("p5")).thenReturn(src);
        PageSchemaDTO copy = new PageSchemaDTO();
        copy.setPid("c");
        copy.setPageKey("x_copy");
        when(pageSchemaService.create(any(PageSchemaCreateRequest.class))).thenReturn(copy);

        handler.execute(ctx("pgm:duplicate_page_schema", "p5"));

        ArgumentCaptor<PageSchemaCreateRequest> cap = ArgumentCaptor.forClass(PageSchemaCreateRequest.class);
        verify(pageSchemaService).create(cap.capture());
        assertEquals(blocks, cap.getValue().getBlocks());
    }

    @Test
    @DisplayName("unknown command code returns handlerExecuted=false without writes")
    void unknownCommand() {
        Map<String, Object> result = handler.execute(ctx("pgm:unknown_op", "p9"));
        assertEquals(false, result.get("handlerExecuted"));
        assertFalse(result.containsKey("pid"));
        verify(jdbcTemplate, never()).update(anyString(), any(), any());
        verify(pageSchemaService, never()).create(any());
    }
}
