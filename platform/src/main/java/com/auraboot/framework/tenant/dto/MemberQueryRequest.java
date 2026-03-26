package com.auraboot.framework.tenant.dto;

import lombok.Data;

@Data
public class MemberQueryRequest {
    private String keyword;
    private String status;
    private String memberType;
    private int pageNum = 1;
    private int pageSize = 10;
}