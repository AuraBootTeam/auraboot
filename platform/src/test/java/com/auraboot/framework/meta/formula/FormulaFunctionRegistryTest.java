package com.auraboot.framework.meta.formula;

import com.auraboot.framework.meta.formula.FormulaFunctionRegistry.FormulaFunctions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.time.LocalDate;
import java.time.Month;

import static org.assertj.core.api.Assertions.assertThat;

class FormulaFunctionRegistryTest {

    private FormulaFunctionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new FormulaFunctionRegistry();
        registry.init();
    }

    @Test
    void init_registers_built_in_functions() {
        assertThat(registry.getAllFunctions()).isNotEmpty();
        assertThat(registry.getAllFunctions()).extracting(FormulaFunctionRegistry.FunctionInfo::name)
            .contains("concat", "upper", "lower", "round", "sum", "if", "now", "today");
    }

    @Test
    void getFunctionsByCategory_filters_correctly() {
        var math = registry.getFunctionsByCategory("math");
        assertThat(math).isNotEmpty();
        assertThat(math).allMatch(f -> "math".equals(f.category()));
        var text = registry.getFunctionsByCategory("text");
        assertThat(text).extracting(FormulaFunctionRegistry.FunctionInfo::name).contains("concat", "upper", "len");
        assertThat(registry.getFunctionsByCategory("nope")).isEmpty();
    }

    @Test
    void registerToContext_does_not_throw() {
        SimpleEvaluationContext ctx = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        registry.registerToContext(ctx);
        // SimpleEvaluationContext.lookupVariable not part of public API; just exercise call.
    }

    // ===== Text functions =====
    @Test
    void text_functions() {
        assertThat(FormulaFunctions.concat("a", "b", "c")).isEqualTo("abc");
        assertThat(FormulaFunctions.upper("hi")).isEqualTo("HI");
        assertThat(FormulaFunctions.upper(null)).isNull();
        assertThat(FormulaFunctions.lower("HI")).isEqualTo("hi");
        assertThat(FormulaFunctions.lower(null)).isNull();
        assertThat(FormulaFunctions.trim("  x  ")).isEqualTo("x");
        assertThat(FormulaFunctions.trim(null)).isNull();
        assertThat(FormulaFunctions.left("hello", 3)).isEqualTo("hel");
        assertThat(FormulaFunctions.left("hi", 5)).isEqualTo("hi");
        assertThat(FormulaFunctions.left(null, 3)).isNull();
        assertThat(FormulaFunctions.right("hello", 3)).isEqualTo("llo");
        assertThat(FormulaFunctions.right("hi", 5)).isEqualTo("hi");
        assertThat(FormulaFunctions.right(null, 3)).isNull();
        assertThat(FormulaFunctions.len("hello")).isEqualTo(5);
        assertThat(FormulaFunctions.len(null)).isEqualTo(0);
        assertThat(FormulaFunctions.replace("aaa", "a", "b")).isEqualTo("bbb");
        assertThat(FormulaFunctions.replace(null, "a", "b")).isNull();
    }

    @Test
    void text_extras() {
        assertThat(FormulaFunctions.concatenate("a", null, 1, "b")).isEqualTo("a1b");
        assertThat(FormulaFunctions.concatenate((Object[]) null)).isEqualTo("");
        assertThat(FormulaFunctions.contains("hello", "ell")).isTrue();
        assertThat(FormulaFunctions.contains("hello", "zzz")).isFalse();
        assertThat(FormulaFunctions.contains(null, "x")).isFalse();
        assertThat(FormulaFunctions.contains("x", null)).isFalse();
        assertThat(FormulaFunctions.substitute("hello", "l", "x")).isEqualTo("hexlo");
        assertThat(FormulaFunctions.substitute(null, "l", "x")).isNull();
        assertThat(FormulaFunctions.substitute("hi", "l", null)).isEqualTo("hi");
        assertThat(FormulaFunctions.mid("hello", 1, 3)).isEqualTo("ell");
        assertThat(FormulaFunctions.mid("hi", 0, 100)).isEqualTo("hi");
        assertThat(FormulaFunctions.mid(null, 0, 1)).isNull();
    }

    // ===== Math functions =====
    @Test
    void math_functions() {
        assertThat(FormulaFunctions.round(3.14159, 2)).isEqualTo(3.14);
        assertThat(FormulaFunctions.floor(3.7)).isEqualTo(3.0);
        assertThat(FormulaFunctions.ceil(3.2)).isEqualTo(4.0);
        assertThat(FormulaFunctions.abs(-5)).isEqualTo(5.0);
        assertThat(FormulaFunctions.min(3, 1, 2)).isEqualTo(1.0);
        assertThat(FormulaFunctions.min()).isEqualTo(0.0);
        assertThat(FormulaFunctions.max(3, 1, 2)).isEqualTo(3.0);
        assertThat(FormulaFunctions.max()).isEqualTo(0.0);
        assertThat(FormulaFunctions.sum(1, 2, 3)).isEqualTo(6.0);
        assertThat(FormulaFunctions.avg(1, 2, 3)).isEqualTo(2.0);
        assertThat(FormulaFunctions.avg()).isEqualTo(0.0);
        assertThat(FormulaFunctions.pow(2, 3)).isEqualTo(8.0);
        assertThat(FormulaFunctions.sqrt(16)).isEqualTo(4.0);
        assertThat(FormulaFunctions.mod(10, 3)).isEqualTo(1.0);
        assertThat(FormulaFunctions.intFunc(3.7)).isEqualTo(3L);
    }

    // ===== Date functions =====
    @Test
    void date_functions() {
        LocalDate d = LocalDate.of(2024, Month.JANUARY, 15);
        assertThat(FormulaFunctions.year(d)).isEqualTo(2024);
        assertThat(FormulaFunctions.year(null)).isEqualTo(0);
        assertThat(FormulaFunctions.month(d)).isEqualTo(1);
        assertThat(FormulaFunctions.month(null)).isEqualTo(0);
        assertThat(FormulaFunctions.day(d)).isEqualTo(15);
        assertThat(FormulaFunctions.day(null)).isEqualTo(0);
        assertThat(FormulaFunctions.dateAdd(d, 7)).isEqualTo(LocalDate.of(2024, 1, 22));
        assertThat(FormulaFunctions.dateAdd(null, 7)).isNull();
        assertThat(FormulaFunctions.dateDiff(d, LocalDate.of(2024, 1, 20))).isEqualTo(5);
        assertThat(FormulaFunctions.dateDiff(null, d)).isEqualTo(0);
        assertThat(FormulaFunctions.dateFormat(d, "yyyy-MM-dd")).isEqualTo("2024-01-15");
        assertThat(FormulaFunctions.dateFormat(null, "yyyy-MM-dd")).isNull();

        assertThat(FormulaFunctions.dateadd(d, 1, "day")).isEqualTo(d.plusDays(1));
        assertThat(FormulaFunctions.dateadd(d, 1, "WEEK")).isEqualTo(d.plusWeeks(1));
        assertThat(FormulaFunctions.dateadd(d, 1, "month")).isEqualTo(d.plusMonths(1));
        assertThat(FormulaFunctions.dateadd(d, 1, "years")).isEqualTo(d.plusYears(1));
        assertThat(FormulaFunctions.dateadd(d, 1, "unknown")).isEqualTo(d.plusDays(1));
        assertThat(FormulaFunctions.dateadd(null, 1, "day")).isNull();
        assertThat(FormulaFunctions.dateadd(d, 1, null)).isNull();

        assertThat(FormulaFunctions.weekday(LocalDate.of(2024, 1, 15))).isEqualTo(1); // Monday
        assertThat(FormulaFunctions.weekday(null)).isEqualTo(0);

        // EOMONTH same month
        assertThat(FormulaFunctions.eomonth(LocalDate.of(2024, 2, 5), 0))
            .isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(FormulaFunctions.eomonth(null, 1)).isNull();

        assertThat(FormulaFunctions.now()).isNotNull();
        assertThat(FormulaFunctions.today()).isNotNull();
    }

    // ===== Logical functions =====
    @Test
    void logical_functions() {
        assertThat(FormulaFunctions.ifFunc(true, "y", "n")).isEqualTo("y");
        assertThat(FormulaFunctions.ifFunc(false, "y", "n")).isEqualTo("n");
        assertThat(FormulaFunctions.isNull(null)).isTrue();
        assertThat(FormulaFunctions.isNull("x")).isFalse();
        assertThat(FormulaFunctions.ifNull(null, "d")).isEqualTo("d");
        assertThat(FormulaFunctions.ifNull("v", "d")).isEqualTo("v");
        assertThat(FormulaFunctions.and(true, true, true)).isTrue();
        assertThat(FormulaFunctions.and(true, false)).isFalse();
        assertThat(FormulaFunctions.and()).isTrue();
        assertThat(FormulaFunctions.or(false, false, true)).isTrue();
        assertThat(FormulaFunctions.or(false, false)).isFalse();
        assertThat(FormulaFunctions.or()).isFalse();
        assertThat(FormulaFunctions.not(false)).isTrue();
        assertThat(FormulaFunctions.not(true)).isFalse();
    }

    @Test
    void switch_function() {
        assertThat(FormulaFunctions.switchFunc("draft", "draft", "Draft", "active", "Active", "Unknown"))
            .isEqualTo("Draft");
        assertThat(FormulaFunctions.switchFunc("active", "draft", "Draft", "active", "Active", "Unknown"))
            .isEqualTo("Active");
        // No match, default present (odd number of pairs)
        assertThat(FormulaFunctions.switchFunc("zzz", "draft", "Draft", "Unknown")).isEqualTo("Unknown");
        // No match, no default (even pairs)
        assertThat(FormulaFunctions.switchFunc("zzz", "draft", "Draft")).isNull();
        // Null value
        assertThat(FormulaFunctions.switchFunc(null, "x", "y")).isNull();
        assertThat(FormulaFunctions.switchFunc("x", (Object[]) null)).isNull();
    }
}
