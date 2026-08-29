package com.utopia.client.owo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Force les menus du mod a s'afficher a l'echelle 2, quelle que soit celle choisie par le joueur.
 *
 * <p>A l'echelle 3 ou 4, un tableau ou un panneau ne tient plus dans la fenetre : les colonnes se
 * replient, les lignes debordent, et l'information disparait sans que rien ne l'annonce. Plutot que
 * de dessiner moins a mesure qu'on grossit, on fixe l'echelle des menus une fois pour toutes.
 *
 * <p>L'echelle du joueur est rendue des qu'il quitte le dernier ecran du mod. Le retour se fait dans
 * le tick client et non a la fermeture de l'ecran : au moment ou un menu se ferme, le suivant n'est
 * pas encore en place, et restaurer la pour la reprendre aussitot ferait clignoter tout l'ecran a
 * chaque navigation.
 */
public final class GuiScaleLock {

    /** Echelle imposee aux menus du mod. */
    private static final double TARGET = 2.0;

    /** Marque les ecrans du mod : tant que l'un d'eux est affiche, l'echelle reste tenue. */
    public interface Scaled {
    }

    /** Echelle du joueur mise de cote, ou -1 si nous n'avons rien change. */
    private static double saved = -1;

    private GuiScaleLock() {
    }

    /** A appeler a l'ouverture d'un ecran du mod. */
    public static void apply() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            return;
        }
        Options options = mc.options;
        boolean unicode = options != null && options.forceUnicodeFont().get();
        // Une petite fenetre peut ne pas supporter l'echelle 2 : on ne demande jamais plus que ce
        // qu'elle accepte, sinon Minecraft rendrait un ecran tronque.
        double max = mc.getWindow().calculateScale(0, unicode);
        double target = Math.min(TARGET, max);
        if (mc.getWindow().getGuiScale() == target) {
            return;
        }
        if (saved < 0) {
            saved = mc.getWindow().getGuiScale();
        }
        mc.getWindow().setGuiScale(target);
        mc.resizeDisplay();
    }

    /** Rend son echelle au joueur des qu'il n'a plus aucun ecran du mod devant les yeux. */
    public static void onClientTick(ClientTickEvent.Post event) {
        if (saved < 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.screen instanceof Scaled) {
            return;
        }
        restore();
    }

    private static void restore() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            saved = -1;
            return;
        }
        double previous = saved;
        saved = -1;
        mc.getWindow().setGuiScale(previous);
        mc.resizeDisplay();
    }
}
