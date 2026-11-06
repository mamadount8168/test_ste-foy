# AdGuard Home — blocage des pubs pour toute la maison

1. Sur une machine toujours allumée (Raspberry Pi, vieux PC) : `docker compose up -d`
2. Ouvrez `http://IP-DE-LA-MACHINE:3000` et suivez l'assistant.
3. Dans la box internet, mettez l'IP de cette machine comme **serveur DNS** (DHCP).
4. Dans *Filtres > Listes de blocage DNS*, gardez « AdGuard DNS filter » et ajoutez
   « AdAway » et « Peter Lowe's list » si vous voulez plus de couverture.
5. Pour protéger un téléphone hors de la maison, utilisez un DNS privé (AdGuard DNS ou NextDNS).

Limite : le blocage DNS ne retire pas les pubs intégrées aux vidéos (YouTube, applis de streaming).
