#!/usr/bin/env python3
"""Fixed-instance, offline, hidden native-GPU benchmark. No launcher UI or desktop input.

Run only after obtaining the shared machine's resource slot. --prepare-only compiles
and audits without changing the instance or starting Minecraft. Touch OUT/STOP to stop.
"""
import argparse
import ctypes
from ctypes import wintypes
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import struct
import time
import zipfile

GAME = Path('E:/CodeGT/测试实例-GTSWN/客户端/.minecraft')
JAVA = Path('C:/Program Files/Java/jdk-26.0.2.1/bin')
EXPORTS = []


def relocate_class(data):
    """Shade ASM UTF8 constants to isolate it from Forge/RFB's ASM version."""
    result = bytearray(data[:10])
    offset, index = 10, 1
    count = int.from_bytes(data[8:10], 'big')
    sizes = {3:4,4:4,5:8,6:8,7:2,8:2,9:4,10:4,11:4,12:4,15:3,16:2,17:4,18:4,19:2,20:2}
    while index < count:
        tag = data[offset]
        result.append(tag)
        offset += 1
        if tag == 1:
            length = int.from_bytes(data[offset:offset+2], 'big')
            value = data[offset+2:offset+2+length].replace(b'org/objectweb/asm', b'gtswn/testguard/asm').replace(b'org.objectweb.asm', b'gtswn.testguard.asm')
            result += struct.pack('>H', len(value)) + value
            offset += 2+length
        else:
            size = sizes[tag]
            result += data[offset:offset+size]
            offset += size
            if tag in (5,6):
                index += 1
        index += 1
    return bytes(result)+data[offset:]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save_json(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding='utf-8')


def prepare(out):
    classes = out / 'agent-classes'
    classes.mkdir(parents=True, exist_ok=True)
    source = Path(__file__).with_name('HiddenGpuAgent.java')
    cache = Path.home()/'.gradle/caches/modules-2/files-2.1/org.ow2.asm'
    asm_jars = []
    for artifact in ['asm', 'asm-tree']:
        matches = list((cache/artifact).glob('9*/*/*.jar'))
        if not matches:
            raise FileNotFoundError('Cached ASM 9 dependency: '+artifact)
        asm_jars.append(sorted(matches, key=lambda p: [int(n) for n in re.findall(r'\d+',p.parents[1].name)])[-1])
    subprocess.run([str(JAVA/'javac.exe'), '--release', '17', '-cp', os.pathsep.join(map(str, asm_jars)),
                    '-d', str(classes), str(source)], check=True)
    manifest = out/'agent.mf'
    manifest.write_text('Manifest-Version: 1.0\nPremain-Class: HiddenGpuAgent\n\n', encoding='ascii')
    agent = out/'hidden-gpu-agent.jar'
    with zipfile.ZipFile(agent, 'w', zipfile.ZIP_DEFLATED) as output:
        output.writestr('META-INF/MANIFEST.MF', manifest.read_bytes())
        for path in classes.glob('*.class'):
            output.writestr(path.name, relocate_class(path.read_bytes()))
        for dependency in asm_jars:
            with zipfile.ZipFile(dependency) as input_jar:
                for name in input_jar.namelist():
                    if name.startswith('org/objectweb/asm/') and name.endswith('.class'):
                        output.writestr(name.replace('org/objectweb/asm/', 'gtswn/testguard/asm/'), relocate_class(input_jar.read(name)))
    lwjgl = GAME/'mods/lwjgl3ify-3.0.42.jar'
    audit = subprocess.run([str(JAVA/'java.exe'), '-Xmx256m', *EXPORTS, '-cp', str(agent),
                            'HiddenGpuAgent', str(lwjgl)], capture_output=True, text=True)
    (out/'agent-audit.txt').write_text(audit.stdout + audit.stderr, encoding='utf-8')
    if audit.returncode:
        raise RuntimeError('Hidden SDL audit failed; see agent-audit.txt')
    audit_copy = out/'lwjgl3ify-audit.jar'
    shutil.copy2(lwjgl, audit_copy)
    return agent, audit_copy


def launch_metadata():
    """Read component metadata, never account files or launcher logs."""
    instance = GAME.parent
    library_root = Path(os.environ['APPDATA'])/'PrismLauncher/libraries'
    assets = library_root.parent/'assets'
    patches = sorted((json.loads(p.read_text(encoding='utf-8'))
                      for p in (instance/'patches').glob('*.json')), key=lambda p: p.get('order', 0))
    libraries, jvm, tweakers = [], [], []
    selected = {}
    main = None
    for patch in patches:
        main = patch.get('mainClass', main)
        jvm += patch.get('+jvmArgs', [])
        tweakers += patch.get('+tweakers', [])
        entries = patch.get('libraries', []) + ([patch['mainJar']] if 'mainJar' in patch else [])
        for lib in entries:
            coordinate = lib['name'].split(':')
            selected[(coordinate[0], coordinate[1], tuple(coordinate[3:]))] = lib
    for lib in selected.values():
            name = lib['name']
            if '-natives-' in name and '-natives-windows:' not in name:
                continue
            rules = lib.get('rules', [])
            if rules and not any(r.get('action') == 'allow' and r.get('os', {}).get('name', 'windows') == 'windows' for r in rules):
                continue
            group, artifact, version, *classifier = name.split(':')
            if lib.get('natives') and not lib.get('downloads', {}).get('artifact'):
                classifier = [lib['natives']['windows'].replace('${arch}', '64')]
            filename = f'{artifact}-{version}' + (f'-{classifier[0]}' if classifier else '') + '.jar'
            if lib.get('MMC-hint') == 'local':
                path = instance/'libraries'/filename
            else:
                path = library_root/Path(*group.split('.'))/artifact/version/filename
            if not path.is_file():
                raise FileNotFoundError(path)
            if path not in libraries:
                libraries.append(path)
    return main, jvm, tweakers, libraries, assets


def replace_settings(path, settings):
    text = path.read_text(encoding='utf-8') if path.exists() else ''
    for key, value in settings.items():
        pattern = r'(?m)^' + re.escape(key) + r':[^\r\n]*'
        line = key + ':' + value
        text = re.sub(pattern, lambda _: line, text) if re.search(pattern, text) else text+'\n'+line+'\n'
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8')


def validate_controls(report, rounds):
    errors = []
    if report.get('status') != 'COMPLETED':
        errors.append('harness failed: '+str(report.get('failure')))
    if report.get('displayWidth') != 1280 or report.get('displayHeight') != 720:
        errors.append('framebuffer must be 1280x720')
    if not str(report.get('java', '')).startswith('26.') or report.get('heapMaxBytes', 1 << 60) > 5*1024**3:
        errors.append('Java26 / <=5GiB heap contract failed')
    renderer = report.get('glRenderer', '')
    if not renderer or re.search(r'software|llvmpipe|GDI Generic|SwiftShader', renderer, re.I):
        errors.append('hardware GL renderer missing')
    windows = report.get('windows', [])
    if len(windows) != 3*rounds:
        errors.append('incomplete benchmark windows')
    for index, window in enumerate(windows):
        scene = window.get('scene')
        if scene != ['empty', 'heavy_dynamic', 'heavy_fixed'][index % 3]:
            errors.append(f'window {index}: scene sequence mismatch')
        if window.get('frameSamples', 0) < 2:
            errors.append(f'window {index}: insufficient frames')
        if scene and scene.startswith('heavy_'):
            for key, expected in {'linkedClientNodes':64, 'incorporatedClientTiles':64,
                                  'visibleCoverSources':128, 'eligibleHighCoverSources':128}.items():
                if window.get(key) != expected:
                    errors.append(f'window {index}: {key}={window.get(key)}, expected {expected}')
            for key in ['linkSubmittedPrimitives', 'quantumSubmittedPrimitives']:
                if window.get(key, 0) <= 0:
                    errors.append(f'window {index}: {key} must be positive')
            if scene == 'heavy_fixed':
                for key, expected in {'linkParticles':192, 'quantumParticles':512}.items():
                    if window.get(key) != expected:
                        errors.append(f'window {index}: {key}={window.get(key)}, expected {expected}')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime-jar', type=Path)
    parser.add_argument('--core-jar', type=Path)
    parser.add_argument('--label', required=True)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--warmup', type=int, default=20)
    parser.add_argument('--sample', type=int, default=15)
    parser.add_argument('--rounds', type=int, default=3)
    parser.add_argument('--timeout', type=int, default=600)
    args = parser.parse_args()
    if os.name != 'nt':
        raise SystemExit('Windows-only foreground safety monitor')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+', args.label):
        raise SystemExit('Label must be filesystem-safe')
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    agent, lwjgl = prepare(out)
    main_class, jvm, tweakers, libraries, assets = launch_metadata()
    verifier = subprocess.run([str(JAVA/'java.exe'), '-Xmx256m', '-Xverify:all',
                              '-Dgtswn.hiddenEvidence='+str(out/'agent-static-verify.txt'),
                              '-javaagent:'+str(agent)+'='+str(lwjgl), '-cp',
                              os.pathsep.join(map(str, [agent, lwjgl, *libraries])),
                              'HiddenGpuAgent', '--verify-class-loading'], capture_output=True, text=True, timeout=30)
    (out/'agent-verify.txt').write_text(verifier.stdout+verifier.stderr, encoding='utf-8')
    if verifier.returncode:
        raise RuntimeError('Patched class JVM verification failed; see agent-verify.txt')
    native_dir = out/'natives'
    native_dir.mkdir(exist_ok=True)
    for library in libraries:
        if 'natives-windows' in library.name:
            with zipfile.ZipFile(library) as native_jar:
                for name in native_jar.namelist():
                    if name.lower().endswith('.dll'):
                        (native_dir/Path(name).name).write_bytes(native_jar.read(name))
    save_json(out/'preflight.json', {'status': 'PREPARED', 'libraryCount': len(libraries),
                                   'agentSha256': sha(agent), 'lwjglSha256': sha(lwjgl), 'mainClass': main_class})
    if args.prepare_only:
        print('PREPARED; Minecraft was not started')
        return
    if not args.runtime_jar or not args.core_jar:
        raise SystemExit('--runtime-jar and --core-jar are required for execution')
    candidates = [args.runtime_jar.resolve(), args.core_jar.resolve()]
    for jar in candidates:
        if not jar.is_file():
            raise FileNotFoundError(jar)
    report_dir = GAME/'gtswn-effects-benchmark'/args.label
    world = GAME/'saves'/('gtswn-effects-'+args.label)
    if report_dir.exists() or world.exists():
        raise SystemExit('Refusing to overwrite a prior report or benchmark world')
    backup = out/'restore'
    backup.mkdir(exist_ok=False)
    config = GAME/'config'
    shutil.copytree(config, backup/'config')
    originals = {}
    def preserve(path):
        originals[path] = path.read_bytes() if path.exists() else None
    for filename in ['options.txt', 'optionsof.txt', 'optionsshaders.txt']:
        preserve(GAME/filename)
    target_jars = [p for p in (GAME/'mods').glob('*.jar')
                   if p.name.lower().startswith(('gtsimplewirelessnetwork', 'gtswn', 'miaokatzecore'))]
    for path in target_jars:
        preserve(path)
    for jar in candidates:
        preserve(GAME/'mods'/jar.name)
    save_json(backup/'manifest.json', {str(p): hashlib.sha256(data).hexdigest() if data is not None else None
                                    for p, data in originals.items()})
    for index, (path, data) in enumerate(originals.items()):
        if data is not None:
            (backup/f'file-{index}.bin').write_bytes(data)
    process = None
    result = {'label': args.label, 'status': 'STARTING', 'foregroundViolations': 0, 'visibleWindowViolations': 0}
    original_config_dirs = {p.relative_to(config) for p in config.rglob('*') if p.is_dir()}
    user32 = ctypes.WinDLL('user32', use_last_error=True)
    user32.GetForegroundWindow.restype = wintypes.HWND
    user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
    user32.IsWindowVisible.argtypes = [wintypes.HWND]
    enum_proc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    visible = []
    @enum_proc
    def visitor(hwnd, unused):
        pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if process and pid.value == process.pid and user32.IsWindowVisible(hwnd):
            visible.append(int(hwnd))
        return True
    try:
        for path in target_jars:
            path.unlink()
        for jar in candidates:
            shutil.copy2(jar, GAME/'mods'/jar.name)
        replace_settings(GAME/'options.txt', {'fullscreen':'false','pauseOnLostFocus':'false','enableVsync':'false',
                         'maxFps':'260','soundCategory_master':'0.0','soundCategory_music':'0.0', 'music':'0.0','sound':'0.0'})
        lwjgl_config = config/'lwjgl3ify.cfg'
        text = lwjgl_config.read_text(encoding='utf-8')
        text = re.sub(r'(?m)^(\s*B:focused=).*$', r'\g<1>false', text)
        text = re.sub(r'(?m)^(\s*B:maximized=).*$', r'\g<1>false', text)
        lwjgl_config.write_text(text, encoding='utf-8')
        harness = config/'gtswn/gtswn.cfg'
        harness.parent.mkdir(parents=True, exist_ok=True)
        if harness.exists():
            text = harness.read_text(encoding='utf-8')
            if 'B:enableAgentTestHarness=' not in text:
                text += '\ntestworld {\n B:enableAgentTestHarness=true\n}\n'
            else:
                text = re.sub(r'B:enableAgentTestHarness=\w+', 'B:enableAgentTestHarness=true', text)
        else:
            text = 'testworld {\n B:enableAgentTestHarness=true\n}\n'
        harness.write_text(text, encoding='utf-8')
        command = [str(JAVA/'javaw.exe'), '-Xms512m', '-Xmx4g', *EXPORTS, *jvm,
                   '-Djava.library.path='+str(native_dir),
                   '-Dfml.noGrab=true', '-Dgtswn.hiddenEvidence='+str(out/'agent-runtime.txt'),
                   '-Dgtswn.effectsBenchmark='+args.label,
                   '-Dgtswn.effectsWarmupSeconds='+str(args.warmup),
                   '-Dgtswn.effectsSampleSeconds='+str(args.sample), '-Dgtswn.effectsRounds='+str(args.rounds),
                   '-javaagent:'+str(agent)+'='+str(lwjgl), '-cp', os.pathsep.join(map(str, libraries)), main_class,
                   '--username', 'EffectsOffline', '--version', '1.7.10', '--gameDir', str(GAME),
                   '--assetsDir', str(assets), '--assetIndex', '1.7.10', '--uuid', 'bd553c15759d391a86ce0383ec61ff95',
                   '--accessToken', '0', '--userProperties', '{}', '--userType', 'legacy',
                   '--width','1280','--height','720']
        for tweaker in tweakers:
            command += ['--tweakClass', tweaker]
        save_json(out/'launch.json', {'command': command, 'runtimeSha256': sha(candidates[0]), 'coreSha256': sha(candidates[1])})
        with (out/'client.log').open('wb') as log:
            startup = subprocess.STARTUPINFO()
            startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
            startup.wShowWindow = 0
            process = subprocess.Popen(command, cwd=GAME, stdout=log, stderr=subprocess.STDOUT,
                                       startupinfo=startup, creationflags=subprocess.CREATE_NO_WINDOW)
            result['pid'] = process.pid
            result['status'] = 'RUNNING'
            save_json(out/'driver-result.json', result)
            start = time.monotonic()
            while process.poll() is None:
                foreground_pid = wintypes.DWORD()
                user32.GetWindowThreadProcessId(user32.GetForegroundWindow(), ctypes.byref(foreground_pid))
                visible.clear()
                user32.EnumWindows(visitor, 0)
                runtime_evidence = out/'agent-runtime.txt'
                if runtime_evidence.exists() and 'FAIL_CLOSED' in runtime_evidence.read_text(encoding='utf-8'):
                    result['status'] = 'GUARD_ABORT'
                    process.terminate()
                    break
                if foreground_pid.value == process.pid or visible:
                    result['foregroundViolations'] += int(foreground_pid.value == process.pid)
                    result['visibleWindowViolations'] += int(bool(visible))
                    result['status'] = 'SAFETY_ABORT'
                    process.terminate()
                    break
                if (out/'STOP').exists() or time.monotonic()-start > min(args.timeout, 600):
                    result['status'] = 'STOPPED' if (out/'STOP').exists() else 'TIMED_OUT'
                    process.terminate()
                    break
                time.sleep(.025)
            process.wait(timeout=15)
            result['exitCode'] = process.returncode
            result['elapsedSeconds'] = time.monotonic()-start
            if result['status'] == 'RUNNING':
                result['status'] = 'EXITED'
        for filename in ['fml-client-latest.log', 'latest.log']:
            latest_log = GAME/'logs'/filename
            if latest_log.is_file():
                shutil.copy2(latest_log, out/filename)
        if report_dir.exists():
            shutil.copytree(report_dir, out/'benchmark', dirs_exist_ok=True)
            if (report_dir/'report.json').exists():
                report = json.loads((report_dir/'report.json').read_text(encoding='utf-8'))
                result['benchmarkStatus'] = report.get('status')
                result['controlErrors'] = validate_controls(report, args.rounds)
                save_json(out/'controls.json', {'passed':not result['controlErrors'],
                          'errors':result['controlErrors'], 'windows':report.get('windows', [])})
        evidence_path = out/'agent-runtime.txt'
        evidence = evidence_path.read_text(encoding='utf-8') if evidence_path.exists() else ''
        swap_match = re.search(r'realSwapCalls=(\d+)', evidence)
        result['realSwapCalls'] = int(swap_match.group(1)) if swap_match else 0
        result['guardConfirmed'] = all('TRANSFORM_OK '+target in evidence for target in
                                      ['org/lwjglx/opengl/Display', 'org/lwjglx/input/Mouse'])
        screenshots = GAME/'screenshots'
        if screenshots.exists():
            for screenshot in screenshots.glob('effects-'+args.label+'-*.png'):
                shutil.copy2(screenshot, out/screenshot.name)
    finally:
        if process is not None and process.poll() is None:
            process.kill()
            process.wait(timeout=15)
        # Restore only test-owned mutations; exact original bytes, including absent files.
        for path, data in originals.items():
            if data is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(data)
        old_files = {p.relative_to(backup/'config'): p for p in (backup/'config').rglob('*') if p.is_file()}
        for path in config.rglob('*'):
            if path.is_file() and path.relative_to(config) not in old_files:
                path.unlink()
        for relative, path in old_files.items():
            destination = config/relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, destination)
        for path in sorted((p for p in config.rglob('*') if p.is_dir()), key=lambda p: len(p.parts), reverse=True):
            if path.relative_to(config) not in original_config_dirs and not any(path.iterdir()):
                path.rmdir()
        result['restored'] = all((not p.exists()) if b is None else (p.exists() and p.read_bytes()==b)
                                 for p,b in originals.items()) and all(sha(config/r)==sha(p) for r,p in old_files.items())
        save_json(out/'driver-result.json', result)
    print(json.dumps(result, ensure_ascii=False))
    if (result.get('benchmarkStatus') != 'COMPLETED' or not result.get('restored')
            or not result.get('guardConfirmed') or not result.get('realSwapCalls')
            or result.get('controlErrors') or result['foregroundViolations'] or result['visibleWindowViolations']):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
