"""Pure admission primitives for the existing procurement Native runner.

No service calls, process management, database access, policy approval or provider I/O.
Files classified here are evidence only; Root must supply exact approved source pins.
"""
from pathlib import Path
import hashlib
import re

PRODUCER_CLASS = 'com.auraboot.framework.plugin.pf4j.ProcurementOrderUiFixtureProducerIT'
PRODUCER_METHOD = 'producePendingFailedUnknownAndMixedSupplierExecutionPrerequisites'
PRODUCER_SOURCES = (
    'platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementOrderUiFixtureProducerIT.java',
    'platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementPublicSourceNativeFixture.java',
    'platform/src/test/java/com/auraboot/framework/notification/sourcebinding/ProductQuoteNativeInitialization.java',
)
PRODUCER_FAMILIES = (
    'com/auraboot/framework/plugin/pf4j/ProcurementOrderUiFixtureProducerIT',
    'com/auraboot/framework/plugin/pf4j/ProcurementPublicSourceNativeFixture',
    'com/auraboot/framework/notification/sourcebinding/ProductQuoteNativeInitialization',
)
S06_FAMILIES = tuple('com/auraboot/framework/notification/sourcebinding/' + name for name in (
    'RegisteredSourceBindingPostgresIT', 'ProductQuoteReminderNativeFacet',
    'TestOwnedNotificationIntentTeardown', 'ProductQuoteNativePluginDirectory',
    'ProductQuoteNativeInitialization',
))


def require(value, code):
    if not value:
        raise ValueError(code)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def validate_pins(core, product):
    require(all(isinstance(value, str) and re.fullmatch('[a-f0-9]{40}', value)
                for value in (core, product)), 'EXPLICIT_ACTUAL_FULL_SOURCE_PINS_REQUIRED')


def validate_source_hashes(expected, actual):
    require(isinstance(expected, dict) and set(expected) == set(PRODUCER_SOURCES),
            'EXACT_PRODUCER_SOURCE_ROSTER_REQUIRED')
    require(all(isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value)
                for value in expected.values()), 'SEALED_SOURCE_HASH_INVALID')
    require(expected == actual, 'SEALED_SOURCE_BYTES_DRIFT')


def compiled_inventory(root, families):
    root = Path(root).resolve(strict=True)
    require(len(families) == len(set(families)), 'DUPLICATE_COMPILED_FAMILY')
    inventory = {}
    for family in families:
        require(isinstance(family, str) and re.fullmatch(r'com/auraboot/framework/[A-Za-z0-9_/]+', family),
                'COMPILED_FAMILY_INVALID')
        main = root / (family + '.class')
        require(main.is_file(), 'ACTUAL_COMPILED_NATIVE_CLASSES_REQUIRED')
        files = [main, *sorted(main.parent.glob(main.stem + '$*.class'))]
        for file in files:
            require(not file.is_symlink() and file.resolve(strict=True).is_relative_to(root),
                    'COMPILED_CLASS_OUTSIDE_APPLICATION')
            relative = str(file.relative_to(root))
            require(relative not in inventory, 'DUPLICATE_COMPILED_CLASS')
            inventory[relative] = sha(file)
    return inventory


def validate_inventory(expected, actual):
    require(isinstance(expected, dict) and expected and expected == actual,
            'COMPILED_NATIVE_CLASS_BYTES_OR_ROSTER_DRIFT')


def validate_fresh_stat(stat, started_ns):
    require(type(started_ns) is int and started_ns > 0, 'NATIVE_START_TIME_REQUIRED')
    require(stat.st_mtime_ns >= started_ns and stat.st_ctime_ns >= started_ns,
            'NATIVE_RECEIPT_NOT_FRESH')
    birth = getattr(stat, 'st_birthtime_ns', None)
    if birth is None and hasattr(stat, 'st_birthtime'):
        birth = int(stat.st_birthtime * 1_000_000_000)
    require(birth is not None and birth >= started_ns, 'NATIVE_RECEIPT_BIRTH_NOT_FRESH')


def qualify_selector(exit_code, counts, cases, sources_unchanged, classes_unchanged):
    require(type(exit_code) is int and exit_code == 0, 'NATIVE_EXIT_NOT_ZERO')
    require(isinstance(counts, dict) and all(type(value) is int for value in counts.values())
            and counts == {'tests': 1, 'failures': 0, 'errors': 0, 'skipped': 0},
            'EXACT_ONE_NATIVE_PASS_REQUIRED')
    require(cases == [{'className': PRODUCER_CLASS, 'method': PRODUCER_METHOD, 'state': 'passed'}],
            'NATIVE_METHOD_OR_CLASS_DRIFT')
    require(sources_unchanged is True and classes_unchanged is True, 'NATIVE_RUN_BYTES_DRIFT')
