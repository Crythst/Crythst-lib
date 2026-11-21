package io.mikaple.crythst.api.physics.force;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ForceGroupTest {
    @Test
    void equalFieldsAddressTheSameAccumulatorKey() {
        final ForceGroup first = new ForceGroup(Component.literal("lift"), Component.literal("lifts"), 0x123456, true);
        final ForceGroup second = new ForceGroup(Component.literal("lift"), Component.literal("lifts"), 0x123456, true);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());

        final Map<ForceGroup, String> keys = new HashMap<>();
        keys.put(first, "first");
        keys.put(second, "second");
        assertEquals(1, keys.size());
        assertEquals("second", keys.get(first));
    }

    @Test
    void differentMetadataProducesADifferentKey() {
        final ForceGroup base = new ForceGroup(Component.literal("lift"), null, 0x123456, false);
        final ForceGroup otherColor = new ForceGroup(Component.literal("lift"), null, 0x654321, false);
        final ForceGroup otherDebug = new ForceGroup(Component.literal("lift"), null, 0x123456, true);

        assertNotEquals(base, otherColor);
        assertNotEquals(base, otherDebug);
    }

    @Test
    void simpleConstructorUsesUndecoratedDefaults() {
        final ForceGroup group = new ForceGroup(Component.literal("simple"));
        assertEquals(Component.literal("simple"), group.label());
        assertNull(group.description());
        assertEquals(0xFFFFFF, group.color());
        assertFalse(group.debug());
        assertEquals(group.label(), group.name());
        assertEquals(group.debug(), group.defaultDisplayed());
    }
}
