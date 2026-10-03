#!/usr/bin/env python3
"""Install wheel and sdist in separate clean venvs, outside the source checkout."""
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import venv
import zipfile

root = Path(__file__).resolve().parents[1]
dist = Path(sys.argv[1] if len(sys.argv) > 1 else root / 'python/dist').resolve()
wheel, = dist.glob('*.whl')
sdist, = dist.glob('*.tar.gz')
with zipfile.ZipFile(wheel) as archive:
    names = archive.namelist()
    assert all(name.startswith(('marionette_mc/', 'marionette_mc-0.1.0a1.dist-info/')) for name in names), names
    assert 'marionette_mc/py.typed' in names
    license_name, = [name for name in names if name.endswith('/licenses/LICENSE')]
    assert archive.read(license_name) == (root / 'LICENSE').read_bytes()
    metadata = archive.read('marionette_mc-0.1.0a1.dist-info/METADATA').decode()
    for field in ('Name: marionette-mc', 'Version: 0.1.0a1', 'Requires-Python: >=3.11',
                  'License-Expression: MIT', 'Requires-Dist: websockets==15.0.1'):
        assert field in metadata, field
    print('WHEEL CONTENTS', *names, sep='\n', flush=True)
with tarfile.open(sdist) as archive:
    members = archive.getmembers()
    assert all(not m.issym() and not m.islnk() and '..' not in Path(m.name).parts for m in members)
    assert any(m.name.endswith('/tests/fixtures/protocol2.json') for m in members)
    assert not any(any(p in m.name for p in ('__pycache__', '.git/', '.venv/', 'run/', 'build/')) for m in members)
    print('SDIST CONTENTS', *(m.name for m in members), sep='\n', flush=True)
    with tempfile.TemporaryDirectory(prefix='marionette-packaging-') as tmp:
        work = Path(tmp)
        archive.extractall(work, filter='data')
        extracted, = work.glob('marionette_mc-*')
        for artifact in (wheel, sdist):
            target = work / ('wheel-env' if artifact == wheel else 'sdist-env')
            venv.EnvBuilder(with_pip=True).create(target)
            interpreter = target / ('Scripts/python.exe' if os.name == 'nt' else 'bin/python')
            env = {k: v for k, v in os.environ.items() if k not in ('PYTHONPATH', 'PYTHONHOME')}
            env['PIP_CONSTRAINT'] = str(root / 'python/requirements-dev.txt')
            subprocess.run([str(interpreter), '-m', 'pip', 'install', str(artifact)], cwd=work, env=env, check=True)
            subprocess.run([str(interpreter), '-c',
                            'import importlib.metadata as m, marionette_mc as c; '
                            'assert c.__version__ == m.version("marionette-mc") == "0.1.0a1"; '
                            'print(c.__file__)'], cwd=work, env=env, check=True)
            subprocess.run([str(interpreter), '-m', 'unittest', 'discover', '-s',
                            str(extracted / 'tests'), '-v'], cwd=work, env=env, check=True)
            subprocess.run([str(interpreter), '-m', 'pip', 'check'], cwd=work, env=env, check=True)
for artifact in (wheel, sdist):
    print(hashlib.sha256(artifact.read_bytes()).hexdigest(), artifact, flush=True)
print('Local packaging verified; published-install acceptance remains gated.', flush=True)
