package com.utopia.casino;

import java.util.List;

/**
 * Les cartes a collectionner que crachent les machines a capsules : les repliques, les surnoms et
 * les betises qui ont fait le serveur.
 *
 * <p>Le texte est fige ici, la <b>rarete</b> non : elle se regle en jeu, carte par carte. Decider a
 * la place des joueurs laquelle de leurs vannes vaut une legendaire n'aurait aucun sens - eux seuls
 * le savent. Tout part donc en commune, a eux de faire monter leurs preferees.
 */
public final class GachaCards {

    /** Une carte du jeu de collection. La rarete vit dans les donnees, pas ici. */
    public record Card(String id, String text) {
    }

    /** Les raretes, de la plus courante a la plus rare. Le poids est la chance relative de tirage. */
    public enum Rarity {
        COMMUNE("Commune", net.minecraft.ChatFormatting.WHITE, 100),
        RARE("Rare", net.minecraft.ChatFormatting.AQUA, 30),
        EPIQUE("Epique", net.minecraft.ChatFormatting.LIGHT_PURPLE, 8),
        LEGENDAIRE("Legendaire", net.minecraft.ChatFormatting.GOLD, 2);

        public final String label;
        public final net.minecraft.ChatFormatting color;
        public final int weight;

        Rarity(String label, net.minecraft.ChatFormatting color, int weight) {
            this.label = label;
            this.color = color;
            this.weight = weight;
        }

        /** La rarete suivante, en boucle : un clic la fait monter d'un cran. */
        public Rarity next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public static final List<Card> ALL = List.of(
            new Card("je_suis_une_princesse", "Je suis une princesse"),
            new Card("la_stagiere_a_vire_le_patron", "La stagiere a vire le patron"),
            new Card("feur", "Feur."),
            new Card("aussi_cours_que_la_carriere_de_p", "Aussi cours que la carriere de parent d'ora..."),
            new Card("le_caca_est_cuit", "Le caca est cuit"),
            new Card("bonsoir_hugo", "Bonsoir Hugo !"),
            new Card("luxia_elle_est_pas_trop_lezard_a", "Luxia, elle est pas trop lezard avec nous."),
            new Card("les_demissons_de_luxia", "Les demissons de luxia"),
            new Card("je_suis_qu_une_particule", "Je suis qu'une particule"),
            new Card("ora_ora_ora_l_exploratrice", "Ora, ora, ora l'exploratrice !"),
            new Card("awa_de_luxia_et_taiyo", "AWA (de Luxia et taiyo)"),
            new Card("bourri_il_est_chauve", "Bourri il est chauve"),
            new Card("on_va_allez_tuer_d_autre_gens", "On va allez tuer d'autre gens..."),
            new Card("tu_est_un_saucier_a_riz", "Tu est un saucier a riz"),
            new Card("au_champs_elyse", "Au champs elyse"),
            new Card("conforama", "Conforama !"),
            new Card("euh_gnegnegne_teuteute", "Euh gnegnegne teuteute..."),
            new Card("l_abeille_meeeeee", "L'abeille Meeeeee"),
            new Card("j_aifaituneora", "#j'aifaituneora"),
            new Card("50_nuances_du_trouple", "50 nuances du trouple"),
            new Card("tu_feras_t_es_moche", "Tu feras t'es moche"),
            new Card("cachez_de_bbux", "Cachez de Bbux"),
            new Card("dinausor_grrrr_graouuu", "Dinausor ! Grrrr ! graouuu"),
            new Card("j_ai_mange_un_chinois_est_mainte", "J'ai mange un chinois, est maintenant il est plus la !"),
            new Card("j_ai_mange_un_p_tit_louis_est_ma", "J'ai mange un p'tit louis est maintenant il est parti"),
            new Card("le_p_tit_chinois_l_axolote_jaune", "Le p'tit chinois (l'axolote jaune de luxia)"),
            new Card("luxia_gamine_insuportable", "Luxia gamine #insuportable"),
            new Card("tu_est_un_pain", "tu est un pain !"),
            new Card("uwu", "UwU"),
            new Card("j_adore_les_gnocchi_a_poil", "J'adore les gnocchi a poil"),
            new Card("mais_ca_va_pas_ou_quoi_la", "Mais ca va pas ou quoi la ?"),
            new Card("titre", "Titre !"),
            new Card("c_est_qui_louis_lui", "C'est qui louis (lui) ?"),
            new Card("qwack_canard_de_luxia", "Qwack ! (canard de luxia)"),
            new Card("mais_moi_j_veux_faire_l_amour", "Mais moi j'veux faire l'amour !"),
            new Card("allez_ferme_ta_geule_oni", "Allez ferme ta geule ! (oni)"),
            new Card("t_es_mignionne_connasse_louis", "T'es mignionne connasse (Louis)"),
            new Card("ta_geule_taiyo", "Ta geule ! (Taiyo)"),
            new Card("chausette_donjon_de_nahalbuck", "Chausette ! (Donjon de nahalbuck)"),
            new Card("oula_mais_bonjour_d_abord", "Oula ! mais bonjour d'abord"));

    private GachaCards() {
    }

    public static Card byId(String id) {
        for (Card c : ALL) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }
}
