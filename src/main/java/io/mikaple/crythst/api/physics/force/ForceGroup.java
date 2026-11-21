package io.mikaple.crythst.api.physics.force;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable grouping key for forces contributed to a structure body.
 *
 * <p>A force group is display metadata plus a stable identity. Callers use the
 * same group instance for every physics substep (normally a {@code static final}
 * or block-entity field); the per-structure accumulator is looked up by
 * {@link #equals(Object)}/{@link #hashCode()}, so two groups with equal label,
 * description, colour and debug flag address the same accumulator. Keeping a
 * stable instance avoids map churn and makes diagnostics readable.</p>
 *
 * <p>The colour is a packed {@code 0xRRGGBB} value intended for debug overlays.
 * The debug flag mirrors Sable's "displayed by default" flag and is advisory:
 * Crythst stores it for consumers such as a future force debug panel but does
 * not render it itself.</p>
 *
 * <p><strong>Side:</strong> safe to construct and compare on either side; the
 * accumulator that consumes it only exists on the server. <strong>Lifecycle:</strong>
 * immutable and safe to retain for the lifetime of the owning block entity.</p>
 *
 * @param label       short human-readable name; must not be {@code null}
 * @param description optional longer description, or {@code null}
 * @param color       packed {@code 0xRRGGBB} display colour
 * @param debug       whether debug UIs should show this group by default
 */
public record ForceGroup(
        Component label,
        @Nullable Component description,
        int color,
        boolean debug
) {
    /**
     * Validates the label.
     *
     * @throws NullPointerException when {@code label} is {@code null}
     */
    public ForceGroup {
        if (label == null) {
            throw new NullPointerException("label");
        }
    }

    /**
     * Creates an undecorated group with a white colour that is hidden from
     * debug UIs by default.
     *
     * @param label short human-readable name
     */
    public ForceGroup(Component label) {
        this(label, null, 0xFFFFFF, false);
    }

    /**
     * Alias for {@link #label()} matching the terminology used by Sable.
     *
     * @return the short human-readable name
     */
    public Component name() {
        return label;
    }

    /**
     * Alias for {@link #debug()} matching Sable's "default displayed" flag.
     *
     * @return whether debug UIs show this group by default
     */
    public boolean defaultDisplayed() {
        return debug;
    }
}
