package io.mikaple.crythst.api.sublevel;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureArgumentTypeTest {

    @Test
    void parsesDecimalHexAndPrefixedIds() throws Exception {
        final StructureArgumentType type = StructureArgumentType.structure();

        final StructureArgumentType.Selector decimal = type.parse(new StringReader("42"));
        assertEquals(42L, decimal.id());
        assertNull(decimal.name());

        final StructureArgumentType.Selector hash = type.parse(new StringReader("#42"));
        assertEquals(42L, hash.id());

        final StructureArgumentType.Selector hex = type.parse(new StringReader("0x2a"));
        assertEquals(42L, hex.id());

        final StructureArgumentType.Selector idPrefix = type.parse(new StringReader("id:42"));
        assertEquals(42L, idPrefix.id());
    }

    @Test
    void fallsBackToNameAndSupportsExplicitNamePrefix() throws Exception {
        final StructureArgumentType type = StructureArgumentType.structure();

        final StructureArgumentType.Selector name = type.parse(new StringReader("ship"));
        assertNull(name.id());
        assertEquals("ship", name.name());

        final StructureArgumentType.Selector prefixed = type.parse(new StringReader("name:ship"));
        assertEquals("ship", prefixed.name());
        assertEquals("'ship'", prefixed.describe());
    }

    @Test
    void rejectsEmptyInputAndEmptyIdPrefix() {
        final StructureArgumentType type = StructureArgumentType.structure();
        assertThrows(CommandSyntaxException.class, () -> type.parse(new StringReader("")));
        assertThrows(CommandSyntaxException.class, () -> type.parse(new StringReader("id:")));
        assertTrue(type.getExamples().contains("42"));
    }
}
