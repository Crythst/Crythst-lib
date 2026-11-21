package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.portal.PortalSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns one {@link PortalViewTarget} per portal and its texture-manager entry.
 */
final class PortalViewRegistry {
    private final Map<UUID, PortalViewTarget> targets = new HashMap<>();

    PortalViewTarget get(UUID portalId) {
        return targets.get(portalId);
    }

    PortalViewTarget getOrCreate(PortalSnapshot portal, RenderTarget mainTarget,
                                 int width, int height) {
        PortalViewTarget existing = targets.get(portal.id());
        if (existing != null && existing.matches(width, height)) {
            return existing;
        }
        final GpuFormat colorFormat = mainTarget.getColorTexture().getFormat();
        final GpuFormat depthFormat = mainTarget.getDepthTexture() == null
                ? GpuFormat.D32_FLOAT : mainTarget.getDepthTexture().getFormat();
        final GpuSampler sampler = RenderSystem.getSamplerCache()
                .getClampToEdge(FilterMode.LINEAR);
        final TextureTarget newTarget = PortalViewTarget.createTarget(
                portal, width, height, colorFormat, depthFormat);

        if (existing != null) {
            existing.replaceTarget(newTarget, sampler);
            return existing;
        }

        final Identifier textureId = Identifier.fromNamespaceAndPath(
                Crythst.MOD_ID, "portal_view/" + portal.id());
        final PortalViewTexture texture = new PortalViewTexture(
                "crythst_portal_view_" + portal.id(), newTarget, sampler);
        Minecraft.getInstance().getTextureManager().register(textureId, texture);
        final PortalViewTarget created = new PortalViewTarget(
                portal.id(), textureId, texture, newTarget);
        targets.put(portal.id(), created);
        return created;
    }

    void prune(Set<UUID> live) {
        final Iterator<Map.Entry<UUID, PortalViewTarget>> iterator = targets.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, PortalViewTarget> entry = iterator.next();
            if (live.contains(entry.getKey())) {
                continue;
            }
            Minecraft.getInstance().getTextureManager().release(entry.getValue().textureId());
            entry.getValue().close();
            iterator.remove();
        }
    }

    void close() {
        for (PortalViewTarget target : targets.values()) {
            target.close();
        }
        targets.clear();
    }
}
