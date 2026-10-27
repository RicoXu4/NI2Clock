import io
import json
import socket
import tempfile
import threading
import time
import unittest
from pathlib import Path
from codex_approvals import ApprovalQueue, LocalApprovalServer
from codex_usage_bridge import make_handler

PAYLOAD = {'hook_event_name': 'PermissionRequest', 'session_id': 'test-chat',
           'tool_name': 'Bash', 'cwd': '/test', 'tool_input': {'command': 'echo approval-test'}}


class ApprovalTests(unittest.TestCase):
    def test_each_choice_is_scoped_once(self):
        q = ApprovalQueue()
        a, b = q.add(PAYLOAD), q.add(PAYLOAD)
        self.assertTrue(q.decide(a['request']['id'], 'approve'))
        self.assertFalse(q.decide(a['request']['id'], 'deny'))
        self.assertFalse(q.decide('unknown', 'approve'))
        self.assertEqual(q.list(), [b['request']])
        self.assertIsNone(b['decision'])
        with self.assertRaises(ValueError):
            q.decide(b['request']['id'], 'allow-all')

    def test_expired_requests_cannot_be_approved(self):
        q = ApprovalQueue(lifetime=-1)
        entry = q.add(PAYLOAD)
        self.assertEqual(q.list(), [])
        self.assertFalse(q.decide(entry['request']['id'], 'approve'))

    def test_network_cannot_submit_hooks_or_decide_without_pairing(self):
        class Connection:
            def __init__(self, request):
                self.input, self.output = io.BytesIO(request), io.BytesIO()
            def makefile(self, mode, *args): return self.input
            def sendall(self, data): self.output.write(data)
            def settimeout(self, value): pass
        q = ApprovalQueue()
        entry = q.add(PAYLOAD)
        for path, token, code in [('/approvals/' + entry['request']['id'], 'wrong', b'401'),
                                  ('/hook', 'secret', b'404'),
                                  ('/approvals/' + entry['request']['id'], 'secret', b'200'),
                                  ('/approvals/' + entry['request']['id'], 'secret', b'409')]:
            body = b'{"decision":"deny"}'
            raw = (f'POST {path} HTTP/1.1\r\nAuthorization: Bearer {token}\r\nContent-Length: {len(body)}\r\n\r\n').encode() + body
            connection = Connection(raw)
            make_handler(None, 'secret', q)(connection, ('127.0.0.1', 1), None)
            self.assertIn(code, connection.output.getvalue().splitlines()[0])
        self.assertEqual(entry['decision'], 'deny')

    def test_local_hook_roundtrip_and_cancellation(self):
        with tempfile.TemporaryDirectory() as directory:
            q = ApprovalQueue(lifetime=2)
            server = LocalApprovalServer(q, Path(directory) / 'hook.sock')
            threading.Thread(target=server.serve_forever, daemon=True).start()
            try:
                for decision in ('approve', 'deny', None):
                    with socket.socket(socket.AF_UNIX) as client:
                        client.connect(str(server.path))
                        client.sendall(json.dumps(PAYLOAD).encode() + b'\n')
                        deadline = time.monotonic() + 2
                        while not q.list() and time.monotonic() < deadline:
                            time.sleep(.01)
                        self.assertTrue(q.list())
                        if decision:
                            self.assertTrue(q.decide(q.list()[0]['id'], decision))
                            self.assertEqual(json.loads(client.recv(1024))['decision'], decision)
                    deadline = time.monotonic() + 2
                    while q.list() and time.monotonic() < deadline:
                        time.sleep(.01)
                    self.assertEqual(q.list(), [])
            finally:
                server.shutdown()
                server.server_close()


if __name__ == '__main__': unittest.main()
