package studio.hiwire.bettergrass;

import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.protocol.Opacity;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;

/**
 * Decides whether a neighbouring block hides the side face behind it.
 *
 * <p>{@link BetterGrassRule} only cares about faces a player can actually see, and "can I see it"
 * is not the same question as "is the neighbour air". A tuft of grass standing on the block below
 * leaves the side behind it in full view despite not being air, and most hillsides are covered in
 * such plants.
 */
@FunctionalInterface
public interface FaceOcclusion {

  /**
   * @param blockId the block occupying the neighbouring position
   * @return {@code true} if that block completely hides the face behind it
   */
  boolean hidesSideFace(int blockId);

  /**
   * The real test, read off the block type.
   *
   * <p>A face is hidden only by a solid, full, opaque cube. Each of the three conditions earns its
   * place:
   *
   * <ul>
   *   <li>{@code Material.Solid} -- plants resolve to the default {@code Empty};
   *   <li>{@code isCubeDrawType()} -- plants are {@code DrawType.Model}, a cross-shaped mesh that
   *       covers almost nothing;
   *   <li>an opacity that actually covers -- {@code Solid}, or {@code Cutout} for a texture that is
   *       alpha-tested but still fills its face. {@code Transparent} and {@code Semitransparent}
   *       are cubes you can see through, so the side behind them is decided on its merits.
   * </ul>
   */
  static FaceOcclusion fromBlockTypes() {
    return blockId -> {
      if (blockId == BlockType.EMPTY_ID || blockId == BlockType.UNKNOWN_ID) {
        return false;
      }

      final var blockType = BlockType.getAssetMap().getAsset(blockId);
      if (blockType == null) {
        return false;
      }

      return hides(blockType.getMaterial(), blockType.getOpacity(), blockType.isCubeDrawType());
    };
  }

  /**
   * The occlusion test itself, split out from the asset lookup so it can be exercised directly.
   *
   * @param material a face is only hidden by something solid; plants resolve to {@code Empty}
   * @param opacity {@code Solid} or {@code Cutout} cover the face; the transparent modes do not
   * @param cubeDrawType whether the block fills its cell, from {@code BlockType.isCubeDrawType()}
   */
  static boolean hides(
      final BlockMaterial material, final Opacity opacity, final boolean cubeDrawType) {
    return material == BlockMaterial.Solid
        && cubeDrawType
        && (opacity == Opacity.Solid || opacity == Opacity.Cutout);
  }
}
