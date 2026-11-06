import json,sys
for r in json.load(open(sys.argv[1])):
    n=int(r['params']['count']); print(r['benchmark'].split('.')[-1], r['params'].get('kernels'), n, "%.1f ns/piece"%(r['primaryMetric']['score']/n))
