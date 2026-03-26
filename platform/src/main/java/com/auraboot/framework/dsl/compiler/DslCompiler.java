package com.auraboot.framework.dsl.compiler;

import com.auraboot.framework.dsl.compiler.model.CompiledPlan;
import com.auraboot.framework.dsl.compiler.model.DslDefinition;

/**
 * Compiles a DSL definition into an optimized execution plan.
 * Implementations are domain-specific (BOM, MRP, Query, etc.).
 */
public interface DslCompiler {

    /**
     * @return the definition type this compiler handles (e.g. "bom", "mrp", "query").
     */
    String supportedType();

    /**
     * Compile the given definition into an execution plan.
     *
     * @param definition the DSL definition to compile
     * @return a compiled plan ready for execution
     * @throws IllegalArgumentException if the definition is invalid for this compiler
     */
    CompiledPlan compile(DslDefinition definition);
}
