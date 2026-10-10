package com.auraboot.framework.plugin.extension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DataPageQueryTest {
    @Test
    void rejectsUnboundedOverflowingAndAmbiguousRequests() {
        for (int[] boundary : List.of(new int[]{0, 20}, new int[]{1, 0},
                new int[]{1, 1001}, new int[]{Integer.MAX_VALUE, 1000})) {
            assertThatThrownBy(() -> new DataPageQuery(Map.of(), Map.of(), List.of(), boundary[0], boundary[1]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new DataPageQuery(Map.of("state", "draft"),
                Map.of("state", List.of("submitted")), List.of(), 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DataPageQuery(Map.of(), Map.of("state", List.of()), List.of(), 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DataPageQuery(Map.of("status;drop", "draft"), Map.of(), List.of(), 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DataPageQuery(Map.of(), Map.of(),
                List.of(new DataPageQuery.Sort("pid", false), new DataPageQuery.Sort("pid", true)), 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void freezesFilterMembershipAndOrderingBeforeHostExecution() {
        Map<String, Object> filters = new HashMap<>(Map.of("supplier", "SUP-A"));
        List<Object> states = new ArrayList<>(List.of("submitted", "closed"));
        List<DataPageQuery.Sort> sorts = new ArrayList<>(List.of(new DataPageQuery.Sort("submitted_at", true)));
        DataPageQuery query = new DataPageQuery(filters, Map.of("state", states), sorts, 2, 20);
        filters.put("supplier", "SUP-B"); states.clear(); sorts.clear();
        assertThat(query.exactFilters()).containsEntry("supplier", "SUP-A");
        assertThat(query.anyOfFilters().get("state")).containsExactly("submitted", "closed");
        assertThat(query.sorts()).containsExactly(new DataPageQuery.Sort("submitted_at", true));
        assertThatThrownBy(() -> query.anyOfFilters().get("state").clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsAHostResultThatExceedsItsBoundOrInventsItsCount() {
        assertThatThrownBy(() -> new DataPage(List.of(Map.of("pid", "A"), Map.of("pid", "B")), 99, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DataPage(List.of(Map.of("pid", "A")), 0, 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
