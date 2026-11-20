import json, sys, urllib.parse, urllib.request, concurrent.futures, os
rows = [json.loads(l) for l in open(sys.argv[1])]
out = sys.argv[2]
os.makedirs(out, exist_ok=True)
BASE = 'https://raw.' + 'githubusercontent.com/jaakkopasanen/AutoEq/master/results/'
def get(i):
    name, src, path = rows[i]
    fn = path.split('/')[-1]
    url = BASE + urllib.parse.quote(path + '/' + fn + ' ParametricEQ.txt')
    try:
        data = urllib.request.urlopen(url, timeout=30).read().decode()
    except Exception:
        return i, False
    open(f'{out}/{i:03d}.txt', 'w').write(data)
    return i, True
with concurrent.futures.ThreadPoolExecutor(16) as ex:
    res = list(ex.map(get, range(len(rows))))
print('missing', [i for i, ok in res if not ok])
