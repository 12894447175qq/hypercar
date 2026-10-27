#!/usr/bin/env python3
"""Verify/install the phone's own Jovi CarLife asset, without re-signing or disabling apps."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--server-port", default="5037")
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))))
    parser.add_argument("--output", type=Path, default=Path.home() / "Library/Caches/hypercar-build/vivo-source")
    parser.add_argument("--install", action="store_true", help="Install only when the component is absent")
    args = parser.parse_args()
    adb = [args.adb, "-P", args.server_port, "-s", args.serial]

    def run(command):
        completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if completed.returncode:
            detail = (completed.stderr or completed.stdout).decode(errors="replace").strip()
            raise RuntimeError(f"Command failed ({completed.returncode}): {detail[:2000]}")
        return completed.stdout

    def device(*command):
        return run(adb + list(command)).decode().strip()

    args.output.mkdir(parents=True, exist_ok=True)
    user = device("shell", "am", "get-current-user")
    host = device("shell", "pm", "path", "com.vivo.car.networking").splitlines()[0].removeprefix("package:")
    if not host.startswith("/") or not host.endswith(".apk"):
        raise RuntimeError("Cannot locate the installed Jovi APK")
    payload = run(adb + ["exec-out", f"unzip -p {shlex.quote(host)} assets/carlife_273.apk"])
    apk = args.output / "carlife_273.apk"
    apk.write_bytes(payload)
    build_tools = args.sdk / "build-tools/35.0.0"
    badging = run([str(build_tools / "aapt"), "dump", "badging", str(apk)]).decode()
    match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not match or match.groups() != ("com.baidu.carlife.vivo", "273", "8.8.4"):
        raise RuntimeError("Embedded CarLife identity changed; inspect before installing")
    verified = run([str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)]).decode()
    cert = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", verified)
    if not cert:
        raise RuntimeError("No verified signer certificate")
    package = match[1]
    installed = package in device("shell", "pm", "list", "packages", "--user", user, package)
    result = {
        "package": package, "version_code": 273, "version_name": "8.8.4",
        "sha256": hashlib.sha256(payload).hexdigest(), "certificate_sha256": cert[1],
        "source": "Installed Jovi assets/carlife_273.apk", "already_installed": installed,
        "installed_now": False, "modified_or_resigned": False,
    }
    # Keep the verified fingerprint even if the phone blocks installation while locked.
    (args.output / "verification.json").write_text(json.dumps(result, indent=2) + "\n")
    (args.output / "signature.txt").write_text(verified)
    if installed:
        # Do not overwrite a different installed variant or silently trust a conflicting signer.
        installed_path = device("shell", "pm", "path", package).splitlines()[0].removeprefix("package:")
        installed_apk = args.output / "installed-base.apk"
        run(adb + ["pull", installed_path, str(installed_apk)])
        installed_cert = run([str(build_tools / "apksigner"), "verify", "--print-certs", str(installed_apk)]).decode()
        if f"certificate SHA-256 digest: {cert[1]}" not in installed_cert:
            raise RuntimeError("Installed component signer differs from the Jovi asset; no changes made")
    elif args.install:
        install_output = device("install", "--user", user, str(apk))
        if "Success" not in install_output:
            raise RuntimeError(install_output)
        result["installed_now"] = True
    (args.output / "verification.json").write_text(json.dumps(result, indent=2) + "\n")
    (args.output / "signature.txt").write_text(verified)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
