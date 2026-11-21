package io.mikaple.crythst.physics.shape;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.util.Validate;

import java.util.ArrayList;
import java.util.List;

/**
 * A collection of child shapes, each with a local transform. This is the main
 * building block for voxel structures: every merged voxel box becomes one child.
 */
public final class CompoundShape implements PhysicsShape {
    public record Child(PhysicsShape shape, Transform3d transform) {
        public Child {
            Validate.notNull(shape, "shape");
            transform = transform == null ? Transform3d.IDENTITY : transform;
        }
    }

    private final List<Child> children;

    public CompoundShape(List<Child> children) {
        Validate.notNull(children, "children");
        if (children.isEmpty()) {
            throw new IllegalArgumentException("A compound shape needs at least one child");
        }
        this.children = List.copyOf(children);
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Child> children() {
        return children;
    }

    @Override
    public ShapeType type() {
        return ShapeType.COMPOUND;
    }

    @Override
    public Aabb localBounds() {
        Aabb result = null;
        for (Child child : children) {
            final Aabb childBounds = child.shape().localBounds().transform(child.transform());
            result = result == null ? childBounds : result.union(childBounds);
        }
        return result == null ? new Aabb(new org.joml.Vector3d(), new org.joml.Vector3d()) : result;
    }

    @Override
    public double volume() {
        double volume = 0.0;
        for (Child child : children) {
            volume += child.shape().volume();
        }
        return volume;
    }

    public static final class Builder {
        private final List<Child> children = new ArrayList<>();

        public Builder add(PhysicsShape shape) {
            children.add(new Child(shape, Transform3d.IDENTITY));
            return this;
        }

        public Builder add(PhysicsShape shape, Transform3d transform) {
            children.add(new Child(shape, transform));
            return this;
        }

        public CompoundShape build() {
            return new CompoundShape(children);
        }
    }
}
