package studio.hiwire.bettergrass;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.environment.EnvironmentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.palette.IntBytePalette;
import com.hypixel.hytale.server.core.universe.world.chunk.palette.ShortBytePalette;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Measures {@link BetterGrassPass#applyToFreshChunk} over a synthetic but realistically shaped
 * chunk, using the engine's own {@link BlockChunk} and {@link BlockSection}.
 *
 * <p>Off by default so it does not slow the normal build. Run with:
 *
 * <pre>./gradlew :mod:test --tests '*BetterGrassPassBenchmark' -Dbettergrass.bench=true -i</pre>
 */
@EnabledIfSystemProperty(named = "bettergrass.bench", matches = "true")
class BetterGrassPassBenchmark {

  private static final int GRASS_ID = 100;
  private static final int GRASS_FULL_ID = 101;
  private static final int DIRT_ID = 500;

  private static final Map<String, Integer> BLOCK_IDS =
      Map.of("Soil_Grass", GRASS_ID, "Soil_Grass_Full", GRASS_FULL_ID);

  private final FaceOcclusion occlusion =
      blockId -> blockId == DIRT_ID || blockId == GRASS_ID || blockId == GRASS_FULL_ID;

  private final GrassVariants variants =
      GrassVariants.resolve(
          key -> BLOCK_IDS.getOrDefault(key, GrassVariants.NOT_FOUND), (base, full) -> true);

  /**
   * Builds a chunk with a rolling grass surface: one grass block per column at a height that varies
   * by a few blocks, dirt beneath it. That is the shape the rule actually costs money on -- every
   * column holds exactly one grass block and slopes give it real work to do.
   */
  private BlockChunk buildTerrainChunk() {
    final var sections = new BlockSection[ChunkUtil.HEIGHT_SECTIONS];
    for (int i = 0; i < sections.length; i++) {
      sections[i] = new BlockSection();
    }

    final var chunk =
        new BlockChunk(
            new ShortBytePalette(), new IntBytePalette(), new EnvironmentChunk(), sections);
    chunk.load(0, 0);

    for (int x = 0; x < ChunkUtil.SIZE; x++) {
      for (int z = 0; z < ChunkUtil.SIZE; z++) {
        // A few interacting periods so the surface has slopes, flats, ridges and hollows.
        final int surfaceY =
            64
                + (int) (3 * Math.sin(x * 0.35))
                + (int) (3 * Math.cos(z * 0.28))
                + (int) (2 * Math.sin((x + z) * 0.16));

        chunk.setBlock(x, surfaceY, z, GRASS_ID, 0, 0);
        for (int y = surfaceY - 4; y < surfaceY; y++) {
          chunk.setBlock(x, y, z, DIRT_ID, 0, 0);
        }
      }
    }

    return chunk;
  }

  @Test
  @DisplayName("benchmark: applyToFreshChunk over a rolling-terrain chunk")
  void benchmarkFreshChunk() {
    final int warmup = 200;
    final int measured = 500;

    for (int i = 0; i < warmup; i++) {
      BetterGrassPass.applyToFreshChunk(buildTerrainChunk(), variants, occlusion);
    }

    long totalNanos = 0;
    int changed = 0;
    for (int i = 0; i < measured; i++) {
      final var chunk = buildTerrainChunk();
      final long start = System.nanoTime();
      changed = BetterGrassPass.applyToFreshChunk(chunk, variants, occlusion);
      totalNanos += System.nanoTime() - start;
    }

    final double microsPerChunk = totalNanos / (double) measured / 1_000.0;
    final int grassPerChunk = ChunkUtil.SIZE * ChunkUtil.SIZE;

    System.out.printf(
        "%nBetter Grass pre-load pass over one chunk column (%d grass blocks):%n"
            + "  %.1f us/chunk   %.0f ns per grass block   %d blocks converted (%.0f%%)%n"
            + "  extrapolated: %.1f ms for a 21x21 chunk area (radius 10)%n%n",
        grassPerChunk,
        microsPerChunk,
        totalNanos / (double) measured / grassPerChunk,
        changed,
        100.0 * changed / grassPerChunk,
        microsPerChunk * 21 * 21 / 1000.0);
  }

  @Test
  @DisplayName("benchmark: the rule alone, no chunk walking")
  void benchmarkRuleOnly() {
    final var chunk = buildTerrainChunk();
    final BlockAccess access = BlockAccessors.ofChunkColumn(chunk);

    // Every position the pass would evaluate, resolved once so the loop measures only the rule.
    final int surfaceSamples = ChunkUtil.SIZE * ChunkUtil.SIZE;
    final int[] xs = new int[surfaceSamples];
    final int[] ys = new int[surfaceSamples];
    final int[] zs = new int[surfaceSamples];
    int found = 0;

    for (int x = 0; x < ChunkUtil.SIZE && found < surfaceSamples; x++) {
      for (int z = 0; z < ChunkUtil.SIZE && found < surfaceSamples; z++) {
        for (int y = 0; y < ChunkUtil.HEIGHT; y++) {
          if (access.getBlock(x, y, z) == GRASS_ID) {
            xs[found] = x;
            ys[found] = y;
            zs[found] = z;
            found++;
            break;
          }
        }
      }
    }

    final int iterations = 2_000;
    long sink = 0;

    for (int i = 0; i < 200; i++) {
      for (int j = 0; j < found; j++) {
        sink += BetterGrassRule.desiredBlock(access, variants, occlusion, xs[j], ys[j], zs[j]);
      }
    }

    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      for (int j = 0; j < found; j++) {
        sink += BetterGrassRule.desiredBlock(access, variants, occlusion, xs[j], ys[j], zs[j]);
      }
    }
    final long elapsed = System.nanoTime() - start;

    System.out.printf(
        "%nBetterGrassRule alone: %.0f ns per block over %d blocks (sink=%d)%n%n",
        elapsed / (double) iterations / found, found, sink);
  }

  @Test
  @DisplayName("benchmark: cost of a section-ref lookup per read vs a direct column read")
  void benchmarkAccessorShape() {
    final var chunk = buildTerrainChunk();
    final BlockAccess direct = BlockAccessors.ofChunkColumn(chunk);

    final int iterations = 500_000;
    long sink = 0;

    for (int i = 0; i < 50_000; i++) {
      sink += direct.getBlock(i & 31, 64, (i >> 5) & 31);
    }

    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      sink += direct.getBlock(i & 31, 64, (i >> 5) & 31);
    }
    final long elapsed = System.nanoTime() - start;

    System.out.printf(
        "%nofChunkColumn read: %.1f ns/read (sink=%d)%n"
            + "  the command path adds a chunk-section reference lookup plus a component fetch"
            + " to every one of these.%n%n",
        elapsed / (double) iterations, sink);
  }

  @Test
  @DisplayName("sanity: the terrain actually produces conversions")
  void terrainProducesConversions() {
    final int changed = BetterGrassPass.applyToFreshChunk(buildTerrainChunk(), variants, occlusion);
    if (changed <= 0) {
      throw new AssertionError("benchmark terrain converted nothing; the numbers would be junk");
    }
    System.out.println("Benchmark terrain converts " + changed + " blocks per chunk.");
  }

  @Test
  @DisplayName("benchmark: cost of re-scanning a chunk that was already converted")
  void benchmarkReloadCost() {
    final var fresh = buildTerrainChunk();
    BetterGrassPass.applyToFreshChunk(fresh, variants, occlusion); // convert once

    for (int i = 0; i < 200; i++) {
      BetterGrassPass.applyToFreshChunk(fresh, variants, occlusion);
    }
    final int measured = 500;
    long nanos = 0;
    for (int i = 0; i < measured; i++) {
      final long t = System.nanoTime();
      BetterGrassPass.applyToFreshChunk(fresh, variants, occlusion);
      nanos += System.nanoTime() - t;
    }

    // How much of a chunk is border, i.e. what the edge pass actually needs?
    int total = 0, border = 0;
    for (int x = 0; x < ChunkUtil.SIZE; x++) {
      for (int z = 0; z < ChunkUtil.SIZE; z++) {
        total++;
        if (x == 0 || x == ChunkUtil.SIZE_MINUS_1 || z == 0 || z == ChunkUtil.SIZE_MINUS_1) {
          border++;
        }
      }
    }
    System.out.printf(
        "%nRe-scan of an ALREADY CONVERTED chunk: %.1f us  (first pass was ~76 us)%n"
            + "  border columns %d of %d (%.0f%%) -- the edge pass walks all %d to act on %d%n"
            + "  chunk load currently runs: 1 interior pass + 5 edge passes%n%n",
        nanos / (double) measured / 1000.0, border, total, 100.0 * border / total, total, border);
  }
}
