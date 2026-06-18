# 💰 API Économie — utopia-admin

API publique et **stable** pour intégrer l'économie d'`utopia-admin` depuis **KubeJS** (ou un autre mod).

- **Classe** : `com.utopia.api.UtopiaEconomyAPI`
- **Tout est statique** — pas d'instance à créer.
- **Montants = entiers** (`long`). Le « solde » est le **compte bancaire** du joueur (≠ pièces physiques dans l'inventaire).
- **Mairie** : compte virtuel partagé (taxe du marché, parcelles serveur…). Accessible aussi par l'API.

> ⚙️ Nécessite `utopia-admin` **v1.64.0+** côté serveur. La signature de cette classe est un contrat stable.

---

## 🚀 Démarrage rapide (KubeJS)

Dans un **server script** (`kubejs/server_scripts/…`) :

```js
// Charge l'API une fois en haut du fichier
const Economy = Java.loadClass('com.utopia.api.UtopiaEconomyAPI')

// Exemple : à la connexion, afficher le solde
PlayerEvents.loggedIn(event => {
  const bal = Economy.getBalance(event.player)
  event.player.tell('Ton solde : ' + Economy.format(bal))
})
```

> ✅ Le plus simple est d'utiliser les méthodes qui prennent **le joueur** (`event.player` est un `ServerPlayer`). Pas besoin de passer le serveur.

---

## 📋 Référence des méthodes

### Soldes bancaires

| Méthode | Retour | Description |
|---|---|---|
| `getBalance(player)` | `long` | Solde du joueur. |
| `getBalance(server, uuid)` | `long` | Solde par UUID (marche **hors ligne**). |
| `getBalance(server, "Pseudo")` | `long` | Solde par pseudo (cache de profils ; `0` si inconnu). |
| `setBalance(player, montant)` | — | Fixe le solde (valeur absolue). |
| `setBalance(server, uuid, montant)` | — | Idem par UUID. |
| `add(player, montant)` | — | Ajoute (négatif = retire ; peut passer sous 0). |
| `add(server, uuid, montant)` | — | Idem par UUID. |
| `remove(player, montant)` | `boolean` | Retire si possible. **`false`** si solde insuffisant (rien retiré). |
| `remove(server, uuid, montant)` | `boolean` | Idem par UUID. |
| `has(player, montant)` | `boolean` | Vrai si solde ≥ montant. |
| `has(server, uuid, montant)` | `boolean` | Idem par UUID. |
| `transfer(server, from, to, montant)` | `boolean` | Transfert entre deux UUID. `false` si source insuffisante. |
| `pay(from, to, montant)` | `boolean` | Paiement joueur → joueur. `false` si insuffisant. |

### Pièces physiques (Utopièces dans l'inventaire)

| Méthode | Retour | Description |
|---|---|---|
| `giveCoins(player, nombre)` | — | Donne des pièces physiques (surplus au sol si plein). |
| `countCoins(player)` | `int` | Compte les pièces physiques de l'inventaire. |
| `takeCoins(player, nombre)` | `int` | Retire jusqu'à `nombre` pièces ; renvoie le **nombre réellement retiré**. |
| `payCombined(player, montant)` | `boolean` | Paie en prenant d'abord les pièces physiques, puis le solde. `false` si total insuffisant. |

### Compte de la mairie

| Méthode | Retour | Description |
|---|---|---|
| `mairieId()` | `UUID` | UUID du compte de la mairie. |
| `getMairieBalance(server)` | `long` | Solde de la mairie. |
| `addToMairie(server, montant)` | — | Crédite (ou débite si négatif) la mairie. |

### Utilitaires

| Méthode | Retour | Description |
|---|---|---|
| `format(montant)` | `String` | Ex. `"1234 Utopieces"`. |
| `currencyName()` | `String` | Nom de la monnaie configurée. |
| `resolvePlayer(server, "Pseudo")` | `UUID` | UUID d'un pseudo (en ligne, sinon cache de profils). `null` si introuvable. |

---

## 🧩 Exemples KubeJS

### 1) Récompenser à une action (ex. casser un bloc précis)

```js
const Economy = Java.loadClass('com.utopia.api.UtopiaEconomyAPI')

BlockEvents.broken('minecraft:diamond_ore', event => {
  Economy.add(event.player, 50)
  event.player.tell('+' + Economy.format(50) + ' pour ce minerai !')
})
```

### 2) Faire payer un service (et refuser si pas assez)

```js
const Economy = Java.loadClass('com.utopia.api.UtopiaEconomyAPI')
const COUT = 100

ItemEvents.rightClicked('minecraft:nether_star', event => {
  const player = event.player
  if (!Economy.remove(player, COUT)) {
    player.tell('§cIl te faut ' + Economy.format(COUT) + '.')
    return
  }
  player.tell('§aService acheté ! (-' + Economy.format(COUT) + ')')
  // ... applique l'effet ici ...
})
```

### 3) Commande personnalisée qui lit un solde (par pseudo)

```js
const Economy = Java.loadClass('com.utopia.api.UtopiaEconomyAPI')

ServerEvents.commandRegistry(event => {
  const { commands: Commands, arguments: Arguments } = event
  event.register(
    Commands.literal('soldede')
      .then(Commands.argument('cible', Arguments.STRING.create(event))
        .executes(ctx => {
          const server = ctx.source.server
          const name = Arguments.STRING.getResult(ctx, 'cible')
          const bal = Economy.getBalance(server, name)
          ctx.source.player.tell(name + ' : ' + Economy.format(bal))
          return 1
        }))
  )
})
```

### 4) Verser une taxe à la mairie

```js
const Economy = Java.loadClass('com.utopia.api.UtopiaEconomyAPI')

// Prélève 10 au joueur et les verse à la mairie
function taxer(player, montant) {
  if (Economy.remove(player, montant)) {
    Economy.addToMairie(player.server, montant)
    return true
  }
  return false
}
```

### 5) Récompense quotidienne hors-ligne (par UUID)

```js
const Economy = Java.loadClass('com.utopia.api.UtopiaEconomyAPI')

// quelque part avec un server et un uuid (string ou java.util.UUID)
function crediterHorsLigne(server, uuid, montant) {
  Economy.add(server, uuid, montant)
}
```

---

## ⚠️ Notes & bonnes pratiques

- **Solde bancaire ≠ pièces physiques.** `getBalance/add/remove` agissent sur le **compte bancaire**. Pour donner/retirer des pièces **dans l'inventaire**, utilise `giveCoins/takeCoins`. `payCombined` combine les deux pour un paiement.
- **Montants entiers** uniquement (`long`). Pas de décimales. Vérifie que tu passes des entiers (`Math.floor(...)` au besoin).
- **`remove` est sûr** : il ne retire rien et renvoie `false` si le solde est insuffisant — teste toujours son retour avant d'appliquer un effet.
- **`add` peut rendre négatif** (utile pour les comptes serveur/mairie). Pour un joueur, préfère `remove` qui protège.
- **Hors ligne** : utilise les variantes `(...server, uuid...)` ou `getBalance(server, "Pseudo")`. La résolution par pseudo dépend du **cache de profils** (joueur déjà vu sur le serveur).
- **Récupérer le serveur natif** dans KubeJS : `event.server` (ou `ctx.source.server` dans une commande), `player.server` depuis un joueur.
- **UUID** : tu peux passer un `java.util.UUID`. Pour partir d'un pseudo, utilise `resolvePlayer(server, "Pseudo")`.

---

## 🔌 Depuis un autre mod (Java)

Ajoute `utopia-admin` en dépendance (compile-only suffit) et appelle :

```java
import com.utopia.api.UtopiaEconomyAPI;

long solde = UtopiaEconomyAPI.getBalance(serverPlayer);
boolean ok = UtopiaEconomyAPI.remove(serverPlayer, 100);
UtopiaEconomyAPI.addToMairie(server, 25);
```

---

*utopia-admin — https://github.com/Lightshadow02/utopia-admin*
