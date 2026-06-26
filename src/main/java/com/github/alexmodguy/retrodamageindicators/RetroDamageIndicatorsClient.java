package com.github.alexmodguy.retrodamageindicators;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

public class RetroDamageIndicatorsClient implements ClientModInitializer {
    public static final String MODID = "retrodamageindicators";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation DAMAGE_INDICATOR_TEXTURE = ResourceLocation.fromNamespaceAndPath(MODID, "textures/gui/damage_indicator.png");
    private static final ResourceLocation DAMAGE_INDICATOR_BACKGROUND_TEXTURE = ResourceLocation.fromNamespaceAndPath(MODID, "textures/gui/damage_indicator_background.png");
    private static final ResourceLocation DAMAGE_INDICATOR_HEALTH_TEXTURE = ResourceLocation.fromNamespaceAndPath(MODID, "textures/gui/damage_indicator_health.png");
    private static final Quaternionf ENTITY_ROTATION = (new Quaternionf()).rotationXYZ((float) Math.toRadians(30), (float) Math.toRadians(130), (float) Math.PI);
    private static LivingEntity damageIndicatorEntity;
    private static MobTypes currentMobType = MobTypes.UNKNOWN;
    private static String currentModSource = "";
    private static int resetDamageIndicatorEntityIn = 0;
    private static boolean renderModelOnly;
    private static float displayedHealth = 0f;
    private static float lastKnownHealth = -1f;
    private static int damageFlashTicks = 0;
    private static final List<DamageText> activeDamageTexts = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        Config.INSTANCE.load();
        HudRenderCallback.EVENT.register(RetroDamageIndicatorsClient::onHudRender);
        ClientTickEvents.END_CLIENT_TICK.register(RetroDamageIndicatorsClient::onClientTick);
        WorldRenderEvents.AFTER_ENTITIES.register(RetroDamageIndicatorsClient::onWorldRender);
    }

    public static float roundHealth(float entityHealth) {
        return (float) (Math.round(entityHealth * 5) / 5D);
    }

    public static void spawnHurtParticles(Entity entity, float damage) {
        if (Minecraft.getInstance().level == null) return;
        if (activeDamageTexts.size() >= 100) return;
        double x = entity.getRandomX(1.0D);
        double y = entity.getEyeY();
        double z = entity.getRandomZ(1.0D);
        String textStr;
        if (Config.INSTANCE.healthDecimals.get()) {
            textStr = String.valueOf(roundHealth((float) Math.abs(damage))).replace(".0", "");
        } else {
            textStr = "" + (int) Math.abs(damage);
        }
        boolean heal = damage > 0;
        int color = heal ? 0x00FF00 : 0xFF0000;
        int colorOutline = heal ? 0x003300 : 0x330000;
        activeDamageTexts.add(new DamageText(x, y, z, Component.literal(textStr), color, colorOutline));
    }

    private static void onHudRender(GuiGraphics guiGraphics, net.minecraft.client.DeltaTracker tickCounter) {
        if (!Config.INSTANCE.hudIndicatorEnabled.get() || Minecraft.getInstance().screen != null) return;
        if (damageIndicatorEntity == null) return;

        float entityMaxHealth = damageIndicatorEntity.getMaxHealth();
        float entityHealth = Math.min(Config.INSTANCE.hpBarAnimated.get() ? displayedHealth : damageIndicatorEntity.getHealth(), entityMaxHealth);
        float healthRatio = entityMaxHealth <= 0.0F ? 0.0F : entityHealth / entityMaxHealth;
        float scale = Config.INSTANCE.hudIndicatorSize.get().floatValue();
        int xOffset = Config.INSTANCE.hudIndicatorAlignLeft.get() ? Config.INSTANCE.hudIndicatorPositionX.get() : guiGraphics.guiWidth() - (int) (208 * scale) - Config.INSTANCE.hudIndicatorPositionX.get();
        int yOffset = Config.INSTANCE.hudIndicatorAlignTop.get() ? Config.INSTANCE.hudIndicatorPositionY.get() : guiGraphics.guiHeight() - (int) (78 * scale) - Config.INSTANCE.hudIndicatorPositionY.get();
        if (Minecraft.getInstance().gui instanceof Gui forgeGui) {
            int bossBars = 0;
            if (Config.INSTANCE.hudIndicatorAlignTop.get()) {
                if (bossBars > 0) {
                    yOffset += Math.min(guiGraphics.guiHeight() / 3, 12 + 19 * bossBars);
                }
                if (!Config.INSTANCE.hudIndicatorAlignLeft.get()) {
                    int potionsActive = 0;
                    for (MobEffectInstance e : Minecraft.getInstance().player.getActiveEffects()) {
                        if (e.showIcon()) potionsActive++;
                    }
                    yOffset += Math.min(potionsActive, 2) * 24;
                }
            }
        }
        float backgroundOpacity = Config.INSTANCE.hudIndicatorBackgroundOpacity.get().floatValue();
        int relativeHealthbarX = 81;
        int relativeHealthbarY = 25;
        int healthbarHeight = 18;
        int healthbarMaxWidth = 124;
        int currentHealthbarWidth = (int) Math.round(healthbarMaxWidth * healthRatio);
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        poseStack.translate(xOffset, yOffset - 0.5F, 0);
        poseStack.scale(scale, scale, scale);

        int scissorBox1MinX = 16, scissorBox1MinY = 4, scissorBox1MaxX = 73, scissorBox1MaxY = 49;
        int scissorBox2MinX = 28, scissorBox2MinY = 49, scissorBox2MaxX = 73, scissorBox2MaxY = 61;
        int entityX = 45, entityY = 56;

        guiGraphics.enableScissor(xOffset + Math.round(scale * scissorBox1MinX), yOffset + Math.round(scale * scissorBox1MinY), xOffset + Math.round(scale * scissorBox1MaxX), yOffset + Math.round(scale * scissorBox1MaxY));
        renderEntityInGui(guiGraphics, entityX, entityY, computeRenderScale(), ENTITY_ROTATION, damageIndicatorEntity, tickCounter.getGameTimeDeltaPartialTick(true));
        guiGraphics.disableScissor();
        guiGraphics.enableScissor(xOffset + Math.round(scale * scissorBox2MinX), yOffset + Math.round(scale * scissorBox2MinY), xOffset + Math.round(scale * scissorBox2MaxX), yOffset + Math.round(scale * scissorBox2MaxY));
        renderEntityInGui(guiGraphics, entityX, entityY, computeRenderScale(), ENTITY_ROTATION, damageIndicatorEntity, tickCounter.getGameTimeDeltaPartialTick(true));
        guiGraphics.disableScissor();

        poseStack.pushPose();
        poseStack.translate(0, 0, -200);

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, backgroundOpacity);
        guiGraphics.blit(DAMAGE_INDICATOR_BACKGROUND_TEXTURE, 0, 0, 50, 0, 0, 208, 78, 256, 256);
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        guiGraphics.blit(DAMAGE_INDICATOR_TEXTURE, 0, 0, 50, 0, 0, 208, 78, 256, 256);
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        int relativeMobTypeX = 5, relativeMobTypeY = 55;
        guiGraphics.blit(currentMobType.getTexture(), relativeMobTypeX, relativeMobTypeY, 50, 0, 0, 18, 18, 18, 18);

        int healthbarVOffset = Config.INSTANCE.colorblindHealthBar.get() ? 36 : 0;
        guiGraphics.blit(DAMAGE_INDICATOR_HEALTH_TEXTURE, relativeHealthbarX, relativeHealthbarY, 50, 0, healthbarVOffset + 18, healthbarMaxWidth, healthbarHeight, 256, 256);
        guiGraphics.blit(DAMAGE_INDICATOR_HEALTH_TEXTURE, relativeHealthbarX, relativeHealthbarY, 50, 0, healthbarVOffset, currentHealthbarWidth, healthbarHeight, 256, 256);

        poseStack.popPose();

        // health text
        String healthText;
        float healthOffsetX = 136, healthOffsetY = 30;
        String healthDivisor;
        int firstHalfWidth;
        if (Config.INSTANCE.healthSeperator.get()) {
            healthDivisor = " | ";
        } else {
            healthDivisor = "/";
            healthOffsetX += 4;
        }
        if (Config.INSTANCE.healthDecimals.get()) {
            healthText = roundHealth(entityHealth) + healthDivisor + roundHealth(entityMaxHealth);
            firstHalfWidth = Minecraft.getInstance().font.width("" + roundHealth(entityHealth));
        } else {
            healthText = (int) entityHealth + healthDivisor + (int) entityMaxHealth;
            firstHalfWidth = Minecraft.getInstance().font.width("" + (int) entityHealth);
        }
        Component healthComponent = Component.literal(healthText);
        int healthWidth = Minecraft.getInstance().font.width(healthComponent);
        float healthScale = Math.min(88F / (float) healthWidth, 1.35F);
        int healthColor = 0xFFFFFF, healthOutlineColor = 0;

        poseStack.pushPose();
        poseStack.translate(healthOffsetX, healthOffsetY, 0);
        poseStack.scale(healthScale, healthScale, 1);
        poseStack.translate(-firstHalfWidth, 0, -50);
        if (Config.INSTANCE.hudHealthTextOutline.get()) {
            Minecraft.getInstance().font.drawInBatch8xOutline(healthComponent.getVisualOrderText(), 0.0F, 0.0F, healthColor, healthOutlineColor, poseStack.last().pose(), guiGraphics.bufferSource(), 15728880);
        } else {
            Minecraft.getInstance().font.drawInBatch(healthComponent.getVisualOrderText(), 0.0F, 0.0F, healthColor, true, poseStack.last().pose(), guiGraphics.bufferSource(), Font.DisplayMode.NORMAL, 0, 15728880);
        }
        poseStack.popPose();

        // name text
        Component nameComponent = damageIndicatorEntity.getDisplayName();
        int nameWidth = Minecraft.getInstance().font.width(nameComponent);
        float nameScale = Math.min(113F / (float) nameWidth, 1.25F);
        float nameOffsetX = 138.5F, nameOffsetY = 6.5F;
        int nameColor = 0xFFFFFF, nameOutlineColor = 0;

        poseStack.pushPose();
        poseStack.translate(nameOffsetX, nameOffsetY, 0);
        poseStack.scale(nameScale, nameScale, 1);
        poseStack.translate(-nameWidth / 2F, 0, -50);
        if (Config.INSTANCE.hudNameTextOutline.get()) {
            Minecraft.getInstance().font.drawInBatch8xOutline(nameComponent.getVisualOrderText(), 0.0F, 0.0F, nameColor, nameOutlineColor, poseStack.last().pose(), guiGraphics.bufferSource(), 15728880);
        } else {
            Minecraft.getInstance().font.drawInBatch(nameComponent.getVisualOrderText(), 0.0F, 0.0F, nameColor, true, poseStack.last().pose(), guiGraphics.bufferSource(), Font.DisplayMode.NORMAL, 0, 15728880);
        }
        poseStack.popPose();

        // mod source text
        if (Config.INSTANCE.showModSource.get() && !currentModSource.isEmpty()) {
            Component modSourceComponent = Component.literal("[" + currentModSource + "]");
            int modSourceWidth = Minecraft.getInstance().font.width(modSourceComponent);
            float maxScale = Config.INSTANCE.modSourceSize.get().floatValue();
            float modSourceScale = Math.min(110F / modSourceWidth, maxScale);
            float modSourceX = 143F + Config.INSTANCE.modSourceOffsetX.get();
            float modSourceY = 46F + Config.INSTANCE.modSourceOffsetY.get();
            int modSourceColor = Config.INSTANCE.modSourceColor.get();

            poseStack.pushPose();
            poseStack.translate(modSourceX, modSourceY, -50);
            poseStack.scale(modSourceScale, modSourceScale, 1);
            poseStack.translate(-modSourceWidth / 2F, 0, 0);
            Minecraft.getInstance().font.drawInBatch(modSourceComponent.getVisualOrderText(), 0.0F, 0.0F, modSourceColor, false, poseStack.last().pose(), guiGraphics.bufferSource(), Font.DisplayMode.NORMAL, 0, 15728880);
            poseStack.popPose();
        }

        // damage flash overlay
        if (Config.INSTANCE.damageFlash.get() && damageFlashTicks > 0) {
            float flashAlpha = (damageFlashTicks / (float) Config.INSTANCE.damageFlashDuration.get()) * 0.45f;
            int color = ((int)(flashAlpha * 255) << 24) | 0xFF2200;
            guiGraphics.fill(0, 0, 208, 78, color);
        }

        poseStack.popPose();
    }

    private static float computeRenderScale() {
        float biggestEntityDimension = Math.max(damageIndicatorEntity.getBbWidth() * 1.2F + 0.3F, damageIndicatorEntity.getBbHeight() * 0.9F) * 0.85F;
        float renderScale = Config.INSTANCE.hudEntitySize.get().floatValue();
        if ((double) biggestEntityDimension > 0.5D) renderScale /= biggestEntityDimension;
        return renderScale;
    }

    private static void onClientTick(Minecraft client) {
        if (client.cameraEntity == null) {
            activeDamageTexts.clear();
            return;
        }
        Entity cameraEntity = client.cameraEntity;
        double maxPickDistance = Config.INSTANCE.maxDistance.get();
        double pickDistance = maxPickDistance;
        Vec3 vec3 = cameraEntity.getEyePosition(client.getTimer().getGameTimeDeltaPartialTick(true));
        HitResult hitResult = cameraEntity.pick(pickDistance, client.getTimer().getGameTimeDeltaPartialTick(true), false);
        LivingEntity found = null;
        if (hitResult != null && hitResult.getType() != HitResult.Type.MISS) {
            pickDistance = hitResult.getLocation().distanceToSqr(vec3);
        }
        Vec3 vec31 = cameraEntity.getViewVector(1.0F);
        Vec3 vec32 = vec3.add(vec31.x * maxPickDistance, vec31.y * maxPickDistance, vec31.z * maxPickDistance);
        AABB aabb = cameraEntity.getBoundingBox().expandTowards(vec31.scale(maxPickDistance)).inflate(3.0D, 3.0D, 3.0D);
        EntityHitResult entityhitresult = ProjectileUtil.getEntityHitResult(cameraEntity, vec3, vec32, aabb, (lookingAt) -> !lookingAt.isSpectator() && lookingAt.isPickable(), pickDistance);
        if (entityhitresult != null) {
            Vec3 vec33 = entityhitresult.getLocation();
            Entity entity = entityhitresult.getEntity();
            double d2 = vec3.distanceToSqr(vec33);
            if (d2 < pickDistance && entity instanceof LivingEntity living && living.isAlive() && !(living instanceof ArmorStand)) {
                found = (LivingEntity) entity;
            }
        }
        if (found != null) {
            float currentHealth = found.getHealth();

            if (found != damageIndicatorEntity) {
                displayedHealth = currentHealth;
                lastKnownHealth = currentHealth;
                damageFlashTicks = 0;
            }

            if (Config.INSTANCE.damageFlash.get() && currentHealth < lastKnownHealth - 0.01f) {
                damageFlashTicks = Config.INSTANCE.damageFlashDuration.get();
            }
            lastKnownHealth = currentHealth;

            float speed = Config.INSTANCE.hpBarAnimationSpeed.get().floatValue();
            displayedHealth += (currentHealth - displayedHealth) * speed;
            if (Math.abs(displayedHealth - currentHealth) < 0.05f) displayedHealth = currentHealth;

            damageIndicatorEntity = found;
            currentMobType = MobTypes.getTypeFor(found);
            resetDamageIndicatorEntityIn = Config.INSTANCE.hudLingerTime.get();
            renderModelOnly = Config.INSTANCE.oldRenderEntities.get().contains(BuiltInRegistries.ENTITY_TYPE.getKey(found.getType()).toString());
            String modId = BuiltInRegistries.ENTITY_TYPE.getKey(found.getType()).getNamespace();
            currentModSource = FabricLoader.getInstance().getModContainer(modId)
                    .map(c -> c.getMetadata().getName())
                    .orElse(modId);
        } else if (resetDamageIndicatorEntityIn-- < 0) {
            damageIndicatorEntity = null;
            currentModSource = "";
            displayedHealth = 0f;
            lastKnownHealth = -1f;
            resetDamageIndicatorEntityIn = 0;
        }

        if (damageFlashTicks > 0) damageFlashTicks--;

        if (!client.isPaused()) {
            for (DamageText dt : activeDamageTexts) {
                dt.age++;
                dt.vy -= 0.04;
                dt.y += dt.vy;
            }
            activeDamageTexts.removeIf(dt -> dt.age >= dt.maxAge);
        }
    }

    private static void onWorldRender(WorldRenderContext context) {
        if (activeDamageTexts.isEmpty() || !Config.INSTANCE.damageParticlesEnabled.get()) return;

        PoseStack poseStack = context.matrixStack();
        if (poseStack == null) return;
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        Vec3 cameraPos = context.camera().getPosition();

        for (DamageText dt : activeDamageTexts) {
            float lifeRatio = 1.0f - (float) dt.age / dt.maxAge;
            float scale = 0.025f * lifeRatio * Config.INSTANCE.damageParticleSize.get().floatValue();
            if (scale <= 0) continue;

            poseStack.pushPose();
            poseStack.translate(dt.x - cameraPos.x, dt.y - cameraPos.y, dt.z - cameraPos.z);
            poseStack.mulPose(context.camera().rotation());
            poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
            poseStack.scale(scale, scale, scale);

            float textX = -Minecraft.getInstance().font.width(dt.text) / 2f;
            if (Config.INSTANCE.damageParticleOutline.get()) {
                Minecraft.getInstance().font.drawInBatch8xOutline(
                        dt.text.getVisualOrderText(), textX, 0f,
                        dt.color, dt.colorOutline,
                        poseStack.last().pose(), bufferSource, 15728880);
            } else {
                Minecraft.getInstance().font.drawInBatch(
                        dt.text.getVisualOrderText(), textX, 0f,
                        dt.color, false, poseStack.last().pose(), bufferSource,
                        Font.DisplayMode.SEE_THROUGH, 0, 15728880);
            }
            poseStack.popPose();
        }
        bufferSource.endBatch();
    }

    public static void renderEntityInGui(GuiGraphics guiGraphics, int xPos, int yPos, float scale, Quaternionf rotation, Entity entity, float partialTicks) {
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate((double) xPos, (double) yPos, -60.0D);
        guiGraphics.pose().mulPose((new Matrix4f()).scaling(scale, scale, (-scale)));
        guiGraphics.pose().mulPose(rotation);

        Vector3f light0 = new Vector3f(1, -1.0F, -1.0F).normalize();
        Vector3f light1 = new Vector3f(-1, 1.0F, 1.0F).normalize();
        RenderSystem.setShaderLights(light0, light1);
        EntityRenderDispatcher entityrenderdispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        entityrenderdispatcher.setRenderShadow(false);
        if (renderModelOnly && entityrenderdispatcher.getRenderer(entity) instanceof LivingEntityRenderer livingEntityRenderer) {
            guiGraphics.pose().translate(0, 1.5F, 0.0D);
            guiGraphics.pose().mulPose(Axis.XP.rotationDegrees(180.0F));
            RenderType renderType = livingEntityRenderer.getModel().renderType(livingEntityRenderer.getTextureLocation(entity));
            livingEntityRenderer.getModel().renderToBuffer(guiGraphics.pose(), guiGraphics.bufferSource().getBuffer(renderType), 15728880,
                    LivingEntityRenderer.getOverlayCoords((LivingEntity) entity, 0.0F));
        } else {
            float f = entity.yRotO + (entity.getYRot() - entity.yRotO) * partialTicks;
            if (entity instanceof LivingEntity living) {
                float f1 = living.yBodyRotO + (living.yBodyRot - living.yBodyRotO) * partialTicks;
                guiGraphics.pose().mulPose(Axis.YN.rotationDegrees(-f1));
            } else {
                guiGraphics.pose().mulPose(Axis.YN.rotationDegrees(-f));
            }
            RenderSystem.runAsFancy(() -> {
                entityrenderdispatcher.render(entity, 0.0D, 0.0D, 0.0D, 0.0F, partialTicks, guiGraphics.pose(), guiGraphics.bufferSource(), 15728880);
            });
        }
        guiGraphics.flush();
        entityrenderdispatcher.setRenderShadow(true);
        guiGraphics.pose().popPose();
        Lighting.setupFor3DItems();
    }

    static class DamageText {
        double x, y, z;
        final Component text;
        final int color, colorOutline;
        int age;
        final int maxAge;
        double vy;

        DamageText(double x, double y, double z, Component text, int color, int colorOutline) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.text = text;
            this.color = color;
            this.colorOutline = colorOutline;
            this.age = 0;
            this.maxAge = 15 + (int) (Math.random() * 6);
            this.vy = 0.15 + Math.random() * 0.15;
        }
    }
}
