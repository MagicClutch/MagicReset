package dev.magicclutch.magicreset.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.magicclutch.magicreset.MagicReset;
import dev.magicclutch.magicreset.config.Messages;
import dev.magicclutch.magicreset.config.Settings;
import dev.magicclutch.magicreset.game.GameManager;
import dev.magicclutch.magicreset.manhunt.ManhuntService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /manhunt} (alias {@code /mm}): Magic Manhunt. Goal and timer settings are shared with
 * {@code /magicreset}.
 */
final class ManhuntCommand {

    private static final String PERM = "magicreset.manhunt";

    private final MagicReset plugin;
    private final GameManager game;
    private final MagicResetCommand shared;

    ManhuntCommand(MagicReset plugin, GameManager game, MagicResetCommand shared) {
        this.plugin = plugin;
        this.game = game;
        this.shared = shared;
    }

    private Messages messages() {
        return this.plugin.messages();
    }

    private ManhuntService manhunt() {
        return this.plugin.manhunt();
    }

    private static CommandSender sender(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender();
    }

    com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("manhunt")
            .requires(source -> source.getSender().hasPermission("magicreset.command.use"))
            .executes(this::menuOrStatus)
            .then(Commands.literal("menu").executes(this::menuOrStatus))
            .then(Commands.literal("help").executes(this::help))
            .then(Commands.literal("status").executes(this::status))
            .then(Commands.literal("on").requires(admin()).executes(ctx -> this.setEnabled(ctx, true)))
            .then(Commands.literal("off").requires(admin()).executes(ctx -> this.setEnabled(ctx, false)))
            .then(Commands.literal("start").requires(admin())
                .executes(ctx -> this.start(ctx, null))
                .then(Commands.argument("seed", StringArgumentType.word()).executes(ctx -> this.start(ctx, StringArgumentType.getString(ctx, "seed")))))
            .then(Commands.literal("target").requires(admin())
                .then(Commands.literal("set").then(Commands.argument("players", ArgumentTypes.players())
                    .executes(ctx -> this.setTargets(ctx, true))))
                .then(Commands.literal("add").then(Commands.argument("players", ArgumentTypes.players())
                    .executes(ctx -> this.setTargets(ctx, false))))
                .then(Commands.literal("remove").then(Commands.argument("players", ArgumentTypes.players())
                    .executes(ctx -> this.setRole(ctx, ManhuntService.Role.HUNTER))))
                .then(Commands.literal("clear").executes(ctx -> {
                    this.manhunt().clearTargets();
                    this.game.saveAsync();
                    return this.status(ctx);
                })))
            .then(Commands.literal("hunter").requires(admin()).then(Commands.argument("players", ArgumentTypes.players())
                .executes(ctx -> this.setRole(ctx, ManhuntService.Role.HUNTER))))
            .then(Commands.literal("spectator").requires(admin()).then(Commands.argument("players", ArgumentTypes.players())
                .executes(ctx -> this.setRole(ctx, ManhuntService.Role.SPECTATOR))))
            .then(Commands.literal("lives").requires(admin()).then(Commands.argument("lives", IntegerArgumentType.integer(1, 100))
                .executes(ctx -> this.setValue(ctx, "manhunt.target-lives", IntegerArgumentType.getInteger(ctx, "lives")))))
            .then(Commands.literal("headstart").requires(admin()).then(Commands.argument("seconds", IntegerArgumentType.integer(0, 600))
                .executes(ctx -> this.setValue(ctx, "manhunt.head-start", IntegerArgumentType.getInteger(ctx, "seconds")))))
            .then(Commands.literal("compass").requires(admin())
                .then(Commands.literal("on").executes(ctx -> this.setValue(ctx, "manhunt.hunter-compass", true)))
                .then(Commands.literal("off").executes(ctx -> this.setValue(ctx, "manhunt.hunter-compass", false))))
            .then(Commands.literal("resetafter").requires(admin()).then(Commands.argument("seconds", IntegerArgumentType.integer(-1, 3600))
                .executes(ctx -> this.setValue(ctx, "manhunt.reset-after", IntegerArgumentType.getInteger(ctx, "seconds")))))
            // the targets' goal and the timer are the same settings as in /magicreset
            .then(this.shared.goalNode())
            .then(this.shared.timerNode());
        return root.build();
    }

    private static java.util.function.Predicate<CommandSourceStack> admin() {
        return source -> source.getSender().hasPermission(PERM);
    }

    private int setEnabled(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        this.plugin.setConfigValue("manhunt.enabled", enabled);
        this.manhunt().onModeChanged();
        this.messages().send(sender(ctx), enabled ? "manhunt.enabled" : "manhunt.disabled");
        return Command.SINGLE_SUCCESS;
    }

    private int start(CommandContext<CommandSourceStack> ctx, String seed) {
        if (this.manhunt().targets().isEmpty()) {
            this.messages().send(sender(ctx), "manhunt.no-targets");
            return 0;
        }
        if (!this.plugin.settings().manhunt().enabled()) {
            this.plugin.setConfigValue("manhunt.enabled", true);
            this.manhunt().onModeChanged();
        }
        Settings.Reset reset = this.plugin.settings().reset();
        String error = this.game.requestReset(seed, reset.countdown(), reset.limboCountdown());
        if (error != null) {
            this.messages().send(sender(ctx), error);
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private int setTargets(CommandContext<CommandSourceStack> ctx, boolean replace) throws CommandSyntaxException {
        List<Player> players = ctx.getArgument("players", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        if (replace) {
            this.manhunt().clearTargets();
        }
        players.forEach(player -> this.manhunt().setRole(player.getUniqueId(), ManhuntService.Role.TARGET));
        this.game.saveAsync();
        return this.status(ctx);
    }

    private int setRole(CommandContext<CommandSourceStack> ctx, ManhuntService.Role role) throws CommandSyntaxException {
        List<Player> players = ctx.getArgument("players", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        players.forEach(player -> this.manhunt().setRole(player.getUniqueId(), role));
        this.game.saveAsync();
        return this.status(ctx);
    }

    private int setValue(CommandContext<CommandSourceStack> ctx, String path, Object value) {
        this.plugin.setConfigValue(path, value);
        this.messages().send(sender(ctx), "manhunt.setting-changed",
            Placeholder.unparsed("setting", path.substring("manhunt.".length())), Placeholder.unparsed("value", String.valueOf(value)));
        return Command.SINGLE_SUCCESS;
    }

    /** Admins get the manhunt dialog, everybody else the status. */
    private int menuOrStatus(CommandContext<CommandSourceStack> ctx) {
        if (sender(ctx) instanceof Player player && player.hasPermission(PERM)) {
            this.plugin.menu().openManhunt(player, false);
            return Command.SINGLE_SUCCESS;
        }
        return this.status(ctx);
    }

    private int status(CommandContext<CommandSourceStack> ctx) {
        Settings.Manhunt config = this.plugin.settings().manhunt();
        long hunters = Bukkit.getOnlinePlayers().stream().filter(p -> this.manhunt().role(p) == ManhuntService.Role.HUNTER).count();
        var resolvers = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
            Placeholder.unparsed("state", config.enabled() ? "on" : "off"),
            Placeholder.unparsed("targets", this.manhunt().targetNames()),
            Placeholder.unparsed("hunters", Long.toString(hunters)),
            Placeholder.unparsed("lives", Integer.toString(config.targetLives())),
            Placeholder.unparsed("headstart", Integer.toString(config.headStart())),
            Placeholder.unparsed("compass", config.hunterCompass() ? "on" : "off"),
            Placeholder.unparsed("resetafter", config.resetAfter() < 0 ? "off" : config.resetAfter() + "s"),
            this.game.goalPlaceholder());
        for (Component line : this.messages().list("manhunt.status", resolvers)) {
            sender(ctx).sendMessage(line);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        for (Component line : this.messages().list("manhunt.help")) {
            sender(ctx).sendMessage(line);
        }
        return Command.SINGLE_SUCCESS;
    }
}
