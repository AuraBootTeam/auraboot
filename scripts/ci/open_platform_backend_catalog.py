"""Expand the release backend profile and reconcile exact JUnit results."""
import argparse
import collections
import hashlib
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET


def require(condition, message):
    if not condition:
        raise ValueError(message)


def without_comments(source):
    # Preserve Java string/character literals while removing comments.
    return re.sub(r'("(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\')|//[^\n]*|/\*.*?\*/',
                  lambda match: match.group(1) or " ", source, flags=re.S)


def cases(source):
    source = without_comments(source)
    require(not re.search(r'@(?:[\w]+\.)*(Disabled|TestFactory|TestTemplate|RepeatedTest|Nested)\b', source),
            "Unsupported or disabled JUnit declaration in blocking scope")
    annotations = list(re.finditer(r'@(?:[\w]+\.)*(Test|ParameterizedTest)\b', source))
    result = []
    for index, annotation in enumerate(annotations):
        end = annotations[index + 1].start() if index + 1 < len(annotations) else len(source)
        text = source[annotation.end():end]
        method = re.search(r'\bvoid\s+(\w+)\s*\(([^)]*)\)', text)
        require(method is not None, "Cannot resolve JUnit method")
        name, parameters = method.groups()
        header = text[:method.start()]
        if annotation.group(1) == "Test":
            require(not parameters.strip(), "Ordinary blocking test must have no parameters")
            preceding = re.search(r'(?:\s*@[\w.]+(?:\((?:[^()"\'{}]|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\')*\))?)+\s*$',
                                  source[:annotation.start()])
            decorations = (preceding.group() if preceding else '') + header
            labels = re.findall(r'@(?:[\w]+\.)*DisplayName\(\s*("(?:\\.|[^"\\])*")\s*\)', decorations)
            require(len(labels) <= 1, "Duplicate display names")
            result.append(json.loads(labels[0]) if labels else name + "()")
        else:
            # Only explicit string/boolean ValueSource formats are admitted. New generators
            # must get an explicit expansion rather than silently passing by count.
            values = re.fullmatch(r'\s*(?:\(name\s*=\s*"((?:\\.|[^"\\])*)"\)\s*)?'
                                  r'@(?:[\w]+\.)*ValueSource\((strings|booleans)\s*=\s*\{(.*?)\}\)\s*', header, re.S)
            require(values is not None, "Unsupported parameterized source/display name")
            display, kind, arguments = values.groups()
            if kind == 'strings':
                strings = re.findall(r'"((?:\\.|[^"\\])*)"', arguments)
                parameter_type = 'String'
                require(re.sub(r'"(?:\\.|[^"\\])*"|[\s,]', '', arguments) == '', "Invalid ValueSource")
            else:
                strings = [value.strip() for value in arguments.split(',')]
                parameter_type = 'boolean'
                require(strings and all(value in ['false', 'true'] for value in strings), "Invalid boolean ValueSource")
            require(strings and re.fullmatch(r'\s*' + parameter_type + r'\s+\w+\s*'
                                            r'(?:,\s*@(?:[\w]+\.)*TempDir\s+(?:java\.nio\.file\.)?Path\s+\w+\s*)?', parameters),
                    "Unsupported parameterized arguments")
            parameter = parameters.split(',')[0].strip().split()[-1]
            if display is not None:
                display = json.loads(chr(34) + display + chr(34))
                require(display.count('{0}') == 1 and not re.search(r'[{}]', display.replace('{0}', '')),
                        "Unsupported parameterized display placeholders")
            for i, value in enumerate(strings, 1):
                if kind == 'strings': value = json.loads(chr(34) + value + chr(34))
                result.append(display.replace('{0}', value) if display is not None
                              else f"[{i}] {parameter}={value}")
    require(result and len(result) == len(set(result)), "Empty or duplicate declared cases")
    return result


def coverage_plan(root, policy):
    minimum = policy['minimumLinePercent']
    require(type(minimum) is int and 80 <= minimum <= 100, "Coverage threshold must be at least 80%")
    source_root = root / "platform/src/main/java"
    names = list(policy['classes'])
    for package in policy['sourcePackages']:
        require(re.fullmatch(r'[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+', package), "Invalid coverage package")
        directory = source_root / package.replace('.', '/')
        require(directory.is_dir(), "Missing coverage source package")
        names.extend('.'.join(p.relative_to(source_root).with_suffix('').parts)
                     for p in sorted(directory.rglob('*.java')))
    require(names and len(names) == len(set(names)), "Empty or duplicate coverage sources")
    exemptions = policy['nonExecutableClasses']
    require(type(exemptions) is list and len(exemptions) == len(set(exemptions))
            and set(exemptions) <= set(names), "Invalid non-executable coverage declarations")
    rows, excluded = [], []
    for name in sorted(names):
        require(re.fullmatch(r'[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+', name), "Invalid coverage source")
        path = source_root / (name.replace('.', '/') + '.java')
        data = path.read_bytes()
        row = {'class': name, 'source': str(path.relative_to(root)),
               'sha256': hashlib.sha256(data).hexdigest()}
        if name in exemptions:
            source = without_comments(data.decode())
            require(re.search(r'public\s+interface\s+' + re.escape(path.stem) + r'\b', source)
                    and not re.search(r'\b(?:default|static|class|enum|record)\b|=', source),
                    "Executable code cannot be excluded from coverage")
            excluded.append(row)
        else:
            rows.append(row)
    require(rows, "Empty executable coverage scope")
    return {'minimumLinePercent': minimum, 'sources': rows, 'nonExecutable': excluded}


def coverage_result(expected, report):
    require(report is not None and Path(report).is_file(), "Missing JaCoCo report")
    tree = ET.parse(report).getroot()
    require(tree.tag == 'report', "Invalid JaCoCo report root")
    sources = {}
    for package in tree.findall('package'):
        for source in package.findall('sourcefile'):
            key = package.get('name', '') + '/' + source.get('name', '')
            require(key not in sources, "Duplicate JaCoCo source")
            sources[key] = source
    rows, failures = [], []
    minimum = expected['coverage']['minimumLinePercent']
    for wanted in expected['coverage']['sources']:
        key = wanted['class'].replace('.', '/') + '.java'
        require(key in sources, "Missing coverage source: " + wanted['class'])
        source = sources[key]
        counters = [c for c in source.findall('counter') if c.get('type') == 'LINE']
        require(len(counters) == 1, "Missing or duplicate LINE counter: " + wanted['class'])
        counter = counters[0]
        require(all(re.fullmatch(r'0|[1-9][0-9]*', counter.get(k, '')) for k in ['covered', 'missed']),
                "Invalid line counts: " + wanted['class'])
        covered, missed = int(counter.get('covered')), int(counter.get('missed'))
        require(covered + missed > 0, "Empty executable coverage: " + wanted['class'])
        lines, actual_covered, actual_missed = set(), 0, 0
        for line in source.findall('line'):
            require(all(re.fullmatch(r'0|[1-9][0-9]*', line.get(k, '')) for k in ['nr', 'ci', 'mi']),
                    "Invalid coverage line: " + wanted['class'])
            number = int(line.get('nr'))
            require(number > 0 and number not in lines, "Duplicate or invalid coverage line")
            lines.add(number)
            if int(line.get('ci')) > 0: actual_covered += 1
            elif int(line.get('mi')) > 0: actual_missed += 1
            else: require(False, "Empty executable line")
        require((covered, missed) == (actual_covered, actual_missed),
                "LINE counter differs from instruction-bearing lines: " + wanted['class'])
        row = {'class': wanted['class'], 'covered': covered, 'total': covered + missed,
               'minimumLinePercent': minimum, 'passed': covered * 100 >= minimum * (covered + missed)}
        rows.append(row)
        if not row['passed']: failures.append(wanted['class'] + f" {covered}/{covered + missed}")
    require(not failures, "Coverage below blocking threshold: " + '; '.join(failures))
    return {'sources': rows, 'minimumLinePercent': minimum, 'verdict': 'PASS'}


def plan(root):
    root = Path(root)
    profile_path = root / "scripts/ci/open-platform-backend.profile.json"
    profile = json.loads(profile_path.read_text())
    require(profile['schemaVersion'] == 1 and profile['id'] == 'open-platform-backend-v1',
            "Invalid backend profile")
    test_root = root / "platform/src/test/java"
    classes = list(profile['classes'])
    required_import_classes = {
        'com.auraboot.framework.plugin.service.PluginDirectoryLoaderTest',
        'com.auraboot.framework.plugin.service.impl.PluginZipResourceReaderTest',
        'com.auraboot.framework.plugin.service.PluginImportTwoPhaseReferenceIntegrationTest',
    }
    require(required_import_classes.issubset(classes), "Public plugin declaration regression classes missing")
    for package in profile['packages']:
        directory = test_root / package.replace('.', '/')
        require(directory.is_dir(), "Missing blocking package")
        classes.extend('.'.join(p.relative_to(test_root).with_suffix('').parts)
                       for p in sorted(directory.rglob('*Test.java')))
    require(classes and len(classes) == len(set(classes)), "Empty or duplicate profile classes")
    rows = []
    for name in sorted(classes):
        require(re.fullmatch(r'[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+', name), "Invalid class")
        path = test_root / (name.replace('.', '/') + '.java')
        data = path.read_bytes()
        rows.append({'class': name, 'source': str(path.relative_to(root)),
                     'sha256': hashlib.sha256(data).hexdigest(), 'cases': cases(data.decode())})
    return {'schemaVersion': 1, 'profile': profile['id'], 'requirements': profile['requirements'],
            'profileSha256': hashlib.sha256(profile_path.read_bytes()).hexdigest(), 'classes': rows,
            'coverage': coverage_plan(root, profile['coverage']),
            'expectedClasses': len(rows), 'expectedCases': sum(len(row['cases']) for row in rows)}


def reconcile(expected, results, coverage=None):
    wanted = collections.Counter((row['class'], name)
                                 for row in expected['classes'] for name in row['cases'])
    actual = collections.Counter()
    files = sorted(Path(results).glob('TEST-*.xml'))
    require(files, "Missing JUnit results")
    for path in files:
        suite = ET.parse(path).getroot()
        tests = suite.findall('testcase')
        require(suite.tag == 'testsuite' and int(suite.get('tests', '-1')) == len(tests),
                "Invalid JUnit suite count")
        require(all(int(suite.get(key, '-1')) == 0 for key in ['failures', 'errors', 'skipped']),
                "JUnit suite failed or skipped")
        for test in tests:
            require(not any(test.find(tag) is not None for tag in ['failure', 'error', 'skipped']),
                    "Required case failed or skipped")
            actual[(test.get('classname'), test.get('name'))] += 1
    require(actual == wanted, "JUnit case identities differ from blocking profile")
    return {'profile': expected['profile'], 'executed': sum(actual.values()),
            'passed': sum(actual.values()), 'classes': len(expected['classes']),
            'coverage': coverage_result(expected, coverage), 'verdict': 'PASS'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['plan', 'verify'])
    parser.add_argument('--root', required=True)
    parser.add_argument('--plan', required=True)
    parser.add_argument('--selectors')
    parser.add_argument('--results')
    parser.add_argument('--receipt')
    parser.add_argument('--coverage')
    args = parser.parse_args()
    current = plan(args.root)
    if args.mode == 'plan':
        Path(args.plan).write_text(json.dumps(current, indent=2) + '\n')
        require(args.selectors is not None, "Selectors output required")
        Path(args.selectors).write_text(''.join(row['class'] + '\n' for row in current['classes']))
        print(json.dumps({key: current[key] for key in ['profile', 'expectedClasses', 'expectedCases']}))
    else:
        require(args.receipt and not Path(args.receipt).exists(), "Receipt already exists or is missing")
        require(current == json.loads(Path(args.plan).read_text()), "Backend source/profile drift")
        require(args.results and args.receipt, "Results and receipt required")
        output = reconcile(current, args.results, args.coverage)
        Path(args.receipt).write_text(json.dumps(output, indent=2) + '\n')
        print(json.dumps(output))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, ET.ParseError) as error:
        raise SystemExit('Backend catalog rejected: ' + str(error))
