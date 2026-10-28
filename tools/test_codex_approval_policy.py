import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from codex_approval_policy import effective_reviewer, should_forward
from codex_approvals import ApprovalQueue
import codex_permission_hook


class PolicyTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / 'transcript.jsonl'
        self.payload = {'hook_event_name': 'PermissionRequest', 'session_id': 'session',
                        'turn_id': 'turn', 'transcript_path': str(self.path), 'tool_name': 'Bash'}

    def context(self, reviewer, turn='turn'):
        self.path.write_text(json.dumps({'type': 'session_meta', 'payload': {'id': 'session'}}) + '\n'
            + json.dumps({'type': 'turn_context', 'payload': {'turn_id': turn, 'approvals_reviewer': reviewer}}) + '\n')

    def test_auto_review_delegates_without_opening_phone_socket(self):
        self.context('auto_review')
        self.assertEqual(effective_reviewer(self.payload), 'auto_review')
        self.assertFalse(should_forward(self.payload))
        with patch('sys.stdin') as stdin, patch('sys.stdout', new_callable=io.StringIO) as stdout, \
                patch('codex_permission_hook.socket.socket') as socket:
            stdin.buffer = io.BytesIO(json.dumps(self.payload).encode())
            codex_permission_hook.main()
            self.assertEqual(json.loads(stdout.getvalue()), {})
            socket.assert_not_called()
        q = ApprovalQueue()
        with self.assertRaises(ValueError): q.add(self.payload)
        self.assertEqual(q.list(), [])

    def test_manual_reviewer_can_be_forwarded(self):
        self.context('user')
        self.assertTrue(should_forward(self.payload))
        q = ApprovalQueue()
        entry = q.add(self.payload)
        self.assertEqual(q.list(), [entry['request']])
        self.assertTrue(q.decide(entry['request']['id'], 'deny'))

    def test_pending_manual_request_is_withdrawn_if_reviewer_changes(self):
        self.context('user')
        q = ApprovalQueue()
        entry = q.add(self.payload)
        self.context('auto_review')
        self.assertEqual(q.list(), [])
        self.assertTrue(entry['event'].is_set())
        self.assertIsNone(entry['decision'])
        self.assertFalse(q.decide(entry['request']['id'], 'approve'))
        self.context('user')
        entry = q.add(self.payload)
        self.context('auto_review')
        self.assertFalse(q.decide(entry['request']['id'], 'approve'))
        self.assertIsNone(entry['decision'])

    def test_changed_mode_uses_current_turn_not_old_manual_turn(self):
        self.context('user', turn='old-turn')
        with self.path.open('a') as stream:
            stream.write(json.dumps({'type': 'turn_context', 'payload': {
                'turn_id': 'turn', 'approvals_reviewer': 'auto_review'}}) + '\n')
        self.assertFalse(should_forward(self.payload))
        self.payload['turn_id'] = 'old-turn'
        self.assertIsNone(effective_reviewer(self.payload))

    def test_missing_unknown_or_mismatched_context_defers_to_codex(self):
        for reviewer in (None, 'future-reviewer'):
            self.context(reviewer)
            self.assertFalse(should_forward(self.payload))
        self.context('user')
        self.assertFalse(should_forward(dict(self.payload, session_id='other-session')))
        self.assertFalse(should_forward(dict(self.payload, turn_id='other-turn')))
        self.assertFalse(should_forward(dict(self.payload, turn_id=None)))
        self.assertFalse(should_forward(dict(self.payload, transcript_path=None)))
        self.path.write_text('not json\n')
        self.assertFalse(should_forward(self.payload))
        self.path.unlink()
        self.assertFalse(should_forward(self.payload))

    def test_permission_mode_or_input_cannot_override_active_reviewer(self):
        self.context('auto_review')
        self.payload.update(permission_mode='default', approvals_reviewer='user',
                            tool_input={'approvals_reviewer': 'user'})
        self.assertFalse(should_forward(self.payload))

    def test_large_transcript_and_partial_last_record(self):
        self.context('user')
        metadata = self.path.read_text().splitlines()[0]
        with self.path.open('w') as stream:
            stream.write(metadata + '\n')
            stream.write(json.dumps({'type': 'response_item', 'payload': 'x' * (3 * 1024 * 1024)}) + '\n')
            stream.write(json.dumps({'type': 'turn_context', 'payload': {
                'turn_id': 'turn', 'approvals_reviewer': 'auto_review'}}) + '\n')
            stream.write('{"type":"response_item"')
        self.assertFalse(should_forward(self.payload))
        self.assertEqual(effective_reviewer(self.payload), 'auto_review')


if __name__ == '__main__': unittest.main()
