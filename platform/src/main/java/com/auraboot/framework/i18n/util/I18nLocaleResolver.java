package com.auraboot.framework.i18n.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * I18n Locale解析器
 * 处理locale优先级：个人设定 > 浏览器locale
 * 
 * @author AuraBoot
 */
@Slf4j
@Component
public class I18nLocaleResolver {

    /**
     * 默认语言
     */
    private static final String DEFAULT_LOCALE = "zh-CN";

    /**
     * 解析locale
     * 优先级：查询参数 > X-Locale请求头 > Accept-Language请求头 > 默认语言
     * 
     * @param request HTTP请求
     * @return 解析后的locale字符串
     */
    public String resolveLocale(HttpServletRequest request) {
        // 1. 优先从查询参数获取（个人设定）
        String localeParam = request.getParameter("locale");
        if (StringUtils.hasText(localeParam) && isValidLocale(localeParam)) {
            log.debug("使用查询参数locale: {}", localeParam);
            return localeParam;
        }

        // 2. 从X-Locale请求头获取（BFF代理传递的locale）
        String xLocale = request.getHeader("X-Locale");
        if (StringUtils.hasText(xLocale) && isValidLocale(xLocale)) {
            log.debug("使用X-Locale请求头locale: {}", xLocale);
            return xLocale;
        }

        // 3. 从Accept-Language请求头获取（浏览器locale）
        String acceptLanguage = request.getHeader("Accept-Language");
        if (StringUtils.hasText(acceptLanguage)) {
            String browserLocale = parseBrowserLocale(acceptLanguage);
            if (browserLocale != null) {
                log.debug("使用浏览器locale: {}", browserLocale);
                return browserLocale;
            }
        }

        // 4. 使用默认语言
        log.debug("使用默认locale: {}", DEFAULT_LOCALE);
        return DEFAULT_LOCALE;
    }

    /**
     * 解析浏览器Accept-Language头
     * 
     * @param acceptLanguage Accept-Language头值
     * @return 匹配的locale，如果没有匹配则返回null
     */
    private String parseBrowserLocale(String acceptLanguage) {
        try {
            // 解析Accept-Language头，格式如：zh-CN,zh;q=0.9,en;q=0.8
            String[] languages = acceptLanguage.split(",");
            
            for (String language : languages) {
                // 移除权重信息，如 zh-CN;q=0.9 -> zh-CN
                String locale = language.split(";")[0].trim();
                
                // 验证locale格式是否有效
                if (isValidLocale(locale)) {
                    return locale;
                }
            }
        } catch (Exception e) {
            log.warn("解析Accept-Language失败: {}", acceptLanguage, e);
        }
        
        return null;
    }

    /**
     * 检查locale是否有效
     * 
     * @param locale locale字符串
     * @return 是否有效
     */
    private boolean isValidLocale(String locale) {
        try {
            // 使用Java标准的Locale类来验证locale格式
            Locale.forLanguageTag(locale.replace("_", "-"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取默认语言
     * 
     * @return 默认语言
     */
    public String getDefaultLocale() {
        return DEFAULT_LOCALE;
    }
}