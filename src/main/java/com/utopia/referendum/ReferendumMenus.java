package com.utopia.referendum;

import java.util.ArrayList;
import java.util.List;

import com.utopia.data.ReferendumData;
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
 * Les referendums : cote administration, on ecrit la question ; cote joueur, on coche pour ou
 * contre.
 *
 * <p>L'intitule s'ecrit <b>ligne par ligne</b>. Une question de referendum tient rarement en dix
 * mots, et une seule longue chaine serait impossible a relire comme a corriger : on remplace un mot
 * a la ligne quatre sans retaper les trois premieres.
 */
public final class ReferendumMenus {

    private ReferendumMenus() {
    }

    private static boolean denied(ServerPlayer player) {
        if (player.hasPermissions(2)) {
            return false;
        }
        player.sendSystemMessage(Messages.error("Reserve a l'administration."));
        return true;
    }

    private static Component head(String texte) {
        return Component.literal(texte)
                .withStyle(s -> s.withColor(ChatFormatting.DARK_AQUA).withBold(true).withItalic(false));
    }

    private static Component valeur(String texte, ChatFormatting couleur) {
        return Component.literal(texte).withStyle(s -> s.withColor(couleur).withItalic(false));
    }

    private static ChatFormatting couleurEtat(ReferendumData.Etat etat) {
        return switch (etat) {
            case OUVERT -> ChatFormatting.GREEN;
            case CLOS -> ChatFormatting.GRAY;
            default -> ChatFormatting.YELLOW;
        };
    }

    /**
     * Largeur visee pour une ligne a l'ecran. Le panneau des consultations offre environ 311 pixels
     * et la police courante tourne autour de six pixels par caractere : au-dela, le client replie
     * la ligne tout seul, et comme chaque ligne est centree separement, le texte part en escalier.
     */
    private static final int LARGEUR = 56;

    /**
     * Met l'intitule en lignes d'ecran. Les retours a la ligne de l'auteur sont respectes - ce sont
     * ses paragraphes - mais chacun est redecoupe aux espaces pour qu'aucune ligne ne deborde.
     *
     * <p>Le decoupage est <b>equilibre</b> : plutot que de remplir chaque ligne au maximum et de
     * laisser trois mots seuls a la derniere, on cherche la largeur qui rend les lignes les plus
     * egales pour un meme nombre de lignes. Centre, un bloc de lignes egales se lit comme une
     * proclamation ; un bloc de lignes inegales se lit comme une erreur.
     */
    public static List<String> enLignesEcran(List<String> paragraphes) {
        List<String> out = new ArrayList<>();
        for (String paragraphe : paragraphes) {
            if (paragraphe == null || paragraphe.isBlank()) {
                out.add("");
                continue;
            }
            List<String> greedy = decouper(paragraphe, LARGEUR);
            List<String> meilleur = greedy;
            int ecartMin = ecart(greedy);
            // On ne descend pas en dessous du point ou le paragraphe gagnerait une ligne de plus :
            // equilibrer ne doit jamais couter une ligne a l'ecran.
            for (int largeur = LARGEUR - 1; largeur >= LARGEUR / 2; largeur--) {
                List<String> essai = decouper(paragraphe, largeur);
                if (essai.size() != greedy.size()) {
                    break;
                }
                int e = ecart(essai);
                if (e < ecartMin) {
                    ecartMin = e;
                    meilleur = essai;
                }
            }
            out.addAll(meilleur);
        }
        return out;
    }

    /** Decoupe aux espaces sans jamais depasser la largeur, sauf pour un mot plus long a lui seul. */
    private static List<String> decouper(String texte, int largeur) {
        List<String> lignes = new ArrayList<>();
        StringBuilder courante = new StringBuilder();
        for (String mot : texte.trim().split("\\s+")) {
            if (courante.length() == 0) {
                courante.append(mot);
            } else if (courante.length() + 1 + mot.length() <= largeur) {
                courante.append(' ').append(mot);
            } else {
                lignes.add(courante.toString());
                courante = new StringBuilder(mot);
            }
        }
        if (courante.length() > 0) {
            lignes.add(courante.toString());
        }
        return lignes;
    }

    /** Difference entre la ligne la plus longue et la plus courte : plus c'est bas, plus c'est droit. */
    private static int ecart(List<String> lignes) {
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (String l : lignes) {
            min = Math.min(min, l.length());
            max = Math.max(max, l.length());
        }
        return lignes.size() <= 1 ? 0 : max - min;
    }

    // ================================================================= Administration

    /** La liste des consultations, avec leur etat et leur depouillement. */
    public static void openAdmin(ServerPlayer player) {
        if (denied(player)) {
            return;
        }
        ReferendumData data = ReferendumData.get(player.server);
        List<ReferendumData.Referendum> tous = new ArrayList<>(data.all());

        List<OwoMenuServer.Column> colonnes = List.of(
                new OwoMenuServer.Column(head("CONSULTATION"), 150, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("ETAT"), 66, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("POUR"), 46, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("CONTRE"), 52, OwoMenuServer.Column.RIGHT));

        List<OwoMenuServer.TableRow> lignes = new ArrayList<>();
        for (ReferendumData.Referendum r : tous) {
            lignes.add(new OwoMenuServer.TableRow(
                    new ItemStack(r.etat == ReferendumData.Etat.OUVERT
                            ? Items.WRITABLE_BOOK : Items.WRITTEN_BOOK),
                    List.of(valeur(r.titre, ChatFormatting.WHITE),
                            valeur(r.etat.label, couleurEtat(r.etat)),
                            valeur(String.valueOf(r.pour()), ChatFormatting.GREEN),
                            valeur(String.valueOf(r.contre()), ChatFormatting.RED)),
                    sp -> openUn(sp, r.id)));
        }
        if (lignes.isEmpty()) {
            lignes.add(new OwoMenuServer.TableRow(
                    List.of(Icons.lore("Aucune consultation pour l'instant.", ChatFormatting.DARK_GRAY),
                            Component.empty(), Component.empty(), Component.empty()),
                    null));
        }

        List<OwoMenuServer.PanelAction> pied = List.of(new OwoMenuServer.PanelAction(
                Icons.label("Nouvelle consultation", ChatFormatting.GREEN),
                ReferendumMenus::promptNouveau));

        OwoMenuServer.openTable(player, Icons.screenTitle("Referendums", ChatFormatting.GOLD),
                List.of(Icons.lore("La reponse est toujours la meme : pour, ou contre.",
                        ChatFormatting.GRAY)),
                List.of(), colonnes, lignes, pied, null, null,
                ReferendumMenus::openAdmin, com.utopia.menu.AdminMenu::openVille);
    }

    /**
     * Creation. La consultation n'existe qu'a la validation du titre : une saisie abandonnee
     * n'appelle aucun retour, et un objet cree plus tot resterait sans intitule dans la liste.
     */
    private static void promptNouveau(ServerPlayer player) {
        if (denied(player)) {
            return;
        }
        Menus.promptFreeText(player, Icons.label("Titre de la consultation", ChatFormatting.GOLD),
                List.of(Icons.lore("Un titre court, celui de la liste et du bouton.", ChatFormatting.GRAY),
                        Icons.lore("L'intitule long s'ecrit ensuite, ligne par ligne.",
                                ChatFormatting.DARK_GRAY)),
                Icons.label("Creer", ChatFormatting.GREEN), "", 60,
                titre -> {
                    if (denied(player)) {
                        return;
                    }
                    if (titre == null || titre.isBlank()) {
                        player.sendSystemMessage(Messages.warn("Il faut un titre."));
                        openAdmin(player);
                        return;
                    }
                    ReferendumData.Referendum r = ReferendumData.get(player.server)
                            .create(titre, player.getGameProfile().getName());
                    if (r == null) {
                        player.sendSystemMessage(Messages.error(
                                "Ce titre ne donne aucun identifiant utilisable."));
                        openAdmin(player);
                        return;
                    }
                    player.sendSystemMessage(Messages.success(
                            "Consultation creee. Ecris maintenant l'intitule, ligne par ligne."));
                    openLignes(player, r.id);
                });
    }

    /** Le detail d'une consultation : intitule, etat, depouillement. */
    public static void openUn(ServerPlayer player, String id) {
        if (denied(player)) {
            return;
        }
        ReferendumData data = ReferendumData.get(player.server);
        ReferendumData.Referendum r = data.get(id);
        if (r == null) {
            player.sendSystemMessage(Messages.warn("Cette consultation n'existe plus."));
            openAdmin(player);
            return;
        }

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Titre", ChatFormatting.GRAY), valeur(r.titre, ChatFormatting.WHITE),
                Icons.label("Renommer", ChatFormatting.AQUA),
                sp -> Menus.promptFreeText(sp, Icons.label("Titre de la consultation", ChatFormatting.GOLD),
                        List.of(Icons.lore("Le titre court, celui de la liste.", ChatFormatting.GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN), r.titre, 60,
                        v -> {
                            if (v != null && !v.isBlank()) {
                                r.titre = v.trim();
                                ReferendumData.get(sp.server).setDirty();
                            }
                            openUn(sp, id);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Intitule", ChatFormatting.GRAY),
                valeur(r.lignes.isEmpty() ? "aucune ligne" : r.lignes.size() + " ligne(s)",
                        r.lignes.isEmpty() ? ChatFormatting.RED : ChatFormatting.WHITE),
                Icons.label("Ecrire", ChatFormatting.GREEN),
                sp -> openLignes(sp, id)));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Etat", ChatFormatting.GRAY),
                valeur(r.etat.label + " - " + r.etat.detail, couleurEtat(r.etat)),
                Component.empty(), null));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Bulletins", ChatFormatting.GRAY),
                valeur(r.votes.size() + " vote(s) : " + r.pour() + " pour, " + r.contre() + " contre"
                                + (r.votes.isEmpty() ? "" : " (" + r.pourcentagePour() + " % pour)"),
                        ChatFormatting.AQUA),
                r.votes.isEmpty() ? Component.empty() : Icons.label("Detail", ChatFormatting.AQUA),
                r.votes.isEmpty() ? null : sp -> openDepouillement(sp, id)));
        if (!r.auteur.isBlank()) {
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label("Ouverte par", ChatFormatting.GRAY),
                    valeur(r.auteur, ChatFormatting.DARK_GRAY), Component.empty(), null));
        }

        List<OwoMenuServer.PanelAction> pied = new ArrayList<>();
        if (r.etat == ReferendumData.Etat.BROUILLON) {
            pied.add(new OwoMenuServer.PanelAction(
                    Icons.label("Ouvrir le vote", ChatFormatting.GREEN),
                    sp -> ouvrir(sp, id)));
        } else if (r.etat == ReferendumData.Etat.OUVERT) {
            pied.add(new OwoMenuServer.PanelAction(
                    Icons.label("Clore le vote", ChatFormatting.YELLOW),
                    sp -> clore(sp, id)));
        }
        pied.add(new OwoMenuServer.PanelAction(
                Icons.label("Supprimer", ChatFormatting.RED),
                sp -> OwoMenuServer.openConfirm(sp,
                        Icons.title("Supprimer \"" + r.titre + "\" ?", ChatFormatting.RED),
                        List.of(Icons.lore("L'intitule et les " + r.votes.size()
                                + " bulletin(s) sont perdus.", ChatFormatting.GRAY)),
                        Icons.label("Supprimer", ChatFormatting.RED),
                        s2 -> {
                            ReferendumData.get(s2.server).remove(id);
                            s2.sendSystemMessage(Messages.success("Consultation supprimee."));
                            openAdmin(s2);
                        },
                        s2 -> openUn(s2, id))));

        OwoMenuServer.openPanel(player, Icons.title(r.titre, ChatFormatting.GOLD), rows, pied,
                sp -> openUn(sp, id), ReferendumMenus::openAdmin);
    }

    private static void ouvrir(ServerPlayer player, String id) {
        ReferendumData data = ReferendumData.get(player.server);
        ReferendumData.Referendum r = data.get(id);
        if (r == null) {
            return;
        }
        // Une consultation sans intitule ne veut rien dire : le joueur verrait un titre et deux
        // boutons, sans savoir sur quoi il se prononce.
        if (r.lignes.isEmpty()) {
            player.sendSystemMessage(Messages.error(
                    "Ecris d'abord l'intitule : personne ne peut voter sur une question vide."));
            openUn(player, id);
            return;
        }
        r.etat = ReferendumData.Etat.OUVERT;
        r.ouvertA = System.currentTimeMillis();
        data.setDirty();
        player.server.getPlayerList().broadcastSystemMessage(
                Component.literal("Referendum ouvert : " + r.titre)
                        .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true))
                        .append(Component.literal("\nTape /referendum pour te prononcer.")
                                .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(false))),
                false);
        openUn(player, id);
    }

    private static void clore(ServerPlayer player, String id) {
        ReferendumData data = ReferendumData.get(player.server);
        ReferendumData.Referendum r = data.get(id);
        if (r == null) {
            return;
        }
        r.etat = ReferendumData.Etat.CLOS;
        r.closA = System.currentTimeMillis();
        data.setDirty();
        String verdict = r.votes.isEmpty() ? "aucun bulletin depose"
                : r.pour() > r.contre() ? "le POUR l'emporte"
                : r.contre() > r.pour() ? "le CONTRE l'emporte" : "egalite parfaite";
        player.server.getPlayerList().broadcastSystemMessage(
                Component.literal("Referendum clos : " + r.titre)
                        .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true))
                        .append(Component.literal("\n" + r.pour() + " pour, " + r.contre()
                                        + " contre - " + verdict + ".")
                                .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(false))),
                false);
        openUn(player, id);
    }

    // ================================================================= Editeur d'intitule

    /** L'intitule, ligne par ligne : ajouter, corriger, deplacer, retirer. */
    public static void openLignes(ServerPlayer player, String id) {
        if (denied(player)) {
            return;
        }
        ReferendumData data = ReferendumData.get(player.server);
        ReferendumData.Referendum r = data.get(id);
        if (r == null) {
            openAdmin(player);
            return;
        }

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        for (int i = 0; i < r.lignes.size(); i++) {
            final int index = i;
            String texte = r.lignes.get(i);
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label(String.valueOf(i + 1), ChatFormatting.DARK_GRAY),
                    valeur(texte.isBlank() ? "(ligne vide)" : texte,
                            texte.isBlank() ? ChatFormatting.DARK_GRAY : ChatFormatting.WHITE),
                    Icons.label("Modifier", ChatFormatting.AQUA),
                    sp -> promptLigne(sp, id, index)));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label("Intitule", ChatFormatting.GRAY),
                    Icons.label("vide - ajoute une premiere ligne", ChatFormatting.RED),
                    Component.empty(), null));
        }

        List<OwoMenuServer.PanelAction> pied = new ArrayList<>();
        if (r.lignes.size() < ReferendumData.MAX_LIGNES) {
            pied.add(new OwoMenuServer.PanelAction(
                    Icons.label("Ajouter une ligne", ChatFormatting.GREEN),
                    sp -> promptLigne(sp, id, -1)));
        }
        if (!r.lignes.isEmpty()) {
            pied.add(new OwoMenuServer.PanelAction(
                    Icons.label("Retirer la derniere", ChatFormatting.RED),
                    sp -> {
                        ReferendumData.Referendum cur = ReferendumData.get(sp.server).get(id);
                        if (cur != null && !cur.lignes.isEmpty()) {
                            cur.lignes.remove(cur.lignes.size() - 1);
                            ReferendumData.get(sp.server).setDirty();
                        }
                        openLignes(sp, id);
                    }));
            pied.add(new OwoMenuServer.PanelAction(
                    Icons.label("Apercu du vote", ChatFormatting.AQUA),
                    sp -> openApercu(sp, id)));
        }

        // L'auteur ecrit des paragraphes, le joueur lit des lignes d'ecran : on annonce les deux,
        // sinon on decouvre a l'apercu qu'un seul paragraphe en occupait quatre.
        int surEcran = enLignesEcran(r.lignes).size();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("A l'ecran", ChatFormatting.DARK_GRAY),
                valeur(r.lignes.size() + " paragraphe(s) = " + surEcran + " ligne(s) affichee(s)",
                        surEcran > 14 ? ChatFormatting.RED : ChatFormatting.DARK_GRAY),
                Component.empty(), null));

        OwoMenuServer.openPanel(player,
                Icons.title("Intitule - " + r.titre, ChatFormatting.GOLD), rows, pied,
                sp -> openLignes(sp, id), sp -> openUn(sp, id));
    }

    /** Saisie d'une ligne. {@code index} vaut -1 pour une ligne ajoutee a la fin. */
    private static void promptLigne(ServerPlayer player, String id, int index) {
        ReferendumData.Referendum r = ReferendumData.get(player.server).get(id);
        if (r == null) {
            return;
        }
        String actuel = index >= 0 && index < r.lignes.size() ? r.lignes.get(index) : "";
        Menus.promptFreeText(player,
                Icons.label(index < 0 ? "Nouvelle ligne" : "Ligne " + (index + 1), ChatFormatting.GOLD),
                List.of(Icons.lore("Accents et ponctuation acceptes.", ChatFormatting.GRAY),
                        Icons.lore("Laisse un espace seul pour une ligne vide.", ChatFormatting.DARK_GRAY),
                        Icons.lore(ReferendumData.MAX_CARACTERES + " caracteres au maximum.",
                                ChatFormatting.DARK_GRAY)),
                Icons.label("Valider", ChatFormatting.GREEN), actuel, ReferendumData.MAX_CARACTERES,
                v -> {
                    ReferendumData donnees = ReferendumData.get(player.server);
                    ReferendumData.Referendum cur = donnees.get(id);
                    if (cur == null || v == null) {
                        openLignes(player, id);
                        return;
                    }
                    if (index >= 0 && index < cur.lignes.size()) {
                        cur.lignes.set(index, v);
                    } else if (cur.lignes.size() < ReferendumData.MAX_LIGNES) {
                        cur.lignes.add(v);
                    }
                    donnees.setDirty();
                    openLignes(player, id);
                });
    }

    /** L'ecran tel que le joueur le verra, sans que le vote compte. */
    private static void openApercu(ServerPlayer player, String id) {
        ReferendumData.Referendum r = ReferendumData.get(player.server).get(id);
        if (r == null) {
            openAdmin(player);
            return;
        }
        List<Component> intitule = new ArrayList<>();
        for (String ligne : enLignesEcran(r.lignes)) {
            intitule.add(valeur(ligne, ChatFormatting.WHITE));
        }
        intitule.add(Icons.lore("Apercu : ton vote ne compte pas ici.", ChatFormatting.DARK_GRAY));

        List<OwoMenuServer.HubEntry> entries = List.of(
                new OwoMenuServer.HubEntry(new ItemStack(Items.LIME_DYE),
                        Icons.label("POUR", ChatFormatting.GREEN),
                        Icons.lore("Apercu", ChatFormatting.DARK_GRAY),
                        sp -> openLignes(sp, id)),
                new OwoMenuServer.HubEntry(new ItemStack(Items.RED_DYE),
                        Icons.label("CONTRE", ChatFormatting.RED),
                        Icons.lore("Apercu", ChatFormatting.DARK_GRAY),
                        sp -> openLignes(sp, id)));

        OwoMenuServer.openHub(player, Icons.screenTitle(r.titre, ChatFormatting.GOLD),
                intitule, entries, null, sp -> openLignes(sp, id));
    }

    /** Le detail nominatif des bulletins : qui a vote quoi. */
    public static void openDepouillement(ServerPlayer player, String id) {
        if (denied(player)) {
            return;
        }
        ReferendumData.Referendum r = ReferendumData.get(player.server).get(id);
        if (r == null) {
            openAdmin(player);
            return;
        }
        List<OwoMenuServer.Column> colonnes = List.of(
                new OwoMenuServer.Column(head("JOUEUR"), 150, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("VOIX"), 80, OwoMenuServer.Column.LEFT));
        List<OwoMenuServer.TableRow> lignes = new ArrayList<>();
        for (java.util.Map.Entry<java.util.UUID, Boolean> e : r.votes.entrySet()) {
            lignes.add(new OwoMenuServer.TableRow(
                    List.of(valeur(r.noms.getOrDefault(e.getKey(), "?"), ChatFormatting.WHITE),
                            valeur(e.getValue() ? "POUR" : "CONTRE",
                                    e.getValue() ? ChatFormatting.GREEN : ChatFormatting.RED)),
                    null));
        }
        OwoMenuServer.openTable(player, Icons.screenTitle("Depouillement", ChatFormatting.GOLD),
                List.of(valeur(r.pour() + " pour, " + r.contre() + " contre ("
                        + r.pourcentagePour() + " % pour)", ChatFormatting.AQUA)),
                List.of(), colonnes, lignes, List.of(), null, null,
                sp -> openDepouillement(sp, id), sp -> openUn(sp, id));
    }

    // ================================================================= Cote joueur

    /** Ce que voit un joueur qui tape /referendum. */
    public static void openVote(ServerPlayer player) {
        ReferendumData data = ReferendumData.get(player.server);
        List<ReferendumData.Referendum> ouverts = data.ouverts();
        if (ouverts.isEmpty()) {
            player.sendSystemMessage(Messages.info("Aucun referendum en cours."));
            return;
        }
        if (ouverts.size() == 1) {
            openBulletin(player, ouverts.get(0).id);
            return;
        }
        // Plusieurs consultations ouvertes : on laisse choisir plutot que d'en imposer une.
        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (ReferendumData.Referendum r : ouverts) {
            boolean deja = r.aVote(player.getUUID());
            entries.add(new OwoMenuServer.HubEntry(
                    new ItemStack(deja ? Items.WRITTEN_BOOK : Items.WRITABLE_BOOK),
                    Icons.label(r.titre, deja ? ChatFormatting.DARK_GRAY : ChatFormatting.WHITE),
                    Icons.lore(deja ? "Tu as deja vote" : "Tu ne t'es pas encore prononce",
                            deja ? ChatFormatting.DARK_GRAY : ChatFormatting.GREEN),
                    sp -> openBulletin(sp, r.id)));
        }
        OwoMenuServer.openHub(player, Icons.screenTitle("Referendums", ChatFormatting.GOLD),
                List.of(Icons.lore(ouverts.size() + " consultation(s) en cours.", ChatFormatting.GRAY)),
                entries, ReferendumMenus::openVote, null);
    }

    /** Le bulletin : l'intitule en entier, et deux boutons. */
    public static void openBulletin(ServerPlayer player, String id) {
        ReferendumData data = ReferendumData.get(player.server);
        ReferendumData.Referendum r = data.get(id);
        if (r == null || r.etat != ReferendumData.Etat.OUVERT) {
            player.sendSystemMessage(Messages.info("Ce referendum n'est plus ouvert."));
            return;
        }
        boolean deja = r.aVote(player.getUUID());

        List<Component> intitule = new ArrayList<>();
        for (String ligne : enLignesEcran(r.lignes)) {
            intitule.add(valeur(ligne, ChatFormatting.WHITE));
        }
        if (deja) {
            boolean pour = Boolean.TRUE.equals(r.votes.get(player.getUUID()));
            intitule.add(Component.literal("Tu as vote ")
                    .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
                    .append(valeur(pour ? "POUR" : "CONTRE",
                            pour ? ChatFormatting.GREEN : ChatFormatting.RED)));
            intitule.add(Icons.lore("Tu peux changer d'avis tant que le vote est ouvert.",
                    ChatFormatting.DARK_GRAY));
        }

        List<OwoMenuServer.HubEntry> entries = List.of(
                new OwoMenuServer.HubEntry(new ItemStack(Items.LIME_DYE),
                        Icons.label("POUR", ChatFormatting.GREEN),
                        Icons.lore("Je suis pour", ChatFormatting.GRAY),
                        sp -> voter(sp, id, true)),
                new OwoMenuServer.HubEntry(new ItemStack(Items.RED_DYE),
                        Icons.label("CONTRE", ChatFormatting.RED),
                        Icons.lore("Je suis contre", ChatFormatting.GRAY),
                        sp -> voter(sp, id, false)));

        OwoMenuServer.openHub(player, Icons.screenTitle(r.titre, ChatFormatting.GOLD),
                intitule, entries, sp -> openBulletin(sp, id), null);
    }

    private static void voter(ServerPlayer player, String id, boolean pour) {
        ReferendumData data = ReferendumData.get(player.server);
        ReferendumData.Referendum r = data.get(id);
        // On relit l'etat au moment du clic : l'ecran a pu rester ouvert apres la cloture.
        if (r == null || r.etat != ReferendumData.Etat.OUVERT) {
            player.sendSystemMessage(Messages.warn("Ce referendum vient d'etre clos."));
            Menus.close(player);
            return;
        }
        r.votes.put(player.getUUID(), pour);
        r.noms.put(player.getUUID(), player.getGameProfile().getName());
        data.setDirty();
        player.sendSystemMessage(Messages.success("Ton vote est enregistre : "
                + (pour ? "POUR" : "CONTRE") + "."));
        Menus.close(player);
    }
}
