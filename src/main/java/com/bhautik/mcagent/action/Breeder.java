package com.bhautik.mcagent.action;

/**
 * Framework-free breeding seam. Feeding two adults of the same species
 * puts them into love mode and produces a calf, which is what turns
 * hunting from strip-mining a herd into a renewable leather supply.
 */
public interface Breeder {

    Breeder NONE = (mobId, foodItemId, reach) -> false;

    /**
     * Feeds one nearby adult of {@code mobId} with {@code foodItemId},
     * consuming a single item. Call twice to pair two animals up.
     *
     * @return true when an animal actually accepted the food
     */
    boolean feed(String mobId, String foodItemId, double reach);
}
