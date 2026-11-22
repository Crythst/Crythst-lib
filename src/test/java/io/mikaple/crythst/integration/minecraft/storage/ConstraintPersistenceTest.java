package io.mikaple.crythst.integration.minecraft.storage;

import io.mikaple.crythst.api.physics.constraint.DofAxisConfiguration;
import io.mikaple.crythst.api.physics.constraint.FixedConstraintConfiguration;
import io.mikaple.crythst.api.physics.constraint.FreeConstraintConfiguration;
import io.mikaple.crythst.api.physics.constraint.GenericConstraintConfiguration;
import io.mikaple.crythst.api.physics.constraint.RotaryConstraintConfiguration;
import io.mikaple.crythst.api.physics.constraint.SixDofConstraintConfiguration;
import io.mikaple.crythst.api.physics.constraint.SixDofAxis;
import io.mikaple.crythst.api.physics.constraint.StructureConstraintConfiguration;
import io.mikaple.crythst.math.Transform3d;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstraintPersistenceTest {
    @Test
    void everyConfigurationRoundTripsThroughNbt() {
        final Transform3d frameA = Transform3d.of(new Vector3d(1.0, 2.0, 3.0),
                new Quaterniond().rotateX(0.2).rotateZ(-0.4));
        final Transform3d frameB = Transform3d.of(new Vector3d(-1.0, 0.5, 2.0),
                new Quaterniond().rotateY(0.7));

        assertConfigRoundTrip(FixedConstraintConfiguration.between(frameA, frameB));
        assertConfigRoundTrip(FreeConstraintConfiguration.between(
                new Vector3d(1.0, 2.0, 3.0), new Vector3d(-1.0, 0.5, 2.0)));
        assertConfigRoundTrip(RotaryConstraintConfiguration.between(
                        new Vector3d(1.0, 2.0, 3.0), new Vector3d(0.0, 1.0, 0.0),
                        new Vector3d(-1.0, 0.5, 2.0), new Vector3d(1.0, 0.0, 0.0))
                .withLimits(-0.75, 0.5).withMotor(2.0, 30.0));
        assertConfigRoundTrip(GenericConstraintConfiguration.slider(frameA, frameB, -2.0, 3.0)
                .withAngularLimits(-0.1, 0.1).withMotor(0.4, 50.0));

        final DofAxisConfiguration motor = DofAxisConfiguration.limited(-1.0, 2.0)
                .withMotor(0.5, 75.0).withSpring(10.0, 0.6);
        final SixDofConstraintConfiguration sixDof = SixDofConstraintConfiguration.between(
                frameA, frameB,
                motor, DofAxisConfiguration.locked(), DofAxisConfiguration.free(),
                DofAxisConfiguration.limited(-0.2, 0.3),
                DofAxisConfiguration.free(), DofAxisConfiguration.locked());
        assertConfigRoundTrip(sixDof);
    }

    @Test
    void savedConstraintRoundTripsStableIdsAndWorldAnchor() {
        final UUID id = UUID.randomUUID();
        final UUID structureA = UUID.randomUUID();
        final SixDofConstraintConfiguration config = SixDofConstraintConfiguration.free(
                Transform3d.of(new Vector3d(1.0, 2.0, 3.0)),
                Transform3d.of(new Vector3d(4.0, 5.0, 6.0)));
        final SavedConstraint saved = new SavedConstraint(id, structureA, null,
                false, config);

        final SavedConstraint round = SavedConstraint.fromTag(saved.toTag());
        assertEquals(id, round.id());
        assertEquals(structureA, round.structureA());
        assertNull(round.structureB());
        assertTrue(round.worldAnchored());
        assertFalse(round.enabled());
        assertEquals(config, round.configuration());
        assertEquals(DofAxisConfiguration.free(),
                ((SixDofConstraintConfiguration) round.configuration())
                        .axis(SixDofAxis.ANGULAR_Z));
    }

    @Test
    void constraintSavedDataCodecRoundTripsAndDropsMissingIdentity() {
        final UUID id = UUID.randomUUID();
        final UUID structureA = UUID.randomUUID();
        final UUID structureB = UUID.randomUUID();
        final SavedConstraint saved = new SavedConstraint(id, structureA, structureB, true,
                FixedConstraintConfiguration.between(Transform3d.IDENTITY,
                        Transform3d.IDENTITY));
        final ConstraintSavedData data = new ConstraintSavedData();
        data.add(saved);
        data.add(saved);
        assertEquals(1, data.constraintCount());

        final CompoundTag encoded = (CompoundTag) ConstraintSavedData.TYPE.codec()
                .encodeStart(NbtOps.INSTANCE, data)
                .getOrThrow();
        final ConstraintSavedData decoded = ConstraintSavedData.TYPE.codec()
                .parse(NbtOps.INSTANCE, encoded)
                .getOrThrow();

        assertEquals(1, decoded.constraintCount());
        assertEquals(id, decoded.constraintIds().get(0));
        final SavedConstraint round = SavedConstraint.fromTag(decoded.records().get(0));
        assertEquals(id, round.id());
        assertEquals(structureA, round.structureA());
        assertEquals(structureB, round.structureB());
        assertEquals(saved.configuration(), round.configuration());

        final CompoundTag missingA = saved.toTag();
        missingA.remove("StructureA");
        assertThrows(IllegalArgumentException.class,
                () -> SavedConstraint.fromTag(missingA));

        final CompoundTag unknown = saved.toTag();
        unknown.putString("Config", "not-a-config");
        final CompoundTag config = unknown.getCompoundOrEmpty("Config");
        config.putString("Type", "unknown");
        unknown.put("Config", config);
        assertThrows(IllegalArgumentException.class,
                () -> SavedConstraint.fromTag(unknown));
    }

    private static void assertConfigRoundTrip(StructureConstraintConfiguration config) {
        final CompoundTag tag = ConstraintConfigCodec.encode(config);
        final StructureConstraintConfiguration round = ConstraintConfigCodec.decode(tag);
        assertEquals(config, round);
        assertEquals(config.localFrameA(), round.localFrameA());
        assertEquals(config.localFrameB(), round.localFrameB());
    }
}
