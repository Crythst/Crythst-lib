package io.mikaple.crythst.api.physics.constraint;

import io.mikaple.crythst.physics.constraint.ConstraintDef;
import io.mikaple.crythst.physics.constraint.ConstraintType;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import org.jetbrains.annotations.Nullable;

/**
 * Description of a joint between one or two structures.
 *
 * <p>The hierarchy maps onto the backend joint families:</p>
 * <ul>
 *   <li>{@link FixedConstraintConfiguration} -&gt;
 *       {@code FixedConstraintDef} / {@link ConstraintType#FIXED}</li>
 *   <li>{@link FreeConstraintConfiguration} -&gt;
 *       {@code PointConstraintDef} / {@link ConstraintType#POINT}</li>
 *   <li>{@link RotaryConstraintConfiguration} -&gt;
 *       {@code HingeConstraintDef} / {@link ConstraintType#HINGE}</li>
 *   <li>{@link GenericConstraintConfiguration} -&gt;
 *       {@code SliderConstraintDef} / {@link ConstraintType#SLIDER}</li>
 * </ul>
 *
 * <p><strong>Frames and units.</strong> Every frame, anchor and axis in this
 * hierarchy is expressed in <em>structure-local metres</em> for the structure it
 * belongs to, so {@code world = structure.pose() * local}. The pipeline converts
 * them to body-local space with {@link ConstraintFrames}, which accounts for the
 * body origin being the structure's centre of mass. Angular limits and target
 * velocities are in radians and radians per second; translation limits and
 * target velocities are in metres and metres per second; impulses/forces use
 * newton-seconds and newtons.</p>
 *
 * <p>When a configuration is used for a world-anchored joint (the
 * single-structure overload of
 * {@link io.mikaple.crythst.api.physics.PhysicsPipeline#addConstraint}), the
 * missing structure B is the world. The world pose is the identity, so
 * {@code frameB}, {@code anchorB} and {@code axisB} are interpreted directly in
 * world space.</p>
 *
 * <p>Configurations are immutable value objects. They do not reference a body
 * or world until {@link #toDefinition} is called.</p>
 */
public sealed interface StructureConstraintConfiguration
        permits FixedConstraintConfiguration,
        FreeConstraintConfiguration,
        RotaryConstraintConfiguration,
        GenericConstraintConfiguration {

    /**
     * Backend joint family this configuration produces.
     */
    ConstraintType type();

    /**
     * Converts this structure-local description into a backend joint
     * definition.
     *
     * <p>This method is normally called by
     * {@link io.mikaple.crythst.api.physics.PhysicsPipeline}; mods only need it
     * when talking to the lower-level physics world directly.</p>
     *
     * @param structureA structure owning body A; must not be {@code null}
     * @param structureB structure owning body B, or {@code null} for a
     *                   world-anchored joint
     * @return a backend definition with frames converted to body-local space
     */
    ConstraintDef toDefinition(PhysicsStructure structureA, @Nullable PhysicsStructure structureB);
}
