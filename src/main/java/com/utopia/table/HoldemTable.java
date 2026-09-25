package com.utopia.table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.utopia.data.TableData;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.net.OwoMenuServer;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Le Casino Hold'em : la main de chacun contre celle du croupier, jamais contre celle des voisins.
 *
 * <p>C'est ce qui permet a une table de tourner avec un seul joueur a trois heures du matin. Chacun
 * avance a son rythme et n'attend personne ; les places ne servent qu'a limiter le monde autour du
 * tapis et a savoir qui regarde.
 *
 * <p>Le croupier ne se qualifie qu'avec une paire de quatre ou mieux. Quand il ne se qualifie pas,
 * l'ante paie et le suivi est rendu : c'est cette regle qui donne au jeu son interet, sans quoi il
 * suffirait de se coucher des qu'on n'a rien.
 */
public final class HoldemTable implements Salle {

    private enum Etat { DECISION, FIN }

    private static final class Main {
        /**
         * Chaque main a son paquet. Au Casino Hold'em on ne joue pas contre ses voisins : leur
         * donner un paquet commun ferait sortir la meme carte a deux tables a la fois, sans
         * qu'aucune regle du jeu ne l'interdise ni ne le justifie.
         */
        final Cartes.Sabot paquet = new Cartes.Sabot(1);
        long ante;
        long suivi;
        final List<Cartes.Carte> joueur = new ArrayList<>();
        final List<Cartes.Carte> croupier = new ArrayList<>();
        final List<Cartes.Carte> tapis = new ArrayList<>();
        Etat etat = Etat.DECISION;
        String resultat = "";
        String detailJoueur = "";
        String detailCroupier = "";
    }

    private final Map<UUID, Main> mains = new LinkedHashMap<>();

    @Override
    public void tick(MinecraftServer server, TableData.Table table) {
        // Rien a cadencer : personne n'attend personne, et une main sans decision reste en l'etat
        // jusqu'a ce que son joueur tranche ou quitte la table.
    }

    @Override
    public void quitte(MinecraftServer server, TableData.Table table, UUID joueur) {
        Main m = mains.remove(joueur);
        if (m == null) {
            return;
        }
        if (m.etat == Etat.DECISION) {
            // Partir apres avoir vu ses cartes vaut abandon : rendre l'ante ferait de la fuite la
            // meilleure strategie du jeu.
            TableManager.solder(server, table, joueur, m.ante, 0);
        }
    }

    @Override
    public boolean dormante() {
        return mains.isEmpty();
    }

    @Override
    public void toutRembourser(MinecraftServer server, TableData.Table table) {
        for (Map.Entry<UUID, Main> e : new LinkedHashMap<>(mains).entrySet()) {
            Main m = e.getValue();
            if (m.etat == Etat.DECISION) {
                TableManager.rembourser(server, e.getKey(), m.ante);
            }
        }
        mains.clear();
    }

    // ------------------------------------------------------------------ L'ecran

    private static String rendre(List<Cartes.Carte> cartes, int combien) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(combien, cartes.size()); i++) {
            sb.append(i > 0 ? " " : "").append(cartes.get(i).libelle());
        }
        for (int i = cartes.size(); i < combien; i++) {
            sb.append(i > 0 ? " " : "").append("[?]");
        }
        return sb.isEmpty() ? "-" : sb.toString();
    }

    public static void ouvrir(ServerPlayer joueur, TableData.Table table) {
        HoldemTable salle = (HoldemTable) TableManager.salle(table);
        String ferme = TableManager.pourquoiFermee(joueur.server, table);
        if (ferme != null) {
            joueur.sendSystemMessage(Messages.warn(ferme));
            return;
        }
        if (!TableManager.asseoir(joueur, table)) {
            return;
        }
        Main m = salle.mains.get(joueur.getUUID());
        boolean fin = m != null && m.etat == Etat.FIN;
        int cartesTapis = m == null ? 0 : (fin ? 5 : 3);

        List<OwoMenuServer.PanelRow> lignes = new ArrayList<>();
        lignes.add(new OwoMenuServer.PanelRow(
                Icons.label("Croupier", ChatFormatting.GOLD),
                Icons.label(m == null ? "-" : fin ? rendre(m.croupier, 2) + "  " + m.detailCroupier
                        : "[?] [?]", ChatFormatting.WHITE), null, null));
        lignes.add(new OwoMenuServer.PanelRow(
                Icons.label("Tapis", ChatFormatting.AQUA),
                Icons.label(m == null ? "-" : rendre(m.tapis, cartesTapis), ChatFormatting.WHITE),
                null, null));
        lignes.add(new OwoMenuServer.PanelRow(
                Icons.label("Ta main", ChatFormatting.GREEN),
                Icons.label(m == null ? "-" : rendre(m.joueur, 2)
                        + (m.detailJoueur.isEmpty() ? "" : "  " + m.detailJoueur),
                        ChatFormatting.WHITE), null, null));
        if (m != null) {
            lignes.add(new OwoMenuServer.PanelRow(
                    Icons.label("Engage", ChatFormatting.GRAY),
                    Icons.label("ante " + m.ante + (m.suivi > 0 ? " + suivi " + m.suivi : ""),
                            ChatFormatting.GOLD), null, null));
        }
        if (m != null && fin) {
            lignes.add(new OwoMenuServer.PanelRow(
                    Icons.label("Resultat", ChatFormatting.YELLOW),
                    Icons.label(m.resultat, ChatFormatting.WHITE), null, null));
        }
        lignes.add(new OwoMenuServer.PanelRow(
                Icons.label("L'ante paie", ChatFormatting.DARK_GRAY),
                Icons.label("quinte flush royale 100, quinte flush 20, carre 10, full 3, couleur 2",
                        ChatFormatting.DARK_GRAY), null, null));

        List<OwoMenuServer.PanelAction> pied = new ArrayList<>();
        if (m == null || fin) {
            pied.add(new OwoMenuServer.PanelAction(Icons.label("Nouvelle main", ChatFormatting.GREEN),
                    sp -> Menus.promptAmount(sp, Icons.label("Ton ante", ChatFormatting.GOLD),
                            List.of(Icons.lore("Le suivi coutera le double de l'ante.",
                                            ChatFormatting.GRAY),
                                    Icons.lore("Entre " + table.miseMin + " et " + (table.miseMax / 3)
                                            + " : il faut pouvoir suivre.", ChatFormatting.DARK_GRAY)),
                            Icons.label("Donner", ChatFormatting.GREEN),
                            table.miseMin, table.miseMin, Math.max(table.miseMin, table.miseMax / 3),
                            v -> donner(sp, table, v))));
        } else {
            pied.add(new OwoMenuServer.PanelAction(
                    Icons.label("Suivre (" + (m.ante * 2) + ")", ChatFormatting.GREEN),
                    sp -> suivre(sp, table)));
            pied.add(new OwoMenuServer.PanelAction(Icons.label("Se coucher", ChatFormatting.RED),
                    sp -> coucher(sp, table)));
        }
        pied.add(new OwoMenuServer.PanelAction(Icons.label("Se lever", ChatFormatting.RED),
                sp -> {
                    TableManager.lever(sp.server, sp.getUUID());
                    Menus.close(sp);
                }));

        OwoMenuServer.openPanel(joueur,
                Icons.screenTitle(table.nom(), ChatFormatting.GOLD),
                lignes, pied, true, sp -> ouvrir(sp, table),
                sp -> {
                    TableManager.lever(sp.server, sp.getUUID());
                    Menus.close(sp);
                });
    }

    // ------------------------------------------------------------------ Les actions

    private static void donner(ServerPlayer joueur, TableData.Table table, long ante) {
        HoldemTable salle = (HoldemTable) TableManager.salle(table);
        if (!TableManager.estAssis(table, joueur.getUUID())) {
            joueur.sendSystemMessage(Messages.warn("Tu n'es plus a cette table."));
            return;
        }
        Main deja = salle.mains.get(joueur.getUUID());
        if (deja != null && deja.etat == Etat.DECISION) {
            ouvrir(joueur, table);
            return; // une main est deja en cours : le second clic ne doit pas en payer une seconde
        }
        // Le suivi vaut deux antes : refuser d'entree plutot que de bloquer le joueur devant un
        // choix qu'il n'a pas les moyens de faire.
        if (ante * 3 > table.miseMax) {
            joueur.sendSystemMessage(Messages.warn("Ante trop forte : ante et suivi doivent tenir "
                    + "sous la mise maximum de " + table.miseMax + "."));
            return;
        }
        if (!TableManager.engager(joueur, table, ante)) {
            ouvrir(joueur, table);
            return;
        }
        Main m = new Main();
        m.ante = ante;
        m.joueur.addAll(m.paquet.tirer(2));
        m.croupier.addAll(m.paquet.tirer(2));
        m.tapis.addAll(m.paquet.tirer(5)); // les cinq sont tirees tout de suite, montrees ensuite
        salle.mains.put(joueur.getUUID(), m);
        ouvrir(joueur, table);
    }

    private static void coucher(ServerPlayer joueur, TableData.Table table) {
        HoldemTable salle = (HoldemTable) TableManager.salle(table);
        Main m = salle.mains.get(joueur.getUUID());
        if (m == null || m.etat != Etat.DECISION) {
            ouvrir(joueur, table);
            return;
        }
        TableManager.solder(joueur.server, table, joueur.getUUID(), m.ante, 0);
        salle.mains.remove(joueur.getUUID());
        joueur.sendSystemMessage(Messages.warn("Couche : tu perds ton ante de " + m.ante + "."));
        ouvrir(joueur, table);
    }

    private static void suivre(ServerPlayer joueur, TableData.Table table) {
        HoldemTable salle = (HoldemTable) TableManager.salle(table);
        Main m = salle.mains.get(joueur.getUUID());
        if (m == null || m.etat != Etat.DECISION) {
            ouvrir(joueur, table);
            return;
        }
        long suivi = m.ante * 2;
        if (!TableManager.engager(joueur, table, suivi)) {
            ouvrir(joueur, table);
            return;
        }
        m.suivi = suivi;
        m.etat = Etat.FIN;

        List<Cartes.Carte> septJoueur = new ArrayList<>(m.joueur);
        septJoueur.addAll(m.tapis);
        List<Cartes.Carte> septCroupier = new ArrayList<>(m.croupier);
        septCroupier.addAll(m.tapis);
        Cartes.Eval evalJoueur = Cartes.meilleure(septJoueur);
        Cartes.Eval evalCroupier = Cartes.meilleure(septCroupier);
        m.detailJoueur = evalJoueur.combinaison().label;
        m.detailCroupier = evalCroupier.combinaison().label;

        long[] gains = HoldemRegles.gains(evalJoueur, evalCroupier, m.ante, suivi);
        long gainAnte = gains[0];
        long gainSuivi = gains[1];
        m.resultat = HoldemRegles.resume(evalJoueur, evalCroupier);
        TableManager.solder(joueur.server, table, joueur.getUUID(), m.ante, gainAnte);
        TableManager.solder(joueur.server, table, joueur.getUUID(), suivi, gainSuivi);

        long delta = gainAnte + gainSuivi - m.ante - suivi;
        joueur.sendSystemMessage(delta > 0 ? Messages.success(m.resultat + " : +" + delta)
                : delta == 0 ? Messages.info(m.resultat)
                : Messages.warn(m.resultat + " : -" + (-delta)));
        ouvrir(joueur, table);
    }
}
