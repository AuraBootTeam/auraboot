package com.auraboot.framework.meta.service;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.dto.SortField;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryCommandParamsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void withoutParametersTheReadIsTheFirstPageOfTwenty() {
        DynamicQueryRequest request = QueryCommandParams.toRequest(objectMapper, Map.of(), false);

        assertThat(request.getPageNum()).isEqualTo(1);
        assertThat(request.getPageSize()).isEqualTo(20);
        assertThat(request.getKeyword()).isNull();
        assertThat(request.getConditions()).isNull();
        assertThat(request.getSortFields()).isNull();
        assertThat(request.getExtraParams()).isEmpty();
        assertThat(QueryCommandParams.toRequest(objectMapper, null, false))
                .usingRecursiveComparison().isEqualTo(request);
    }

    @Test
    void pagingAndKeywordAcceptTheirShortNames() {
        DynamicQueryRequest request = QueryCommandParams.toRequest(objectMapper,
                Map.of("page", 3, "pageSize", 50, "search", "north"), false);

        assertThat(request.getPageNum()).isEqualTo(3);
        assertThat(request.getPageSize()).isEqualTo(50);
        assertThat(request.getKeyword()).isEqualTo("north");
        assertThat(QueryCommandParams.toRequest(objectMapper, Map.of("page", 3, "pageSize", 50, "search", "north"), true))
                .usingRecursiveComparison().isEqualTo(request);
    }

    @Test
    void filtersAreConditionsWhetherGivenAsAListOrAsJsonText() {
        Map<String, Object> condition = Map.of("fieldName", "crm_acc_stage", "operator", "EQ", "value", "won");

        DynamicQueryRequest asList = QueryCommandParams.toRequest(objectMapper, Map.of("filters", List.of(condition)), true);
        DynamicQueryRequest asText = QueryCommandParams.toRequest(objectMapper,
                Map.of("filters", "[{\"fieldName\":\"crm_acc_stage\",\"operator\":\"EQ\",\"value\":\"won\"}]"), true);

        assertThat(asList.getConditions()).hasSize(1);
        assertThat(asList.getConditions().get(0).getFieldName()).isEqualTo("crm_acc_stage");
        assertThat(asList.getConditions().get(0).getValue()).isEqualTo("won");
        assertThat(asText).usingRecursiveComparison().isEqualTo(asList);
        assertThat(QueryCommandParams.toRequest(objectMapper, Map.of("filters", List.of()), false).getConditions()).isNull();
        assertThat(QueryCommandParams.toRequest(objectMapper, Map.of("filters", " "), false).getConditions()).isNull();
    }

    @Test
    void sortingAcceptsAListOrAFieldWithADirection() {
        DynamicQueryRequest byList = QueryCommandParams.toRequest(objectMapper,
                Map.of("sortFields", List.of(Map.of("fieldName", "crm_acc_name", "direction", "DESC"))), true);
        DynamicQueryRequest byField = QueryCommandParams.toRequest(objectMapper,
                Map.of("sortField", "crm_acc_name", "sortOrder", "asc"), true);
        DynamicQueryRequest defaultDirection = QueryCommandParams.toRequest(objectMapper,
                Map.of("sortField", "crm_acc_name"), true);

        assertThat(byList.getSortFields()).hasSize(1);
        assertThat(byList.getSortFields().get(0).getDirection()).isEqualTo(SortField.SortDirection.DESC);
        assertThat(byField.getSortFields()).hasSize(1);
        assertThat(byField.getSortFields().get(0).getDirection()).isEqualTo(SortField.SortDirection.ASC);
        assertThat(defaultDirection.getSortFields().get(0).getDirection()).isEqualTo(SortField.SortDirection.DESC);
    }

    @Test
    void aLenientReadDropsWhatItCannotReadAndPassesTheRestOn() {
        DynamicQueryRequest request = QueryCommandParams.toRequest(objectMapper, new HashMap<>(Map.of(
                "filters", "not a list", "sortFields", 7, "pageNum", "first", "pageSize", 5,
                "crm_acc_stage", "won", "recordPid", "p-1")), false);

        assertThat(request.getPageNum()).isEqualTo(1);
        assertThat(request.getPageSize()).isEqualTo(5);
        assertThat(request.getConditions()).isNull();
        assertThat(request.getSortFields()).isNull();
        assertThat(request.getExtraParams()).containsExactly(Map.entry("crm_acc_stage", "won"));
    }

    @Test
    void aLenientReadFallsBackToSortFieldWhenTheListCannotBeRead() {
        DynamicQueryRequest request = QueryCommandParams.toRequest(objectMapper, Map.of(
                "sortFields", List.of(7), "sortField", "crm_acc_name", "sortOrder", "asc"), false);

        assertThat(request.getSortFields()).hasSize(1);
        assertThat(request.getSortFields().get(0).getFieldName()).isEqualTo("crm_acc_name");
    }

    @Test
    void aStrictReadRefusesAFilterItCannotRead() {
        assertThatThrownBy(() -> QueryCommandParams.toRequest(objectMapper, Map.of("filters", "not a list"), true))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.getResponseCode()).isEqualTo(ResponseCode.BadParam);
                    assertThat(failure.getMessage()).contains("filters");
                });
        assertThatThrownBy(() -> QueryCommandParams.toRequest(objectMapper, Map.of("filters", Map.of("fieldName", "a")), true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void aStrictReadRefusesASortOrderItCannotRead() {
        assertThatThrownBy(() -> QueryCommandParams.toRequest(objectMapper, Map.of("sortFields", "crm_acc_name"), true))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getMessage()).contains("sortFields"));
        assertThatThrownBy(() -> QueryCommandParams.toRequest(objectMapper, Map.of("sortFields", List.of(7)), true))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> QueryCommandParams.toRequest(objectMapper,
                Map.of("sortField", "crm_acc_name", "sortOrder", "ascending"), true))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getMessage()).contains("sortOrder"));
        assertThatThrownBy(() -> QueryCommandParams.toRequest(objectMapper, Map.of("sortField", 7), true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void aStrictReadRefusesPagingAndKeywordValuesItCannotRead() {
        assertRefused(Map.of("pageNum", "first"), "pageNum");
        assertRefused(Map.of("page", "2"), "page");
        assertRefused(Map.of("pageSize", "big"), "pageSize");
        assertRefused(Map.of("keyword", 13800000000L), "keyword");
        assertRefused(Map.of("search", 13800000000L), "search");
        assertThat(QueryCommandParams.toRequest(objectMapper, Map.of("pageNum", "2"), false).getPageNum()).isEqualTo(1);
    }

    @Test
    void aStrictReadRefusesAParameterOutsideTheContract() {
        assertRefused(Map.of("crm_acc_stage", "won"), "crm_acc_stage");
        assertRefused(Map.of("keywrod", "north"), "keywrod");
        assertThat(QueryCommandParams.toRequest(objectMapper, Map.of("recordPid", "p-1"), true).getExtraParams()).isEmpty();
    }

    @Test
    void recordPidNamesARecordOnlyWhenItIsPresent() {
        assertThat(QueryCommandParams.recordPid(Map.of("recordPid", "p-1"))).isEqualTo("p-1");
        assertThat(QueryCommandParams.recordPid(Map.of("recordPid", 42))).isEqualTo("42");
        assertThat(QueryCommandParams.recordPid(Map.of("recordPid", " "))).isNull();
        assertThat(QueryCommandParams.recordPid(Map.of())).isNull();
        assertThat(QueryCommandParams.recordPid(null)).isNull();
    }

    private void assertRefused(Map<String, Object> params, String parameter) {
        assertThatThrownBy(() -> QueryCommandParams.toRequest(objectMapper, params, true))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.getResponseCode()).isEqualTo(ResponseCode.BadParam);
                    assertThat(failure.getMessage()).contains(parameter);
                });
    }
}
