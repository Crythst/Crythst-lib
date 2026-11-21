package io.mikaple.crythst.api.sublevel;

import io.mikaple.crythst.physics.structure.PhysicsStructure;

import java.util.List;

/**
 * Identity diff used by the polling observer implementation.
 */
final class StructureObserverSupport {
    private StructureObserverSupport() {
    }

    static void diff(List<PhysicsStructure> previous, List<PhysicsStructure> current,
                     StructureObserver observer) {
        for (PhysicsStructure old : previous) {
            if (!containsIdentity(current, old)) {
                observer.onStructureRemoved(old);
            }
        }
        for (PhysicsStructure added : current) {
            if (!containsIdentity(previous, added)) {
                observer.onStructureAdded(added);
            }
        }
    }

    static boolean sameIdentities(List<PhysicsStructure> first, List<PhysicsStructure> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int i = 0; i < first.size(); i++) {
            if (first.get(i) != second.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsIdentity(List<PhysicsStructure> structures, PhysicsStructure target) {
        for (PhysicsStructure structure : structures) {
            if (structure == target) {
                return true;
            }
        }
        return false;
    }
}
