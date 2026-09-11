package com.utopia.client.arcade;

import com.utopia.client.owo.GuiScaleLock;
import com.utopia.net.ArcadeScorePayload;
import com.utopia.net.MenuC2SPayload;
import com.utopia.net.OpenArcadePayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * La borne d'arcade vue de l'interieur : un cadre, un jeu qui tourne dedans, un score au-dessus.
 *
 * <p>Ce n'est pas un ecran owo mais un {@link Screen} nu, parce qu'un jeu a besoin de dessiner des
 * pixels et de recevoir les touches brutes, deux choses qu'une mise en page declarative rend
 * penible. Le score n'est envoye qu'une fois, a la fin de la partie.
 */
public final class ArcadeScreen extends Screen implements GuiScaleLock.Scaled {

    private final OpenArcadePayload data;
    private final ArcadeGame game;

    private long lastFrameNanos;
    private boolean scoreSent;
    private double overDelay;

    public ArcadeScreen(OpenArcadePayload data) {
        super(data.title());
        this.data = data;
        this.game = create(data.gameId());
    }

    private static ArcadeGame create(String gameId) {
        return switch (gameId) {
            case "tetris" -> new TetrisGame();
            case "breakout" -> new BreakoutGame();
            default -> new SnakeGame();
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false; // en solo, le monde doit continuer de tourner pendant la partie
    }

    /** Le cadre de jeu : centre, marges reservees au titre et au rappel des touches. */
    private int[] field() {
        int marginTop = 34;
        int marginBottom = 24;
        int maxW = Math.max(80, this.width - 40);
        int maxH = Math.max(60, this.height - marginTop - marginBottom);
        double aspect = game.aspect();
        int w = maxW;
        int h = (int) (w / aspect);
        if (h > maxH) {
            h = maxH;
            w = (int) (h * aspect);
        }
        int x = (this.width - w) / 2;
        int y = marginTop + (maxH - h) / 2;
        return new int[] {x, y, w, h};
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long nowNanos = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0 : (nowNanos - lastFrameNanos) / 1_000_000_000.0;
        lastFrameNanos = nowNanos;
        // Une image tres longue (changement de fenetre, chargement) ne doit pas faire avancer la
        // partie d'un bloc : on plafonne, le joueur ne perd pas sur un a-coup qui n'est pas le sien.
        dt = Math.min(dt, 0.1);

        if (!game.over()) {
            game.update(dt);
            if (game.over()) {
                sendScore();
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(
                        SoundEvents.NOTE_BLOCK_BASS.value(), 0.7f));
            }
        } else {
            overDelay += dt;
        }

        this.renderBackground(g, mouseX, mouseY, partialTick);
        int[] f = field();
        game.render(g, f[0], f[1], f[2], f[3]);
        renderHud(g, f);
        if (game.over()) {
            renderGameOver(g, f);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderHud(GuiGraphics g, int[] f) {
        g.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFD966);

        Component score = Component.literal("SCORE  " + game.score())
                .withStyle(s -> s.withColor(ChatFormatting.WHITE).withBold(true));
        g.drawString(this.font, score, f[0], 24, 0xFFFFFFFF, false);

        String recordText = data.bestScore() <= 0
                ? "RECORD  -"
                : "RECORD  " + data.bestScore()
                        + (data.bestHolder().isEmpty() ? "" : "  " + data.bestHolder());
        int recordWidth = this.font.width(recordText);
        g.drawString(this.font, recordText, f[0] + f[2] - recordWidth, 24, 0xFF9AA6B8, false);

        String hint = game.over() ? "Echap pour quitter" : game.hint();
        g.drawCenteredString(this.font, hint, this.width / 2, f[1] + f[3] + 8, 0xFF8A93A3);
    }

    private void renderGameOver(GuiGraphics g, int[] f) {
        g.fill(f[0], f[1], f[0] + f[2], f[1] + f[3], 0xB4000000);
        int cy = f[1] + f[3] / 2;
        g.drawCenteredString(this.font, Component.literal("PARTIE TERMINEE")
                .withStyle(s -> s.withColor(ChatFormatting.RED).withBold(true)),
                this.width / 2, cy - 14, 0xFFFF5555);
        g.drawCenteredString(this.font, Component.literal(game.score() + " points")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD)),
                this.width / 2, cy, 0xFFFFD966);
        if (data.bestScore() > 0 && game.score() > data.bestScore()) {
            g.drawCenteredString(this.font, Component.literal("NOUVEAU RECORD")
                    .withStyle(s -> s.withColor(ChatFormatting.LIGHT_PURPLE).withBold(true)),
                    this.width / 2, cy + 14, 0xFFFF88FF);
        }
        // Le rappel ne s'affiche qu'apres une seconde : sinon on quitte par reflexe avant d'avoir
        // vu son score.
        if (overDelay > 1.0) {
            g.drawCenteredString(this.font, "Echap pour quitter", this.width / 2, cy + 30, 0xFF8A93A3);
        }
    }

    private void sendScore() {
        if (scoreSent) {
            return;
        }
        scoreSent = true;
        PacketDistributor.sendToServer(MenuC2SPayload.of(
                new ArcadeScorePayload(data.sessionId(), game.score())));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // Echap
            this.onClose();
            return true;
        }
        if (!game.over() && game.keyPressed(keyCode)) {
            return true;
        }
        // On ne laisse pas la touche d'inventaire ni les autres raccourcis fermer la borne en
        // pleine partie : une flechette mal placee couterait la partie payee.
        return true;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        int[] f = field();
        if (f[2] > 0 && f[3] > 0) {
            game.mouseMoved((mouseX - f[0]) / f[2], (mouseY - f[1]) / f[3]);
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!game.over()) {
            int[] f = field();
            game.mouseClicked((mouseX - f[0]) / f[2], (mouseY - f[1]) / f[3], button);
        }
        return true;
    }

    @Override
    public void onClose() {
        // Une partie abandonnee compte quand meme : le joueur a paye, son score lui revient.
        if (game.score() > 0) {
            sendScore();
        }
        super.onClose();
    }
}
