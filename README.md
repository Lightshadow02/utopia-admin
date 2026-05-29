# utopia-admin

Mod **NeoForge 1.21.1** regroupant plusieurs "plugins" cote serveur pour un serveur Minecraft :

- **Recompenses quotidiennes** (`/daily`) en **calendrier** : l'admin planifie une recompense par date (1 a 2 mois a l'avance) via un **menu GUI**. Cote joueur, le calendrier montre les jours en **coffre** (aujourd'hui), **shulker** (a venir), **vitre verte** (recupere) ou **barriere** (manque). Systeme de **serie (streak)** conserve.
- **Demandes de teleportation entre joueurs** : `/tpa`, `/tpahere`, `/tpaccept`, `/tpadeny` (message **cliquable** dans le chat), avec **animation de particules + son** a la teleportation.
- **Gestion du spawn** : `/spawn`, `/setspawn`.
- **Nettoyage des objets au sol (clear lag)** : chaque objet droppe est supprime au bout d'une duree configurable (5 min par defaut), parametre via un fichier **JSON**.
- **Economie / banque** : solde en pieces par joueur (`/balance`, `/pay`), retrait/depot en **pieces physiques** (`/withdraw`, `/deposit`, clic droit), et commandes admin (`/money give|take|set`).
- **Parcelles** : terrains a **formes libres** (union de boites, non alignees aux chunks) traces par l'admin, **achetables** par les joueurs (`/parcel buy`), avec **protection** (build/coffres/portes/machines) et gestion fine des joueurs autorises (`/parcel trust`).
- **Banniere ASCII** dans les logs au demarrage + **notification a la connexion** si une recompense quotidienne est a recuperer.

> `mod_id` interne : `utopia_admin` (underscore obligatoire, les ids NeoForge interdisent le tiret) ; nom affiche et jar : `utopia-admin`.
> Mod cote serveur. Aucune installation cote client n'est requise (voir la section "Cote serveur / client" plus bas). Le jar va dans le dossier `mods/` du serveur.

---

## Installation

1. Installer **NeoForge `21.1.231`** (ou superieur compatible 1.21.1) sur le serveur.
2. Copier `utopia-admin-1.4.0.jar` (genere dans `build/libs/`) dans le dossier `mods/` du serveur.
3. Demarrer le serveur une fois : le fichier de configuration `config/utopia_admin-common.toml` est cree automatiquement.

---

## Commandes

### Teleportation entre joueurs
| Commande | Permission | Effet |
|---|---|---|
| `/tpa <joueur>` | joueur | Demande a se teleporter **vers** le joueur cible. |
| `/tpahere <joueur>` | joueur | Demande que le joueur cible se teleporte **vers vous**. |
| `/tpaccept [joueur]` | joueur | Accepte la derniere demande recue (ou celle d'un joueur precis). |
| `/tpadeny [joueur]` | joueur | Refuse la demande recue (alias : `/tpdeny`). |

La cible recoit un message cliquable **[ACCEPTER] [REFUSER]**. Une demande expire automatiquement (delai configurable).

### Spawn
| Commande | Permission | Effet |
|---|---|---|
| `/spawn` | joueur | Teleporte au spawn du serveur (spawn personnalise si defini, sinon spawn du monde). |
| `/setspawn` | op niveau 2 | Definit le spawn du serveur a votre position actuelle. |

### Recompenses quotidiennes (calendrier)
| Commande | Permission | Effet |
|---|---|---|
| `/daily` | joueur | Ouvre le **calendrier** du mois (GUI coffre). Clic sur le jour courant (coffre) = recuperer. |
| `/daily claim` | joueur | Reclame directement la recompense du jour (sans menu). |
| `/daily status` | joueur | Affiche l'etat (disponible / prochaine a minuit) et la serie (chat). |
| `/daily admin` | op niveau 2 | Ouvre le **menu d'administration** (calendrier + recompense par defaut + gestion des joueurs). |
| `/daily reset [joueur]` | op niveau 2 | Reinitialise reclamation, serie et historique (soi-meme ou un joueur). |

**Modele :** une recompense **par jour calendaire**. La date donne sa recompense planifiee (voir calendrier admin) ; a defaut, la *recompense par defaut* est donnee. Se reclamer chaque jour fait monter la **serie** ; **un jour manque la reinitialise**.

**Menus GUI (coffre)**, rendus par le client vanilla (aucun mod cote client requis) :
- *Calendrier joueur* (`/daily`) : grille du mois. **Coffre** = aujourd'hui (cliquable pour recuperer), **shulker** = a venir, **vitre verte** = recupere, **barriere** = manque. Fleches = changer de mois.
- *Menu admin* (`/daily admin`) :
  - **Calendrier des recompenses** : navigue par mois (1-2 mois a l'avance), clique un jour (coffre/shulker) pour **editer sa recompense** (place des items -> sauvegarde dans `daily_calendar.json`). Les jours passes ne sont pas modifiables.
  - **Recompense par defaut** : items donnes les jours sans planning (editeur d'items, sauve dans `daily.items`).
  - **Gestion des joueurs** : pour chaque joueur en ligne : forcer la recompense, reinitialiser, ajuster la serie (+1/-1).

### Nettoyage des objets au sol (clear lag)
| Commande | Permission | Effet |
|---|---|---|
| `/clearlag` ou `/clearlag info` | op niveau 2 | Affiche l'etat, la duree par defaut, l'intervalle et le nombre d'objets au sol. |
| `/clearlag reload` | op niveau 2 | Recharge `config/utopia_admin/clearlag.json`. |
| `/clearlag now` | op niveau 2 | Supprime immediatement tous les objets au sol non proteges. |

Toutes les teleportations utilisent un **delai (warmup)** configurable, annulable si le joueur bouge ou subit des degats. Pendant le decompte, un **anneau de particules** tourne autour du joueur, puis une **animation + un son** marquent le depart et l'arrivee (toggle `teleport.effects`).

### Economie / banque
| Commande | Permission | Effet |
|---|---|---|
| `/balance [joueur]` (alias `/bal`) | joueur | Affiche son solde, ou celui d'un autre joueur. |
| `/pay <joueur> <montant>` | joueur | Transfere des pieces de son solde vers un autre joueur. |
| `/withdraw <montant>` | joueur | Retire des pieces de la banque vers l'inventaire (pieces physiques). |
| `/deposit [montant]` | joueur | Depose les pieces de l'inventaire en banque (tout si aucun montant). |
| `/money give\|take\|set <joueur> <montant>` | op niveau 2 | Crediter / debiter / definir le solde d'un joueur. |

Les pieces retirees sont des items **marques** (l'item de base est configurable). On peut aussi **deposer en faisant clic droit** en tenant des pieces. `/balance`, `/pay` et `/money` acceptent des joueurs **hors ligne** (par pseudo).

### Parcelles (terrains)
Terrains a **formes libres** : une parcelle = **union de boites** rectangulaires (tailles differentes, decalages, irregulieres, non alignees aux chunks) pour suivre routes / batiments / reliefs. L'admin les trace, les joueurs les achetent.

**Admin** (op niveau 2) — definir avec l'outil de selection :
| Commande | Effet |
|---|---|
| `/parcel wand` | Recoit l'outil. **Clic gauche** = coin 1, **clic droit** = coin 2 (une boite). |
| `/parcel create <id> [prix]` | Cree une parcelle a partir de la selection (avec prix = mise en vente). |
| `/parcel addbox <id>` | Ajoute la selection comme boite supplementaire (forme irreguliere). |
| `/parcel setprice <id> <prix>` / `setsale <id> <true\|false>` | Prix / mise en vente. |
| `/parcel setowner <id> <joueur>` / `delete <id>` / `tp <id>` | Proprietaire / suppression / teleportation. |

**Joueur** :
| Commande | Effet |
|---|---|
| `/parcel info` / `/parcel list` | Infos de la parcelle sous vos pieds / vos parcelles. |
| `/parcel buy` | Achete la parcelle ou vous vous trouvez (paiement via `/balance`). |
| `/parcel sell <prix>` / `/parcel unsell` | Met en vente / retire de la vente votre parcelle. |
| `/parcel transfer <joueur>` | Donne la parcelle a un joueur. |
| `/parcel trust <joueur> <droits>` | Autorise un joueur. Droits : `build`, `containers`, `doors`, `machines`, ou `all`. |
| `/parcel untrust <joueur>` / `/parcel trustlist` | Retire un joueur / liste des membres. |

**Protection** : a l'interieur d'une parcelle, casser/poser (`build`), ouvrir coffres (`containers`), portes/boutons/leviers (`doors`) et machines/redstone (`machines`) sont reserves au proprietaire et aux membres ayant le droit correspondant. Hors parcelle = libre. Les op peuvent ignorer la protection (`parcel.opBypass`).

> Couverture v1 : casse/pose de blocs + clic droit (coffres/portes/machines). Non couverts pour l'instant : explosions, pistons, propagation de fluide/feu, griefing de mobs, degats aux entites (cadres, supports d'armure). A demander si besoin.

---

## Nettoyage des objets au sol — `config/utopia_admin/clearlag.json`

Chaque objet droppe est supprime **individuellement** lorsque sa duree de vie est ecoulee (comptee depuis qu'il est au sol). Fichier cree automatiquement au premier demarrage ; rechargeable a chaud avec `/clearlag reload`.

```json
{
  "enabled": true,
  "defaultLifetimeSeconds": 300,
  "scanIntervalSeconds": 5,
  "protectNamedItems": true,
  "broadcastOnClear": false,
  "broadcastMessage": "&7%count% objet(s) au sol ont ete nettoyes.",
  "perItemLifetimeSeconds": {
    "minecraft:cobblestone": 30,
    "minecraft:dirt": 30,
    "minecraft:netherrack": 30,
    "minecraft:cobbled_deepslate": 30
  },
  "neverDespawn": [
    "minecraft:nether_star",
    "minecraft:elytra",
    "minecraft:totem_of_undying",
    "minecraft:dragon_egg"
  ]
}
```

| Cle | Role |
|---|---|
| `enabled` | Active/desactive le nettoyage automatique. |
| `defaultLifetimeSeconds` | Duree de vie par defaut (300 = 5 min). `0` ou negatif = ne jamais supprimer. |
| `scanIntervalSeconds` | Frequence des passages (granularite de suppression). |
| `protectNamedItems` | Si `true`, les objets renommes (enclume) ne sont jamais supprimes. |
| `broadcastOnClear` | Annonce dans le chat le nombre d'objets nettoyes (si > 0). |
| `broadcastMessage` | Message d'annonce. `%count%` = nombre, codes couleur `&` supportes. |
| `perItemLifetimeSeconds` | Duree specifique par item (en secondes). `0`/negatif = ne jamais supprimer. |
| `neverDespawn` | Items jamais supprimes, quelle que soit la duree par defaut. |

> Les durees **&le; 5 min** s'appuient sur l'age natif de l'objet (persiste meme apres rechargement de chunk). Une duree **> 5 min** est geree par le mod : son minuteur repart si le chunk se recharge ou si le serveur redemarre.

---

## Configuration des autres modules (`config/utopia_admin-common.toml`)

Le fichier est recharge a chaud quand vous le sauvegardez.

```toml
[teleport]
    # Delai avant teleportation. 0 = instantane.
    warmupSeconds = 3
    cancelOnMove = true
    cancelOnDamage = true
    effects = true               # animation de particules + son a la teleportation

[tpa]
    requestTimeoutSeconds = 60   # expiration d'une demande
    cooldownSeconds = 0          # delai entre deux demandes envoyees

[spawn]
    cooldownSeconds = 0          # delai entre deux /spawn

[economy]
    # Item utilise comme "piece" lors d'un retrait. Repli sur gold_nugget renomme si absent.
    coinItem = "utopiamods:utopiece"
    currencyName = "pieces"      # nom affiche de la monnaie
    startingBalance = 0          # solde de depart d'un nouveau joueur

[parcel]
    wandItem = "minecraft:golden_hoe"   # outil de selection des coins
    opBypass = true                     # les op ignorent la protection des parcelles

[daily]
    # NOTE : en mode calendrier, c'est 1 recompense par jour calendaire (cooldownHours ignore).
    cooldownHours = 24
    announce = false             # annoncer a tout le serveur
    # Recompense PAR DEFAUT (jours sans planning au calendrier). "modid:item quantite".
    items = ["minecraft:diamond 1", "minecraft:cooked_beef 16"]
    # Commandes lancees a chaque reclamation : {player} = pseudo, permission 4.
    commands = ["give {player} minecraft:experience_bottle 8"]

    [daily.streak]
        enabled = true
        resetHours = 48          # (info) un jour calendaire manque reinitialise la serie
        # Paliers de serie : "jour | items | commandes"
        #   - prefixe * = recurrent (ex "*7" = tous les 7 jours)
        #   - items et commandes separes par des virgules, champs facultatifs
        milestones = [
            "7 | minecraft:diamond 5 | ",
            "30 | minecraft:netherite_ingot 2 | effect give {player} minecraft:hero_of_the_village 6000 1"
        ]
```

**Calendrier des recompenses** — `config/utopia_admin/daily_calendar.json` (genere automatiquement, edite via `/daily admin` ou a la main) : une date ISO -> liste d'items.

```json
{
  "2026-06-01": ["minecraft:diamond 3", "minecraft:golden_apple 1"],
  "2026-06-02": ["minecraft:emerald 5"],
  "2026-12-25": ["minecraft:netherite_ingot 1", "minecraft:cake 1"]
}
```

### Exemples de paliers
- `"3 | minecraft:golden_apple 1 | "` : au 3e jour consecutif, donne 1 pomme d'or.
- `"*7 | | say {player} fete sa semaine !"` : tous les 7 jours, execute une commande, sans item.
- `"*1 | minecraft:emerald 1 | "` : 1 emeraude bonus **chaque** jour de serie.

> Limitation : les commandes de palier sont separees par des virgules, evitez donc les virgules **dans** une commande de palier (utilisez plutot `daily.commands` pour ces cas).

---

## Cote serveur / client

Mod **100 % cote serveur** : aucun code client, aucun canal reseau ni registre custom. Les menus utilisent des conteneurs vanilla (`MenuType.GENERIC_9xN`), rendus nativement par le client.

- A installer **uniquement dans le `mods/` du serveur**.
- Les joueurs **n'ont rien a installer** : un client NeoForge sans le mod (et meme un client purement vanilla) peut se connecter (NeoForge 21.1 a retire la verification de version par mod ; la compatibilite depend des canaux reseau, ici aucun).
- Fonctionne aussi en solo / LAN si le jar est present cote client (sans jamais l'imposer aux autres).

---

## Compilation

Le mod cible **Java 21** (requis par Minecraft 1.21.1).

```bash
# Compiler et produire le jar dans build/libs/
./gradlew build

# Lancer un serveur de test (environnement de dev)
./gradlew runServer
```

Si votre `java` par defaut n'est pas Java 21, pointez Gradle dessus :

```bash
JAVA_HOME=/chemin/vers/jdk-21 ./gradlew build
```
