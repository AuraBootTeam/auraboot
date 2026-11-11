"""Hermetic verifier checks; real product proof is the native installation gate."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('probes', Path(__file__).with_name('oss-security-probes.py'))
probes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probes)


class SmsProbeTest(unittest.TestCase):
    def run_probe(self, response):
        self.calls, self.checks = [], []

        def request(path, method, body, headers):
            self.calls.append((path, method, body, headers))
            return response(len(self.calls))

        def require(condition, message):
            if not condition:
                raise RuntimeError(message)

        probes.verify_sms_mobile_window(request, lambda *a, **kw: self.checks.append((a, kw)),
                                        require, 'test')

    def test_exact_mobile_window_and_no_sms_send(self):
        self.run_probe(lambda n: {'code': 35000, 'message': 'Too many login attempts'} if n == 11
                       else {'code': 10000, 'message': 'Validation failed',
                             'context': 'Invalid or expired verification code'})
        self.assertEqual(len(self.checks), 1)
        self.assertEqual(len({c[2]['mobile'] for c in self.calls}), 1)
        self.assertEqual(len({c[2]['email'] for c in self.calls}), 11)
        self.assertEqual({c[0] for c in self.calls}, {'/api/auth/login/sms'})
        self.assertEqual({c[1] for c in self.calls}, {'POST'})

    def test_missing_limit_is_red(self):
        with self.assertRaisesRegex(RuntimeError, 'budget bypassed'):
            self.run_probe(lambda n: {'code': 10000, 'message': 'Validation failed',
                                     'context': 'Invalid or expired verification code'})
        self.assertEqual(self.checks, [])

    def test_premature_ip_limit_is_red(self):
        with self.assertRaisesRegex(RuntimeError, 'budget bypassed'):
            self.run_probe(lambda n: {'code': 35000, 'message': 'Too many login attempts'})

    def test_development_context_detail_contract(self):
        self.run_probe(lambda n: {'code': 35000, 'message': 'Too many login attempts'} if n == 11
                       else {'code': 10000, 'message': 'Validation failed',
                             'context': {'detail': 'Invalid or expired verification code'}})
        self.assertEqual(len(self.checks), 1)

    def test_wrong_code_or_wrong_context_field_is_red(self):
        for value in [{'code': 35000, 'context': 'Invalid or expired verification code'},
                      {'code': 10000, 'context': {'messageKey': 'Invalid or expired verification code'}}]:
            with self.subTest(value=value), self.assertRaisesRegex(RuntimeError, 'real OTP verifier'):
                self.run_probe(lambda n: value)

    def test_disabled_sms_or_server_error_cannot_pass(self):
        for value in [{'code': 35000, 'message': 'channel disabled'},
                      {'code': 35000, '_httpStatus': 500, 'message': 'verification failed'},
                      {'code': 0, 'data': {'jwt': 'must-never-issue'}}]:
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                self.run_probe(lambda n: value)


if __name__ == '__main__':
    unittest.main()
