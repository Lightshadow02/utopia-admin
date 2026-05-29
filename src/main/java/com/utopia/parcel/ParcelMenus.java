package com.utopia.parcel;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.utopia.data.ParcelData;
import com.utopia.economy.EconomyManager;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.gui.UtopiaGui;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Interfaces (GUI coffre) de gestion d'une parcelle : membres et permissions. */
public final class ParcelMenus {

    private ParcelMenus() {
    }

    private static final Parcel.Flag[] FLAGS = Parcel.Flag.values();

    private static String flagLabel(Parcel.Flag flag) {
        return switch (flag) {
            case BUILD -> "Construire";
            case CONTAINERS -> "Coffres / conteneurs";
            case DOORS -> "Portes / boutons";
            case MACHINES -> "Machines / redstone";
        };
    }

    // ----------------------------------------------------------------------------------- helpers

    private static Parcel getParcel(MinecraftServer server, String id) {
        return ParcelData.get(server).get(id);
    }

    private static boolean canManage(ServerPlayer player, Parcel parcel) {
        return parcel.isOwner(player.getUUID()) || ParcelManager.canBypass(player);
    }

    private static String nameOf(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        Optional<GameProfile> gp = server.getProfileCache().get(id);
        if (gp.isPresent() && gp.get().getName() != null) {
            return gp.get().getName();
        }
        return id.toString().substring(0, 8);
    }

    private static ItemStack head(MinecraftServer server, UUID id, Component name, List<Component> lore) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return Icons.playerHead(online, name, lore);
        }
        Optional<GameProfile> gp = server.getProfileCache().get(id);
        if (gp.isPresent()) {
            return Icons.playerHead(gp.get(), name, lore);
        }
        return Icons.icon(Items.PLAYER_HEAD, name, lore);
    }

    // ----------------------------------------------------------------------------------- menu principal

    public static void openParcelMenu(ServerPlayer player) {
        BlockPos pos = player.blockPosition();
        Parcel p = ParcelData.get(player.server).parcelAt(player.serverLevel().dimension().location(),
                pos.getX(), pos.getY(), pos.getZ());
        if (p == null) {
            player.sendSystemMessage(Messages.warn("Vous n'etes sur aucune parcelle."));
            return;
        }
        openParcelMenuFor(player, p.id());
    }

    public static void openParcelMenuFor(ServerPlayer player, String parcelId) {
        MinecraftServer server = player.server;
        Parcel p = getParcel(server, parcelId);
        if (p == null) {
            player.sendSystemMessage(Messages.error("Parcelle introuvable."));
            return;
        }
        boolean manage = canManage(player, p);

        UtopiaGui gui = new UtopiaGui(3, Icons.label("Parcelle : " + p.name(), ChatFormatting.DARK_AQUA));

        List<Component> info = new ArrayList<>();
        info.add(Icons.lore("Proprietaire : " + (p.isOwned() ? p.ownerName() : "aucun (serveur)"), ChatFormatting.GRAY));
        info.add(Icons.lore("En vente : " + (p.forSale() ? "oui (" + EconomyManager.format(p.price()) + ")" : "non"),
                p.forSale() ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        info.add(Icons.lore("Regions : " + p.regionCount() + " | membres : " + p.members().size(), ChatFormatting.DARK_GRAY));
        gui.set(4, Icons.icon(Items.PAPER, Icons.label("Parcelle " + p.id(), ChatFormatting.AQUA), info));

        if (manage) {
            gui.button(11, Icons.icon(Items.PLAYER_HEAD, Icons.label("Gerer les membres", ChatFormatting.YELLOW),
                    List.of(Icons.lore("Ajouter/retirer des joueurs et leurs droits", ChatFormatting.GRAY))),
                    sp -> openMembersMenu(sp, parcelId));

            boolean sale = p.forSale();
            gui.button(15, Icons.icon(sale ? Items.REDSTONE_BLOCK : Items.EMERALD_BLOCK,
                    Icons.label(sale ? "Retirer de la vente" : "Mettre en vente", sale ? ChatFormatting.RED : ChatFormatting.GREEN),
                    List.of(Icons.lore(sale ? "Actuellement en vente : " + EconomyManager.format(p.price()) : "Definir le prix : /parcel sell <prix>",
                            ChatFormatting.GRAY))),
                    sp -> {
                        Parcel cur = getParcel(server, parcelId);
                        if (cur != null && canManage(sp, cur)) {
                            cur.setForSale(!cur.forSale());
                            ParcelData.get(server).setDirty();
                            sp.sendSystemMessage(Messages.success("Parcelle " + (cur.forSale() ? "mise en vente." : "retiree de la vente.")));
                        }
                        openParcelMenuFor(sp, parcelId);
                    });
        } else if (p.forSale()) {
            gui.button(13, Icons.icon(Items.EMERALD, Icons.label("Acheter", ChatFormatting.GREEN),
                    List.of(Icons.lore("Prix : " + EconomyManager.format(p.price()), ChatFormatting.GOLD),
                            Icons.lore("Clic pour acheter (paye via votre solde)", ChatFormatting.GRAY))),
                    sp -> {
                        Parcel cur = getParcel(server, parcelId);
                        if (cur == null) {
                            return;
                        }
                        long price = cur.price();
                        switch (ParcelManager.purchase(sp, cur)) {
                            case INSUFFICIENT -> sp.sendSystemMessage(Messages.error("Solde insuffisant (" + EconomyManager.format(price) + ")."));
                            case NOT_FOR_SALE -> sp.sendSystemMessage(Messages.error("Plus en vente."));
                            case ALREADY_OWNER -> sp.sendSystemMessage(Messages.error("Vous la possedez deja."));
                            default -> sp.sendSystemMessage(Messages.success("Parcelle achetee pour " + EconomyManager.format(price) + " !"));
                        }
                        sp.closeContainer();
                    });
        }

        gui.fillEmpty();
        Menus.open(player, gui);
    }

    // ----------------------------------------------------------------------------------- membres

    public static void openMembersMenu(ServerPlayer player, String parcelId) {
        MinecraftServer server = player.server;
        Parcel p = getParcel(server, parcelId);
        if (p == null || !canManage(player, p)) {
            player.sendSystemMessage(Messages.error("Acces refuse."));
            return;
        }
        UtopiaGui gui = new UtopiaGui(6, Icons.label("Membres : " + p.name(), ChatFormatting.DARK_AQUA));

        int slot = 0;
        for (var entry : p.members().entrySet()) {
            if (slot > 44) {
                break;
            }
            UUID memberId = entry.getKey();
            List<Component> lore = new ArrayList<>();
            for (Parcel.Flag f : FLAGS) {
                boolean on = entry.getValue().contains(f);
                lore.add(Icons.lore((on ? "[x] " : "[ ] ") + flagLabel(f), on ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
            }
            lore.add(Icons.lore("Clic pour modifier", ChatFormatting.YELLOW));
            gui.button(slot++, head(server, memberId, Icons.label(nameOf(server, memberId), ChatFormatting.WHITE), lore),
                    sp -> openMemberEditMenu(sp, parcelId, memberId));
        }

        gui.button(49, Icons.icon(Items.LIME_DYE, Icons.label("Ajouter un membre", ChatFormatting.GREEN),
                List.of(Icons.lore("Choisir un joueur en ligne", ChatFormatting.GRAY))),
                sp -> openAddMemberMenu(sp, parcelId));
        gui.button(53, Icons.icon(Items.ARROW, Icons.label("Retour", ChatFormatting.YELLOW), List.of()),
                sp -> openParcelMenuFor(sp, parcelId));
        gui.fillEmpty();
        Menus.open(player, gui);
    }

    public static void openMemberEditMenu(ServerPlayer player, String parcelId, UUID memberId) {
        MinecraftServer server = player.server;
        Parcel p = getParcel(server, parcelId);
        if (p == null || !canManage(player, p)) {
            player.sendSystemMessage(Messages.error("Acces refuse."));
            return;
        }
        EnumSet<Parcel.Flag> flags = p.members().get(memberId);
        if (flags == null) {
            openMembersMenu(player, parcelId);
            return;
        }

        UtopiaGui gui = new UtopiaGui(3,
                Icons.label("Droits : " + nameOf(server, memberId), ChatFormatting.DARK_AQUA));
        gui.set(4, head(server, memberId, Icons.label(nameOf(server, memberId), ChatFormatting.WHITE),
                List.of(Icons.lore("Cliquez les boutons pour activer/desactiver", ChatFormatting.GRAY))));

        int[] slots = { 10, 12, 14, 16 };
        for (int i = 0; i < FLAGS.length; i++) {
            Parcel.Flag flag = FLAGS[i];
            boolean on = flags.contains(flag);
            gui.button(slots[i], Icons.icon(on ? Items.LIME_DYE : Items.GRAY_DYE,
                    Icons.label(flagLabel(flag), on ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                    List.of(Icons.lore(on ? "Active - clic pour desactiver" : "Desactive - clic pour activer", ChatFormatting.GRAY))),
                    sp -> toggleFlag(sp, parcelId, memberId, flag));
        }

        gui.button(21, Icons.icon(Items.BARRIER, Icons.label("Retirer ce membre", ChatFormatting.RED), List.of()),
                sp -> {
                    Parcel cur = getParcel(server, parcelId);
                    if (cur != null && canManage(sp, cur)) {
                        cur.setMember(memberId, EnumSet.noneOf(Parcel.Flag.class));
                        ParcelData.get(server).setDirty();
                        sp.sendSystemMessage(Messages.success(nameOf(server, memberId) + " retire de la parcelle."));
                    }
                    openMembersMenu(sp, parcelId);
                });
        gui.button(23, Icons.icon(Items.ARROW, Icons.label("Retour", ChatFormatting.YELLOW), List.of()),
                sp -> openMembersMenu(sp, parcelId));
        gui.fillEmpty();
        Menus.open(player, gui);
    }

    private static void toggleFlag(ServerPlayer player, String parcelId, UUID memberId, Parcel.Flag flag) {
        MinecraftServer server = player.server;
        Parcel p = getParcel(server, parcelId);
        if (p == null || !canManage(player, p)) {
            return;
        }
        EnumSet<Parcel.Flag> set = EnumSet.noneOf(Parcel.Flag.class);
        EnumSet<Parcel.Flag> current = p.members().get(memberId);
        if (current != null) {
            set.addAll(current);
        }
        if (set.contains(flag)) {
            set.remove(flag);
        } else {
            set.add(flag);
        }
        p.setMember(memberId, set);
        ParcelData.get(server).setDirty();
        if (set.isEmpty()) {
            player.sendSystemMessage(Messages.info(nameOf(server, memberId) + " n'a plus aucun droit (retire)."));
            openMembersMenu(player, parcelId);
        } else {
            openMemberEditMenu(player, parcelId, memberId);
        }
    }

    public static void openAddMemberMenu(ServerPlayer player, String parcelId) {
        MinecraftServer server = player.server;
        Parcel p = getParcel(server, parcelId);
        if (p == null || !canManage(player, p)) {
            player.sendSystemMessage(Messages.error("Acces refuse."));
            return;
        }
        UtopiaGui gui = new UtopiaGui(6, Icons.label("Ajouter un membre", ChatFormatting.DARK_AQUA));

        int slot = 0;
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (slot > 44) {
                break;
            }
            UUID id = online.getUUID();
            if (p.isOwner(id) || p.members().containsKey(id)) {
                continue; // deja proprietaire ou membre
            }
            gui.button(slot++, Icons.playerHead(online, Icons.label(online.getGameProfile().getName(), ChatFormatting.WHITE),
                    List.of(Icons.lore("Clic pour ajouter (tous les droits par defaut)", ChatFormatting.GRAY))),
                    sp -> {
                        Parcel cur = getParcel(server, parcelId);
                        if (cur != null && canManage(sp, cur)) {
                            cur.setMember(id, EnumSet.allOf(Parcel.Flag.class));
                            ParcelData.get(server).setDirty();
                            sp.sendSystemMessage(Messages.success(online.getGameProfile().getName() + " ajoute. Ajustez ses droits."));
                            openMemberEditMenu(sp, parcelId, id);
                        }
                    });
        }
        if (slot == 0) {
            gui.set(22, Icons.icon(Items.BARRIER, Icons.label("Aucun joueur disponible", ChatFormatting.RED),
                    List.of(Icons.lore("Les joueurs hors ligne s'ajoutent via /parcel trust", ChatFormatting.GRAY))));
        }

        gui.button(49, Icons.icon(Items.ARROW, Icons.label("Retour", ChatFormatting.YELLOW), List.of()),
                sp -> openMembersMenu(sp, parcelId));
        gui.fillEmpty();
        Menus.open(player, gui);
    }
}
