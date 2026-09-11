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
    /** Blocs proteges par une permission de parcelle en plus de ceux detectes automatiquement. */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> PARCEL_RESTRICTED_BLOCKS;

    // Chambres d'auberge
    public static final ModConfigSpec.ConfigValue<String> ROOM_WAND_ITEM;

    // Menu central
    public static final ModConfigSpec.ConfigValue<String> MENU_QUEST_COMMAND;

    /** Prix unitaire minimal d'une offre sur le marche flottant. */
    public static final ModConfigSpec.IntValue MARKET_MIN_PRICE;

    /** Serveurs du reseau proposes dans le menu central. */
    public static final ModConfigSpec.ConfigValue<String> SERVERS_CURRENT;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SERVERS_LIST;

    /** Boutons du menu central : chacun peut etre retire sans toucher au code. */
    public static final ModConfigSpec.BooleanValue MENU_PARCELS;
    public static final ModConfigSpec.BooleanValue MENU_SHOP;
    public static final ModConfigSpec.BooleanValue MENU_DAILY;
    public static final ModConfigSpec.BooleanValue MENU_QUOTES;
    public static final ModConfigSpec.BooleanValue MENU_BETS;
    public static final ModConfigSpec.BooleanValue MENU_TPA;
    public static final ModConfigSpec.BooleanValue MENU_SPAWN;
    public static final ModConfigSpec.BooleanValue MENU_QUESTS;
    public static final ModConfigSpec.BooleanValue MENU_SERVERS;

    /** Boutons du menu d'administration. */
    public static final ModConfigSpec.BooleanValue ADMIN_PARCELS;
    public static final ModConfigSpec.BooleanValue ADMIN_ECONOMY;
    public static final ModConfigSpec.BooleanValue ADMIN_DAILY;
    public static final ModConfigSpec.BooleanValue ADMIN_ROOMS;
    public static final ModConfigSpec.BooleanValue ADMIN_INNKEEPERS;
    public static final ModConfigSpec.BooleanValue ADMIN_MARKET;
    public static final ModConfigSpec.BooleanValue ADMIN_MARKETRECOVERY;
    public static final ModConfigSpec.BooleanValue ADMIN_MAIRE;
    public static final ModConfigSpec.BooleanValue ADMIN_INVENTORIES;
    public static final ModConfigSpec.BooleanValue ADMIN_WARPS;
    public static final ModConfigSpec.BooleanValue ADMIN_ELECTIONS;
    public static final ModConfigSpec.BooleanValue ADMIN_JOBS;
    public static final ModConfigSpec.BooleanValue ADMIN_HOLOGRAMS;
    public static final ModConfigSpec.BooleanValue ADMIN_BETS;
    public static final ModConfigSpec.BooleanValue ADMIN_QUOTES;
    public static final ModConfigSpec.BooleanValue ADMIN_SAVINGS;
    public static final ModConfigSpec.BooleanValue ADMIN_CHANTIERS;
    public static final ModConfigSpec.BooleanValue ADMIN_TRANSIT;
    public static final ModConfigSpec.BooleanValue ADMIN_STRUCTURES;
    public static final ModConfigSpec.BooleanValue ADMIN_NPCS;
    public static final ModConfigSpec.BooleanValue ADMIN_WAYSTONES;

    /** Modules du menu du maire : un module retire ici disparait de /maire. */
    public static final ModConfigSpec.BooleanValue MAIRIE_TAXES;
    public static final ModConfigSpec.BooleanValue MAIRIE_LEADERBOARD;
    public static final ModConfigSpec.BooleanValue MAIRIE_PVP;

    /** Heure a laquelle le marchand ambulant retrouve sa place (0-23, heure de Paris). */
    public static final ModConfigSpec.IntValue MERCHANT_RESET_HOUR;
    public static final ModConfigSpec.IntValue MERCHANT_PERSONAL_QUOTA;
    public static final ModConfigSpec.IntValue MERCHANT_DAILY_QUOTA;
    public static final ModConfigSpec.IntValue MERCHANT_UNIT_PRICE;
    public static final ModConfigSpec.BooleanValue MERCHANT_ANNOUNCE;
    public static final ModConfigSpec.BooleanValue MERCHANT_ROTATE_STRUCTURE;

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
        PARCEL_RESTRICTED_BLOCKS = BUILDER
                .comment("Blocs interdits aux joueurs qui n'ont pas la permission indiquee sur la parcelle.",
                        "A remplir librement : un bloc par ligne, \"modid:bloc | PERMISSION\".",
                        "Permissions possibles : BUILD, CONTAINERS, DOORS, MACHINES, CREATE.",
                        "Sans permission indiquee, CONTAINERS est utilise (droit d'ouvrir les coffres).",
                        "Sert aux blocs que le mod ne peut pas deviner seul : un terminal de stockage",
                        "n'est pas un coffre pour Minecraft, il donne pourtant acces a tous les coffres.",
                        "Cette liste passe avant la detection automatique : elle permet aussi de",
                        "reclasser un bloc deja protege (mettre un bloc Create sous CONTAINERS...).")
                .defineListAllowEmpty("restrictedBlocks",
                        List.of("toms_storage:storage_terminal | CONTAINERS",
                                "toms_storage:crafting_terminal | CONTAINERS",
                                "toms_storage:inventory_connector | CONTAINERS",
                                "toms_storage:inventory_proxy | CONTAINERS",
                                "toms_storage:level_emitter | MACHINES"),
                        () -> "modid:bloc | CONTAINERS",
                        Config::validateRestrictedBlock);
        BUILDER.pop();

        BUILDER.comment("Marche flottant (stands tenus par les joueurs).").push("market");
        MARKET_MIN_PRICE = BUILDER
                .comment("Prix unitaire minimal d'une offre. En dessous, la part de la mairie et la",
                        "part detruite tombent a zero par arrondi : la vente ne fait plus circuler",
                        "d'Utopieces, elle ne fait que les deplacer.")
                .defineInRange("minUnitPrice", 2, 1, 1_000_000);
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

        BUILDER.comment("Boutons du menu central : mettre a false pour en retirer un.")
                .push("entries");
        MENU_PARCELS = BUILDER.comment("Bouton \"Mes parcelles\".").define("parcels", true);
        MENU_SHOP = BUILDER.comment("Bouton \"Boutique\".").define("shop", true);
        MENU_DAILY = BUILDER.comment("Bouton \"Recompense\".").define("daily", true);
        MENU_QUOTES = BUILDER.comment("Bouton \"Mes devis\".").define("quotes", true);
        MENU_BETS = BUILDER.comment("Bouton \"Creer un pari\".").define("bets", true);
        MENU_TPA = BUILDER.comment("Bouton \"Se teleporter\".").define("tpa", true);
        MENU_SPAWN = BUILDER.comment("Bouton \"Retour au spawn\".").define("spawn", true);
        MENU_QUESTS = BUILDER.comment("Bouton \"Quetes\".").define("quests", true);
        MENU_SERVERS = BUILDER.comment("Bouton \"Changer de serveur\".").define("servers", true);
        BUILDER.pop(); // entries

        BUILDER.comment("Serveurs du reseau, proposes par le bouton \"Changer de serveur\".",
                        "Necessite un proxy Velocity ou BungeeCord devant le serveur.")
                .push("servers");
        SERVERS_CURRENT = BUILDER
                .comment("Nom de CE serveur dans le proxy. Il sera montre comme celui ou l'on se trouve",
                        "au lieu d'etre propose. Vide = inconnu, tous les serveurs restent proposables.")
                .define("current", "utopia");
        SERVERS_LIST = BUILDER
                .comment("Un serveur par ligne : \"nom dans le proxy | libelle affiche | sous-titre\".",
                        "Le nom doit correspondre exactement a celui declare dans velocity.toml.")
                .defineListAllowEmpty("list",
                        List.of("hub | Hub | Retour au lobby du reseau",
                                "utopia | Utopia | Le serveur principal"),
                        () -> "hub | Hub | Retour au lobby",
                        Config::validateServerEntry);
        BUILDER.pop(); // servers
        BUILDER.pop(); // menu

        BUILDER.comment("Menu d'administration (/admin). Un bouton retire ici disparait de l'ecran ;")
                .comment("la commande correspondante, elle, reste accessible.")
                .push("admin");
        BUILDER.comment("Boutons du menu d'administration : mettre a false pour en retirer un.")
                .push("entries");
        ADMIN_PARCELS = BUILDER.comment("Bouton \"Parcelles\".").define("parcels", true);
        ADMIN_ECONOMY = BUILDER.comment("Bouton \"Economie\".").define("economy", true);
        ADMIN_DAILY = BUILDER.comment("Bouton \"Recompenses (daily)\".").define("daily", true);
        ADMIN_ROOMS = BUILDER.comment("Bouton \"Auberge / chambres\".").define("rooms", true);
        ADMIN_INNKEEPERS = BUILDER.comment("Bouton \"Aubergistes\".").define("innkeepers", true);
        ADMIN_MARKET = BUILDER.comment("Bouton \"Marche : definir un stand\".").define("market", true);
        ADMIN_MARKETRECOVERY = BUILDER.comment("Bouton \"Recuperation marche\".").define("marketRecovery", true);
        ADMIN_MAIRE = BUILDER.comment("Bouton \"Maire\".").define("maire", true);
        ADMIN_INVENTORIES = BUILDER.comment("Bouton \"Inventaires\".").define("inventories", true);
        ADMIN_WARPS = BUILDER.comment("Bouton \"Warps\".").define("warps", true);
        ADMIN_ELECTIONS = BUILDER.comment("Bouton \"Elections\".").define("elections", true);
        ADMIN_JOBS = BUILDER.comment("Bouton \"Metiers et salaires\".").define("jobs", true);
        ADMIN_HOLOGRAMS = BUILDER.comment("Bouton \"Hologrammes\".").define("holograms", true);
        ADMIN_BETS = BUILDER.comment("Bouton \"Paris\".").define("bets", true);
        ADMIN_QUOTES = BUILDER.comment("Bouton \"Devis des joueurs\".").define("quotes", true);
        ADMIN_SAVINGS = BUILDER.comment("Bouton \"Livrets d'epargne\".").define("savings", true);
        ADMIN_CHANTIERS = BUILDER.comment("Bouton \"Chantiers\".").define("chantiers", true);
        ADMIN_TRANSIT = BUILDER.comment("Bouton \"Capitaines Transit\".").define("transit", true);
        ADMIN_STRUCTURES = BUILDER.comment("Bouton \"Structures\".").define("structures", true);
        ADMIN_NPCS = BUILDER.comment("Bouton \"Statues\".").define("npcs", true);
        ADMIN_WAYSTONES = BUILDER.comment("Bouton \"Balises de voyage\".").define("waystones", true);
        BUILDER.pop(); // entries
        BUILDER.pop(); // admin

        BUILDER.comment("Menu du maire (/maire). Ces modules sont pilotes en jeu par le maire elu ;",
                        "les retirer ici les lui retire, y compris ce qu'il avait deja regle.")
                .push("mairie");
        BUILDER.comment("Modules du menu du maire : mettre a false pour en retirer un.")
                .push("entries");
        MAIRIE_TAXES = BUILDER
                .comment("Bouton \"Taxes et impots\" : impot sur les salaires et taxes nommees.",
                        "A false, aucune taxe nommee n'est prelevee et l'impot sur les salaires",
                        "cesse de s'appliquer, quelles que soient les valeurs deja saisies.",
                        "La taxe sur les devis, elle, reste active : elle est anterieure et se",
                        "regle a part, dans l'ecran des devis.")
                .define("taxes", true);
        MAIRIE_LEADERBOARD = BUILDER
                .comment("Bouton \"Concours de chasse\" : classement 24 h a points par mob.",
                        "A false, plus aucune prise n'est comptee ni recompensee.")
                .define("leaderboard", true);
        MAIRIE_PVP = BUILDER
                .comment("Bouton \"PVP dans les parcelles\".",
                        "A false, le maire ne peut plus toucher au PVP et les parcelles",
                        "retrouvent le comportement du serveur.")
                .define("pvp", true);
        BUILDER.pop(); // entries
        BUILDER.pop(); // mairie

        BUILDER.comment("Marchand ambulant : le PNJ qui rachete les items du jour.",
                        "Sa liste d'items se regle a part, dans config/utopia_admin/mongol_calendar.json.")
                .push("merchant");
        MERCHANT_RESET_HOUR = BUILDER
                .comment("Heure REELLE (horloge du monde reel, fuseau de Paris) a laquelle il retrouve",
                        "sa place quotidienne et sa reserve commune. 0 = minuit, 4 = 4h du matin.",
                        "C'est le seul moment de la journee ou les compteurs repartent a zero :",
                        "epuiser la reserve ne la renouvelle pas, il faut attendre cette heure.")
                .defineInRange("resetHour", 0, 0, 23);
        MERCHANT_PERSONAL_QUOTA = BUILDER
                .comment("Place quotidienne de chaque joueur : ces items ne touchent pas la reserve",
                        "commune. Chacun peut donc toujours vendre ce nombre d'items par jour,",
                        "quoi qu'aient fait les autres.")
                .defineInRange("personalQuota", 200, 0, 1_000_000);
        MERCHANT_DAILY_QUOTA = BUILDER
                .comment("Reserve commune au serveur, entamee uniquement par les depassements de la",
                        "place quotidienne. 0 = personne ne peut vendre au-dela de sa place du jour.")
                .defineInRange("dailyQuota", 1000, 0, 100_000_000);
        MERCHANT_UNIT_PRICE = BUILDER
                .comment("Utopieces payees par item rachete.")
                .defineInRange("unitPrice", 1, 1, 1_000_000);
        MERCHANT_ANNOUNCE = BUILDER
                .comment("Annoncer sur le serveur quand sa reserve est epuisee, puis quand elle est",
                        "renouvelee a l'heure ci-dessus.")
                .define("announce", true);
        MERCHANT_ROTATE_STRUCTURE = BUILDER
                .comment("A cette meme heure, sa structure passe a l'etat suivant : le marchand ne",
                        "bouge pas, c'est son etal qui change de blocs chaque jour. La structure doit",
                        "etre en mode Manuel (les modes Jour/nuit et Horaires suivent le temps du jeu",
                        "et reprendraient la main aussitot).")
                .define("rotateStructure", true);
        BUILDER.pop(); // merchant

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

    /**
     * Valide une entree "modid:bloc | PERMISSION" : l'identifiant doit etre bien forme et la
     * permission, si elle est ecrite, doit exister. Le bloc lui-meme n'est pas verifie dans le
     * registre : on veut pouvoir preparer la ligne d'un mod qui n'est pas encore installe.
     */
    private static boolean validateRestrictedBlock(final Object obj) {
        if (!(obj instanceof String raw)) {
            return false;
        }
        String[] fields = raw.split("\\|", -1);
        if (ResourceLocation.tryParse(fields[0].trim()) == null) {
            return false;
        }
        if (fields.length < 2 || fields[1].trim().isEmpty()) {
            return true; // permission omise : CONTAINERS par defaut
        }
        String flag = fields[1].trim().toUpperCase(java.util.Locale.ROOT);
        for (com.utopia.parcel.Parcel.Flag f : com.utopia.parcel.Parcel.Flag.values()) {
            if (f.name().equals(flag)) {
                return true;
            }
        }
        return false;
    }

    /** Valide une entree de serveur : le nom dans le proxy est obligatoire, le reste facultatif. */
    private static boolean validateServerEntry(final Object obj) {
        return obj instanceof String raw && !raw.split("\\|", -1)[0].trim().isEmpty();
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
