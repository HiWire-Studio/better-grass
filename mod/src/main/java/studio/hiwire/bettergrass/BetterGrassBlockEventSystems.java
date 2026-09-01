package studio.hiwire.bettergrass;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Keeps grass connected when players change blocks.
 *
 * <p>Both events are cancellable and fire <em>before</em> the world changes, so the re-evaluation is
 * pushed onto the world thread with {@code world.execute}. That is not a compromise: the rule reads
 * the world fresh every time and converts only a base grass block that currently qualifies, so a
 * deferred task whose event was cancelled, or whose block has since been broken, replaced or
 * unloaded, does nothing at all. There is no stale state to guard against.
 *
 * <p>Only nine positions are re-checked per edit -- see {@link BetterGrassRule#dirtyPositions} --
 * which is roughly 300ns of work.
 */
public final class BetterGrassBlockEventSystems {

  private BetterGrassBlockEventSystems() {}

  /** Re-evaluates around a placed block. */
  public static final class BlockPlaced extends EntityEventSystem<EntityStore, PlaceBlockEvent> {

    @Nonnull private final BetterGrassFeature feature;

    public BlockPlaced(@Nonnull final BetterGrassFeature feature) {
      super(PlaceBlockEvent.class);
      this.feature = feature;
    }

    @Nullable @Override
    public Query<EntityStore> getQuery() {
      return Archetype.empty();
    }

    @Override
    public void handle(
        final int index,
        @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
        @Nonnull final Store<EntityStore> store,
        @Nonnull final CommandBuffer<EntityStore> commandBuffer,
        @Nonnull final PlaceBlockEvent event) {
      final var target = event.getTargetBlock();
      scheduleRefresh(feature, store, target.x(), target.y(), target.z());
    }
  }

  /** Re-evaluates around a broken block. */
  public static final class BlockBroken extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    @Nonnull private final BetterGrassFeature feature;

    public BlockBroken(@Nonnull final BetterGrassFeature feature) {
      super(BreakBlockEvent.class);
      this.feature = feature;
    }

    @Nullable @Override
    public Query<EntityStore> getQuery() {
      return Archetype.empty();
    }

    @Override
    public void handle(
        final int index,
        @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
        @Nonnull final Store<EntityStore> store,
        @Nonnull final CommandBuffer<EntityStore> commandBuffer,
        @Nonnull final BreakBlockEvent event) {
      final var target = event.getTargetBlock();
      scheduleRefresh(feature, store, target.x(), target.y(), target.z());
    }
  }

  /**
   * Queues the nine-position refresh for after the edit has actually happened.
   *
   * <p>Breaking matters as much as placing: filling a gap beside a grass block removes an exposed
   * face, and emptying one exposes a face that was hidden, and either can flip whether the block
   * qualifies.
   */
  private static void scheduleRefresh(
      @Nonnull final BetterGrassFeature feature,
      @Nonnull final Store<EntityStore> store,
      final int x,
      final int y,
      final int z) {
    if (!feature.isEnabled() || feature.variants() == null) {
      return;
    }

    final World world = store.getExternalData().getWorld();
    world.execute(() -> feature.refreshAround(world, x, y, z));
  }
}
