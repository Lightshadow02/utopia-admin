# 🔀 API Proxy (Velocity / BungeeCord) — utopia-admin

API publique et **stable** pour **déplacer un joueur vers un autre serveur** d'un réseau **Velocity** (ou BungeeCord) depuis le backend, déclenchable depuis **KubeJS**.

- **Classe** : `com.utopia.api.UtopiaProxyAPI`
- **Tout est statique** — pas d'instance.
- Fonctionne via le canal de messages plugin **`bungeecord:main`** (sous-canal `Connect`), que le proxy intercepte (le client ne voit jamais le message).

> ⚙️ Nécessite `utopia-admin` **v1.76.0+** côté serveur.

---

## ✅ Pré-requis réseau

1. Le serveur tourne **derrière un proxy Velocity** (ou BungeeCord/Waterfall).
2. Le **serveur cible** est déclaré dans la config du proxy (`velocity.toml` → `[servers]`).
3. Velocity gère nativement le sous-canal `Connect` du canal `bungeecord:main` → **aucune config supplémentaire** dans la plupart des cas. (Sur BungeeCord, c'est natif aussi.)
4. Le mod `utopia-admin` est présent (il enregistre le canal). Le mod est requis **client + serveur**.

> Le **nom du serveur** passé à l'API doit correspondre **exactement** au nom déclaré dans le proxy (ex. `lobby`, `survie`, `creatif`).

---

## 📋 Méthodes

| Méthode | Description |
|---|---|
| `connectToServer(player, "serveur")` | Déplace **ce joueur** vers le serveur backend nommé. |
| `connectToServer(server, "Pseudo", "serveur")` | Déplace le joueur **en ligne** nommé. Renvoie `false` s'il est absent. |
| `sendBungee(player, "SousCanal", ...args)` | Envoi générique d'un message BungeeCord/Velocity (avancé). |

---

## 🚀 Démarrage rapide (KubeJS)

Dans un **server script** (`kubejs/server_scripts/…`) :

```js
const Proxy = Java.loadClass('com.utopia.api.UtopiaProxyAPI')

// Exemple : un item qui renvoie au lobby
ItemEvents.rightClicked('minecraft:compass', event => {
  Proxy.connectToServer(event.player, 'lobby')
})
```

> ✅ `event.player` est un `ServerPlayer` → on peut le passer directement.

---

## 🧩 Exemples

### 1) Commande personnalisée `/lobby`

```js
const Proxy = Java.loadClass('com.utopia.api.UtopiaProxyAPI')

ServerEvents.commandRegistry(event => {
  const { commands: Commands } = event
  event.register(
    Commands.literal('lobby').executes(ctx => {
      Proxy.connectToServer(ctx.source.player, 'lobby')
      return 1
    })
  )
})
```

### 2) Portail / zone : entrer dans une région téléporte vers un autre serveur

```js
const Proxy = Java.loadClass('com.utopia.api.UtopiaProxyAPI')

PlayerEvents.tick(event => {
  const p = event.player
  // exemple : si le joueur passe sous Y=0 dans une zone donnée
  if (p.y < 0 && p.x > 100 && p.x < 120 && p.z > 100 && p.z < 120) {
    Proxy.connectToServer(p, 'nether_world')
  }
})
```

### 3) Déplacer un joueur par pseudo (depuis un autre contexte)

```js
const Proxy = Java.loadClass('com.utopia.api.UtopiaProxyAPI')

function envoyer(server, pseudo, cible) {
  if (!Proxy.connectToServer(server, pseudo, cible)) {
    console.warn(pseudo + ' introuvable')
  }
}
```

### 4) Combiner avec l'économie (payer pour voyager)

```js
const Proxy = Java.loadClass('com.utopia.api.UtopiaProxyAPI')
const Economy = Java.loadClass('com.utopia.api.UtopiaEconomyAPI')

ItemEvents.rightClicked('minecraft:ender_pearl', event => {
  const p = event.player
  if (Economy.remove(p, 100)) {
    p.tell('§aVoyage payé. Téléportation...')
    Proxy.connectToServer(p, 'donjon')
  } else {
    p.tell('§cIl te faut 100 Utopieces.')
  }
})
```

---

## ⚠️ Notes

- Le déplacement est **asynchrone** côté proxy : le joueur est transféré juste après l'appel.
- Si le serveur cible est **hors ligne** ou **inconnu du proxy**, le proxy refuse (souvent un message côté joueur) — rien à gérer côté mod.
- L'API ne fait **rien d'utile sans proxy** (en solo / serveur seul, le message est simplement ignoré).
- Le canal `bungeecord:main` ajoute **un canal réseau** au mod — c'est volontaire et minimal (un seul canal, multi sous-commandes).

---

## 🔌 Depuis un autre mod (Java)

```java
import com.utopia.api.UtopiaProxyAPI;

UtopiaProxyAPI.connectToServer(serverPlayer, "lobby");
```

---

*utopia-admin — https://github.com/Lightshadow02/utopia-admin*
