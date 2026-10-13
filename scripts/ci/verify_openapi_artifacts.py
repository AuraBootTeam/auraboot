import json, sys, hashlib
from importlib.metadata import version
from jsonschema import FormatChecker
from jsonschema.exceptions import FormatError
from urllib.parse import urljoin
from pathlib import Path
from jsonschema import Draft202012Validator
folder = Path(sys.argv[1]); fixture = Path(__file__).resolve().parents[1] / 'fixtures/open-platform-openapi'
official = fixture / 'openapi-3.1-schema-2022-10-07.json'
profile = json.loads((fixture / 'profile.json').read_text())
def require(value, message):
    if not value: raise ValueError(message)
require(version('jsonschema') == '4.26.0', 'Pinned jsonschema verifier required')
require(hashlib.sha256(official.read_bytes()).hexdigest() == profile['schemaSha256'], 'Official OAS schema bytes changed')
formats = ['date', 'date-time', 'uri', 'uri-reference', 'regex']
require(set(formats).issubset(FormatChecker.checkers), 'Required format checkers unavailable')
checker = FormatChecker(formats=formats)
def document_errors(document):
    # OAS3.1 permits URL relative references. Resolve OAuth URLs only in validation
    # context; preserve original bytes and verify URI-reference syntax first.
    resolved = json.loads(json.dumps(document))
    try:
        for scheme in resolved.get('components', {}).get('securitySchemes', {}).values():
            if scheme.get('type') != 'oauth2': continue
            for flow in scheme.get('flows', {}).values():
                for key in ['tokenUrl', 'authorizationUrl', 'refreshUrl']:
                    value = flow.get(key)
                    if isinstance(value, str):
                        checker.check(value, 'uri-reference')
                        flow[key] = urljoin('https://openapi-validation.invalid/', value)
    except FormatError as error:
        return [error]
    return list(Draft202012Validator(json.loads(official.read_text()), format_checker=checker).iter_errors(resolved))

documents = {name: json.loads((folder / (name + ".json")).read_text()) for name in ["resource", "query"]}
rows = []
for name, doc in documents.items():
    errors = document_errors(doc)
    require(not errors, name + ' official OpenAPI structure/format invalid')
    expected_paths = ({'/api/open/v1/resources/fixture.parts': {'get', 'post'},
                       '/api/open/v1/resources/fixture.parts/{recordPid}': {'get', 'patch', 'delete'}}
                      if name == 'resource' else {'/api/open/v1/queries/fixture.summary:execute': {'post'}})
    require(set(doc.get('paths', {})) == set(expected_paths)
            and all(set(doc['paths'][path]) == methods for path, methods in expected_paths.items()),
            name + ' fixture endpoint surface missing or changed')
    for schema in doc['components']['schemas'].values(): Draft202012Validator.check_schema(schema)
    rows.append({"name": name + " official OpenAPI structure", "passed": True})
def check(name, document, schema, payload, valid):
    validator = Draft202012Validator({"$ref": "#/components/schemas/" + schema, "components": documents[document]["components"]}, format_checker=checker)
    actual = validator.is_valid(payload)
    require(actual == valid, name + ' payload expectation violated')
    rows.append({"name": name, "passed": True, "expectedValid": valid})
check("create typed nested nullable fields", "resource", "CreateInput", {"title":"Part", "cost":"1.25", "profile":{"label":"Blue", "secret":None}, "tags":["one",None]}, True)
check("create missing required title", "resource", "CreateInput", {"cost":1.25}, False)
check("create empty", "resource", "CreateInput", {}, False)
check("create source mapping rejected", "resource", "CreateInput", {"title":"Part", "internal_name":"leak"}, False)
check("create read-only alias rejected", "resource", "CreateInput", {"title":"Part", "day":"2026-10-10"}, False)
check("nested required label", "resource", "CreateInput", {"title":"Part", "profile":{}}, False)
check("nested unknown mapping", "resource", "CreateInput", {"title":"Part", "profile":{"label":"Blue", "internal_label":"leak"}}, False)
check("array budget", "resource", "CreateInput", {"title":"Part", "tags":["x"]*1001}, False)
check("nonfinite decimal", "resource", "CreateInput", {"title":"Part", "cost":"Infinity"}, False)
check("resource installation omitted fields", "resource", "Resource", {"pid":"part-x", "profile":{}, "cost":None}, True)
check("resource numeric identity", "resource", "Resource", {"pid":"123"}, False)
check("resource write-only input", "resource", "Resource", {"pid":"part-x", "inputOnly":"leak"}, False)
check("patch nullable", "resource", "UpdateInput", {"cost":None}, True)
check("patch empty", "resource", "UpdateInput", {}, False)
check("patch nonnullable", "resource", "UpdateInput", {"title":None}, False)
check("patch nested required label", "resource", "UpdateInput", {"profile":{"secret":"x"}}, False)
check("query typed array parameter", "query", "QueryParameters", {"zones":["A","B"]}, True)
check("query missing required parameter", "query", "QueryParameters", {}, False)
check("query wrong scalar array item", "query", "QueryParameters", {"zones":[12]}, False)
check("query unknown parameter", "query", "QueryParameters", {"zones":["A"],"tenantId":1}, False)
check("query required envelope", "query", "QueryRequest", {}, False)
check("query envelope unknown control", "query", "QueryRequest", {"parameters":{"zones":["A"]},"tenantId":1}, False)
check("query result alias", "query", "QueryItem", {"count":3}, True)
check("query source alias rejected", "query", "QueryItem", {"private_total":3}, False)
event={"id":"evt_fixture", "type":"open.resource.fixture.parts.updated", "schemaVersion":3, "occurredAt":"2026-10-10T00:00:00Z", "subject":{"type":"fixture.parts", "pid":"part-x"}, "recordVersion":2, "correlationId":None, "data":{"pid":"part-x"}}
check("event wire body", "resource", "ModelEvent", event, True)
check("event internal context", "resource", "ModelEvent", {**event,"authorization_context":"leak"}, False)
check("event wrong schema", "resource", "ModelEvent", {**event,"schemaVersion":2}, False)
check("resource ISO date and time", "resource", "Resource", {"pid":"part-x", "day":"2026-10-10", "at":"2026-10-10T00:00:00Z"}, True)
check("resource impossible date", "resource", "Resource", {"pid":"part-x", "day":"2026-02-30"}, False)
check("resource malformed datetime", "resource", "Resource", {"pid":"part-x", "at":"not-a-date"}, False)
check("resource datetime without timezone", "resource", "Resource", {"pid":"part-x", "at":"2026-10-10T00:00:00"}, False)
check("event malformed occurredAt", "resource", "ModelEvent", {**event,"occurredAt":"not-a-date"}, False)
bad = json.loads(json.dumps(documents['resource']))
bad['components']['securitySchemes']['oauth2ClientCredentials']['flows']['clientCredentials']['tokenUrl']='bad uri'
require(bool(document_errors(bad)), 'Malformed token URL accepted')
rows.append({'name':'document malformed token URL','passed':True,'expectedValid':False})
require([row['name'] for row in rows] == profile['cases'] and all(row['passed'] is True for row in rows), 'OpenAPI case denominator mismatch')
output = Path(sys.argv[2]); require(not output.exists(), 'OpenAPI receipt already exists')
output.write_text(json.dumps({'profile':profile['id'],'executed':len(rows),'passed':len(rows),'formats':formats,
 'schemaSha256':profile['schemaSha256'],'dependencyVersion':version('jsonschema'),'oauthUrlValidation':'OAS3.1 relative references resolved in validation context; original bytes preserved',
 'documents':{name:hashlib.sha256((folder/(name+'.json')).read_bytes()).hexdigest() for name in documents},
 'results':rows},indent=2)+'\n')
print('Independent OpenAPI structure, component schema, payload and format verification passed:',len(rows))
