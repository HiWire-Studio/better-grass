package studio.hiwire.bettergrass;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntLists;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;

/**
 * The base &harr; {@code _Full} block id pairs that Better Grass swaps between.
 *
 * <p>Hytale already ships a "grass on every side" variant of each grass block (see {@code
 * Soil_Grass_Full}, whose sides use {@code Soil_Grass_Side_Full_GS.png} instead of the
 * dirt-with-a-fringe {@code Soil_Grass_Side.png}). Worldgen places those variants by hand in a few
 * zone definitions; nothing swaps to them at runtime. This class resolves the pairs so {@link
 * BetterGrassRule} can.
 *
 * <p>Every variant declares {@code "Parent": "Soil_Grass"}, so a base block and its {@code _Full}
 * counterpart share material, group, gathering and drops. The swap is therefore purely cosmetic --
 * nothing that keys off block behaviour can tell the difference.
 *
 * <p>Resolution is done through a {@link ToIntFunction} rather than by reaching for {@code
 * BlockType.getAssetMap()} directly so the rule can be unit tested without a running server.
 */
public final class GrassVariants {

  /** Returned by {@link #baseOf} for a block that is not part of any known grass pair. */
  public static final int NOT_GRASS = -1;

  /** Block id used to mean "this asset does not exist"; matches {@code AssetMap.NOT_FOUND}. */
  public static final int NOT_FOUND = -1;

  private static final String FULL_SUFFIX = "_Full";

  /**
   * The base block keys. The {@code _Full} counterpart of each is derived by suffix, which is how
   * the vanilla assets are named without exception.
   *
   * <p>These are every base/{@code _Full} pair in the game that is <em>ground cover</em>. The game
   * has others -- {@code Wood_*_Trunk}, {@code Deco_SpiderWeb}, {@code Furniture_Cybercity_Windows}
   * -- but those wrap trunks or decorate props rather than carpeting the ground, so the
   * hillside rule means nothing for them.
   *
   * <p>{@code Soil_Leaves} belongs here even though it is not grass: it is leaf litter on the
   * ground with the same base/full split, and it reads as one surface with grass, so each counts as
   * "grass" for the other's below-diagonal test. Its group is {@code LeavesGround}, which nothing
   * paints a transition onto, so converting it cannot produce the seam the grass families needed
   * asset overrides to avoid.
   */
  private static final List<String> BASE_KEYS =
      List.of(
          "Soil_Grass",
          "Soil_Grass_Burnt",
          "Soil_Grass_Cold",
          "Soil_Grass_Deep",
          "Soil_Grass_Dry",
          "Soil_Grass_Sunny",
          "Soil_Grass_Wet",
          "Soil_Leaves");

  private final Int2IntMap baseByAnyId;
  private final Int2IntMap fullByBaseId;
  private final IntList allGrassIds;
  private final List<String> unresolvedKeys;
  private final List<String> unsafePairs;

  private GrassVariants(
      Int2IntMap baseByAnyId,
      Int2IntMap fullByBaseId,
      IntList allGrassIds,
      List<String> unresolvedKeys,
      List<String> unsafePairs) {
    this.baseByAnyId = baseByAnyId;
    this.fullByBaseId = fullByBaseId;
    this.allGrassIds = allGrassIds;
    this.unresolvedKeys = unresolvedKeys;
    this.unsafePairs = unsafePairs;
  }

  /**
   * Resolves every grass pair through {@code blockIdLookup}, keeping only pairs {@code
   * pairDropsMatch} vouches for.
   *
   * <p>A pair is kept only when both halves resolve; a half that is missing is reported through
   * {@link #unresolvedKeys()} so the caller can log it rather than failing the whole feature.
   *
   * <p>The {@code pairDropsMatch} check is what keeps the conversion from becoming an item dupe. In
   * the shipped assets it always passes -- every {@code _Full} variant declares {@code "Parent":
   * "Soil_Grass"} and never overrides {@code Gathering}, so both halves drop {@code Soil_Dirt} and
   * converting one into the other creates nothing. That safety is inherited rather than declared,
   * though, so an asset pack or a future patch could break it silently. Refusing a pair whose
   * halves do not drop the same thing means the worst case is that Better Grass stops working for
   * that variant, instead of minting items.
   *
   * @param blockIdLookup maps a block type key to its asset index, or {@link #NOT_FOUND}
   * @param pairDropsMatch given (baseKey, fullKey), whether breaking either yields the same items
   */
  public static GrassVariants resolve(
      ToIntFunction<String> blockIdLookup, BiPredicate<String, String> pairDropsMatch) {
    final var baseByAnyId = new Int2IntOpenHashMap();
    final var fullByBaseId = new Int2IntOpenHashMap();
    final var allGrassIds = new IntArrayList();
    final var unresolved = new ArrayList<String>();
    final var unsafe = new ArrayList<String>();

    baseByAnyId.defaultReturnValue(NOT_GRASS);
    fullByBaseId.defaultReturnValue(NOT_GRASS);

    for (final String baseKey : BASE_KEYS) {
      final String fullKey = baseKey + FULL_SUFFIX;
      final int baseId = blockIdLookup.applyAsInt(baseKey);
      final int fullId = blockIdLookup.applyAsInt(fullKey);

      if (baseId == NOT_FOUND) {
        unresolved.add(baseKey);
      }
      if (fullId == NOT_FOUND) {
        unresolved.add(fullKey);
      }
      if (baseId == NOT_FOUND || fullId == NOT_FOUND) {
        continue;
      }
      if (!pairDropsMatch.test(baseKey, fullKey)) {
        unsafe.add(baseKey + " -> " + fullKey);
        continue;
      }

      baseByAnyId.put(baseId, baseId);
      baseByAnyId.put(fullId, baseId);
      fullByBaseId.put(baseId, fullId);
      allGrassIds.add(baseId);
      allGrassIds.add(fullId);
    }

    return new GrassVariants(
        baseByAnyId,
        fullByBaseId,
        IntLists.unmodifiable(allGrassIds),
        List.copyOf(unresolved),
        List.copyOf(unsafe));
  }

  /** @return {@code true} if no pair resolved at all, meaning the feature cannot do anything */
  public boolean isEmpty() {
    return allGrassIds.isEmpty();
  }

  /** @return the block keys that could not be resolved, for logging */
  public List<String> unresolvedKeys() {
    return unresolvedKeys;
  }

  /**
   * @return pairs rejected because their halves do not drop the same items, for logging. A
   *     non-empty list means an asset changed in a way that would have made converting profitable.
   */
  public List<String> unsafePairs() {
    return unsafePairs;
  }

  /**
   * Every known grass block id, base and {@code _Full} alike. Suitable for {@code
   * BlockSection.containsAny} / {@code BlockSection.find}, which lets a section that holds no grass
   * be skipped without walking its 32<sup>3</sup> blocks.
   */
  public IntList allGrassIds() {
    return allGrassIds;
  }

  /** @return {@code true} if {@code blockId} is a grass block in either of its two forms */
  public boolean isGrass(int blockId) {
    return baseByAnyId.get(blockId) != NOT_GRASS;
  }

  /**
   * @return the base ("fringed sides") id of the pair {@code blockId} belongs to, or {@link
   *     #NOT_GRASS}
   */
  public int baseOf(int blockId) {
    return baseByAnyId.get(blockId);
  }

  /** @return the {@code _Full} ("grass on every side") id for a base id, or {@link #NOT_GRASS} */
  public int fullOf(int baseId) {
    return fullByBaseId.get(baseId);
  }
}
