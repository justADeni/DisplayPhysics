"""Applies the packet id shifts found in idiff.py (26.2 -> 26.3) to the bot's 26.3 protocol.json."""
import json, difflib

path = 'node_modules/minecraft-data/minecraft-data/data/pc/26.3/protocol.json'
proto = json.load(open(path))
report = json.load(open('idiff.json'))

def snake(const):
    return const.split('_', 1)[1].lower()

for key, seqs in report.items():
    state, direction = key.split('.')
    if state == 'login':
        continue
    types = proto[state][direction]['types']
    packet = types['packet']
    mapper = packet[1][0]['type'][1]
    switch = packet[1][1]['type'][1]
    current = {int(k, 16): v for k, v in mapper['mappings'].items()}
    names = [current[i] for i in sorted(current)]
    old, new = seqs['old'], seqs['new']
    if len(names) != len(old):
        print(f'!! {key}: protocol.json has {len(names)} packets, the server jar has {len(old)}; skipping')
        continue

    out = []
    sm = difflib.SequenceMatcher(None, old, new, autojunk=False)
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == 'equal':
            out.extend(names[i1:i2])
        elif tag == 'delete':
            print(f'   {key}: removed {names[i1:i2]}')
        elif tag == 'insert':
            for const in new[j1:j2]:
                placeholder = 'new_' + snake(const)
                out.append(placeholder)
                types['packet_' + placeholder] = ['container', [{'name': 'data', 'type': 'restBuffer'}]]
                switch['fields'][placeholder] = 'packet_' + placeholder
                print(f'   {key}: inserted {placeholder}')
        else:
            raise SystemExit(f'unexpected {tag} in {key}')
    mapper['mappings'] = {hex(i): n for i, n in enumerate(out)}
    print(f'{key}: {len(names)} -> {len(out)} packets')

json.dump(proto, open(path, 'w'))
