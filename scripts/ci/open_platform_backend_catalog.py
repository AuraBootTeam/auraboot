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
            # Only existing string ValueSource formats are admitted. New generators
            # must get an explicit expansion rather than silently passing by count.
            values = re.fullmatch(r'\s*(?:\(name\s*=\s*"((?:\\.|[^"\\])*)"\)\s*)?'
                                  r'@(?:[\w]+\.)*ValueSource\(strings\s*=\s*\{(.*?)\}\)\s*', header, re.S)
            require(values is not None, "Unsupported parameterized source/display name")
            display, arguments = values.groups()
            strings = re.findall(r'"((?:\\.|[^"\\])*)"', arguments)
            require(strings and re.fullmatch(r'\s*String\s+\w+\s*'
                                            r'(?:,\s*@(?:[\w]+\.)*TempDir\s+(?:java\.nio\.file\.)?Path\s+\w+\s*)?', parameters),
                    "Unsupported parameterized arguments")
            require(re.sub(r'"(?:\\.|[^"\\])*"|[\s,]', '', arguments) == '',
                    "Invalid ValueSource")
            parameter = parameters.split(',')[0].strip().split()[-1]
            if display is not None:
                display = json.loads(chr(34) + display + chr(34))
                require(display.count('{0}') == 1 and not re.search(r'[{}]', display.replace('{0}', '')),
                        "Unsupported parameterized display placeholders")
            for i, value in enumerate(strings, 1):
                value = json.loads(chr(34) + value + chr(34))
                result.append(display.replace('{0}', value) if display is not None
                              else f"[{i}] {parameter}={value}")
    require(result and len(result) == len(set(result)), "Empty or duplicate declared cases")
    return result


def plan(root):
    root = Path(root)
    profile_path = root / "scripts/ci/open-platform-backend.profile.json"
    profile = json.loads(profile_path.read_text())
    require(profile['schemaVersion'] == 1 and profile['id'] == 'open-platform-backend-v1',
            "Invalid backend profile")
    test_root = root / "platform/src/test/java"
    classes = list(profile['classes'])
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
            'expectedClasses': len(rows), 'expectedCases': sum(len(row['cases']) for row in rows)}


def reconcile(expected, results):
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
            'passed': sum(actual.values()), 'classes': len(expected['classes']), 'verdict': 'PASS'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['plan', 'verify'])
    parser.add_argument('--root', required=True)
    parser.add_argument('--plan', required=True)
    parser.add_argument('--selectors')
    parser.add_argument('--results')
    parser.add_argument('--receipt')
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
        output = reconcile(current, args.results)
        Path(args.receipt).write_text(json.dumps(output, indent=2) + '\n')
        print(json.dumps(output))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, ET.ParseError) as error:
        raise SystemExit('Backend catalog rejected: ' + str(error))
