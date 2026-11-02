"""Run the opt-in native factory with Spark in an independent, headless server copy.

No desktop input, remote upload, RCON, or changes to the source instance. Requires
an already accepted source-instance EULA and explicit paths to the tested jars.
"""
from pathlib import Path
import argparse
import ctypes
import datetime
import hashlib
import json
import re
import shutil
import socket
import subprocess
import time


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def free_memory():
    class Memory(ctypes.Structure):
        _fields_ = [('length', ctypes.c_ulong), ('load', ctypes.c_ulong)] + [
            (name, ctypes.c_ulonglong) for name in ('total', 'available', 'pt', 'pa', 'vt', 'va', 'ex')]
    memory = Memory()
    memory.length = ctypes.sizeof(memory)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(memory)):
        raise OSError('GlobalMemoryStatusEx failed')
    return memory.available / 2**30


def prepare(args):
    source, runtime = args.server.resolve(), args.runtime.resolve()
    if source == runtime or source in runtime.parents:
        raise ValueError('Runtime must be independent of the source instance')
    if runtime.exists():
        raise FileExistsError(runtime)
    if not re.search(r'(?m)^eula=true\s*$', (source / 'eula.txt').read_text(encoding='utf-8-sig')):
        raise ValueError('Source instance has no existing accepted EULA')
    runtime.mkdir(parents=True)
    for name in ('mods', 'libraries', 'config', 'scripts'):
        folder = source / name
        if folder.is_dir():
            def ignored(directory, names):
                if Path(directory) != folder or name != 'mods':
                    return []
                return [n for n in names if n.lower().endswith('.jar') and
                        n.lower().startswith(('gtswn-', 'miaokatzecore-', 'spark-'))]
            shutil.copytree(folder, runtime / name, ignore=ignored)
    for name in ('java9args.txt', 'lwjgl3ify-forgePatches.jar', 'minecraft_server.1.7.10.jar',
                 'eula.txt', 'server.properties'):
        shutil.copy2(source / name, runtime / name)
    for path in source.glob('forge-*.jar'):
        shutil.copy2(path, runtime / path.name)
    inputs = {}
    for key in ('gtswn', 'core', 'spark'):
        path = getattr(args, key).resolve()
        target = runtime / 'mods' / path.name
        if target.exists():
            raise FileExistsError(target)
        shutil.copy2(path, target)
        inputs[key] = {'path': str(path), 'installed': str(target), 'sha256': sha(target)}
    cfg = runtime / 'config/gtswn/gtswn.cfg'
    cfg.parent.mkdir(parents=True, exist_ok=True)
    text = cfg.read_text(encoding='utf-8') if cfg.exists() else ''
    if 'enableAgentTestHarness' in text:
        text = re.sub(r'B:enableAgentTestHarness\s*=\s*\w+', 'B:enableAgentTestHarness=true', text)
    else:
        text += '\ntestworld {\n B:enableAgentTestHarness=true\n I:dimensionId=19871\n}\n'
    cfg.write_text(text, encoding='utf-8')
    settings = {'server-ip': '127.0.0.1', 'server-port': str(args.port), 'level-name': 'FactorySpark',
                'online-mode': 'false', 'white-list': 'false', 'level-type': 'FLAT',
                'generator-settings': '2;0;1;', 'generate-structures': 'false', 'view-distance': '2',
                'spawn-monsters': 'false', 'spawn-animals': 'false', 'difficulty': '0',
                'enable-rcon': 'false', 'enable-query': 'false', 'max-players': '2'}
    properties = (runtime / 'server.properties').read_text(encoding='iso-8859-1')
    lines = [line for line in properties.splitlines() if line.split('=', 1)[0] not in settings]
    lines.extend(key + '=' + value for key, value in settings.items())
    (runtime / 'server.properties').write_text('\n'.join(lines) + '\n', encoding='iso-8859-1')
    write_json(runtime / 'factory-inputs.json', {'sourceInstance': str(source), 'sourceReadOnly': True,
               'inputs': inputs, 'port': args.port, 'world': 'FactorySpark'})
    print('PREPARED', runtime, flush=True)


def run(args):
    runtime = args.runtime.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    inputs = json.loads((runtime / 'factory-inputs.json').read_text(encoding='utf-8-sig'))
    for entry in inputs['inputs'].values():
        if sha(entry['installed']) != entry['sha256']:
            raise ValueError('Installed jar changed after preparation: ' + entry['installed'])
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', inputs['port']))
    if free_memory() < args.heap_gib + 1:
        raise RuntimeError('Insufficient available memory for requested heap plus 1 GiB headroom')
    commands = output / 'commands.txt'
    commands.write_text('', encoding='utf-8')
    record = {'status': 'STARTING', 'inputs': inputs, 'startedUtc': datetime.datetime.now(
        datetime.timezone.utc).isoformat(), 'sampler': 'Spark Java, 4ms, all non-sleeping threads',
        'sampleSecondsRequested': args.seconds, 'scenario': args.scenario,
        'noDesktopInput': True, 'noRemoteUpload': True}
    argv = [str(args.java), '-Xms512M', '-Xmx' + str(args.heap_gib) + 'G', '-Dfml.readTimeout=180',
            '-Duser.language=en', '@java9args.txt', '-jar', 'lwjgl3ify-forgePatches.jar', 'nogui']
    record['argv'] = argv
    log_path = output / 'server.log'
    proc = None
    receipt = output / 'receipt.json'
    try:
        with log_path.open('wb') as log:
            proc = subprocess.Popen(argv, cwd=runtime, stdin=subprocess.PIPE, stdout=log,
                                    stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
            record['pid'] = proc.pid
            write_json(receipt, record)
            print('OWNED_SERVER', proc.pid, flush=True)
            def send(command):
                proc.stdin.write((command + '\n').encode('utf-8'))
                proc.stdin.flush()
                record.setdefault('commands', []).append({'elapsed': round(time.monotonic() - started, 3),
                                                          'command': command})
                print('SENT', command, flush=True)
            started = last_status = time.monotonic()
            booted = sample_start = stop_sent = save_offset = None
            stopping = False
            stopping_since = None
            hold_until = None
            cursor = manual_sent = 0
            accumulated = ''
            while proc.poll() is None:
                with log_path.open('rb') as reader:
                    reader.seek(cursor)
                    raw = reader.read()
                    cursor += len(raw)
                accumulated += raw.decode('utf-8', errors='replace')
                now = time.monotonic()
                manual = commands.read_text(encoding='utf-8-sig').splitlines()
                for command in manual[manual_sent:]:
                    if command.strip():
                        send(command)
                manual_sent = len(manual)
                if not booted and 'Done (' in accumulated and 'For help, type' in accumulated:
                    booted = now
                    record['status'] = 'BUILDING'
                    send('spark profiler cancel')
                    send('gtswn test run ' + args.scenario)
                if booted and not sample_start and 'GTSWN_NATIVE_STRESS_READY' in accumulated:
                    send('spark tps')
                    send('spark profiler start --thread * --ignore-sleeping --interval 4 --force-java-sampler')
                    sample_start = now
                    record.update(status='PROFILING', stableMarkerObserved=True)
                if sample_start and not stop_sent and now - sample_start >= args.seconds:
                    save_offset = len(accumulated)
                    send('spark profiler stop --save-to-file')
                    send('spark tps')
                    stop_sent = now
                if stop_sent and not record.get('profile'):
                    match = re.search(r'Data has been written to:\s*(.+)', accumulated[save_offset:])
                    if match:
                        path = Path(re.sub(r'\u001b\[[0-9;]*m', '', match.group(1)).strip())
                        if not path.is_absolute():
                            path = runtime / path
                        path = path.resolve()
                        if runtime not in path.parents or not path.is_file():
                            raise ValueError('Unexpected Spark output path: ' + str(path))
                        target = output / path.name
                        shutil.copy2(path, target)
                        record['profile'] = {'path': str(target), 'sha256': sha(target)}
                        print('PROFILE_SAVED', target, flush=True)
                report_folder = runtime / inputs['world'] / 'gtswn-test-reports'
                final = []
                if report_folder.exists():
                    for path in report_folder.glob('*.json'):
                        try:
                            value = json.loads(path.read_text(encoding='utf-8'))
                        except (json.JSONDecodeError, UnicodeDecodeError):
                            continue  # Async writer may currently be publishing a checkpoint.
                        if value.get('selection') == args.scenario and value.get('endedAtMs', 0) > 0:
                            final.append((path, value))
                if final and not stopping:
                    path, result = max(final, key=lambda item: item[1]['startedAtMs'])
                    if result['startedAtMs'] >= int((time.time() - (now - started) - 5) * 1000):
                        shutil.copy2(path, output / 'scenario-report.json')
                        record['scenarioStatus'] = result['status']
                        record['scenarioFailure'] = result.get('failure')
                        record['results'] = [{key: item.get(key) for key in ('id', 'status', 'detail')}
                                             for item in result.get('results', [])]
                        if record.get('profile') or not sample_start or result['status'] != 'PASS':
                            if result['status'] == 'PASS' and args.hold_after_pass_seconds and hold_until is None:
                                hold_until = now + args.hold_after_pass_seconds
                                record['status'] = 'HOLDING_FOR_CAPTURE'
                                send('save-all')
                                print('HOLDING_FOR_CAPTURE', args.hold_after_pass_seconds, flush=True)
                            if hold_until is None or now >= hold_until:
                                send('spark profiler cancel')
                                send('save-all')
                                send('stop')
                                stopping = True
                                stopping_since = now
                                record['status'] = 'STOPPING'
                if not stopping and (free_memory() < .5 or now - started > args.deadline or
                                     (not booted and now - started > 600)):
                    record['abort'] = 'resource-or-time-limit'
                    send('spark profiler cancel')
                    send('gtswn test stop')
                    send('stop')
                    stopping = True
                    stopping_since = now
                if stopping and now - stopping_since > 120:
                    raise TimeoutError('Owned server did not exit within 120 seconds after stop')
                if booted and not stopping and now - last_status >= 30:
                    send('gtswn test status')
                    last_status = now
                write_json(receipt, record)
                time.sleep(1)
            record['exitCode'] = proc.returncode
            record['status'] = 'PASS' if proc.returncode == 0 and record.get('scenarioStatus') == 'PASS' and record.get('profile') else 'FAILED'
    except Exception as error:
        record.update(status='ERROR', error=repr(error))
        raise
    finally:
        if proc is not None and proc.poll() is None:
            proc.stdin.write(b'spark profiler cancel\ngtswn test stop\nstop\n')
            proc.stdin.flush()
            try:
                proc.wait(timeout=120)
            except subprocess.TimeoutExpired:
                proc.terminate()
                proc.wait(timeout=30)
                record['forcedTermination'] = True
        record['finishedUtc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        write_json(receipt, record)
    print(record['status'], flush=True)
    return 0 if record['status'] == 'PASS' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    prep = sub.add_parser('prepare')
    for key in ('server', 'runtime', 'gtswn', 'core', 'spark'):
        prep.add_argument('--' + key, type=Path, required=True)
    prep.add_argument('--port', type=int, default=25674)
    launch = sub.add_parser('run')
    for key in ('runtime', 'output', 'java'):
        launch.add_argument('--' + key, type=Path, required=True)
    launch.add_argument('--heap-gib', type=int, default=4)
    launch.add_argument('--seconds', type=int, default=120)
    launch.add_argument('--deadline', type=int, default=2400)
    launch.add_argument('--hold-after-pass-seconds', type=int, default=0,
                        help='Keep the completed server available for optional screenshots; profiling is already stopped')
    launch.add_argument('--scenario', default='factory.native_stress')
    options = parser.parse_args()
    raise SystemExit(prepare(options) if options.mode == 'prepare' else run(options))
