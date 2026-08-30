package com.bhautik.mcagent.build;

/**
 * Ground the agent has already committed to a structure.
 *
 * <p>Recorded so a later build can be placed somewhere else instead of
 * on top of an earlier one. Without this the second farm lands on the
 * first, which is the same class of mistake as building over the base
 * chest - just slower to notice.
 *
 * <p>Coordinates are the structure's minimum corner; the extents run
 * positive from there.
 */
public record Reservation(String name, int x, int y, int z,
                          int width, int height, int length) {

    /** Blocks kept clear between neighbouring structures. */
    public static final int MARGIN = 2;

    /**
     * Whether two footprints collide, with {@link #MARGIN} of breathing
     * room so structures do not end up flush against each other.
     */
    public boolean overlaps(Reservation other) {
        return overlaps1D(x, width, other.x, other.width)
                && overlaps1D(y, height, other.y, other.height)
                && overlaps1D(z, length, other.z, other.length);
    }

    /** True when a point falls inside this footprint plus its margin. */
    public boolean covers(int px, int py, int pz) {
        return within(px, x, width) && within(py, y, height) && within(pz, z, length);
    }

    private static boolean overlaps1D(int aMin, int aSize, int bMin, int bSize) {
        int aMax = aMin + aSize - 1 + MARGIN;
        int bMax = bMin + bSize - 1 + MARGIN;
        return aMin - MARGIN <= bMax && bMin - MARGIN <= aMax;
    }

    private static boolean within(int point, int min, int size) {
        return point >= min - MARGIN && point <= min + size - 1 + MARGIN;
    }

    /**
     * The reservation a blueprint would claim if built centred on this
     * origin. Blueprint offsets run from -radius to +radius, so the
     * minimum corner sits half a width back from the origin.
     */
    public static Reservation centredOn(String name, Blueprint blueprint,
                                        int originX, int originY, int originZ) {
        return new Reservation(name,
                originX - blueprint.width() / 2, originY,
                originZ - blueprint.length() / 2,
                blueprint.width(), Math.max(blueprint.height(), 1), blueprint.length());
    }

    public String describe() {
        return name + " at " + x + " " + y + " " + z
                + " (" + width + "x" + height + "x" + length + ")";
    }
}
