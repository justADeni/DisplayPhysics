"""Installs the real 26.2 / 26.3 protocol data from the upstream branches into the bot's minecraft-data."""
import json, os, shutil, subprocess, tarfile, urllib.request

base = 'node_modules/minecraft-data/minecraft-data/data'
data_js = 'node_modules/minecraft-data/data.js'

for name, branch in (('26.2', 'pc_26_2'), ('26.3', 'pc_26_3')):
    tgz = f'{branch}.tgz'
    if not os.path.exists(tgz):
        urllib.request.urlretrieve(f'https://codeload.github.com/PrismarineJS/minecraft-data/tar.gz/{branch}', tgz)
    with tarfile.open(tgz) as t:
        prefix = None
        for m in t.getmembers():
            if prefix is None:
                prefix = m.name.split('/')[0]
            if m.name.startswith(f'{prefix}/data/pc/{name}/'):
                m.name = m.name[len(prefix) + 1 + len('data/'):]
                t.extract(m, base)
    paths = json.loads(subprocess.check_output(['curl', '-s', f'https://raw.githubusercontent.com/PrismarineJS/minecraft-data/{branch}/data/dataPaths.json']))['pc'][name]

    # files the branch's data points at that the npm package does not ship
    wanted = {f'data/{rel}/{key}.json' for key, rel in paths.items() if key != 'proto'}
    wanted |= {f'data/{rel}/proto.yml' for key, rel in paths.items() if key == 'proto'}
    with tarfile.open(tgz) as t:
        for m in t.getmembers():
            rel = m.name.split('/', 1)[1] if '/' in m.name else ''
            if rel in wanted and not os.path.exists(os.path.join(base, rel[len('data/'):])):
                m.name = rel[len('data/'):]
                t.extract(m, base)

    s = open(data_js).read()
    assert f"'{name}': {{" not in s, 'reinstall minecraft-data first'
    getters = []
    for key, rel in paths.items():
        if key == 'proto':
            getters.append(f"      proto: __dirname + '/minecraft-data/data/{rel}/proto.yml'")
        else:
            getters.append(f'      get {key} () {{ return require("./minecraft-data/data/{rel}/{key}.json") }}')
    block = f"    '{name}': {{\n" + ",\n".join(getters) + "\n    }"
    start = s.index("    '26.1': {")
    end = s.index("\n    }\n", start) + len("\n    }")
    s = s[:end] + ",\n" + block + s[end:]
    open(data_js, 'w').write(s)
    print('installed', name, 'protocol', json.load(open(f'{base}/pc/{name}/version.json'))['version'])

f = 'node_modules/minecraft-protocol/src/version.js'
s = open(f).read()
if "'26.3'" not in s:
    s = s.replace("'26.1']", "'26.1', '26.2', '26.3']")
    open(f, 'w').write(s)
