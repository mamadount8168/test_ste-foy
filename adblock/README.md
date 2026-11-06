# Ste-Foy AdBlock

Extension de navigateur (Manifest V3) qui bloque les publicités et traqueurs.

## Fonctionnement
- `rules/ads.json` : règles réseau (declarativeNetRequest), générées par `gen_rules.py`. Ajoutez des domaines dans la liste `DOMAINS`, puis relancez `python3 gen_rules.py`.
- `cosmetic.css` / `content.js` : masquent les emplacements publicitaires.
- Aucune donnée n'est collectée ni envoyée : tout reste dans le navigateur.

## Installation
- **Chrome / Edge** : `chrome://extensions` → Mode développeur → *Charger l'extension non empaquetée* → dossier `adblock/`.
- **Firefox** : `about:debugging` → *Ce Firefox* → *Charger un module complémentaire temporaire* → `adblock/manifest.json`.

## Limites
Une extension ne protège que le navigateur : pas les applications mobiles ni les télés. Pour cela, utilisez un DNS filtrant (AdGuard Home, Pi-hole). Certains sites détectent les bloqueurs, et les pubs intégrées dans les vidéos ne sont pas bloquées.
