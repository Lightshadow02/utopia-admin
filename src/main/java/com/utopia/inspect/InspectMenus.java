package com.utopia.inspect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.gui.UtopiaGui;
import com.utopia.net.OwoMenuServer;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Voir - et corriger - les affaires de quelqu'un, connecte ou non.
 *
 * <p>Un joueur deconnecte n'existe plus en memoire : ses affaires sont relues dans son fichier de
 * sauvegarde et y sont reecrites. C'est tout l'interet : la plupart des litiges se reglent apres
 * coup, quand l'interesse est parti se coucher.
 *
 * <p>L'enregistrement ne recopie pas tout l'inventaire, il ne reporte <b>que les cases que
 * l'administrateur a touchees</b>. Sans cela, ouvrir l'inventaire d'un joueur connecte, le regarder
 * dix secondes puis enregistrer annulerait tout ce qu'il a fait pendant ce temps - un objet
 * fabrique, un coffre vide, un achat. La comparaison se fait avec l'etat du moment de l'ouverture.
 */
public final class InspectMenus {

    /**
     * Ou se trouve chaque case de l'inventaire dans la fenetre, et l'inverse. Le sac vient en haut
     * comme dans l'ecran d'inventaire, la barre d'action en dessous, puis l'equipement - casque a
     * gauche, main gauche a droite. {@code -1} marque une case de decor.
     */
    private static final int[] CASE_DE = new int[54];

    static {
        Arrays.fill(CASE_DE, -1);
        for (int i = 0; i < 27; i++) {
            CASE_DE[i] = 9 + i;
        }
        for (int i = 0; i < 9; i++) {
            CASE_DE[27 + i] = i;
        }
        CASE_DE[36] = 39; // casque
        CASE_DE[37] = 38; // plastron
        CASE_DE[38] = 37; // jambieres
        CASE_DE[39] = 36; // bottes
        CASE_DE[40] = 40; // main gauche
    }

    /** Duree de validite de la liste des sauvegardes. Elle se relit sur disque, inutile a chaque page. */
    private static final long VALIDITE_LISTE_MS = 30_000L;

    private static List<OfflinePlayerData.Connu> listeCache;
    private static long listeVueA;

    private InspectMenus() {
    }

    // ------------------------------------------------------------------ Le choix du joueur

    public static void open(ServerPlayer admin) {
        openJoueurs(admin, 0);
    }

    public static void openJoueurs(ServerPlayer admin, int page) {
        if (refuse(admin)) {
            return;
        }
        MinecraftServer server = admin.server;
        List<OwoMenuServer.HubEntry> entrees = new ArrayList<>();
        java.util.Set<UUID> deja = new java.util.HashSet<>();

        // Les connectes d'abord : ce sont eux qu'on cherche le plus souvent, et leur inventaire est
        // celui de l'instant plutot que celui de leur derniere deconnexion.
        for (ServerPlayer present : server.getPlayerList().getPlayers()) {
            deja.add(present.getUUID());
            String nom = present.getGameProfile().getName();
            entrees.add(new OwoMenuServer.HubEntry(
                    Icons.playerHead(present, Icons.label(nom, ChatFormatting.GREEN), List.of()),
                    Icons.label(nom, ChatFormatting.GREEN),
                    Icons.lore("Connecte", ChatFormatting.GRAY),
                    sp -> openChoix(sp, present.getUUID(), nom, page)));
        }
        for (OfflinePlayerData.Connu connu : liste(server)) {
            if (deja.contains(connu.id())) {
                continue;
            }
            entrees.add(new OwoMenuServer.HubEntry(new ItemStack(Items.SKELETON_SKULL),
                    Icons.label(connu.nom(), ChatFormatting.WHITE),
                    Icons.lore(connu.nomConnu() ? "Hors ligne" : "Hors ligne - pseudo inconnu",
                            ChatFormatting.DARK_GRAY),
                    sp -> openChoix(sp, connu.id(), connu.nom(), page)));
        }

        List<OwoMenuServer.HubEntry> epingles = List.of(new OwoMenuServer.HubEntry(
                new ItemStack(Items.WRITABLE_BOOK),
                Icons.label("Par son pseudo", ChatFormatting.GOLD),
                Icons.lore("Chercher directement, sans derouler la liste", ChatFormatting.GRAY),
                sp -> promptPseudo(sp, page)));

        OwoMenuServer.openHubPaged(admin,
                Icons.screenTitle("Voir un inventaire", ChatFormatting.LIGHT_PURPLE),
                List.of(Icons.lore("Les deconnectes aussi : leurs affaires sont relues dans leur "
                        + "sauvegarde.", ChatFormatting.GRAY)),
                epingles, entrees, page, 28, InspectMenus::openJoueurs,
                com.utopia.menu.AdminMenu::openJoueurs);
    }

    private static List<OfflinePlayerData.Connu> liste(MinecraftServer server) {
        long maintenant = System.currentTimeMillis();
        if (listeCache == null || maintenant - listeVueA > VALIDITE_LISTE_MS) {
            listeCache = OfflinePlayerData.joueursConnus(server);
            listeVueA = maintenant;
        }
        return listeCache;
    }

    /**
     * Retrouve un joueur par son pseudo, sans jamais sortir de la machine.
     *
     * <p>Le cache de profils sait aussi resoudre un pseudo, mais il appelle Mojang quand il ne le
     * connait pas - et cet appel-la, sur le fil du serveur, fige la partie de tout le monde le temps
     * qu'il reponde. On se contente donc de ce qui est ici : quelqu'un qui n'a jamais joue sur ce
     * serveur n'a pas de sauvegarde, et n'a donc rien a montrer.
     */
    public static OfflinePlayerData.Connu resoudre(MinecraftServer server, String pseudo) {
        String nom = pseudo == null ? "" : pseudo.trim();
        if (nom.isEmpty()) {
            return null;
        }
        ServerPlayer present = server.getPlayerList().getPlayerByName(nom);
        if (present != null) {
            return new OfflinePlayerData.Connu(present.getUUID(),
                    present.getGameProfile().getName(), true);
        }
        for (OfflinePlayerData.Connu connu : liste(server)) {
            if (connu.nomConnu() && connu.nom().equalsIgnoreCase(nom)) {
                return connu;
            }
        }
        return null;
    }

    private static void promptPseudo(ServerPlayer admin, int page) {
        if (refuse(admin)) {
            return;
        }
        Menus.promptFreeText(admin, Icons.label("Chercher un joueur", ChatFormatting.GOLD),
                List.of(Icons.lore("Le pseudo Minecraft, connecte ou non.", ChatFormatting.GRAY)),
                Icons.label("Chercher", ChatFormatting.GREEN), "", 16,
                pseudo -> {
                    if (refuse(admin)) {
                        return;
                    }
                    if (pseudo == null || pseudo.isBlank()) {
                        openJoueurs(admin, page);
                        return;
                    }
                    String nom = pseudo.trim();
                    OfflinePlayerData.Connu trouve = resoudre(admin.server, nom);
                    if (trouve == null) {
                        admin.sendSystemMessage(Messages.error("Aucune sauvegarde pour \"" + nom
                                + "\". Ce joueur n'est jamais venu sur ce serveur."));
                        openJoueurs(admin, page);
                        return;
                    }
                    openChoix(admin, trouve.id(), trouve.nom(), page);
                });
    }

    // ------------------------------------------------------------------ Inventaire ou coffre

    public static void openChoix(ServerPlayer admin, UUID cible, String nom, int page) {
        if (refuse(admin)) {
            return;
        }
        boolean connecte = admin.server.getPlayerList().getPlayer(cible) != null;
        if (!connecte && !OfflinePlayerData.existe(admin.server, cible)) {
            admin.sendSystemMessage(Messages.error(nom + " n'a aucune sauvegarde sur ce serveur."));
            openJoueurs(admin, page);
            return;
        }

        List<Component> stats = new ArrayList<>();
        stats.add(Component.literal(nom)
                .withStyle(s -> s.withColor(connecte ? ChatFormatting.GREEN : ChatFormatting.GRAY)
                        .withItalic(false)));
        stats.add(Icons.lore(connecte
                ? "Connecte : ce que tu changes lui arrive tout de suite."
                : "Hors ligne : ses affaires sont relues et reecrites dans sa sauvegarde.",
                ChatFormatting.DARK_GRAY));

        List<OwoMenuServer.HubEntry> entrees = List.of(
                new OwoMenuServer.HubEntry(new ItemStack(Items.CHEST),
                        Icons.label("Inventaire", ChatFormatting.AQUA),
                        Icons.lore("Le sac, la barre d'action, l'armure et la main gauche",
                                ChatFormatting.GRAY),
                        sp -> openInventaire(sp, cible, nom, page)),
                new OwoMenuServer.HubEntry(new ItemStack(Items.ENDER_CHEST),
                        Icons.label("Coffre de l'End", ChatFormatting.LIGHT_PURPLE),
                        Icons.lore("Les vingt-sept cases de son coffre de l'End",
                                ChatFormatting.GRAY),
                        sp -> openEnderchest(sp, cible, nom, page)));

        OwoMenuServer.openHub(admin, Icons.screenTitle(nom, ChatFormatting.LIGHT_PURPLE),
                stats, entrees, sp -> openChoix(sp, cible, nom, page),
                sp -> openJoueurs(sp, page));
    }

    // ------------------------------------------------------------------ Les affaires

    /** Le plan du coffre de l'End : ses vingt-sept cases, puis le bandeau. */
    private static int[] planEnder() {
        int[] plan = new int[36];
        Arrays.fill(plan, -1);
        for (int i = 0; i < OfflinePlayerData.TAILLE_ENDER; i++) {
            plan[i] = i;
        }
        return plan;
    }

    public static void openInventaire(ServerPlayer admin, UUID cible, String nom, int page) {
        ouvrir(admin, cible, nom, page, CASE_DE.clone(), false, 6,
                Icons.screenTitle("Inventaire de " + nom, ChatFormatting.AQUA),
                "Sac en haut, barre d'action au milieu, equipement en bas.");
    }

    public static void openEnderchest(ServerPlayer admin, UUID cible, String nom, int page) {
        ouvrir(admin, cible, nom, page, planEnder(), true, 4,
                Icons.screenTitle("Coffre de l'End de " + nom, ChatFormatting.LIGHT_PURPLE),
                "Les vingt-sept cases de son coffre de l'End.");
    }

    private static void ouvrir(ServerPlayer admin, UUID cible, String nom, int page, int[] plan,
            boolean coffreDeLEnd, int lignes, Component titre, String mode) {
        if (refuse(admin)) {
            return;
        }
        if (cible.equals(admin.getUUID()) && !coffreDeLEnd) {
            // Ses propres cases apparaitraient deux fois dans la meme fenetre, en haut par le
            // miroir et en bas par l'inventaire du joueur : un objet deplace de l'une a l'autre
            // irait et viendrait de la meme case, avec le resultat qu'on imagine.
            admin.sendSystemMessage(Messages.warn("Pour ton propre inventaire, la touche E suffit."));
            openChoix(admin, cible, nom, page);
            return;
        }
        MiroirAffaires miroir = new MiroirAffaires(admin.server, cible, plan, coffreDeLEnd);
        if (!miroir.pret()) {
            admin.sendSystemMessage(Messages.error("Impossible de lire les affaires de " + nom + "."));
            openChoix(admin, cible, nom, page);
            return;
        }

        UtopiaGui gui = new UtopiaGui(lignes, titre, miroir);
        for (int g = 0; g < plan.length; g++) {
            if (plan[g] >= 0) {
                gui.editableSlot(g);
            }
        }

        int bandeau = (lignes - 1) * 9;
        int slotInfo = bandeau;
        int slotFermer = bandeau + 4;
        gui.set(slotInfo, Icons.icon(Items.PAPER, Icons.label("Mode d'emploi", ChatFormatting.AQUA),
                List.of(Icons.lore(mode, ChatFormatting.GRAY),
                        Icons.lore("Chaque deplacement porte tout de suite.", ChatFormatting.GREEN),
                        Icons.lore(miroir.horsLigne()
                                ? "Hors ligne : sa sauvegarde est reecrite a chaque geste."
                                : "Connecte : il le voit dans son inventaire a l'instant.",
                                ChatFormatting.DARK_GRAY),
                        Icons.lore("Il n'y a rien a enregistrer, et rien a annuler.",
                                ChatFormatting.DARK_GRAY))));
        gui.button(slotFermer, Icons.icon(Items.BARRIER, Icons.label("FERMER", ChatFormatting.RED),
                List.of(Icons.lore("Retour a la fiche de " + nom, ChatFormatting.GRAY))),
                sp -> {
                    gui.markFinalized();
                    openChoix(sp, cible, nom, page);
                });
        // Les cases restantes du bandeau, et celles que le plan laisse vides entre l'equipement et
        // le bandeau, sont bouchees : un objet lache dans un trou n'irait nulle part.
        for (int g = 0; g < plan.length; g++) {
            if (plan[g] < 0 && g != slotInfo && g != slotFermer) {
                gui.set(g, Icons.filler());
            }
        }
        Menus.open(admin, gui);
    }

    // ------------------------------------------------------------------ Le commun

    /** Le droit est reverifie a chaque ecran : un menu reste ouvert apres un retrait de grade. */
    private static boolean refuse(ServerPlayer admin) {
        if (admin.hasPermissions(2)) {
            return false;
        }
        admin.sendSystemMessage(Messages.error("Reserve aux operateurs."));
        return true;
    }
}
