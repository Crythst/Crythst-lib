package io.mikaple.crythst.math;

/**
 * Double-buffered render pose for a physics body.
 *
 * <p>The server thread calls {@link #push(Transform3d)} after every tick. Render
 * code reads {@link #sample(double)} with the frame interpolation alpha without
 * blocking. A single volatile immutable snapshot keeps the read tear-free.</p>
 */
public final class InterpolatedPose {
    private record Snapshot(Transform3d previous, Transform3d current) {
    }

    private volatile Snapshot snapshot = new Snapshot(Transform3d.IDENTITY, Transform3d.IDENTITY);

    public void reset(Transform3d pose) {
        snapshot = new Snapshot(pose, pose);
    }

    public void push(Transform3d pose) {
        final Snapshot old = snapshot;
        snapshot = new Snapshot(old.current, pose);
    }

    public Transform3d current() {
        return snapshot.current;
    }

    public Transform3d previous() {
        return snapshot.previous;
    }

    public Transform3d sample(double alpha) {
        final Snapshot local = snapshot;
        return local.previous.interpolate(local.current, alpha);
    }
}
