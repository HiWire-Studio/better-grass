package studio.hiwire.bettergrass;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import javax.annotation.Nonnull;

/** {@link BlockAccess} implementations over the two places Better Grass reads blocks from. */
public final class BlockAccessors {

  private BlockAccessors() {}

  /**
   * Reads only within a single chunk column.
   *
   * <p>Used from the chunk pre-load hook, which runs while the chunk is still being loaded. Reading
   * a neighbouring chunk's components at that point would mean touching state owned by another
   * load, so anything outside this column is reported as {@link BlockType#UNKNOWN_ID} and the rule
   * declines to decide. The cost is that the outermost block of each edge is left alone until
   * the chunk is live and {@code BetterGrassPass.applyToChunkEdges} can read across the
   * boundary.
   */
  @Nonnull
  public static BlockAccess ofChunkColumn(@Nonnull final BlockChunk chunk) {
    final int chunkX = chunk.getX();
    final int chunkZ = chunk.getZ();

    return (x, y, z) -> {
      if (y < ChunkUtil.MIN_Y || y >= ChunkUtil.HEIGHT) {
        return BlockType.EMPTY_ID;
      }
      if ((x >> ChunkUtil.BITS) != chunkX || (z >> ChunkUtil.BITS) != chunkZ) {
        return BlockType.UNKNOWN_ID;
      }
      return chunk.getBlock(x, y, z);
    };
  }

  /**
   * Reads any position whose chunk section is currently in memory, and reports {@link
   * BlockType#UNKNOWN_ID} for everything else.
   *
   * <p>Used by the commands, which run on the world thread over chunks that are already loaded, so
   * neighbouring chunks are normally available and edges resolve properly.
   */
  @Nonnull
  public static BlockAccess ofLoadedChunks(@Nonnull final ChunkStore chunkStore) {
    return (x, y, z) -> {
      if (y < ChunkUtil.MIN_Y || y >= ChunkUtil.HEIGHT) {
        return BlockType.EMPTY_ID;
      }

      final var sectionRef = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
      if (sectionRef == null || !sectionRef.isValid()) {
        return BlockType.UNKNOWN_ID;
      }

      final var section =
          chunkStore.getStore().getComponent(sectionRef, BlockSection.getComponentType());
      if (section == null) {
        return BlockType.UNKNOWN_ID;
      }

      return section.get(x, y, z);
    };
  }
}
