package com.utopia.table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * La roulette : un tirage toutes les minutes, et d'ici la chacun mise ce qu'il veut, ou il veut,
 * autant de fois qu'il veut.
 *
 * <p>Le tour ne s'arrete jamais tant qu'il reste quelqu'un a table. C'est ce qui distingue la
 * roulette des deux autres jeux : personne n'attend personne, et une mise posee a la derniere
 * seconde compte comme les autres.
 */
public final class RouletteTable implements Salle {

    /** Le tour de roue, en millisecondes. */
    private static final long TOUR_MS = 60_000L;
    /** Mises retenues au maximum, toutes personnes confondues. */
    private static final int MAX_MISES = 60;
    /**
     * Mises par personne et par tour. Chacune occupe une ligne d'ecran, et un panneau ne tient que
     * cinquante lignes : au-dela, les dernieres seraient tronquees sans que personne ne le voie.
     */
    private static final int MAX_MISES_PAR_JOUEUR = 15;

    /** Une mise posee sur le tapis. */
    private record Mise(UUID joueur, String nom, String libelle, java.util.Set<Integer> numeros,
                        int rapport, long montant) {
    }

    private final List<Mise> mises = new ArrayList<>();
    private final Random alea = new Random();
    private long prochainTirage;
    private int dernierNumero = -1;

    // ------------------------------------------------------------------ Le tour de roue

    @Override
    public void tick(MinecraftServer server, TableData.Table table) {
        long maintenant = System.currentTimeMillis();
        if (TableManager.places(table).isEmpty() && mises.isEmpty()) {
            prochainTirage = 0; // table deserte : la roue s'arrete plutot que de tourner pour rien
            return;
        }
        if (prochainTirage == 0) {
            prochainTirage = maintenant + TOUR_MS;
            return;
        }
        if (maintenant < prochainTirage) {
            return;
        }
        tirer(server, table);
        prochainTirage = maintenant + TOUR_MS;
    }

    /** Secondes avant le prochain tirage, ou -1 si la roue ne tourne pas. */
    public int secondesRestantes() {
        if (prochainTirage == 0) {
            return -1;
        }
        return (int) Math.max(0, (prochainTirage - System.currentTimeMillis() + 999) / 1000);
    }

    private void tirer(MinecraftServer server, TableData.Table table) {
        int numero = alea.nextInt(37);
        dernierNumero = numero;
        // La liste est copiee puis videe avant tout versement : un paiement qui rouvrirait un
        // ecran ne doit pas pouvoir faire resoudre deux fois les memes mises.
        List<Mise> tour = new ArrayList<>(mises);
        mises.clear();

        Map<UUID, long[]> bilan = new LinkedHashMap<>(); // [mise totale, retour total]
        for (Mise m : tour) {
            long gain = m.numeros().contains(numero) ? m.montant() * (m.rapport() + 1L) : 0L;
            TableManager.solder(server, table, m.joueur(), m.montant(), gain);
            long[] b = bilan.computeIfAbsent(m.joueur(), k -> new long[2]);
            b[0] += m.montant();
            b[1] += gain;
        }

        Component annonce = Component.literal(table.nom() + " : " + Roulette.decrire(numero))
                .withStyle(s -> s.withColor(numero == 0 ? ChatFormatting.GREEN
                        : Roulette.rouge(numero) ? ChatFormatting.RED : ChatFormatting.GRAY)
                        .withBold(true).withItalic(false));
        for (UUID id : TableManager.places(table)) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(id);
            if (joueur == null) {
                continue;
            }
            joueur.sendSystemMessage(Component.empty().append(Messages.PREFIX).append(annonce));
            long[] b = bilan.get(id);
            if (b != null) {
                long delta = b[1] - b[0];
                joueur.sendSystemMessage(delta > 0
                        ? Messages.success("Tu gagnes " + delta + " Utopiece(s).")
                        : delta == 0 ? Messages.info("Tu retrouves ta mise.")
                        : Messages.warn("Tu perds " + (-delta) + " Utopiece(s)."));
            }
            ouvrir(joueur, table);
        }
    }

    @Override
    public void quitte(MinecraftServer server, TableData.Table table, UUID joueur) {
        rendre(server, table, joueur);
    }

    @Override
    public boolean dormante() {
        return mises.isEmpty();
    }

    @Override
    public void toutRembourser(MinecraftServer server, TableData.Table table) {
        for (Mise m : new ArrayList<>(mises)) {
            TableManager.rembourser(server, m.joueur(), m.montant());
        }
        mises.clear();
        prochainTirage = 0;
    }

    private void rendre(MinecraftServer server, TableData.Table table, UUID joueur) {
        long total = 0;
        for (Mise m : new ArrayList<>(mises)) {
            if (m.joueur().equals(joueur)) {
                mises.remove(m);
                TableManager.rembourser(server, joueur, m.montant());
                total += m.montant();
            }
        }
        ServerPlayer p = server.getPlayerList().getPlayer(joueur);
        if (p != null && total > 0) {
            p.sendSystemMessage(Messages.info(total + " Utopiece(s) rendue(s) : tes mises sont retirees."));
        }
    }

    // ------------------------------------------------------------------ L'ecran

    public static void ouvrir(ServerPlayer joueur, TableData.Table table) {
        RouletteTable salle = (RouletteTable) TableManager.salle(table);
        String ferme = TableManager.pourquoiFermee(joueur.server, table);
        if (ferme != null) {
            joueur.sendSystemMessage(Messages.warn(ferme));
            return;
        }
        if (!TableManager.asseoir(joueur, table)) {
            return;
        }

        List<OwoMenuServer.PanelRow> lignes = new ArrayList<>();
        int reste = salle.secondesRestantes();
        lignes.add(new OwoMenuServer.PanelRow(
                Icons.label("Prochain tirage", ChatFormatting.AQUA),
                Icons.label(reste < 0 ? "en attente de mises" : "dans " + reste + " s",
                        reste >= 0 && reste <= 10 ? ChatFormatting.RED : ChatFormatting.WHITE),
                null, null));
        lignes.add(new OwoMenuServer.PanelRow(
                Icons.label("Dernier numero", ChatFormatting.GRAY),
                Icons.label(salle.dernierNumero < 0 ? "-" : Roulette.decrire(salle.dernierNumero),
                        salle.dernierNumero < 0 ? ChatFormatting.DARK_GRAY
                                : salle.dernierNumero == 0 ? ChatFormatting.GREEN
                                : Roulette.rouge(salle.dernierNumero) ? ChatFormatting.RED
                                : ChatFormatting.WHITE),
                null, null));

        long engage = 0;
        for (Mise m : salle.mises) {
            if (m.joueur().equals(joueur.getUUID())) {
                engage += m.montant();
                lignes.add(new OwoMenuServer.PanelRow(
                        Icons.label(m.libelle(), ChatFormatting.WHITE),
                        Icons.label(m.montant() + " (x" + (m.rapport() + 1) + ")", ChatFormatting.GOLD),
                        Icons.label("Retirer", ChatFormatting.RED),
                        sp -> {
                            salle.mises.remove(m);
                            TableManager.rembourser(sp.server, sp.getUUID(), m.montant());
                            sp.sendSystemMessage(Messages.info("Mise retiree, "
                                    + m.montant() + " Utopiece(s) rendue(s)."));
                            ouvrir(sp, table);
                        }));
            }
        }
        if (engage == 0) {
            lignes.add(new OwoMenuServer.PanelRow(
                    Icons.label("Tes mises", ChatFormatting.DARK_GRAY),
                    Icons.label("aucune", ChatFormatting.DARK_GRAY), null, null));
        }

        long total = engage;
        List<OwoMenuServer.PanelAction> pied = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Miser", ChatFormatting.GREEN),
                        sp -> choisirFamille(sp, table)),
                new OwoMenuServer.PanelAction(Icons.label("Se lever", ChatFormatting.RED),
                        sp -> {
                            TableManager.lever(sp.server, sp.getUUID());
                            Menus.close(sp);
                        }));

        OwoMenuServer.openPanel(joueur,
                Icons.screenTitle(table.nom() + (total > 0 ? " - engage " + total : ""),
                        ChatFormatting.RED),
                lignes, pied, true, sp -> ouvrir(sp, table),
                sp -> {
                    TableManager.lever(sp.server, sp.getUUID());
                    Menus.close(sp);
                });
    }

    private static void choisirFamille(ServerPlayer joueur, TableData.Table table) {
        List<OwoMenuServer.HubEntry> entrees = new ArrayList<>();
        for (Roulette.Famille f : Roulette.Famille.values()) {
            entrees.add(new OwoMenuServer.HubEntry(new ItemStack(Items.GOLD_NUGGET),
                    Icons.label(f.label + " - paie " + f.rapport + " contre 1", ChatFormatting.GOLD),
                    Icons.lore(f.detail, ChatFormatting.GRAY),
                    sp -> choisirCible(sp, table, f, 0)));
        }
        OwoMenuServer.openHub(joueur, Icons.screenTitle("Miser", ChatFormatting.RED),
                List.of(Icons.lore("Mise entre " + table.miseMin + " et " + table.miseMax
                        + " Utopieces.", ChatFormatting.GRAY)),
                entrees, sp -> choisirFamille(sp, table), sp -> ouvrir(sp, table));
    }

    private static void choisirCible(ServerPlayer joueur, TableData.Table table,
            Roulette.Famille famille, int page) {
        List<OwoMenuServer.HubEntry> entrees = new ArrayList<>();
        for (Roulette.Choix c : Roulette.choix(famille)) {
            entrees.add(new OwoMenuServer.HubEntry(new ItemStack(Items.GOLD_NUGGET),
                    Icons.label(c.libelle(), ChatFormatting.WHITE),
                    Icons.lore(c.numeros().size() + " numero(s), paie " + c.rapport() + " contre 1",
                            ChatFormatting.GRAY),
                    sp -> montant(sp, table, c)));
        }
        OwoMenuServer.openHubPaged(joueur,
                Icons.screenTitle(famille.label, ChatFormatting.RED),
                List.of(Icons.lore(famille.detail, ChatFormatting.GRAY)),
                entrees, page, 28,
                (sp, p) -> choisirCible(sp, table, famille, p),
                sp -> choisirFamille(sp, table));
    }

    private static void montant(ServerPlayer joueur, TableData.Table table, Roulette.Choix choix) {
        Menus.promptAmount(joueur,
                Icons.label("Miser sur " + choix.libelle(), ChatFormatting.GOLD),
                List.of(Icons.lore("Paie " + choix.rapport() + " contre 1.", ChatFormatting.GRAY),
                        Icons.lore("La mise est retiree tout de suite ; tu peux la retirer du "
                                + "tapis avant le tirage.", ChatFormatting.DARK_GRAY)),
                Icons.label("Poser", ChatFormatting.GREEN),
                table.miseMin, table.miseMin, table.miseMax,
                valeur -> poser(joueur, table, choix, valeur));
    }

    private static void poser(ServerPlayer joueur, TableData.Table table, Roulette.Choix choix,
            long valeur) {
        RouletteTable salle = (RouletteTable) TableManager.salle(table);
        if (TableManager.pourquoiFermee(joueur.server, table) != null
                || !TableManager.estAssis(table, joueur.getUUID())) {
            joueur.sendSystemMessage(Messages.warn("Tu n'es plus a cette table."));
            return;
        }
        if (salle.mises.size() >= MAX_MISES) {
            joueur.sendSystemMessage(Messages.warn("Le tapis est plein, attends le tirage."));
            ouvrir(joueur, table);
            return;
        }
        int siennes = 0;
        for (Mise m : salle.mises) {
            if (m.joueur().equals(joueur.getUUID())) {
                siennes++;
            }
        }
        if (siennes >= MAX_MISES_PAR_JOUEUR) {
            joueur.sendSystemMessage(Messages.warn("Pas plus de " + MAX_MISES_PAR_JOUEUR
                    + " mises par tour."));
            ouvrir(joueur, table);
            return;
        }
        if (!TableManager.engager(joueur, table, valeur)) {
            ouvrir(joueur, table);
            return;
        }
        salle.mises.add(new Mise(joueur.getUUID(), joueur.getGameProfile().getName(),
                choix.libelle(), choix.numeros(), choix.rapport(), valeur));
        if (salle.prochainTirage == 0) {
            salle.prochainTirage = System.currentTimeMillis() + TOUR_MS;
        }
        joueur.sendSystemMessage(Messages.success(valeur + " sur " + choix.libelle() + "."));
        ouvrir(joueur, table);
    }
}
