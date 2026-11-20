import re, sys, collections, json, urllib.parse
idx = open(sys.argv[1]).read().splitlines()
rx = re.compile(r'^- \[(.+)\]\(\./(.+)\) by (\S.*?)(?: on (.+))?$')
by = collections.defaultdict(list)
for l in idx:
    m = rx.match(l)
    if not m: continue
    name, path, src, rig = m.groups()
    by[name].append((src, rig or '', urllib.parse.unquote(path)))
def prio(e):
    src, rig, _ = e
    if src == 'oratory1990': return 0
    if src == 'Rtings' and rig.startswith('Bruel'): return 1
    if src == 'crinacle' and 'GRAS' in rig: return 2
    if src == 'Rtings': return 3
    if src == 'crinacle': return 4
    return 5
models = sorted(by.items(), key=lambda kv: (-len({s for s,_,_ in kv[1]}), kv[0].lower()))
top = models[:500]
print('sources needed for rank 500:', len({s for s,_,_ in top[-1][1]}), file=sys.stderr)
for name, entries in top:
    e = min(entries, key=prio)
    print(json.dumps([name, e[0], e[2]]))
