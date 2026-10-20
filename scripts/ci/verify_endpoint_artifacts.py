"""Validate actual endpoint HTTP downloads against the pinned official OAS schema."""
import argparse
import hashlib
import json
from importlib.metadata import version
from pathlib import Path
from urllib.parse import urljoin
from jsonschema import Draft202012Validator, FormatChecker
from open_platform_endpoint_probe import DEFINITIONS, verify_endpoint_report


def verify(folder):
    folder=Path(folder)
    fixture=Path(__file__).resolve().parents[1]/'fixtures/open-platform-openapi'
    profile=json.loads((fixture/'profile.json').read_text())
    official=fixture/'openapi-3.1-schema-2022-10-07.json'
    if version('jsonschema')!='4.26.0' or hashlib.sha256(official.read_bytes()).hexdigest()!=profile['schemaSha256']:
        raise ValueError('Pinned endpoint OpenAPI verifier identity required')
    report=verify_endpoint_report(json.loads((folder/'result.json').read_text()))
    checker=FormatChecker(formats=['date','date-time','uri','uri-reference','regex'])
    validator=Draft202012Validator(json.loads(official.read_text()),format_checker=checker)
    rows=[]
    for entry,(name,definition) in zip(report['contracts'],DEFINITIONS.items()):
        path=folder/(entry['code']+'.openapi.json');doc=json.loads(path.read_text())
        if hashlib.sha256(json.dumps(doc,sort_keys=True).encode()).hexdigest()!=entry['openapiSha256']:
            raise ValueError('Endpoint downloaded OpenAPI bytes changed')
        # Resolve relative OAuth URL references only in the validation copy, preserving downloaded bytes.
        resolved=json.loads(json.dumps(doc))
        for scheme in resolved.get('components',{}).get('securitySchemes',{}).values():
            for flow in scheme.get('flows',{}).values():
                for key in ('tokenUrl','authorizationUrl','refreshUrl'):
                    if key in flow:
                        checker.check(flow[key],'uri-reference');flow[key]=urljoin('https://openapi-validation.invalid/',flow[key])
        validator.validate(resolved)
        for schema in doc['components']['schemas'].values():Draft202012Validator.check_schema(schema)
        types=(folder/(entry['code']+'.d.ts')).read_bytes()
        if hashlib.sha256(types).hexdigest()!=entry['typesSha256']:raise ValueError('Endpoint downloaded types bytes changed')
        rows.append({'code':entry['code'],'openapiSha256':hashlib.sha256(path.read_bytes()).hexdigest(),'typesSha256':entry['typesSha256']})
    return {'profile':'controller-endpoint-openapi-v1','executed':len(rows),'passed':len(rows),'documents':rows,'schemaSha256':profile['schemaSha256']}


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('folder');parser.add_argument('receipt');args=parser.parse_args()
    receipt=Path(args.receipt)
    if receipt.exists():raise SystemExit('Endpoint OpenAPI receipt already exists')
    receipt.write_text(json.dumps(verify(args.folder),indent=2)+'\n')
