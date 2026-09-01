package studio.hiwire.bettergrass;

import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import studio.hiwire.bettergrass.command.BetterGrassCommand;

/**
 * Better Grass: OptiFine's "Better Grass" for Hytale, done server-side.
 *
 * <p>The whole feature lives under {@code studio.hiwire.bettergrass} and touches the host plugin in
 * exactly one place -- {@link #install} -- so it can be lifted into its own mod by moving the
 * package and giving it a {@code JavaPlugin} of its own.
 *
 * <p><b>What it does.</b> Hytale already ships the artwork: every grass block has a {@code _Full}
 * sibling whose sides use the grass texture instead of dirt-with-a-fringe, but nothing swaps to it
 * at runtime. Better Grass evaluates {@link BetterGrassRule} and performs the swap.
 *
 * <p><b>Where it runs.</b> Three hooks, no commands beyond an on/off switch:
 *
 * <ol>
 *   <li>chunk pre-load -- converts the interior of every chunk as it loads, new or saved, before
 *       anyone has been sent it, so the change is invisible;
 *   <li>chunk load ({@link BetterGrassChunkSystem}) -- finishes the border strips the pre-load pass
 *       could not decide without reading the chunk next door;
 *   <li>block place and break ({@link BetterGrassBlockEventSystems}) -- keeps player edits
 *       connected, nine positions at a time.
 * </ol>
 *
 * <p><b>What it costs.</b> The renderer is closed and block textures are per block type, so this is
 * a real block swap that persists in the save, not a render-time trick. It is safe as swaps go:
 * both variants inherit from {@code Soil_Grass}, so drops and behaviour are identical, and {@link
 * GrassDropSafety} refuses any pair where that stops being true.
 */
public final class BetterGrassFeature {

  private final JavaPlugin plugin;

  @Nullable private EventRegistration<?, ChunkPreLoadProcessEvent> chunkHook;
  @Nullable private GrassVariants variants;

  private final FaceOcclusion occlusion = FaceOcclusion.fromBlockTypes();

  private boolean enabled = true;
  private boolean variantsResolved;

  private BetterGrassFeature(@Nonnull final JavaPlugin plugin) {
    this.plugin = plugin;
  }

  /**
   * Installs Better Grass into a host plugin. Call from {@code setup()}; call {@link #shutdown()}
   * from {@code shutdown()}.
   */
  @Nonnull
  public static BetterGrassFeature install(@Nonnull final JavaPlugin plugin) {
    final var feature = new BetterGrassFeature(plugin);

    feature.chunkHook =
        plugin
            .getEventRegistry()
            .registerGlobal(ChunkPreLoadProcessEvent.class, feature::onChunkPreLoad);

    plugin.getChunkStoreRegistry().registerSystem(new BetterGrassChunkSystem(feature));
    plugin
        .getEntityStoreRegistry()
        .registerSystem(new BetterGrassBlockEventSystems.BlockPlaced(feature));
    plugin
        .getEntityStoreRegistry()
        .registerSystem(new BetterGrassBlockEventSystems.BlockBroken(feature));

    plugin.getCommandRegistry().registerCommand(new BetterGrassCommand(feature));

    return feature;
  }

  /** Unregisters the chunk hook. Blocks already converted stay converted. */
  public void shutdown() {
    if (chunkHook != null) {
      chunkHook.unregister();
      chunkHook = null;
    }
  }

  /** @return the test for whether a neighbouring block hides the side face behind it */
  @Nonnull
  public FaceOcclusion occlusion() {
    return occlusion;
  }

  /** @return whether Better Grass is converting blocks */
  public boolean isEnabled() {
    return enabled;
  }

  /** Turns conversion on or off. Existing converted blocks are left as they are. */
  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  /**
   * Resolves the grass block pairs, once, on first use.
   *
   * <p>Deferred rather than done at setup because block type assets are not loaded yet when plugins
   * are set up, and asset packs may add or replace block types afterwards.
   *
   * @return the resolved pairs, or {@code null} if none could be resolved
   */
  @Nullable public GrassVariants variants() {
    if (!variantsResolved) {
      variantsResolved = true;

      final var resolved =
          GrassVariants.resolve(
              key -> BlockType.getAssetMap().getIndexOrDefault(key, GrassVariants.NOT_FOUND),
              GrassDropSafety::dropsMatch);

      if (!resolved.unresolvedKeys().isEmpty()) {
        plugin
            .getLogger()
            .at(Level.WARNING)
            .log("Better Grass could not resolve block types: %s", resolved.unresolvedKeys());
      }

      if (!resolved.unsafePairs().isEmpty()) {
        plugin
            .getLogger()
            .at(Level.SEVERE)
            .log(
                "Better Grass refused these pairs because their halves drop different items,"
                    + " which would make converting farmable: %s",
                resolved.unsafePairs());
      }

      if (resolved.isEmpty()) {
        plugin
            .getLogger()
            .at(Level.SEVERE)
            .log("Better Grass found no usable grass variants; the feature will do nothing.");
      } else {
        variants = resolved;
      }
    }

    return variants;
  }

  /**
   * Re-evaluates the positions a change at {@code (x, y, z)} can affect.
   *
   * <p>Must run on the world thread. Safe to call for a block that has since changed or unloaded --
   * the rule re-reads everything it needs.
   *
   * @return the number of blocks changed
   */
  public int refreshAround(@Nonnull final World world, final int x, final int y, final int z) {
    final var grassVariants = variants();
    if (grassVariants == null || !enabled) {
      return 0;
    }

    final var chunkStore = world.getChunkStore();
    final var access = BlockAccessors.ofLoadedChunks(chunkStore);
    final var positions = new int[BetterGrassRule.DIRTY_POSITIONS * 3];
    final int count = BetterGrassRule.dirtyPositions(x, y, z, positions);

    int changed = 0;
    for (int i = 0; i < count; i++) {
      final int offset = i * 3;
      if (BetterGrassPass.applyAt(
          chunkStore,
          access,
          grassVariants,
          occlusion,
          positions[offset],
          positions[offset + 1],
          positions[offset + 2])) {
        changed++;
      }
    }

    return changed;
  }

  /** Converts the interior of a chunk as it loads, before anyone has been sent it. */
  private void onChunkPreLoad(@Nonnull final ChunkPreLoadProcessEvent event) {
    if (!enabled) {
      return;
    }

    final var grassVariants = variants();
    if (grassVariants == null) {
      return;
    }

    final var blockChunk = event.getHolder().getComponent(BlockChunk.getComponentType());
    if (blockChunk == null) {
      return;
    }

    BetterGrassPass.applyToFreshChunk(blockChunk, grassVariants, occlusion);
  }
}
