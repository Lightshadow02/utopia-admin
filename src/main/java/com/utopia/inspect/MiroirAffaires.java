package com.utopia.inspect;

import java.util.UUID;

import com.utopia.UtopiaMod;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Une vue sur les affaires de quelqu'un d'autre, et non une copie.
 *
 * <p>La difference n'est pas un detail. Avec une copie et un bouton "Enregistrer", sortir un objet
 * de la fenetre puis fermer sans enregistrer le laisserait dans les deux inventaires a la fois :
 * une duplication a la portee de n'importe quel operateur distrait. Ici chaque deplacement porte
 * immediatement sur l'inventaire vise, comme si on rangeait un coffre.
 *
 * <p>La cible peut se connecter ou se deconnecter pendant que la fenetre est ouverte. La verite est
 * donc retrouvee a chaque acces : le joueur en memoire s'il est la, son fichier de sauvegarde
 * sinon. Quand il arrive, le cache du fichier est jete - il vient d'etre relu par le serveur et ce
 * qu'on en savait ne vaut plus rien.
 */
public final class MiroirAffaires extends SimpleContainer {

    private final MinecraftServer server;
    private final UUID cible;
    /** Case de la fenetre vers case reelle ; {@code -1} pour une case de decor. */
    private final int[] plan;
    private final boolean coffreDeLEnd;

    /** Les affaires relues du fichier, tant que la cible est absente. */
    private NonNullList<ItemStack> horsLigne;
    /** Faux quand la sauvegarde n'a pas pu etre lue : on n'ecrira alors rien par-dessus. */
    private boolean lisible = true;

    public MiroirAffaires(MinecraftServer server, UUID cible, int[] plan, boolean coffreDeLEnd) {
        super(plan.length);
        this.server = server;
        this.cible = cible;
        this.plan = plan;
        this.coffreDeLEnd = coffreDeLEnd;
    }

    /** Vrai si les affaires ont pu etre atteintes : sinon la fenetre ne doit pas s'ouvrir. */
    public boolean pret() {
        if (present() != null) {
            return true;
        }
        cache();
        return lisible;
    }

    public boolean horsLigne() {
        return present() == null;
    }

    private ServerPlayer present() {
        return server.getPlayerList().getPlayer(cible);
    }

    private NonNullList<ItemStack> cache() {
        if (horsLigne != null) {
            return horsLigne;
        }
        CompoundTag tag = OfflinePlayerData.lire(server, cible);
        if (tag == null) {
            lisible = false;
            horsLigne = NonNullList.withSize(taille(), ItemStack.EMPTY);
            return horsLigne;
        }
        horsLigne = coffreDeLEnd
                ? OfflinePlayerData.enderchest(server, tag)
                : OfflinePlayerData.inventaire(server, tag);
        return horsLigne;
    }

    private int taille() {
        return coffreDeLEnd ? OfflinePlayerData.TAILLE_ENDER : OfflinePlayerData.TAILLE_INVENTAIRE;
    }

    private ItemStack lire(int place) {
        ServerPlayer joueur = present();
        if (joueur != null) {
            horsLigne = null; // il est revenu : ce qu'on avait lu du fichier est perime
            return coffreDeLEnd
                    ? joueur.getEnderChestInventory().getItem(place)
                    : joueur.getInventory().getItem(place);
        }
        return cache().get(place);
    }

    private void ecrire(int place, ItemStack stack) {
        ItemStack borne = stack.copy();
        borne.limitSize(getMaxStackSize(borne));
        ServerPlayer joueur = present();
        if (joueur != null) {
            horsLigne = null;
            if (coffreDeLEnd) {
                joueur.getEnderChestInventory().setItem(place, borne);
            } else {
                joueur.getInventory().setItem(place, borne);
                joueur.inventoryMenu.broadcastChanges();
            }
            return;
        }
        if (!lisible) {
            return; // sauvegarde illisible : ne rien ecrire vaut mieux que d'ecrire du vide
        }
        cache().set(place, borne);
        verser();
    }

    /**
     * Reporte les affaires dans le fichier. Il est relu juste avant : on ne remplace que la liste
     * des objets, tout le reste - position, vie, experience, avancement - reste ce qu'il etait.
     */
    private void verser() {
        CompoundTag tag = OfflinePlayerData.lire(server, cible);
        if (tag == null) {
            lisible = false;
            UtopiaMod.LOGGER.warn("[Utopia] Sauvegarde de {} disparue en cours de modification.", cible);
            return;
        }
        if (coffreDeLEnd) {
            OfflinePlayerData.poserEnderchest(server, tag, horsLigne);
        } else {
            OfflinePlayerData.poserInventaire(server, tag, horsLigne);
        }
        OfflinePlayerData.ecrire(server, cible, tag);
    }

    // ------------------------------------------------------------------ Le conteneur

    @Override
    public ItemStack getItem(int index) {
        if (index < 0 || index >= plan.length || plan[index] < 0) {
            return super.getItem(index);
        }
        return lire(plan[index]);
    }

    @Override
    public void setItem(int index, ItemStack stack) {
        if (index < 0 || index >= plan.length || plan[index] < 0) {
            super.setItem(index, stack);
            return;
        }
        ecrire(plan[index], stack);
    }

    @Override
    public ItemStack removeItem(int index, int count) {
        if (index < 0 || index >= plan.length || plan[index] < 0) {
            return super.removeItem(index, count);
        }
        ItemStack present = lire(plan[index]);
        if (present.isEmpty() || count <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack pris = present.copy();
        ItemStack retire = pris.split(count);
        ecrire(plan[index], pris);
        return retire;
    }

    @Override
    public ItemStack removeItemNoUpdate(int index) {
        if (index < 0 || index >= plan.length || plan[index] < 0) {
            return super.removeItemNoUpdate(index);
        }
        ItemStack present = lire(plan[index]).copy();
        ecrire(plan[index], ItemStack.EMPTY);
        return present;
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < plan.length; i++) {
            if (!getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < plan.length; i++) {
            if (plan[i] >= 0) {
                ecrire(plan[i], ItemStack.EMPTY);
            }
        }
        super.clearContent();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
