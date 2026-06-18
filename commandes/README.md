# 📖 Guide des commandes — utopia-admin

Liste **complète et à jour** de toutes les commandes du mod, classées par **joueur** et **administrateur**.

> **Permissions** : les commandes admin demandent le **niveau d'opérateur 2** (op). Un joueur op (ou la console) y a accès ; un joueur normal non.
> Notation : `<...>` = obligatoire · `[...]` = optionnel · `a|b` = au choix · 🖱️ = ouvre un menu.
> **Menus** : depuis la v1.14.0 les menus utilisent **owo-ui** → le mod doit être installé **côté client ET serveur** (même version), avec **owo-lib** présent sur le client. Certaines saisies (montants, ID) se font dans un **champ à remplir**.

---

## 👤 Commandes joueur

### 🧰 Menu principal
| Commande | Effet |
|---|---|
| `/menu` | 🖱️ Ouvre le **hub principal** du joueur (accès aux menus disponibles). |

### 🧭 Téléportation entre joueurs
| Commande | Effet |
|---|---|
| `/tpa <joueur>` | Demande à **te téléporter vers** ce joueur. |
| `/tpahere <joueur>` | Demande que ce joueur **se téléporte vers toi**. |
| `/tpaccept [joueur]` | Accepte la dernière demande reçue (ou celle d'un joueur précis). |
| `/tpadeny [joueur]` | Refuse la demande reçue. |
| `/tpdeny` | Alias de `/tpadeny` (refuse la dernière demande). |

> La cible reçoit un message **cliquable** `[ACCEPTER] [REFUSER]`. La demande expire après un délai. La téléportation a un **délai (warmup)** avec animation de particules : ne bouge pas / ne prends pas de dégâts pendant le décompte.

### 🏠 Spawn
| Commande | Effet |
|---|---|
| `/spawn` | Te téléporte au spawn du serveur. |

### 🎁 Récompense quotidienne
| Commande | Effet |
|---|---|
| `/daily` | 🖱️ Ouvre le **calendrier** du mois. Clique le jour courant (coffre) pour récupérer. |
| `/daily claim` | Récupère directement la récompense du jour (sans menu). |
| `/daily status` | Affiche l'état (disponible / prochaine à minuit) et ta série (streak). |

> 1 récompense **par jour**. Récupérer chaque jour fait monter ta **série** ; un jour manqué la remet à zéro.

### 💰 Économie / banque
| Commande | Effet |
|---|---|
| `/balance [joueur]` | Affiche ton solde (ou celui d'un autre joueur). Alias : `/bal`. |
| `/balance menu` | 🖱️ **Menu banque** : payer un joueur, retirer (**montant à saisir**), ou déposer toutes ses pièces. |
| `/balance top` (ou `/baltop`) | Classement des plus gros soldes. |
| `/pay <joueur> <montant>` | Envoie des pièces de ton solde vers un autre joueur (montant ≥ 1). |
| `/withdraw <montant>` | Retire des pièces de la banque (limité à la **place dispo** dans ton inventaire ; le reste reste en banque). |
| `/deposit [montant]` | Dépose des pièces de l'inventaire en banque (tout si aucun montant). |

> Tu peux aussi **déposer en faisant clic droit** en tenant des pièces.

### 🏘️ Parcelles
| Commande | Effet |
|---|---|
| `/parcel` ou `/parcel menu` | 🖱️ Menu de la parcelle où tu te trouves. **Hors d'une parcelle**, ouvre tes parcelles (navigables ◄ ►). |
| `/parcel mine` | 🖱️ Ouvre **tes parcelles** depuis n'importe où ; flèches ◄ ► pour passer de l'une à l'autre, puis gérer les membres / vendre / déplacer l'hologramme. |
| `/parcel shop` | 🖱️ Liste des parcelles **en vente** (triées par ID). **Clic gauche** = acheter (confirmation), **clic droit** = voir les délimitations 30 s (particules). Le **vendeur** (Mairie ou joueur) et le prix sont affichés. |
| `/parcel info` | Infos sur la parcelle où tu te trouves (proprio, prix, tes droits). |
| `/parcel list` | Liste tes parcelles. |
| `/parcel buy` | Achète la parcelle où tu te trouves (paiement via `/balance`). |
| `/parcel sell <prix>` | Met **ta** parcelle en vente aux joueurs au prix donné (≥ 0). |
| `/parcel unsell` | Retire ta parcelle de la vente. |
| `/parcel transfer <joueur>` | Donne ta parcelle à un autre joueur. |
| `/parcel trust <joueur> <droits>` | Autorise un joueur. Droits : `build`, `containers`, `doors`, `machines`, `create`, ou `all`. |
| `/parcel untrust <joueur>` | Retire tous les droits d'un joueur. |
| `/parcel trustlist` | Liste les joueurs autorisés sur la parcelle. |

**Droits** (`trust`) : `build` (casser/poser) · `containers` (coffres…) · `doors` (portes/trappes/boutons/leviers/plaques) · `machines` (fours/enclumes/tables/redstone) · `create` (blocs du mod **Create**) · `all` (tout).
Exemple : `/parcel trust Steve build containers create`.

> 🔒 La protection bloque aussi les **poses de blocs non-joueur** (projectiles type *slingshot*, distributeurs, sable…), les **explosions**, le **feu**, et empêche les non-autorisés de **blesser/tuer les entités** (villageois, animaux, cadres…) — on peut donc **commercer avec un villageois sans pouvoir le tuer ni ouvrir son coffre**.
> 🚪 Les **portes, trappes, portillons, boutons, leviers et plaques** sont **publics** par défaut (tout le monde peut les utiliser), config `parcel.publicDoors`.

> 🖱️ **Le plus simple** : `/parcel` (sur ta parcelle) ou `/parcel mine` (de n'importe où) → menu : « Gérer les membres », « Vendre », TP, voir les délimitations. Avec plusieurs parcelles, navigue avec ◄ ►.
> **Vendre** : *à la Mairie* (immédiat, **75 % remboursé** de ce que tu as payé) ou *aux joueurs* (**tu fixes ton prix** : un **champ à remplir** s'ouvre, ou `/parcel sell <prix>` pour un montant exact). Une parcelle en vente affiche un **hologramme** `ID / À VENDRE / Vendeur / prix` (déplaçable à la boussole), et apparaît dans `/parcel shop` avec le **vendeur** (Mairie ou joueur).

`/parcel` a un alias : `/parcelle`.

---

## 🧑‍💼 Rôles désignés (aubergiste, maire)

> Ces commandes sont utilisables par les **op** **OU** par un joueur **désigné par un op** (via `/admin`). Un joueur non désigné ne les voit pas.

### 🛏️ Auberge (aubergiste)
| Commande | Effet |
|---|---|
| `/auberge` | 🖱️ Ouvre le **gestionnaire des chambres** (liste, attribuer, prix, durée, geler/libérer). Op + **aubergistes désignés**. |

> On peut aussi ouvrir ce gestionnaire en faisant **clic droit sur le bloc d'accès** de l'auberge (si un op en a défini un). Un aubergiste **ne peut pas supprimer** une chambre (réservé aux op). La désignation des aubergistes se fait dans `/admin → Aubergistes`.

### 🏛️ Mairie (maire)
| Commande | Effet |
|---|---|
| `/maire` | 🖱️ **Compte de la mairie** : **retirer** vers son solde, **déposer** depuis son solde, voir/**rendre les objets expirés** du marché. Op + **maire désigné**. |

> La Mairie est créditée de la **taxe du marché (25 %)**. Elle **n'apparaît pas** dans `/baltop`. Désignation du maire dans `/admin → Maire`.

---

## 🛒 Marché public

> Le marché fonctionne surtout au **clic droit** sur les **stands physiques** (pas de commande joueur). Les stands sont créés par un op.

| Action | Effet |
|---|---|
| **Clic droit** sur un stand **libre** | Le **réserve** (devient ton stand). |
| **Clic droit** sur **ton** stand | 🖱️ Gérer : **Ajouter (objet en main)** + prix, **Retirer** une offre, **Libérer** le stand. |
| **Clic droit** sur le stand **d'un autre** | 🖱️ **Acheter** une offre. |
| **Shift + clic droit** mains vides (op) | 🖱️ **Configuration du stand** : définir les emplacements d'affichage. |

> Jusqu'à **10 offres** par stand (types d'objets différents possibles). Chaque offre expire après **48 h** (objets non vendus → récupération de la mairie). À l'achat : **75 % vendeur / 25 % mairie**. Les objets en vente s'affichent en **hologramme** au-dessus du stand (ou sur des **emplacements définis** par un op ; défilement si plus d'offres que d'emplacements).

---

## 🛡️ Commandes administrateur (op niveau 2)

### 🧰 Hub d'administration
| Commande | Effet |
|---|---|
| `/admin` | 🖱️ Ouvre le **hub admin** : Parcelles, Économie, Récompenses, Auberge/chambres, Aubergistes, Marché (définir un stand, récupération), **Maire**, **Inventaires**, **Warps**. |

### 🧹 Nettoyage des objets au sol (clear-lag)
| Commande | Effet |
|---|---|
| `/clearlag` ou `/clearlag info` | État : actif, durée par défaut, intervalle, nombre d'objets au sol. |
| `/clearlag reload` | Recharge `config/utopia_admin/clearlag.json`. |
| `/clearlag now` | Supprime immédiatement tous les objets au sol non protégés. |

### 🎁 Daily — administration
| Commande | Effet |
|---|---|
| `/daily admin` | 🖱️ Menu admin : calendrier des récompenses, récompense par défaut, gestion des joueurs. |
| `/daily reset [joueur]` | Réinitialise réclamation, série et historique (soi-même ou un joueur). |

> Calendrier admin : flèches = changer de mois ; clic sur un jour (coffre = aujourd'hui, shulker = à venir) pour éditer sa récompense. Sauvegardé dans `config/utopia_admin/daily_calendar.json`.

### 💰 Économie — administration
| Commande | Effet |
|---|---|
| `/balance admin` (ou `/bal admin`) | 🖱️ Menu : liste des joueurs → donner / retirer **1, 10, 100, 1000** d'un clic. |
| `/money give <joueur> <montant>` | Crédite le solde d'un joueur (montant ≥ 1). |
| `/money take <joueur> <montant>` | Débite le solde d'un joueur (montant ≥ 1). |
| `/money set <joueur> <montant>` | Définit le solde d'un joueur (montant ≥ 0). |
| `/baltop holo here` | Place l'**hologramme du classement** (top 10 des soldes) à ta position. |
| `/baltop holo move` | 🖱️ Ouvre le **menu boussole** pour déplacer l'hologramme (flèches ↑↓←→ + Monter/Descendre, Placer ici, Supprimer). |
| `/baltop holo remove` | Retire l'hologramme du classement. |

> `/balance admin` couvre les joueurs **en ligne** ; pour un joueur **hors ligne**, utilise `/money …` (accepte les pseudos hors ligne).
> 🏆 **Hologramme BalTop** : affiche « ★ TOP 10 RICHESSES ★ » + les 10 plus riches au-dessus du sol (mise à jour auto ~2 s). Position persistante. Déplaçable à la boussole comme les hologrammes de parcelle.

### 🏗️ Parcelles — administration
| Commande | Effet |
|---|---|
| `/parcel admin` | 🖱️ Menu **global** : toutes les parcelles (triées par ID). **Clic gauche** = gérer, **clic droit** = se téléporter. Bouton **« Créer une parcelle Admin »** (saisie de l'ID). Les parcelles Admin sont marquées **`[ADMIN]`**. Dans le menu d'une parcelle : transférer, **changer le prix** (saisie), remettre en vente Mairie, **déplacer l'hologramme** (boussole ↑↓←→), **bascule Admin**, **supprimer** (confirmation). |
| `/parcel wand` | Reçois l'**outil de tracé**. |
| `/parcel trace clear` | Efface le tracé en cours. |
| `/parcel trace undo` | Annule le dernier point du tracé. |
| `/parcel create <id> [prix]` | Crée une parcelle à partir du tracé (≥ 3 points). Avec un prix = mise en vente directe (≥ 0). |
| `/parcel createadmin <id>` | Crée une **parcelle Admin** à partir du tracé (protégée, hors shop). |
| `/parcel setadmin <id> <true\|false>` | Passe une parcelle existante en **Admin** (ou la repasse normale). |
| `/parcel addregion <id>` | Ajoute le tracé courant comme région supplémentaire (forme composée). |
| `/parcel setprice <id> <prix>` | Définit le prix d'une parcelle (≥ 0). |
| `/parcel setsale <id> <true\|false>` | Met une parcelle en vente / la retire. |
| `/parcel setowner <id> <joueur>` | Force le propriétaire d'une parcelle. |
| `/parcel delete <id>` | Supprime une parcelle. |
| `/parcel tp <id>` | Te téléporte à une parcelle (admin uniquement). |

#### 🛡️ Parcelles Admin
Une **parcelle Admin** est une zone serveur **protégée** pour les routes, bâtiments, décorations, végétation… :
- **Anti-grief** : seuls les admins peuvent casser / poser / interagir (coffres, machines…). Les portes restent publiques si `parcel.publicDoors` est activé.
- **Hors shop** : jamais en vente, absente de `/parcel shop`.
- **Invisible aux joueurs** : `/parcel info` et le menu affichent « Zone protégée » aux non-admins ; pas d'hologramme ; sans propriétaire.
- Création/gestion : `/parcel createadmin <id>`, `/parcel setadmin <id> <bool>`, ou le bouton **bascule Admin** dans le menu de la parcelle.

#### ✏️ Tracer une parcelle
1. `/parcel wand` pour recevoir l'outil.
2. **Clic droit au sol** sur chaque coin du contour = ajoute un point. **Clic gauche** = annuler le dernier point.
3. Les points apparaissent en **rouge foncé**, les lignes en **rouge** (particules, visibles par toi).
4. Contour fermé (≥ 3 points) → `/parcel create <id> [prix]`.
5. Forme en plusieurs morceaux : retrace un contour puis `/parcel addregion <id>`.

#### ↩️ Annuler / corriger une sélection
| Moyen | Effet |
|---|---|
| **Clic gauche** avec l'outil | Annule le dernier point. |
| `/parcel trace undo` | Annule le dernier point. |
| `/parcel trace clear` | Efface tout le tracé. |

> Le tracé se vide aussi **automatiquement** après un `/parcel create` ou `/parcel addregion` réussi.

### 🛏️ Chambres d'auberge — administration
| Commande | Effet |
|---|---|
| `/room` ou `/room menu` | 🖱️ Gestionnaire des chambres (= `/auberge`). |
| `/room wand` | Reçois l'**outil chambre** : clic **gauche** = coin 1, clic **droit** = coin 2 (le **Y** compte). |
| `/room create <id>` | Crée une chambre depuis la sélection de l'outil. |
| `/room assign <id> <joueur> <prix/jour> <jours>` | Attribue la chambre (avance le coût ; crédite le solde de l'occupant). |
| `/room free <id>` | Libère la chambre. |
| `/room freeze <id>` / `/room unfreeze <id>` | Gèle / dégèle la chambre. |
| `/room setprice <id> <prix/jour>` | Change le prix par jour. |
| `/room setdays <id> <jours>` | Change la durée (en jours). |
| `/room delete <id>` | Supprime la chambre. |
| `/room list` | Liste les chambres. |

> **Configuration** (dans `/admin → Auberge / chambres`) : « Gérer les chambres », « Recevoir l'outil chambre », et « **Définir le bloc d'accès** » (active un mode : le prochain bloc **cassé** devient le bloc d'accès — clic droit dessus = ouvre le gestionnaire). Désignation des **aubergistes** : `/admin → Aubergistes`.

### 🛒 Marché public — administration
> Pas de commande dédiée : tout passe par `/admin` et les **clics** sur les stands.

- `/admin → Marché : définir un stand` : active un mode, puis **casse le bloc** qui deviendra le stand.
- **Casser** un stand existant le supprime (offres en cours → récupération).
- **Shift + clic droit** mains vides sur un stand → **Définir les emplacements d'affichage** (casse un bloc par emplacement ; recasse pour retirer ; re-Shift-clic droit pour terminer) ou **Effacer les emplacements**.
- `/admin → Récupération marché` : rendre aux joueurs les objets expirés.
- `/admin → Maire` : désigner qui accède à `/maire`.

### 🧰 Outils op divers
| Commande | Effet |
|---|---|
| `/flyspeed <1-10>` | Règle la **vitesse de vol** (1 = normale, 10 = max). |
| `/flyspeed reset` | Remet la vitesse de vol normale. |
| `/flyspeed` | Affiche la vitesse de vol actuelle. |
| `/inv 1` · `/inv 2` | Bascule entre **inventaire 1 et 2** (alias `/inventaire`). Sauvegarde l'inventaire courant et charge l'autre — pratique pour garder sa **survie** avant de passer en **créatif**. Aussi via `/admin → Inventaires`. |

### 🧭 Warps admin
| Commande | Effet |
|---|---|
| `/setwarp <nom>` | Crée / met à jour un warp à ta position. |
| `/warp <nom>` | **Téléportation instantanée** vers un warp (auto-complétion des noms). |
| `/delwarp <nom>` | Supprime un warp. |
| `/warps` (ou `/warp` seul) | Liste les warps. |

> Tous les warps sont **réservés aux op** et persistés (dimension + position + orientation). Aussi accessibles via `/admin → Warps` (liste cliquable, clic = TP).

---

## ⚙️ Configuration
- **TOML** : `config/utopia_admin-common.toml` (téléportation, tpa, spawn, daily, économie, parcelle).
- **JSON** : `config/utopia_admin/clearlag.json` (nettoyage) et `config/utopia_admin/daily_calendar.json` (calendrier).

Détails de configuration dans le [README principal](../README.md).
