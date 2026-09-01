package studio.hiwire.bettergrass;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;

/**
 * Reads block ids at world coordinates.
 *
 * <p>Implementations must return {@link BlockType#UNKNOWN_ID} for a position they cannot read --
 * typically because its chunk is not in memory. {@link BetterGrassRule} treats that as "cannot
 * decide" and leaves the block alone, rather than guessing and producing a visible seam.
 *
 * <p>Positions below or above the world return {@link BlockType#EMPTY_ID}, matching {@code
 * BlockChunk.getBlock}.
 */
@FunctionalInterface
public interface BlockAccess {

  /**
   * @param x world X
   * @param y world Y
   * @param z world Z
   * @return the block id, or {@link BlockType#UNKNOWN_ID} if the position is not readable
   */
  int getBlock(int x, int y, int z);
}
