package dev.pvpbotcmd;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Adds /pvpbot. Everything is done by running HeroBot's own commands
 * (/playerspawn and /player), so this mod has no compile-time dependency on HeroBot.
 *
 *   /pvpbot spawn <name>              spawn a survival bot with a diamond kit at your position
 *   /pvpbot fight <name> [target]     bot chases and attacks target (default: you)
 *   /pvpbot stop <name>               bot stops fighting
 *   /pvpbot ping <name> <ms>          set the bot's simulated ping
 *   /pvpbot range [blocks]            show or set the attack range (saved to config/pvpbotcmd.properties)
 *   /pvpbot bunnyhop [true|false]     show or set sprint-jumping while chasing (saved to config)
 *   /pvpbot hopstop [blocks]          show or set the distance at which bunny hopping stops (default 3.5)
 *   /pvpbot eat [hunger]              show or set the hunger level at or below which bots eat (0 = never, default 14)
 *   /pvpbot remove <name>             remove the bot
 *   /pvpbot clear                     remove all bots made with /pvpbot
 *   /pvpbot list                      list bots made with /pvpbot
 */
public class PvpBotMod implements ModInitializer {

    private static final Set<String> BOTS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private static final Map<String, Fight> FIGHTS = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private static CommandDispatcher<CommandSourceStack> dispatcher;
    private static int tickCounter = 0;

    private static final double MIN_RANGE = 1.0;
    private static final double MAX_RANGE = 6.0;
    private static double attackRange = 3.3;
    private static boolean bunnyHop = true;
    private static double hopStopDistance = 3.5;
    private static int eatBelow = 14;
    private static final double MAX_HOP_STOP = 8.0;
    private static final double HOP_RESUME_MARGIN = 1.0;

    private static final SuggestionProvider<CommandSourceStack> BOT_NAMES =
            (ctx, builder) -> SharedSuggestionProvider.suggest(BOTS, builder);

    private static final class Fight {
        final String target;
        ServerPlayer lastBot;
        boolean started;
        boolean hopping;
        boolean eating;
        int eatStartTick;

        Fight(String target) {
            this.target = target;
        }
    }

    @Override
    public void onInitialize() {
        loadConfig();
        CommandRegistrationCallback.EVENT.register((d, registryAccess, environment) -> {
            dispatcher = d;
            register(d);
        });
        ServerTickEvents.END_SERVER_TICK.register(PvpBotMod::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            BOTS.clear();
            FIGHTS.clear();
        });
    }

    // ---------------------------------------------------------------- commands

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("pvpbot")
                .then(Commands.literal("spawn")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("fight")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(BOT_NAMES)
                                .executes(ctx -> fight(ctx, StringArgumentType.getString(ctx, "name"), null))
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .executes(ctx -> fight(ctx,
                                                StringArgumentType.getString(ctx, "name"),
                                                StringArgumentType.getString(ctx, "target"))))))
                .then(Commands.literal("stop")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(BOT_NAMES)
                                .executes(ctx -> stop(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("ping")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(BOT_NAMES)
                                .then(Commands.argument("ms", IntegerArgumentType.integer(0, 1000))
                                        .executes(ctx -> ping(ctx,
                                                StringArgumentType.getString(ctx, "name"),
                                                IntegerArgumentType.getInteger(ctx, "ms"))))))
                .then(Commands.literal("range")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Attack range is " + attackRange + " blocks."), false);
                            return 1;
                        })
                        .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(MIN_RANGE, MAX_RANGE))
                                .executes(ctx -> setRange(ctx, DoubleArgumentType.getDouble(ctx, "blocks")))))
                .then(Commands.literal("bunnyhop")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Bunny hop is " + (bunnyHop ? "on" : "off") + "."), false);
                            return 1;
                        })
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(ctx -> setBunnyHop(ctx, BoolArgumentType.getBool(ctx, "enabled")))))
                .then(Commands.literal("hopstop")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Bunny hop stops at " + hopStopDistance + " blocks."), false);
                            return 1;
                        })
                        .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(MIN_RANGE, MAX_HOP_STOP))
                                .executes(ctx -> setHopStop(ctx, DoubleArgumentType.getDouble(ctx, "blocks")))))
                .then(Commands.literal("eat")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(eatBelow <= 0
                                    ? "Auto-eat is off."
                                    : "Bots eat when hunger is " + eatBelow + " or lower (20 = full)."), false);
                            return 1;
                        })
                        .then(Commands.argument("hunger", IntegerArgumentType.integer(0, 19))
                                .executes(ctx -> setEat(ctx, IntegerArgumentType.getInteger(ctx, "hunger")))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(BOT_NAMES)
                                .executes(ctx -> remove(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("clear")
                        .executes(PvpBotMod::clear))
                .then(Commands.literal("list")
                        .executes(PvpBotMod::list)));
    }

    private static boolean herobotPresent() {
        return dispatcher != null
                && dispatcher.getRoot().getChild("playerspawn") != null
                && dispatcher.getRoot().getChild("player") != null;
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer me = src.getPlayerOrException();
        MinecraftServer server = src.getServer();

        if (!herobotPresent()) {
            src.sendFailure(Component.literal("HeroBot is not installed: /playerspawn and /player were not found."));
            return 0;
        }
        if (name.length() > 16) {
            src.sendFailure(Component.literal("Bot names can be at most 16 characters."));
            return 0;
        }
        if (server.getPlayerList().getPlayerByName(name) != null) {
            src.sendFailure(Component.literal("A player named " + name + " is already online."));
            return 0;
        }

        String pos = String.format(Locale.ROOT, "%.2f %.2f %.2f", me.getX(), me.getY(), me.getZ());
        // Uses the caller's own permissions, so HeroBot's permission rules still apply.
        server.getCommands().performPrefixedCommand(src.withSuppressedOutput(),
                "playerspawn " + name + " at " + pos + " in survival");

        if (server.getPlayerList().getPlayerByName(name) == null) {
            src.sendFailure(Component.literal("Could not spawn " + name + ". Check that you have permission to use /playerspawn."));
            return 0;
        }

        BOTS.add(name);
        giveKit(server, name);
        src.sendSuccess(() -> Component.literal("Spawned " + name + ". Use /pvpbot fight " + name + " to start."), false);
        return 1;
    }

    private static int fight(CommandContext<CommandSourceStack> ctx, String name, String target) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!BOTS.contains(name)) {
            src.sendFailure(Component.literal(name + " is not a bot made with /pvpbot spawn."));
            return 0;
        }
        String t = target != null ? target : src.getPlayerOrException().getName().getString();
        if (name.equalsIgnoreCase(t)) {
            src.sendFailure(Component.literal("A bot can't fight itself."));
            return 0;
        }
        FIGHTS.put(name, new Fight(t));
        src.sendSuccess(() -> Component.literal(name + " is now fighting " + t + "."), false);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack src = ctx.getSource();
        if (!BOTS.contains(name)) {
            src.sendFailure(Component.literal(name + " is not a bot made with /pvpbot spawn."));
            return 0;
        }
        FIGHTS.remove(name);
        run(src.getServer(), "player " + name + " stop");
        src.sendSuccess(() -> Component.literal(name + " stopped."), false);
        return 1;
    }

    private static int ping(CommandContext<CommandSourceStack> ctx, String name, int ms) {
        CommandSourceStack src = ctx.getSource();
        if (!BOTS.contains(name)) {
            src.sendFailure(Component.literal(name + " is not a bot made with /pvpbot spawn."));
            return 0;
        }
        run(src.getServer(), "player " + name + " ping " + ms);
        src.sendSuccess(() -> Component.literal(name + " ping set to " + ms + " ms."), false);
        return 1;
    }

    private static int setRange(CommandContext<CommandSourceStack> ctx, double blocks) {
        attackRange = blocks;
        saveConfig();
        ctx.getSource().sendSuccess(() -> Component.literal("Attack range set to " + blocks + " blocks."), true);
        return 1;
    }

    private static int setBunnyHop(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        bunnyHop = enabled;
        saveConfig();
        ctx.getSource().sendSuccess(() -> Component.literal("Bunny hop " + (enabled ? "on" : "off") + "."), true);
        return 1;
    }

    private static int setHopStop(CommandContext<CommandSourceStack> ctx, double blocks) {
        hopStopDistance = blocks;
        saveConfig();
        ctx.getSource().sendSuccess(() -> Component.literal("Bunny hop now stops at " + blocks + " blocks."), true);
        return 1;
    }

    private static int setEat(CommandContext<CommandSourceStack> ctx, int hunger) {
        eatBelow = hunger;
        saveConfig();
        ctx.getSource().sendSuccess(() -> Component.literal(hunger <= 0
                ? "Auto-eat off."
                : "Bots now eat when hunger is " + hunger + " or lower."), true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack src = ctx.getSource();
        if (!BOTS.contains(name)) {
            src.sendFailure(Component.literal(name + " is not a bot made with /pvpbot spawn."));
            return 0;
        }
        removeBot(src.getServer(), name);
        src.sendSuccess(() -> Component.literal("Removed " + name + "."), false);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        for (String name : new TreeSet<>(BOTS)) {
            removeBot(src.getServer(), name);
        }
        src.sendSuccess(() -> Component.literal("Removed all PvP bots."), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        String text = BOTS.isEmpty() ? "No PvP bots." : "PvP bots: " + String.join(", ", BOTS);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return BOTS.size();
    }

    private static void removeBot(MinecraftServer server, String name) {
        FIGHTS.remove(name);
        BOTS.remove(name);
        run(server, "player " + name + " disconnect");
    }

    // ---------------------------------------------------------------- config

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("pvpbotcmd.properties");
    }

    private static void loadConfig() {
        Path path = configPath();
        if (!Files.exists(path)) {
            saveConfig();
            return;
        }
        try (Reader in = Files.newBufferedReader(path)) {
            Properties props = new Properties();
            props.load(in);
            double v = Double.parseDouble(props.getProperty("attack_range", "3.3").trim());
            attackRange = Math.max(MIN_RANGE, Math.min(MAX_RANGE, v));
            bunnyHop = Boolean.parseBoolean(props.getProperty("bunny_hop", "true").trim());
            double hs = Double.parseDouble(props.getProperty("hop_stop_distance", "3.5").trim());
            hopStopDistance = Math.max(MIN_RANGE, Math.min(MAX_HOP_STOP, hs));
            int eb = Integer.parseInt(props.getProperty("eat_below", "14").trim());
            eatBelow = Math.max(0, Math.min(19, eb));
        } catch (IOException | NumberFormatException ex) {
            attackRange = 3.3;
            bunnyHop = true;
            hopStopDistance = 3.5;
            eatBelow = 14;
        }
    }

    private static void saveConfig() {
        Properties props = new Properties();
        props.setProperty("attack_range", Double.toString(attackRange));
        props.setProperty("bunny_hop", Boolean.toString(bunnyHop));
        props.setProperty("hop_stop_distance", Double.toString(hopStopDistance));
        props.setProperty("eat_below", Integer.toString(eatBelow));
        try (Writer out = Files.newBufferedWriter(configPath())) {
            props.store(out, "PvPBot Commands config. attack_range = blocks (1.0 - 6.0) at which bots start attacking; bunny_hop = true/false; hop_stop_distance = blocks at which hopping stops (default 3.5); eat_below = hunger level (0-19) at or below which bots eat, 0 = never (default 14).");
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Runs a command as the server, without chat feedback. */
    private static void run(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    private static void giveKit(MinecraftServer server, String n) {
        run(server, "item replace entity " + n + " hotbar.0 with minecraft:diamond_sword");
        run(server, "item replace entity " + n + " hotbar.1 with minecraft:golden_apple 16");
        run(server, "item replace entity " + n + " hotbar.2 with minecraft:cooked_beef 32");
        run(server, "item replace entity " + n + " armor.head with minecraft:diamond_helmet");
        run(server, "item replace entity " + n + " armor.chest with minecraft:diamond_chestplate");
        run(server, "item replace entity " + n + " armor.legs with minecraft:diamond_leggings");
        run(server, "item replace entity " + n + " armor.feet with minecraft:diamond_boots");
        run(server, "item replace entity " + n + " weapon.offhand with minecraft:shield");
        run(server, "player " + n + " hotbar 1");
    }

    /** Hotbar index (0-8) of the best food: golden apples first, then anything edible. -1 if none. */
    private static int findFoodSlot(ServerPlayer bot) {
        int any = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (stack.isEmpty() || !stack.has(DataComponents.FOOD)) {
                continue;
            }
            if (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
                return i;
            }
            if (any < 0) {
                any = i;
            }
        }
        return any;
    }

    // ---------------------------------------------------------------- fight loop

    private static void tick(MinecraftServer server) {
        if (FIGHTS.isEmpty()) {
            return;
        }
        tickCounter++;

        Iterator<Map.Entry<String, Fight>> it = FIGHTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Fight> e = it.next();
            String botName = e.getKey();
            Fight f = e.getValue();

            ServerPlayer bot = server.getPlayerList().getPlayerByName(botName);
            if (bot == null) {
                it.remove();
                BOTS.remove(botName);
                continue;
            }

            // Bot respawned (new entity): its action state is gone, set it up again.
            if (bot != f.lastBot) {
                f.lastBot = bot;
                f.started = false;
                f.hopping = false;
                f.eating = false;
            }

            ServerPlayer target = server.getPlayerList().getPlayerByName(f.target);
            if (target == null || !target.isAlive() || target.isSpectator() || bot.level() != target.level()) {
                if (f.started) {
                    run(server, "player " + botName + " stop");
                    f.started = false;
                    f.hopping = false;
                    f.eating = false;
                }
                continue;
            }

            if (!f.started) {
                run(server, "player " + botName + " hotbar 1");
                run(server, "player " + botName + " autojump true");
                run(server, "player " + botName + " sprint");
                run(server, "player " + botName + " move forward");
                f.started = true;
            }

            if (tickCounter % 2 == 0) {
                run(server, "player " + botName + " look upon " + f.target + " eyes");
            }

            // Auto-eat: swap to food, eat, then swap back to the sword in hotbar slot 1.
            if (f.eating) {
                int elapsed = tickCounter - f.eatStartTick;
                if ((elapsed >= 5 && !bot.isUsingItem()) || elapsed > 100) {
                    run(server, "player " + botName + " hotbar 1");
                    f.eating = false;
                }
            } else if (eatBelow > 0 && bot.getFoodData().getFoodLevel() <= eatBelow) {
                int slot = findFoodSlot(bot);
                if (slot >= 0) {
                    run(server, "player " + botName + " hotbar " + (slot + 1));
                    run(server, "player " + botName + " use once");
                    f.eating = true;
                    f.eatStartTick = tickCounter;
                }
            }
            if (f.eating) {
                if (f.hopping) {
                    run(server, "player " + botName + " jump");
                    f.hopping = false;
                }
                continue; // no hopping or attacking while eating
            }

            double dist = bot.distanceTo(target);
            boolean inRange = dist <= attackRange;

            // Bunny hop: sprint-jump while closing a gap, stop hopping once within hopStopDistance.
            // It only starts again after the gap grows past hopStopDistance + HOP_RESUME_MARGIN,
            // so a bot in the middle of a combo stays on the ground.
            if (f.hopping) {
                if (!bunnyHop || dist <= hopStopDistance) {
                    run(server, "player " + botName + " jump");
                    f.hopping = false;
                }
            } else if (bunnyHop && dist > hopStopDistance + HOP_RESUME_MARGIN) {
                run(server, "player " + botName + " jump continuous");
                f.hopping = true;
            }

            // Combo: a sprint-hit cancels sprinting (that is what gives the extra knockback),
            // so re-sprint right away and hit again the moment the sword cooldown is full.
            if (!bot.isSprinting()) {
                run(server, "player " + botName + " sprint");
            }
            if (inRange && bot.getAttackStrengthScale(0.5F) >= 1.0F) {
                run(server, "player " + botName + " attack once");
            }
        }
    }
}

