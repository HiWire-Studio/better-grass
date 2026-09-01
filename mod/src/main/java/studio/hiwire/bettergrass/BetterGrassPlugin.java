package studio.hiwire.bettergrass;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import lombok.Getter;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

/**
 * Better Grass: OptiFine's "Better Grass" for Hytale, done server-side.
 *
 * <p>The plugin is a thin shell. Everything lives in {@link BetterGrassFeature}, which was written
 * to be installable into any host plugin through a single call -- it started life inside another
 * mod and moved here unchanged.
 *
 * <p>The other half of this mod ships no code at all: 29 asset overrides under {@code
 * Server/Item/Items/Soil/Grass} and friends give each grass family its own block group and a
 * one-directional transition hierarchy, so grass types blend into each other without stacking two
 * masks on the same seam. See {@code tools/generate_grass_overrides.py}.
 */
public class BetterGrassPlugin extends JavaPlugin {

  public static final String PREFIX = "[HiWire:BetterGrass]";

  private static BetterGrassPlugin instance;

  @Getter private BetterGrassFeature betterGrassFeature;

  public BetterGrassPlugin(@NonNullDecl final JavaPluginInit init) {
    super(init);
  }

  public static BetterGrassPlugin get() {
    return instance;
  }

  @Override
  protected void setup() {
    instance = this;
    betterGrassFeature = BetterGrassFeature.install(this);
  }

  @Override
  protected void shutdown() {
    if (betterGrassFeature != null) {
      betterGrassFeature.shutdown();
    }
  }
}
