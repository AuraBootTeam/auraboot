package com.auraboot.framework.meta.spi;

import com.auraboot.framework.meta.entity.Model;

import java.util.List;

/**
 * SPI for MONEY field expansion during model publish.
 * Enterprise finance module provides the implementation.
 * Core wires this as {@code @Autowired(required = false)} so the application
 * starts without the finance module; MONEY fields simply skip expansion.
 */
public interface MoneyFieldExpansionSpi {

    /**
     * Expand MONEY-type fields for the given model:
     * auto-creates {@code _base} companion fields, currency header fields,
     * and a currencyConversionHandler binding rule on the CREATE command.
     *
     * @param model the published model
     * @return list of field codes that were created (empty if none)
     */
    List<String> expandMoneyFields(Model model);
}
