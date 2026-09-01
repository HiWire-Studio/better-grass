package studio.hiwire.bettergrass.command;

import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.VECTOR3I;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import org.joml.Vector3i;
import studio.hiwire.bettergrass.BetterGrassFeature;
import studio.hiwire.bettergrass.BetterGrassRule;
import studio.hiwire.bettergrass.BlockAccess;
import studio.hiwire.bettergrass.BlockAccessors;
import studio.hiwire.bettergrass.GrassVariants;

/**
 * {@code /bettergrass explain <x> <y> <z>} -- prints exactly why a block did or did not convert.
 *
 * <p>Every "this one should have gone full" report comes down to which face vetoed it, and that is
 * not guessable from the outside: it depends on four neighbours, four blocks below those, and
 * whether each neighbour occludes. This dumps all of it, plus the chunk-local coordinates, so a
 * chunk-edge case is obvious at a glance.
 */
public class BetterGrassExplainCommand extends AbstractPlayerCommand {

  /** Mirrors every explanation to the server log; chat messages never reach it. */
  private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

  private static final String[] FACE_NAMES = {"north(-z)", "east(+x)", "south(+z)", "west(-x)"};
  private static final int[] OFFSET_X = {0, 1, 0, -1};
  private static final int[] OFFSET_Z = {-1, 0, 1, 0};

  private final BetterGrassFeature feature;

  @Nonnull
  private final RequiredArg<Vector3i> positionArg =
      withRequiredArg("x y z", "Block position to explain", VECTOR3I);

  public BetterGrassExplainCommand(@Nonnull final BetterGrassFeature feature) {
    super("explain", "Explain why a grass block did or did not get full sides");
    this.feature = feature;
  }

  @Override
  protected void execute(
      @Nonnull final CommandContext context,
      @Nonnull final Store<EntityStore> store,
      @Nonnull final Ref<EntityStore> ref,
      @Nonnull final PlayerRef playerRef,
      @Nonnull final World world) {
    final var variants = feature.variants();
    if (variants == null) {
      report(context, new StringBuilder("Better Grass has no usable grass variants."));
      return;
    }

    final var pos = positionArg.get(context);
    final var occlusion = feature.occlusion();
    final BlockAccess access = BlockAccessors.ofLoadedChunks(world.getChunkStore());

    final var out = new StringBuilder();
    out.append("Better Grass @ ").append(pos.x).append(' ').append(pos.y).append(' ').append(pos.z);
    out.append("\n  chunk ")
        .append(pos.x >> ChunkUtil.BITS)
        .append(',')
        .append(pos.z >> ChunkUtil.BITS)
        .append("  local x=")
        .append(pos.x & ChunkUtil.SIZE_MASK)
        .append(" z=")
        .append(pos.z & ChunkUtil.SIZE_MASK)
        .append(edgeNote(pos));

    final int selfId = access.getBlock(pos.x, pos.y, pos.z);
    out.append("\n  block: ").append(describe(selfId));

    final int baseId = variants.baseOf(selfId);
    if (baseId == GrassVariants.NOT_GRASS) {
      out.append("\n  -> not a grass block, nothing to do");
      report(context, out);
      return;
    }
    if (selfId != baseId) {
      out.append("\n  -> already full-sided; the rule is one-way and never downgrades");
      report(context, out);
      return;
    }

    int exposed = 0;
    int qualifying = 0;
    for (int face = 0; face < OFFSET_X.length; face++) {
      final int nx = pos.x + OFFSET_X[face];
      final int nz = pos.z + OFFSET_Z[face];
      final int neighbourId = access.getBlock(nx, pos.y, nz);

      out.append("\n  ").append(FACE_NAMES[face]).append(": ").append(describe(neighbourId));

      if (neighbourId == BlockType.UNKNOWN_ID) {
        out.append("  <- UNREADABLE (chunk not loaded) - blocks the whole decision");
        continue;
      }
      if (occlusion.hidesSideFace(neighbourId)) {
        out.append("  [hidden, ignored]");
        continue;
      }

      exposed++;
      final int belowId = access.getBlock(nx, pos.y - 1, nz);
      final boolean isGrass = variants.isGrass(belowId);
      if (isGrass) {
        qualifying++;
      }
      out.append("  [exposed] below=")
          .append(describe(belowId))
          .append(isGrass ? "  QUALIFIES" : "  <- VETO (not grass)");
    }

    out.append("\n  exposed=").append(exposed).append(" qualifying=").append(qualifying);
    final int desired =
        BetterGrassRule.desiredBlock(access, variants, occlusion, pos.x, pos.y, pos.z);
    out.append("\n  -> ")
        .append(
            desired == BetterGrassRule.NO_CHANGE
                ? (exposed == 0
                    ? "no change: no exposed face (all sides hidden)"
                    : "no change: no exposed face has grass diagonally below it")
                : "would convert to " + describe(desired));

    report(context, out);
  }

  /** Sends the explanation to the player and mirrors it to the server log. */
  private static void report(
      @Nonnull final CommandContext context, @Nonnull final StringBuilder out) {
    final String text = out.toString();
    context.sendMessage(Message.raw(text));
    LOGGER.at(java.util.logging.Level.INFO).log("%s", text);
  }

  private static String edgeNote(@Nonnull final Vector3i pos) {
    final int lx = pos.x & ChunkUtil.SIZE_MASK;
    final int lz = pos.z & ChunkUtil.SIZE_MASK;
    final boolean edge =
        lx == 0 || lx == ChunkUtil.SIZE_MINUS_1 || lz == 0 || lz == ChunkUtil.SIZE_MINUS_1;
    return edge ? "  (CHUNK EDGE - needs the neighbouring chunk)" : "";
  }

  private static String describe(final int blockId) {
    if (blockId == BlockType.EMPTY_ID) {
      return "air";
    }
    if (blockId == BlockType.UNKNOWN_ID) {
      return "unknown/unloaded";
    }
    final var type = BlockType.getAssetMap().getAsset(blockId);
    return type == null ? ("id:" + blockId) : type.getId();
  }
}
