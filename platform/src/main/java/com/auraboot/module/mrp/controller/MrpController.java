package com.auraboot.module.mrp.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.module.mrp.dto.DemandEntry;
import com.auraboot.module.mrp.dto.MrpResult;
import com.auraboot.module.mrp.dto.MrpRunRequest;
import com.auraboot.module.mrp.engine.MrpPlanningEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/mrp")
@RequiredArgsConstructor
public class MrpController {

    private final MrpPlanningEngine engine;

    @PostMapping("/run")
    public ApiResponse<MrpResult> runMrp(@RequestBody MrpRunRequest request) {
        if (request.getDemands() == null || request.getDemands().isEmpty()) {
            return ApiResponse.error("Demands list cannot be empty");
        }
        if (request.getHorizonDays() <= 0) {
            request.setHorizonDays(90);
        }
        List<DemandEntry> demands = request.getDemands();
        MrpResult result = engine.runMrp(demands, request.getHorizonDays());
        return ApiResponse.success(result);
    }
}
