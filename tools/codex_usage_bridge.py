#!/usr/bin/env python3
"""Paired LAN bridge for Codex limits and per-request phone approvals."""
import argparse
import json
import math
import os
import queue
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from codex_approvals import ApprovalQueue, LocalApprovalServer
from codex_desktop_approvals import DesktopApprovals


def sanitized_snapshot(result, observed_at=None):
    """Allowlist quota fields; account IDs, credits and authentication stay local."""
    limits = result.get('rateLimitsByLimitId')
    if not isinstance(limits, dict) or not limits:
        legacy = result.get('rateLimits')
        limits = {legacy.get('limitId') or 'codex': legacy} if isinstance(legacy, dict) else {}
    buckets = []
    for key, value in limits.items():
        if not isinstance(value, dict):
            continue
        windows = []
        for kind in ('primary', 'secondary'):
            window = value.get(kind)
            if not isinstance(window, dict):
                continue
            used = window.get('usedPercent')
            if isinstance(used, bool) or not isinstance(used, (float, int)) or not math.isfinite(used):
                used = None
            else:
                used = max(0, min(100, used))
            duration = window.get('windowDurationMins')
            reset = window.get('resetsAt')
            windows.append({
                'kind': kind, 'usedPercent': used,
                'durationMinutes': duration if type(duration) is int and duration > 0 else None,
                'resetsAt': reset if type(reset) is int and reset > 0 else None,
            })
        buckets.append({'id': str(key)[:120], 'name': str(value.get('limitName') or key)[:120], 'windows': windows})
    return {'schemaVersion': 1, 'observedAt': int(time.time() if observed_at is None else observed_at), 'buckets': buckets}


class CodexSession:
    def __init__(self, codex):
        self.codex = codex
        self.process = None
        self.sequence = 0
        self.state = tempfile.TemporaryDirectory(prefix='ni2clock-codex-usage-')

    def _start(self):
        self.close_process()
        self.messages = queue.Queue()
        self.process = subprocess.Popen([
            self.codex, 'app-server', '-c', f'sqlite_home={json.dumps(self.state.name + "/state")}',
            '-c', f'log_dir={json.dumps(self.state.name + "/logs")}',
        ], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)
        process = self.process
        messages = self.messages

        def read():
            try:
                for line in process.stdout:
                    try:
                        messages.put(json.loads(line))
                    except json.JSONDecodeError:
                        pass
            finally:
                messages.put(None)
        threading.Thread(target=read, daemon=True).start()
        self._rpc('initialize', {'clientInfo': {'name': 'ni2clock_usage', 'title': 'NI2Clock Usage', 'version': '1.0'}})
        self._send({'method': 'initialized', 'params': {}})

    def _send(self, message):
        self.process.stdin.write(json.dumps(message) + '\n')
        self.process.stdin.flush()

    def _rpc(self, method, params=None):
        self.sequence += 1
        message = {'id': self.sequence, 'method': method}
        if params is not None:
            message['params'] = params
        self._send(message)
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            response = self.messages.get(timeout=max(.01, deadline - time.monotonic()))
            if response is None:
                raise RuntimeError('Codex app-server stopped')
            if response.get('id') == self.sequence:
                if 'error' in response:
                    raise RuntimeError('Codex rejected the usage read')
                return response['result']
        raise TimeoutError('Codex usage read timed out')

    def read(self):
        try:
            if self.process is None or self.process.poll() is not None:
                self._start()
            return self._rpc('account/rateLimits/read')
        except Exception:
            self.close_process()
            raise

    def close_process(self):
        if self.process is not None:
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
            for stream in (self.process.stdin, self.process.stdout):
                stream.close()
            self.process = None

    def close(self):
        self.close_process()
        self.state.cleanup()


class UsageCache:
    def __init__(self, source, interval=60):
        self.source = source
        self.interval = interval
        self.lock = threading.Lock()
        self.snapshot = None
        self.attempted_at = None
        self.failed = False

    def get(self):
        with self.lock:
            now = time.monotonic()
            if self.attempted_at is None or now - self.attempted_at >= self.interval:
                self.attempted_at = now
                try:
                    self.snapshot = sanitized_snapshot(self.source.read())
                    self.failed = False
                except Exception:
                    self.failed = True
            if self.snapshot is None:
                return None
            return dict(self.snapshot, refreshFailed=self.failed)


def make_handler(cache, token, approvals=None, desktop=None):
    class Handler(BaseHTTPRequestHandler):
        def authorized(self):
            supplied = self.headers.get('Authorization', '')
            if secrets.compare_digest(supplied.encode(), ('Bearer ' + token).encode()):
                return True
            self.respond(401, {'error': 'Pairing key required'})
            return False

        def do_GET(self):
            if self.path not in ('/usage', '/approvals'):
                self.respond(404, {'error': 'Not found'})
                return
            if not self.authorized():
                return
            if self.path == '/approvals':
                self.respond(200, {'requests': (desktop.list() if desktop else [])
                              + (approvals.list() if approvals else [])})
                return
            result = cache.get()
            self.respond(200 if result is not None else 503,
                         result if result is not None else {'error': 'Codex usage is unavailable'})

        def do_POST(self):
            if not self.authorized():
                return
            if not self.path.startswith('/approvals/') or approvals is None:
                self.respond(404, {'error': 'Not found'})
                return
            try:
                size = int(self.headers.get('Content-Length', '0'))
                if size <= 0 or size > 1024:
                    raise ValueError('Invalid body size')
                self.connection.settimeout(5)
                body = json.loads(self.rfile.read(size))
                request_id = self.path[len('/approvals/'):]
                target = desktop if request_id.startswith('app-') else approvals
                accepted = target.decide(request_id, body.get('decision')) if target else False
            except OSError:
                self.respond(503, {'error': 'Desktop response could not be confirmed'})
                return
            except (ValueError, AttributeError):
                self.respond(400, {'error': 'Invalid decision'})
                return
            self.respond(200 if accepted else 409, {'resolved': accepted})

        def respond(self, status, value):
            data = json.dumps(value, allow_nan=False).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *args):
            pass  # Do not log request headers or pairing keys.
    return Handler


def read_properties(path):
    if not path.exists():
        return {}
    return {k.strip(): v.strip() for line in path.read_text().splitlines()
            if line.strip() and not line.lstrip().startswith('#') and '=' in line
            for k, v in [line.split('=', 1)]}


def configure(path, host, port):
    properties = read_properties(path)
    token = properties.get('codex.bridgeToken') or secrets.token_urlsafe(32)
    url = f'http://{host}:{port}/usage'
    original = path.read_text() if path.exists() else ''
    lines = [line for line in original.splitlines()
             if not line.startswith(('codex.bridgeUrl=', 'codex.bridgeToken='))]
    path.write_text('\n'.join(lines) + f'\ncodex.bridgeUrl={url}\ncodex.bridgeToken={token}\n')
    os.chmod(path, 0o600)
    return token, url


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', required=True, help='LAN address to bind (use 127.0.0.1 for local tests)')
    parser.add_argument('--port', type=int, default=18765)
    parser.add_argument('--properties', type=Path, default=Path(__file__).resolve().parents[1] / 'clock.properties')
    parser.add_argument('--codex', default=shutil.which('codex'))
    parser.add_argument('--configure', action='store_true', help='Write local pairing defaults, then exit')
    args = parser.parse_args()
    if args.configure:
        _, url = configure(args.properties, args.host, args.port)
        print(f'Pairing defaults configured for {url}; rebuild the Android app.')
        return
    properties = read_properties(args.properties)
    token = properties.get('codex.bridgeToken', '')
    if not token:
        parser.error('Run with --configure first to generate a local pairing key')
    if not args.codex:
        parser.error('Codex CLI is required; sign in with the desktop account')
    source = CodexSession(args.codex)
    cache = UsageCache(source)
    approvals = ApprovalQueue()
    desktop = DesktopApprovals()
    local = LocalApprovalServer(approvals)
    server = ThreadingHTTPServer((args.host, args.port), make_handler(cache, token, approvals, desktop))
    server.daemon_threads = True
    threading.Thread(target=local.serve_forever, daemon=True).start()
    desktop.start()
    try:
        initial = cache.get()
        print(f'Usage bridge listening on {args.host}:{args.port}; '
              + ('current usage available' if initial else 'waiting for Codex sign-in/usage'), flush=True)
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        desktop.close()
        local.shutdown()
        local.server_close()
        source.close()


if __name__ == '__main__':
    main()
