package com.utopia.entity;

/**
 * Un PNJ rendu avec le modele joueur et un skin arbitraire (propriete "textures" du profil).
 * Implemente par les PNJ des stands et des boutiques : permet un rendu commun.
 */
public interface SkinNpc {

    /** Nom affiche (peut etre vide). */
    String ownerName();

    /** Valeur de la propriete "textures" ; vide = skin par defaut (Steve). */
    String skinValue();

    /** Signature de la propriete "textures" ; vide = non signee. */
    String skinSignature();
}
