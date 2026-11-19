#!/usr/bin/env python3
"""Rewrites the generated blocks of README.md and README.de.md from the files that hold the facts.

Only text between <!-- AUTO:name --> and <!-- /AUTO:name --> is touched; everything else is written by hand.
Sources: app/build.gradle (version, minSdk, targetSdk), fastlane/metadata/android/<locale>/ (changelog, Play
description, screenshots), the SPDX lines in app/src/main (license), e2e/run.sh and app/src/test (tests).
Usage: python3 utils/readme/generate.py [repo root]. Every block of both files is rendered before anything is
written, so a missing source fails without leaving a half README.
"""
import collections
import pathlib
import re
import sys

REPO = 'BEKO2210/ordo'
# API level -> Android version; an unknown level fails loudly instead of printing a guess.
ANDROID = {21: '5.0', 22: '5.1', 23: '6.0', 24: '7.0', 25: '7.1', 26: '8.0', 27: '8.1', 28: '9', 29: '10',
           30: '11', 31: '12', 32: '12L', 33: '13', 34: '14', 35: '15', 36: '16', 37: '17'}
MARKER = re.compile(r'<!-- AUTO:([\w-]+) -->\n.*?<!-- /AUTO:\1 -->', re.S)

LANGS = {
    'README.md': dict(
        locale='en-US', release='release', license='License: {lic}', badge_license='license',
        new='New in {v}',
        status='Current version {v} · [release v{v}]({url}) · Android {min} and later '
               '(minSdk {mins}, targetSdk {tgts} = Android {tgt}) · [{lic}](LICENSE)',
        phone='Phone', tablet7='7-inch tablet', tablet10='10-inch tablet',
        shots_note='The pictures of the Play listing, from `fastlane/metadata/android/{loc}/images/`.',
        features_note='From the Play listing, `fastlane/metadata/android/{loc}/full_description.txt`.',
        tests='Device tests: {n} scripts in [`e2e/`](e2e/README.md), run with `e2e/run.sh`: {ro} read-only '
              '({ro_list}) and {w} more with `--write` ({w_list}). Unit tests: {u} test classes in '
              '`app/src/test`.'),
    'README.de.md': dict(
        locale='de-DE', release='Version', license='Lizenz: {lic}', badge_license='Lizenz',
        new='Neu in {v}',
        status='Aktuelle Version {v} · [Release v{v}]({url}) · ab Android {min} '
               '(minSdk {mins}, targetSdk {tgts} = Android {tgt}) · [{lic}](LICENSE)',
        phone='Handy', tablet7='7-Zoll-Tablet', tablet10='10-Zoll-Tablet',
        shots_note='Die Bilder des Play-Eintrags, aus `fastlane/metadata/android/{loc}/images/`.',
        features_note='Aus dem Play-Eintrag, `fastlane/metadata/android/{loc}/full_description.txt`.',
        tests='Gerätetests: {n} Skripte in [`e2e/`](e2e/README.md), gestartet mit `e2e/run.sh`: {ro} nur '
              'lesend ({ro_list}) und {w} weitere mit `--write` ({w_list}). Unit-Tests: {u} Testklassen in '
              '`app/src/test`.'),
}


class SourceError(Exception):
    pass


def read(root, rel):
    path = root / rel
    if not path.is_file():
        raise SourceError(f'missing source: {rel}')
    return path.read_text(encoding='utf-8')


def find(pattern, text, what):
    m = re.search(pattern, text, re.M)
    if not m:
        raise SourceError(f'not found: {what}')
    return m.group(1)


def android(api):
    if api not in ANDROID:
        raise SourceError(f'unknown API level {api}: add it to ANDROID in utils/readme/generate.py')
    return ANDROID[api]


def gradle(root):
    g = read(root, 'app/build.gradle')
    return dict(v=find(r"^\s*versionName\s+'([^']+)'", g, 'versionName in app/build.gradle'),
                mins=int(find(r'^\s*minSdk\s+(\d+)', g, 'minSdk in app/build.gradle')),
                tgts=int(find(r'^\s*targetSdk\s+(\d+)', g, 'targetSdk in app/build.gradle')))


def spdx(root):
    ids = collections.Counter()
    for p in (root / 'app/src/main').rglob('*'):
        if p.suffix in ('.kt', '.java', '.xml') and p.is_file():
            ids.update(re.findall(r'SPDX-License-Identifier:\s*([\w.+-]+)', p.read_text(errors='ignore')))
    if not ids:
        raise SourceError('no SPDX-License-Identifier in app/src/main')
    return ids.most_common(1)[0][0]


def bullets(text, what):
    items = [l[1:].strip() for l in text.splitlines() if l.startswith('•')]
    if not items:
        raise SourceError(f'no • lines in {what}')
    return items


def features(root, loc):
    rel = f'fastlane/metadata/android/{loc}/full_description.txt'
    out = []
    # Sections are blank-line separated: a title line, then text and/or • lines. Only sections with • lines are
    # features; the intro, privacy and license paragraphs have their own hand-written README sections.
    for block in read(root, rel).strip().split('\n\n'):
        title, *rest = block.splitlines()
        if not any(l.startswith('•') for l in rest):
            continue
        out.append(f'**{title.strip()}**\n')
        prose = [l.strip() for l in rest if not l.startswith('•')]
        if prose:
            out.append(' '.join(prose) + '\n')
        out.extend(f'- {b}' for b in bullets('\n'.join(rest), rel))
        out.append('')
    if not out:
        raise SourceError(f'no feature sections in {rel}')
    return '\n'.join(out).rstrip()


def gallery(root, loc, kind, label, per_row, width):
    rel = f'fastlane/metadata/android/{loc}/images/{kind}'
    files = sorted(p.name for p in (root / rel).glob('*') if p.suffix.lower() in ('.png', '.jpg', '.jpeg'))
    if not files:
        return []
    rows = [f'<p align="center"><sub>{label}</sub></p>']
    for i in range(0, len(files), per_row):
        rows.append('<p align="center">')
        for f in files[i:i + per_row]:
            name = re.sub(r'^\d+_', '', f.rsplit('.', 1)[0]).replace('_', ' ')
            rows.append(f'  <img src="{rel}/{f}" width="{width}" alt="{label}: {name}">')
        rows.append('</p>')
    return rows


def tests(root, t):
    run = read(root, 'e2e/run.sh')
    ro = find(r'^tests="([^"$]+)"', run, 'read-only tests in e2e/run.sh').split()
    w = find(r'tests="\$tests ([^"]+)"', run, '--write tests in e2e/run.sh').split()
    for name in ro + w:
        if not (root / 'e2e' / f'{name}.sh').is_file():
            raise SourceError(f'e2e/run.sh lists {name}, but e2e/{name}.sh is missing')
    units = [p for p in (root / 'app/src/test').rglob('*Test.*') if p.suffix in ('.kt', '.java')]
    if not units:
        raise SourceError('no unit tests in app/src/test')
    code = lambda names: ', '.join(f'`{n}`' for n in names)
    return t['tests'].format(n=len(ro) + len(w), ro=len(ro), ro_list=code(ro), w=len(w), w_list=code(w),
                             u=len(units))


def render(root, t, g, lic):
    loc, v = t['locale'], g['v']
    url = f'https://github.com/{REPO}/releases/tag/v{v}'
    shield = lambda s: s.replace('-', '--').replace(' ', '%20')
    log = f'fastlane/metadata/android/{loc}/changelogs/default.txt'
    shots = []
    for kind, label, per_row, width in (('phoneScreenshots', t['phone'], 4, '24%'),
                                        ('sevenInchScreenshots', t['tablet7'], 3, '32%'),
                                        ('tenInchScreenshots', t['tablet10'], 3, '32%')):
        shots += gallery(root, loc, kind, label, per_row, width)
    if not shots:
        raise SourceError(f'no screenshots in fastlane/metadata/android/{loc}/images/')
    return {
        'badges': '\n'.join([
            f'  <a href="{url}"><img alt="{t["release"]} {v}" src="https://img.shields.io/badge/'
            f'{t["release"]}-{shield(v)}-2563EB"></a>',
            f'  <img alt="Android {android(g["mins"])}+" src="https://img.shields.io/badge/Android-'
            f'{shield(android(g["mins"]))}%2B-3DDC84">',
            f'  <a href="LICENSE"><img alt="{t["license"].format(lic=lic)}" src="https://img.shields.io/badge/'
            f'{t["badge_license"]}-{shield(lic)}-blue"></a>']),
        'new': '\n'.join([f'> **{t["new"].format(v=v)}**'] + [f'> - {b}' for b in bullets(read(root, log), log)]),
        'status': t['status'].format(v=v, url=url, min=android(g['mins']), mins=g['mins'], tgts=g['tgts'],
                                     tgt=android(g['tgts']), lic=lic),
        'features': t['features_note'].format(loc=loc) + '\n\n' + features(root, loc),
        'screenshots': t['shots_note'].format(loc=loc) + '\n\n' + '\n'.join(shots),
        'tests': tests(root, t),
    }


def apply(text, blocks, name):
    found = MARKER.findall(text)
    if sorted(found) != sorted(blocks):
        raise SourceError(f'{name}: blocks {sorted(found)} do not match the generated ones {sorted(blocks)}')
    return MARKER.sub(lambda m: f'<!-- AUTO:{m.group(1)} -->\n{blocks[m.group(1)]}\n<!-- /AUTO:{m.group(1)} -->',
                      text)


def generate(root):
    """Returns {README path: new text}; raises SourceError before anything is written."""
    root = pathlib.Path(root)
    g, lic = gradle(root), spdx(root)
    return {root / name: apply(read(root, name), render(root, t, g, lic), name) for name, t in LANGS.items()}


def main():
    root = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parents[2]
    try:
        out = generate(root)
    except SourceError as e:
        sys.exit(f'README not generated: {e}')
    for path, text in out.items():
        if path.read_text(encoding='utf-8') != text:
            path.write_text(text, encoding='utf-8')
            print(f'updated {path.name}')


if __name__ == '__main__':
    main()
