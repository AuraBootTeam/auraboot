"""Exact-source focused backend diagnosis; never qualifies the full OSS nightly."""
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


def plan(platform, arguments):
    if not arguments:
        return {'mode': 'full', 'fullNightlyQualification': True, 'classes': [], 'gradleArguments': []}
    classes = []
    while arguments:
        if len(arguments) < 2 or arguments[0] != '--test-class':
            raise ValueError('Only repeated --test-class FQCN selectors are admitted')
        name = arguments[1]
        arguments = arguments[2:]
        if not re.fullmatch(r'(?:[a-zA-Z_]\w*\.)+[A-Za-z_]\w*', name) or name in [r['class'] for r in classes]:
            raise ValueError('Unique exact test class required; patterns and methods are forbidden')
        relative = Path(*name.split('.')).with_suffix('.java')
        roots = [platform] + sorted(p for p in platform.iterdir() if p.is_dir() and p.name.startswith('platform-'))
        matches = [(root, root / 'src/test/java' / relative) for root in roots
                   if (root / 'src/test/java' / relative).is_file()]
        if len(matches) != 1:
            raise ValueError('Test must have exactly one source owner: ' + name)
        owner, source = matches[0]
        if source.resolve() != source.absolute():
            raise ValueError('Physical test source required')
        package = name.rsplit('.', 1)[0]
        if not re.search(r'\bpackage\s+' + re.escape(package) + r'\s*;', source.read_text()):
            raise ValueError('Package must match the requested class')
        classes.append({'class': name, 'module': owner.name if owner != platform else 'platform',
                        'task': ':' + (owner.name + ':' if owner != platform else '')})
    gradle = []
    for task in dict.fromkeys(row['task'] for row in classes):
        gradle.extend([task + 'cleanTest', task + 'test'])
        for row in classes:
            if row['task'] == task:
                gradle.extend(['--tests', row['class']])
    return {'mode': 'focused-diagnosis', 'fullNightlyQualification': False,
            'classes': classes, 'gradleArguments': gradle}


def reconcile(selection, junit):
    results = []
    for row in selection['classes']:
        directory = junit / row['module'] / 'test'
        cases = []
        for file in directory.glob('TEST-*.xml'):
            for case in ET.parse(file).iter('testcase'):
                if case.get('classname') == row['class'] or case.get('classname', '').startswith(row['class'] + '$'):
                    cases.append(case)
        result = {**row, 'executed': sum(case.find('skipped') is None for case in cases),
                  'failed': sum(case.find('failure') is not None or case.find('error') is not None for case in cases),
                  'skipped': sum(case.find('skipped') is not None for case in cases)}
        results.append(result)
    return {'mode': 'focused-diagnosis', 'fullNightlyQualification': False, 'classes': results,
            'passed': bool(results) and all(r['executed'] > 0 and not r['failed'] and not r['skipped'] for r in results)}


def main():
    if sys.argv[1] == 'plan':
        result = plan(Path(sys.argv[2]).resolve(), sys.argv[4:])
        with Path(sys.argv[3]).open('x') as output:
            output.write(json.dumps(result, indent=2) + '\n')
        print('\n'.join(result['gradleArguments']))
    elif sys.argv[1] == 'reconcile' and len(sys.argv) == 4:
        selection_file = Path(sys.argv[2])
        result = reconcile(json.loads(selection_file.read_text()), Path(sys.argv[3]))
        with selection_file.with_name('focused-test-results.json').open('x') as output:
            output.write(json.dumps(result, indent=2) + '\n')
        return 0 if result['passed'] else 1
    else:
        raise ValueError('Unknown backend selection command')
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (ValueError, OSError, ET.ParseError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(2)
