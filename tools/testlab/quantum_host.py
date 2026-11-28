"""Console-only control for the owned, non-OP quantum security client fixture."""
from pathlib import Path
import uuid
import host


PROFILE = 'quantum.safety_client'


def actor_ops(profile, actor, actor_uuid):
    return [] if profile == PROFILE else [{'uuid': actor_uuid, 'name': actor, 'level': 2, 'bypassesPlayerLimit': False}]


class ConsoleDriver:
    def __init__(self, result, server_root, run_id, artifacts, stdin, actor_uuid=None):
        self.result, self.server_root = Path(result), Path(server_root)
        self.run_id, self.artifacts, self.stdin = str(uuid.UUID(run_id)), artifacts, stdin
        self.sent, self.business_run = set(), None
        self.actor_uuid = str(uuid.UUID(actor_uuid)) if actor_uuid else None
        self.forwarded = set()

    def forward_fixture_receipts(self):
        if self.business_run is None:
            return
        directory = self.server_root / ('MiaoHost-' + self.run_id) / 'gtswn-test-reports'
        expected = next(item for item in self.artifacts if item['modId'] == 'gtswn')
        for phase, stage, policy, security, action, previous in [
                ('build-revoked', -6, True, False, 'revoke-build', None),
                ('security-ready', -4, True, True, 'prepare-security', 'build-revoked'),
                ('revoked', 7, True, False, 'revoke', 'security-ready'),
                ('rejected', 8, True, False, 'revoke', 'revoked')]:
            source = directory / (self.business_run + '.fixture-' + phase + '.json')
            if phase in self.forwarded or not source.is_file():
                continue
            value = host.read(source)
            artifact = value.get('artifact') or {}
            if (value.get('schema') != 1 or value.get('runId') != self.business_run
                    or value.get('actorUuid') != self.actor_uuid or self.actor_uuid is None
                    or value.get('phase') != phase or value.get('stage') != stage
                    or value.get('policy') is not policy or value.get('nativeSecurity') is not security
                    or value.get('nativeBuild') is not (phase == 'security-ready')
                    or value.get('nativeAll') is not (phase == 'security-ready')
                    or not isinstance(value.get('serverTick'), int)
                    or artifact.get('jarSha256') != expected['sha256']
                    or artifact.get('sourceCommit') != expected.get('sourceCommit')
                    or artifact.get('sourceDirty') != 'false'):
                raise ValueError('Native quantum fixture receipt identity/state mismatch')
            if action not in self.sent or (previous is not None and previous not in self.forwarded):
                raise ValueError('Native quantum fixture receipt arrived out of console order')
            target = self.result / ('quantum-fixture-' + phase + '.json')
            if target.exists():
                raise FileExistsError('Unexpected existing fixture coordination receipt')
            host.write(target, {'hostRunId': self.run_id, 'businessRunId': self.business_run,
                'actorUuid': self.actor_uuid, 'phase': phase, 'serverReceipt': value})
            self.forwarded.add(phase)

    def tick(self):
        self.forward_fixture_receipts()
        control = self.result / 'quantum-control.json'
        if not control.is_file():
            return
        value = host.read(control)
        if value.get('hostRunId') != self.run_id:
            raise ValueError('Quantum control belongs to another host run')
        phase = value.get('phase')
        if phase == 'WAIT_START':
            if 'start' not in self.sent:
                self.stdin.write(f'miaotest gtswn run {PROFILE} --request {self.run_id}\n'.encode())
                self.stdin.flush(); self.sent.add('start')
            target = self.server_root / 'logs/miao-testlab' / (self.run_id + '.json')
            if self.business_run is None and target.is_file():
                response = host.verify_response(host.read(target), 'gtswn', self.artifacts)
                if response.get('requestId') != self.run_id or response.get('accepted') is not True:
                    raise ValueError('Quantum console fixture was not accepted for this request')
                business_run = (response.get('snapshot') or {}).get('runId')
                if business_run is None:
                    return
                self.business_run = str(uuid.UUID(business_run))
                host.write(self.result / 'quantum-server-run.json', {'hostRunId': self.run_id,
                    'businessRunId': self.business_run, 'response': response})
            return
        action = {'WAIT_BUILD_REVOKE': 'revoke-build', 'WAIT_SECURITY_READY': 'prepare-security',
            'WAIT_REVOKE': 'revoke', 'WAIT_RESTORE': 'complete'}.get(phase)
        if action is None:
            raise ValueError('Unknown quantum console control phase')
        if self.business_run is None or value.get('businessRunId') != self.business_run:
            raise ValueError('Quantum control business run mismatch')
        if action == 'complete' and 'revoke' not in self.sent:
            raise ValueError('Quantum restore requested before revocation evidence')
        if action == 'prepare-security' and 'revoke-build' not in self.sent:
            raise ValueError('Quantum management setup requested before BUILD revocation')
        if action == 'revoke' and 'prepare-security' not in self.sent:
            raise ValueError('Quantum management chain requested before teammate read/revocation')
        if action not in self.sent:
            self.stdin.write(f'gtswn test quantum-safety {action} {self.business_run}\n'.encode())
            self.stdin.flush(); self.sent.add(action)
            host.write(self.result / 'quantum-console-actions.json', {'hostRunId': self.run_id,
                'businessRunId': self.business_run, 'sent': sorted(self.sent), 'actorIsConsole': True})
