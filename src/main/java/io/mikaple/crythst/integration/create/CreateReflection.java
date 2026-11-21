package io.mikaple.crythst.integration.create;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Minimal, cached, failure-tolerant reflection helper for the soft Create
 * compatibility layer.
 *
 * <p>Only {@link Class#getMethod(String, Class[])} is used, so the layer can
 * never reach a private or protectively-encapsulated Create member. Every call
 * is wrapped and returns {@code null}/{@code false} instead of propagating an
 * error: a Create update must never crash Crythst.</p>
 *
 * <p>This class is package-private on purpose. Mods that want a stable
 * reflection entry point should use {@link CreateCompat}.</p>
 */
final class CreateReflection {
    private static final ClassValue<ConcurrentHashMap<String, Optional<Method>>> METHODS =
            new ClassValue<>() {
                @Override
                protected ConcurrentHashMap<String, Optional<Method>> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<>();
                }
            };

    private CreateReflection() {
    }

    /**
     * Finds a public method on {@code owner} (including inherited methods) and
     * caches the result, including negative results.
     */
    static Method find(Class<?> owner, String name, Class<?>... parameterTypes) {
        if (owner == null || name == null) {
            return null;
        }
        final String key = key(name, parameterTypes);
        final Optional<Method> cached = METHODS.get(owner).get(key);
        if (cached != null) {
            return cached.orElse(null);
        }
        Method method = null;
        try {
            method = owner.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException | SecurityException | LinkageError ignored) {
            // Create is absent, older, or the method was renamed.
        }
        METHODS.get(owner).put(key, Optional.ofNullable(method));
        return method;
    }

    /**
     * Invokes a method and returns its value, or {@code null} when the method
     * is missing, inaccessible or threw.
     */
    static Object invoke(Method method, Object target, Object... arguments) {
        if (method == null || target == null) {
            return null;
        }
        try {
            return method.invoke(target, arguments);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * Invokes a method for its side effect.
     *
     * @return {@code true} when the method was found and returned normally
     */
    static boolean invokeVoid(Method method, Object target, Object... arguments) {
        if (method == null || target == null) {
            return false;
        }
        try {
            method.invoke(target, arguments);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static String key(String name, Class<?>[] parameterTypes) {
        if (parameterTypes.length == 0) {
            return name + "()";
        }
        return name + '(' + Arrays.stream(parameterTypes)
                .map(Class::getName)
                .collect(Collectors.joining(",")) + ')';
    }
}
