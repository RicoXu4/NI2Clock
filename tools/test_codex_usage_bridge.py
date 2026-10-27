import io
import json
import unittest
from codex_usage_bridge import UsageCache, make_handler, sanitized_snapshot


class BridgeTests(unittest.TestCase):
    def test_only_quota_fields_are_exported(self):
        source = {'accountId': 'private-account', 'accessToken': 'private-secret',
                  'rateLimitsByLimitId': {'codex': {'limitName': 'Codex',
                    'primary': {'usedPercent': 43, 'windowDurationMins': 300, 'resetsAt': 1234},
                    'secondary': None, 'credits': {'balance': 'private-balance'}}}}
        value = sanitized_snapshot(source, 1000)
        self.assertEqual(value['buckets'][0]['windows'][0]['usedPercent'], 43)
        serialized = json.dumps(value)
        for private in ('private-account', 'private-secret', 'private-balance'):
            self.assertNotIn(private, serialized)
        self.assertEqual(value['observedAt'], 1000)

    def test_missing_usage_stays_unknown_and_out_of_range_is_clamped(self):
        value = sanitized_snapshot({'rateLimits': {'primary': {'usedPercent': None},
                                   'secondary': {'usedPercent': 130}}}, 1000)
        windows = value['buckets'][0]['windows']
        self.assertIsNone(windows[0]['usedPercent'])
        self.assertEqual(windows[1]['usedPercent'], 100)
        self.assertIsNone(windows[0]['resetsAt'])

    def test_multi_bucket_data_takes_precedence_over_legacy(self):
        value = sanitized_snapshot({'rateLimits': {'primary': {'usedPercent': 99}},
              'rateLimitsByLimitId': {'codex': {'primary': {'usedPercent': 10}},
                                    'other': {'primary': {'usedPercent': 20}}}}, 1000)
        self.assertEqual([b['windows'][0]['usedPercent'] for b in value['buckets']], [10, 20])

    def test_cached_snapshot_keeps_its_timestamp_when_refresh_fails(self):
        class Source:
            fail = False
            reads = 0
            def read(self):
                self.reads += 1
                if self.fail:
                    raise RuntimeError('offline')
                return {'rateLimits': {'primary': {'usedPercent': 5}}}
        source = Source()
        cache = UsageCache(source)
        first = cache.get()
        self.assertEqual(source.reads, 1)
        cache.get()
        self.assertEqual(source.reads, 1)
        source.fail = True
        cache.attempted_at -= 61
        second = cache.get()
        self.assertEqual(second['observedAt'], first['observedAt'])
        self.assertTrue(second['refreshFailed'])

    def test_pairing_is_required_before_reading_usage(self):
        class Cache:
            called = False
            def get(self):
                self.called = True
                return {'schemaVersion': 1, 'buckets': []}
        class Connection:
            def __init__(self, request):
                self.input = io.BytesIO(request)
                self.output = io.BytesIO()
            def makefile(self, mode, *args):
                return self.input
            def sendall(self, data):
                self.output.write(data)
        for key, expected in [('', b'401'), ('wrong', b'401'), ('pairing-key', b'200')]:
            cache = Cache()
            request = f'GET /usage HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {key}\r\n\r\n'.encode()
            connection = Connection(request)
            make_handler(cache, 'pairing-key')(connection, ('127.0.0.1', 1), None)
            self.assertIn(expected, connection.output.getvalue().splitlines()[0])
            self.assertEqual(cache.called, expected == b'200')


if __name__ == '__main__':
    unittest.main()
