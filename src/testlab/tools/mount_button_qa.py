"""Issue 131 live-button opt-in over the unchanged registered shared host.

Console alone changes the owned actor's OP role. The existing shared adapter owns
processes, desktop, packs, leases, world and ordinary recovery. This wrapper adds
only run-bound role coordination and byte restoration for local GUI preferences.
"""
from pathlib import Path
import json
import os
import sys
import time

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'tools/testlab'))
import host
import client_qa
import quantum_host


class MountConsoleDriver:
    def __init__(self, result, server_root, run_id, artifacts, stdin, actor_uuid=None):
        self.result, self.server_root = Path(result), Path(server_root)
        self.run_id, self.stdin, self.actor_uuid = run_id, stdin, actor_uuid
        self.sent = set()
        self.pending = None

    def tick(self):
        path = self.result / 'mount-control.json'
        if not path.is_file():
            return
        try:
            value = host.read(path)
        except (OSError, json.JSONDecodeError):
            return
        if value.get('hostRunId') != self.run_id or value.get('actorUuid') != self.actor_uuid:
            raise ValueError('Mount console role request identity mismatch')
        restore = value.get('restore')
        if type(restore) is not bool:
            raise ValueError('Mount console role request must state restore')
        if restore not in self.sent:
            self.stdin.write(('op ' if restore else 'deop ').encode() + client_qa.ACTOR.encode() + b'\n')
            self.stdin.flush()
            self.sent.add(restore)
            self.pending = time.monotonic()
        ops = host.read(self.server_root / 'ops.json')
        actor = [x for x in ops if x.get('uuid') == self.actor_uuid]
        matches = len(actor) == 1 and actor[0].get('level') == 2 if restore else not actor
        if not matches:
            if time.monotonic() - self.pending > 20:
                raise RuntimeError('Native server OP list did not reach requested owned actor role')
            return
        target = self.result / ('mount-role-restored.json' if restore else 'mount-role-ordinary.json')
        if not target.exists():
            host.write(target, {**value, 'actorLevel2': restore, 'actorLevel4': False,
                'controlSender': 'dedicated server console', 'nativeOpsFile': str(self.server_root / 'ops.json'),
                'nativeOpsSha256': host.sha(self.server_root / 'ops.json'), 'observedAt': host.stamp(),
                'evidence': 'Console deop/op completed and native persisted OP list observed; GUI business uses no management permission'})


def adapter():
    original_ops = quantum_host.actor_ops
    quantum_host.PROFILE = 'device.paged_client'
    quantum_host.actor_ops = lambda profile, actor, actor_uuid: original_ops('control-fixture-only', actor, actor_uuid)
    quantum_host.ConsoleDriver = MountConsoleDriver
    previous = os.environ.get('JDK_JAVA_OPTIONS')
    os.environ['JDK_JAVA_OPTIONS'] = ((previous + ' ') if previous else '') + '-Dgtswn.testlab.mountButton=true'
    try:
        return client_qa.main()
    finally:
        if previous is None:
            os.environ.pop('JDK_JAVA_OPTIONS', None)
        else:
            os.environ['JDK_JAVA_OPTIONS'] = previous


def main():
    if '--module' in sys.argv and sys.argv[sys.argv.index('--module') + 1] == 'gtswn' and '--owner-host-run' in sys.argv:
        return adapter()
    execute = host.execute_client
    def own_execute(value, args):
        value['clientAdapter']['script'] = str(Path(__file__).resolve())
        root = Path(value['instance']) if 'instance' in value else host.registered(args.registry, 'gtswn', 'client')['_path']
        evidence = args.evidence / (value['runId'] + '-mount-preferences-recovery')
        evidence.mkdir(parents=True, exist_ok=False)
        paths = [root / 'config/gtswn/desktops', root / 'config/gtswn/factory_display_client.cfg']
        original_directories = {p for p in paths if p.is_dir()}
        before = {}
        for path in paths:
            if path.is_dir():
                for file in path.rglob('*'):
                    if file.is_file(): before[file] = file.read_bytes()
            elif path.is_file(): before[path] = path.read_bytes()
        for file, payload in before.items():
            target = evidence / file.relative_to(root)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(payload)
        host.write(evidence / 'inventory.json', {'root': str(root), 'files': {str(f.relative_to(root)): host.sha(f) for f in before}})
        try:
            return execute(value, args)
        finally:
            pid = Path(value['clientAdapter']['workspace']) / 'client-runs' / value['runId'] / 'pid.json'
            if pid.is_file() and host.alive(host.read(pid).get('identity')):
                raise RuntimeError('Client still alive; preference backups preserved, restoration deferred')
            current = []
            for path in paths:
                current.extend([p for p in path.rglob('*') if p.is_file()] if path.is_dir() else [path] if path.is_file() else [])
            for file in current:
                if file not in before:
                    quoted = str(file).replace("'", "''")
                    import subprocess
                    subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command',
                        "Remove-Item -LiteralPath '" + quoted + "' -ErrorAction Stop"], check=True, capture_output=True,
                        creationflags=subprocess.CREATE_NO_WINDOW)
            for file, payload in before.items(): file.write_bytes(payload)
            for path in paths:
                if path.is_dir() and path not in original_directories and not any(path.iterdir()):
                    quoted = str(path).replace("'", "''")
                    import subprocess
                    subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command',
                        "Remove-Item -LiteralPath '" + quoted + "' -ErrorAction Stop"], check=True, capture_output=True,
                        creationflags=subprocess.CREATE_NO_WINDOW)
            host.write(evidence / 'restoration.json', {'restored': True, 'verified': all(f.read_bytes() == b for f, b in before.items()),
                'removedNewFiles': [str(f) for f in current if f not in before]})
    host.execute_client = own_execute
    return host.main()


if __name__ == '__main__':
    raise SystemExit(main())
