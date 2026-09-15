package com.utopia.dieu;

import java.util.ArrayList;
import java.util.List;

import com.utopia.data.DieuData;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.net.OwoMenuServer;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Le pupitre de {@code /dieux} : ce qui sert a preparer un evenement sans que personne ne le voie
 * venir, pas meme les autres operateurs.
 *
 * <p>Le droit est reverifie a chaque ecran et non seulement a l'ouverture de la commande : un menu
 * reste affiche apres que la console a retire le titre, et c'est le clic qui compte.
 */
public final class DieuMenus {

    private DieuMenus() {
    }

    private static boolean denied(ServerPlayer player) {
        if (DieuData.get(player.server).isDieu(player.getUUID())) {
            return false;
        }
        // Message volontairement neutre : il ne doit pas reveler qu'un tel pouvoir existe.
        player.sendSystemMessage(Messages.error("Commande inconnue."));
        return true;
    }

    public static void open(ServerPlayer player) {
        if (denied(player)) {
            return;
        }
        List<Component> stats = new ArrayList<>();
        stats.add(Component.literal("Prepare ce que personne ne doit voir venir.")
                .withStyle(s -> s.withColor(ChatFormatting.LIGHT_PURPLE).withItalic(false)));
        stats.add(Icons.lore("Ce menu n'existe pour aucun autre joueur, operateurs compris.",
                ChatFormatting.DARK_GRAY));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.COMMAND_BLOCK),
                Icons.label("Tous les outils d'administration", ChatFormatting.RED),
                Icons.lore("Les cinq rubriques de /admin, sans passer par la commande",
                        ChatFormatting.GRAY),
                com.utopia.menu.AdminMenu::open));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.GOAT_HORN),
                Icons.label("Annonce au serveur", ChatFormatting.GOLD),
                Icons.lore("Diffuse un message a tout le monde, sans nom d'auteur",
                        ChatFormatting.GRAY),
                DieuMenus::promptAnnonce));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.WRITABLE_BOOK),
                Icons.label("Depouillement", ChatFormatting.RED),
                Icons.lore("Decider du resultat de l'election en cours", ChatFormatting.GRAY),
                DieuMenus::openElection));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.LEATHER_HELMET),
                Icons.label("Deguisement", ChatFormatting.LIGHT_PURPLE),
                Icons.lore("Porter un autre nom, et le visage de quelqu'un d'autre",
                        ChatFormatting.GRAY),
                DieuMenus::openDeguisement));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PAPER),
                Icons.label("Message a un joueur", ChatFormatting.AQUA),
                Icons.lore("Un message prive, qui n'a l'air de venir de personne",
                        ChatFormatting.GRAY),
                sp -> openJoueurs(sp, 0)));
        int porteurs = com.utopia.data.PowerData.get(player.server).tousLesPorteurs().size();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.BLAZE_ROD),
                Icons.label("Capacites speciales", ChatFormatting.LIGHT_PURPLE),
                Icons.lore(porteurs == 0
                        ? "Accorder un pouvoir a quelqu'un"
                        : porteurs + " porteur(s) - accorder ou reprendre", ChatFormatting.GRAY),
                sp -> openCapacites(sp, 0)));

        OwoMenuServer.openHub(player, Icons.screenTitle("Dieux", ChatFormatting.LIGHT_PURPLE),
                stats, entries, DieuMenus::open, null);
    }

    /**
     * Une annonce sans signature : pendant un evenement, un message qui porte le nom d'un
     * administrateur casse la fiction que l'on vient de monter.
     */
    private static void promptAnnonce(ServerPlayer player) {
        if (denied(player)) {
            return;
        }
        Menus.promptFreeText(player, Icons.label("Annonce au serveur", ChatFormatting.GOLD),
                List.of(Icons.lore("Diffusee a tous, sans nom d'auteur.", ChatFormatting.GRAY),
                        Icons.lore("Accents et ponctuation acceptes.", ChatFormatting.DARK_GRAY)),
                Icons.label("Diffuser", ChatFormatting.GREEN), "", 200,
                texte -> {
                    if (denied(player)) {
                        return;
                    }
                    if (texte == null || texte.isBlank()) {
                        open(player);
                        return;
                    }
                    player.server.getPlayerList().broadcastSystemMessage(
                            Component.literal(texte)
                                    .withStyle(s -> s.withColor(ChatFormatting.LIGHT_PURPLE)
                                            .withBold(true).withItalic(false)),
                            false);
                    player.sendSystemMessage(Messages.success("Annonce diffusee."));
                    open(player);
                });
    }

    /**
     * Le depouillement, tel qu'on decide qu'il sera. Les deux colonnes sont montrees cote a cote :
     * ce que les bulletins disent, et ce que le serveur annoncera. Truquer a l'aveugle serait le
     * meilleur moyen de se trahir.
     */
    public static void openElection(ServerPlayer player) {
        if (denied(player)) {
            return;
        }
        com.utopia.data.ElectionData donnees = com.utopia.data.ElectionData.get(player.server);
        com.utopia.data.ElectionData.Election el = donnees.current();
        if (el == null || el.candidates.isEmpty()) {
            player.sendSystemMessage(Messages.warn("Aucune election en preparation."));
            open(player);
            return;
        }

        java.util.Map<String, Integer> reels =
                com.utopia.election.ElectionManager.comptesReels(el);
        java.util.Map<String, Integer> affiches = new java.util.LinkedHashMap<>();
        for (com.utopia.election.ElectionManager.Scored sc
                : com.utopia.election.ElectionManager.scores(el)) {
            affiches.put(sc.name(), sc.votes());
        }

        List<Component> stats = new ArrayList<>();
        stats.add(Component.literal(el.name + " - " + el.status)
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withItalic(false)));
        stats.add(Icons.lore(el.votes.size() + " bulletin(s) deposes. Le total annonce ne bougera "
                + "jamais : truquer deplace des voix, il n'en cree aucune.", ChatFormatting.DARK_GRAY));
        if (el.truqueEnFaveurDe != null) {
            stats.add(Component.literal("Depouillement oriente en faveur de " + el.truqueEnFaveurDe)
                    .withStyle(s -> s.withColor(ChatFormatting.RED).withBold(true).withItalic(false)));
        }
        if (el.votes.size() == 0) {
            stats.add(Component.literal("Aucun bulletin : il n'y a rien a deplacer.")
                    .withStyle(s -> s.withColor(ChatFormatting.RED).withItalic(false)));
        }

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (String candidat : el.candidates) {
            int reel = reels.getOrDefault(candidat, 0);
            int affiche = affiches.getOrDefault(candidat, reel);
            boolean choisi = candidat.equals(el.truqueEnFaveurDe);
            String detail = reel == affiche
                    ? reel + " voix"
                    : reel + " voix reelles, " + affiche + " annoncees";
            entries.add(new OwoMenuServer.HubEntry(
                    new ItemStack(choisi ? Items.GOLDEN_HELMET : Items.PLAYER_HEAD),
                    Icons.label(candidat + (choisi ? " - fera gagnant" : ""),
                            choisi ? ChatFormatting.GOLD : ChatFormatting.WHITE),
                    Icons.lore(detail + (choisi ? "" : " - clique pour le faire gagner"),
                            reel == affiche ? ChatFormatting.GRAY : ChatFormatting.GOLD),
                    sp -> {
                        com.utopia.data.ElectionData d = com.utopia.data.ElectionData.get(sp.server);
                        com.utopia.data.ElectionData.Election e = d.current();
                        if (e != null) {
                            e.truqueEnFaveurDe = candidat;
                            d.setDirty();
                        }
                        openElection(sp);
                    }));
        }
        if (el.truqueEnFaveurDe != null) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.BARRIER),
                    Icons.label("Rendre le scrutin sincere", ChatFormatting.GREEN),
                    Icons.lore("Le depouillement redevient celui des bulletins", ChatFormatting.GRAY),
                    sp -> {
                        com.utopia.data.ElectionData d = com.utopia.data.ElectionData.get(sp.server);
                        com.utopia.data.ElectionData.Election e = d.current();
                        if (e != null) {
                            e.truqueEnFaveurDe = null;
                            d.setDirty();
                        }
                        openElection(sp);
                    }));
        }

        OwoMenuServer.openHub(player, Icons.screenTitle("Depouillement", ChatFormatting.RED),
                stats, entries, DieuMenus::openElection, DieuMenus::open);
    }

    /** Le deguisement : un nom d'emprunt, un visage d'emprunt, ou les deux. */
    public static void openDeguisement(ServerPlayer player) {
        if (denied(player)) {
            return;
        }
        com.utopia.data.DisguiseData.Disguise d =
                com.utopia.data.DisguiseData.get(player.server).get(player.getUUID());
        boolean nom = d != null && d.aUnNom();
        boolean skin = d != null && d.aUnSkin();

        List<Component> stats = new ArrayList<>();
        stats.add(Component.literal("Tu apparais sous : ")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
                .append(Component.literal(nom ? d.nom : player.getGameProfile().getName())
                        .withStyle(s -> s.withColor(nom ? ChatFormatting.GOLD : ChatFormatting.WHITE)
                                .withItalic(false))));
        stats.add(Component.literal("Visage : ")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
                .append(Component.literal(skin ? "celui de " + d.skinSource : "le tien")
                        .withStyle(s -> s.withColor(skin ? ChatFormatting.GOLD : ChatFormatting.WHITE)
                                .withItalic(false))));
        stats.add(Icons.lore("Les autres voient le visage tout de suite ; toi, a ta prochaine "
                + "connexion.", ChatFormatting.DARK_GRAY));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.NAME_TAG),
                Icons.label("Changer de nom", ChatFormatting.AQUA),
                Icons.lore("Le nom du chat et de la liste des joueurs", ChatFormatting.GRAY),
                sp -> Menus.promptFreeText(sp, Icons.label("Nom d'emprunt", ChatFormatting.GOLD),
                        List.of(Icons.lore("Accents et espaces acceptes.", ChatFormatting.GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN),
                        nom ? d.nom : "", 32,
                        v -> {
                            if (denied(sp)) {
                                return;
                            }
                            if (v != null && !v.isBlank()) {
                                com.utopia.disguise.DisguiseManager.setNom(sp, v);
                            }
                            openDeguisement(sp);
                        })));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PLAYER_HEAD),
                Icons.label("Prendre un visage", ChatFormatting.AQUA),
                Icons.lore("Copier le skin d'un joueur connecte", ChatFormatting.GRAY),
                sp -> openVisages(sp, 0)));
        if (nom) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.BARRIER),
                    Icons.label("Reprendre mon nom", ChatFormatting.GREEN), Component.empty(),
                    sp -> {
                        com.utopia.disguise.DisguiseManager.clearNom(sp);
                        openDeguisement(sp);
                    }));
        }
        if (skin) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.BARRIER),
                    Icons.label("Reprendre mon visage", ChatFormatting.GREEN), Component.empty(),
                    sp -> {
                        com.utopia.disguise.DisguiseManager.clearSkin(sp);
                        openDeguisement(sp);
                    }));
        }

        OwoMenuServer.openHub(player, Icons.screenTitle("Deguisement", ChatFormatting.LIGHT_PURPLE),
                stats, entries, DieuMenus::openDeguisement, DieuMenus::open);
    }

    private static void openVisages(ServerPlayer player, int page) {
        if (denied(player)) {
            return;
        }
        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (ServerPlayer modele : player.server.getPlayerList().getPlayers()) {
            if (modele == player) {
                continue;
            }
            entries.add(new OwoMenuServer.HubEntry(
                    Icons.playerHead(modele, Icons.label(modele.getGameProfile().getName(),
                            ChatFormatting.WHITE), List.of()),
                    Icons.label(modele.getGameProfile().getName(), ChatFormatting.WHITE),
                    Icons.lore("Prendre son visage", ChatFormatting.GRAY),
                    sp -> {
                        if (denied(sp)) {
                            return;
                        }
                        com.utopia.disguise.DisguiseManager.setSkin(sp, modele);
                        openDeguisement(sp);
                    }));
        }
        OwoMenuServer.openHubPaged(player,
                Icons.screenTitle("Prendre un visage", ChatFormatting.LIGHT_PURPLE),
                List.of(Icons.lore("Le skin est copie : le modele peut partir ou en changer.",
                        ChatFormatting.GRAY)),
                entries, page, 28, DieuMenus::openVisages, DieuMenus::openDeguisement);
    }

    private static void openJoueurs(ServerPlayer player, int page) {
        if (denied(player)) {
            return;
        }
        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (ServerPlayer cible : player.server.getPlayerList().getPlayers()) {
            entries.add(new OwoMenuServer.HubEntry(
                    Icons.playerHead(cible, Icons.label(cible.getGameProfile().getName(),
                            ChatFormatting.WHITE), List.of()),
                    Icons.label(cible.getGameProfile().getName(), ChatFormatting.WHITE),
                    Icons.lore("Lui envoyer un message prive", ChatFormatting.GRAY),
                    sp -> promptMessage(sp, cible.getUUID(), cible.getGameProfile().getName(), page)));
        }
        OwoMenuServer.openHubPaged(player,
                Icons.screenTitle("Message a un joueur", ChatFormatting.AQUA),
                List.of(Icons.lore("Seuls les joueurs connectes sont proposes.", ChatFormatting.GRAY)),
                entries, page, 28, DieuMenus::openJoueurs, DieuMenus::open);
    }

    private static void promptMessage(ServerPlayer player, java.util.UUID cibleId, String nom, int page) {
        if (denied(player)) {
            return;
        }
        Menus.promptFreeText(player, Icons.label("Message a " + nom, ChatFormatting.AQUA),
                List.of(Icons.lore("Lui seul le lira, et rien n'indiquera d'ou il vient.",
                        ChatFormatting.GRAY)),
                Icons.label("Envoyer", ChatFormatting.GREEN), "", 200,
                texte -> {
                    if (denied(player)) {
                        return;
                    }
                    if (texte == null || texte.isBlank()) {
                        openJoueurs(player, page);
                        return;
                    }
                    ServerPlayer cible = player.server.getPlayerList().getPlayer(cibleId);
                    if (cible == null) {
                        player.sendSystemMessage(Messages.warn(nom + " s'est deconnecte."));
                        openJoueurs(player, page);
                        return;
                    }
                    cible.sendSystemMessage(Component.literal(texte)
                            .withStyle(s -> s.withColor(ChatFormatting.LIGHT_PURPLE)
                                    .withBold(true).withItalic(false)));
                    player.sendSystemMessage(Messages.success("Message remis a " + nom + "."));
                    openJoueurs(player, page);
                });
    }

    // ------------------------------------------------------------------ Capacites speciales

    /**
     * A qui accorder une capacite. Les joueurs connectes viennent en premier, puis les porteurs
     * absents : c'est la seule facon de reprendre un pouvoir a quelqu'un qui s'est deconnecte juste
     * apres l'avoir recu.
     */
    public static void openCapacites(ServerPlayer player, int page) {
        if (denied(player)) {
            return;
        }
        com.utopia.data.PowerData donnees = com.utopia.data.PowerData.get(player.server);

        List<Component> stats = new ArrayList<>();
        stats.add(Component.literal("Une capacite se donne a une personne, pas a un objet.")
                .withStyle(s -> s.withColor(ChatFormatting.LIGHT_PURPLE).withItalic(false)));
        stats.add(Icons.lore("L'objet qui la porte peut etre perdu ou vole : le droit, lui, reste "
                + "ici et se reprend d'un clic.", ChatFormatting.DARK_GRAY));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        java.util.Set<java.util.UUID> vus = new java.util.HashSet<>();
        for (ServerPlayer cible : player.server.getPlayerList().getPlayers()) {
            vus.add(cible.getUUID());
            String nom = cible.getGameProfile().getName();
            entries.add(entreeJoueur(donnees, cible.getUUID(), nom,
                    Icons.playerHead(cible, Icons.label(nom, ChatFormatting.WHITE), List.of()),
                    true, page));
        }
        for (java.util.Map.Entry<java.util.UUID, String> absent : donnees.noms().entrySet()) {
            if (vus.contains(absent.getKey())) {
                continue;
            }
            entries.add(entreeJoueur(donnees, absent.getKey(), absent.getValue(),
                    new ItemStack(Items.SKELETON_SKULL), false, page));
        }

        OwoMenuServer.openHubPaged(player,
                Icons.screenTitle("Capacites speciales", ChatFormatting.LIGHT_PURPLE),
                stats, entries, page, 28, DieuMenus::openCapacites, DieuMenus::open);
    }

    private static OwoMenuServer.HubEntry entreeJoueur(com.utopia.data.PowerData donnees,
            java.util.UUID id, String nom, ItemStack icone, boolean connecte, int page) {
        java.util.Set<com.utopia.power.Power> siens = donnees.de(id);
        String detail;
        if (siens.isEmpty()) {
            detail = connecte ? "Aucune capacite" : "Hors ligne - aucune capacite";
        } else {
            StringBuilder sb = new StringBuilder();
            for (com.utopia.power.Power p : siens) {
                sb.append(sb.isEmpty() ? "" : ", ").append(p.label);
            }
            detail = (connecte ? "" : "Hors ligne - ") + sb;
        }
        return new OwoMenuServer.HubEntry(icone,
                Icons.label(nom, siens.isEmpty() ? ChatFormatting.WHITE : ChatFormatting.LIGHT_PURPLE),
                Icons.lore(detail, siens.isEmpty() ? ChatFormatting.GRAY : ChatFormatting.LIGHT_PURPLE),
                sp -> openCapacitesJoueur(sp, id, nom, page));
    }

    /** Ce que porte une personne, et ce qu'on peut lui accorder ou lui reprendre. */
    public static void openCapacitesJoueur(ServerPlayer player, java.util.UUID id, String nom,
            int page) {
        if (denied(player)) {
            return;
        }
        com.utopia.data.PowerData donnees = com.utopia.data.PowerData.get(player.server);
        ServerPlayer cible = player.server.getPlayerList().getPlayer(id);

        List<Component> stats = new ArrayList<>();
        stats.add(Component.literal(nom)
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withItalic(false)));
        stats.add(Icons.lore(cible == null
                ? "Hors ligne. Le droit se donne quand meme, l'objet suivra a sa connexion."
                : "Connecte. L'objet lui est remis tout de suite.", ChatFormatting.DARK_GRAY));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (com.utopia.power.Power pouvoir : com.utopia.power.Power.values()) {
            boolean tenu = donnees.a(id, pouvoir);
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(pouvoir.icone),
                    Icons.label(pouvoir.label + (tenu ? " - accordee" : ""),
                            tenu ? ChatFormatting.GREEN : pouvoir.couleur),
                    Icons.lore(tenu ? "Clique pour la lui reprendre" : pouvoir.detail,
                            tenu ? ChatFormatting.GRAY : ChatFormatting.GRAY),
                    sp -> {
                        if (denied(sp)) {
                            return;
                        }
                        boolean accorde = com.utopia.data.PowerData.get(sp.server)
                                .basculer(id, nom, pouvoir);
                        com.utopia.power.PowerManager.appliquer(sp.server, id, pouvoir, accorde);
                        sp.sendSystemMessage(accorde
                                ? Messages.success(pouvoir.label + " accordee a " + nom + ".")
                                : Messages.warn(pouvoir.label + " reprise a " + nom + "."));
                        openCapacitesJoueur(sp, id, nom, page);
                    }));
        }
        if (!donnees.de(id).isEmpty()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.CHEST),
                    Icons.label("Lui redonner ses objets", ChatFormatting.GOLD),
                    Icons.lore("S'il a perdu, jete ou casse ce qui porte sa capacite",
                            ChatFormatting.GRAY),
                    sp -> {
                        if (denied(sp)) {
                            return;
                        }
                        ServerPlayer present = sp.server.getPlayerList().getPlayer(id);
                        if (present == null) {
                            sp.sendSystemMessage(Messages.warn(nom + " n'est pas connecte : il "
                                    + "retrouvera ses objets en revenant."));
                        } else {
                            int rendus = com.utopia.power.PowerManager.rendreLesObjets(present);
                            sp.sendSystemMessage(rendus > 0
                                    ? Messages.success(rendus + " objet(s) remis a " + nom + ".")
                                    : Messages.info(nom + " a deja tout ce qu'il lui faut."));
                        }
                        openCapacitesJoueur(sp, id, nom, page);
                    }));
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.BARRIER),
                    Icons.label("Tout lui reprendre", ChatFormatting.RED),
                    Icons.lore("Il redevient un joueur comme les autres", ChatFormatting.GRAY),
                    sp -> {
                        if (denied(sp)) {
                            return;
                        }
                        // Passe par le service : le droit tombe, et l'objet avec lui.
                        com.utopia.power.PowerManager.toutRetirer(sp.server, id);
                        sp.sendSystemMessage(Messages.warn("Capacites reprises a " + nom + "."));
                        openCapacites(sp, page);
                    }));
        }

        OwoMenuServer.openHub(player,
                Icons.screenTitle("Capacites - " + nom, ChatFormatting.LIGHT_PURPLE),
                stats, entries,
                sp -> openCapacitesJoueur(sp, id, nom, page),
                sp -> openCapacites(sp, page));
    }
}
