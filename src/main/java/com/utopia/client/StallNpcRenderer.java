package com.utopia.client;

import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.utopia.entity.StallNpc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;

/**
 * Rendu du PNJ d'un stand : modele joueur classique, avec le skin par defaut (Steve) tant que le
 * stand est libre, et le skin du proprietaire une fois reserve.
 *
 * <p>Le skin est reconstruit a partir de la propriete "textures" synchronisee par l'entite : il
 * s'affiche donc meme si le proprietaire est hors ligne. Le gestionnaire de skins de Minecraft gere
 * le telechargement et le cache.
 */
public class StallNpcRenderer extends LivingEntityRenderer<StallNpc, PlayerModel<StallNpc>> {

    public StallNpcRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
        // Couche de la 2e peau (chapeau/veste...) pour un rendu fidele.
        this.addLayer(new CustomHeadLayer<>(this, context.getModelSet(), context.getItemInHandRenderer()));
    }

    /** Jamais d'etiquette au-dessus du PNJ : l'hologramme du stand porte deja le nom. */
    @Override
    protected boolean shouldShowName(StallNpc entity) {
        return false;
    }

    @Override
    public ResourceLocation getTextureLocation(StallNpc entity) {
        String value = entity.skinValue();
        if (value == null || value.isEmpty()) {
            return DefaultPlayerSkin.getDefaultTexture(); // stand libre -> Steve
        }
        String name = entity.ownerName();
        GameProfile profile = new GameProfile(
                UUID.nameUUIDFromBytes(("utopia_stall:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                name.isEmpty() ? "Stand" : name);
        String signature = entity.skinSignature();
        profile.getProperties().put("textures",
                new Property("textures", value, signature.isEmpty() ? null : signature));
        PlayerSkin skin = Minecraft.getInstance().getSkinManager().getInsecureSkin(profile);
        return skin.texture();
    }
}
