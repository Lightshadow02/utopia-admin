package com.utopia.power;

import java.util.UUID;

import com.utopia.data.PowerData;
import com.utopia.util.Messages;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Le lien entre le droit et l'objet qui le materialise.
 *
 * <p>Le droit est la seule verite : il vit dans {@link PowerData}. L'objet n'est qu'une poignee,
 * remise quand la capacite est accordee et reprise quand elle est retiree.
 *
 * <p>L'objet n'est <b>jamais</b> rendu parce qu'il manque. Un objet qu'on retrouve des qu'on ne
 * l'a plus se duplique a volonte : il suffit de le poser dans un coffre et de se reconnecter. Il
 * n'est remis que sur une dette notee - accorder la capacite a quelqu'un d'absent, ou lui reprendre
 * son baton a la mort - et sur demande expresse de /dieux.
 */
public final class PowerManager {

    private PowerManager() {
    }

    /** L'objet qui porte cette capacite, neuf. */
    private static ItemStack objetDe(Power pouvoir) {
        return switch (pouvoir) {
            case BATON_DE_MAGE -> MageStaff.fabriquer();
        };
    }

    private static boolean estLobjetDe(Power pouvoir, ItemStack stack) {
        return switch (pouvoir) {
            case BATON_DE_MAGE -> MageStaff.estUnBaton(stack);
        };
    }

    // ------------------------------------------------------------------ Donner et reprendre

    /**
     * Repercute sur la personne ce que /dieux vient de decider. Absente, elle recevra son objet a sa
     * prochaine connexion : la dette est notee, et notee une seule fois.
     */
    public static void appliquer(MinecraftServer server, UUID id, Power pouvoir, boolean accorde) {
        ServerPlayer cible = server.getPlayerList().getPlayer(id);
        if (cible == null) {
            if (accorde) {
                PowerData.get(server).noterARemettre(id, pouvoir);
            }
            return;
        }
        if (accorde) {
            donner(cible, pouvoir);
            cible.sendSystemMessage(Messages.success("Tu recois une capacite : " + pouvoir.label
                    + ". " + pouvoir.detail + "."));
        } else {
            retirerObjets(cible, pouvoir);
            cible.sendSystemMessage(Messages.warn("Tu perds une capacite : " + pouvoir.label + "."));
        }
    }

    /** Reprend toutes les capacites d'une personne, objets compris. */
    public static void toutRetirer(MinecraftServer server, UUID id) {
        PowerData donnees = PowerData.get(server);
        // La copie protege du cas ou appliquer viendrait un jour a toucher au registre : aujourd'hui
        // il ne fait que reprendre l'objet, c'est le toutRetirer d'apres la boucle qui efface.
        for (Power pouvoir : new java.util.ArrayList<>(donnees.de(id))) {
            appliquer(server, id, pouvoir, false);
        }
        donnees.toutRetirer(id);
    }

    /** Remet l'objet manquant, sauf si la personne l'a deja sur elle. */
    private static boolean donner(ServerPlayer cible, Power pouvoir) {
        PowerData.get(cible.server).oublierARemettre(cible.getUUID(), pouvoir);
        if (aDejaLobjet(cible, pouvoir)) {
            return false;
        }
        ItemHandlerHelper.giveItemToPlayer(cible, objetDe(pouvoir));
        return true;
    }

    private static boolean aDejaLobjet(ServerPlayer cible, Power pouvoir) {
        var inv = cible.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (estLobjetDe(pouvoir, inv.getItem(i))) {
                return true;
            }
        }
        // La grille d'artisanat et l'objet tenu au curseur ne font pas partie de l'inventaire : un
        // baton pose la passerait pour perdu, et on en remettrait un second.
        var grille = cible.inventoryMenu.getCraftSlots();
        for (int i = 0; i < grille.getContainerSize(); i++) {
            if (estLobjetDe(pouvoir, grille.getItem(i))) {
                return true;
            }
        }
        return estLobjetDe(pouvoir, cible.containerMenu.getCarried());
    }

    /** Fait disparaitre l'objet des mains de quelqu'un a qui on vient de retirer le droit. */
    private static void retirerObjets(ServerPlayer cible, Power pouvoir) {
        PowerData.get(cible.server).oublierARemettre(cible.getUUID(), pouvoir);
        var inv = cible.getInventory();
        boolean touche = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (estLobjetDe(pouvoir, inv.getItem(i))) {
                inv.setItem(i, ItemStack.EMPTY);
                touche = true;
            }
        }
        var grille = cible.inventoryMenu.getCraftSlots();
        for (int i = 0; i < grille.getContainerSize(); i++) {
            if (estLobjetDe(pouvoir, grille.getItem(i))) {
                grille.setItem(i, ItemStack.EMPTY);
                touche = true;
            }
        }
        if (estLobjetDe(pouvoir, cible.containerMenu.getCarried())) {
            cible.containerMenu.setCarried(ItemStack.EMPTY);
            touche = true;
        }
        if (touche) {
            inv.setChanged();
            cible.containerMenu.broadcastChanges();
        }
    }

    /**
     * Rend a une personne connectee les objets de ses capacites. Renvoie le nombre d'objets remis :
     * zero signifie qu'elle a deja tout ce qu'il lui faut.
     */
    public static int rendreLesObjets(ServerPlayer cible) {
        int rendus = 0;
        for (Power pouvoir : PowerData.get(cible.server).de(cible.getUUID())) {
            if (donner(cible, pouvoir)) {
                rendus++;
            }
        }
        return rendus;
    }

    // ------------------------------------------------------------------ Connexion et mort

    /**
     * A la connexion : on remet ce qui etait du a quelqu'un d'absent au moment de la decision, et on
     * reprend l'objet d'une capacite retiree pendant l'absence. Un objet inerte laisse dans
     * l'inventaire se lit comme une panne plutot que comme une decision.
     */
    public static void onLogin(ServerPlayer joueur) {
        livrerCeQuiEstDu(joueur);
        var siennes = PowerData.get(joueur.server).de(joueur.getUUID());
        for (Power pouvoir : Power.values()) {
            if (!siennes.contains(pouvoir)) {
                retirerObjets(joueur, pouvoir);
            }
        }
    }

    /**
     * A la mort : les objets de capacite ne tombent pas. Un mage qui perd son baton au fond d'un
     * ravin en plein evenement ne peut pas attendre qu'un administrateur le lui rende - mais laisser
     * tomber l'ancien tout en remettant un neuf a la reapparition en ferait deux. Alors on le
     * retire du tas et on note la dette : elle sera honoree a la reapparition.
     */
    public static void onDrops(ServerPlayer mort,
            java.util.Collection<net.minecraft.world.entity.item.ItemEntity> tombes) {
        PowerData donnees = PowerData.get(mort.server);
        for (Power pouvoir : donnees.de(mort.getUUID())) {
            if (tombes.removeIf(objet -> estLobjetDe(pouvoir, objet.getItem()))) {
                donnees.noterARemettre(mort.getUUID(), pouvoir);
            }
        }
    }

    /** A la reapparition : on honore ce qui a ete note a la mort, et rien d'autre. */
    public static void onRespawn(ServerPlayer joueur) {
        livrerCeQuiEstDu(joueur);
    }

    /** Remet les objets notes comme dus, pour les capacites encore detenues. */
    private static void livrerCeQuiEstDu(ServerPlayer joueur) {
        PowerData donnees = PowerData.get(joueur.server);
        var siennes = donnees.de(joueur.getUUID());
        for (Power pouvoir : new java.util.ArrayList<>(donnees.aRemettre(joueur.getUUID()))) {
            if (siennes.contains(pouvoir)) {
                donner(joueur, pouvoir);
            }
        }
    }
}
