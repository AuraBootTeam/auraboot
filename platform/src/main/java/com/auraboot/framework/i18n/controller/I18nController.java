package com.auraboot.framework.i18n.controller;


import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.i18n.service.I18nService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@Controller
@RequestMapping("/api/")
public class I18nController {

    @Autowired
    private I18nService i18nService;

    // Keep the original method with query parameter
    @GetMapping("/i18n")
    @ResponseBody
    public Object i18n(@RequestParam String locale) throws IOException {
        Object i18nData = i18nService.getI18nData(locale);
        return ApiResponse.success(i18nData);
    }
    
    // Add a new method with path variable
    @GetMapping("/i18n/{locale}")
    @ResponseBody
    public Object i18nByPath(@PathVariable String locale) throws IOException {
        Object i18nData = i18nService.getI18nData(locale);
        return ApiResponse.success(i18nData);
    }
    

}
