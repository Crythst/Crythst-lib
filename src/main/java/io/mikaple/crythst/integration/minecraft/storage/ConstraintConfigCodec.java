package io.mikaple.crythst.integration.minecraft.storage;

import io.mikaple.crythst.api.physics.constraint.DofAxisConfiguration;
import io.mikaple.crythst.api.physics.constraint.FixedConstraintConfiguration;
import io.mikaple.crythst.api.physics.constraint.FreeConstraintConfiguration;
import io.mikaple.crythst.api.physics.constraint.GenericConstraintConfiguration;
import io.mikaple.crythst.api.physics.constraint.RotaryConstraintConfiguration;
import io.mikaple.crythst.api.physics.constraint.SixDofConstraintConfiguration;
import io.mikaple.crythst.api.physics.constraint.StructureConstraintConfiguration;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.util.Validate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * NBT codec for {@link StructureConstraintConfiguration}.
 *
 * <p>All values are structure-local or world-space exactly as in the live
 * configuration, so a decoded configuration can be passed straight back to
 * {@code PhysicsPipeline.addConstraint}. This class has no knowledge of live
 * structures and is safe to unit-test without a server level.</p>
 */
public final class ConstraintConfigCodec {
    private static final String TYPE = "Type";
    private static final String FRAME_A = "FrameA";
    private static final String FRAME_B = "FrameB";

    private ConstraintConfigCodec() {
    }

    public static CompoundTag encode(StructureConstraintConfiguration configuration) {
        Validate.notNull(configuration, "configuration");
        final CompoundTag tag = new CompoundTag();
        switch (configuration) {
            case FixedConstraintConfiguration fixed -> {
                tag.putString(TYPE, "fixed");
                tag.put(FRAME_A, transform(fixed.frameA()));
                tag.put(FRAME_B, transform(fixed.frameB()));
            }
            case FreeConstraintConfiguration free -> {
                tag.putString(TYPE, "free");
                tag.put("AnchorA", vector(free.anchorA()));
                tag.put("AnchorB", vector(free.anchorB()));
            }
            case RotaryConstraintConfiguration rotary -> {
                tag.putString(TYPE, "rotary");
                tag.put("AnchorA", vector(rotary.anchorA()));
                tag.put("AxisA", vector(rotary.axisA()));
                tag.put("AnchorB", vector(rotary.anchorB()));
                tag.put("AxisB", vector(rotary.axisB()));
                tag.putDouble("LowerLimit", rotary.lowerLimit());
                tag.putDouble("UpperLimit", rotary.upperLimit());
                tag.putBoolean("MotorEnabled", rotary.motorEnabled());
                tag.putDouble("MotorTargetVelocity", rotary.motorTargetVelocity());
                tag.putDouble("MaxMotorImpulse", rotary.maxMotorImpulse());
            }
            case GenericConstraintConfiguration generic -> {
                tag.putString(TYPE, "slider");
                tag.put(FRAME_A, transform(generic.frameA()));
                tag.put(FRAME_B, transform(generic.frameB()));
                tag.putDouble("LowerLinearLimit", generic.lowerLinearLimit());
                tag.putDouble("UpperLinearLimit", generic.upperLinearLimit());
                tag.putDouble("LowerAngularLimit", generic.lowerAngularLimit());
                tag.putDouble("UpperAngularLimit", generic.upperAngularLimit());
                tag.putBoolean("MotorEnabled", generic.motorEnabled());
                tag.putDouble("TargetLinearVelocity", generic.targetLinearVelocity());
                tag.putDouble("MaxLinearForce", generic.maxLinearForce());
            }
            case SixDofConstraintConfiguration sixDof -> {
                tag.putString(TYPE, "six_dof");
                tag.put(FRAME_A, transform(sixDof.frameA()));
                tag.put(FRAME_B, transform(sixDof.frameB()));
                final ListTag axes = new ListTag();
                for (DofAxisConfiguration axis : sixDof.axes()) {
                    axes.add(axis(axis));
                }
                tag.put("Axes", axes);
            }
        }
        return tag;
    }

    public static StructureConstraintConfiguration decode(CompoundTag tag) {
        Validate.notNull(tag, "tag");
        final String type = tag.getStringOr(TYPE, "");
        return switch (type) {
            case "fixed" -> new FixedConstraintConfiguration(
                    transform(tag.getCompoundOrEmpty(FRAME_A)),
                    transform(tag.getCompoundOrEmpty(FRAME_B)));
            case "free" -> new FreeConstraintConfiguration(
                    vector(tag.getCompoundOrEmpty("AnchorA")),
                    vector(tag.getCompoundOrEmpty("AnchorB")));
            case "rotary" -> new RotaryConstraintConfiguration(
                    vector(tag.getCompoundOrEmpty("AnchorA")),
                    vector(tag.getCompoundOrEmpty("AxisA")),
                    vector(tag.getCompoundOrEmpty("AnchorB")),
                    vector(tag.getCompoundOrEmpty("AxisB")),
                    tag.getDoubleOr("LowerLimit", -Math.PI),
                    tag.getDoubleOr("UpperLimit", Math.PI),
                    tag.getBooleanOr("MotorEnabled", false),
                    tag.getDoubleOr("MotorTargetVelocity", 0.0),
                    tag.getDoubleOr("MaxMotorImpulse", 0.0));
            case "slider" -> new GenericConstraintConfiguration(
                    transform(tag.getCompoundOrEmpty(FRAME_A)),
                    transform(tag.getCompoundOrEmpty(FRAME_B)),
                    tag.getDoubleOr("LowerLinearLimit", -1.0),
                    tag.getDoubleOr("UpperLinearLimit", 1.0),
                    tag.getDoubleOr("LowerAngularLimit", 0.0),
                    tag.getDoubleOr("UpperAngularLimit", 0.0),
                    tag.getBooleanOr("MotorEnabled", false),
                    tag.getDoubleOr("TargetLinearVelocity", 0.0),
                    tag.getDoubleOr("MaxLinearForce", 0.0));
            case "six_dof" -> decodeSixDof(tag);
            default -> throw new IllegalArgumentException(
                    "Unknown constraint configuration type: '" + type + "'");
        };
    }

    private static SixDofConstraintConfiguration decodeSixDof(CompoundTag tag) {
        final ListTag axes = tag.getListOrEmpty("Axes");
        if (axes.size() != 6) {
            throw new IllegalArgumentException("A 6-DOF record needs exactly 6 axes; got "
                    + axes.size());
        }
        final DofAxisConfiguration[] parsed = new DofAxisConfiguration[6];
        for (int i = 0; i < 6; i++) {
            parsed[i] = axis(axes.getCompoundOrEmpty(i));
        }
        return new SixDofConstraintConfiguration(
                transform(tag.getCompoundOrEmpty(FRAME_A)),
                transform(tag.getCompoundOrEmpty(FRAME_B)),
                parsed[0], parsed[1], parsed[2], parsed[3], parsed[4], parsed[5]);
    }

    private static CompoundTag axis(DofAxisConfiguration axis) {
        final CompoundTag tag = new CompoundTag();
        tag.putBoolean("Disabled", axis.disabled());
        tag.putDouble("LowerLimit", axis.lowerLimit());
        tag.putDouble("UpperLimit", axis.upperLimit());
        tag.putBoolean("MotorEnabled", axis.motorEnabled());
        tag.putDouble("TargetVelocity", axis.targetVelocity());
        tag.putDouble("MaxMotorForce", axis.maxMotorForce());
        tag.putBoolean("SpringEnabled", axis.springEnabled());
        tag.putDouble("Stiffness", axis.stiffness());
        tag.putDouble("Damping", axis.damping());
        return tag;
    }

    private static DofAxisConfiguration axis(CompoundTag tag) {
        return new DofAxisConfiguration(
                tag.getBooleanOr("Disabled", false),
                tag.getDoubleOr("LowerLimit", 0.0),
                tag.getDoubleOr("UpperLimit", 0.0),
                tag.getBooleanOr("MotorEnabled", false),
                tag.getDoubleOr("TargetVelocity", 0.0),
                tag.getDoubleOr("MaxMotorForce", 0.0),
                tag.getBooleanOr("SpringEnabled", false),
                tag.getDoubleOr("Stiffness", 0.0),
                tag.getDoubleOr("Damping", 0.0));
    }

    private static CompoundTag transform(Transform3d transform) {
        final CompoundTag tag = vectorTag(transform.position());
        final Quaterniond rotation = transform.rotation();
        tag.putDouble("QX", rotation.x);
        tag.putDouble("QY", rotation.y);
        tag.putDouble("QZ", rotation.z);
        tag.putDouble("QW", rotation.w);
        return tag;
    }

    private static Transform3d transform(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return Transform3d.IDENTITY;
        }
        return Transform3d.of(
                new Vector3d(tag.getDoubleOr("X", 0.0),
                        tag.getDoubleOr("Y", 0.0),
                        tag.getDoubleOr("Z", 0.0)),
                new Quaterniond(tag.getDoubleOr("QX", 0.0),
                        tag.getDoubleOr("QY", 0.0),
                        tag.getDoubleOr("QZ", 0.0),
                        tag.getDoubleOr("QW", 1.0)));
    }

    private static CompoundTag vector(Vector3dc value) {
        return vectorTag(value);
    }

    private static Vector3d vector(CompoundTag tag) {
        return new Vector3d(tag.getDoubleOr("X", 0.0),
                tag.getDoubleOr("Y", 0.0),
                tag.getDoubleOr("Z", 0.0));
    }

    private static CompoundTag vectorTag(Vector3dc value) {
        final CompoundTag tag = new CompoundTag();
        tag.putDouble("X", value.x());
        tag.putDouble("Y", value.y());
        tag.putDouble("Z", value.z());
        return tag;
    }
}
