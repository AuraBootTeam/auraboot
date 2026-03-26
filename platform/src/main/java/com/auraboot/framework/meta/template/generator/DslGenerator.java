package com.auraboot.framework.meta.template.generator;

import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.template.dto.CrudTemplateConfig;

import java.util.List;

/**
 * DSL Generator interface for generating UnifiedSchema page DSL
 * 
 * @author AuraBoot
 */
public interface DslGenerator {
    
    /**
     * Generate list page DSL
     * 
     * @param model Model definition
     * @param bindings Field bindings
     * @param fields Field definitions
     * @param config Generation configuration
     * @return Page Schema
     */
    PageSchema generateListPage(
        Model model,
        List<ModelFieldBinding> bindings,
        List<Field> fields,
        CrudTemplateConfig config
    );
    
    /**
     * Generate form page DSL
     * 
     * @param model Model definition
     * @param bindings Field bindings
     * @param fields Field definitions
     * @param config Generation configuration
     * @return Page Schema
     */
    PageSchema generateFormPage(
        Model model,
        List<ModelFieldBinding> bindings,
        List<Field> fields,
        CrudTemplateConfig config
    );
    
    /**
     * Generate detail page DSL
     * 
     * @param model Model definition
     * @param bindings Field bindings
     * @param fields Field definitions
     * @param config Generation configuration
     * @return Page Schema
     */
    PageSchema generateDetailPage(
        Model model,
        List<ModelFieldBinding> bindings,
        List<Field> fields,
        CrudTemplateConfig config
    );
}
