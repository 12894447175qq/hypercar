#!/usr/bin/env python3
"""Exercise real Gradle signing choices without credentials or phone access."""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
PROPERTIES = ROOT / 'keystore.properties'
parser = argparse.ArgumentParser()
parser.add_argument('--build-apk', action='store_true')
parser.add_argument('--output-dir', type=Path, default=ROOT/'app/build/reports/local-signing')
args = parser.parse_args()
args.output_dir.mkdir(parents=True, exist_ok=True)
if PROPERTIES.exists():
    raise SystemExit('Use an unconfigured checkout; existing keystore.properties will not be replaced.')
if args.build_apk and not (ROOT/'app/src/main/jniLibs/arm64-v8a/libhypercar_core.so').exists():
    raise SystemExit('Build the arm64 native library before this APK smoke test.')

results = []
wrapper = ['cmd', '/d', '/c', str(ROOT/'gradlew.bat')] if os.name == 'nt' else ['bash', str(ROOT/'gradlew')]


def gradle(name, tasks, ci=False, expected_success=True, marker=None, extra_env=None):
    env = dict(os.environ, CI=str(ci).lower())
    env.update(extra_env or {})
    cmd = wrapper + ['--no-daemon', '--console=plain', '-Pkotlin.compiler.execution.strategy=in-process'] + tasks
    started = time.monotonic()
    run = subprocess.run(cmd, cwd=ROOT, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=600)
    (args.output_dir/(name+'.txt')).write_text(run.stdout, encoding='utf-8')
    record = {'name':name, 'command':cmd, 'ci':ci, 'exitCode':run.returncode,
              'expectedSuccess':expected_success, 'elapsedSeconds':round(time.monotonic()-started, 3)}
    results.append(record)
    assert (run.returncode == 0) == expected_success, f'{name}: unexpected exit {run.returncode}; see saved output'
    if marker: assert marker in run.stdout, f'{name}: missing {marker}'
    print(f'PASS {name}', flush=True)
    return run.stdout


@contextlib.contextmanager
def configuration(text):
    with PROPERTIES.open('x', encoding='utf-8') as stream:
        os.chmod(PROPERTIES, 0o600)
        stream.write(text)
    try:
        yield
    finally:
        assert PROPERTIES.read_text(encoding='utf-8') == text, 'Temporary configuration changed externally; not deleting it.'
        PROPERTIES.unlink()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def apk_certificate(name):
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT', ''))
    candidates = sorted((sdk/'build-tools').glob('*/apksigner.bat' if os.name == 'nt' else '*/apksigner'))
    assert candidates, 'apksigner is required for the APK verification'
    cmd = ['cmd', '/d', '/c', str(candidates[-1])] if os.name == 'nt' else [str(candidates[-1])]
    apk = ROOT/'app/build/outputs/apk/release/app-release.apk'
    run = subprocess.run(cmd+['verify', '--verbose', '--print-certs', str(apk)], env=os.environ, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (args.output_dir/(name+'-signature.txt')).write_text(run.stdout, encoding='utf-8')
    assert run.returncode == 0, 'APK signature invalid'
    return re.search(r'certificate SHA-256 digest: ([0-9a-f]+)', run.stdout).group(1)


try:
    gradle('local-number-without-key', ['help', '-Phypercar.buildNumber=5'], marker='SIGNING: mode=local-test')
    gradle('explicit-required-key-missing', ['help', '-Phypercar.requireReleaseSigning=true'], expected_success=False, marker='SIGNING: missing_required_config')
    gradle('ci-number-key-missing', ['help', '-Phypercar.buildNumber=5'], ci=True, expected_success=False, marker='SIGNING: missing_required_config')
    gradle('ci-release-key-missing', [':app:assembleRelease'], ci=True, expected_success=False, marker='SIGNING: missing_required_config')
    gradle('ci-debug-configuration', ['help'], ci=True, marker='SIGNING: mode=ci-test')
    with configuration('keyAlias=androiddebugkey\n'):
        gradle('invalid-config-not-replaced', ['help'], expected_success=False, marker='SIGNING: incomplete_config')

    if args.build_apk:
        with tempfile.TemporaryDirectory(prefix='hypercar-signing-') as temp:
            isolated_home = Path(temp)/'android'
            isolated_home.mkdir()
            extra_env = {'ANDROID_USER_HOME':str(isolated_home)}
            tasks = [':app:assembleRelease', '-Phypercar.buildNumber=5']
            gradle('generate-local-test-key', tasks, marker='SIGNING: mode=local-test', extra_env=extra_env)
            key = isolated_home/'debug.keystore'
            assert key.exists(), 'AGP did not generate the isolated default test key'
            key_hash, first_cert = digest(key), apk_certificate('generated-key')
            gradle('reuse-local-test-key', tasks, marker='SIGNING: mode=local-test', extra_env=extra_env)
            assert digest(key) == key_hash, 'The existing test key was replaced'
            assert apk_certificate('reused-key') == first_cert, 'Repeated builds changed certificate'
            # Deliberately omit keyPassword: the stored password must be reused without a prompt.
            props = f'storeFile={key.as_posix()}\nstorePassword=android\nkeyAlias=androiddebugkey\n'
            with configuration(props):
                gradle('configured-key-priority', tasks+['-Phypercar.requireReleaseSigning=true'], ci=True, marker='SIGNING: mode=configured')
                assert apk_certificate('configured-key') == first_cert, 'Explicit configured key was ignored'
            # Leave the normal build output using this computer's retained key, not the test fixture.
            real_home = os.environ.get('ANDROID_USER_HOME')
            default_key = (Path(real_home) if real_home else Path.home()/'.android')/'debug.keystore'
            previous_hash = digest(default_key) if default_key.exists() else None
            gradle('normal-local-build', tasks, marker='SIGNING: mode=local-test')
            if previous_hash: assert digest(default_key) == previous_hash, 'The existing machine key changed'
            assert apk_certificate('normal-local-key')
    summary = {'passed':True, 'checks':results, 'apkBuilt':args.build_apk,
               'phoneConnected':False, 'privateKeysCommitted':False,
               'windowsWrapperExecuted':os.name == 'nt'}
    (args.output_dir/'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(f'PASS {len(results)} signing checks', flush=True)
finally:
    (args.output_dir/'runs.json').write_text(json.dumps(results, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
