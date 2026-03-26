package com.auraboot.framework.meta.bean;

import lombok.Data;

/**
 * 国际化Bean类，用于存储多语言文本
 * 
 * @author AuraBoot
 */
@Data
public class I18nBean {
    
    /**
     * 中文
     */
    private String zh;
    
    /**
     * 英文
     */
    private String en;
    
    /**
     * 日文
     */
    private String ja;
    
    /**
     * 韩文
     */
    private String ko;
    
    /**
     * 法文
     */
    private String fr;
    
    /**
     * 德文
     */
    private String de;
    
    /**
     * 西班牙文
     */
    private String es;
    
    /**
     * 意大利文
     */
    private String it;
    
    /**
     * 葡萄牙文
     */
    private String pt;
    
    /**
     * 俄文
     */
    private String ru;
    
    /**
     * 阿拉伯文
     */
    private String ar;
    
    /**
     * 默认语言
     */
    private String defaultLang;
    
    /**
     * 扩展语言映射
     */
    private java.util.Map<String, String> extensions;
    
    /**
     * 获取默认语言的文本
     * 
     * @return 默认语言的文本
     */
    public String getDefaultText() {
        if (zh != null && !zh.trim().isEmpty()) {
            return zh;
        }
        if (en != null && !en.trim().isEmpty()) {
            return en;
        }
        return null;
    }
}