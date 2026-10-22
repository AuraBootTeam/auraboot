# Semantic YAML test fixtures

See PRD 16 (`ida/docs/16-prd-semantic-yml-dsl.md`) §2 spec.

## Layout

```
semantic/
├── valid/                              # Pass both JSON Schema and SemanticValidator
│   ├── sales.semantic.yml              # 5 metric types incl. simple/ratio/cumulative/derived
│   ├── inventory.semantic.yml          # warehouse RLS access policy
│   └── crm.semantic.yml                # conversion metric (trial→paid 30d)
└── invalid/
    ├── schema/                         # Should be rejected by JSON Schema validator
    │   ├── missing-version.semantic.yml      # required property missing
    │   ├── bad-metric-type.semantic.yml      # enum violation
    │   ├── wrong-version.semantic.yml        # const "0.1" violation
    │   ├── uppercase-code.semantic.yml       # pattern ^[a-z]... violation
    │   └── wrong-conversion-window.semantic.yml  # pattern \d+[dhwmy] violation
    └── validator/                      # Pass JSON Schema, rejected by SemanticValidator (W2)
        └── sql-injection-filter.semantic.yml     # contains ;/--/UNION/DROP in sql_filter
```

## Usage in tests

W1 D1-2 (Python validation as smoke test):

```bash
pip install jsonschema pyyaml
python /tmp/validate_v2.py   # script in PR
```

W2 D1-3 (Java SemanticYamlParser / SemanticValidator tests, JUnit 5 + networknt/json-schema-validator):

```java
@ParameterizedTest
@ValueSource(strings = {"sales", "inventory", "crm"})
void valid_yaml_passes_schema(String name) {
    SemanticModelDTO m = parser.parse(load("valid/" + name + ".semantic.yml"));
    assertThat(m).isNotNull();
}

@ParameterizedTest
@ValueSource(strings = {
    "missing-version", "bad-metric-type", "wrong-version",
    "uppercase-code", "wrong-conversion-window"
})
void schema_invalid_throws(String name) {
    assertThatThrownBy(() -> parser.parse(load("invalid/schema/" + name + ".semantic.yml")))
        .isInstanceOf(SemanticYamlInvalidException.class)
        .hasMessageContaining("SEMANTIC_YAML_INVALID");
}

@Test
void sql_injection_in_filter_rejected_by_validator() {
    SemanticModelDTO dto = parser.parse(load("invalid/validator/sql-injection-filter.semantic.yml"));
    assertThatThrownBy(() -> validator.validate(dto))
        .isInstanceOf(SemanticValidationException.class)
        .hasMessageContaining("SQL_INJECTION_DETECTED");
}
```

## Adding more fixtures

When adding a new schema/validator rule, add **both** a positive case (in `valid/`) and a negative case (in `invalid/schema/` or `invalid/validator/`). Negative cases must include a comment explaining which rule they exercise.
