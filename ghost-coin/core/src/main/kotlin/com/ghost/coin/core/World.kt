package com.ghost.coin.core

enum class Continent(val label: String) { AFRIQUE("Afrique"), EUROPE("Europe"), AMERIQUE("Amérique"), ASIE("Asie"), OCEANIE("Océanie") }

/**
 * Un pays du tour du monde. `biome` choisit le paysage, `style` l'architecture des bâtiments,
 * `monument` la forme du monument emblématique (dont le vrai nom est `landmark`).
 */
class Country(
    val code: String, val name: String, val continent: Continent, val lat: Int, val lon: Int,
    val biome: Char, val style: Char, val monument: Char, val landmark: String,
) {
    /** Drapeau sous forme d'emoji, dérivé du code ISO. */
    val flag: String get() = code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

val BIOMES = mapOf(
    's' to "Savane", 'd' to "Désert", 'j' to "Forêt tropicale", 'c' to "Littoral et îles",
    'm' to "Montagnes", 'n' to "Terres du Nord", 'p' to "Plaines et collines", 't' to "Steppe",
)

val STYLES = mapOf(
    'a' to "Architecture de terre", 'b' to "Cases à toit de chaume", 'c' to "Médinas, arcs et coupoles",
    'd' to "Maisons coloniales colorées", 'e' to "Maisons à pignons et colombages", 'f' to "Maisons blanches méditerranéennes",
    'g' to "Maisons de bois peint", 'h' to "Églises à bulbes", 'i' to "Toits courbes d'Asie de l'Est",
    'j' to "Toits effilés d'Asie du Sud-Est", 'k' to "Dômes et iwans", 'l' to "Ville contemporaine",
    'm' to "Fare et maisons sur pilotis", 'o' to "Yourtes",
)

/*
 * Données modulaires : une ligne par pays, dans l'ordre du voyage.
 * code ISO | nom | latitude | longitude | biome | style | monument | lieu emblématique
 * Ajouter un pays = ajouter une ligne. L'ordre des continents est celui de l'énumération Continent.
 */
private val DATA = mapOf(
    Continent.AFRIQUE to """
SN|Sénégal|14|-17|s|a|S|Monument de la Renaissance africaine
ML|Mali|13|-8|s|a|Q|Grande Mosquée de Djenné
CI|Côte d'Ivoire|7|-5|j|d|D|Basilique Notre-Dame de la Paix
CM|Cameroun|4|12|j|b|N|Mont Cameroun
GH|Ghana|6|-1|c|d|A|Arche de l'Indépendance
NG|Nigeria|9|7|s|l|N|Zuma Rock
GN|Guinée|10|-13|j|b|N|Mont Nimba
GM|Gambie|13|-16|c|b|A|Arch 22
BF|Burkina Faso|12|-2|s|a|Q|Grande Mosquée de Bobo-Dioulasso
BJ|Bénin|6|2|c|b|A|Porte du Non-Retour
TG|Togo|8|1|s|a|F|Maisons-tours de Koutammakou
NE|Niger|14|2|d|a|Q|Mosquée d'Agadez
TD|Tchad|12|15|d|a|N|Massif de l'Ennedi
MR|Mauritanie|18|-16|d|c|M|Mosquée de Chinguetti
MA|Maroc|34|-7|d|c|M|Mosquée Koutoubia
DZ|Algérie|36|3|d|c|O|Mémorial du Martyr
TN|Tunisie|37|10|d|c|J|Amphithéâtre d'El Jem
EG|Égypte|30|31|d|c|P|Pyramides de Gizeh
LY|Libye|33|13|d|c|G|Leptis Magna
ZA|Afrique du Sud|-30|25|s|l|N|Montagne de la Table
KE|Kenya|-1|37|s|b|N|Mont Kenya
TZ|Tanzanie|-6|35|s|b|N|Kilimandjaro
ET|Éthiopie|9|39|m|a|F|Églises rupestres de Lalibela
MG|Madagascar|-19|47|c|b|E|Allée des Baobabs
RW|Rwanda|-2|30|m|b|N|Parc national des Volcans
UG|Ouganda|1|32|j|b|N|Lac Victoria
CD|République démocratique du Congo|-3|22|j|b|N|Volcan Nyiragongo
CG|Congo|-4|15|j|b|D|Basilique Sainte-Anne du Congo
GA|Gabon|0|10|j|b|N|Parc national de la Lopé
AO|Angola|-9|14|s|d|F|Forteresse de São Miguel
MZ|Mozambique|-18|36|c|d|F|Île de Mozambique
ZM|Zambie|-15|28|s|b|N|Chutes Victoria
ZW|Zimbabwe|-19|30|s|a|F|Grand Zimbabwe
BW|Botswana|-22|24|s|b|N|Delta de l'Okavango
NA|Namibie|-23|17|d|e|N|Dunes de Sossusvlei
SO|Somalie|3|45|d|c|M|Mosquée Fakr ad-Din
SD|Soudan|16|33|d|a|P|Pyramides de Méroé
SS|Soudan du Sud|5|31|s|b|N|Marais du Sudd
ER|Érythrée|15|39|m|l|Y|Station Fiat Tagliero d'Asmara
DJ|Djibouti|12|43|d|c|N|Lac Assal
LS|Lesotho|-29|28|m|b|N|Thaba Bosiu
SZ|Eswatini|-26|31|s|b|N|Rocher de Sibebe
MW|Malawi|-14|34|s|b|N|Lac Malawi
BI|Burundi|-3|29|m|b|N|Lac Tanganyika
GW|Guinée-Bissau|12|-15|c|d|N|Archipel des Bijagos
GQ|Guinée équatoriale|2|10|j|d|K|Cathédrale Sainte-Isabelle de Malabo
CV|Cap-Vert|15|-24|c|d|N|Pico do Fogo
KM|Comores|-12|43|c|c|M|Mosquée du Vendredi de Moroni
SC|Seychelles|-5|55|c|d|H|Tour de l'Horloge de Victoria
MU|Maurice|-20|58|c|d|N|Le Morne Brabant
ST|Sao Tomé-et-Principe|0|7|c|d|N|Pico Cão Grande
CF|République centrafricaine|6|20|j|b|N|Chutes de Boali
LR|Liberia|6|-10|c|d|N|Lac Piso
SL|Sierra Leone|8|-12|c|d|E|Cotton Tree historique de Freetown
""",
    Continent.EUROPE to """
FR|France|47|2|p|e|T|Tour Eiffel
DE|Allemagne|51|10|p|e|A|Porte de Brandebourg
IT|Italie|42|12|p|f|J|Colisée
ES|Espagne|40|-4|p|f|K|Sagrada Família
PT|Portugal|39|-9|c|f|F|Tour de Belém
BE|Belgique|51|4|p|e|T|Atomium
CH|Suisse|47|8|m|e|N|Cervin
GB|Royaume-Uni|53|-1|p|e|H|Big Ben
IE|Irlande|53|-8|p|e|N|Falaises de Moher
NL|Pays-Bas|52|5|p|e|W|Moulins de Kinderdijk
LU|Luxembourg|50|6|p|e|F|Casemates du Bock
MC|Monaco|44|7|c|f|C|Palais princier
AD|Andorre|43|2|m|e|K|Sanctuaire de Meritxell
LI|Liechtenstein|47|10|m|e|C|Château de Vaduz
AT|Autriche|48|15|m|e|C|Château de Schönbrunn
PL|Pologne|52|20|p|e|C|Château du Wawel
CZ|République tchèque|50|15|p|e|C|Château de Prague
SK|Slovaquie|49|19|p|e|C|Château de Bratislava
HU|Hongrie|47|19|p|e|D|Parlement de Budapest
RO|Roumanie|46|25|p|e|C|Château de Bran
BG|Bulgarie|43|25|p|h|B|Cathédrale Alexandre-Nevski
GR|Grèce|38|23|c|f|G|Parthénon
SE|Suède|60|16|n|g|H|Hôtel de ville de Stockholm
NO|Norvège|61|9|n|g|N|Fjord de Geiranger
DK|Danemark|56|10|n|g|S|La Petite Sirène
FI|Finlande|62|26|n|g|D|Cathédrale d'Helsinki
IS|Islande|65|-18|n|g|K|Hallgrímskirkja
EE|Estonie|59|26|n|g|F|Remparts de Tallinn
LV|Lettonie|57|25|n|g|O|Monument de la Liberté
LT|Lituanie|55|24|p|e|F|Tour de Gediminas
UA|Ukraine|49|32|p|h|B|Cathédrale Sainte-Sophie de Kiev
MD|Moldavie|47|29|p|h|A|Arc de triomphe de Chișinău
BY|Biélorussie|53|28|p|h|C|Château de Mir
HR|Croatie|45|16|c|f|F|Remparts de Dubrovnik
RS|Serbie|44|21|p|h|D|Temple Saint-Sava
SI|Slovénie|46|15|m|e|N|Lac de Bled
ME|Monténégro|43|19|c|f|N|Baie de Kotor
BA|Bosnie-Herzégovine|44|18|m|f|A|Vieux pont de Mostar
AL|Albanie|41|20|c|f|F|Château de Berat
MK|Macédoine du Nord|42|22|m|f|N|Lac d'Ohrid
XK|Kosovo|43|21|m|f|F|Forteresse de Prizren
MT|Malte|36|14|c|f|F|Remparts de La Valette
CY|Chypre|35|33|c|f|G|Site antique de Kourion
SM|Saint-Marin|44|12|m|f|F|Tours du mont Titano
VA|Vatican|42|12|p|f|D|Basilique Saint-Pierre
RU|Russie|56|38|n|h|B|Cathédrale Saint-Basile
""",
    Continent.AMERIQUE to """
CA|Canada|56|-100|n|g|T|Tour CN
US|États-Unis|39|-98|p|l|S|Statue de la Liberté
MX|Mexique|23|-102|d|d|X|Pyramide de Chichén Itzá
BR|Brésil|-10|-52|j|d|S|Christ Rédempteur
AR|Argentine|-35|-64|p|d|O|Obélisque de Buenos Aires
CO|Colombie|4|-73|j|d|K|Cathédrale de sel de Zipaquirá
PE|Pérou|-10|-76|m|a|F|Machu Picchu
CL|Chili|-33|-71|m|d|Z|Moaï de l'île de Pâques
VE|Venezuela|7|-66|j|d|N|Salto Ángel
EC|Équateur|-1|-78|m|d|O|Mitad del Mundo
BO|Bolivie|-17|-65|m|a|A|Porte du Soleil de Tiwanaku
PY|Paraguay|-23|-58|p|d|F|Missions jésuites de Trinidad
UY|Uruguay|-33|-56|p|d|S|La Main de Punta del Este
GY|Guyana|5|-59|j|d|N|Chutes de Kaieteur
SR|Suriname|4|-56|j|d|K|Cathédrale Saints-Pierre-et-Paul de Paramaribo
GT|Guatemala|15|-90|j|d|X|Temple du Grand Jaguar de Tikal
CR|Costa Rica|10|-84|j|d|N|Volcan Arenal
PA|Panama|9|-80|j|l|A|Pont des Amériques
NI|Nicaragua|13|-85|j|d|N|Volcan Momotombo
HN|Honduras|15|-87|j|d|X|Site maya de Copán
SV|Salvador|14|-89|j|d|S|Monument au Divin Sauveur du monde
BZ|Belize|17|-88|c|d|X|Site maya de Xunantunich
CU|Cuba|22|-79|c|d|D|Capitole de La Havane
JM|Jamaïque|18|-77|c|d|N|Chutes de Dunn's River
HT|Haïti|19|-72|c|d|F|Citadelle La Ferrière
DO|République dominicaine|19|-70|c|d|L|Phare de Colomb
BS|Bahamas|25|-77|c|d|F|Fort Fincastle
BB|Barbade|13|-60|c|d|H|Parlement de Bridgetown
TT|Trinité-et-Tobago|11|-61|c|d|N|Baie de Maracas
AG|Antigua-et-Barbuda|17|-62|c|d|F|Arsenal de Nelson
DM|Dominique|15|-61|c|d|N|Boiling Lake
GD|Grenade|12|-62|c|d|F|Fort George
LC|Sainte-Lucie|14|-61|c|d|N|Les Pitons
VC|Saint-Vincent-et-les-Grenadines|13|-61|c|d|N|Volcan de la Soufrière
KN|Saint-Christophe-et-Niévès|17|-63|c|d|F|Forteresse de Brimstone Hill
""",
    Continent.ASIE to """
CN|Chine|35|105|p|i|F|Grande Muraille
JP|Japon|36|138|p|i|R|Torii d'Itsukushima
KR|Corée du Sud|37|128|p|i|I|Palais de Gyeongbokgung
KP|Corée du Nord|40|127|p|i|T|Tour du Juche
IN|Inde|22|79|p|k|D|Taj Mahal
PK|Pakistan|30|70|d|k|M|Mosquée Badshahi
BD|Bangladesh|24|90|j|k|O|Mémorial national des Martyrs
NP|Népal|28|84|m|j|U|Stupa de Bodnath
BT|Bhoutan|27|90|m|j|F|Monastère de Taktshang
LK|Sri Lanka|7|81|c|j|N|Rocher de Sigiriya
MV|Maldives|4|73|c|m|N|Lagons des atolls
TH|Thaïlande|15|101|j|j|I|Wat Arun
VN|Vietnam|16|107|j|j|N|Baie d'Along
KH|Cambodge|13|105|j|j|U|Angkor Vat
LA|Laos|18|103|j|j|U|Pha That Luang
MM|Myanmar|21|96|j|j|U|Pagode Shwedagon
MY|Malaisie|4|102|j|l|Y|Tours Petronas
SG|Singapour|1|104|c|l|S|Merlion
ID|Indonésie|-2|118|j|j|U|Borobudur
PH|Philippines|13|122|c|j|N|Rizières de Banaue
BN|Brunei|5|115|j|k|M|Mosquée Omar Ali Saifuddien
MN|Mongolie|47|104|t|o|S|Statue équestre de Gengis Khan
KZ|Kazakhstan|48|67|t|l|T|Tour Baïterek
UZ|Ouzbékistan|41|64|d|k|D|Registan de Samarcande
KG|Kirghizistan|41|75|m|o|N|Lac Issyk-Koul
TJ|Tadjikistan|39|71|m|k|S|Monument d'Ismaïl Samani
TM|Turkménistan|39|59|d|k|N|Cratère de Darvaza
AF|Afghanistan|34|66|m|k|M|Minaret de Djam
IR|Iran|32|54|d|k|A|Tour Azadi
IQ|Irak|33|44|d|c|X|Ziggourat d'Ur
SA|Arabie saoudite|24|45|d|l|F|Site de Hégra
AE|Émirats arabes unis|24|54|d|l|Y|Burj Khalifa
QA|Qatar|25|51|d|l|Y|Tours de West Bay
BH|Bahreïn|26|50|d|l|F|Fort de Bahreïn
KW|Koweït|29|48|d|l|T|Tours de Koweït
OM|Oman|21|57|d|c|M|Grande Mosquée du Sultan Qabus
YE|Yémen|15|48|d|a|F|Tours de terre de Shibam
JO|Jordanie|31|36|d|c|G|Trésor de Pétra
LB|Liban|34|36|c|f|G|Temples de Baalbek
SY|Syrie|35|38|d|c|F|Krak des Chevaliers
IL|Israël|31|35|c|f|F|Forteresse de Massada
PS|Palestine|32|35|d|c|K|Basilique de la Nativité de Bethléem
TR|Turquie|39|35|p|k|D|Sainte-Sophie
AZ|Azerbaïdjan|40|48|t|l|Y|Flame Towers
GE|Géorgie|42|44|m|e|K|Église de la Trinité de Guerguéti
AM|Arménie|40|45|m|e|K|Monastère de Khor Virap
TL|Timor oriental|-9|126|c|j|S|Cristo Rei de Dili
""",
    Continent.OCEANIE to """
AU|Australie|-25|134|d|l|V|Opéra de Sydney
NZ|Nouvelle-Zélande|-42|173|p|g|N|Fjord de Milford Sound
FJ|Fidji|-18|178|c|m|N|Îles Mamanuca
PG|Papouasie-Nouvelle-Guinée|-6|145|j|m|N|Mont Wilhelm
WS|Samoa|-14|-172|c|m|N|Fosse de To Sua
TO|Tonga|-21|-175|c|m|A|Trilithe de Haʻamonga ʻa Maui
VU|Vanuatu|-16|167|c|m|N|Volcan Yasur
KI|Kiribati|1|173|c|m|N|Atoll de Tarawa
TV|Tuvalu|-8|179|c|m|N|Atoll de Funafuti
NR|Nauru|-1|167|c|m|N|Lagune de Buada
PW|Palaos|7|134|c|m|N|Îles Chelbacheb
MH|Îles Marshall|7|171|c|m|N|Atoll de Majuro
FM|États fédérés de Micronésie|7|158|c|m|F|Cité de Nan Madol
SB|Îles Salomon|-9|160|c|m|N|Lagon de Marovo
""",
)

val COUNTRIES: List<Country> = Continent.entries.flatMap { c ->
    DATA.getValue(c).trim().lines().map { line ->
        val p = line.split('|')
        Country(p[0], p[1], c, p[2].toInt(), p[3].toInt(), p[4][0], p[5][0], p[6][0], p[7])
    }
}

/** Index du premier pays de chaque continent, puis le total : [0, 54, 100, 135, 182, 196]. */
val CONTINENT_START: List<Int> = Continent.entries.map { c -> COUNTRIES.indexOfFirst { it.continent == c } } + COUNTRIES.size

fun countryOf(v: Int) = COUNTRIES[v % COUNTRIES.size]
fun villageName(v: Int) = countryOf(v).name + if (v >= COUNTRIES.size) " · tour ${v / COUNTRIES.size + 1}" else ""
