#!/usr/bin/env python3
"""Verify decrypted production-pump output with an independent H.264 decoder."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--ffmpeg', default=shutil.which('ffmpeg'))
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
if not args.ffmpeg:
    raise SystemExit('Provide --ffmpeg /path/to/ffmpeg (no phone or signing key required).')
args.output.mkdir(parents=True, exist_ok=True)
env = dict(os.environ, HYPERCAR_VIDEO_EVIDENCE=str(args.output.resolve()))
cmd = ['cargo', 'test', '--locked', '--manifest-path', str(ROOT/'rust-core/Cargo.toml'), 'video_flow_tests']
with (args.output/'video-flow-tests.txt').open('w') as log:
    subprocess.run(cmd, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)

def decode(path):
    command = [args.ffmpeg, '-hide_banner', '-loglevel', 'error', '-err_detect', 'explode',
               '-i', str(path), '-map', '0:v:0', '-fps_mode', 'passthrough', '-noautoscale',
               '-f', 'framemd5', '-']
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    assert result.returncode == 0, f'Decoder failed for {path.name}: {result.stderr[:1000]}'
    assert not result.stderr.strip(), f'Decoder errors for {path.name}: {result.stderr[:500]}'
    (args.output/(path.stem+'.framemd5')).write_text(result.stdout)
    return [line.split(',')[-1].strip() for line in result.stdout.splitlines() if line and not line.startswith('#')]

reference = decode(ROOT/'rust-core/testdata/source-1280x720.h264')
assert len(reference) == 9
checks = []
for label in ['same-size', 'changed-size']:
    source = args.output/(label+'-source.h264')
    frames = decode(source)
    assert frames == reference * 2, f'{label}: source pixels changed across transport/recovery'
    mixed = args.output/(label+'-mixed.h264')
    mixed_frames = decode(mixed)
    assert len(mixed_frames) > len(frames), 'standby must also have been decoded'
    checks.append({'case': label, 'sourceFrames': len(frames), 'mixedFrames': len(mixed_frames),
                   'sourcePixelsMatch': True, 'mixedSha256': hashlib.sha256(mixed.read_bytes()).hexdigest()})
summary = {'checks': checks, 'decoder': subprocess.check_output([args.ffmpeg, '-version'], text=True).splitlines()[0],
           'realVehicleTest': False, 'input': 'generated baseline H264 fixture, not current vivo capture'}
(args.output/'video-decode-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n')
print(json.dumps(summary, ensure_ascii=False, indent=2))
