package com.utopia.mairie;

import java.util.ArrayList;
import java.util.List;

import com.utopia.data.MairieData;
import com.utopia.data.MarketData;
import com.utopia.economy.EconomyManager;

import net.minecraft.server.MinecraftServer;

/**
 * Percepteur de la mairie : un seul endroit ou l'on demande "combien cette transaction doit-elle a
 * la commune", pour que chaque flux d'Utopieces reponde de la meme facon.
 *
 * <p>Toutes les taxes d'un flux s'additionnent, mais le cumul est <b>plafonne</b>
 * ({@link MairieData#MAX_TOTAL_TAX} % du montant). Sans ce plafond, deux taxes de 60 % rendraient le
 * net negatif, et un solde negatif est ramene a zero sans un mot par la banque : le joueur perdrait
 * tout apres avoir livre. Le plafond atteint, les taxes suivantes ne prennent rien — elles sont
 * servies dans leur ordre de creation, ce qui rend le resultat previsible et explicable.
 */
public final class TaxManager {

    /** Une ligne de prelevement : de quoi rendre le detail au joueur, taxe par taxe. */
    public record Line(String id, String name, long amount) {
    }

    /** Le resultat d'un calcul de taxes : le total et son detail. */
    public record Levy(long total, List<Line> lines) {

        public static final Levy NONE = new Levy(0L, List.of());

        public boolean isEmpty() {
            return total <= 0;
        }
    }

    private TaxManager() {
    }

    /**
     * Calcule ce qui serait preleve, sans rien deplacer. Sert aux ecrans et aux messages qui doivent
     * annoncer la retenue avant qu'elle n'ait lieu.
     */
    public static Levy quote(MinecraftServer server, MairieData.Flow flow, long amount) {
        if (amount <= 0) {
            return Levy.NONE;
        }
        MairieData data = MairieData.get(server);
        long budget = amount * MairieData.MAX_TOTAL_TAX / 100L;
        if (budget <= 0) {
            return Levy.NONE;
        }
        List<Line> lines = new ArrayList<>();
        long total = 0;

        // La taxe sur les devis est anterieure aux pouvoirs fiscaux du maire et se regle ailleurs :
        // elle survit meme quand l'administrateur retire le module de taxes.
        boolean mairieTaxes = com.utopia.Config.MAIRIE_TAXES.get();
        int base = switch (flow) {
            case SALAIRE -> mairieTaxes ? data.salaryTaxPercent() : 0;
            case DEVIS -> com.utopia.data.QuoteData.get(server).taxPercent();
            default -> 0;
        };
        if (base > 0) {
            long due = Math.min(amount * base / 100L, budget);
            if (due > 0) {
                lines.add(new Line(null, baseName(flow), due));
                total += due;
            }
        }
        for (MairieData.Tax tax : mairieTaxes ? data.taxesFor(flow) : List.<MairieData.Tax>of()) {
            if (total >= budget) {
                break;
            }
            long due = Math.min(tax.on(amount), budget - total);
            if (due > 0) {
                lines.add(new Line(tax.id, tax.name, due));
                total += due;
            }
        }
        return total <= 0 ? Levy.NONE : new Levy(total, List.copyOf(lines));
    }

    /**
     * Calcule, verse le produit a la mairie et met a jour les cumuls percus. L'argent preleve est
     * suppose <b>deja retire</b> du payeur par l'appelant : cette methode ne debite personne, elle
     * ne fait que rediriger la part de la commune avant que le reste ne soit credite au beneficiaire.
     */
    public static Levy collect(MinecraftServer server, MairieData.Flow flow, long amount) {
        Levy levy = quote(server, flow, amount);
        if (levy.isEmpty()) {
            return levy;
        }
        MairieData data = MairieData.get(server);
        EconomyManager.add(server, MarketData.MAIRIE_UUID, levy.total());
        for (Line line : levy.lines()) {
            if (line.id() == null) {
                continue; // un impot de droit commun n'a pas de compteur propre
            }
            MairieData.Tax tax = data.tax(line.id());
            if (tax != null) {
                tax.collected += line.amount();
            }
        }
        data.setDirty();
        return levy;
    }

    /** Nom de l'impot de droit commun du flux, celui qui existait avant les taxes nommees. */
    private static String baseName(MairieData.Flow flow) {
        return flow == MairieData.Flow.SALAIRE ? "Impot sur les salaires" : "Taxe sur les devis";
    }

    /**
     * Virement entre joueurs, taxe au passage : le destinataire recoit le montant moins la part de
     * la commune. Renvoie null si le payeur n'a pas de quoi, auquel cas rien n'a bouge.
     */
    public static Levy transferTaxed(MinecraftServer server, java.util.UUID from,
                                     java.util.UUID to, long amount) {
        if (amount <= 0 || !EconomyManager.remove(server, from, amount)) {
            return null;
        }
        Levy levy = collect(server, MairieData.Flow.VIREMENT, amount);
        EconomyManager.add(server, to, amount - levy.total());
        return levy;
    }

    /** Detail lisible en une ligne : "Taxe biodiversite 12, Impot sur les salaires 30". */
    public static String describe(Levy levy) {
        if (levy.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>(levy.lines().size());
        for (Line line : levy.lines()) {
            parts.add(line.name() + " " + line.amount());
        }
        return String.join(", ", parts);
    }

    /** Resume court pour les messages de chat : "- 42 Utopieces de taxes (detail)". */
    public static String suffix(Levy levy) {
        return levy.isEmpty() ? "" : " - " + levy.total() + " de taxes (" + describe(levy) + ")";
    }
}
