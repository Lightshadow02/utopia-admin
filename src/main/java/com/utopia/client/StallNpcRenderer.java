package com.utopia.client;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.utopia.entity.SkinNpc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * Rendu commun des PNJ du mod (stands de marche, marchands) : modele joueur avec le skin par defaut
 * (Steve) ou celui porte par l'entite.
 *
 * <p>Le skin est reconstruit a partir de la propriete "textures" synchronisee : il s'affiche donc
 * meme si le joueur d'origine est hors ligne. Le gestionnaire de skins gere telechargement et cache.
 *
 * @param <T> une entite vivante qui expose un skin ({@link SkinNpc})
 */
public class StallNpcRenderer<T extends LivingEntity & SkinNpc>
        extends LivingEntityRenderer<T, PlayerModel<T>> {

    /** Faut-il afficher l'etiquette de nom au-dessus du PNJ ? */
    private final boolean showName;

    public StallNpcRenderer(EntityRendererProvider.Context context, boolean showName) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
        this.showName = showName;
        // Couche de la 2e peau (chapeau/veste...) pour un rendu fidele.
        this.addLayer(new CustomHeadLayer<>(this, context.getModelSet(), context.getItemInHandRenderer()));
    }

    @Override
    protected boolean shouldShowName(T entity) {
        return showName && super.shouldShowName(entity);
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        String value = entity.skinValue();
        if (value == null || value.isEmpty()) {
            return DefaultPlayerSkin.getDefaultTexture(); // pas de skin defini -> Steve
        }
        String name = entity.ownerName();
        GameProfile profile = new GameProfile(
                UUID.nameUUIDFromBytes(("utopia_npc:" + name).getBytes(StandardCharsets.UTF_8)),
                name.isEmpty() ? "Npc" : name);
        String signature = entity.skinSignature();
        profile.getProperties().put("textures",
                new Property("textures", value, signature.isEmpty() ? null : signature));
        PlayerSkin skin = Minecraft.getInstance().getSkinManager().getInsecureSkin(profile);
        return skin.texture();
    }
}
