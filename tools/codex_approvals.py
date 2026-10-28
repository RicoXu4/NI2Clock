"""Per-request approval relay. Submission is only through a private local socket."""
import json
import os
import secrets
import select
import socket
import socketserver
import stat
import threading
import time
from pathlib import Path
from codex_approval_policy import should_forward

SOCKET_PATH = Path(__file__).resolve().parents[1] / '.ni2clock-approval.sock'
MAX_REQUEST = 64 * 1024


class ApprovalQueue:
    def __init__(self, lifetime=120):
        self.lifetime = lifetime
        self.lock = threading.Lock()
        self.pending = {}

    def add(self, payload):
        if not should_forward(payload):
            raise ValueError('Only confirmed manual-review requests are forwarded')
        if not isinstance(payload.get('tool_name'), str) or not payload['tool_name']:
            raise ValueError('Tool name required')
        request = {'id': secrets.token_urlsafe(24), 'tool': payload['tool_name'],
                   'session': str(payload.get('session_id') or ''),
                   'cwd': str(payload.get('cwd') or ''),
                   'input': payload.get('tool_input'), 'expiresAt': int(time.time() + self.lifetime)}
        entry = {'request': request, 'deadline': time.monotonic() + self.lifetime,
                 'event': threading.Event(), 'decision': None,
                 'policy': {key: payload.get(key) for key in
                            ('hook_event_name', 'session_id', 'turn_id', 'transcript_path')}}
        with self.lock:
            if len(self.pending) >= 32:
                raise ValueError('Approval queue full')
            self.pending[request['id']] = entry
        return entry

    def remove(self, entry):
        with self.lock:
            self.pending.pop(entry['request']['id'], None)

    def list(self):
        with self.lock:
            return [e['request'] for e in self.pending.values()
                    if self._is_pending(e)]

    def _is_pending(self, entry):
        if entry['event'].is_set():
            return False
        if entry['deadline'] <= time.monotonic() or not should_forward(entry['policy']):
            # Withdraw a stale request and release the hook back to native Codex.
            entry['event'].set()
            return False
        return True

    def decide(self, request_id, decision):
        if decision not in ('approve', 'deny'):
            raise ValueError('Invalid decision')
        with self.lock:
            entry = self.pending.get(request_id)
            if entry is None or not self._is_pending(entry):
                return False
            entry['decision'] = decision
            entry['event'].set()
            return True


class LocalApprovalServer(socketserver.ThreadingUnixStreamServer):
    daemon_threads = True

    def __init__(self, approvals, path=SOCKET_PATH):
        self.approvals = approvals
        self.path = Path(path)
        if self.path.exists():
            info = self.path.lstat()
            if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.getuid():
                raise RuntimeError('Approval socket path is occupied')
            with socket.socket(socket.AF_UNIX) as probe:
                try:
                    probe.connect(str(self.path))
                except ConnectionRefusedError:
                    self.path.unlink()
                else:
                    raise RuntimeError('Another approval bridge is running')
        super().__init__(str(self.path), LocalApprovalHandler)
        os.chmod(self.path, 0o600)

    def server_close(self):
        super().server_close()
        self.path.unlink(missing_ok=True)


class LocalApprovalHandler(socketserver.StreamRequestHandler):
    def handle(self):
        entry = None
        try:
            self.connection.settimeout(5)
            data = self.rfile.readline(MAX_REQUEST + 1)
            if len(data) > MAX_REQUEST or not data.endswith(b'\n'):
                return
            entry = self.server.approvals.add(json.loads(data))
            while time.monotonic() < entry['deadline']:
                if entry['event'].wait(.2):
                    self.wfile.write(json.dumps({'decision': entry['decision']}).encode() + b'\n')
                    return
                # Codex cancellation/termination closes the hook socket; drop its request.
                if select.select([self.connection], [], [], 0)[0]:
                    return
            self.wfile.write(b'{}\n')  # Defer to Codex's normal approval flow.
        except (OSError, ValueError, TypeError, AttributeError):
            pass
        finally:
            if entry is not None:
                self.server.approvals.remove(entry)
