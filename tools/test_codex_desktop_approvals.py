import copy
import unittest
from unittest.mock import Mock
from codex_desktop_approvals import DesktopApprovals, app_access, apply_patch

REQUEST = {'id': 'request-17', 'method': 'mcpServer/elicitation/request', 'params': {
    'threadId': 'chat', 'turnId': 'turn', 'serverName': 'cua_repl', 'mode': 'form',
    'message': 'Allow Codex to use Calculator?',
    'requestedSchema': {'type': 'object', 'properties': {}},
    '_meta': {'codex_approval_kind': 'mcp_tool_call', 'connector_id': 'computer-use',
              'tool_name': 'access_app', 'tool_params': {'app': 'Calculator'},
              'persist': ['session', 'always']}}}


class DesktopApprovalTests(unittest.TestCase):
    def adapter(self):
        relay = DesktopApprovals()
        relay.socket = object()
        relay.states['chat'] = {'owner': 'owner', 'state': {}, 'revision': None}
        relay._follow = Mock()
        relay._request = Mock(return_value={'result': {'ok': True}})
        self.snapshot(relay, [copy.deepcopy(REQUEST)])
        return relay

    def snapshot(self, relay, requests, revision=1, owner='owner'):
        relay._state(owner, {'hostId': 'local', 'conversationId': 'chat', 'change': {
            'type': 'snapshot', 'revision': revision, 'conversationState': {
                'id': 'chat', 'title': 'Calculator test', 'cwd': '/project', 'requests': requests,
                'turns': [{'private': 'never exported'}]}}})

    def test_only_app_access_prompts_are_supported(self):
        self.assertEqual(app_access(REQUEST)['app'], 'Calculator')
        for mutation in ('other_connector', 'form_fields', 'user_verification', 'completed', 'no_app', 'no_scope'):
            request = copy.deepcopy(REQUEST)
            p = request['params']
            if mutation == 'other_connector': p['_meta']['connector_id'] = 'browser-use'
            elif mutation == 'form_fields': p['requestedSchema']['properties']['password'] = {'type': 'string'}
            elif mutation == 'user_verification': p['mode'] = 'openai/userVerification'
            elif mutation == 'completed': request['completed'] = True
            elif mutation == 'no_app': p['_meta']['tool_params'] = {}
            else: p['_meta'].pop('persist')
            self.assertIsNone(app_access(request), mutation)

    def test_approve_is_conversation_scoped_and_duplicate_is_rejected(self):
        relay = self.adapter()
        public = relay.list()[0]
        self.assertEqual(public['input']['name'], 'Calculator')
        self.assertEqual(public['input']['access'], 'This conversation')
        self.assertNotIn('private', str(public))
        self.assertNotIn('turns', relay.states['chat']['state'])
        self.assertTrue(relay.decide(public['id'], 'approve'))
        method, params, owner = relay._request.call_args.args
        self.assertEqual(method, 'thread-follower-submit-mcp-server-elicitation-response')
        self.assertEqual(owner, 'owner')
        self.assertEqual(params, {'conversationId': 'chat', 'requestId': 'request-17',
            'response': {'action': 'accept', 'content': {}, '_meta': {'persist': 'session'}}})
        self.assertFalse(relay.decide(public['id'], 'approve'))
        self.assertEqual(relay._request.call_count, 1)

    def test_deny_and_nonpersistent_primary_response(self):
        relay = self.adapter()
        self.assertTrue(relay.decide(relay.list()[0]['id'], 'deny'))
        self.assertEqual(relay._request.call_args.args[1]['response'],
                         {'action': 'decline', 'content': None, '_meta': None})
        request = copy.deepcopy(REQUEST)
        request['params']['_meta']['persist'] = ['always']
        self.snapshot(relay, [request], revision=2)
        self.assertTrue(relay.decide(relay.list()[0]['id'], 'approve'))
        self.assertIsNone(relay._request.call_args.args[1]['response']['_meta'])

    def test_desktop_resolution_and_revision_gap_remove_phone_request(self):
        relay = self.adapter()
        key = relay.list()[0]['id']
        relay._state('owner', {'hostId': 'local', 'conversationId': 'chat', 'change': {
            'type': 'patches', 'baseRevision': 1, 'revision': 2,
            'patches': [{'op': 'remove', 'path': ['requests', 0]}]}})
        self.assertEqual(relay.list(), [])
        self.assertFalse(relay.decide(key, 'approve'))
        self.snapshot(relay, [copy.deepcopy(REQUEST)], revision=3)
        relay._state('owner', {'hostId': 'local', 'conversationId': 'chat', 'change': {
            'type': 'patches', 'baseRevision': 2, 'revision': 4, 'patches': []}})
        self.assertEqual(relay.list(), [])
        relay._follow.assert_called_once_with('chat', 'owner', False)

    def test_wrong_owner_thread_and_disconnection_cannot_grant(self):
        relay = self.adapter()
        key = relay.list()[0]['id']
        self.snapshot(relay, [], revision=2, owner='impostor')
        self.assertEqual(relay.list()[0]['id'], key)
        relay.socket = None
        self.assertFalse(relay.decide(key, 'approve'))
        request = copy.deepcopy(REQUEST)
        request['params']['threadId'] = 'different-chat'
        self.snapshot(relay, [request], revision=2)
        self.assertEqual(relay.list(), [])

    def test_changed_request_gets_new_phone_id_and_failed_reply_can_retry(self):
        relay = self.adapter()
        key = relay.list()[0]['id']
        request = copy.deepcopy(REQUEST)
        request['params']['_meta']['tool_params']['app'] = 'TextEdit'
        self.snapshot(relay, [request], revision=2)
        self.assertFalse(relay.decide(key, 'approve'))
        key = relay.list()[0]['id']
        relay._request.side_effect = OSError('disconnected')
        with self.assertRaises(OSError): relay.decide(key, 'approve')
        self.assertEqual(relay.list()[0]['id'], key)

    def test_patch_updates_preserve_other_requests(self):
        state = {'requests': [copy.deepcopy(REQUEST)], 'title': 'old', 'cwd': '/project'}
        apply_patch(state, {'op': 'replace', 'path': ['requests', 0, 'params', 'message'], 'value': 'New reason'})
        self.assertEqual(state['requests'][0]['params']['message'], 'New reason')
        apply_patch(state, {'op': 'add', 'path': ['turns', 0], 'value': 'private'})
        self.assertNotIn('turns', state)


if __name__ == '__main__': unittest.main()
