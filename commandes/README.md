# 📖 Guide des commandes — utopia-admin

Liste **complète et à jour** de toutes les commandes du mod, classées par **joueur** et **administrateur**.

> **Permissions** : les commandes admin demandent le **niveau d'opérateur 2** (op). Un joueur op (ou la console) y a accès ; un joueur normal non.
> Notation : `<...>` = obligatoire · `[...]` = optionnel · `a|b` = au choix · 🖱️ = ouvre un menu.

---

## 👤 Commandes joueur

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
| `/pay <joueur> <montant>` | Envoie des pièces de ton solde vers un autre joueur (montant ≥ 1). |
| `/withdraw <montant>` | Retire des pièces de la banque vers ton inventaire (montant ≥ 1). |
| `/deposit [montant]` | Dépose des pièces de l'inventaire en banque (tout si aucun montant). |

> Tu peux aussi **déposer en faisant clic droit** en tenant des pièces.

### 🏘️ Parcelles
| Commande | Effet |
|---|---|
| `/parcel` ou `/parcel menu` | 🖱️ Menu de la parcelle où tu te trouves. **Hors d'une parcelle**, ouvre tes parcelles (navigables ◄ ►). |
| `/parcel mine` | 🖱️ Ouvre **tes parcelles** depuis n'importe où ; flèches ◄ ► pour passer de l'une à l'autre, puis gérer / vendre / TP. |
| `/parcel shop` | 🖱️ Liste des parcelles **en vente** (triées par ID). **Clic gauche** = acheter (confirmation), **clic droit** = voir les délimitations 30 s (particules). Le **vendeur** (Serveur ou joueur) et le prix sont affichés. |
| `/parcel info` | Infos sur la parcelle où tu te trouves (proprio, prix, tes droits). |
| `/parcel list` | Liste tes parcelles. |
| `/parcel buy` | Achète la parcelle où tu te trouves (paiement via `/balance`). |
| `/parcel sell <prix>` | Met **ta** parcelle en vente aux joueurs au prix donné (≥ 0). |
| `/parcel unsell` | Retire ta parcelle de la vente. |
| `/parcel transfer <joueur>` | Donne ta parcelle à un autre joueur. |
| `/parcel trust <joueur> <droits>` | Autorise un joueur. Droits : `build`, `containers`, `doors`, `machines`, ou `all`. |
| `/parcel untrust <joueur>` | Retire tous les droits d'un joueur. |
| `/parcel trustlist` | Liste les joueurs autorisés sur la parcelle. |

**Droits** (`trust`) : `build` (casser/poser) · `containers` (coffres…) · `doors` (portes/trappes/boutons/leviers/plaques) · `machines` (fours/enclumes/tables/redstone) · `create` (blocs du mod **Create**) · `all` (tout).
Exemple : `/parcel trust Steve build containers create`.

> 🔒 La protection bloque aussi les **poses de blocs non-joueur** dans une parcelle (projectiles type *slingshot*, distributeurs, sable qui tombe…), pour éviter les contournements.

> 🖱️ **Le plus simple** : `/parcel` (sur ta parcelle) ou `/parcel mine` (de n'importe où) → menu : « Gérer les membres », « Vendre », TP, voir les délimitations. Avec plusieurs parcelles, navigue avec ◄ ►.
> **Vendre** : *au serveur* (immédiat, **75 % remboursé** de ce que tu as payé) ou *aux joueurs* (**tu fixes ton prix** : boutons ±1/±10/±100/±1000, ou `/parcel sell <prix>` pour un montant exact). Une parcelle en vente affiche un **hologramme** `ID / À VENDRE / Vendeur / prix`, et apparaît dans `/parcel shop` avec le **vendeur** (Serveur ou joueur).

`/parcel` a un alias : `/parcelle`.

---

## 🛡️ Commandes administrateur (op niveau 2)

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

> `/balance admin` couvre les joueurs **en ligne** ; pour un joueur **hors ligne**, utilise `/money …` (accepte les pseudos hors ligne).

### 🏗️ Parcelles — administration
| Commande | Effet |
|---|---|
| `/parcel admin` | 🖱️ Menu **global** : toutes les parcelles (triées par ID). **Clic gauche** = gérer (propriétaire, transférer, remettre en vente serveur, déplacer l'hologramme X/Y/Z, **supprimer** avec confirmation), **clic droit** = se téléporter. |
| `/parcel wand` | Reçois l'**outil de tracé**. |
| `/parcel trace clear` | Efface le tracé en cours. |
| `/parcel trace undo` | Annule le dernier point du tracé. |
| `/parcel create <id> [prix]` | Crée une parcelle à partir du tracé (≥ 3 points). Avec un prix = mise en vente directe (≥ 0). |
| `/parcel addregion <id>` | Ajoute le tracé courant comme région supplémentaire (forme composée). |
| `/parcel setprice <id> <prix>` | Définit le prix d'une parcelle (≥ 0). |
| `/parcel setsale <id> <true\|false>` | Met une parcelle en vente / la retire. |
| `/parcel setowner <id> <joueur>` | Force le propriétaire d'une parcelle. |
| `/parcel delete <id>` | Supprime une parcelle. |
| `/parcel tp <id>` | Te téléporte à une parcelle. |

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

---

## ⚙️ Configuration
- **TOML** : `config/utopia_admin-common.toml` (téléportation, tpa, spawn, daily, économie, parcelle).
- **JSON** : `config/utopia_admin/clearlag.json` (nettoyage) et `config/utopia_admin/daily_calendar.json` (calendrier).

Détails de configuration dans le [README principal](../README.md).
