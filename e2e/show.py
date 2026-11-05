import json,sys
for name in sys.argv[1:]:
    raw=open(f'results/{name}.json').read()
    print('==', name)
    try: d=json.loads(raw[raw.index('{'):])
    except Exception: print('  unparsable', raw[:200]); continue
    for k,v in d['checks'].items(): print('  ', 'PASS' if v['ok'] else 'FAIL', k, str(v['detail'])[:200].replace('\n',' '))
    print('   notes:', d.get('notes'), d.get('firstRemovals'))
