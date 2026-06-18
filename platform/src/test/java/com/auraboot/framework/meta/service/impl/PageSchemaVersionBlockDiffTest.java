package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.PageSchemaVersionComparisonDTO;
import com.auraboot.framework.meta.dto.PageSchemaVersionComparisonDTO.DifferenceType;
import com.auraboot.framework.meta.dto.PageSchemaVersionComparisonDTO.FieldDifference;
import com.auraboot.framework.meta.entity.PageSchemaHistory;
import com.auraboot.framework.meta.mapper.PageSchemaHistoryMapper;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * B3 — block-level version diff. The page block tree is diffed by stable block id
 * (added / removed / per-prop modified, recursing into children) instead of as one
 * opaque blob. Uses a REAL ObjectMapper (the service parses block snapshots) while
 * mocking the history mapper.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PageSchemaVersionServiceImpl — B3 block-level diff")
class PageSchemaVersionBlockDiffTest {

    @Mock
    private PageSchemaMapper pageSchemaMapper;

    @Mock
    private PageSchemaHistoryMapper pageSchemaHistoryMapper;

    @Mock
    private ObjectMapper injectedObjectMapper;

    @InjectMocks
    private PageSchemaVersionServiceImpl versionService;

    @BeforeEach
    void setUp() {
        // The diff parses block snapshots — replace the injected mock with a real one.
        ReflectionTestUtils.setField(versionService, "objectMapper", new ObjectMapper());
    }

    private PageSchemaHistory history(long id, Object blocks) {
        PageSchemaHistory history = new PageSchemaHistory();
        history.setId(id);
        history.setPid("page-pid");
        history.setOp("save");
        history.setOpAt(Instant.now());
        history.setOpBy("user-pid");
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("name", "page");
        snapshot.put("kind", "detail");
        snapshot.put("version", (int) id);
        snapshot.put("blocks", blocks);
        history.setSnapshot(snapshot);
        return history;
    }

    private Map<String, Object> block(String id, String blockType, String title) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("id", id);
        block.put("blockType", blockType);
        if (title != null) {
            block.put("title", title);
        }
        return block;
    }

    private Map<String, Object> blockWithChildren(String id, String blockType, List<Map<String, Object>> children) {
        Map<String, Object> block = block(id, blockType, null);
        block.put("blocks", new ArrayList<>(children));
        return block;
    }

    private Map<String, DifferenceType> diffByPath(PageSchemaVersionComparisonDTO result) {
        return result.getDifferences().stream()
                .collect(Collectors.toMap(FieldDifference::getFieldPath, FieldDifference::getType,
                        (a, b) -> a));
    }

    private void stub(PageSchemaHistory source, PageSchemaHistory target) {
        when(pageSchemaHistoryMapper.selectById(source.getId())).thenReturn(source);
        when(pageSchemaHistoryMapper.selectById(target.getId())).thenReturn(target);
    }

    @Test
    @DisplayName("emits per-block ADDED + per-prop MODIFIED instead of a coarse blocks blob")
    void emitsBlockLevelDiffs() {
        PageSchemaHistory v1 = history(1L, List.of(block("a", "field", "X")));
        PageSchemaHistory v2 = history(2L, List.of(block("a", "field", "Y"), block("b", "field", "New")));
        stub(v1, v2);

        PageSchemaVersionComparisonDTO result = versionService.compareVersions(1L, 2L);
        Map<String, DifferenceType> paths = diffByPath(result);

        assertThat(paths).containsEntry("blocks[a].title", DifferenceType.MODIFIED);
        assertThat(paths).containsEntry("blocks[b]", DifferenceType.ADDED);
        // No coarse blob entry — blocks are diffed at block level now.
        assertThat(paths).doesNotContainKey("blocks");
        assertThat(result.getSummary().getHasMajorChanges()).isTrue();
        assertThat(result.getSummary().getTotalDifferences()).isEqualTo(paths.size());
    }

    @Test
    @DisplayName("reports a removed block")
    void reportsRemovedBlock() {
        PageSchemaHistory v1 = history(1L, List.of(block("a", "field", "X"), block("b", "field", "Y")));
        PageSchemaHistory v2 = history(2L, List.of(block("a", "field", "X")));
        stub(v1, v2);

        Map<String, DifferenceType> paths = diffByPath(versionService.compareVersions(1L, 2L));
        assertThat(paths).containsEntry("blocks[b]", DifferenceType.REMOVED);
        assertThat(paths).doesNotContainKey("blocks[a].title"); // unchanged block omitted
    }

    @Test
    @DisplayName("recurses into nested child blocks")
    void recursesNestedChildBlocks() {
        PageSchemaHistory v1 = history(1L,
                List.of(blockWithChildren("root", "detail", List.of(block("c1", "field", "A")))));
        PageSchemaHistory v2 = history(2L,
                List.of(blockWithChildren("root", "detail", List.of(block("c1", "field", "B")))));
        stub(v1, v2);

        Map<String, DifferenceType> paths = diffByPath(versionService.compareVersions(1L, 2L));
        assertThat(paths).containsEntry("blocks[root].blocks[c1].title", DifferenceType.MODIFIED);
    }

    @Test
    @DisplayName("identical block trees yield no block-level differences")
    void identicalSnapshots() {
        PageSchemaHistory v1 = history(1L, List.of(block("a", "field", "X")));
        PageSchemaHistory v2 = history(2L, List.of(block("a", "field", "X")));
        stub(v1, v2);

        Map<String, DifferenceType> paths = diffByPath(versionService.compareVersions(1L, 2L));
        // The block tree is identical → no blocks[...] entries (the version field
        // intentionally differs in this fixture and is not a block-level concern).
        assertThat(paths.keySet()).noneMatch(path -> path.startsWith("blocks"));
    }

    @Test
    @DisplayName("diffs blocks even when stored as a JSON string snapshot")
    void diffsBlocksStoredAsJsonString() {
        PageSchemaHistory v1 = history(1L, "[{\"id\":\"a\",\"blockType\":\"field\",\"title\":\"X\"}]");
        PageSchemaHistory v2 = history(2L,
                "[{\"id\":\"a\",\"blockType\":\"field\",\"title\":\"Y\"}]");
        stub(v1, v2);

        Map<String, DifferenceType> paths = diffByPath(versionService.compareVersions(1L, 2L));
        assertThat(paths).containsEntry("blocks[a].title", DifferenceType.MODIFIED);
    }
}
