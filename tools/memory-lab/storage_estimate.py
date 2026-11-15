#!/usr/bin/env python3
"""Size and build time of the on-device schema (KONZEPT.md, Datenmodell) for the synthetic phone, scaled to 10k files."""
import json
import os
import pathlib
import sqlite3
import tempfile
import time

import build_graph as bg

SCHEMA = """
CREATE TABLE folder(id INTEGER PRIMARY KEY, parent INTEGER, name TEXT NOT NULL);
CREATE TABLE file(id INTEGER PRIMARY KEY, folder INTEGER NOT NULL, name TEXT NOT NULL, size INTEGER, mtime INTEGER,
                  kind INTEGER, origin INTEGER, lat REAL, lon REAL, media_id INTEGER);
CREATE INDEX file_folder ON file(folder);
CREATE TABLE node(id INTEGER PRIMARY KEY, kind INTEGER, label TEXT, folder INTEGER, selector TEXT, t0 INTEGER, t1 INTEGER,
                  files INTEGER, bytes INTEGER, user_label TEXT);
CREATE TABLE member(node INTEGER, file INTEGER, PRIMARY KEY(node, file)) WITHOUT ROWID;
CREATE TABLE edge(a INTEGER, b INTEGER, kind INTEGER, weight REAL, PRIMARY KEY(a, b, kind)) WITHOUT ROWID;
CREATE TABLE keyword(word TEXT, node INTEGER, PRIMARY KEY(word, node)) WITHOUT ROWID;
CREATE TABLE habit_event(t INTEGER, op INTEGER, from_folder INTEGER, to_folder INTEGER, name_token TEXT, age_days INTEGER);
"""

files, history = bg.load()
t = time.time()
g = bg.build(files, history)
build_s = time.time() - t
with tempfile.TemporaryDirectory() as d:
    db = sqlite3.connect(os.path.join(d, "memory.db"))
    db.executescript(SCHEMA)
    folders = {}
    for f in files:
        parts = f["path"].split("/")
        for i in range(1, len(parts)):
            key = "/".join(parts[:i])
            if key not in folders:
                folders[key] = len(folders) + 1
                db.execute("INSERT INTO folder VALUES(?,?,?)", (folders[key], folders.get("/".join(parts[:i - 1])), parts[i - 1]))
    ids = {}
    for i, f in enumerate(files, 1):
        ids[f["path"]] = i
        db.execute("INSERT INTO file VALUES(?,?,?,?,?,?,?,?,?,?)", (i, folders[os.path.dirname(f["path"])],
                   os.path.basename(f["path"]), f["size"], f["mtime"], 1, 1, f["lat"], f["lon"], i))
    for n in g["nodes"]:
        nid = int(n["id"][1:])
        db.execute("INSERT INTO node VALUES(?,?,?,?,?,?,?,?,?,?)", (nid, 1, n["text"], None, json.dumps(n["selector"]), 0, 0,
                   len(n["members"]), 0, None))
        db.executemany("INSERT INTO member VALUES(?,?)", [(nid, ids[m]) for m in set(n["members"])])
        db.executemany("INSERT OR IGNORE INTO keyword VALUES(?,?)", [(k, nid) for k in n["keywords"]])
    for h in history:
        db.execute("INSERT INTO habit_event VALUES(?,?,?,?,?,?)", (h["t"], 1, 1, 2, h.get("name", "")[:20], h.get("age_days")))
    db.commit()
    db.execute("VACUUM")
    db.close()
    size = os.path.getsize(os.path.join(d, "memory.db"))
print(json.dumps({"files": len(files), "nodes": len(g["nodes"]), "member_rows": sum(len(set(n["members"])) for n in g["nodes"]),
                  "db_kb": round(size / 1024), "db_kb_per_10k_files": round(size / 1024 * 10000 / len(files)),
                  "graph_build_s_python": round(build_s, 3)}))
