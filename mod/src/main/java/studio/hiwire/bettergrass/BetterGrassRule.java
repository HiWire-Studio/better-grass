package studio.hiwire.bettergrass;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;

/**
 * Decides whether a grass block should wear its fringed sides or its full-grass sides.
 *
 * <p>This is OptiFine's Better Grass rule: an exposed side face of a grass block should be drawn as
 * grass when the block diagonally below that face is itself grass, which is what turns a stepped
 * hillside from banded dirt into a continuous grass slope.
 *
 * <p>Hytale cannot express that per face -- a block type carries one texture set for all six faces
 * (see {@code BlockTextures}: six names and a weight, no connection metadata), and the renderer is
 * closed, so the decision has to be made per block. That is fine in practice: a side face whose
 * neighbour is a solid block is never rendered, so only the exposed faces matter, and on ordinary
 * terrain a grass block has at most one or two of those. Hence:
 *
 * <ul>
 *   <li>faces hidden by a solid, full, opaque cube are ignored -- they are never drawn. Anything
 *       less than that, a tuft of {@code Plant_Grass_Arid} included, leaves the face visible; see
 *       {@link FaceOcclusion};
 *   <li>if no face is exposed the block is left alone, whichever variant it currently is;
 *   <li>the block goes full only when <em>every</em> exposed face qualifies.
 * </ul>
 *
 * <p>That last point is an approximation. OptiFine decides each face independently, while a block
 * type here carries one texture set for all six faces, so the decision is all-or-nothing. Requiring
 * every exposed face to qualify keeps grass from ever appearing above bare rock: a corner that drops
 * to grass on one side and to stone on the other stays fringed rather than showing grass over the
 * stone. The price is that such a corner keeps its dirt edge on the side that did qualify.
 *
 * <p>The rule is total and order independent: it maps a position to a desired id using only the
 * occlusion and grass-ness of the neighbourhood, neither of which a Better Grass swap can change
 * (both variants are solid opaque cubes, and both count as grass). A pass may therefore apply its
 * changes as it walks, and re-running it is a no-op.
 *
 * <p>It is also <b>one-way</b>: a block already wearing full-grass sides is never put back. Not for
 * lack of ability, but because a {@code _Full} block might be one a player crafted (Soils plus a
 * fibre at a Farmingbench) and placed on purpose. The rule cannot tell that block from one it
 * converted itself, and silently destroying something a player paid for is worse than leaving a
 * block full after the terrain that justified it is dug away -- which still looks fine, since
 * worldgen places {@code _Full} variants by hand in a dozen zone definitions anyway.
 *
 * <p>Being one-way also makes this safe to call from a deferred task: the first thing it does is
 * re-read the block, so a position that stopped being grass between scheduling and running simply
 * returns {@link #NO_CHANGE}.
 */
public final class BetterGrassRule {

  /** Returned when the block should keep whatever id it already has. */
  public static final int NO_CHANGE = -1;

  /** Horizontal neighbour offsets: north, east, south, west. */
  private static final int[] OFFSET_X = {0, 1, 0, -1};

  private static final int[] OFFSET_Z = {-1, 0, 1, 0};

  private BetterGrassRule() {}

  /**
   * @param access reads the neighbourhood; see {@link BlockAccess} for the unreadable case
   * @param variants the resolved base &harr; {@code _Full} pairs
   * @param occlusion decides which side faces are actually visible
   * @return the block id this position should hold, or {@link #NO_CHANGE}
   */
  public static int desiredBlock(
      BlockAccess access, GrassVariants variants, FaceOcclusion occlusion, int x, int y, int z) {
    final int currentId = access.getBlock(x, y, z);
    final int baseId = variants.baseOf(currentId);
    if (baseId == GrassVariants.NOT_GRASS) {
      return NO_CHANGE;
    }
    if (currentId != baseId) {
      // Already full-sided. One-way: never downgrade, it may be a player's placed block.
      return NO_CHANGE;
    }

    int exposedFaces = 0;
    int qualifyingFaces = 0;

    for (int face = 0; face < OFFSET_X.length; face++) {
      final int neighbourX = x + OFFSET_X[face];
      final int neighbourZ = z + OFFSET_Z[face];

      final int neighbourId = access.getBlock(neighbourX, y, neighbourZ);
      if (neighbourId == BlockType.UNKNOWN_ID) {
        // A neighbouring chunk is not loaded. Deciding now risks a seam that never gets revisited,
        // so leave the block for a later pass that can see the whole neighbourhood.
        return NO_CHANGE;
      }
      if (occlusion.hidesSideFace(neighbourId)) {
        continue;
      }

      exposedFaces++;

      final int belowNeighbourId = access.getBlock(neighbourX, y - 1, neighbourZ);
      if (belowNeighbourId == BlockType.UNKNOWN_ID) {
        return NO_CHANGE;
      }
      if (variants.isGrass(belowNeighbourId)) {
        qualifyingFaces++;
      }
    }

    // Every exposed face must qualify. A block with no exposed face has nothing to show and is
    // left alone.
    if (exposedFaces == 0 || qualifyingFaces != exposedFaces) {
      return NO_CHANGE;
    }

    final int fullId = variants.fullOf(baseId);
    return fullId == currentId ? NO_CHANGE : fullId;
  }

  /**
   * The positions whose decision a change at {@code (x, y, z)} can affect, written into {@code out}
   * as flat x, y, z triples.
   *
   * <p>Falls straight out of inverting the rule. A block at Q reads: Q itself, Q's four horizontal
   * neighbours (air-ness), and those neighbours one block down (grass-ness). So a change at P can
   * only matter to P, to the four blocks P is a side neighbour of, and to the four blocks P is the
   * below-diagonal of -- nine positions, no more.
   *
   * @return the number of positions written (always {@link #DIRTY_POSITIONS})
   */
  public static int dirtyPositions(final int x, final int y, final int z, final int[] out) {
    int i = 0;

    out[i++] = x;
    out[i++] = y;
    out[i++] = z;

    for (int face = 0; face < OFFSET_X.length; face++) {
      // Blocks that have P as a side neighbour.
      out[i++] = x + OFFSET_X[face];
      out[i++] = y;
      out[i++] = z + OFFSET_Z[face];

      // Blocks that have P as the block below their side neighbour.
      out[i++] = x + OFFSET_X[face];
      out[i++] = y + 1;
      out[i++] = z + OFFSET_Z[face];
    }

    return DIRTY_POSITIONS;
  }

  /** The number of positions {@link #dirtyPositions} reports: the block itself plus 4 + 4. */
  public static final int DIRTY_POSITIONS = 9;
}
