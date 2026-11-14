#!/usr/bin/env python3
# Copyright (c) 2026 Ordo contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Writes the legal web pages in docs/ from the texts the app shows (res/values*/legal.xml)."""

import html
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / 'app/src/main/res'
# (values directory, language, imprint page, privacy page, other language, back label)
LOCALES = [
    ('values-de', 'de', 'impressum.html', 'datenschutz.html', 'English', 'Startseite'),
    ('values', 'en', 'imprint.html', 'privacy.html', 'Deutsch', 'Home'),
]
LINK = re.compile(r'(https?://[^\s<]+|[\w.+-]+@[\w-]+\.[\w.-]+)')


def unescape(text):
    return text.replace('\\n', '\n').replace("\\'", "'").replace('\\"', '"')


def read(directory):
    root = ET.parse(RES / directory / 'legal.xml').getroot()
    strings = {e.get('name'): unescape(e.text) for e in root.iter('string')}
    arrays = {e.get('name'): [unescape(i.text) for i in e.iter('item')]
              for e in root.iter('string-array')}
    return strings, arrays


def link(match):
    target = match.group(1)
    href = target if target.startswith('http') else 'mailto:' + target
    return f'<a href="{href}">{target}</a>'


def body(text):
    parts = []
    for block in text.split('\n\n'):
        lines = [LINK.sub(link, html.escape(line, quote=False)) for line in block.split('\n')]
        if all(line.startswith('• ') for line in lines):
            items = ''.join(f'<li>{line[2:]}</li>' for line in lines)
            parts.append(f'<ul>{items}</ul>')
        else:
            parts.append('<p>' + '<br>'.join(lines) + '</p>')
    return '\n'.join(parts)


def page(language, title, sections, nav):
    content = '\n'.join(f'<section>\n<h2>{html.escape(heading)}</h2>\n{body(text)}\n</section>'
                        for heading, text in sections)
    links = ' '.join(f'<a href="{href}">{html.escape(label)}</a>' for href, label in nav)
    return f'''<!doctype html>
<html lang="{language}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)} – Ordo</title>
<link rel="icon" href="icon/play-store-icon.png">
<link rel="stylesheet" href="site.css">
</head>
<body>
<header>
<a class="brand" href="./"><img src="icon/play-store-icon.png" alt="" width="40" height="40">Ordo</a>
<nav>{links}</nav>
</header>
<main>
<h1>{html.escape(title)}</h1>
{content}
</main>
</body>
</html>
'''


def main():
    pages = {}
    for directory, language, imprint, privacy, _, _ in LOCALES:
        strings, arrays = read(directory)
        pages[language] = (strings, arrays, imprint, privacy)
    for index, (directory, language, imprint, privacy, other_label, home) in enumerate(LOCALES):
        strings, arrays, _, _ = pages[language]
        _, _, other_imprint, other_privacy = pages[LOCALES[1 - index][1]]
        for kind, file, other in (('imprint', imprint, other_imprint),
                                  ('privacy', privacy, other_privacy)):
            titles = arrays[f'legal_{kind}_titles']
            texts = arrays[f'legal_{kind}_texts']
            assert len(titles) == len(texts), (directory, kind)
            nav = [('./', home), (imprint, strings['legal_imprint_title']),
                   (privacy, strings['legal_privacy_title']), (other, other_label)]
            (ROOT / 'docs' / file).write_text(
                page(language, strings[f'legal_{kind}_title'], zip(titles, texts), nav),
                encoding='utf-8')


if __name__ == '__main__':
    main()
