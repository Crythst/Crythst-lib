package io.mikaple.crythst.integration.minecraft.entity.placement;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.TagValueInput;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative entity placement on physics structures.
 *
 * <p>Vanilla spawn eggs, item frames and modded entity items expect a real
 * world block at the clicked position. For a structure voxel that block is air,
 * which makes vanilla spawn the entity inside the structure or reject the
 * hanging entity because it has no support. This registry provides a
 * structure-aware path:</p>
 *
 * <ol>
 *     <li>The server re-raycasts the player's authoritative look vector and
 *     derives a surface point from the resulting
 *     {@link StructureBlockHitResult}.</li>
 *     <li>A built-in handler (spawn egg, item frame/glow frame) or a
 *     mod-registered {@link StructureEntityPlacementHandler} places the
 *     entity at that point.</li>
 *     <li>The caller consumes one item only after success, respecting creative
 *     mode.</li>
 * </ol>
 *
 * <p>The registry is keyed by {@link Item}, so Create seats or other modded
 * entity items can be added without Crythst depending on those mods. Register
 * custom items from common initialization so the client's cancellation
 * predicate ({@link #handles(ItemStack)}) and the server's handler table stay
 * in sync; only the built-in item types are registered automatically.</p>
 *
 * <h2>Item frame stability</h2>
 * <p>Item frames are spawned with the vanilla {@code Fixed} flag set, because
 * there is no real support block for vanilla's survival check. The entity is
 * positioned exactly on the structure surface for the current session.
 * Attaching the frame to a moving structure is not implemented; if the
 * structure moves or disappears the frame stays where it was placed.</p>
 */
public final class StructureEntityPlacement {
    private static final Map<Item, StructureEntityPlacementHandler> HANDLERS =
            new ConcurrentHashMap<>();

    static {
        // Built-in hanging entities. Paintings are intentionally not included:
        // Painting.create scans the real Level for a fitting wall, so it cannot
        // be resolved against structure voxels without a separate variant
        // search. Vanilla behaviour is kept for paintings.
        HANDLERS.put(Items.ITEM_FRAME, StructureEntityPlacement::placeItemFrame);
        HANDLERS.put(Items.GLOW_ITEM_FRAME, StructureEntityPlacement::placeItemFrame);
    }

    private StructureEntityPlacement() {
    }

    /**
     * Registers or replaces the handler for one item.
     *
     * @throws IllegalArgumentException when {@code item} or {@code handler} is
     *                                  {@code null}
     */
    public static void register(Item item, StructureEntityPlacementHandler handler) {
        if (item == null) {
            throw new IllegalArgumentException("item must not be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        HANDLERS.put(item, handler);
    }

    /** Removes a handler; returns the previous handler, or {@code null}. */
    public static @Nullable StructureEntityPlacementHandler unregister(Item item) {
        return item == null ? null : HANDLERS.remove(item);
    }

    /** Number of explicitly registered handlers (spawn eggs are implicit). */
    public static int registeredHandlerCount() {
        return HANDLERS.size();
    }

    /**
     * Whether this item has a structure-aware entity placement path.
     * Spawn eggs are recognised by type, so every spawn egg works without
     * being registered one by one.
     */
    public static boolean handles(@Nullable ItemStack stack) {
        return handlerFor(stack) != null;
    }

    public static boolean handles(@Nullable Item item) {
        return item instanceof SpawnEggItem || (item != null && HANDLERS.containsKey(item));
    }

    /**
     * Handler for a stack, or {@code null} when the item is not handled.
     */
    public static @Nullable StructureEntityPlacementHandler handlerFor(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        final Item item = stack.getItem();
        if (item instanceof SpawnEggItem) {
            return StructureEntityPlacement::placeSpawnEgg;
        }
        return HANDLERS.get(item);
    }

    /**
     * Places an entity for {@code stack} on the authoritative structure hit.
     *
     * <p>This method does not consume the stack and does not check reach,
     * occlusion or the duplicate-click guard; callers must have already
     * performed those checks with the same authoritative hit. It computes the
     * surface point locally, so no client coordinates are trusted.</p>
     *
     * @return the handler result, or
     *         {@link StructureEntityPlacementHandler.Result#failed()}
     */
    public static StructureEntityPlacementHandler.Result place(
            ServerPlayer player, ItemStack stack, StructureBlockHitResult hit,
            InteractionHand hand) {
        if (player == null || stack == null || hit == null || hit.structure() == null
                || hit.structure().closed()) {
            return StructureEntityPlacementHandler.Result.failed();
        }
        final StructureEntityPlacementHandler handler = handlerFor(stack);
        if (handler == null) {
            return StructureEntityPlacementHandler.Result.failed();
        }
        final StructureEntityPlacementMath.SurfacePoint surface =
                StructureEntityPlacementMath.surfacePoint(hit);
        if (surface == null || !(player.level() instanceof ServerLevel level)) {
            return StructureEntityPlacementHandler.Result.failed();
        }
        final StructureEntityPlacementContext context = new StructureEntityPlacementContext(
                player, level, stack, hit, surface, hand);
        try {
            final StructureEntityPlacementHandler.Result result = handler.place(context);
            return result == null ? StructureEntityPlacementHandler.Result.failed() : result;
        } catch (RuntimeException e) {
            Crythst.LOGGER.warn("Structure entity placement handler failed for {}",
                    stack.getItem(), e);
            return StructureEntityPlacementHandler.Result.failed();
        }
    }

    /**
     * Vanilla spawn-egg semantics with the final position moved out of the
     * structure face. {@link EntityType#spawn} still applies the stack's
     * entity-data component, baby/variant data, random yaw and
     * {@code finalizeSpawn}, matching a real spawn egg.
     */
    private static StructureEntityPlacementHandler.Result placeSpawnEgg(
            StructureEntityPlacementContext context) {
        final EntityType<?> type = SpawnEggItem.getType(context.stack());
        if (type == null || !type.canSpawn(context.level())) {
            return StructureEntityPlacementHandler.Result.failed();
        }
        final Entity entity = type.spawn(context.level(), context.stack(), context.player(),
                context.spawnBlockPos(), EntitySpawnReason.SPAWN_ITEM_USE, false, false);
        if (entity == null) {
            return StructureEntityPlacementHandler.Result.failed();
        }
        // Move the entity from the vanilla block cell to the exact structure
        // surface. This is what prevents the "egg spawned inside the block"
        // bug; the hit point is already outside the voxel by the surface
        // epsilon.
        final var spawn = context.spawnPoint();
        entity.setPos(spawn.x, spawn.y, spawn.z);
        entity.setOldPosAndRot();
        context.level().gameEvent(context.player(), GameEvent.ENTITY_PLACE, spawn);
        return StructureEntityPlacementHandler.Result.placed(entity);
    }

    /**
     * Builds and places an item frame or glow frame. Support validation is
     * skipped because the supporting structure voxel is air in the real level;
     * the vanilla {@code Fixed} flag keeps the frame from being dropped by its
     * periodic support check.
     */
    private static StructureEntityPlacementHandler.Result placeItemFrame(
            StructureEntityPlacementContext context) {
        final StructureEntityPlacementMath.FramePlacement placement =
                StructureEntityPlacementMath.framePlacement(context.surface());
        if (placement == null) {
            return StructureEntityPlacementHandler.Result.failed();
        }
        final EntityType<? extends ItemFrame> type = context.stack().getItem() == Items.GLOW_ITEM_FRAME
                ? EntityTypes.GLOW_ITEM_FRAME
                : EntityTypes.ITEM_FRAME;
        final Entity created = type.create(context.level(), EntitySpawnReason.TRIGGERED);
        if (!(created instanceof ItemFrame frame)) {
            return StructureEntityPlacementHandler.Result.failed();
        }

        // Populate the vanilla attached-block/facing/fixed state through the
        // normal entity load path. This avoids an accessor mixin for ItemFrame's
        // private "Fixed" field while preserving the GlowItemFrame subclass
        // created by the entity type factory.
        frame.load(TagValueInput.create(ProblemReporter.DISCARDING,
                context.level().registryAccess(), frameTag(placement)));
        EntityType.createDefaultStackConfig(context.level(), context.stack(), context.player())
                .apply(frame);

        // BlockAttachedEntity#setPos snaps back to the attached block centre.
        // setPosRaw + the exact orientation-aware box keeps the frame on the
        // actual (possibly rotated or scaled) structure surface.
        frame.setPosRaw(placement.position().x, placement.position().y,
                placement.position().z);
        frame.setBoundingBox(placement.boundingBox());
        frame.setOldPosAndRot();

        frame.playPlacementSound();
        context.level().addFreshEntity(frame);
        context.level().gameEvent(context.player(), GameEvent.ENTITY_PLACE,
                placement.position());
        return StructureEntityPlacementHandler.Result.placed(frame);
    }

    private static CompoundTag frameTag(StructureEntityPlacementMath.FramePlacement placement) {
        final BlockPos pos = placement.attachedBlockPos();
        final CompoundTag tag = new CompoundTag();
        tag.putIntArray("block_pos", new int[]{pos.getX(), pos.getY(), pos.getZ()});
        tag.putString("Facing", placement.facing().getSerializedName());
        tag.putBoolean("Fixed", true);
        final ListTag position = new ListTag();
        position.add(DoubleTag.valueOf(placement.position().x));
        position.add(DoubleTag.valueOf(placement.position().y));
        position.add(DoubleTag.valueOf(placement.position().z));
        tag.put("Pos", position);
        return tag;
    }
}
