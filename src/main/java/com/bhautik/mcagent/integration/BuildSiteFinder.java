package com.bhautik.mcagent.integration;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.build.Blueprint;
import com.bhautik.mcagent.build.Reservation;
import com.bhautik.mcagent.world.BlockLocator;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Optional;

/**
 * Chooses where a structure goes: near the base, on ground that fits it,
 * clear of the base furniture and of anything already built.
 *
 * <p>Searches in rings outward from a minimum standoff, so structures
 * cluster tidily around the base instead of landing on top of it or
 * scattering. The first site that passes every check wins - nearest
 * suitable beats best-scoring, and it keeps the walk short.
 */
public final class BuildSiteFinder {

    /** Never build closer than this to the base anchor. */
    public static final int MIN_STANDOFF = 6;
    /** Give up looking beyond this range from the base. */
    public static final int MAX_RANGE = 40;
    /** Step between candidate origins; a stride avoids scanning every block. */
    private static final int STRIDE = 2;
    /** Height variation tolerated across a footprint. */
    private static final int MAX_UNEVENNESS = 2;
    /** Columns allowed to be unusable before a site is rejected. */
    private static final double MAX_BAD_FRACTION = 0.1;

    private BuildSiteFinder() {
    }

    /**
     * A free, flat-enough spot for this blueprint.
     *
     * @param taken footprints already claimed by other structures
     */
    public static Optional<BlockLocator.BlockSite> find(ServerPlayer player,
                                                        Blueprint blueprint,
                                                        BlockPos base,
                                                        List<Reservation> taken) {
        for (int ring = MIN_STANDOFF; ring <= MAX_RANGE; ring += STRIDE) {
            for (int dx = -ring; dx <= ring; dx += STRIDE) {
                for (int dz = -ring; dz <= ring; dz += STRIDE) {
                    // Only the ring's edge is new; the inside was checked
                    // by a previous, closer ring.
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    var candidate = groundedOrigin(player, base.offset(dx, 0, dz));
                    if (candidate == null) {
                        continue;
                    }
                    if (collides(blueprint, candidate, taken)) {
                        continue;
                    }
                    if (!fits(player, blueprint, candidate)) {
                        continue;
                    }
                    McAgent.LOGGER.info(
                            "[Build] Site chosen {} {} {} ({} blocks from base)",
                            candidate.getX(), candidate.getY(), candidate.getZ(), ring);
                    return Optional.of(new BlockLocator.BlockSite(
                            candidate.getX(), candidate.getY(), candidate.getZ()));
                }
            }
        }
        return Optional.empty();
    }

    /** Would this blueprint, centred here, land on ground already claimed? */
    private static boolean collides(Blueprint blueprint, BlockPos origin,
                                    List<Reservation> taken) {
        var proposed = Reservation.centredOn(blueprint.name(), blueprint,
                origin.getX(), origin.getY(), origin.getZ());
        return taken.stream().anyMatch(proposed::overlaps);
    }

    /** Follows the surface down to solid ground at this column. */
    private static BlockPos groundedOrigin(ServerPlayer player, BlockPos column) {
        Level level = player.level();
        for (int dy = 3; dy >= -3; dy--) {
            var pos = column.offset(0, dy, 0);
            if (!level.getBlockState(pos).isAir()
                    && level.getBlockState(pos.above()).isAir()) {
                return pos.immutable();
            }
        }
        return null;
    }

    /**
     * Whether the footprint is buildable: solid, roughly level, free of
     * anything protected, and not already occupied by structures.
     */
    private static boolean fits(ServerPlayer player, Blueprint blueprint,
                                BlockPos origin) {
        Level level = player.level();
        int halfWidth = blueprint.width() / 2;
        int halfLength = blueprint.length() / 2;
        int columns = 0;
        int bad = 0;
        for (int dx = -halfWidth; dx <= halfWidth; dx++) {
            for (int dz = -halfLength; dz <= halfLength; dz++) {
                columns++;
                var pos = origin.offset(dx, 0, dz);
                if (protectedAt(level, pos) || protectedAt(level, pos.above())) {
                    return false; // never build over anything valuable
                }
                boolean usable = blueprint.needsSoil()
                        ? isSoil(level, pos)
                        : !level.getBlockState(pos).isAir();
                boolean clearAbove = level.getBlockState(pos.above()).isAir();
                boolean level0 = Math.abs(surfaceOffset(level, pos)) <= MAX_UNEVENNESS;
                if (!usable || !clearAbove || !level0) {
                    bad++;
                }
            }
        }
        return columns > 0 && (double) bad / columns <= MAX_BAD_FRACTION;
    }

    /** How far this column's surface sits from the origin's own level. */
    private static int surfaceOffset(Level level, BlockPos pos) {
        for (int dy = 0; dy <= MAX_UNEVENNESS; dy++) {
            if (!level.getBlockState(pos.offset(0, dy, 0)).isAir()
                    && level.getBlockState(pos.offset(0, dy + 1, 0)).isAir()) {
                return dy;
            }
            if (!level.getBlockState(pos.offset(0, -dy, 0)).isAir()
                    && level.getBlockState(pos.offset(0, -dy + 1, 0)).isAir()) {
                return -dy;
            }
        }
        return MAX_UNEVENNESS + 1;
    }

    /** Ground a hoe can actually turn into farmland. */
    private static boolean isSoil(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                || state.is(net.minecraft.world.level.block.Blocks.DIRT)
                || state.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)
                || state.is(net.minecraft.world.level.block.Blocks.ROOTED_DIRT)
                || state.is(net.minecraft.world.level.block.Blocks.FARMLAND);
    }

    private static boolean protectedAt(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof Container;
    }
}
