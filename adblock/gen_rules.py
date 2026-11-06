#!/usr/bin/env python3
"""Génère rules/ads.json (règles declarativeNetRequest) à partir d'une liste de domaines."""
import json
DOMAINS = """
doubleclick.net googlesyndication.com googleadservices.com adservice.google.com
pagead2.googlesyndication.com googletagservices.com google-analytics.com
googletagmanager.com adnxs.com adsrvr.org advertising.com criteo.com criteo.net
taboola.com outbrain.com rubiconproject.com pubmatic.com openx.net casalemedia.com
smartadserver.com amazon-adsystem.com moatads.com scorecardresearch.com
quantserve.com adform.net adition.com 2mdn.net serving-sys.com yieldmo.com
media.net bidswitch.net contextweb.com sharethrough.com teads.tv zedo.com
popads.net popcash.net propellerads.com exoclick.com juicyads.com trafficjunky.net
adcolony.com applovin.com unity3d.com/ads mopub.com inmobi.com chartboost.com
facebook.net/en_US/fbevents.js connect.facebook.net hotjar.com mouseflow.com
adsafeprotected.com doubleverify.com ads-twitter.com analytics.tiktok.com
""".split()
rules = []
for i, d in enumerate(DOMAINS, 1):
    rules.append({
        "id": i, "priority": 1,
        "action": {"type": "block"},
        "condition": {"urlFilter": f"||{d}^", "resourceTypes": [
            "script", "image", "xmlhttprequest", "sub_frame", "stylesheet",
            "media", "ping", "font", "other"]}
    })
with open("rules/ads.json", "w") as f:
    json.dump(rules, f, indent=1)
print(len(rules), "règles")
