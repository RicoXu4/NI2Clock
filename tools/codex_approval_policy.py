"""Determine the active turn's reviewer without changing Codex approval policy.

The hook API does not expose the reviewer. Current Codex transcripts do; treat
missing, stale or changed transcript data as unknown and defer to native Codex.
"""
import json
import stat
from pathlib import Path

MAX_CONTEXT_BYTES = 2 * 1024 * 1024


def effective_reviewer(payload):
    """Return user/auto_review only for an exact session and active-turn match."""
    session = payload.get('session_id')
    turn = payload.get('turn_id')
    transcript = payload.get('transcript_path')
    if not all(isinstance(value, str) and value for value in (session, turn, transcript)):
        return None
    try:
        path = Path(transcript)
        if not stat.S_ISREG(path.stat().st_mode):
            return None
        with path.open('rb') as stream:
            metadata = json.loads(stream.readline(MAX_CONTEXT_BYTES))
            if metadata.get('type') != 'session_meta' or metadata.get('payload', {}).get('id') != session:
                return None
            stream.seek(0, 2)
            start = max(0, stream.tell() - MAX_CONTEXT_BYTES)
            stream.seek(start)
            if start:
                stream.readline()  # Discard the partial first record.
            lines = stream.read(MAX_CONTEXT_BYTES).splitlines()
        for line in reversed(lines):
            try:
                record = json.loads(line)
            except (ValueError, UnicodeError):
                continue
            if record.get('type') != 'turn_context':
                continue
            context = record.get('payload', {})
            # Never use a previous turn, global config, or permission_mode as a guess.
            if context.get('turn_id') != turn:
                return None
            reviewer = context.get('approvals_reviewer')
            return reviewer if reviewer in ('user', 'auto_review') else None
    except (OSError, ValueError, TypeError, AttributeError):
        pass
    return None


def should_forward(payload):
    return payload.get('hook_event_name') == 'PermissionRequest' and effective_reviewer(payload) == 'user'
