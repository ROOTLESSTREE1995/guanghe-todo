#!/usr/bin/env python3
"""Install an isolated JDK 17 + Android SDK for this project (macOS/Linux).

Usage:
  python3 scripts/setup-toolchain.py --directory .toolchain --accept-sdk-licenses
  BANXU_TOOLCHAIN="$PWD/.toolchain" ./scripts/build.sh

Requires Python 3, curl, tar and unzip. Downloads come only from the official
Adoptium and Google distribution endpoints. Each archive is verified against
its publisher's checksum before extraction. No system-wide installation occurs.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shlex
import subprocess
import sys
import xml.etree.ElementTree as ET


def download(url, destination, algorithm=None, expected=None):
    if destination.exists() and expected:
        if digest(destination, algorithm) == expected:
            return
    temporary = destination.with_suffix(destination.suffix + '.part')
    subprocess.run(['curl', '-fLsS', '--retry', '3', '--connect-timeout', '30',
                    url, '-o', str(temporary)], check=True)
    if expected and digest(temporary, algorithm) != expected:
        temporary.unlink(missing_ok=True)
        raise RuntimeError('Checksum mismatch: ' + destination.name)
    temporary.replace(destination)


def digest(path, algorithm):
    value = hashlib.new(algorithm)
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--directory', default='.toolchain')
    parser.add_argument('--accept-sdk-licenses', action='store_true',
                        help='Accept the Android SDK licenses for this installation.')
    args = parser.parse_args()
    system, machine = platform.system(), platform.machine().lower()
    if system == 'Darwin' and machine in ('arm64', 'aarch64', 'x86_64'):
        os_name, host_os = 'mac', 'macosx'
        arch = 'aarch64' if machine in ('arm64', 'aarch64') else 'x64'
    elif system == 'Linux' and machine == 'x86_64':
        os_name, host_os, arch = 'linux', 'linux', 'x64'
    else:
        sys.exit('Use Android Studio with JDK 17 on this operating system. '
                 'This bootstrap supports macOS (Apple Silicon/Intel) and Linux x64.')

    root = Path(args.directory).expanduser().resolve()
    downloads = root / 'downloads'
    sdk = root / 'android-sdk'
    downloads.mkdir(parents=True, exist_ok=True)
    sdk.mkdir(parents=True, exist_ok=True)

    jdk_metadata = downloads / 'jdk-metadata.json'
    url = ('https://api.adoptium.net/v3/assets/latest/17/hotspot?'
           f'architecture={arch}&image_type=jdk&os={os_name}&vendor=eclipse')
    download(url, jdk_metadata)
    metadata = json.loads(jdk_metadata.read_text())[0]
    package = metadata['binary']['package']
    archive = downloads / package['name']
    print('Downloading and verifying Temurin JDK 17…', flush=True)
    download(package['link'], archive, 'sha256', package['checksum'])
    jdk_root = root / 'jdk'
    jdk_root.mkdir(exist_ok=True)
    subprocess.run(['tar', '-xzf', str(archive), '--strip-components=1',
                    '-C', str(jdk_root)], check=True)
    java_home = jdk_root / 'Contents/Home' if system == 'Darwin' else jdk_root

    repository = downloads / 'android-repository.xml'
    download('https://dl.google.com/android/repository/repository2-1.xml', repository)
    sdk_package = next(node for node in ET.parse(repository).getroot().findall('remotePackage')
                       if node.attrib.get('path') == 'cmdline-tools;19.0')
    sdk_archive = next(node.find('complete') for node in sdk_package.findall('./archives/archive')
                       if node.findtext('host-os') == host_os)
    cli_archive = downloads / sdk_archive.findtext('url')
    print('Downloading and verifying Android command-line tools…', flush=True)
    download('https://dl.google.com/android/repository/' + sdk_archive.findtext('url'),
             cli_archive, 'sha1', sdk_archive.findtext('checksum'))
    cli_parent = sdk / 'cmdline-tools'
    cli_parent.mkdir(exist_ok=True)
    cli = cli_parent / '19.0'
    if not (cli / 'bin/sdkmanager').exists():
        subprocess.run(['unzip', '-q', str(cli_archive), '-d', str(cli_parent)], check=True)
        (cli_parent / 'cmdline-tools').rename(cli)

    variables = {'JAVA_HOME': str(java_home), 'ANDROID_HOME': str(sdk),
                 'ANDROID_SDK_ROOT': str(sdk), 'ANDROID_USER_HOME': str(root / 'android-user'),
                 'ANDROID_AVD_HOME': str(root / 'android-user/avd'),
                 'GRADLE_USER_HOME': str(root / 'gradle-home')}
    environment = dict(os.environ, **variables)
    environment['PATH'] = str(java_home / 'bin') + os.pathsep + os.environ.get('PATH', '')
    manager = [str(cli / 'bin/sdkmanager'), '--sdk_root=' + str(sdk)]
    print('Installing Android API 35, Build Tools 35.0.0 and platform tools…', flush=True)
    if args.accept_sdk_licenses:
        subprocess.run(manager + ['--licenses'], input='y\n' * 100,
                       text=True, env=environment, check=True)
    else:
        subprocess.run(manager + ['--licenses'], env=environment, check=True)
    subprocess.run(manager + ['platforms;android-35', 'build-tools;35.0.0', 'platform-tools'],
                   env=environment, check=True)
    (root / 'env.sh').write_text(
        '\n'.join('export ' + key + '=' + shlex.quote(value)
                  for key, value in variables.items()) +
        '\nexport PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/19.0/bin:$ANDROID_HOME/platform-tools:$PATH"\n')
    print('Ready. Build with: BANXU_TOOLCHAIN=' + shlex.quote(str(root)) + ' ./scripts/build.sh')


if __name__ == '__main__':
    main()
