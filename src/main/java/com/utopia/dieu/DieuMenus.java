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
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PAPER),
                Icons.label("Message a un joueur", ChatFormatting.AQUA),
                Icons.lore("Un message prive, qui n'a l'air de venir de personne",
                        ChatFormatting.GRAY),
                sp -> openJoueurs(sp, 0)));

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
}
