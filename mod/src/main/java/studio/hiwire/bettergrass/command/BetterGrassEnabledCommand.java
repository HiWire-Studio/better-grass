package studio.hiwire.bettergrass.command;

import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.BOOLEAN;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import studio.hiwire.bettergrass.BetterGrassFeature;

/**
 * {@code /bettergrass enabled [true|false]} -- reports or sets whether Better Grass converts
 * blocks. With no argument it reports.
 *
 * <p>Turning it off stops future conversions; it does not put converted blocks back, because the
 * rule is one-way by design (a {@code _Full} block may be one a player crafted and placed).
 */
public class BetterGrassEnabledCommand extends AbstractPlayerCommand {

  private final BetterGrassFeature feature;

  @Nonnull
  private final OptionalArg<Boolean> enabledArg =
      withOptionalArg("enabled", "Whether Better Grass should convert blocks", BOOLEAN);

  public BetterGrassEnabledCommand(@Nonnull final BetterGrassFeature feature) {
    super("enabled", "Show or set whether Better Grass converts grass blocks");
    this.feature = feature;
  }

  @Override
  protected void execute(
      @Nonnull final CommandContext context,
      @Nonnull final Store<EntityStore> store,
      @Nonnull final Ref<EntityStore> ref,
      @Nonnull final PlayerRef playerRef,
      @Nonnull final World world) {
    if (enabledArg.provided(context)) {
      feature.setEnabled(enabledArg.get(context).booleanValue());
    }

    final var variants = feature.variants();
    final String detail =
        variants == null
            ? " (no usable grass variants resolved -- check the server log)"
            : " (" + variants.allGrassIds().size() / 2 + " grass variants)";

    context.sendMessage(
        Message.raw("Better Grass is " + (feature.isEnabled() ? "ON" : "OFF") + detail));
  }
}
