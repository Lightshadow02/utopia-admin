package com.utopia.inspect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.utopia.UtopiaMod;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Les affaires d'un joueur qui n'est pas la, lues et reecrites dans son fichier de sauvegarde.
 *
 * <p>Un joueur deconnecte n'existe plus en memoire : son inventaire dort dans
 * {@code playerdata/<uuid>.dat}. Ce fichier est le meme que celui que Minecraft relira a sa
 * prochaine connexion, d'ou deux precautions. La premiere : ne jamais y toucher pendant que le
 * joueur est connecte, sinon le serveur reecrirait le fichier par-dessus a sa deconnexion et le
 * travail serait perdu sans un mot. La seconde : ecrire a cote puis remplacer d'un seul geste,
 * comme le fait Minecraft, pour qu'une coupure de courant ne laisse jamais un fichier a moitie
 * ecrit - ce serait tout un inventaire perdu.
 *
 * <p>Les numeros de case ne se suivent pas dans le fichier : le sac occupe 0 a 35, l'armure 100 a
 * 103, la main gauche 150. Ils sont ramenes ici a une suite continue de 41 cases, celle que
 * Minecraft expose aussi en memoire, pour que l'affichage n'ait qu'une seule convention a suivre.
 */
public final class OfflinePlayerData {

    /** Cases d'un inventaire : 0-35 le sac, 36-39 l'armure (bottes d'abord), 40 la main gauche. */
    public static final int TAILLE_INVENTAIRE = 41;
    /** Cases d'un coffre de l'End. */
    public static final int TAILLE_ENDER = 27;

    private static final int PREMIERE_ARMURE = 100;
    private static final int PREMIERE_MAIN_GAUCHE = 150;

    private OfflinePlayerData() {
    }

    private static Path dossier(MinecraftServer server) {
        return server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
    }

    public static Path fichier(MinecraftServer server, UUID id) {
        return dossier(server).resolve(id.toString() + ".dat");
    }

    public static boolean existe(MinecraftServer server, UUID id) {
        return Files.isRegularFile(fichier(server, id));
    }

    /** Le contenu du fichier, ou nul s'il n'existe pas ou ne se lit pas. */
    public static CompoundTag lire(MinecraftServer server, UUID id) {
        Path path = fichier(server, id);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        } catch (Exception e) {
            UtopiaMod.LOGGER.warn("[Utopia] Sauvegarde de {} illisible : {}", id, e.toString());
            return null;
        }
    }

    /**
     * Reecrit le fichier. L'ecriture passe par un fichier temporaire puis un remplacement d'un seul
     * geste : un inventaire a moitie ecrit vaut moins qu'un inventaire pas ecrit du tout.
     */
    public static boolean ecrire(MinecraftServer server, UUID id, CompoundTag tag) {
        try {
            Path dossier = dossier(server);
            Files.createDirectories(dossier);
            Path temporaire = Files.createTempFile(dossier, id + "-", ".dat");
            NbtIo.writeCompressed(tag, temporaire);
            net.minecraft.Util.safeReplaceFile(dossier.resolve(id + ".dat"), temporaire,
                    dossier.resolve(id + ".dat_old"));
            return true;
        } catch (Exception e) {
            UtopiaMod.LOGGER.error("[Utopia] Ecriture de la sauvegarde de {} impossible.", id, e);
            return false;
        }
    }

    // ------------------------------------------------------------------ Inventaire

    public static NonNullList<ItemStack> inventaire(MinecraftServer server, CompoundTag tag) {
        NonNullList<ItemStack> cases = NonNullList.withSize(TAILLE_INVENTAIRE, ItemStack.EMPTY);
        relire(server, tag.getList("Inventory", Tag.TAG_COMPOUND), cases, true);
        return cases;
    }

    public static void poserInventaire(MinecraftServer server, CompoundTag tag,
            NonNullList<ItemStack> cases) {
        ListTag liste = new ListTag();
        for (int i = 0; i < Math.min(cases.size(), TAILLE_INVENTAIRE); i++) {
            ItemStack stack = cases.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            CompoundTag entree = new CompoundTag();
            entree.putByte("Slot", (byte) numeroDeCase(i));
            liste.add(stack.save(server.registryAccess(), entree));
        }
        tag.put("Inventory", liste);
    }

    /** Le numero que le fichier donne a la case {@code i} de la suite continue. */
    private static int numeroDeCase(int i) {
        if (i < 36) {
            return i;
        }
        return i == 40 ? PREMIERE_MAIN_GAUCHE : PREMIERE_ARMURE + (i - 36);
    }

    // ------------------------------------------------------------------ Coffre de l'End

    public static NonNullList<ItemStack> enderchest(MinecraftServer server, CompoundTag tag) {
        NonNullList<ItemStack> cases = NonNullList.withSize(TAILLE_ENDER, ItemStack.EMPTY);
        relire(server, tag.getList("EnderItems", Tag.TAG_COMPOUND), cases, false);
        return cases;
    }

    public static void poserEnderchest(MinecraftServer server, CompoundTag tag,
            NonNullList<ItemStack> cases) {
        ListTag liste = new ListTag();
        for (int i = 0; i < Math.min(cases.size(), TAILLE_ENDER); i++) {
            ItemStack stack = cases.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            CompoundTag entree = new CompoundTag();
            entree.putByte("Slot", (byte) i);
            liste.add(stack.save(server.registryAccess(), entree));
        }
        tag.put("EnderItems", liste);
    }

    /**
     * Range une liste du fichier dans une suite continue de cases. Le numero est lu non signe, comme
     * le fait Minecraft : la main gauche porte le numero 150, qui deborde d'un octet signe.
     */
    private static void relire(MinecraftServer server, ListTag liste, NonNullList<ItemStack> cases,
            boolean avecArmure) {
        for (int i = 0; i < liste.size(); i++) {
            CompoundTag entree = liste.getCompound(i);
            int numero = entree.getByte("Slot") & 255;
            int place;
            if (numero < 36 && numero < cases.size()) {
                place = numero;
            } else if (avecArmure && numero >= PREMIERE_ARMURE && numero < PREMIERE_ARMURE + 4) {
                place = 36 + (numero - PREMIERE_ARMURE);
            } else if (avecArmure && numero == PREMIERE_MAIN_GAUCHE) {
                place = 40;
            } else {
                continue; // numero d'une version passee, ou d'un mod qui ajoute des cases
            }
            cases.set(place, ItemStack.parse(server.registryAccess(), entree).orElse(ItemStack.EMPTY));
        }
    }

    // ------------------------------------------------------------------ Le trombinoscope

    /**
     * Tous ceux qui ont une sauvegarde sur ce serveur, joueurs connectes compris. Le pseudo vient du
     * cache local des profils et ne coute aucun appel reseau ; ceux qu'il ne connait pas sont
     * renvoyes sans nom, l'identifiant valant mieux que rien.
     */
    public static List<Connu> joueursConnus(MinecraftServer server) {
        List<Connu> connus = new ArrayList<>();
        try (var fichiers = Files.list(dossier(server))) {
            fichiers.forEach(path -> {
                String nomFichier = path.getFileName().toString();
                if (!nomFichier.endsWith(".dat")) {
                    return; // .dat_old et fichiers temporaires ne sont pas des joueurs
                }
                try {
                    UUID id = UUID.fromString(nomFichier.substring(0, nomFichier.length() - 4));
                    String pseudo = server.getProfileCache() == null ? null
                            : server.getProfileCache().get(id)
                                    .map(com.mojang.authlib.GameProfile::getName).orElse(null);
                    connus.add(new Connu(id, pseudo == null ? id.toString().substring(0, 8) : pseudo,
                            pseudo != null));
                } catch (IllegalArgumentException ignored) {
                    // nom de fichier qui n'est pas un identifiant : ce n'est pas un joueur
                }
            });
        } catch (Exception e) {
            UtopiaMod.LOGGER.warn("[Utopia] Liste des sauvegardes illisible : {}", e.toString());
        }
        connus.sort((a, b) -> a.nom.compareToIgnoreCase(b.nom));
        return connus;
    }

    /** Un joueur ayant une sauvegarde : son identifiant, son pseudo, et si ce pseudo est sur. */
    public record Connu(UUID id, String nom, boolean nomConnu) {
    }
}
