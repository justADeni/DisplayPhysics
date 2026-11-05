"""Which packets did 26.3 add, remove or move, compared to 26.2? Reads the registration order out of the server jars."""
import subprocess, re, difflib, sys, json

JARS = {
    '26.2': 'C:/Users/Deni/IdeaProjects/Logically/run-26.2/versions/26.2/paper-26.2.jar',
    '26.3': 'C:/Users/Deni/IdeaProjects/Logically/run/versions/26.3/paper-26.3.jar',
}
CLASSES = {
    'configuration': 'net.minecraft.network.protocol.configuration.ConfigurationProtocols',
    'play': 'net.minecraft.network.protocol.game.GameProtocols',
    'login': 'net.minecraft.network.protocol.login.LoginProtocols',
}

def sequences(jar, cls):
    out = subprocess.check_output(['javap', '-cp', jar, '-p', '-c', cls], text=True)
    result = []
    for method in re.split(r'\n  (?=[a-z].*\)\s*(?:throws.*)?;\n)', out):
        names = re.findall(r'getstatic\s+#\d+\s+// Field ([\w/$]+PacketTypes)\.((?:CLIENTBOUND|SERVERBOUND)_[A-Z0-9_]+):', method)
        names = [n for _, n in names]
        if len(names) >= 3:
            result.append(names)
    return result

def split_directions(seqs):
    """The flat sequences hold clientbound and serverbound packets mixed per method; split by prefix, keep order."""
    c, s = [], []
    for seq in seqs:
        for n in seq:
            (c if n.startswith('CLIENTBOUND') else s).append(n)
    return c, s

report = {}
for state, cls in CLASSES.items():
    a = split_directions(sequences(JARS['26.2'], cls))
    b = split_directions(sequences(JARS['26.3'], cls))
    for label, x, y in (('toClient', a[0], b[0]), ('toServer', a[1], b[1])):
        sm = difflib.SequenceMatcher(None, x, y, autojunk=False)
        ops = [(tag, x[i1:i2], y[j1:j2], i1, j1) for tag, i1, i2, j1, j2 in sm.get_opcodes() if tag != 'equal']
        print(f'{state}.{label}: 26.2 has {len(x)}, 26.3 has {len(y)}')
        for tag, xs, ys, i1, j1 in ops:
            print(f'   {tag} at 26.2 index {i1} / 26.3 index {j1}: {xs} -> {ys}')
        report[f'{state}.{label}'] = {'old': x, 'new': y}
json.dump(report, open('idiff.json', 'w'))
