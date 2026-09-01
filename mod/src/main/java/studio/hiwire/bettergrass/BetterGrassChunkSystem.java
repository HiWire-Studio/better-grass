package studio.hiwire.bettergrass;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import javax.annotation.Nonnull;

/**
 * Closes the chunk-border seams once a chunk column is live.
 *
 * <p>The interior of a chunk is converted earlier, during pre-load, where nobody has been sent the
 * chunk yet and the change is invisible. That pass cannot decide the outermost block of each edge,
 * because deciding it means reading the chunk next door while this one is still loading. This
 * system picks those up afterwards, when neighbours are addressable.
 *
 * <p>It fixes both sides: this chunk's strips and the facing strips of each already-loaded
 * neighbour. A chunk that loaded alone gets its border finished when each neighbour shows up, so
 * borders converge with no world-wide pass and no command.
 *
 * <p>The work is deferred onto the world thread rather than done inline. That keeps it clear of
 * whatever load ordering is in flight, and costs nothing in correctness: {@link BetterGrassRule}
 * re-reads every block it looks at, so a chunk that unloaded in the meantime simply yields no
 * changes.
 */
public final class BetterGrassChunkSystem extends HolderSystem<ChunkStore> {

  /** The four horizontal chunk neighbours whose shared border needs finishing. */
  private static final int[] NEIGHBOUR_X = {0, 1, 0, -1};

  private static final int[] NEIGHBOUR_Z = {-1, 0, 1, 0};

  @Nonnull private final BetterGrassFeature feature;

  public BetterGrassChunkSystem(@Nonnull final BetterGrassFeature feature) {
    this.feature = feature;
  }

  @Override
  public Query<ChunkStore> getQuery() {
    return BlockChunk.getComponentType();
  }

  @Override
  public void onEntityAdd(
      @Nonnull final Holder<ChunkStore> holder,
      @Nonnull final AddReason reason,
      @Nonnull final Store<ChunkStore> store) {
    if (!feature.isEnabled()) {
      return;
    }

    final var blockChunk = holder.getComponent(BlockChunk.getComponentType());
    if (blockChunk == null) {
      return;
    }

    final var chunkStore = store.getExternalData();
    final int chunkX = blockChunk.getX();
    final int chunkZ = blockChunk.getZ();

    chunkStore.getWorld().execute(() -> finishBorders(chunkStore, chunkX, chunkZ));
  }

  @Override
  public void onEntityRemoved(
      @Nonnull final Holder<ChunkStore> holder,
      @Nonnull final RemoveReason reason,
      @Nonnull final Store<ChunkStore> store) {
    // Nothing to undo: converted blocks are ordinary world state.
  }

  /** Re-evaluates the border strips of this chunk and of each loaded neighbour. */
  private void finishBorders(
      @Nonnull final ChunkStore chunkStore, final int chunkX, final int chunkZ) {
    final var variants = feature.variants();
    if (variants == null) {
      return;
    }

    applyEdges(chunkStore, variants, feature.occlusion(), chunkX, chunkZ);

    for (int i = 0; i < NEIGHBOUR_X.length; i++) {
      applyEdges(
          chunkStore,
          variants,
          feature.occlusion(),
          chunkX + NEIGHBOUR_X[i],
          chunkZ + NEIGHBOUR_Z[i]);
    }
  }

  /** Runs the edge pass over one chunk, if it is loaded. */
  private static void applyEdges(
      @Nonnull final ChunkStore chunkStore,
      @Nonnull final GrassVariants variants,
      @Nonnull final FaceOcclusion occlusion,
      final int chunkX,
      final int chunkZ) {
    final var chunkRef = chunkStore.getChunkReference(ChunkUtil.indexChunk(chunkX, chunkZ));
    if (chunkRef == null || !chunkRef.isValid()) {
      return;
    }

    final var store = chunkStore.getStore();
    final var worldChunk = store.getComponent(chunkRef, WorldChunk.getComponentType());
    final var blockChunk = store.getComponent(chunkRef, BlockChunk.getComponentType());
    if (worldChunk == null || blockChunk == null) {
      return;
    }

    BetterGrassPass.applyToChunkEdges(worldChunk, blockChunk, chunkStore, variants, occlusion);
  }
}
