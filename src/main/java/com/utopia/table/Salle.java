package com.utopia.table;

import java.util.UUID;

import com.utopia.data.TableData;

import net.minecraft.server.MinecraftServer;

/** L'etat vivant d'une table. Chaque jeu a le sien ; le gestionnaire ne connait que ceci. */
public interface Salle {

    /** Appele une fois par seconde. */
    void tick(MinecraftServer server, TableData.Table table);

    /** Quelqu'un quitte la table, de son plein gre ou en se deconnectant. */
    void quitte(MinecraftServer server, TableData.Table table, UUID joueur);

    /** Vrai quand plus rien ne se joue : le gestionnaire peut alors oublier la table. */
    boolean dormante();

    /** Rend tout ce qui est engage et n'a pas ete joue. Fermeture de table, arret du serveur. */
    void toutRembourser(MinecraftServer server, TableData.Table table);
}
