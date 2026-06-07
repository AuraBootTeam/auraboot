package com.auraboot.framework.decision.ast;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Condition AST must deserialize from the JSON shape produced by the designer / stored
 * in {@code ab_drt_version.content_json} (docs/1.md §14.2) and evaluate to the same result.
 */
class AstJsonDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ConditionAstEvaluator evaluator = new ConditionAstEvaluator();

    private static final String JSON = """
        {
          "type": "group",
          "op": "AND",
          "children": [
            {
              "type": "compare",
              "enabled": true,
              "left": { "type": "path", "scope": "record", "path": "data.amount", "dataType": "decimal" },
              "operator": "GT",
              "right": { "type": "literal", "value": 10000, "dataType": "decimal" }
            },
            {
              "type": "compare",
              "left": { "type": "path", "scope": "record", "path": "data.priority", "dataType": "enum" },
              "operator": "EQ",
              "right": { "type": "literal", "value": "HIGH", "dataType": "enum" }
            }
          ]
        }
        """;

    @Test
    void deserializesPolymorphicAstAndEvaluates() throws Exception {
        ConditionNode node = mapper.readValue(JSON, ConditionNode.class);
        assertThat(node).isInstanceOf(ConditionNode.GroupNode.class);

        DecisionContext match = DecisionContext.builder()
                .record(Map.of("amount", 20000, "priority", "HIGH")).build();
        assertThat(evaluator.evaluate(node, match).result()).isEqualTo(Truth.TRUE);

        DecisionContext noMatch = DecisionContext.builder()
                .record(Map.of("amount", 500, "priority", "HIGH")).build();
        assertThat(evaluator.evaluate(node, noMatch).result()).isEqualTo(Truth.FALSE);
    }

    @Test
    void enabledAbsentDefaultsToActive() throws Exception {
        // second child omits "enabled" → must be treated as active
        ConditionNode node = mapper.readValue(JSON, ConditionNode.class);
        ConditionNode.GroupNode g = (ConditionNode.GroupNode) node;
        ConditionNode.CompareNode second = (ConditionNode.CompareNode) g.children().get(1);
        assertThat(second.active()).isTrue();
    }
}
