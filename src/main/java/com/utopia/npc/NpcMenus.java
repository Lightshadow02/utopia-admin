package com.utopia.npc;

import java.util.ArrayList;
import java.util.List;

import com.utopia.data.NpcData;
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

/** Gestion des statues decoratives depuis {@code /admin} : creation, visage, position, suppression. */
public final class NpcMenus {

    private static final int PAGE_SIZE = 12;
    /** Pas de rotation : huit orientations suffisent a placer une statue proprement. */
    private static final float TURN = 45.0f;

    private NpcMenus() {
    }

    // ==============================================================================================
    //  Liste
    // ==============================================================================================

    public static void open(ServerPlayer admin) {
        open(admin, 0);
    }

    public static void open(ServerPlayer admin, int page) {
        if (!admin.hasPermissions(2)) {
            admin.sendSystemMessage(Messages.warn("Reserve a l'administration."));
            return;
        }
        NpcData data = NpcData.get(admin.server);

        Component title = Icons.screenTitle("Statues", ChatFormatting.LIGHT_PURPLE);
        List<Component> stats = List.of(
                Icons.lore(data.all().size() + " statue(s) posee(s)", ChatFormatting.GRAY),
                Icons.lore("Elles gardent le visage copie, meme joueur parti.",
                        ChatFormatting.DARK_GRAY));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.ARMOR_STAND),
                Icons.label("Nouvelle statue", ChatFormatting.GREEN),
                Icons.lore("Posee a votre position, a votre effigie", ChatFormatting.GRAY),
                NpcMenus::promptCreate));
        for (NpcData.Npc npc : data.all()) {
            String id = npc.id;
            entries.add(new OwoMenuServer.HubEntry(
                    new ItemStack(npc.enabled ? Items.PLAYER_HEAD : Items.GRAY_DYE),
                    Icons.label(npc.name, npc.enabled ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY),
                    Icons.lore((npc.skinFrom.isBlank() ? "visage par defaut" : "visage de " + npc.skinFrom)
                                    + (npc.isPlaced()
                                            ? String.format(" - %.0f %.0f %.0f", npc.x, npc.y, npc.z)
                                            : " - non placee")
                                    + (npc.enabled ? "" : " - masquee"),
                            npc.isPlaced() ? ChatFormatting.GRAY : ChatFormatting.RED),
                    sp -> openNpc(sp, id)));
        }

        OwoMenuServer.openHubPaged(admin, title, stats, entries, page, PAGE_SIZE,
                NpcMenus::open, com.utopia.menu.AdminMenu::open);
    }

    private static void promptCreate(ServerPlayer admin) {
        Menus.promptFreeText(admin, Icons.title("Nom de la statue", ChatFormatting.GOLD),
                List.of(Icons.lore("Affiche au-dessus d'elle, et sert a la retrouver",
                        ChatFormatting.GRAY)),
                Icons.label("Poser ici", ChatFormatting.GREEN), admin.getGameProfile().getName(), 32,
                name -> {
                    if (name == null || name.isBlank()) {
                        admin.sendSystemMessage(Messages.warn("Nom vide."));
                        open(admin);
                        return;
                    }
                    NpcData data = NpcData.get(admin.server);
                    NpcData.Npc npc = data.create(name);
                    NpcManager.place(admin, npc);
                    // Par defaut elle prend le visage de qui la pose : c'est le cas le plus courant,
                    // et cela evite une statue Steve le temps de choisir un joueur.
                    NpcManager.copyFrom(admin, npc);
                    data.setDirty();
                    NpcManager.sync(admin.server);
                    admin.sendSystemMessage(Messages.success("Statue \"" + npc.name + "\" posee ici."));
                    openNpc(admin, npc.id);
                });
    }

    // ==============================================================================================
    //  Fiche
    // ==============================================================================================

    public static void openNpc(ServerPlayer admin, String id) {
        NpcData data = NpcData.get(admin.server);
        NpcData.Npc npc = data.get(id);
        if (npc == null) {
            open(admin);
            return;
        }
        Component title = Icons.title(npc.name, ChatFormatting.LIGHT_PURPLE);

        List<OwoMenuServer.PanelRow> rows = List.of(
                new OwoMenuServer.PanelRow(
                        Icons.label("Nom affiche", ChatFormatting.GRAY),
                        Icons.label(npc.name, ChatFormatting.WHITE),
                        Icons.label("Renommer", ChatFormatting.YELLOW),
                        sp -> Menus.promptFreeText(sp, Icons.title("Nouveau nom", ChatFormatting.GOLD),
                                List.of(), Icons.label("Valider", ChatFormatting.GREEN), npc.name, 32,
                                text -> {
                                    if (text != null && !text.isBlank()) {
                                        npc.name = text.trim();
                                        refresh(sp);
                                    }
                                    openNpc(sp, id);
                                })),
                new OwoMenuServer.PanelRow(
                        Icons.label("Visage", ChatFormatting.GRAY),
                        Icons.label(npc.skinFrom.isBlank() ? "par defaut" : "copie de " + npc.skinFrom,
                                npc.skinFrom.isBlank() ? ChatFormatting.DARK_GRAY : ChatFormatting.AQUA),
                        Icons.label("Changer", ChatFormatting.YELLOW),
                        sp -> openSkin(sp, id, 0)),
                new OwoMenuServer.PanelRow(
                        Icons.label("Etiquette", ChatFormatting.GRAY),
                        Icons.label(npc.showName ? "nom visible" : "nom masque",
                                npc.showName ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY),
                        Icons.label(npc.showName ? "Masquer" : "Afficher", ChatFormatting.YELLOW),
                        sp -> {
                            npc.showName = !npc.showName;
                            refresh(sp);
                            openNpc(sp, id);
                        }),
                new OwoMenuServer.PanelRow(
                        Icons.label("Position", ChatFormatting.GRAY),
                        Icons.label(npc.isPlaced()
                                ? String.format("%.1f %.1f %.1f", npc.x, npc.y, npc.z) : "non placee",
                                npc.isPlaced() ? ChatFormatting.WHITE : ChatFormatting.RED),
                        Icons.label("Placer ici", ChatFormatting.GREEN),
                        sp -> {
                            NpcManager.place(sp, npc);
                            refresh(sp);
                            sp.sendSystemMessage(Messages.success("Statue deplacee ici."));
                            openNpc(sp, id);
                        }),
                new OwoMenuServer.PanelRow(
                        Icons.label("Orientation", ChatFormatting.GRAY),
                        Icons.label(String.format("%.0f degres", normalize(npc.restYaw)),
                                ChatFormatting.AQUA),
                        Icons.label("Tourner", ChatFormatting.YELLOW),
                        sp -> {
                            npc.restYaw = normalize(npc.restYaw + TURN);
                            refresh(sp);
                            openNpc(sp, id);
                        }),
                new OwoMenuServer.PanelRow(
                        Icons.label("Affichage", ChatFormatting.GRAY),
                        Icons.label(npc.enabled ? "visible" : "masquee",
                                npc.enabled ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY),
                        Icons.label(npc.enabled ? "Masquer" : "Afficher", ChatFormatting.YELLOW),
                        sp -> {
                            npc.enabled = !npc.enabled;
                            refresh(sp);
                            openNpc(sp, id);
                        }));

        List<OwoMenuServer.PanelAction> footer = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Supprimer", ChatFormatting.RED),
                        sp -> {
                            NpcData d = NpcData.get(sp.server);
                            d.remove(id);
                            NpcManager.removeEntity(sp.server, id);
                            sp.sendSystemMessage(Messages.success("Statue supprimee."));
                            open(sp);
                        }));

        OwoMenuServer.openPanel(admin, title, rows, footer, sp -> openNpc(sp, id), NpcMenus::open);
    }

    // ==============================================================================================
    //  Visage
    // ==============================================================================================

    public static void openSkin(ServerPlayer admin, String id, int page) {
        MinecraftServer server = admin.server;
        NpcData.Npc npc = NpcData.get(server).get(id);
        if (npc == null) {
            open(admin);
            return;
        }
        Component title = Icons.title("Visage - " + npc.name, ChatFormatting.AQUA);
        List<Component> stats = List.of(
                Icons.lore(npc.skinFrom.isBlank() ? "Visage par defaut" : "Copie de " + npc.skinFrom,
                        ChatFormatting.GRAY),
                Icons.lore("Le visage est copie une fois pour toutes : le joueur peut partir.",
                        ChatFormatting.DARK_GRAY));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.NAME_TAG),
                Icons.label("Chercher un pseudo", ChatFormatting.GREEN),
                Icons.lore("Meme un joueur jamais venu, le visage est recupere en ligne",
                        ChatFormatting.GRAY),
                sp -> Menus.promptText(sp, Icons.title("Pseudo a copier", ChatFormatting.GOLD),
                        List.of(Icons.lore("Le pseudo Minecraft exact", ChatFormatting.GRAY)),
                        Icons.label("Copier", ChatFormatting.GREEN), "", 16,
                        pseudo -> {
                            sp.sendSystemMessage(Messages.info("Recherche du visage de \"" + pseudo
                                    + "\"..."));
                            NpcManager.fetchSkin(server, pseudo, npc, found -> {
                                if (found == null) {
                                    sp.sendSystemMessage(Messages.error("Visage introuvable pour \""
                                            + pseudo + "\"."));
                                } else {
                                    sp.sendSystemMessage(Messages.success("Visage de " + found
                                            + " copie."));
                                }
                                openSkin(sp, id, 0);
                            });
                        })));
        if (!npc.skinFrom.isBlank()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.BARRIER),
                    Icons.label("Retirer le visage", ChatFormatting.RED),
                    Icons.lore("Revient a l'apparence par defaut", ChatFormatting.GRAY),
                    sp -> {
                        npc.skinValue = "";
                        npc.skinSignature = "";
                        npc.skinFrom = "";
                        refresh(sp);
                        openSkin(sp, id, 0);
                    }));
        }
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            String pseudo = target.getGameProfile().getName();
            entries.add(new OwoMenuServer.HubEntry(
                    Icons.playerHead(target, Icons.label(pseudo, ChatFormatting.WHITE), List.of()),
                    Icons.label(pseudo, ChatFormatting.WHITE),
                    Icons.lore("Clic : copier ce visage", ChatFormatting.GRAY),
                    sp -> {
                        if (NpcManager.copyFrom(target, npc)) {
                            refresh(sp);
                            sp.sendSystemMessage(Messages.success("Visage de " + pseudo + " copie."));
                        } else {
                            sp.sendSystemMessage(Messages.warn("Ce joueur n'a pas de skin a copier "
                                    + "(serveur en mode hors ligne)."));
                        }
                        openSkin(sp, id, 0);
                    }));
        }

        OwoMenuServer.openHubPaged(admin, title, stats, entries, page, PAGE_SIZE,
                (sp, p) -> openSkin(sp, id, p), sp -> openNpc(sp, id));
    }

    // ==============================================================================================
    //  Utilitaires
    // ==============================================================================================

    private static float normalize(float yaw) {
        float v = yaw % 360.0f;
        return v < 0 ? v + 360.0f : v;
    }

    private static void refresh(ServerPlayer admin) {
        NpcData.get(admin.server).setDirty();
        NpcManager.sync(admin.server);
    }
}
