package io.mikaple.crythst.physics.query;

import io.mikaple.crythst.physics.body.BodyHandle;

import java.util.Objects;

/**
 * Predicate used to include bodies in a query result.
 */
@FunctionalInterface
public interface BodyFilter {
    BodyFilter ALL = body -> true;

    boolean test(BodyHandle body);

    static BodyFilter excluding(BodyHandle body) {
        Objects.requireNonNull(body, "body");
        final long id = body.id();
        return candidate -> candidate.id() != id;
    }
}
