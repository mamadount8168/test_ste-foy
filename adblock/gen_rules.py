#!/usr/bin/env python3
"""Génère rules/ads.json (declarativeNetRequest) à partir de la liste AdGuard DNS
(lists/adguard-dns.txt) + domaines manuels. Usage : python3 gen_rules.py"""
import json, os, re

MANUAL = """
doubleclick.net googlesyndication.com googleadservices.com adservice.google.com
googletagservices.com adnxs.com adsrvr.org criteo.com criteo.net taboola.com
outbrain.com rubiconproject.com pubmatic.com openx.net casalemedia.com
amazon-adsystem.com moatads.com scorecardresearch.com quantserve.com
popads.net propellerads.com exoclick.com hotjar.com ads-twitter.com
""".split()

DOM = re.compile(r"^\|\|([a-z0-9][a-z0-9.-]*\.[a-z]{2,})\^$")
block, allow = set(MANUAL), set()
path = "lists/adguard-dns.txt"
if os.path.exists(path):
    for line in open(path, encoding="utf-8", errors="ignore"):
        line = line.strip()
        if line.startswith("@@"):
            m = DOM.match(line[2:])
            if m: allow.add(m.group(1))
        else:
            m = DOM.match(line)
            if m: block.add(m.group(1))
block -= allow

TYPES = ["script", "image", "xmlhttprequest", "sub_frame", "stylesheet",
         "media", "ping", "font", "websocket", "other"]
CHUNK = 1000
rules, rid = [], 1
for kind, domains, prio, action in (("block", sorted(block), 1, "block"),
                                    ("allow", sorted(allow), 2, "allow")):
    for i in range(0, len(domains), CHUNK):
        rules.append({"id": rid, "priority": prio, "action": {"type": action},
                      "condition": {"requestDomains": domains[i:i+CHUNK], "resourceTypes": TYPES}})
        rid += 1
json.dump(rules, open("rules/ads.json", "w"), separators=(",", ":"))
print(f"{len(block)} domaines bloqués, {len(allow)} exceptions, {len(rules)} règles")
