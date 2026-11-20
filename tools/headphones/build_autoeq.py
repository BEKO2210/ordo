"""Builds autoeq_presets.json from the 500 picked AutoEq ParametricEQ.txt files (MIT, github.com/jaakkopasanen/AutoEq)."""
import json, re, sys
rows = [json.loads(l) for l in open(sys.argv[1])]
peq_dir, out = sys.argv[2], sys.argv[3]
TYPES = {'PK': 0, 'LSC': 1, 'HSC': 2}
rx = re.compile(r'Filter \d+: ON (PK|LSC|HSC) Fc ([\d.]+) Hz Gain (-?[\d.]+) dB Q ([\d.]+)')
def num(x):
    v = float(x)
    return int(v) if v == int(v) else v
profiles = []
for i, (name, src, path) in enumerate(rows):
    text = open(f'{peq_dir}/{i:03d}.txt').read()
    pre = float(re.search(r'Preamp: (-?[\d.]+) dB', text).group(1))
    flat = []
    for t, fc, g, q in rx.findall(text):
        flat += [TYPES[t], num(fc), num(g), num(q)]
    assert len(flat) == 40, name
    profiles.append([name, src, num(pre), flat])
profiles.sort(key=lambda p: p[0].lower())
doc = {
    'source': 'AutoEq by Jaakko Pasanen, github.com/jaakkopasanen/AutoEq, results/ (MIT License)',
    'selection': '500 models measured by the most independent sources in results/INDEX.md; '
                 'per model oratory1990 > Rtings (B&K 5128) > crinacle (GRAS) > Rtings > crinacle > others',
    'filter': 'type (0 peaking, 1 low shelf, 2 high shelf), Fc Hz, gain dB, Q',
    'profiles': profiles,
}
open(out, 'w').write(json.dumps(doc, separators=(',', ':'), ensure_ascii=False))
