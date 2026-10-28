"""Computer Use app-access relay for the inspected desktop IPC protocol.

This private protocol is version-sensitive. Unknown messages are never answered;
only an explicit phone decision can respond to a matching app-access request.
"""
import copy
import hashlib
import json
import re
import secrets
import socket
import sqlite3
import struct
import threading
import time
import uuid
from pathlib import Path

MAX_FRAME = 32 * 1024 * 1024


def app_access(request):
    """Mirror the desktop's Computer Use app-card detection, not generic MCP forms."""
    if not isinstance(request, dict) or request.get('method') != 'mcpServer/elicitation/request' or request.get('completed'):
        return None
    params = request.get('params', {})
    meta = params.get('_meta', {})
    if not isinstance(meta, dict) or params.get('mode') != 'form':
        return None
    connector = re.sub(r'[^a-z0-9]+', '-', str(meta.get('connector_id', '')).strip().lower()).strip('-')
    if not (connector == 'computer-use' or connector.startswith('computer-use-')):
        return None
    if meta.get('codex_approval_kind') != 'mcp_tool_call':
        return None
    schema = params.get('requestedSchema', {})
    if schema.get('type') != 'object' or schema.get('properties') != {} or schema.get('required'):
        return None
    tool_params = meta.get('tool_params')
    if not isinstance(tool_params, dict):
        return None
    app = tool_params.get('app')
    for field in meta.get('tool_params_display', []) or []:
        if isinstance(field, dict) and field.get('name') == 'app' and isinstance(field.get('value'), str):
            app = field['value']
            break
    modes = meta.get('persist')
    modes = [modes] if isinstance(modes, str) else modes
    if not isinstance(app, str) or not app.strip() or not isinstance(modes, list) or not modes:
        return None
    if any(mode not in ('session', 'always') for mode in modes):
        return None
    if not isinstance(request.get('id'), (str, int)) or isinstance(request.get('id'), bool):
        return None
    if not isinstance(params.get('turnId'), str) or not params['turnId']:
        return None
    return {'app': app.strip(), 'scope': 'This conversation' if 'session' in modes else 'This request',
            'session': 'session' in modes, 'tool': str(meta.get('tool_name') or 'App access'),
            'reason': str(params.get('message') or 'Allow Codex to use this app through Computer Use.'),
            'risk': 'Elevated risk' if meta.get('riskLevel') == 'high' else None,
            'subtitle': meta.get('subtitle')}


def fingerprint(request):
    return hashlib.sha256(json.dumps(request, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def apply_patch(state, patch):
    """Apply only request/title/folder patches; never retain conversation content."""
    path = patch.get('path')
    if not isinstance(path, list):
        raise ValueError('Unsupported patch path')
    if not path or path[0] not in ('requests', 'title', 'cwd'):
        return
    target = state
    for part in path[:-1]:
        target = target[part]
    key = path[-1]
    op = patch.get('op')
    if op not in ('add', 'remove', 'replace'):
        raise ValueError('Unsupported patch operation')
    if isinstance(target, list):
        if type(key) is not int or key < 0:
            raise ValueError('Invalid array index')
        if op == 'add':
            if key > len(target): raise ValueError('Invalid array index')
            target.insert(key, copy.deepcopy(patch['value']))
        elif op == 'remove': target.pop(key)
        else: target[key] = copy.deepcopy(patch['value'])
    elif op == 'remove': del target[key]
    else: target[key] = copy.deepcopy(patch['value'])


class DesktopApprovals:
    def __init__(self, home=None):
        self.home = Path(home or Path.home() / '.codex')
        self.lock = threading.RLock()
        self.write_lock = threading.Lock()
        self.pending_lock = threading.Lock()
        self.responses = {}
        self.states = {}
        self.cards = {}
        self.socket = None
        self.client_id = 'initializing-client'
        self.stop_event = threading.Event()
        self.threads = []

    def start(self):
        for target in (self._run, self._discover):
            thread = threading.Thread(target=target, daemon=True)
            self.threads.append(thread)
            thread.start()

    def close(self):
        self.stop_event.set()
        self._disconnect()
        for thread in self.threads: thread.join(timeout=2)

    def _send(self, message):
        data = json.dumps(message, allow_nan=False).encode()
        if len(data) > MAX_FRAME: raise ValueError('Desktop message too large')
        with self.write_lock:
            connection = self.socket
            if connection is None: raise OSError('Desktop disconnected')
            connection.sendall(struct.pack('<I', len(data)) + data)

    def _request(self, method, params, owner=None, version=1):
        request_id = str(uuid.uuid4())
        event = threading.Event()
        slot = {'event': event}
        with self.pending_lock: self.responses[request_id] = slot
        try:
            self._send({'type': 'request', 'requestId': request_id, 'sourceClientId': self.client_id,
                        'method': method, 'version': version, 'params': params,
                        'targetClientId': owner, 'timeoutMs': 4000})
            if not event.wait(5): raise OSError('Desktop request timed out')
            response = slot.get('response', {})
            if response.get('resultType') != 'success': raise OSError('Desktop rejected request')
            return response
        finally:
            with self.pending_lock: self.responses.pop(request_id, None)

    def _follow(self, thread_id, owner, following=True):
        self._send({'type': 'broadcast', 'method': 'thread-stream-following-changed', 'version': 1,
                    'sourceClientId': self.client_id, 'targetClientIds': [owner],
                    'params': {'hostId': 'local', 'conversationId': thread_id, 'following': following}})

    def _disconnect(self):
        with self.lock:
            connection, self.socket = self.socket, None
            self.states.clear()
            self.cards.clear()
            self.client_id = 'initializing-client'
        if connection is not None:
            try: connection.shutdown(socket.SHUT_RDWR)
            except OSError: pass
            connection.close()
        with self.pending_lock:
            for slot in self.responses.values(): slot['event'].set()

    @staticmethod
    def _read(connection, size):
        data = bytearray()
        while len(data) < size:
            part = connection.recv(size - len(data))
            if not part: raise EOFError()
            data.extend(part)
        return bytes(data)

    def _run(self):
        while not self.stop_event.is_set():
            try:
                connection = socket.socket(socket.AF_UNIX)
                connection.connect(str(self.home / 'ipc' / 'ipc.sock'))
                with self.lock: self.socket = connection
                self._send({'type': 'request', 'requestId': 'ni2clock-initialize',
                            'sourceClientId': 'initializing-client', 'method': 'initialize', 'version': 0,
                            'params': {'clientType': 'ni2clock-approvals'}})
                while not self.stop_event.is_set():
                    size = struct.unpack('<I', self._read(connection, 4))[0]
                    if not 0 < size <= MAX_FRAME: raise ValueError('Unsupported desktop frame')
                    self._message(json.loads(self._read(connection, size)))
            except (OSError, EOFError, ValueError, KeyError, TypeError, AttributeError, IndexError):
                pass  # Never log private conversation frames or credentials.
            finally: self._disconnect()
            self.stop_event.wait(2)

    def _message(self, message):
        if message.get('type') == 'client-discovery-request':
            self._send({'type': 'client-discovery-response', 'requestId': message['requestId'],
                        'response': {'canHandle': False}})
        elif message.get('type') == 'response':
            if message.get('requestId') == 'ni2clock-initialize':
                if message.get('resultType') != 'success': raise ValueError('Desktop initialization failed')
                self.client_id = message['result']['clientId']
            else:
                with self.pending_lock:
                    slot = self.responses.get(message.get('requestId'))
                    if slot:
                        slot['response'] = message
                        slot['event'].set()
        elif message.get('type') == 'broadcast':
            if message.get('targetClientIds') is not None and self.client_id not in message['targetClientIds']:
                return
            if message.get('method') == 'client-status-changed' and message.get('params', {}).get('status') == 'disconnected':
                owner = message['params'].get('clientId')
                with self.lock:
                    for thread_id in list(self.states):
                        if self.states[thread_id]['owner'] == owner: self._drop(thread_id)
            elif message.get('method') == 'thread-stream-state-changed':
                if message.get('version') != 11: raise ValueError('Unsupported desktop stream version')
                self._state(message['sourceClientId'], message['params'])

    def _drop(self, thread_id):
        self.states.pop(thread_id, None)
        self.cards = {key: value for key, value in self.cards.items() if value['thread'] != thread_id}

    def _state(self, owner, params):
        if params.get('hostId') != 'local': return
        thread_id = params.get('conversationId')
        with self.lock:
            saved = self.states.get(thread_id)
            if saved is None or saved['owner'] != owner: return
            change = params.get('change', {})
            try:
                if change.get('type') == 'snapshot':
                    state = change['conversationState']
                    if state.get('id') != thread_id: raise ValueError('Thread mismatch')
                    saved['state'] = {key: copy.deepcopy(state.get(key)) for key in ('requests', 'title', 'cwd')}
                elif change.get('type') == 'patches' and change.get('baseRevision') == saved.get('revision'):
                    for patch in change['patches']: apply_patch(saved['state'], patch)
                else: raise ValueError('Desktop stream changed')
                saved['revision'] = change['revision']
                saved['received'] = time.monotonic()
                self._refresh(thread_id, saved)
            except (ValueError, KeyError, TypeError, IndexError, AttributeError):
                # A new discovery/follow will obtain a full snapshot after a gap.
                self._follow(thread_id, owner, False)
                self._drop(thread_id)

    def _refresh(self, thread_id, saved):
        state = saved['state']
        candidates = {}
        for request in state.get('requests') or []:
            app = app_access(request)
            if app is None or request['params'].get('threadId') != thread_id: continue
            signature = fingerprint(request)
            existing = next((value for value in self.cards.values() if value['thread'] == thread_id
                             and value['signature'] == signature), None)
            if existing:
                candidates[existing['public']['id']] = existing
                continue
            key = 'app-' + secrets.token_urlsafe(24)
            info = {'name': app['app'], 'reason': app['reason'], 'access': app['scope'],
                    'chat_name': state.get('title') or thread_id}
            if app['risk']: info['risk'] = app['risk']
            if app['subtitle']: info['details'] = app['subtitle']
            candidates[key] = {'thread': thread_id, 'owner': saved['owner'], 'request': request,
                               'signature': signature, 'app': app, 'sending': False,
                               'public': {'id': key, 'kind': 'computer_use_app', 'tool': 'Computer Use',
                                          'cwd': state.get('cwd') or '', 'session': thread_id,
                                          'input': info}}
        self.cards = {key: value for key, value in self.cards.items() if value['thread'] != thread_id}
        self.cards.update(candidates)

    def list(self):
        with self.lock:
            return [copy.deepcopy(value['public']) for value in self.cards.values() if not value['sending']]

    def decide(self, key, decision):
        if decision not in ('approve', 'deny'): raise ValueError('Invalid decision')
        with self.lock:
            card = self.cards.get(key)
            if card is None or card['sending'] or self.socket is None: return False
            saved = self.states.get(card['thread'])
            if saved is None or saved['owner'] != card['owner']: return False
            if not any(fingerprint(r) == card['signature'] and app_access(r) for r in saved['state'].get('requests') or []):
                return False
            card['sending'] = True
            response = {'action': 'decline', 'content': None, '_meta': None}
            if decision == 'approve':
                response = {'action': 'accept', 'content': {},
                            '_meta': {'persist': 'session'} if card['app']['session'] else None}
            params = {'conversationId': card['thread'], 'requestId': card['request']['id'], 'response': response}
        try:
            result = self._request('thread-follower-submit-mcp-server-elicitation-response', params, card['owner'])
            if result.get('result', {}).get('ok') is not True: raise OSError('Unconfirmed desktop response')
        except OSError:
            with self.lock:
                if self.cards.get(key) is card: card['sending'] = False
            raise
        with self.lock: self.cards.pop(key, None)
        return True

    def _thread_ids(self):
        database = self.home / 'state_5.sqlite'
        with sqlite3.connect(database.as_uri() + '?mode=ro', uri=True, timeout=1) as connection:
            return [row[0] for row in connection.execute(
                'SELECT id FROM threads WHERE archived = 0 ORDER BY updated_at_ms DESC LIMIT 64')]

    def _discover(self):
        while not self.stop_event.is_set():
            if self.client_id != 'initializing-client':
                try:
                    ids = self._thread_ids()
                    with self.lock:
                        for old in set(self.states) - set(ids):
                            self._follow(old, self.states[old]['owner'], False)
                            self._drop(old)
                    for thread_id in ids:
                        if self.stop_event.is_set(): break
                        with self.lock:
                            if thread_id in self.states: continue
                        try:
                            response = self._request('thread-owner-discovery', {'hostId': 'local', 'conversationId': thread_id})
                            owner = response['handledByClientId']
                            with self.lock: self.states[thread_id] = {'owner': owner, 'state': {}, 'revision': None}
                            self._follow(thread_id, owner)
                        except (OSError, KeyError): pass
                except (OSError, sqlite3.Error, ValueError): pass
            self.stop_event.wait(10)
