package io.mikaple.crythst.api.sublevel;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * Brigadier argument for selecting a structure in the command source's level.
 *
 * <p>The argument parses a structure reference into a {@link Selector}; use
 * {@link #getStructure(CommandContext, String)} to resolve it to a
 * {@link PhysicsStructure}. Brigadier argument parsing has no command context,
 * so the actual level lookup must happen in the command body.</p>
 *
 * <p>Accepted forms are a decimal body id ({@code 42}), a hex body id
 * ({@code 0x2a} or {@code #42}), or a name/label. Structures do not currently
 * have a dedicated name field, so a name resolves only through
 * {@link StructureContainer#byName(String)} (body user data or the decimal id
 * as text).</p>
 */
public final class StructureArgumentType implements ArgumentType<StructureArgumentType.Selector> {
    private static final Collection<String> EXAMPLES = List.of("42", "#42", "0x2a", "ship");
    private static final SimpleCommandExceptionType EMPTY =
            new SimpleCommandExceptionType(Component.literal("Expected a structure id or name"));
    private static final SimpleCommandExceptionType NO_CONTAINER =
            new SimpleCommandExceptionType(Component.literal("This level has no physics structure container"));
    private static final SimpleCommandExceptionType NOT_FOUND =
            new SimpleCommandExceptionType(Component.literal("Unknown physics structure"));

    private StructureArgumentType() {
    }

    /**
     * Creates the argument type.
     */
    public static StructureArgumentType structure() {
        return new StructureArgumentType();
    }

    /**
     * Resolves the parsed argument through the command source's container.
     *
     * @throws CommandSyntaxException when the level has no container or the
     *                                reference does not match a structure
     */
    public static PhysicsStructure getStructure(CommandContext<CommandSourceStack> context,
                                                String argumentName) throws CommandSyntaxException {
        final Selector selector = context.getArgument(argumentName, Selector.class);
        final StructureContainer container = StructureContainer.getContainer(context.getSource().getLevel());
        if (container == null) {
            throw NO_CONTAINER.create();
        }
        final PhysicsStructure structure = selector.id() != null
                ? container.byId(selector.id())
                : container.byName(selector.name());
        if (structure == null) {
            throw NOT_FOUND.create();
        }
        return structure;
    }

    @Override
    public Selector parse(StringReader reader) throws CommandSyntaxException {
        // Brigadier's readString treats ':' and '#' as separators in some
        // forms. Command arguments here are plain whitespace-delimited tokens,
        // so read the raw token to keep id:/name:/#/0x prefixes intact.
        final int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
            reader.skip();
        }
        final String token = reader.getString().substring(start, reader.getCursor());
        if (token.isEmpty()) {
            throw EMPTY.createWithContext(reader);
        }
        final Long id = parseId(token);
        if (id != null) {
            return new Selector(id, null);
        }
        if (token.startsWith("id:")) {
            throw EMPTY.createWithContext(reader);
        }
        final String name = token.startsWith("name:") ? token.substring("name:".length()) : token;
        if (name.isEmpty()) {
            throw EMPTY.createWithContext(reader);
        }
        return new Selector(null, name);
    }

    private static @Nullable Long parseId(String token) {
        String value = token;
        if (value.startsWith("id:")) {
            value = value.substring("id:".length());
        }
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.isEmpty()) {
            return null;
        }
        try {
            if (value.startsWith("0x") || value.startsWith("0X")) {
                return Long.parseLong(value.substring(2), 16);
            }
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }

    /**
     * Parsed structure reference. Exactly one of {@link #id()} and
     * {@link #name()} is non-null.
     */
    public record Selector(@Nullable Long id, @Nullable String name) {
        public Selector {
            if ((id == null) == (name == null)) {
                throw new IllegalArgumentException("Exactly one of id or name must be present");
            }
        }

        /** Human-readable form for diagnostics. */
        public String describe() {
            return id != null ? "#" + id : "'" + name + "'";
        }
    }
}
