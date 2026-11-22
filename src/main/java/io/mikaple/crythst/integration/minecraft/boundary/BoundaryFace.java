package io.mikaple.crythst.integration.minecraft.boundary;

import net.minecraft.core.Direction;

/**
 * One external face of a structure volume.
 *
 * <p>{@code x/y/z} are structure-local voxel coordinates inside the volume and
 * {@code direction} points from that voxel to the neighbouring position just
 * outside the volume. The outside position is the only place where the host
 * world can physically touch the structure, so all cross-boundary bridges use
 * this representation instead of exposing auxiliary-level coordinates.</p>
 */
public record BoundaryFace(int x, int y, int z, Direction direction) {
    public BoundaryFace {
        if (direction == null) {
            throw new NullPointerException("direction");
        }
    }

    public int outsideX() {
        return x + direction.getStepX();
    }

    public int outsideY() {
        return y + direction.getStepY();
    }

    public int outsideZ() {
        return z + direction.getStepZ();
    }
}
