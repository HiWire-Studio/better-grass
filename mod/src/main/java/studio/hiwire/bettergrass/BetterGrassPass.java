package studio.hiwire.bettergrass;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.function.IntConsumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies {@link BetterGrassRule} across chunks.
 *
 * <p>Three entry points, because the callers write blocks very differently. {@link
 * #applyToFreshChunk} runs from the pre-load hook on a chunk nobody has been sent yet, so it writes
 * straight into the section holders via {@link BlockOperations#setBlockPreLoad}. {@link
 * #applyToChunkEdges} and {@link #applyAt} run on the world thread over live chunks and go through
 * {@link BlockOperations#setBlock} so clients are told about the change.
 *
 * <p>Both walk only the blocks that are actually grass: {@code BlockSection} keeps a palette, so
 * {@code containsAny} rejects a section in one lookup and {@code find} enumerates just the matching
 * indices instead of all 32,768 blocks.
 */
public final class BetterGrassPass {

  /**
   * A Better Grass swap is cosmetic and must not read as breaking and replacing the block: no
   * drops, no particles, no sound, no burn-out, and no filler bookkeeping (grass is a plain cube).
   * Neighbour connection updates are suppressed too -- grass has no connected-block rule set, so
   * they would be pure overhead.
   */
  private static final int SETTINGS =
      SetBlockSettings.NO_SEND_PARTICLES
          | SetBlockSettings.NO_SEND_AUDIO
          | SetBlockSettings.NO_DROP_ITEMS
          | SetBlockSettings.NO_FIRE_ON_BREAK
          | SetBlockSettings.NO_UPDATE_NEIGHBOR_CONNECTIONS
          | SetBlockSettings.NO_SET_FILLER
          | SetBlockSettings.NO_BREAK_FILLER;

  private BetterGrassPass() {}

  /** Receives one grass block found in a chunk, with both its local and world coordinates. */
  @FunctionalInterface
  private interface GrassBlockVisitor {
    void visit(
        @Nonnull BlockSection section,
        int sectionIndex,
        int index,
        int blockId,
        int localX,
        int localZ,
        int worldX,
        int worldY,
        int worldZ);
  }

  /**
   * Converts grass in a chunk that is still being loaded, writing directly into its sections.
   *
   * <p>Only positions whose whole neighbourhood lies inside this column can be decided here; see
   * {@link BlockAccessors#ofChunkColumn}. Edge blocks are left to {@link #applyToChunkEdges},
   * which runs once the chunk is live and its neighbours are readable.
   *
   * @param chunkSections the column's sections, indexed by section y
   * @param blockSections the matching block data, indexed by section y
   * @return the number of blocks changed
   */
  public static int applyToFreshChunk(
      final int chunkX,
      final int chunkZ,
      @Nonnull final ChunkSection[] chunkSections,
      @Nonnull final BlockSection[] blockSections,
      @Nonnull final GrassVariants variants,
      @Nonnull final FaceOcclusion occlusion) {
    final BlockAccess access = BlockAccessors.ofChunkColumn(chunkX, chunkZ, blockSections);
    final int[] changed = {0};

    forEachGrassBlock(
        chunkX,
        chunkZ,
        blockSections,
        variants.allGrassIds(),
        (section, sectionIndex, index, blockId, localX, localZ, worldX, worldY, worldZ) -> {
          final int desired =
              BetterGrassRule.desiredBlock(access, variants, occlusion, worldX, worldY, worldZ);
          if (desired == BetterGrassRule.NO_CHANGE) {
            return;
          }
          if (BlockOperations.setBlockPreLoad(
              chunkSections[sectionIndex],
              section,
              worldX,
              worldY,
              worldZ,
              desired,
              section.getRotationIndex(index),
              section.getFiller(index))) {
            changed[0]++;
          }
        });

    return changed[0];
  }

  /**
   * Re-evaluates only the four one-block-wide border strips of a loaded chunk.
   *
   * <p>{@link #applyToFreshChunk} cannot decide those, because deciding them means reading the
   * chunk next door while this one is still loading. Once the chunk is live its neighbours are
   * addressable, and this closes the seam. Every chunk gets another go at its strips as each
   * neighbour arrives, so the borders converge without any world-wide pass.
   *
   * @return the number of blocks changed
   */
  public static int applyToChunkEdges(
      @Nonnull final ChunkStore chunkStore,
      final int chunkX,
      final int chunkZ,
      @Nonnull final GrassVariants variants,
      @Nonnull final FaceOcclusion occlusion) {
    final BlockAccess access = BlockAccessors.ofLoadedChunks(chunkStore);
    final var store = chunkStore.getStore();

    @SuppressWarnings("unchecked")
    final Ref<ChunkStore>[] sectionRefs = new Ref[ChunkUtil.HEIGHT_SECTIONS];
    final var blockSections = new BlockSection[ChunkUtil.HEIGHT_SECTIONS];
    for (int sectionIndex = 0; sectionIndex < ChunkUtil.HEIGHT_SECTIONS; sectionIndex++) {
      final var sectionRef = chunkStore.getChunkSectionReference(chunkX, sectionIndex, chunkZ);
      if (sectionRef == null || !sectionRef.isValid()) {
        continue;
      }
      sectionRefs[sectionIndex] = sectionRef;
      blockSections[sectionIndex] = store.getComponent(sectionRef, BlockSection.getComponentType());
    }

    final int[] changed = {0};

    forEachGrassBlock(
        chunkX,
        chunkZ,
        blockSections,
        variants.allGrassIds(),
        (section, sectionIndex, index, blockId, localX, localZ, worldX, worldY, worldZ) -> {
          if (localX != 0
              && localX != ChunkUtil.SIZE_MINUS_1
              && localZ != 0
              && localZ != ChunkUtil.SIZE_MINUS_1) {
            return;
          }

          final int desired =
              BetterGrassRule.desiredBlock(access, variants, occlusion, worldX, worldY, worldZ);
          if (desired == BetterGrassRule.NO_CHANGE) {
            return;
          }
          if (setLiveBlock(
              chunkStore, sectionRefs[sectionIndex], worldX, worldY, worldZ, desired)) {
            changed[0]++;
          }
        });

    return changed[0];
  }

  /**
   * Re-evaluates a single position in a loaded world.
   *
   * <p>Used for the handful of positions a block placement or break can affect. Resolves the chunk
   * itself and does nothing if it is not loaded, so this is safe to call from a deferred task.
   *
   * @return {@code true} if the block was changed
   */
  public static boolean applyAt(
      @Nonnull final ChunkStore chunkStore,
      @Nonnull final BlockAccess access,
      @Nonnull final GrassVariants variants,
      @Nonnull final FaceOcclusion occlusion,
      final int x,
      final int y,
      final int z) {
    final int desired = BetterGrassRule.desiredBlock(access, variants, occlusion, x, y, z);
    if (desired == BetterGrassRule.NO_CHANGE) {
      return false;
    }

    final var sectionRef = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
    if (sectionRef == null || !sectionRef.isValid()) {
      return false;
    }

    return setLiveBlock(chunkStore, sectionRef, x, y, z, desired);
  }

  /**
   * Writes one block into a live section, unrotated and without filler, the same way the engine's
   * own one-off block changes do.
   */
  private static boolean setLiveBlock(
      @Nonnull final ChunkStore chunkStore,
      @Nonnull final Ref<ChunkStore> sectionRef,
      final int x,
      final int y,
      final int z,
      final int blockId) {
    final BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
    if (blockType == null) {
      return false;
    }

    return BlockOperations.setBlock(
        chunkStore,
        sectionRef,
        x,
        y,
        z,
        blockId,
        blockType,
        RotationTuple.NONE_INDEX,
        FillerBlockUtil.NO_FILLER,
        SETTINGS);
  }

  /**
   * Visits every block in the column whose id is in {@code grassIds}.
   *
   * <p>Indices are collected per section before being visited, so the visitor is free to write
   * blocks without mutating the palette that is being enumerated.
   *
   * @param sections the column's block data, indexed by section y; missing sections are skipped
   */
  private static void forEachGrassBlock(
      final int chunkX,
      final int chunkZ,
      @Nonnull final BlockSection[] sections,
      @Nonnull final IntList grassIds,
      @Nonnull final GrassBlockVisitor visitor) {
    if (grassIds.isEmpty()) {
      return;
    }

    final int chunkBlockX = chunkX << ChunkUtil.BITS;
    final int chunkBlockZ = chunkZ << ChunkUtil.BITS;
    final var indices = new IntArrayList();
    final int sectionCount = Math.min(sections.length, ChunkUtil.HEIGHT_SECTIONS);

    for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
      @Nullable final BlockSection section = sections[sectionIndex];
      if (section == null || section.isSolidAir() || !section.containsAny(grassIds)) {
        continue;
      }

      indices.clear();
      // Explicitly typed: BlockSection.find is overloaded on IntConsumer and BiIntConsumer, and a
      // bare lambda matches both.
      final IntConsumer collect = indices::add;
      section.find(grassIds, collect);

      final int sectionBaseY = sectionIndex << ChunkUtil.BITS;
      for (int i = 0; i < indices.size(); i++) {
        final int index = indices.getInt(i);
        final int localX = ChunkUtil.xFromIndex(index);
        final int localZ = ChunkUtil.zFromIndex(index);
        final int worldY = sectionBaseY | ChunkUtil.yFromIndex(index);

        visitor.visit(
            section,
            sectionIndex,
            index,
            section.get(index),
            localX,
            localZ,
            chunkBlockX | localX,
            worldY,
            chunkBlockZ | localZ);
      }
    }
  }
}
