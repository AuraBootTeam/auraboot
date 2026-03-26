package com.auraboot.framework.meta.formula;

import java.lang.annotation.*;

/**
 * Annotation to mark a method as a formula function
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FormulaFunction {
    /**
     * Function name used in formulas
     */
    String value();

    /**
     * Function description
     */
    String description() default "";

    /**
     * Function category
     */
    String category() default "general";

    /**
     * Example usage
     */
    String example() default "";
}
