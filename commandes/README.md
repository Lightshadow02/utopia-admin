# 📖 Guide des commandes — utopia-admin

Toutes les commandes du mod, classées par **joueur** et **administrateur**.

> **Permissions** : les commandes admin demandent le **niveau d'opérateur 2** (op). Un joueur op (ou la console) y a accès ; un joueur normal non.
> `<...>` = argument obligatoire · `[...]` = argument optionnel · `a|b` = au choix.

---

## 👤 Commandes joueur

### 🧭 Téléportation
| Commande | Effet |
|---|---|
| `/tpa <joueur>` | Demande à **te téléporter vers** ce joueur. |
| `/tpahere <joueur>` | Demande que ce joueur **se téléporte vers toi**. |
| `/tpaccept [joueur]` | Accepte la dernière demande reçue (ou celle d'un joueur précis). |
| `/tpadeny [joueur]` | Refuse la demande reçue. Alias : `/tpdeny`. |
| `/spawn` | Te téléporte au spawn du serveur. |

> La cible reçoit un message **cliquable** `[ACCEPTER] [REFUSER]`. Une demande expire après un délai. La téléportation a un court **délai (warmup)** avec animation : ne bouge pas et ne prends pas de dégâts pendant le décompte.

### 🎁 Récompense quotidienne
| Commande | Effet |
|---|---|
| `/daily` | Ouvre le **calendrier** du mois. Clique le jour du jour (coffre) pour récupérer. |
| `/daily claim` | Récupère directement la récompense du jour (sans menu). |
| `/daily status` | Affiche l'état (disponible / prochaine à minuit) et ta série (streak). |

> 1 récompense **par jour**. Se connecter et récupérer chaque jour fait monter ta **série** ; un jour manqué la remet à zéro.

### 💰 Économie / banque
| Commande | Effet |
|---|---|
| `/balance [joueur]` | Affiche ton solde (ou celui d'un autre joueur). Alias : `/bal`. |
| `/pay <joueur> <montant>` | Envoie des pièces de ton solde vers un autre joueur. |
| `/withdraw <montant>` | Retire des pièces de la banque vers ton inventaire (pièces physiques). |
| `/deposit [montant]` | Dépose des pièces de ton inventaire en banque (tout si aucun montant). |

> Tu peux aussi **déposer en faisant clic droit** en tenant des pièces.

### 🏠 Parcelles
| Commande | Effet |
|---|---|
| `/parcel info` | Infos sur la parcelle où tu te trouves (proprio, prix, tes droits). |
| `/parcel list` | Liste tes parcelles. |
| `/parcel buy` | Achète la parcelle où tu te trouves (paiement via ton solde `/balance`). |
| `/parcel sell <prix>` | Met **ta** parcelle (sous tes pieds) en vente. |
| `/parcel unsell` | Retire ta parcelle de la vente. |
| `/parcel transfer <joueur>` | Donne ta parcelle à un autre joueur. |
| `/parcel trust <joueur> <droits>` | Autorise un joueur sur ta parcelle. |
| `/parcel untrust <joueur>` | Retire les droits d'un joueur. |
| `/parcel trustlist` | Liste les joueurs autorisés sur la parcelle. |

**Droits possibles** (pour `trust`) : `build`, `containers`, `doors`, `machines`, ou `all`.
- `build` = casser/poser des blocs
- `containers` = coffres, barils, hoppers, shulkers…
- `doors` = portes, trappes, portillons, boutons, leviers, plaques
- `machines` = fours, enclumes, tables, redstone…

Exemple : `/parcel trust Steve build containers` (Steve peut construire et ouvrir les coffres).

---

## 🛡️ Commandes administrateur (op niveau 2)

### 🧹 Nettoyage des objets au sol (clear-lag)
| Commande | Effet |
|---|---|
| `/clearlag` ou `/clearlag info` | Affiche l'état, la durée par défaut, l'intervalle et le nombre d'objets au sol. |
| `/clearlag reload` | Recharge `config/utopia_admin/clearlag.json`. |
| `/clearlag now` | Supprime immédiatement tous les objets au sol non protégés. |

### 🎁 Daily — administration
| Commande | Effet |
|---|---|
| `/daily admin` | Ouvre le **menu d'administration** : calendrier des récompenses, récompense par défaut, gestion des joueurs. |
| `/daily reset [joueur]` | Réinitialise réclamation, série et historique (soi-même ou un joueur). |

> Dans le **calendrier admin** : flèches = changer de mois, clic sur un jour (coffre = aujourd'hui, shulker = à venir) pour éditer sa récompense. Sauvegardé dans `config/utopia_admin/daily_calendar.json`.

### 💰 Économie — administration
| Commande | Effet |
|---|---|
| `/money give <joueur> <montant>` | Crédite le solde d'un joueur. |
| `/money take <joueur> <montant>` | Débite le solde d'un joueur. |
| `/money set <joueur> <montant>` | Définit le solde d'un joueur. |

> Accepte les joueurs **hors ligne** (par pseudo).

### 🏠 Spawn
| Commande | Effet |
|---|---|
| `/setspawn` | Définit le spawn du serveur à ta position actuelle. |

### 🏗️ Parcelles — création & gestion
| Commande | Effet |
|---|---|
| `/parcel wand` | Reçois l'**outil de tracé**. |
| `/parcel trace clear` | Efface le tracé en cours. |
| `/parcel trace undo` | Annule le dernier point du tracé. |
| `/parcel create <id> [prix]` | Crée une parcelle à partir du tracé (≥ 3 points). Avec un prix = mise en vente directe. |
| `/parcel addregion <id>` | Ajoute le tracé courant comme région supplémentaire (forme composée). |
| `/parcel setprice <id> <prix>` | Définit le prix d'une parcelle. |
| `/parcel setsale <id> <true\|false>` | Met une parcelle en vente / la retire. |
| `/parcel setowner <id> <joueur>` | Force le propriétaire d'une parcelle. |
| `/parcel delete <id>` | Supprime une parcelle. |
| `/parcel tp <id>` | Te téléporte à une parcelle. |

#### ✏️ Comment tracer une parcelle
1. `/parcel wand` pour recevoir l'outil.
2. **Clic droit au sol** sur chaque coin du contour → ajoute un point. **Clic gauche** = annuler le dernier point.
3. Les points apparaissent en **rouge foncé** et les lignes en **rouge** (particules, visibles par toi seul).
4. Une fois le contour fermé (≥ 3 points), `/parcel create <nom> [prix]`.
5. Pour une forme en plusieurs morceaux : retrace un contour puis `/parcel addregion <nom>`.

> `/parcel` a un alias : `/parcelle`.

---

## ⚙️ Configuration

- **TOML** : `config/utopia_admin-common.toml` (téléportation, tpa, spawn, daily, économie, parcelle).
- **JSON** : `config/utopia_admin/clearlag.json` (nettoyage) et `config/utopia_admin/daily_calendar.json` (calendrier).

Voir le [README principal](../README.md) pour le détail de la configuration.
