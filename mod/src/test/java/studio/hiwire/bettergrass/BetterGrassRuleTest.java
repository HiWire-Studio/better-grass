package studio.hiwire.bettergrass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.protocol.Opacity;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link BetterGrassRule} against a hand-built neighbourhood.
 *
 * <p>The rule reads the world only through {@link BlockAccess} and resolves block ids only through
 * {@link GrassVariants#resolve}, so both can be faked and no server has to run.
 */
class BetterGrassRuleTest {

  private static final String GRASS = "Soil_Grass";
  private static final String GRASS_FULL = "Soil_Grass_Full";
  private static final String COLD = "Soil_Grass_Cold";
  private static final String COLD_FULL = "Soil_Grass_Cold_Full";

  private static final int STONE_ID = 500;

  /** A plant such as Plant_Grass_Arid: occupies the position but hides nothing behind it. */
  private static final int PLANT_ID = 600;

  /** Assigns stable ids well clear of {@code EMPTY_ID} (0) and {@code UNKNOWN_ID} (1). */
  private static final Map<String, Integer> BLOCK_IDS =
      Map.of(
          GRASS, 100,
          GRASS_FULL, 101,
          COLD, 102,
          COLD_FULL, 103);

  private final GrassVariants variants =
      GrassVariants.resolve(
          key -> BLOCK_IDS.getOrDefault(key, GrassVariants.NOT_FOUND), (base, full) -> true);

  private final int grassId = BLOCK_IDS.get(GRASS);
  private final int grassFullId = BLOCK_IDS.get(GRASS_FULL);
  private final int coldId = BLOCK_IDS.get(COLD);
  private final int coldFullId = BLOCK_IDS.get(COLD_FULL);

  /**
   * Solid, full, opaque cubes are the only occluders. Plants are not, which is the whole point of
   * {@link FaceOcclusion}.
   */
  private final FaceOcclusion occlusion =
      blockId -> blockId == STONE_ID || variants.isGrass(blockId);

  /** A sparse world; anything not placed is air. */
  private static final class FakeWorld implements BlockAccess {
    private final Map<Long, Integer> blocks = new HashMap<>();

    FakeWorld set(int x, int y, int z, int blockId) {
      blocks.put(key(x, y, z), blockId);
      return this;
    }

    @Override
    public int getBlock(int x, int y, int z) {
      return blocks.getOrDefault(key(x, y, z), BlockType.EMPTY_ID);
    }

    private static long key(int x, int y, int z) {
      return ((long) x << 42) ^ ((long) y << 21) ^ z;
    }
  }

  @Test
  @DisplayName("resolves every shipped grass pair")
  void resolvesPairs() {
    assertTrue(variants.unresolvedKeys().containsAll(List.of("Soil_Grass_Burnt")));
    assertEquals(grassId, variants.baseOf(grassFullId));
    assertEquals(grassFullId, variants.fullOf(grassId));
    assertTrue(variants.isGrass(grassFullId));
    assertEquals(GrassVariants.NOT_GRASS, variants.baseOf(STONE_ID));
  }

  @Test
  @DisplayName("a block with no exposed side is left alone")
  void buriedBlockIsUntouched() {
    final var world =
        new FakeWorld()
            .set(0, 10, 0, grassId)
            .set(1, 10, 0, grassId)
            .set(-1, 10, 0, grassId)
            .set(0, 10, 1, grassId)
            .set(0, 10, -1, grassId);

    assertEquals(
        BetterGrassRule.NO_CHANGE,
        BetterGrassRule.desiredBlock(world, variants, occlusion, 0, 10, 0));
  }

  @Test
  @DisplayName("a hillside step goes full: the exposed side has grass diagonally below")
  void hillsideStepGoesFull() {
    final var world =
        new FakeWorld()
            .set(0, 10, 0, grassId)
            // three sides buried
            .set(-1, 10, 0, grassId)
            .set(0, 10, 1, grassId)
            .set(0, 10, -1, grassId)
            // east is open, and the terrain steps down to grass
            .set(1, 9, 0, grassId);

    assertEquals(grassFullId, BetterGrassRule.desiredBlock(world, variants, occlusion, 0, 10, 0));
  }

  @Test
  @DisplayName("a cliff over stone stays fringed")
  void cliffOverStoneStaysBase() {
    final var world =
        new FakeWorld()
            .set(0, 10, 0, grassId)
            .set(-1, 10, 0, grassId)
            .set(0, 10, 1, grassId)
            .set(0, 10, -1, grassId)
            .set(1, 9, 0, STONE_ID);

    assertEquals(
        BetterGrassRule.NO_CHANGE,
        BetterGrassRule.desiredBlock(world, variants, occlusion, 0, 10, 0));
  }

  @Test
  @DisplayName("a corner qualifying on only one of two exposed sides stays fringed")
  void mixedCornerStaysBase() {
    final var world =
        new FakeWorld()
            .set(0, 10, 0, grassId)
            .set(-1, 10, 0, grassId)
            .set(0, 10, -1, grassId)
            // east drops to grass, south drops to stone
            .set(1, 9, 0, grassId)
            .set(0, 9, 1, STONE_ID);

    // Every exposed face must qualify, so the drop to stone keeps this block fringed rather than
    // showing grass above bare rock.
    assertEquals(
        BetterGrassRule.NO_CHANGE,
        BetterGrassRule.desiredBlock(world, variants, occlusion, 0, 10, 0));
  }

  @Test
  @DisplayName("a full block is never downgraded, even when it no longer qualifies")
  void fullBlocksAreNeverDowngraded() {
    // It could be one a player crafted (Soils + a fibre) and placed deliberately; the rule cannot
    // tell that apart from one it converted, so it leaves every full block alone.
    final var world =
        new FakeWorld()
            .set(0, 10, 0, grassFullId)
            .set(-1, 10, 0, grassId)
            .set(0, 10, 1, grassId)
            .set(0, 10, -1, grassId)
            .set(1, 9, 0, STONE_ID);

    assertEquals(
        BetterGrassRule.NO_CHANGE,
        BetterGrassRule.desiredBlock(world, variants, occlusion, 0, 10, 0));
  }

  @Test
  @DisplayName("the dirty set is the block, its four side neighbours, and those one block up")
  void dirtySetInvertsTheRule() {
    final var out = new int[BetterGrassRule.DIRTY_POSITIONS * 3];
    final int count = BetterGrassRule.dirtyPositions(10, 20, 30, out);

    assertEquals(BetterGrassRule.DIRTY_POSITIONS, count);

    final var seen = new java.util.HashSet<String>();
    for (int i = 0; i < count; i++) {
      seen.add(out[i * 3] + "," + out[i * 3 + 1] + "," + out[i * 3 + 2]);
    }

    assertEquals(
        java.util.Set.of(
            "10,20,30",
            "10,20,29",
            "10,21,29",
            "11,20,30",
            "11,21,30",
            "10,20,31",
            "10,21,31",
            "9,20,30",
            "9,21,30"),
        seen);
  }

  @Test
  @DisplayName("an unreadable neighbour blocks the decision rather than guessing")
  void unknownNeighbourDefersDecision() {
    final BlockAccess world =
        (x, y, z) -> {
          if (x == 0 && y == 10 && z == 0) {
            return grassId;
          }
          if (x == 1) {
            return BlockType.UNKNOWN_ID;
          }
          return BlockType.EMPTY_ID;
        };

    assertEquals(
        BetterGrassRule.NO_CHANGE,
        BetterGrassRule.desiredBlock(world, variants, occlusion, 0, 10, 0));
  }

  @Test
  @DisplayName("variants keep their own family: a cold block goes to cold full")
  void coldVariantUsesItsOwnFull() {
    final var world =
        new FakeWorld()
            .set(0, 10, 0, coldId)
            .set(-1, 10, 0, coldId)
            .set(0, 10, 1, coldId)
            .set(0, 10, -1, coldId)
            .set(1, 9, 0, grassId);

    assertEquals(coldFullId, BetterGrassRule.desiredBlock(world, variants, occlusion, 0, 10, 0));
  }

  @Test
  @DisplayName("a plant standing on the lower block does not hide the face behind it")
  void plantsDoNotOccludeTheFace() {
    // The Plant_Grass_Arid case: the hillside steps down to grass, but a tuft of dry grass grows
    // on that lower block. The face is still in plain view, so the block must still convert.
    final var world =
        new FakeWorld()
            .set(0, 10, 0, grassId)
            .set(-1, 10, 0, grassId)
            .set(0, 10, 1, grassId)
            .set(0, 10, -1, grassId)
            .set(1, 10, 0, PLANT_ID)
            .set(1, 9, 0, grassId);

    assertEquals(grassFullId, BetterGrassRule.desiredBlock(world, variants, occlusion, 0, 10, 0));
  }

  @Test
  @DisplayName("a plant over stone still disqualifies, it does not merely get skipped")
  void plantOverStoneStillDisqualifies() {
    final var world =
        new FakeWorld()
            .set(0, 10, 0, grassId)
            .set(-1, 10, 0, grassId)
            .set(0, 10, 1, grassId)
            .set(0, 10, -1, grassId)
            .set(1, 10, 0, PLANT_ID)
            .set(1, 9, 0, STONE_ID);

    assertEquals(
        BetterGrassRule.NO_CHANGE,
        BetterGrassRule.desiredBlock(world, variants, occlusion, 0, 10, 0));
  }

  @Test
  @DisplayName("a non-grass block is never touched")
  void nonGrassIsUntouched() {
    final var world = new FakeWorld().set(0, 10, 0, STONE_ID);

    assertEquals(
        BetterGrassRule.NO_CHANGE,
        BetterGrassRule.desiredBlock(world, variants, occlusion, 0, 10, 0));
  }

  @Test
  @DisplayName("applying the rule twice changes nothing the second time")
  void ruleIsIdempotent() {
    final var world =
        new FakeWorld()
            .set(0, 10, 0, grassId)
            .set(-1, 10, 0, grassId)
            .set(0, 10, 1, grassId)
            .set(0, 10, -1, grassId)
            .set(1, 9, 0, grassId);

    final int first = BetterGrassRule.desiredBlock(world, variants, occlusion, 0, 10, 0);
    assertEquals(grassFullId, first);

    world.set(0, 10, 0, first);
    assertEquals(
        BetterGrassRule.NO_CHANGE,
        BetterGrassRule.desiredBlock(world, variants, occlusion, 0, 10, 0));
  }

  @Test
  @DisplayName("a solid full cube hides the face behind it, whatever its opacity mode")
  void occlusionAcceptsCutout() {
    // Soil_Leaves is a solid full cube that happens to be alpha-tested; it still covers its face.
    assertTrue(FaceOcclusion.hides(BlockMaterial.Solid, Opacity.Cutout, true));
    assertTrue(FaceOcclusion.hides(BlockMaterial.Solid, Opacity.Solid, true));
  }

  @Test
  @DisplayName("see-through cubes, plants and non-cubes do not hide the face")
  void occlusionRejectsSeeThroughAndNonCubes() {
    assertFalse(FaceOcclusion.hides(BlockMaterial.Solid, Opacity.Transparent, true));
    assertFalse(FaceOcclusion.hides(BlockMaterial.Solid, Opacity.Semitransparent, true));
    assertFalse(FaceOcclusion.hides(BlockMaterial.Empty, Opacity.Solid, true));
    assertFalse(FaceOcclusion.hides(BlockMaterial.Solid, Opacity.Solid, false));
  }
}
