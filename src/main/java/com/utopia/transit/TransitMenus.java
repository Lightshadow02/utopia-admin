package com.utopia.transit;

import java.util.ArrayList;
import java.util.List;

import com.utopia.data.TransitData;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.gui.UtopiaGui;
import com.utopia.net.OwoMenuServer;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Interfaces des Capitaines Transit : la replique puis l'embarquement cote joueur, la configuration
 * des capitaines, des quatre destinations et du point de retour cote administration.
 *
 * <p>Cote joueur, on parle toujours de traversee et d'embarquement.
 */
public final class TransitMenus {

    /** Position des caps dans la grille 3x9 du menu (croix directionnelle). */
    private static final int SLOT_NORD = 4;
    private static final int SLOT_OUEST = 12;
    private static final int SLOT_CENTRE = 13;
    private static final int SLOT_EST = 14;
    private static final int SLOT_SUD = 22;

    private static final int PAGE_SIZE = 12;

    private TransitMenus() {
    }

    // ==============================================================================================
    //  Cote joueur
    // ==============================================================================================

    /** Clic sur un capitaine : replique, puis interface correspondant a son mode. */
    public static void onInteract(ServerPlayer player, String captainId) {
        TransitData data = TransitData.get(player.server);
        TransitData.Captain captain = data.captain(captainId);
        if (captain == null || !captain.enabled) {
            player.sendSystemMessage(Messages.warn("Ce capitaine n'assure aucune traversee pour le moment."));
            return;
        }
        String line = TransitManager.nextLine(player, captain.mode);
        if (!line.isEmpty()) {
            player.sendSystemMessage(Component.literal("[" + captain.name + "] ")
                    .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true))
                    .append(Component.literal(line)
                            .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(false).withItalic(true))));
        }
        if (captain.mode == TransitData.Mode.RETOUR) {
            openReturn(player, captainId);
        } else {
            openCompass(player, captainId);
        }
    }

    /** Croix directionnelle : Nord en haut, Est a droite, Sud en bas, Ouest a gauche. */
    public static void openCompass(ServerPlayer player, String captainId) {
        TransitData data = TransitData.get(player.server);
        TransitData.Captain captain = data.captain(captainId);
        if (captain == null) {
            return;
        }
        UtopiaGui gui = new UtopiaGui(3, Icons.title(captain.name, ChatFormatting.GOLD)).gridLayout(true);

        // Centre purement informatif : volontairement non cliquable.
        gui.set(SLOT_CENTRE, Icons.icon(Items.COMPASS,
                Icons.label("Choisissez votre destination", ChatFormatting.GOLD),
                List.of(Icons.lore("Quatre caps vers le continent de ressources", ChatFormatting.GRAY))));

        cap(gui, SLOT_NORD, TransitData.Direction.NORD, captainId, data);
        cap(gui, SLOT_EST, TransitData.Direction.EST, captainId, data);
        cap(gui, SLOT_SUD, TransitData.Direction.SUD, captainId, data);
        cap(gui, SLOT_OUEST, TransitData.Direction.OUEST, captainId, data);

        gui.fillEmpty();
        Menus.open(player, gui);
    }

    /** Un cap de la croix : bouton d'embarquement, ou case inerte si la destination est fermee. */
    private static void cap(UtopiaGui gui, int slot, TransitData.Direction direction,
                            String captainId, TransitData data) {
        boolean usable = data.isUsable(direction);
        String label = direction.arrow() + " " + direction.label();
        if (!usable) {
            gui.set(slot, Icons.icon(Items.BARRIER, Icons.label(label, ChatFormatting.DARK_GRAY),
                    List.of(Icons.lore("Destination indisponible", ChatFormatting.RED))));
            return;
        }
        gui.button(slot, Icons.icon(Items.ARROW, Icons.label(label, ChatFormatting.AQUA),
                        List.of(Icons.lore("Embarquer vers le " + direction.label(), ChatFormatting.GRAY))),
                sp -> depart(sp, TransitData.get(sp.server).destination(direction),
                        "le " + direction.label(), captainId));
    }

    /** Interface du retour : une seule option, vers Utopia. */
    public static void openReturn(ServerPlayer player, String captainId) {
        TransitData data = TransitData.get(player.server);
        TransitData.Captain captain = data.captain(captainId);
        if (captain == null) {
            return;
        }
        boolean usable = data.isReturnUsable();
        Component title = Icons.title(captain.name, ChatFormatting.GOLD);
        List<Component> stats = List.of(Component.literal(usable
                        ? "Le navire est pret a lever l'ancre."
                        : "Aucune traversee possible pour le moment.")
                .withStyle(s -> s.withColor(usable ? ChatFormatting.GRAY : ChatFormatting.RED).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(usable ? Items.OAK_BOAT : Items.BARRIER),
                Icons.label("Retourner sur Utopia", usable ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY),
                Icons.lore(usable ? "Embarquer pour l'ile principale" : "Traversee indisponible",
                        usable ? ChatFormatting.GRAY : ChatFormatting.RED),
                sp -> depart(sp, TransitData.get(sp.server).returnPoint(), "Utopia", captainId)));

        OwoMenuServer.openHub(player, title, stats, entries, sp -> openReturn(sp, captainId), null);
    }

    /** Embarquement effectif : verifie le quai puis emmene le joueur, ou explique pourquoi c'est impossible. */
    private static void depart(ServerPlayer player, TransitData.Point point, String where, String captainId) {
        TransitManager.BoardResult result = TransitManager.board(player, point, where);
        if (result != TransitManager.BoardResult.OK) {
            player.sendSystemMessage(Messages.warn(TransitManager.reason(result)));
            TransitData.Captain captain = TransitData.get(player.server).captain(captainId);
            if (captain != null && captain.mode == TransitData.Mode.RETOUR) {
                openReturn(player, captainId);
            } else {
                openCompass(player, captainId);
            }
            return;
        }
        Menus.close(player);
        player.sendSystemMessage(Messages.success("Vous montez a bord... Le navire appareille pour "
                + where + ". Bonne traversee !"));
    }

    // ==============================================================================================
    //  Cote administration
    // ==============================================================================================

    public static void openAdmin(ServerPlayer admin) {
        openAdmin(admin, 0);
    }

    public static void openAdmin(ServerPlayer admin, int page) {
        TransitData data = TransitData.get(admin.server);

        Component title = Icons.screenTitle("Capitaines Transit", ChatFormatting.GOLD);
        int usable = 0;
        for (TransitData.Direction d : TransitData.Direction.values()) {
            if (data.isUsable(d)) {
                usable++;
            }
        }
        List<Component> stats = List.of(
                Component.literal(data.captains().size() + " capitaine(s) - " + usable + "/4 destination(s) pretes")
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)),
                Component.literal(data.isReturnUsable() ? "Point de retour : configure" : "Point de retour : MANQUANT")
                        .withStyle(s -> s.withColor(data.isReturnUsable() ? ChatFormatting.GREEN : ChatFormatting.RED)
                                .withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.OAK_BOAT),
                Icons.label("Nouveau capitaine", ChatFormatting.GREEN),
                Icons.lore("Place un capitaine a ta position", ChatFormatting.GRAY),
                TransitMenus::promptCreate));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.OAK_BOAT),
                Icons.label("Capitaine de retour ici", ChatFormatting.LIGHT_PURPLE),
                Icons.lore("Un clic : meme skin, un seul bouton \"Retour sur Utopia\"",
                        ChatFormatting.GRAY),
                TransitMenus::createReturnCaptain));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.COMPASS),
                Icons.label("Destinations (4 caps)", ChatFormatting.AQUA),
                Icons.lore("Quais d'arrivee sur le continent", ChatFormatting.GRAY),
                TransitMenus::openDestinations));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.ENDER_PEARL),
                Icons.label("Point de retour (Utopia)", ChatFormatting.LIGHT_PURPLE),
                Icons.lore("Commun aux quatre capitaines du continent", ChatFormatting.GRAY),
                TransitMenus::openReturnPoint));
        for (TransitData.Captain captain : data.captains()) {
            String id = captain.id;
            entries.add(new OwoMenuServer.HubEntry(
                    new ItemStack(captain.enabled ? Items.PLAYER_HEAD : Items.GRAY_DYE),
                    Icons.label(captain.name + " (" + captain.mode.label() + ")",
                            captain.enabled ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY),
                    Icons.lore(captain.isPlaced()
                                    ? String.format("%.0f %.0f %.0f", captain.x, captain.y, captain.z)
                                    : "non place",
                            captain.isPlaced() ? ChatFormatting.GRAY : ChatFormatting.RED),
                    sp -> openCaptain(sp, id)));
        }

        OwoMenuServer.openHubPaged(admin, title, stats, entries, page, PAGE_SIZE,
                TransitMenus::openAdmin, com.utopia.menu.AdminMenu::open);
    }

    /**
     * Pose en un seul clic un capitaine du retour a la position de l'admin : meme apparence que les
     * capitaines deja en place, mode Retour, et une interface a un seul bouton vers Utopia. C'est la
     * moitie de la ligne qui demande le moins de reglages, autant qu'elle n'en demande aucun.
     */
    private static void createReturnCaptain(ServerPlayer admin) {
        TransitData data = TransitData.get(admin.server);

        // On reprend l'apparence et le nom d'un capitaine existant : la ligne doit se ressembler.
        TransitData.Captain model = null;
        for (TransitData.Captain c : data.captains()) {
            if (!c.skinValue.isBlank()) {
                model = c;
                break;
            }
        }
        String name = model != null ? model.name : "Capitaine Transit";
        TransitData.Captain captain = data.create(name);
        captain.mode = TransitData.Mode.RETOUR;
        if (model != null) {
            captain.skinValue = model.skinValue;
            captain.skinSignature = model.skinSignature;
        }
        place(admin, captain);
        data.setDirty();
        TransitManager.syncNpcs(admin.server);

        admin.sendSystemMessage(Messages.success("Capitaine du retour place ici"
                + (model != null ? " avec le skin de " + model.name + "." : ".")));
        if (!data.isReturnUsable()) {
            admin.sendSystemMessage(Messages.warn("Il reste a indiquer ou les joueurs debarquent sur "
                    + "Utopia : /admin > Capitaines Transit > Point de retour."));
        }
        openAdmin(admin);
    }

    private static void promptCreate(ServerPlayer admin) {
        Menus.promptFreeText(admin, Icons.label("Nom du capitaine", ChatFormatting.GOLD),
                List.of(Icons.lore("Ex : Capitaine Transit", ChatFormatting.GRAY)),
                Icons.label("Placer ici", ChatFormatting.GREEN), "Capitaine Transit", 32,
                name -> {
                    if (name == null || name.isBlank()) {
                        admin.sendSystemMessage(Messages.warn("Nom vide."));
                        openAdmin(admin);
                        return;
                    }
                    TransitData data = TransitData.get(admin.server);
                    TransitData.Captain captain = data.create(name);
                    place(admin, captain);
                    data.setDirty();
                    TransitManager.syncNpcs(admin.server);
                    admin.sendSystemMessage(Messages.success("Capitaine \"" + captain.name
                            + "\" place. Choisis son mode (Aller ou Retour)."));
                    openCaptain(admin, captain.id);
                });
    }

    /** Enregistre la position et l'orientation de repos d'un capitaine depuis celles de l'admin. */
    private static void place(ServerPlayer admin, TransitData.Captain captain) {
        captain.dim = admin.level().dimension().location().toString();
        captain.x = admin.getX();
        captain.y = admin.getY();
        captain.z = admin.getZ();
        captain.restYaw = admin.getYRot();
    }

    public static void openCaptain(ServerPlayer admin, String id) {
        TransitData data = TransitData.get(admin.server);
        TransitData.Captain captain = data.captain(id);
        if (captain == null) {
            openAdmin(admin);
            return;
        }
        Component title = Icons.title(captain.name, ChatFormatting.AQUA);

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Mode", ChatFormatting.GRAY),
                Icons.label(captain.mode.label(), ChatFormatting.AQUA),
                Icons.label("Basculer", ChatFormatting.YELLOW),
                sp -> {
                    captain.mode = captain.mode.other();
                    data.setDirty();
                    sp.sendSystemMessage(Messages.info("Mode : " + captain.mode.label()
                            + (captain.mode == TransitData.Mode.ALLER
                            ? " (propose les quatre caps)" : " (ramene sur Utopia)")));
                    openCaptain(sp, id);
                }));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Nom", ChatFormatting.GRAY),
                Icons.label(captain.name, ChatFormatting.WHITE),
                Icons.label("Renommer", ChatFormatting.YELLOW),
                sp -> Menus.promptFreeText(sp, Icons.label("Nom du capitaine", ChatFormatting.GOLD), List.of(),
                        Icons.label("Valider", ChatFormatting.GREEN), captain.name, 32,
                        n -> {
                            if (n != null && !n.isBlank()) {
                                captain.name = n.trim();
                                data.setDirty();
                                TransitManager.syncNpcs(sp.server);
                            }
                            openCaptain(sp, id);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Skin", ChatFormatting.GRAY),
                Icons.label(skinLabel(captain), ChatFormatting.AQUA),
                Icons.label("Changer", ChatFormatting.YELLOW),
                sp -> openSkin(sp, id, "", 0, captain.skinValue)));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Position", ChatFormatting.GRAY),
                Icons.label(captain.isPlaced()
                        ? String.format("%.0f %.0f %.0f", captain.x, captain.y, captain.z) : "non place",
                        captain.isPlaced() ? ChatFormatting.AQUA : ChatFormatting.RED),
                Icons.label("Ici", ChatFormatting.GREEN),
                sp -> {
                    place(sp, captain);
                    data.setDirty();
                    TransitManager.syncNpcs(sp.server);
                    sp.sendSystemMessage(Messages.success("Capitaine deplace a ta position "
                            + "(orientation de repos enregistree)."));
                    openCaptain(sp, id);
                }));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Etat", ChatFormatting.GRAY),
                Icons.label(captain.enabled ? "actif" : "desactive",
                        captain.enabled ? ChatFormatting.GREEN : ChatFormatting.RED),
                Icons.label(captain.enabled ? "Desactiver" : "Activer", ChatFormatting.YELLOW),
                sp -> {
                    captain.enabled = !captain.enabled;
                    data.setDirty();
                    TransitManager.syncNpcs(sp.server);
                    openCaptain(sp, id);
                }));

        List<OwoMenuServer.PanelAction> footer = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Supprimer", ChatFormatting.RED),
                        sp -> {
                            data.remove(id);
                            TransitManager.syncNpcs(sp.server);
                            sp.sendSystemMessage(Messages.success("Capitaine supprime "
                                    + "(les destinations restent configurees)."));
                            openAdmin(sp);
                        }));

        OwoMenuServer.openPanel(admin, title, rows, footer, sp -> openCaptain(sp, id), TransitMenus::openAdmin);
    }

    private static String skinLabel(TransitData.Captain captain) {
        if (captain.skinValue == null || captain.skinValue.isEmpty()) {
            return "Steve";
        }
        String packed = com.utopia.entity.NpcSkins.nameOf(captain.skinValue);
        return packed == null ? "personnalise" : com.utopia.entity.NpcSkins.label(packed);
    }

    /** Selecteur de skin dans le pack, avec apercu en direct sur le capitaine. */
    public static void openSkin(ServerPlayer admin, String id, String query, int page, String original) {
        TransitData data = TransitData.get(admin.server);
        TransitData.Captain captain = data.captain(id);
        if (captain == null) {
            openAdmin(admin);
            return;
        }
        List<String> found = com.utopia.entity.NpcSkins.search(query);
        String currentName = com.utopia.entity.NpcSkins.nameOf(captain.skinValue);

        Component title = Icons.title("Skin - " + captain.name, ChatFormatting.LIGHT_PURPLE);
        List<Component> stats = List.of(
                Component.literal(query.isBlank()
                                ? found.size() + " skins disponibles"
                                : found.size() + " resultat(s) pour \"" + query + "\"")
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)),
                Component.literal("Clique un skin : il s'applique aussitot sur le capitaine.")
                        .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.NAME_TAG),
                Icons.label("Rechercher...", ChatFormatting.YELLOW),
                Icons.lore(query.isBlank() ? "Filtrer par nom" : "Filtre : " + query, ChatFormatting.GRAY),
                sp -> Menus.promptFreeText(sp, Icons.label("Rechercher un skin", ChatFormatting.GOLD),
                        List.of(Icons.lore("Laisse vide pour tout afficher", ChatFormatting.GRAY)),
                        Icons.label("Chercher", ChatFormatting.GREEN), query, 32,
                        q -> openSkin(sp, id, q == null ? "" : q, 0, original))));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.BARRIER),
                Icons.label("Steve (defaut)", ChatFormatting.GRAY),
                Icons.lore("Retire le skin personnalise", ChatFormatting.GRAY),
                sp -> {
                    captain.skinValue = "";
                    captain.skinSignature = "";
                    data.setDirty();
                    TransitManager.syncNpcs(sp.server);
                    openSkin(sp, id, query, page, original);
                }));
        for (String skin : found) {
            boolean isCurrent = skin.equals(currentName);
            entries.add(new OwoMenuServer.HubEntry(
                    new ItemStack(isCurrent ? Items.LIME_DYE : Items.PLAYER_HEAD),
                    Icons.label(com.utopia.entity.NpcSkins.label(skin),
                            isCurrent ? ChatFormatting.GREEN : ChatFormatting.WHITE),
                    Icons.lore(isCurrent ? "Skin actuel" : "Clic : essayer", ChatFormatting.GRAY),
                    sp -> {
                        captain.skinValue = com.utopia.entity.NpcSkins.value(skin);
                        captain.skinSignature = "";
                        data.setDirty();
                        TransitManager.syncNpcs(sp.server);
                        openSkin(sp, id, query, page, original);
                    }));
        }

        OwoMenuServer.openHubPaged(admin, title, stats, entries, page, PAGE_SIZE,
                (sp, p) -> openSkin(sp, id, query, p, original), sp -> openCaptain(sp, id));
    }

    // -------- Destinations --------

    public static void openDestinations(ServerPlayer admin) {
        TransitData data = TransitData.get(admin.server);
        Component title = Icons.title("Destinations du continent", ChatFormatting.AQUA);

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        for (TransitData.Direction d : TransitData.Direction.values()) {
            TransitData.Point p = data.destination(d);
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label(d.arrow() + " " + d.label(), ChatFormatting.GRAY),
                    Icons.label(pointLabel(p), p.isSet() && p.enabled ? ChatFormatting.GREEN : ChatFormatting.RED),
                    Icons.label("Regler", ChatFormatting.YELLOW),
                    sp -> openPoint(sp, d)));
        }

        OwoMenuServer.openPanel(admin, title, rows, List.of(),
                TransitMenus::openDestinations, TransitMenus::openAdmin);
    }

    private static String pointLabel(TransitData.Point p) {
        if (!p.isSet()) {
            return "non defini";
        }
        return String.format("%.0f %.0f %.0f", p.x, p.y, p.z) + (p.enabled ? "" : " (ferme)");
    }

    /** Reglage d'un quai d'arrivee : position, ouverture, et verification de praticabilite. */
    public static void openPoint(ServerPlayer admin, TransitData.Direction direction) {
        TransitData data = TransitData.get(admin.server);
        TransitData.Point p = data.destination(direction);
        Component title = Icons.title("Quai " + direction.label(), ChatFormatting.AQUA);
        List<Component> stats = List.of(
                Component.literal(pointLabel(p))
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)),
                Component.literal(checkLabel(admin, p))
                        .withStyle(s -> s.withColor(TransitManager.check(admin.server, p)
                                        == TransitManager.BoardResult.OK ? ChatFormatting.GREEN : ChatFormatting.RED)
                                .withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.ENDER_PEARL),
                Icons.label("Definir ici", ChatFormatting.GREEN),
                Icons.lore("Le joueur arrivera a ta position et dans ton orientation", ChatFormatting.GRAY),
                sp -> {
                    setPoint(sp, p);
                    data.setDirty();
                    sp.sendSystemMessage(Messages.success("Quai " + direction.label() + " enregistre."));
                    openPoint(sp, direction);
                }));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(p.enabled ? Items.LIME_DYE : Items.GRAY_DYE),
                Icons.label(p.enabled ? "Fermer la destination" : "Ouvrir la destination",
                        p.enabled ? ChatFormatting.RED : ChatFormatting.GREEN),
                Icons.lore("Une destination fermee n'est pas embarquable", ChatFormatting.GRAY),
                sp -> {
                    p.enabled = !p.enabled;
                    data.setDirty();
                    openPoint(sp, direction);
                }));

        OwoMenuServer.openHub(admin, title, stats, entries,
                sp -> openPoint(sp, direction), TransitMenus::openDestinations);
    }

    public static void openReturnPoint(ServerPlayer admin) {
        TransitData data = TransitData.get(admin.server);
        TransitData.Point p = data.returnPoint();
        Component title = Icons.title("Point de retour - Utopia", ChatFormatting.LIGHT_PURPLE);
        List<Component> stats = List.of(
                Component.literal(pointLabel(p))
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)),
                Component.literal(checkLabel(admin, p))
                        .withStyle(s -> s.withColor(TransitManager.check(admin.server, p)
                                        == TransitManager.BoardResult.OK ? ChatFormatting.GREEN : ChatFormatting.RED)
                                .withItalic(false)),
                Component.literal("Commun aux quatre capitaines du continent.")
                        .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.ENDER_PEARL),
                Icons.label("Definir ici", ChatFormatting.GREEN),
                Icons.lore("Place-toi sur le pont, a cote du capitaine (pas dans lui)", ChatFormatting.GRAY),
                sp -> {
                    setPoint(sp, p);
                    data.setDirty();
                    sp.sendSystemMessage(Messages.success("Point de retour enregistre."));
                    openReturnPoint(sp);
                }));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(p.enabled ? Items.LIME_DYE : Items.GRAY_DYE),
                Icons.label(p.enabled ? "Fermer le retour" : "Ouvrir le retour",
                        p.enabled ? ChatFormatting.RED : ChatFormatting.GREEN),
                Icons.lore("Suspend les traversees de retour", ChatFormatting.GRAY),
                sp -> {
                    p.enabled = !p.enabled;
                    data.setDirty();
                    openReturnPoint(sp);
                }));

        OwoMenuServer.openHub(admin, title, stats, entries,
                TransitMenus::openReturnPoint, TransitMenus::openAdmin);
    }

    private static void setPoint(ServerPlayer admin, TransitData.Point p) {
        p.dim = admin.level().dimension().location().toString();
        p.x = admin.getX();
        p.y = admin.getY();
        p.z = admin.getZ();
        p.yaw = admin.getYRot();
        p.pitch = admin.getXRot();
    }

    /** Diagnostic lisible du quai : praticable, ou raison precise du refus. */
    private static String checkLabel(ServerPlayer admin, TransitData.Point p) {
        TransitManager.BoardResult result = TransitManager.check(admin.server, p);
        return result == TransitManager.BoardResult.OK
                ? "Quai praticable" : TransitManager.reason(result);
    }
}
