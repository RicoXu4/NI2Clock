#!/usr/bin/env python3
"""Codex PermissionRequest hook: wait for a single phone decision, otherwise defer."""
import json
import socket
import sys
from codex_approvals import MAX_REQUEST, SOCKET_PATH


def main():
    result = {}
    try:
        data = sys.stdin.buffer.read(MAX_REQUEST + 1)
        if len(data) > MAX_REQUEST:
            raise ValueError('Request too large')
        payload = json.loads(data)
        if payload.get('hook_event_name') != 'PermissionRequest':
            raise ValueError('Wrong hook event')
        with socket.socket(socket.AF_UNIX) as connection:
            connection.settimeout(125)
            connection.connect(str(SOCKET_PATH))
            connection.sendall(json.dumps(payload).encode() + b'\n')
            with connection.makefile('rb') as stream:
                decision = json.loads(stream.readline(1024)).get('decision')
        if decision in ('approve', 'deny'):
            result = {'hookSpecificOutput': {'hookEventName': 'PermissionRequest',
                       'decision': {'behavior': 'allow' if decision == 'approve' else 'deny'}}}
    except (OSError, ValueError, TypeError, AttributeError):
        pass  # Unavailable bridge, expiry or invalid payload: normal Codex prompt.
    print(json.dumps(result))


if __name__ == '__main__':
    main()
