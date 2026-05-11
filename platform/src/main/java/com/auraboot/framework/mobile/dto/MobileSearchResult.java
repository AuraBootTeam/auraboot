package com.auraboot.framework.mobile.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class MobileSearchResult {

    private String keyword;
    private int totalCount;
    private List<SearchHit> hits;

    @Data
    @Builder
    public static class SearchHit {
        private String modelCode;
        private String modelLabel;
        private String recordId;
        private String recordPid;
        private String displayName;
        private String title;
        private String subtitle;
        private String type;
        private String deepLink;
        private Map<String, String> fields;
    }
}
