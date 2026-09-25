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
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Le blackjack, chacun son tour et dans l'ordre des places, comme a un vrai tapis.
 *
 * <p>Qui dit tour de table dit joueur qui s'absente. Chaque tour a donc un delai au bout duquel la
 * main reste d'office : sans lui, une table entiere attendrait indefiniment quelqu'un parti manger.
 *
 * <p>Le croupier s'arrete a 17, y compris sur un 17 mou. C'est la regle la plus favorable au joueur
 * des deux qui existent, et celle qu'on attend d'une maison qui prend deja son avantage ailleurs.
 */
public final class BlackjackTable implements Salle {

    /** Temps laisse pour miser une fois la premiere mise posee. */
    private static final long MISES_MS = 20_000L;
    /** Temps laisse a chacun pour jouer sa main. */
    private static final long TOUR_MS = 20_000L;
    /** Temps d'affichage du resultat avant la main suivante. */
    private static final long RESULTAT_MS = 8_000L;

    private enum Phase { MISES, JEU, CROUPIER, RESULTAT }

    /** La main de quelqu'un pendant une donne. */
    private static final class Main {
        final List<Cartes.Carte> cartes = new ArrayList<>();
        long mise;
        boolean doublee;
        boolean terminee;
        String resultat = "";
    }

    private final Cartes.Sabot sabot = new Cartes.Sabot(6);
    private final Map<UUID, Main> mains = new LinkedHashMap<>();
    private final List<Cartes.Carte> croupier = new ArrayList<>();
    private final List<UUID> ordre = new ArrayList<>();

    private Phase phase = Phase.MISES;
    private int tour = -1;
    private long echeance;

    // ------------------------------------------------------------------ Le deroulement

    @Override
    public void tick(MinecraftServer server, TableData.Table table) {
        long maintenant = System.currentTimeMillis();
        switch (phase) {
            case MISES -> {
                if (echeance > 0 && maintenant >= echeance) {
                    distribuer(server, table);
                }
            }
            case JEU -> {
                if (maintenant >= echeance) {
                    UUID actuel = joueurDuTour();
                    if (actuel != null) {
                        Main m = mains.get(actuel);
                        if (m != null) {
                            m.terminee = true;
                        }
                        ServerPlayer p = server.getPlayerList().getPlayer(actuel);
                        if (p != null) {
                            p.sendSystemMessage(Messages.warn("Temps ecoule : ta main reste."));
                        }
                    }
                    avancer(server, table);
                }
            }
            case CROUPIER -> jouerCroupier(server, table);
            case RESULTAT -> {
                if (maintenant >= echeance) {
                    nouvelleDonne(server, table);
                }
            }
        }
    }

    private UUID joueurDuTour() {
        return tour >= 0 && tour < ordre.size() ? ordre.get(tour) : null;
    }

    private void distribuer(MinecraftServer server, TableData.Table table) {
        ordre.clear();
        for (UUID id : TableManager.places(table)) {
            Main m = mains.get(id);
            if (m != null && m.mise > 0) {
                ordre.add(id);
            }
        }
        if (ordre.isEmpty()) {
            echeance = 0;
            return; // personne n'a mise : on reste en phase de mises
        }
        sabot.remelangerSiEntame();
        croupier.clear();
        for (UUID id : ordre) {
            mains.get(id).cartes.addAll(sabot.tirer(2));
        }
        croupier.addAll(sabot.tirer(2));
        phase = Phase.JEU;
        tour = -1;
        avancer(server, table);
    }

    /** Passe au joueur suivant qui a encore quelque chose a decider. */
    private void avancer(MinecraftServer server, TableData.Table table) {
        while (true) {
            tour++;
            if (tour >= ordre.size()) {
                phase = Phase.CROUPIER;
                echeance = 0;
                rafraichir(server, table);
                return;
            }
            Main m = mains.get(ordre.get(tour));
            if (m == null) {
                continue;
            }
            // Un blackjack ou un 21 servi n'a rien a decider : on ne fait pas patienter la table
            // pour une main qui ne peut plus bouger.
            if (m.terminee || Cartes.total(m.cartes) >= 21) {
                m.terminee = true;
                continue;
            }
            echeance = System.currentTimeMillis() + TOUR_MS;
            rafraichir(server, table);
            return;
        }
    }

    private void jouerCroupier(MinecraftServer server, TableData.Table table) {
        // Le croupier ne tire que si quelqu'un est encore en course : inutile de servir une table
        // ou tout le monde a saute, et cela evite de lui donner un blackjack pour rien.
        boolean quelquUnTient = false;
        for (UUID id : ordre) {
            Main m = mains.get(id);
            if (m != null && Cartes.total(m.cartes) <= 21) {
                quelquUnTient = true;
            }
        }
        if (quelquUnTient) {
            while (Cartes.total(croupier) < 17) {
                croupier.add(sabot.tirer());
            }
        }
        resoudre(server, table);
    }

    private void resoudre(MinecraftServer server, TableData.Table table) {
        int totalCroupier = Cartes.total(croupier);
        boolean bjCroupier = Cartes.blackjack(croupier);
        for (UUID id : ordre) {
            Main m = mains.get(id);
            if (m == null || m.mise <= 0) {
                continue;
            }
            int total = Cartes.total(m.cartes);
            boolean bj = Cartes.blackjack(m.cartes) && !m.doublee;
            long gain;
            if (total > 21) {
                gain = 0;
                m.resultat = "Saute a " + total;
            } else if (bj && !bjCroupier) {
                // Trois pour deux, arrondi au profit du joueur : une mise impaire ne doit pas lui
                // couter une demi-Utopiece au passage.
                gain = m.mise + (m.mise * 3 + 1) / 2;
                m.resultat = "Blackjack !";
            } else if (bjCroupier && !bj) {
                gain = 0;
                m.resultat = "Blackjack du croupier";
            } else if (bjCroupier) {
                gain = m.mise;
                m.resultat = "Egalite (deux blackjacks)";
            } else if (totalCroupier > 21) {
                gain = m.mise * 2;
                m.resultat = "Le croupier saute a " + totalCroupier;
            } else if (total > totalCroupier) {
                gain = m.mise * 2;
                m.resultat = total + " contre " + totalCroupier;
            } else if (total == totalCroupier) {
                gain = m.mise;
                m.resultat = "Egalite a " + total;
            } else {
                gain = 0;
                m.resultat = total + " contre " + totalCroupier;
            }
            TableManager.solder(server, table, id, m.mise, gain);
            long delta = gain - m.mise;
            // La mise est soldee : on l'efface aussitot. Sans cela, un arret du serveur pendant
            // l'affichage du resultat la rembourserait une seconde fois, et un depart aussi.
            m.mise = 0;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                p.sendSystemMessage(delta > 0 ? Messages.success(m.resultat + " : +" + delta)
                        : delta == 0 ? Messages.info(m.resultat + " : mise rendue")
                        : Messages.warn(m.resultat + " : -" + (-delta)));
            }
        }
        phase = Phase.RESULTAT;
        echeance = System.currentTimeMillis() + RESULTAT_MS;
        rafraichir(server, table);
    }

    private void nouvelleDonne(MinecraftServer server, TableData.Table table) {
        mains.clear();
        croupier.clear();
        ordre.clear();
        tour = -1;
        phase = Phase.MISES;
        echeance = 0;
        rafraichir(server, table);
    }

    private void rafraichir(MinecraftServer server, TableData.Table table) {
        for (UUID id : new ArrayList<>(TableManager.places(table))) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                ouvrir(p, table);
            }
        }
    }

    // ------------------------------------------------------------------ Les departs

    @Override
    public void quitte(MinecraftServer server, TableData.Table table, UUID joueur) {
        Main m = mains.remove(joueur);
        ordre.remove(joueur);
        if (m == null || m.mise <= 0) {
            return;
        }
        if (phase == Phase.MISES) {
            TableManager.rembourser(server, joueur, m.mise); // rien n'a ete servi, rien n'est du
        } else {
            // La main etait commencee : partir vaut abandon, comme au tapis. Rendre ici
            // permettrait de voir ses cartes puis de se lever quand elles sont mauvaises.
            TableManager.solder(server, table, joueur, m.mise, 0);
        }
    }

    @Override
    public boolean dormante() {
        return phase == Phase.MISES && mains.isEmpty();
    }

    @Override
    public void toutRembourser(MinecraftServer server, TableData.Table table) {
        for (Map.Entry<UUID, Main> e : new LinkedHashMap<>(mains).entrySet()) {
            if (e.getValue().mise > 0) {
                TableManager.rembourser(server, e.getKey(), e.getValue().mise);
            }
        }
        mains.clear();
        croupier.clear();
        ordre.clear();
        tour = -1;
        phase = Phase.MISES;
        echeance = 0;
    }

    // ------------------------------------------------------------------ L'ecran

    private static String rendre(List<Cartes.Carte> cartes, boolean cacherLaSeconde) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cartes.size(); i++) {
            sb.append(i > 0 ? " " : "");
            sb.append(cacherLaSeconde && i == 1 ? "[?]" : cartes.get(i).libelle());
        }
        return sb.toString();
    }

    public static void ouvrir(ServerPlayer joueur, TableData.Table table) {
        BlackjackTable salle = (BlackjackTable) TableManager.salle(table);
        String ferme = TableManager.pourquoiFermee(joueur.server, table);
        if (ferme != null) {
            joueur.sendSystemMessage(Messages.warn(ferme));
            return;
        }
        if (!TableManager.asseoir(joueur, table)) {
            return;
        }
        UUID moi = joueur.getUUID();
        Main maMain = salle.mains.get(moi);
        boolean aMoi = moi.equals(salle.joueurDuTour()) && salle.phase == Phase.JEU;

        List<OwoMenuServer.PanelRow> lignes = new ArrayList<>();
        boolean cache = salle.phase == Phase.JEU;
        lignes.add(new OwoMenuServer.PanelRow(
                Icons.label("Croupier", ChatFormatting.GOLD),
                Icons.label(salle.croupier.isEmpty() ? "-" : rendre(salle.croupier, cache)
                        + (cache ? "" : "  = " + Cartes.total(salle.croupier)),
                        ChatFormatting.WHITE), null, null));

        for (UUID id : TableManager.places(table)) {
            Main m = salle.mains.get(id);
            String nom = nomDe(joueur, id);
            boolean sonTour = id.equals(salle.joueurDuTour()) && salle.phase == Phase.JEU;
            String valeur;
            if (m == null || m.mise <= 0) {
                valeur = salle.phase == Phase.MISES ? "n'a pas encore mise" : "ne joue pas ce tour";
            } else if (m.cartes.isEmpty()) {
                valeur = "mise " + m.mise;
            } else {
                valeur = rendre(m.cartes, false) + "  = " + Cartes.total(m.cartes)
                        + (m.resultat.isEmpty() ? "" : "  " + m.resultat);
            }
            lignes.add(new OwoMenuServer.PanelRow(
                    Icons.label((sonTour ? "> " : "") + nom + (id.equals(moi) ? " (toi)" : ""),
                            sonTour ? ChatFormatting.YELLOW
                                    : id.equals(moi) ? ChatFormatting.AQUA : ChatFormatting.GRAY),
                    Icons.label(valeur, ChatFormatting.WHITE), null, null));
        }

        List<OwoMenuServer.PanelAction> pied = new ArrayList<>();
        if (salle.phase == Phase.MISES && (maMain == null || maMain.mise <= 0)) {
            pied.add(new OwoMenuServer.PanelAction(Icons.label("Miser", ChatFormatting.GREEN),
                    sp -> Menus.promptAmount(sp, Icons.label("Ta mise", ChatFormatting.GOLD),
                            List.of(Icons.lore("Entre " + table.miseMin + " et " + table.miseMax
                                    + " Utopieces.", ChatFormatting.GRAY)),
                            Icons.label("Miser", ChatFormatting.GREEN),
                            table.miseMin, table.miseMin, table.miseMax,
                            v -> miser(sp, table, v))));
        }
        if (aMoi && maMain != null) {
            pied.add(new OwoMenuServer.PanelAction(Icons.label("Tirer", ChatFormatting.GREEN),
                    sp -> tirer(sp, table)));
            pied.add(new OwoMenuServer.PanelAction(Icons.label("Rester", ChatFormatting.YELLOW),
                    sp -> rester(sp, table)));
            if (maMain.cartes.size() == 2 && !maMain.doublee) {
                pied.add(new OwoMenuServer.PanelAction(Icons.label("Doubler", ChatFormatting.GOLD),
                        sp -> doubler(sp, table)));
            }
        }
        pied.add(new OwoMenuServer.PanelAction(Icons.label("Se lever", ChatFormatting.RED),
                sp -> {
                    TableManager.lever(sp.server, sp.getUUID());
                    Menus.close(sp);
                }));

        String etat = switch (salle.phase) {
            case MISES -> salle.echeance > 0
                    ? "Mises ouvertes - " + secondes(salle.echeance) + " s"
                    : "Mises ouvertes";
            case JEU -> aMoi ? "A toi - " + secondes(salle.echeance) + " s"
                    : "Au tour de " + nomDe(joueur, salle.joueurDuTour());
            case CROUPIER -> "Le croupier joue";
            case RESULTAT -> "Fin de la main";
        };

        OwoMenuServer.openPanel(joueur,
                Icons.screenTitle(table.nom() + " - " + etat, ChatFormatting.GREEN),
                lignes, pied, true, sp -> ouvrir(sp, table),
                sp -> {
                    TableManager.lever(sp.server, sp.getUUID());
                    Menus.close(sp);
                });
    }

    private static int secondes(long echeance) {
        return (int) Math.max(0, (echeance - System.currentTimeMillis() + 999) / 1000);
    }

    private static String nomDe(ServerPlayer source, UUID id) {
        if (id == null) {
            return "-";
        }
        ServerPlayer p = source.server.getPlayerList().getPlayer(id);
        return p != null ? p.getGameProfile().getName() : "un joueur";
    }

    // ------------------------------------------------------------------ Les actions

    private static void miser(ServerPlayer joueur, TableData.Table table, long valeur) {
        BlackjackTable salle = (BlackjackTable) TableManager.salle(table);
        if (salle.phase != Phase.MISES || !TableManager.estAssis(table, joueur.getUUID())) {
            joueur.sendSystemMessage(Messages.warn("Les mises sont fermees."));
            ouvrir(joueur, table);
            return;
        }
        Main deja = salle.mains.get(joueur.getUUID());
        if (deja != null && deja.mise > 0) {
            ouvrir(joueur, table);
            return; // deja mise : le second clic ne doit rien retirer de plus
        }
        if (!TableManager.engager(joueur, table, valeur)) {
            ouvrir(joueur, table);
            return;
        }
        Main m = new Main();
        m.mise = valeur;
        salle.mains.put(joueur.getUUID(), m);
        if (salle.echeance == 0) {
            salle.echeance = System.currentTimeMillis() + MISES_MS;
        }
        salle.rafraichir(joueur.server, table);
    }

    private static Main mainJouable(ServerPlayer joueur, TableData.Table table) {
        BlackjackTable salle = (BlackjackTable) TableManager.salle(table);
        if (salle.phase != Phase.JEU || !joueur.getUUID().equals(salle.joueurDuTour())) {
            joueur.sendSystemMessage(Messages.warn("Ce n'est pas ton tour."));
            ouvrir(joueur, table);
            return null;
        }
        Main m = salle.mains.get(joueur.getUUID());
        if (m == null || m.terminee) {
            ouvrir(joueur, table);
            return null;
        }
        return m;
    }

    private static void tirer(ServerPlayer joueur, TableData.Table table) {
        BlackjackTable salle = (BlackjackTable) TableManager.salle(table);
        Main m = mainJouable(joueur, table);
        if (m == null) {
            return;
        }
        m.cartes.add(salle.sabot.tirer());
        if (Cartes.total(m.cartes) >= 21) {
            m.terminee = true;
            salle.avancer(joueur.server, table);
        } else {
            salle.echeance = System.currentTimeMillis() + TOUR_MS;
            salle.rafraichir(joueur.server, table);
        }
    }

    private static void rester(ServerPlayer joueur, TableData.Table table) {
        BlackjackTable salle = (BlackjackTable) TableManager.salle(table);
        Main m = mainJouable(joueur, table);
        if (m == null) {
            return;
        }
        m.terminee = true;
        salle.avancer(joueur.server, table);
    }

    private static void doubler(ServerPlayer joueur, TableData.Table table) {
        BlackjackTable salle = (BlackjackTable) TableManager.salle(table);
        Main m = mainJouable(joueur, table);
        if (m == null) {
            return;
        }
        if (m.cartes.size() != 2 || m.doublee) {
            ouvrir(joueur, table);
            return;
        }
        if (!TableManager.engager(joueur, table, m.mise)) {
            ouvrir(joueur, table);
            return;
        }
        m.mise *= 2;
        m.doublee = true;
        m.cartes.add(salle.sabot.tirer());
        m.terminee = true; // doubler, c'est payer pour une carte et une seule
        salle.avancer(joueur.server, table);
    }
}
