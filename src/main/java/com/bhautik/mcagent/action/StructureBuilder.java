package com.bhautik.mcagent.action;

import com.bhautik.mcagent.world.BlockLocator;

/**
 * Framework-free seam for raising a structure block by block.
 *
 * <p>Separate from {@link PlaceBlockAction.Placer}, which puts one block
 * wherever it can find room. Building needs an exact position, has to
 * clear what is in the way, and must be able to read back what it laid
 * down so the result can be verified against the blueprint.
 */
public interface StructureBuilder {

    StructureBuilder NONE = new StructureBuilder() {
        @Override public boolean place(BlockLocator.BlockSite at, String blockId) {
            return false;
        }

        @Override public boolean clear(BlockLocator.BlockSite at) {
            return false;
        }

        @Override public String blockAt(BlockLocator.BlockSite at) {
            return "";
        }

        @Override public boolean isProtected(BlockLocator.BlockSite at) {
            return false;
        }
    };

    /**
     * Puts this block at exactly this position, consuming one from the
     * inventory when the block has an item form.
     *
     * @return true when the world now holds that block there
     */
    boolean place(BlockLocator.BlockSite at, String blockId);

    /** Empties the position so the structure has room. */
    boolean clear(BlockLocator.BlockSite at);

    /** Block id currently at the position, for verification. */
    String blockAt(BlockLocator.BlockSite at);

    /**
     * True when this position holds something a build must never
     * overwrite: a container and its contents, base infrastructure, a
     * bed, a spawner. Building surveys its whole footprint for these
     * BEFORE placing anything, because overwriting a chest deletes what
     * is inside it with no drops and nothing can bring that back.
     */
    boolean isProtected(BlockLocator.BlockSite at);
}
