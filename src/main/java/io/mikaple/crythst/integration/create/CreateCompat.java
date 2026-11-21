package io.mikaple.crythst.integration.create;

import io.mikaple.crythst.Crythst;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Soft, class-name based detection of Create and of Create contraption
 * entities.
 *
 * <p>Crythst must build and run without Create on the classpath, therefore this
 * class never imports a {@code com.zurrtum.create} or
 * {@code com.simibubi.create} type. Instead it probes for well-known public
 * classes with {@link Class#forName(String, boolean, ClassLoader)} and invokes
 * only public methods through {@link CreateReflection}.</p>
 *
 * <p>Supported reference build: <b>Create-Fly 26.3 / 6.0.9-1</b>, package
 * {@code com.zurrtum.create}. Upstream Create's {@code com.simibubi.create}
 * names are accepted as well, which keeps the detection useful for builds that
 * only rename the package.</p>
 *
 * <p>All methods are null-safe and never throw when Create is missing or when a
 * reflected method has been renamed.</p>
 */
public final class CreateCompat {
    /** Package prefix used by Create-Fly and the 26.3 branch. */
    public static final String CREATE_FLY_PACKAGE = "com.zurrtum.create";
    /** Package prefix used by upstream Create / older ports. */
    public static final String UPSTREAM_CREATE_PACKAGE = "com.simibubi.create";
    /** Human-readable version this layer was written against. */
    public static final String REFERENCE_VERSION = "Create-Fly 26.3 / 6.0.9-1";

    /**
     * Marker classes. Their presence means "a Create build is installed"; a
     * missing contraption base class then only disables the anchor helpers
     * instead of hiding the whole integration.
     */
    private static final String[] MARKER_CLASSES = {
            CREATE_FLY_PACKAGE + ".Create",
            UPSTREAM_CREATE_PACKAGE + ".Create"
    };

    /**
     * Contraption entity base classes across Create versions. Subclasses such
     * as {@code ControlledContraptionEntity}, {@code OrientedContraptionEntity},
     * {@code GantryContraptionEntity} and {@code CarriageContraptionEntity}
     * are covered by {@link Class#isInstance(Object)} once a base is resolvable.
     */
    private static final String[] CONTRAPTION_BASE_CLASSES = {
            CREATE_FLY_PACKAGE + ".content.contraptions.AbstractContraptionEntity",
            UPSTREAM_CREATE_PACKAGE + ".content.contraptions.AbstractContraptionEntity",
            CREATE_FLY_PACKAGE + ".content.contraptions.ContraptionEntity",
            UPSTREAM_CREATE_PACKAGE + ".content.contraptions.ContraptionEntity"
    };

    private static final ClassValue<Boolean> CONTRAPTION_TYPES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> base : Detection.BASES) {
                if (base.isAssignableFrom(type)) {
                    return Boolean.TRUE;
                }
            }
            return isContraptionClassName(type.getName());
        }
    };

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private CreateCompat() {
    }

    /**
     * Whether a Create build was detected at runtime.
     *
     * <p>Detection is performed once per process. A negative result is stable
     * for that process, which matches Fabric's mod loading lifecycle: Create
     * cannot appear after class initialisation.</p>
     */
    public static boolean isLoaded() {
        return Detection.LOADED;
    }

    /**
     * Base classes that were actually resolvable. Useful for logs and for
     * diagnosing a Create update that moved the class names again.
     */
    public static List<String> detectedContraptionBaseClasses() {
        final List<String> names = new ArrayList<>(Detection.BASES.size());
        for (Class<?> type : Detection.BASES) {
            names.add(type.getName());
        }
        return List.copyOf(names);
    }

    /**
     * Whether a fully qualified class name belongs to a known Create package.
     * This is a pure string check and does not load the class.
     */
    public static boolean isCreateClass(String className) {
        if (className == null) {
            return false;
        }
        return className.startsWith(CREATE_FLY_PACKAGE + '.')
                || className.startsWith(UPSTREAM_CREATE_PACKAGE + '.');
    }

    /**
     * Whether a fully qualified class name looks like a contraption entity.
     *
     * <p>This intentionally accepts add-on subclasses: any Create-package class
     * whose simple name ends in {@code ContraptionEntity} is treated as a
     * contraption entity. Callers that need certainty should prefer
     * {@link #isContraptionEntity(Entity)}, which also checks the resolved base
     * classes.</p>
     */
    public static boolean isContraptionClassName(String className) {
        if (!isCreateClass(className)) {
            return false;
        }
        final int separator = Math.max(className.lastIndexOf('.'), className.lastIndexOf('$'));
        final String simpleName = className.substring(separator + 1);
        return simpleName.endsWith("ContraptionEntity");
    }

    /**
     * Whether the entity is a Create contraption entity (a subclass of
     * {@code AbstractContraptionEntity} in Create-Fly 6.0.x).
     */
    public static boolean isContraptionEntity(Entity entity) {
        return entity != null && CONTRAPTION_TYPES.get(entity.getClass());
    }

    /**
     * Whether the entity rides a contraption, either directly or through a
     * chain of vehicles (seats, nested contraptions, minecarts). Passengers of
     * contraptions must be kept on the structure as well.
     */
    public static boolean isRidingContraption(Entity entity) {
        if (entity == null) {
            return false;
        }
        Entity vehicle = entity.getVehicle();
        // A hard cap protects against cyclic or malicious vehicle chains.
        for (int depth = 0; vehicle != null && depth < 16; depth++) {
            if (isContraptionEntity(vehicle)) {
                return true;
            }
            vehicle = vehicle.getVehicle();
        }
        return false;
    }

    /**
     * The single predicate the entity riding manager should use: a contraption
     * entity itself or anything riding one must not be pushed off the
     * structure by ordinary entity collision.
     */
    public static boolean shouldStayOnStructure(Entity entity) {
        return isContraptionEntity(entity) || isRidingContraption(entity);
    }

    /**
     * Reads Create's anchor position ({@code getAnchorVec()}). For most
     * contraptions this equals {@link Entity#position()}; oriented contraptions
     * subtract a half-block offset, so callers that write positions back must
     * stay in {@link Entity#position()} space (see
     * {@link CreateStructureBridge}). Returns empty when Create is absent.
     */
    public static Optional<Vec3> anchorPosition(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        return asVec(CreateReflection.invoke(
                CreateReflection.find(entity.getClass(), "getAnchorVec"), entity));
    }

    /**
     * Reads Create's previous-tick anchor ({@code getPrevAnchorVec()}).
     */
    public static Optional<Vec3> previousAnchorPosition(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        return asVec(CreateReflection.invoke(
                CreateReflection.find(entity.getClass(), "getPrevAnchorVec"), entity));
    }

    /**
     * Calls Create's {@code toGlobalVector(Vec3, float)}. The three-argument
     * {@code toGlobalVector(Vec3, float, boolean)} overload is used as a
     * fallback because both are public in 6.0.x.
     */
    public static Optional<Vec3> toGlobalVector(Entity entity, Vec3 localVector, float partialTicks) {
        if (entity == null || localVector == null) {
            return Optional.empty();
        }
        final Class<?> type = entity.getClass();
        Method method = CreateReflection.find(type, "toGlobalVector", Vec3.class, float.class);
        Object result = CreateReflection.invoke(method, entity, localVector, partialTicks);
        if (result == null) {
            method = CreateReflection.find(type, "toGlobalVector", Vec3.class, float.class, boolean.class);
            result = CreateReflection.invoke(method, entity, localVector, partialTicks, false);
        }
        return asVec(result);
    }

    /**
     * Calls Create's {@code toLocalVector(Vec3, float)}.
     */
    public static Optional<Vec3> toLocalVector(Entity entity, Vec3 globalVector, float partialTicks) {
        if (entity == null || globalVector == null) {
            return Optional.empty();
        }
        final Class<?> type = entity.getClass();
        Method method = CreateReflection.find(type, "toLocalVector", Vec3.class, float.class);
        Object result = CreateReflection.invoke(method, entity, globalVector, partialTicks);
        if (result == null) {
            method = CreateReflection.find(type, "toLocalVector", Vec3.class, float.class, boolean.class);
            result = CreateReflection.invoke(method, entity, globalVector, partialTicks, false);
        }
        return asVec(result);
    }

    /**
     * Reads the player currently controlling the contraption.
     */
    public static Optional<UUID> controllingPlayer(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        final Object value = CreateReflection.invoke(
                CreateReflection.find(entity.getClass(), "getControllingPlayer"), entity);
        if (value instanceof Optional<?> optional && optional.orElse(null) instanceof UUID id) {
            return Optional.of(id);
        }
        return Optional.empty();
    }

    /**
     * Calls {@code setControllingPlayer(LivingEntity)}. Passing {@code null}
     * clears the controller. This is a public Create API used when a player
     * controls a bearing/contraption.
     *
     * @return {@code true} when the method exists and accepted the value
     */
    public static boolean setControllingPlayer(Entity entity, LivingEntity player) {
        if (entity == null) {
            return false;
        }
        return CreateReflection.invokeVoid(
                CreateReflection.find(entity.getClass(), "setControllingPlayer", LivingEntity.class),
                entity, player);
    }

    /**
     * Whether the entity exposes a public anchor method. Diagnostics helper.
     */
    public static boolean hasAnchorApi(Entity entity) {
        return entity != null && CreateReflection.find(entity.getClass(), "getAnchorVec") != null;
    }

    /**
     * Optional entry point for the mod initialiser. Safe to call without
     * Create: it logs once and returns {@code false}, leaving every other
     * Crythst subsystem untouched.
     *
     * <p>When Create is present this also installs the lazy server-tick hook
     * that keeps attached contraptions on their structures.</p>
     *
     * @return {@code true} when a Create build was detected
     */
    public static boolean register() {
        final boolean loaded = isLoaded();
        if (REGISTERED.compareAndSet(false, true)) {
            if (loaded) {
                Crythst.LOGGER.info(
                        "Create compatibility enabled against {} (detected base classes: {})",
                        REFERENCE_VERSION, detectedContraptionBaseClasses());
            } else {
                Crythst.LOGGER.debug(
                        "Create not detected; Create contraption compatibility hooks stay dormant");
            }
        }
        if (loaded) {
            CreateStructureBridge.registerTicker();
        }
        return loaded;
    }

    private static Optional<Vec3> asVec(Object value) {
        return value instanceof Vec3 vec ? Optional.of(vec) : Optional.empty();
    }

    private static Class<?> tryLoad(String name) {
        final ClassLoader context = Thread.currentThread().getContextClassLoader();
        final ClassLoader own = CreateCompat.class.getClassLoader();
        Class<?> type = load(name, context);
        if (type == null && own != context) {
            type = load(name, own);
        }
        if (type == null) {
            type = load(name, ClassLoader.getSystemClassLoader());
        }
        return type;
    }

    private static Class<?> load(String name, ClassLoader loader) {
        if (loader == null) {
            return null;
        }
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError | SecurityException ignored) {
            return null;
        }
    }

    /**
     * Lazily initialised so merely loading {@link CreateCompat} does not scan
     * the classpath until somebody asks about Create.
     */
    private static final class Detection {
        static final List<Class<?>> BASES;
        static final boolean LOADED;

        static {
            final List<Class<?>> bases = new ArrayList<>(CONTRAPTION_BASE_CLASSES.length);
            for (String name : CONTRAPTION_BASE_CLASSES) {
                final Class<?> type = tryLoad(name);
                if (type != null) {
                    bases.add(type);
                }
            }
            BASES = List.copyOf(bases);

            boolean loaded = !bases.isEmpty();
            if (!loaded) {
                for (String marker : MARKER_CLASSES) {
                    if (tryLoad(marker) != null) {
                        loaded = true;
                        break;
                    }
                }
            }
            LOADED = loaded;
        }
    }
}
