package studio.hiwire.bettergrass.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import javax.annotation.Nonnull;
import studio.hiwire.bettergrass.BetterGrassFeature;

/**
 * {@code /bettergrass} -- an on/off switch, nothing more.
 *
 * <p>Conversion happens on chunk load and on block edits, so there is no retroactive command to
 * run: existing worlds convert as players explore them.
 */
public class BetterGrassCommand extends AbstractCommandCollection {

  public BetterGrassCommand(@Nonnull final BetterGrassFeature feature) {
    super("bettergrass", "Better-Grass style side textures for grass blocks");
    addSubCommand(new BetterGrassEnabledCommand(feature));
    addSubCommand(new BetterGrassExplainCommand(feature));
  }
}
