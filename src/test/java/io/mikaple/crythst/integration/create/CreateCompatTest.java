package io.mikaple.crythst.integration.create;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Detection is a pure class-name / classpath concern, so these tests run
 * without Create installed. They also pin the "Create absent" fallback: every
 * public helper must stay quiet and return an empty/false result.
 */
class CreateCompatTest {
    @Test
    void createPackagesAreRecognised() {
        assertTrue(CreateCompat.isCreateClass(
                "com.zurrtum.create.content.contraptions.AbstractContraptionEntity"));
        assertTrue(CreateCompat.isCreateClass(
                "com.simibubi.create.content.contraptions.AbstractContraptionEntity"));
        assertTrue(CreateCompat.isCreateClass("com.zurrtum.create.Create"));
        assertTrue(CreateCompat.isCreateClass("com.zurrtum.create.AllBlocks$Nested"));
    }

    @Test
    void unrelatedPackagesAreRejected() {
        assertFalse(CreateCompat.isCreateClass(null));
        assertFalse(CreateCompat.isCreateClass(""));
        assertFalse(CreateCompat.isCreateClass("com.zurrtum.createfake.Block"));
        assertFalse(CreateCompat.isCreateClass("com.example.create.Create"));
        assertFalse(CreateCompat.isCreateClass("net.minecraft.world.entity.Entity"));
    }

    @Test
    void contraptionEntityNamesAreRecognised() {
        assertTrue(CreateCompat.isContraptionClassName(
                "com.zurrtum.create.content.contraptions.AbstractContraptionEntity"));
        assertTrue(CreateCompat.isContraptionClassName(
                "com.zurrtum.create.content.contraptions.ControlledContraptionEntity"));
        assertTrue(CreateCompat.isContraptionClassName(
                "com.zurrtum.create.content.contraptions.OrientedContraptionEntity"));
        assertTrue(CreateCompat.isContraptionClassName(
                "com.zurrtum.create.content.contraptions.gantry.GantryContraptionEntity"));
        assertTrue(CreateCompat.isContraptionClassName(
                "com.zurrtum.create.content.trains.entity.CarriageContraptionEntity"));
        assertTrue(CreateCompat.isContraptionClassName(
                "com.simibubi.create.content.contraptions.OrientedContraptionEntity"));
        // Add-on subclasses in the Create package follow the same naming rule.
        assertTrue(CreateCompat.isContraptionClassName(
                "com.zurrtum.create.addon.MyCustomContraptionEntity"));
    }

    @Test
    void nonContraptionCreateClassesAreRejected() {
        assertFalse(CreateCompat.isContraptionClassName(
                "com.zurrtum.create.content.contraptions.Contraption"));
        assertFalse(CreateCompat.isContraptionClassName(
                "com.zurrtum.create.content.contraptions.ContraptionCollider"));
        assertFalse(CreateCompat.isContraptionClassName(
                "com.example.MyContraptionEntity"));
        assertFalse(CreateCompat.isContraptionClassName(null));
    }

    @Test
    void createAbsentFallbackIsQuiet() {
        // The test classpath intentionally has no Create jar.
        assertFalse(CreateCompat.isLoaded());
        assertTrue(CreateCompat.detectedContraptionBaseClasses().isEmpty());

        assertFalse(CreateCompat.isContraptionEntity(null));
        assertFalse(CreateCompat.isRidingContraption(null));
        assertFalse(CreateCompat.shouldStayOnStructure(null));
        assertFalse(CreateCompat.hasAnchorApi(null));

        assertTrue(CreateCompat.anchorPosition(null).isEmpty());
        assertTrue(CreateCompat.previousAnchorPosition(null).isEmpty());
        assertTrue(CreateCompat.toGlobalVector(null, Vec3.ZERO, 0.0f).isEmpty());
        assertTrue(CreateCompat.toLocalVector(null, Vec3.ZERO, 0.0f).isEmpty());
        assertTrue(CreateCompat.controllingPlayer(null).isEmpty());
        assertFalse(CreateCompat.setControllingPlayer(null, null));
    }

    @Test
    void registerWithoutCreateIsANoOp() {
        assertFalse(CreateCompat.register());
        // A second call is safe and still reports "not loaded".
        assertFalse(CreateCompat.register());
    }
}
