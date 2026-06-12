package com.utopia;

import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Configuration commune du mod, editable par les administrateurs dans
 * {@code config/utopia-common.toml}. NeoForge recharge automatiquement le fichier
 * lorsqu'il est modifie sur le disque.
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ----------------------------------------------------------------------------------------
    // Teleportation (commun a /tpa, /tpahere et /spawn)
    // ----------------------------------------------------------------------------------------
    public static final ModConfigSpec.IntValue TELEPORT_WARMUP_SECONDS;
    public static final ModConfigSpec.BooleanValue TELEPORT_CANCEL_ON_MOVE;
    public static final ModConfigSpec.BooleanValue TELEPORT_CANCEL_ON_DAMAGE;
    public static final ModConfigSpec.BooleanValue TELEPORT_EFFECTS;

    // ----------------------------------------------------------------------------------------
    // Demandes de teleportation entre joueurs
    // ----------------------------------------------------------------------------------------
    public static final ModConfigSpec.IntValue TPA_REQUEST_TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue TPA_COOLDOWN_SECONDS;

    // ----------------------------------------------------------------------------------------
    // Spawn
    // ----------------------------------------------------------------------------------------
    public static final ModConfigSpec.IntValue SPAWN_COOLDOWN_SECONDS;

    // ----------------------------------------------------------------------------------------
    // Recompenses quotidiennes
    // ----------------------------------------------------------------------------------------
    public static final ModConfigSpec.IntValue DAILY_COOLDOWN_HOURS;
    public static final ModConfigSpec.BooleanValue DAILY_ANNOUNCE;
    public static final ModConfigSpec.BooleanValue DAILY_DEFAULT_ENABLED;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DAILY_ITEMS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DAILY_COMMANDS;

    public static final ModConfigSpec.BooleanValue DAILY_STREAK_ENABLED;
    public static final ModConfigSpec.IntValue DAILY_STREAK_RESET_HOURS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DAILY_STREAK_MILESTONES;

    // ----------------------------------------------------------------------------------------
    // Economie / banque
    // ----------------------------------------------------------------------------------------
    public static final ModConfigSpec.ConfigValue<String> ECO_COIN_ITEM;
    public static final ModConfigSpec.ConfigValue<String> ECO_CARD_ITEM;
    public static final ModConfigSpec.ConfigValue<String> ECO_CURRENCY_NAME;
    public static final ModConfigSpec.IntValue ECO_STARTING_BALANCE;

    // ----------------------------------------------------------------------------------------
    // Parcelles
    // ----------------------------------------------------------------------------------------
    public static final ModConfigSpec.ConfigValue<String> PARCEL_WAND_ITEM;
    public static final ModConfigSpec.BooleanValue PARCEL_OP_BYPASS;
    public static final ModConfigSpec.BooleanValue PARCEL_PROTECT_EXPLOSIONS;
    public static final ModConfigSpec.BooleanValue PARCEL_EXTINGUISH_FIRE;
    public static final ModConfigSpec.BooleanValue PARCEL_PUBLIC_DOORS;
    public static final ModConfigSpec.BooleanValue PARCEL_PROTECT_ENTITIES;
    public static final ModConfigSpec.ConfigValue<String> PARCEL_HABITATION_ITEM;
    public static final ModConfigSpec.ConfigValue<String> PARCEL_COMMERCE_ITEM;

    // Chambres d'auberge
    public static final ModConfigSpec.ConfigValue<String> ROOM_WAND_ITEM;

    // Menu central
    public static final ModConfigSpec.ConfigValue<String> MENU_QUEST_COMMAND;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment(
                "=================================================================",
                " Utopia Essentials - configuration",
                " Modifiez ce fichier puis sauvegardez : il est recharge a chaud.",
                "=================================================================");

        BUILDER.comment("Parametres communs a toutes les teleportations.").push("teleport");
        TELEPORT_WARMUP_SECONDS = BUILDER
                .comment("Delai (en secondes) avant l'execution d'une teleportation. 0 = instantane.")
                .defineInRange("warmupSeconds", 3, 0, 600);
        TELEPORT_CANCEL_ON_MOVE = BUILDER
                .comment("Annuler la teleportation si le joueur change de bloc pendant le delai.")
                .define("cancelOnMove", true);
        TELEPORT_CANCEL_ON_DAMAGE = BUILDER
                .comment("Annuler la teleportation si le joueur subit des degats pendant le delai.")
                .define("cancelOnDamage", true);
        TELEPORT_EFFECTS = BUILDER
                .comment("Afficher une animation de particules + un son lors d'une teleportation.")
                .define("effects", true);
        BUILDER.pop();

        BUILDER.comment("Demandes de teleportation entre joueurs (/tpa, /tpahere).").push("tpa");
        TPA_REQUEST_TIMEOUT_SECONDS = BUILDER
                .comment("Duree de validite (en secondes) d'une demande avant expiration.")
                .defineInRange("requestTimeoutSeconds", 60, 5, 3600);
        TPA_COOLDOWN_SECONDS = BUILDER
                .comment("Temps minimum (en secondes) entre deux demandes envoyees par un meme joueur. 0 = aucun.")
                .defineInRange("cooldownSeconds", 0, 0, 3600);
        BUILDER.pop();

        BUILDER.comment("Commande /spawn.").push("spawn");
        SPAWN_COOLDOWN_SECONDS = BUILDER
                .comment("Temps minimum (en secondes) entre deux utilisations de /spawn. 0 = aucun.")
                .defineInRange("cooldownSeconds", 0, 0, 3600);
        BUILDER.pop();

        BUILDER.comment(
                "Recompenses quotidiennes (/daily).",
                "Format des items : \"modid:item quantite\" (la quantite est optionnelle, defaut 1).",
                "  exemples : \"minecraft:diamond 3\", \"minecraft:golden_apple\".",
                "Format des commandes : commande serveur executee avec permission 4.",
                "  Le jeton {player} est remplace par le pseudo du joueur.",
                "  exemples : \"give {player} minecraft:experience_bottle 16\", \"effect give {player} minecraft:luck 600 0\".")
                .push("daily");
        DAILY_COOLDOWN_HOURS = BUILDER
                .comment("Temps (en heures) entre deux recompenses pour un meme joueur.")
                .defineInRange("cooldownHours", 24, 1, 8760);
        DAILY_ANNOUNCE = BUILDER
                .comment("Annoncer a tout le serveur quand un joueur reclame sa recompense.")
                .define("announce", false);
        DAILY_DEFAULT_ENABLED = BUILDER
                .comment("Donner la recompense par defaut (items + commandes ci-dessous) les jours SANS planning calendaire.",
                        "false = seuls les jours explicitement planifies dans le calendrier donnent quelque chose.")
                .define("defaultRewardEnabled", true);
        DAILY_ITEMS = BUILDER
                .comment("Items donnes a chaque recompense quotidienne (peut etre vide).")
                .defineListAllowEmpty("items",
                        List.of("minecraft:diamond 1", "minecraft:cooked_beef 16"),
                        () -> "minecraft:diamond 1",
                        Config::validateItemStack);
        DAILY_COMMANDS = BUILDER
                .comment("Commandes executees a chaque recompense quotidienne (peut etre vide).")
                .defineListAllowEmpty("commands",
                        List.of("give {player} minecraft:experience_bottle 8"),
                        () -> "give {player} minecraft:experience_bottle 8",
                        Config::validateString);

        BUILDER.comment("Systeme de serie (jours consecutifs).").push("streak");
        DAILY_STREAK_ENABLED = BUILDER
                .comment("Activer le comptage des jours consecutifs et les recompenses de paliers.")
                .define("enabled", true);
        DAILY_STREAK_RESET_HOURS = BUILDER
                .comment(
                        "Au-dela de ce delai (en heures) sans reclamer, la serie repart a 1.",
                        "Doit etre superieur a daily.cooldownHours (defaut 48 = on a une journee de battement).")
                .defineInRange("resetHours", 48, 1, 8760);
        DAILY_STREAK_MILESTONES = BUILDER
                .comment(
                        "Recompenses de palier. Une entree par palier, au format :",
                        "  \"jour | items | commandes\"",
                        "  - jour     : numero du palier. Prefixez par * pour un palier recurrent",
                        "               (ex: \"*7\" = tous les 7 jours).",
                        "  - items    : liste d'items separes par des virgules (peut etre vide).",
                        "  - commandes: liste de commandes separees par des virgules (peut etre vide).",
                        "  Les recompenses de palier s'ajoutent aux items/commandes de base.",
                        "  exemples :",
                        "    \"7 | minecraft:diamond 5 | \"",
                        "    \"*30 | minecraft:netherite_ingot 2 | effect give {player} minecraft:hero_of_the_village 6000 1\"")
                .defineListAllowEmpty("milestones",
                        List.of(
                                "7 | minecraft:diamond 5 | ",
                                "30 | minecraft:netherite_ingot 2 | effect give {player} minecraft:hero_of_the_village 6000 1"),
                        () -> "7 | minecraft:diamond 5 | ",
                        Config::validateMilestone);
        BUILDER.pop(); // streak
        BUILDER.pop(); // daily

        BUILDER.comment("Economie / banque (commandes /balance, /pay, /withdraw, /deposit, /money).").push("economy");
        ECO_COIN_ITEM = BUILDER
                .comment(
                        "Item utilise comme \"piece\" physique lors d'un retrait (/withdraw).",
                        "Si l'item n'existe pas au lancement, repli automatique sur minecraft:gold_nugget renomme.")
                .define("coinItem", "utopiamods:utopiece");
        ECO_CARD_ITEM = BUILDER
                .comment("Item \"carte bancaire\" : un clic droit en le tenant ouvre le menu de banque.")
                .define("cardItem", "utopiamods:carte_credit");
        ECO_CURRENCY_NAME = BUILDER
                .comment("Nom de la monnaie affiche dans les messages (ex: \"pieces\").")
                .define("currencyName", "pieces");
        ECO_STARTING_BALANCE = BUILDER
                .comment("Solde de depart d'un joueur n'ayant jamais ete credite.")
                .defineInRange("startingBalance", 0, 0, Integer.MAX_VALUE);
        BUILDER.pop();

        BUILDER.comment("Parcelles (terrains a formes libres, achat, permissions).").push("parcel");
        PARCEL_WAND_ITEM = BUILDER
                .comment("Outil de selection des coins d'une parcelle (clic gauche = coin 1, clic droit = coin 2).")
                .define("wandItem", "minecraft:golden_hoe");
        PARCEL_OP_BYPASS = BUILDER
                .comment("Les operateurs (op) ignorent la protection des parcelles (build/conteneurs/etc.).")
                .define("opBypass", true);
        PARCEL_PROTECT_EXPLOSIONS = BUILDER
                .comment("Empecher les explosions (TNT, creepers...) de detruire les blocs des parcelles.")
                .define("protectExplosions", true);
        PARCEL_EXTINGUISH_FIRE = BUILDER
                .comment("Eteindre automatiquement le feu qui apparait dans les parcelles (balayage periodique).")
                .define("extinguishFire", true);
        PARCEL_PUBLIC_DOORS = BUILDER
                .comment("Portes, trappes, portillons, boutons, leviers, plaques accessibles a TOUS dans les parcelles.")
                .define("publicDoors", true);
        PARCEL_PROTECT_ENTITIES = BUILDER
                .comment("Empecher les joueurs non autorises de blesser/tuer les entites (villageois, animaux, cadres...) d'une parcelle.")
                .define("protectEntities", true);
        PARCEL_HABITATION_ITEM = BUILDER
                .comment("Item exige (et consomme) pour acheter une parcelle Habitation. Vide pour ne rien exiger.")
                .define("habitationItem", "utopiamods:actedepropriete");
        PARCEL_COMMERCE_ITEM = BUILDER
                .comment("Item exige (et consomme) pour acheter une parcelle Commerce. Vide pour ne rien exiger.")
                .define("commerceItem", "utopiamods:licencecommerciale");
        BUILDER.pop();

        BUILDER.comment("Chambres d'auberge (boites 3D superposables, gerees par les admins/aubergistes).").push("room");
        ROOM_WAND_ITEM = BUILDER
                .comment("Outil de selection des chambres (clic gauche = coin 1, clic droit = coin 2 ; le Y compte).")
                .define("wandItem", "minecraft:blaze_rod");
        BUILDER.pop();

        BUILDER.comment("Menu central (/menu).").push("menu");
        MENU_QUEST_COMMAND = BUILDER
                .comment("Commande lancee par le bouton 'Quetes' du /menu (ex: 'ftbquests open_book'). Vide = bouton inactif.")
                .define("questCommand", "ftbquests open_book");
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private Config() {
    }

    /** Une chaine quelconque non nulle est acceptee (utilise pour les listes de commandes). */
    private static boolean validateString(final Object obj) {
        return obj instanceof String;
    }

    /** Valide une entree "modid:item [quantite]" : l'item doit exister et la quantite (si presente) etre un entier positif. */
    private static boolean validateItemStack(final Object obj) {
        if (!(obj instanceof String raw)) {
            return false;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String[] parts = trimmed.split("\\s+");
        ResourceLocation id = ResourceLocation.tryParse(parts[0]);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return false;
        }
        if (parts.length >= 2) {
            try {
                return Integer.parseInt(parts[1]) > 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    /** Valide une entree de palier "jour | items | commandes" : seul le champ "jour" est obligatoire et doit etre un entier. */
    private static boolean validateMilestone(final Object obj) {
        if (!(obj instanceof String raw)) {
            return false;
        }
        String[] fields = raw.split("\\|", -1);
        if (fields.length == 0) {
            return false;
        }
        String dayToken = fields[0].trim();
        if (dayToken.startsWith("*")) {
            dayToken = dayToken.substring(1).trim();
        }
        try {
            return Integer.parseInt(dayToken) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
