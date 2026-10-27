#!/usr/bin/env python3
"""Register the phone hook without granting Codex hook trust. Existing hooks are preserved."""
import json
import shlex
import shutil
import sys
import time
from pathlib import Path


def main():
    destination = Path.home() / '.codex' / 'hooks.json'
    script = Path(__file__).resolve().with_name('codex_permission_hook.py')
    command = shlex.join([sys.executable, str(script)])
    value = json.loads(destination.read_text()) if destination.exists() else {}
    hooks = value.setdefault('hooks', {}).setdefault('PermissionRequest', [])
    if any(h.get('command') == command for group in hooks for h in group.get('hooks', [])):
        print('Phone approval hook is already registered. Review its trust in Codex /hooks.')
        return
    if destination.exists():
        shutil.copy2(destination, destination.with_name('hooks.json.backup-' + str(int(time.time()))))
    hooks.append({'matcher': '.*', 'hooks': [{'type': 'command', 'command': command,
                  'timeout': 130, 'statusMessage': 'Waiting for approval on NI2Clock'}]})
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(value, indent=2) + '\n')
    destination.chmod(0o600)
    print('Phone approval hook registered. Review and trust it in Codex /hooks before use.')


if __name__ == '__main__': main()
