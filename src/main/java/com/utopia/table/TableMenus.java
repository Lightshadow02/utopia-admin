package com.utopia.table;

import java.util.ArrayList;
import java.util.List;

import com.utopia.casino.CasinoManager;
import com.utopia.data.TableData;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.net.OwoMenuServer;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Les tables vues du cote du gerant : en poser, les regler, leur donner un croupier. */
public final class TableMenus {

    private TableMenus() {
    }

    private static boolean refuse(ServerPlayer player) {
        if (CasinoManager.canManage(player)) {
            return false;
        }
        player.sendSystemMessage(Messages.error("Reserve aux gerants du casino."));
        return true;
    }

    /** Le point d'entree du joueur : clic droit sur une table. */
    public static void ouvrirPourJouer(ServerPlayer joueur, TableData.Table table) {
        switch (table.jeu) {
            case BLACKJACK -> BlackjackTable.ouvrir(joueur, table);
            case HOLDEM -> HoldemTable.ouvrir(joueur, table);
            case ROULETTE -> RouletteTable.ouvrir(joueur, table);
        }
    }

    // ------------------------------------------------------------------ La liste

    public static void open(ServerPlayer player) {
        open(player, 0);
    }

    public static void open(ServerPlayer player, int page) {
        if (refuse(player)) {
            return;
        }
        TableData data = TableData.get(player.server);
        List<OwoMenuServer.HubEntry> entrees = new ArrayList<>();
        for (TableData.Table t : data.tables()) {
            int assis = TableManager.places(t).size();
            String etat = t.aUnCroupier()
                    ? (TableManager.ouverte(player.server, t) ? "ouverte par " + t.croupierNom
                            : "fermee, " + t.croupierNom + " absent")
                    : "tenue par la maison";
            entrees.add(new OwoMenuServer.HubEntry(new ItemStack(t.jeu.icone),
                    Icons.label(t.nom(), t.jeu.couleur),
                    Icons.lore(t.jeu.label + " - " + assis + "/" + t.places + " places - " + etat,
                            ChatFormatting.GRAY),
                    sp -> openConfig(sp, t.key(), page)));
        }
        List<OwoMenuServer.HubEntry> epingles = List.of(new OwoMenuServer.HubEntry(
                new ItemStack(Items.OAK_SIGN),
                Icons.label("Poser une table", ChatFormatting.GREEN),
                Icons.lore("Choisis le jeu, puis casse le bloc qui portera la table",
                        ChatFormatting.GRAY),
                TableMenus::choisirJeu));

        OwoMenuServer.openHubPaged(player,
                Icons.screenTitle("Tables de jeu", ChatFormatting.GOLD),
                List.of(Icons.lore(data.tables().size() + " table(s) posee(s).", ChatFormatting.GRAY)),
                epingles, entrees, page, 28, TableMenus::open, com.utopia.casino.CasinoMenus::open);
    }

    private static void choisirJeu(ServerPlayer player) {
        if (refuse(player)) {
            return;
        }
        List<OwoMenuServer.HubEntry> entrees = new ArrayList<>();
        for (Jeu jeu : Jeu.values()) {
            entrees.add(new OwoMenuServer.HubEntry(new ItemStack(jeu.icone),
                    Icons.label(jeu.label, jeu.couleur),
                    Icons.lore(jeu.detail + " Jusqu'a " + jeu.placesMax + " places.",
                            ChatFormatting.GRAY),
                    sp -> {
                        TableManager.commencerPose(sp.getUUID(), jeu);
                        sp.sendSystemMessage(Messages.info("Mode actif : casse le bloc qui portera "
                                + "la table de " + jeu.label + "."));
                        Menus.close(sp);
                    }));
        }
        OwoMenuServer.openHub(player, Icons.screenTitle("Quelle table ?", ChatFormatting.GOLD),
                List.of(Icons.lore("Le bloc que tu casseras deviendra la table.", ChatFormatting.GRAY),
                        Icons.lore("N'importe quel bloc convient : les joueurs feront clic droit "
                                + "dessus pour s'asseoir.", ChatFormatting.DARK_GRAY)),
                entrees, TableMenus::choisirJeu, TableMenus::open);
    }

    // ------------------------------------------------------------------ Le reglage

    public static void openConfig(ServerPlayer player, String key, int page) {
        if (refuse(player)) {
            return;
        }
        TableData data = TableData.get(player.server);
        TableData.Table t = data.byKey(key);
        if (t == null) {
            player.sendSystemMessage(Messages.warn("Cette table n'existe plus."));
            open(player, page);
            return;
        }

        List<OwoMenuServer.PanelRow> lignes = new ArrayList<>();
        lignes.add(ligne("Jeu", t.jeu.label, null, null));
        lignes.add(ligne("Position", t.x + " " + t.y + " " + t.z, null, null));
        lignes.add(ligne("Nom affiche", t.label.isBlank() ? "(le nom du jeu)" : t.label, "Changer",
                sp -> Menus.promptFreeText(sp, Icons.label("Nom de la table", ChatFormatting.GOLD),
                        List.of(Icons.lore("Vide = le nom du jeu.", ChatFormatting.GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN), t.label, 32,
                        v -> {
                            t.label = v == null ? "" : v.trim();
                            data.setDirty();
                            openConfig(sp, key, page);
                        })));
        lignes.add(ligne("Places", t.places + " sur " + t.jeu.placesMax, "Changer",
                sp -> Menus.promptAmount(sp, Icons.label("Nombre de places", ChatFormatting.GOLD),
                        List.of(Icons.lore("De 1 a " + t.jeu.placesMax + " pour ce jeu.",
                                ChatFormatting.GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN),
                        t.places, 1, t.jeu.placesMax,
                        v -> {
                            t.places = (int) v;
                            data.setDirty();
                            openConfig(sp, key, page);
                        })));
        lignes.add(ligne("Mise minimum", String.valueOf(t.miseMin), "Changer",
                sp -> Menus.promptAmount(sp, Icons.label("Mise minimum", ChatFormatting.GOLD),
                        List.of(), Icons.label("Valider", ChatFormatting.GREEN),
                        t.miseMin, 1, Math.max(1, t.miseMax),
                        v -> {
                            t.miseMin = v;
                            data.setDirty();
                            openConfig(sp, key, page);
                        })));
        lignes.add(ligne("Mise maximum", String.valueOf(t.miseMax), "Changer",
                sp -> Menus.promptAmount(sp, Icons.label("Mise maximum", ChatFormatting.GOLD),
                        List.of(Icons.lore("Au Hold'em, l'ante et son suivi doivent tenir dessous.",
                                ChatFormatting.DARK_GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN),
                        t.miseMax, t.miseMin, 1_000_000,
                        v -> {
                            t.miseMax = v;
                            data.setDirty();
                            openConfig(sp, key, page);
                        })));
        lignes.add(ligne("Croupier",
                t.aUnCroupier() ? t.croupierNom : "aucun (la maison tient la table)",
                t.aUnCroupier() ? "Retirer" : "Nommer",
                sp -> {
                    if (t.aUnCroupier()) {
                        t.croupier = null;
                        t.croupierNom = "";
                        data.setDirty();
                        openConfig(sp, key, page);
                    } else {
                        choisirCroupier(sp, key, page);
                    }
                }));
        if (t.aUnCroupier()) {
            lignes.add(ligne("Commission", t.commission + " % des mises", "Changer",
                    sp -> Menus.promptAmount(sp, Icons.label("Commission du croupier",
                                    ChatFormatting.GOLD),
                            List.of(Icons.lore("Part des mises qui lui revient, jusqu'a 20 %.",
                                    ChatFormatting.GRAY)),
                            Icons.label("Valider", ChatFormatting.GREEN), t.commission, 0, 20,
                            v -> {
                                t.commission = (int) v;
                                data.setDirty();
                                openConfig(sp, key, page);
                            })));
        }
        lignes.add(ligne("Assis en ce moment", TableManager.places(t).size() + " joueur(s)", null, null));

        List<OwoMenuServer.PanelAction> pied = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Retirer la table", ChatFormatting.RED),
                        sp -> OwoMenuServer.openConfirm(sp,
                                Icons.label("Retirer " + t.nom() + " ?", ChatFormatting.RED),
                                List.of(Icons.lore("Les mises en cours seront rendues.",
                                        ChatFormatting.GRAY)),
                                Icons.label("Retirer", ChatFormatting.RED),
                                confirm -> {
                                    TableManager.oublier(confirm.server, t);
                                    TableData.get(confirm.server).remove(key);
                                    confirm.sendSystemMessage(Messages.success("Table retiree."));
                                    open(confirm, page);
                                },
                                annule -> openConfig(annule, key, page))));

        OwoMenuServer.openPanel(player, Icons.screenTitle(t.nom(), t.jeu.couleur),
                lignes, pied, false, sp -> openConfig(sp, key, page), sp -> open(sp, page));
    }

    private static void choisirCroupier(ServerPlayer player, String key, int page) {
        if (refuse(player)) {
            return;
        }
        List<OwoMenuServer.HubEntry> entrees = new ArrayList<>();
        for (ServerPlayer candidat : player.server.getPlayerList().getPlayers()) {
            entrees.add(new OwoMenuServer.HubEntry(
                    Icons.playerHead(candidat, Icons.label(candidat.getGameProfile().getName(),
                            ChatFormatting.WHITE), List.of()),
                    Icons.label(candidat.getGameProfile().getName(), ChatFormatting.WHITE),
                    Icons.lore("Il tiendra la table ; elle fermera quand il partira",
                            ChatFormatting.GRAY),
                    sp -> {
                        TableData data = TableData.get(sp.server);
                        TableData.Table t = data.byKey(key);
                        if (t != null) {
                            t.croupier = candidat.getUUID();
                            t.croupierNom = candidat.getGameProfile().getName();
                            data.setDirty();
                        }
                        openConfig(sp, key, page);
                    }));
        }
        OwoMenuServer.openHubPaged(player,
                Icons.screenTitle("Qui tient la table ?", ChatFormatting.GOLD),
                List.of(Icons.lore("Seuls les joueurs connectes sont proposes.", ChatFormatting.GRAY),
                        Icons.lore("Une table a croupier ne tourne que quand il est la.",
                                ChatFormatting.DARK_GRAY)),
                entrees, 0, 28, (sp, p) -> choisirCroupier(sp, key, page),
                sp -> openConfig(sp, key, page));
    }

    private static OwoMenuServer.PanelRow ligne(String label, String valeur, String bouton,
            java.util.function.Consumer<ServerPlayer> action) {
        return new OwoMenuServer.PanelRow(
                Icons.label(label, ChatFormatting.AQUA),
                Icons.label(valeur, ChatFormatting.WHITE),
                bouton == null ? null : Icons.label(bouton, ChatFormatting.GREEN),
                action);
    }

    /** Le petit resume affiche a l'ouverture de /casino. */
    public static Component resume(ServerPlayer player) {
        int n = TableData.get(player.server).tables().size();
        return Icons.lore(n == 0 ? "Aucune table posee" : n + " table(s) - blackjack, Hold'em, roulette",
                ChatFormatting.GRAY);
    }
}
