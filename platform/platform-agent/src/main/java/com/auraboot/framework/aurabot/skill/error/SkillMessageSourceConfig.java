package com.auraboot.framework.aurabot.skill.error;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Dedicated {@link MessageSource} for the AuraBot Skill SPI (Plan §Step 8).
 *
 * <p>The platform's existing i18n stack is YAML-driven (see
 * {@code I18nResourceService} and {@code i18n.*.yaml} on the classpath) and
 * does not register a Spring {@link MessageSource} bean. Rather than retrofit
 * that subsystem, we ship a scoped {@code ResourceBundleMessageSource} bound
 * to {@code i18n/aurabot-skill[_locale].properties} so {@link SkillExceptionHandler}
 * can resolve typed {@link SkillErrorCode} keys via the standard Spring API.
 *
 * <p>Bean is named explicitly to avoid colliding with any future platform-wide
 * {@code messageSource} primary bean — handler injects by qualifier.
 */
@Configuration
public class SkillMessageSourceConfig {

    public static final String BEAN_NAME = "auraBotSkillMessageSource";

    @Bean(name = BEAN_NAME)
    public MessageSource auraBotSkillMessageSource() {
        ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
        ms.setBasename("i18n/aurabot-skill");
        ms.setDefaultEncoding("UTF-8");
        // When a key is missing, return null so handler falls back to
        // SkillSpiException#getMessage() — preferred over throwing or echoing
        // the bare key to the wire.
        ms.setUseCodeAsDefaultMessage(false);
        return ms;
    }
}
