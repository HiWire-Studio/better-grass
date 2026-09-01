package studio.hiwire.bettergrass;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockBreakingDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.HarvestingDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.SoftBlockDropType;
import java.util.Objects;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Decides whether converting one grass block into another can be exploited for items.
 *
 * <p>Better Grass turns a block a player can place cheaply into one that is otherwise crafted from
 * Soils plus a fibre. That is only harmless while breaking either yields the same thing. In the
 * shipped assets it does -- {@code Soil_Grass} declares {@code Gathering.Breaking.ItemId =
 * "Soil_Dirt"}, every {@code _Full} variant inherits it through {@code "Parent": "Soil_Grass"}, and
 * {@code BlockHarvestUtils.getDrops} returns exactly that item -- so the swap creates nothing.
 *
 * <p>But nothing declares that invariant, so an asset pack or a patch could quietly break it. This
 * check enforces it, and Better Grass drops any pair that fails rather than risk minting items.
 */
public final class GrassDropSafety {

  private GrassDropSafety() {}

  /**
   * @return {@code true} if breaking {@code baseKey} and {@code fullKey} yields the same items, so
   *     converting between them cannot be farmed
   */
  public static boolean dropsMatch(final String baseKey, final String fullKey) {
    final var assetMap = BlockType.getAssetMap();
    final var base =
        assetMap.getAsset(assetMap.getIndexOrDefault(baseKey, GrassVariants.NOT_FOUND));
    final var full =
        assetMap.getAsset(assetMap.getIndexOrDefault(fullKey, GrassVariants.NOT_FOUND));
    if (base == null || full == null) {
      return false;
    }

    final var baseGathering = base.getGathering();
    final var fullGathering = full.getGathering();

    // The common case: the variant inherits its parent's gathering, so this is the same instance.
    if (baseGathering == fullGathering) {
      return !usesDefaultDropWhenPlaced(baseGathering);
    }
    if (baseGathering == null || fullGathering == null) {
      return false;
    }
    if (usesDefaultDropWhenPlaced(baseGathering) || usesDefaultDropWhenPlaced(fullGathering)) {
      return false;
    }

    // The three drop configurations are unrelated classes that happen to share the same two
    // accessors, so each is compared through them.
    return sameDrop(
            baseGathering.getBreaking(),
            fullGathering.getBreaking(),
            BlockBreakingDropType::getItemId,
            BlockBreakingDropType::getDropListId)
        && sameDrop(
            baseGathering.getSoft(),
            fullGathering.getSoft(),
            SoftBlockDropType::getItemId,
            SoftBlockDropType::getDropListId)
        && sameDrop(
            baseGathering.getHarvest(),
            fullGathering.getHarvest(),
            HarvestingDropType::getItemId,
            HarvestingDropType::getDropListId);
  }

  /**
   * A block whose gathering sets {@code UseDefaultDropWhenPlaced} drops <em>itself</em> when it was
   * player-placed rather than its configured drop ({@code BlockHarvestUtils} nulls the item id for
   * a "deco" block). Converting such a block would hand the player the converted variant as an
   * item, which is exactly the dupe this guard exists to prevent.
   */
  private static boolean usesDefaultDropWhenPlaced(@Nullable final BlockGathering gathering) {
    return gathering != null && gathering.shouldUseDefaultDropWhenPlaced();
  }

  /** Two drop configurations match when both are absent, or both name the same item and list. */
  private static <T> boolean sameDrop(
      @Nullable final T base,
      @Nullable final T full,
      @Nonnull final Function<T, String> itemId,
      @Nonnull final Function<T, String> dropListId) {
    if (base == null || full == null) {
      return base == full;
    }
    return Objects.equals(itemId.apply(base), itemId.apply(full))
        && Objects.equals(dropListId.apply(base), dropListId.apply(full));
  }
}
