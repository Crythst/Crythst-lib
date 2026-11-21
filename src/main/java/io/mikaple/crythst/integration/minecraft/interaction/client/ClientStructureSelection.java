package io.mikaple.crythst.integration.minecraft.interaction.client;

import io.mikaple.crythst.client.network.ClientStructureCache;
import io.mikaple.crythst.client.network.ClientStructureSnapshot;
import io.mikaple.crythst.integration.fabric.client.ClientPhysicsView;
import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import io.mikaple.crythst.integration.minecraft.selection.PhysicsStructureView;
import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import io.mikaple.crythst.integration.minecraft.selection.StructureSelection;
import io.mikaple.crythst.integration.minecraft.selection.StructureVoxelView;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureRenderState;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Client-side structure sources for {@link StructureSelection}.
 *
 * <p>In singleplayer the integrated server publishes render snapshots through
 * {@link ClientPhysicsView}; in multiplayer the synced
 * {@link ClientStructureCache} is used. Both are sampled at one frame time so
 * the hit matches the rendered pose. Pose-only updates never touch physics.</p>
 */
public final class ClientStructureSelection {
    private ClientStructureSelection() {
    }

    /**
     * Current frame's candidate structures. Duplicate body ids are resolved in
     * favour of the integrated render snapshot.
     */
    public static List<StructureVoxelView> views() {
        final long now = System.nanoTime();
        final List<StructureVoxelView> views = new ArrayList<>();
        final Set<Long> liveIds = new HashSet<>();

        for (PhysicsStructure structure : ClientPhysicsView.structures()) {
            if (structure.closed()) {
                continue;
            }
            final StructureRenderState state = structure.renderStateAt(now);
            views.add(PhysicsStructureView.of(structure, state.pose()));
            liveIds.add(state.bodyId());
        }

        for (ClientStructureSnapshot snapshot : ClientStructureCache.snapshots()) {
            if (liveIds.contains(snapshot.bodyId())) {
                continue;
            }
            final Transform3d pose = ClientStructureCache.interpolatedPose(snapshot.bodyId(), now);
            views.add(new SnapshotView(snapshot, pose));
        }
        return views;
    }

    /**
     * The structure hit currently installed by vanilla picking, if any.
     */
    public static StructureBlockHitResult current(Minecraft minecraft) {
        return minecraft == null ? null : StructureBlockHitResult.of(minecraft.hitResult);
    }

    /**
     * Structure-only raycast from the player's eye and gravity-adjusted look
     * vector. Used by the compatibility adapter and diagnostics; normal
     * client code should read {@link #current(Minecraft)} instead.
     */
    public static StructureBlockHitResult raycast(LocalPlayer player, double reach) {
        if (player == null || reach <= 0.0) {
            return null;
        }
        final Vec3 eye = player.getEyePosition();
        return StructureSelection.raycast(views(), eye, PlayerGravity.lookDirection(player), reach);
    }

    private static final class SnapshotView implements StructureVoxelView {
        private final ClientStructureSnapshot snapshot;
        private final Transform3d pose;

        private SnapshotView(ClientStructureSnapshot snapshot, Transform3d pose) {
            this.snapshot = snapshot;
            this.pose = pose;
        }

        @Override
        public long bodyId() {
            return snapshot.bodyId();
        }

        @Override
        public Transform3d pose() {
            return pose;
        }

        @Override
        public double blockScale() {
            return snapshot.blockScale();
        }

        @Override
        public IntBounds3 bounds() {
            return snapshot.bounds();
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return snapshot.bounds().contains(x, y, z) && snapshot.isSolid(x, y, z);
        }

        @Override
        public BlockState blockState(int x, int y, int z) {
            if (!snapshot.bounds().contains(x, y, z)) {
                return Blocks.AIR.defaultBlockState();
            }
            try {
                return snapshot.blockStateAt(x, y, z);
            } catch (RuntimeException ignored) {
                return Blocks.AIR.defaultBlockState();
            }
        }
    }
}
