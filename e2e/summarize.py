import json,sys,glob
for f in sorted(glob.glob('results/*.json')):
    raw=open(f).read()
    print('==', f.split('/')[-1][:-5])
    try:
        d=json.loads(raw[raw.index('{'):])
    except Exception:
        print('  unparsable:', raw[:200].replace('\n',' ')); continue
    for k,v in d['checks'].items(): print('  ', 'PASS' if v['ok'] else 'FAIL', k, str(v['detail'])[:170].replace('\n',' '))
    print('   notes:', d.get('notes'))
