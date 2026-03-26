package com.auraboot.framework.meta.ai;

import com.auraboot.framework.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for AI Next Best Action suggestions.
 *
 * GET /api/ai/nba?modelCode=crm_lead&recordPid=01ABC...
 *
 * @since 6.3.0
 */
@RestController
@RequestMapping("/api/ai/nba")
@RequiredArgsConstructor
public class NbaController {

    private final NbaService nbaService;

    @GetMapping
    public ApiResponse<List<NbaService.NbaSuggestion>> getSuggestions(
            @RequestParam String modelCode,
            @RequestParam String recordPid) {
        List<NbaService.NbaSuggestion> suggestions = nbaService.suggest(modelCode, recordPid);
        return ApiResponse.success(suggestions);
    }
}
