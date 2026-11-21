package io.mikaple.crythst.physics.collision;

import io.mikaple.crythst.util.Validate;

/**
 * Bullet-style collision group bitmask pair.
 *
 * @param group        the bit group this body belongs to (at least one bit)
 * @param collidesWith bitmask of groups this body may collide with
 */
public record CollisionFilter(int group, int collidesWith) {
    public static final CollisionFilter DEFAULT = new CollisionFilter(1, 0xFFFF);
    public static final CollisionFilter NONE = new CollisionFilter(1, 0);

    public CollisionFilter {
        if ((group & ~0xFFFF) != 0 || group == 0) {
            throw new IllegalArgumentException("group must be a non-zero 16-bit mask: " + group);
        }
        if ((collidesWith & ~0xFFFF) != 0) {
            throw new IllegalArgumentException("collidesWith must be a 16-bit mask: " + collidesWith);
        }
    }

    public static CollisionFilter ofGroup(int groupBit) {
        return new CollisionFilter(groupBit, 0xFFFF);
    }

    public static CollisionFilter layer(int layer, int mask) {
        Validate.atLeast(layer, 0, "layer");
        if (layer > 15) {
            throw new IllegalArgumentException("layer must be in [0, 15]");
        }
        return new CollisionFilter(1 << layer, mask & 0xFFFF);
    }

    public boolean collidesWith(CollisionFilter other) {
        return (collidesWith & other.group) != 0 && (other.collidesWith & group) != 0;
    }
}
