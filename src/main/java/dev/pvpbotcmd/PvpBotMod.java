package dev.pvpbotcmd;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Adds /pvpbot. Everything is done by running HeroBot's own commands
 * (/playerspawn and /player), so this mod has no compile-time dependency on HeroBot.
 *
 * Bot commands:
 *   /pvpbot spawn <name>              spawn a survival bot at your position (no items)
 *   /pvpbot adopt <name>              take control of a bot you spawned with /playerspawn
 *   /pvpbot fight <name> [target]     bot chases and attacks target (default: you)
 *   /pvpbot stop <name>               bot stops fighting (and stops auto-targeting)
 *   /pvpbot ping <name> <ms>          set the bot's simulated ping
 *   /pvpbot remove <name>             remove the bot
 *   /pvpbot clear                     remove all bots made with /pvpbot
 *   /pvpbot list                      list bots
 *   /pvpbot options                   list every setting
 *   /pvpbot mass_spawn <n> [prefix]   spawn many bots (Bot1, Bot2, ...)
 *   /pvpbot status <name>             what a bot is doing right now
 *   /pvpbot help | reload
 *
 * Settings (each is /pvpbot <name> [value]; saved to config/pvpbotcmd.properties): see the OPT constants below.
 *
 * Behaviour of bots made with /pvpbot spawn or /pvpbot adopt:
 *   - wear any armor in their inventory (empty armor slots only)
 *   - hold their best sword (or axe) from the hotbar while fighting
 *   - chase with sprint + bunny hop, stop hopping when close
 *   - after each hit they W-tap or S-tap (tapmode), then go again
 *   - jump reset, shield blocking, shield stun with an axe, hit web (cobweb on the target), stuck detection,
 *     totem/shield in the off-hand
 *   - they randomly jump while close to the target (jumpchance / jumprange)
 *   - sometimes jump for a critical hit when close, and always crit when they happen to be falling (fallcrit)
 *   - at low hearts they run away bunny hopping, eat, then fight again
 *   - revenge: they fight back against whoever hits them
 *   - auto target: idle bots pick the nearest player (bots may pick other bots, see botfight)
 *   - smooth aim (aim), random reaction time before each hit (reactmin/reactmax)
 *   - strafe left/right while close (strafe), and use a water bucket to escape cobwebs (webescape)
 */
public class PvpBotMod implements ModInitializer {

    // ---------------------------------------------------------------- settings

    private static final Map<String, Opt> OPTS = new LinkedHashMap<>();

    private static final class Opt {
        final String cmd;
        final String key;
        final double min;
        final double max;
        final boolean bool;
        final String desc;
        final String[] choices;
        double value;

        Opt(String cmd, String key, double def, double min, double max, boolean bool, String desc, String[] choices) {
            this.cmd = cmd;
            this.key = key;
            this.min = min;
            this.max = max;
            this.bool = bool;
            this.desc = desc;
            this.choices = choices;
            this.value = def;
        }

        boolean on() {
            return value >= 0.5;
        }

        int asInt() {
            return (int) Math.round(value);
        }

        String show() {
            if (choices != null) {
                return choices[(int) Math.round(value)];
            }
            if (bool) {
                return on() ? "on" : "off";
            }
            if (value == Math.rint(value)) {
                return Long.toString(Math.round(value));
            }
            return Double.toString(value);
        }
    }

    private static Opt opt(String cmd, String key, double def, double min, double max, boolean bool, String desc) {
        Opt o = new Opt(cmd, key, def, min, max, bool, desc, null);
        OPTS.put(cmd, o);
        return o;
    }

    private static Opt choiceOpt(String cmd, String key, int def, String desc, String... choices) {
        Opt o = new Opt(cmd, key, def, 0, choices.length - 1, false, desc, choices);
        OPTS.put(cmd, o);
        return o;
    }

    private static final Opt ATTACK_RANGE = opt("range", "attack_range", 3.0, 1.0, 6.0, false,
            "reach in blocks, measured from the bot's eyes to the target's hitbox");
    private static final Opt BUNNY_HOP = opt("bunnyhop", "bunny_hop", 1, 0, 1, true,
            "sprint-jump while chasing");
    private static final Opt HOP_STOP = opt("hopstop", "hop_stop_distance", 3.5, 1.0, 8.0, false,
            "bunny hopping stops this many blocks from the target");
    private static final Opt REVENGE = opt("revenge", "revenge", 1, 0, 1, true,
            "bots fight back against whoever hits them");
    private static final Opt EAT_HEARTS = opt("eat", "eat_below_hearts", 5.0, 0, 10, false,
            "bots run away and eat at this many hearts or fewer (0 = never)");
    private static final Opt EAT_COUNT = opt("eatcount", "eat_count", 3, 1, 10, false,
            "items eaten in a row before the bot fights again (it runs away only once, then eats them all)");
    private static final Opt FLEE_DIST = opt("flee", "flee_distance", 8.0, 3.0, 30.0, false,
            "blocks to run away before eating");
    private static final Opt CRIT_CHANCE = opt("critchance", "crit_chance", 25, 0, 100, false,
            "percent chance to try a critical hit when close");
    private static final Opt CRIT_RANGE = opt("critrange", "crit_range", 4.0, 1.0, 6.0, false,
            "blocks within which bots try critical hits");
    private static final Opt FALL_CRIT = opt("fallcrit", "fall_crit", 1, 0, 1, true,
            "bots that are falling within reach always hit as a critical (ignores critchance/critrange)");
    private static final Opt AUTO_TARGET = opt("autotarget", "auto_target", 1, 0, 1, true,
            "idle bots pick the nearest player as their target");
    private static final Opt FIND_RANGE = opt("findrange", "find_range", 24, 4, 128, false,
            "blocks within which bots look for a target");

    private static final Opt AIM = opt("aim", "aim_ticks", 3, 0, 20, false,
            "ticks the bot takes to turn toward its target (0 = instant)");
    private static final Opt REACT_MIN = opt("reactmin", "reaction_min_ticks", 3, 0, 40, false,
            "shortest wait before the bot swings once a hit is possible");
    private static final Opt REACT_MAX = opt("reactmax", "reaction_max_ticks", 7, 0, 40, false,
            "longest wait before the bot swings once a hit is possible");
    private static final Opt BOT_FIGHT = opt("botfight", "bots_fight_each_other", 1, 0, 1, true,
            "bots can target and take revenge on other bots");
    private static final Opt WEB_ESCAPE = opt("webescape", "web_escape", 1, 0, 1, true,
            "bots stuck in a cobweb use a water bucket from their inventory to get out");
    private static final Opt WEB_BREAK = opt("webbreak", "web_break", 1, 0, 1, true,
            "bots stuck in a cobweb with no water bucket break it with their weapon");
    private static final Opt STRAFE = opt("strafe", "strafe", 1, 0, 1, true,
            "bots strafe left and right while close to their target");
    private static final Opt STRAFE_TICKS = opt("strafeticks", "strafe_ticks", 12, 2, 40, false,
            "ticks between strafe direction changes");

    private static final Opt TAP_MODE = choiceOpt("tapmode", "tap_mode", 1,
            "how the bot taps after each hit: off, wtap (let go of forward), stap (step back), mixed (random)",
            "off", "wtap", "stap", "mixed");
    private static final Opt TAP_TICKS = opt("tapticks", "tap_ticks", 2, 1, 10, false,
            "ticks a W-tap or S-tap lasts");
    private static final Opt JUMP_CHANCE = opt("jumpchance", "jump_chance", 15, 0, 100, false,
            "percent chance, every quarter second, that a bot close to its target jumps");
    private static final Opt JUMP_RANGE = opt("jumprange", "jump_range", 5.0, 1.0, 12.0, false,
            "blocks within which bots randomly jump on their target");

    private static final Opt JUMP_RESET = opt("jumpreset", "jump_reset_chance", 20, 0, 100, false,
            "percent chance to jump right after taking a hit, to shake off the knockback");
    private static final Opt SHIELD_CHANCE = opt("shield", "shield_chance", 45, 0, 100, false,
            "percent chance to raise a shield when the target is about to swing (needs a shield in the inventory)");
    private static final Opt SHIELD_TICKS = opt("shieldticks", "shield_ticks", 20, 2, 100, false,
            "ticks the shield stays up (a shield needs about 5 ticks to start blocking, so keep this well above that)");
    private static final Opt STUN_CHANCE = opt("stun", "shield_stun_chance", 35, 0, 100, false,
            "percent chance to swap to an axe when the target raises a shield, to disable it");
    private static final Opt HIT_WEB = opt("hitweb", "hit_web_chance", 25, 0, 100, false,
            "percent chance that a landed hit is followed by really placing a cobweb at the target's feet (needs cobwebs in the inventory)");
    private static final Opt WEB_LIFETIME = opt("webtime", "web_lifetime_ticks", 100, 0, 1200, false,
            "ticks before a cobweb dropped by a bot disappears again (0 = never)");
    private static final Opt STUCK = opt("stuck", "stuck_detection", 1, 0, 1, true,
            "bots that stop making progress jump and sidestep");
    private static final Opt TOTEM = opt("totem", "totem_offhand", 1, 0, 1, true,
            "bots put a totem of undying (or else a shield) from their inventory into the off-hand");
    private static final Opt MASS_MAX = opt("massmax", "mass_spawn_max", 20, 1, 100, false,
            "most bots /pvpbot mass_spawn may create at once");

    // ---- difficulty presets: /pvpbot difficulty <1-5 or name> sets all of these at once
    private static final String[] LEVEL_NAMES = {"beginner", "easy", "normal", "hard", "insane"};
    private static final List<String> DIFFICULTY_CHOICES =
            List.of("1", "2", "3", "4", "5", "beginner", "easy", "normal", "hard", "insane");
    private static final Opt[] PRESET_OPTS = {
            AIM, REACT_MIN, REACT_MAX, CRIT_CHANCE, STRAFE, STRAFE_TICKS, TAP_MODE, TAP_TICKS,
            BUNNY_HOP, EAT_HEARTS, FALL_CRIT, JUMP_CHANCE, JUMP_RESET, SHIELD_CHANCE, STUN_CHANCE
    };
    //                                           aim rmin rmax crit strafe sticks tap tticks hop eat fcrit jump jreset shield stun
    private static final double[][] PRESETS = {
            /* 1 beginner */ {8, 8, 16, 0, 0, 14, 0, 3, 0, 0, 0, 0, 0, 0, 0},
            /* 2 easy     */ {5, 5, 10, 10, 1, 16, 1, 3, 0, 3, 0, 5, 5, 20, 10},
            /* 3 normal   */ {3, 3, 7, 25, 1, 12, 1, 2, 1, 5, 1, 15, 20, 45, 35},
            /* 4 hard     */ {2, 1, 4, 45, 1, 9, 3, 2, 1, 6, 1, 25, 35, 65, 60},
            /* 5 insane   */ {1, 0, 2, 70, 1, 6, 3, 1, 1, 7, 1, 35, 55, 85, 85},
    };
    private static String difficultyName = "normal";

    private static final double STRAFE_MAX_DIST = 6.0;
    private static final double HOP_RESUME_MARGIN = 1.0;
    private static final int FLEE_MAX_TICKS = 80;
    private static final int EAT_TIMEOUT_TICKS = 60;
    private static final int EAT_COOLDOWN_TICKS = 60;
    private static final int CRIT_TIMEOUT_TICKS = 25;
    private static final int WEB_BREAK_TIMEOUT_TICKS = 80;

    // ---------------------------------------------------------------- state

    private static final Set<String> BOTS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private static final Set<String> STOPPED = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private static final Map<String, Fight> FIGHTS = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private static final Map<String, Pending> PENDING = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private static final Map<String, Integer> LAST_HURT = new HashMap<>();
    private static final ArrayDeque<MassJob> MASS_QUEUE = new ArrayDeque<>();
    private static final ArrayList<PlacedWeb> PLACED_WEBS = new ArrayList<>();
    private static CommandDispatcher<CommandSourceStack> dispatcher;
    private static int globalTick = 0;

    private static final SuggestionProvider<CommandSourceStack> BOT_NAMES =
            (ctx, builder) -> SharedSuggestionProvider.suggest(BOTS, builder);

    /** A bot that was asked to spawn but has not joined yet (HeroBot spawns bots asynchronously). */
    private static final class Pending {
        final String owner;
        final boolean quiet;
        int waited;

        Pending(String owner, boolean quiet) {
            this.owner = owner;
            this.quiet = quiet;
        }
    }

    /** One bot waiting to be spawned by /pvpbot mass_spawn (they are spawned a few ticks apart). */
    private static final class MassJob {
        final String owner;
        final String name;

        MassJob(String owner, String name) {
            this.owner = owner;
            this.name = name;
        }
    }

    /** A cobweb dropped by a bot, removed again when it expires. */
    private static final class PlacedWeb {
        final Level level;
        final BlockPos pos;
        final int expireTick;

        PlacedWeb(Level level, BlockPos pos, int expireTick) {
            this.level = level;
            this.pos = pos;
            this.expireTick = expireTick;
        }
    }

    private enum Phase { FIGHT, FLEE, EAT }

    private static final class Fight {
        String target;
        ServerPlayer lastBot;
        Phase phase = Phase.FIGHT;
        boolean started;
        boolean hopping;
        boolean paused;
        boolean crit;
        int weaponSlot = -1;
        int pauseUntil;
        int attackTick = -1;
        int critStart;
        int phaseStart;
        int useStart;
        int eaten;
        int nextEatTick;
        int eatSlot;
        int eatStackCount;
        Item eatItem;
        int reactAt = -1;
        int sprintHoldUntil;
        int sidestepUntil;
        int lastHitStamp = Integer.MIN_VALUE;
        double lastHp = -1;
        Vec3 lastPos;
        boolean axeMode;
        boolean targetBlocking;
        boolean blocking;
        int blockUntil;
        boolean shieldRolled;
        int placeStage;
        int placeStart;
        int placeCooldown;
        BlockPos placeCell;
        int strafeDir;
        int nextStrafeTick;
        int webStage;
        int webStart;
        int nextWebTick;
        int webSlot;
        BlockPos webPos;

        Fight(String target) {
            this.target = target;
        }

        void reset() {
            phase = Phase.FIGHT;
            started = false;
            hopping = false;
            paused = false;
            crit = false;
            weaponSlot = -1;
            attackTick = -1;
            eaten = 0;
            reactAt = -1;
            strafeDir = 0;
            webStage = 0;
            axeMode = false;
            targetBlocking = false;
            blocking = false;
            shieldRolled = false;
            placeStage = 0;
            lastHp = -1;
            lastPos = null;
        }
    }

    // ---------------------------------------------------------------- init

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
            STOPPED.clear();
            FIGHTS.clear();
            PENDING.clear();
            LAST_HURT.clear();
            MASS_QUEUE.clear();
            PLACED_WEBS.clear();
        });
    }

    // ---------------------------------------------------------------- commands

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("pvpbot");

        root.then(Commands.literal("spawn")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "name")))));

        root.then(Commands.literal("adopt")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> adopt(ctx, StringArgumentType.getString(ctx, "name")))));

        root.then(Commands.literal("fight")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BOT_NAMES)
                        .executes(ctx -> fight(ctx, StringArgumentType.getString(ctx, "name"), null))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(ctx -> fight(ctx,
                                        StringArgumentType.getString(ctx, "name"),
                                        StringArgumentType.getString(ctx, "target"))))));

        root.then(Commands.literal("stop")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BOT_NAMES)
                        .executes(ctx -> stop(ctx, StringArgumentType.getString(ctx, "name")))));

        root.then(Commands.literal("ping")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BOT_NAMES)
                        .then(Commands.argument("ms", IntegerArgumentType.integer(0, 1000))
                                .executes(ctx -> ping(ctx,
                                        StringArgumentType.getString(ctx, "name"),
                                        IntegerArgumentType.getInteger(ctx, "ms"))))));

        root.then(Commands.literal("remove")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BOT_NAMES)
                        .executes(ctx -> remove(ctx, StringArgumentType.getString(ctx, "name")))));

        root.then(Commands.literal("clear").executes(PvpBotMod::clear));
        root.then(Commands.literal("list").executes(PvpBotMod::list));
        root.then(Commands.literal("options").executes(PvpBotMod::listOptions));
        root.then(Commands.literal("help").executes(PvpBotMod::help));
        root.then(Commands.literal("reload").executes(PvpBotMod::reload));

        root.then(Commands.literal("status")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BOT_NAMES)
                        .executes(ctx -> status(ctx, StringArgumentType.getString(ctx, "name")))));

        root.then(Commands.literal("mass_spawn")
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                        .executes(ctx -> massSpawn(ctx, IntegerArgumentType.getInteger(ctx, "count"), "Bot"))
                        .then(Commands.argument("prefix", StringArgumentType.word())
                                .executes(ctx -> massSpawn(ctx,
                                        IntegerArgumentType.getInteger(ctx, "count"),
                                        StringArgumentType.getString(ctx, "prefix"))))));

        root.then(Commands.literal("difficulty")
                .executes(PvpBotMod::showDifficulty)
                .then(Commands.argument("level", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DIFFICULTY_CHOICES, builder))
                        .executes(ctx -> setDifficulty(ctx, StringArgumentType.getString(ctx, "level")))));

        // One command per setting: /pvpbot <name> shows it, /pvpbot <name> <value> changes it.
        for (Opt o : OPTS.values()) {
            LiteralArgumentBuilder<CommandSourceStack> node =
                    Commands.literal(o.cmd).executes(ctx -> showOpt(ctx, o));
            if (o.choices != null) {
                node.then(Commands.argument("value", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Arrays.asList(o.choices), builder))
                        .executes(ctx -> setChoice(ctx, o, StringArgumentType.getString(ctx, "value"))));
            } else if (o.bool) {
                node.then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> setOpt(ctx, o, BoolArgumentType.getBool(ctx, "value") ? 1 : 0)));
            } else {
                node.then(Commands.argument("value", DoubleArgumentType.doubleArg(o.min, o.max))
                        .executes(ctx -> setOpt(ctx, o, DoubleArgumentType.getDouble(ctx, "value"))));
            }
            root.then(node);
        }

        d.register(root);
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
        if (PENDING.containsKey(name)) {
            src.sendFailure(Component.literal(name + " is already being spawned."));
            return 0;
        }

        // Runs with the caller's own permissions and shows HeroBot's own messages, so any error is visible.
        // Plain /playerspawn spawns at the caller's position; the gamemode is switched once the bot is online.
        server.getCommands().performPrefixedCommand(src, "playerspawn " + name);

        // HeroBot spawns bots asynchronously, so the bot may not exist yet. tickPending() finishes the job.
        PENDING.put(name, new Pending(me.getName().getString(), false));
        return 1;
    }

    private static int adopt(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer me = src.getPlayerOrException();
        if (src.getServer().getPlayerList().getPlayerByName(name) == null) {
            src.sendFailure(Component.literal("No player named " + name + " is online."));
            return 0;
        }
        if (name.equalsIgnoreCase(me.getName().getString())) {
            src.sendFailure(Component.literal("You can't adopt yourself."));
            return 0;
        }
        BOTS.add(name);
        STOPPED.remove(name);
        src.sendSuccess(() -> Component.literal("Now controlling " + name + "."), false);
        return 1;
    }

    private static int fight(CommandContext<CommandSourceStack> ctx, String name, String target) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!BOTS.contains(name)) {
            src.sendFailure(Component.literal(name + " is not a PvP bot yet. If you spawned it with /playerspawn, run /pvpbot adopt " + name + " first."));
            return 0;
        }
        String t = target != null ? target : src.getPlayerOrException().getName().getString();
        if (name.equalsIgnoreCase(t)) {
            src.sendFailure(Component.literal("A bot can't fight itself."));
            return 0;
        }
        STOPPED.remove(name);
        Fight existing = FIGHTS.get(name);
        if (existing != null) {
            existing.target = t;
        } else {
            FIGHTS.put(name, new Fight(t));
        }
        src.sendSuccess(() -> Component.literal(name + " is now fighting " + t + "."), false);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack src = ctx.getSource();
        if (!BOTS.contains(name)) {
            src.sendFailure(Component.literal(name + " is not a PvP bot yet. If you spawned it with /playerspawn, run /pvpbot adopt " + name + " first."));
            return 0;
        }
        FIGHTS.remove(name);
        STOPPED.add(name);
        run(src.getServer(), "player " + name + " stop");
        src.sendSuccess(() -> Component.literal(name + " stopped. It won't pick targets again until you use /pvpbot fight " + name + "."), false);
        return 1;
    }

    private static int ping(CommandContext<CommandSourceStack> ctx, String name, int ms) {
        CommandSourceStack src = ctx.getSource();
        if (!BOTS.contains(name)) {
            src.sendFailure(Component.literal(name + " is not a PvP bot yet. If you spawned it with /playerspawn, run /pvpbot adopt " + name + " first."));
            return 0;
        }
        run(src.getServer(), "player " + name + " ping " + ms);
        src.sendSuccess(() -> Component.literal(name + " ping set to " + ms + " ms."), false);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack src = ctx.getSource();
        if (!BOTS.contains(name)) {
            src.sendFailure(Component.literal(name + " is not a PvP bot yet. If you spawned it with /playerspawn, run /pvpbot adopt " + name + " first."));
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
        StringBuilder sb = new StringBuilder();
        for (String name : BOTS) {
            Fight f = FIGHTS.get(name);
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(name);
            if (f != null) {
                sb.append(" -> ").append(f.target);
            }
        }
        String text = BOTS.isEmpty() ? "No PvP bots." : "PvP bots: " + sb;
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return BOTS.size();
    }

    private static int listOptions(CommandContext<CommandSourceStack> ctx) {
        showDifficulty(ctx);
        for (Opt o : OPTS.values()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "/pvpbot " + o.cmd + " = " + o.show() + "  (" + o.desc + ")"), false);
        }
        return OPTS.size();
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        String[] lines = {
                "/pvpbot spawn <name>  |  /pvpbot mass_spawn <count> [prefix]  |  /pvpbot adopt <name>",
                "/pvpbot fight <name> [target]  |  stop <name>  |  remove <name>  |  clear  |  list",
                "/pvpbot status <name>  |  ping <name> <ms>  |  reload  |  options",
                "/pvpbot difficulty <1-5 or beginner/easy/normal/hard/insane>",
                "Every setting is /pvpbot <setting> [value]. Run /pvpbot options to see them all."
        };
        for (String line : lines) {
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        loadConfig();
        ctx.getSource().sendSuccess(() -> Component.literal("Reloaded config/pvpbotcmd.properties."), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer bot = src.getServer().getPlayerList().getPlayerByName(name);
        if (bot == null) {
            src.sendFailure(Component.literal(name + " is not online."));
            return 0;
        }
        Fight f = FIGHTS.get(name);
        StringBuilder sb = new StringBuilder(name);
        sb.append(BOTS.contains(name) ? ": PvP bot" : ": not a PvP bot");
        if (f == null) {
            sb.append(STOPPED.contains(name) ? ", stopped" : ", idle");
        } else {
            sb.append(", ").append(f.phase).append(" -> ").append(f.target);
            if (f.paused) {
                sb.append(", tapping");
            }
            if (f.hopping) {
                sb.append(", hopping");
            }
            if (f.crit) {
                sb.append(", going for a crit");
            }
            if (f.blocking) {
                sb.append(", blocking");
            }
            if (f.axeMode) {
                sb.append(", axe out");
            }
            if (f.webStage != 0) {
                sb.append(", escaping a cobweb");
            }
        }
        sb.append(String.format(Locale.ROOT, ", health %.1f, hunger %d, holding %s",
                bot.getHealth() + bot.getAbsorptionAmount(),
                bot.getFoodData().getFoodLevel(),
                bot.getMainHandItem().getHoverName().getString()));
        String text = sb.toString();
        src.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int massSpawn(CommandContext<CommandSourceStack> ctx, int count, String prefix) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer me = src.getPlayerOrException();
        MinecraftServer server = src.getServer();
        if (!herobotPresent()) {
            src.sendFailure(Component.literal("HeroBot is not installed: /playerspawn and /player were not found."));
            return 0;
        }
        if (prefix.length() > 12) {
            src.sendFailure(Component.literal("The prefix can be at most 12 characters (names are limited to 16)."));
            return 0;
        }
        if (count > MASS_MAX.asInt()) {
            src.sendFailure(Component.literal("At most " + MASS_MAX.asInt() + " bots at once. Change it with /pvpbot massmax <number>."));
            return 0;
        }
        Set<String> used = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (MassJob job : MASS_QUEUE) {
            used.add(job.name);
        }
        String owner = me.getName().getString();
        int queued = 0;
        for (int n = 1; queued < count && n < 1000; n++) {
            String name = prefix + n;
            if (name.length() > 16 || used.contains(name) || PENDING.containsKey(name)
                    || server.getPlayerList().getPlayerByName(name) != null) {
                continue;
            }
            used.add(name);
            MASS_QUEUE.add(new MassJob(owner, name));
            queued++;
        }
        int total = queued;
        src.sendSuccess(() -> Component.literal("Spawning " + total + " bots named " + prefix + "1, " + prefix + "2, ..."), false);
        return queued;
    }

    private static boolean isPresetOpt(Opt o) {
        for (Opt p : PRESET_OPTS) {
            if (p == o) {
                return true;
            }
        }
        return false;
    }

    private static int showDifficulty(CommandContext<CommandSourceStack> ctx) {
        String text = "Difficulty: " + difficultyName;
        for (int i = 0; i < LEVEL_NAMES.length; i++) {
            if (LEVEL_NAMES[i].equals(difficultyName)) {
                text += " (level " + (i + 1) + " of 5)";
            }
        }
        if (difficultyName.equals("custom")) {
            text += " (a setting was changed after choosing a preset)";
        }
        String shown = text;
        ctx.getSource().sendSuccess(() -> Component.literal(shown), false);
        return 1;
    }

    private static int setDifficulty(CommandContext<CommandSourceStack> ctx, String raw) {
        String r = raw.trim().toLowerCase(Locale.ROOT);
        int level = -1;
        for (int i = 0; i < LEVEL_NAMES.length; i++) {
            if (LEVEL_NAMES[i].equals(r) || Integer.toString(i + 1).equals(r)) {
                level = i;
                break;
            }
        }
        if (level < 0) {
            ctx.getSource().sendFailure(Component.literal(
                    "Unknown difficulty. Use 1-5 or beginner, easy, normal, hard, insane."));
            return 0;
        }
        for (int i = 0; i < PRESET_OPTS.length; i++) {
            Opt o = PRESET_OPTS[i];
            o.value = Math.max(o.min, Math.min(o.max, PRESETS[level][i]));
        }
        difficultyName = LEVEL_NAMES[level];
        saveConfig();
        String msg = "Difficulty set to " + LEVEL_NAMES[level] + " (level " + (level + 1) + " of 5).";
        ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
        return 1;
    }

    private static int showOpt(CommandContext<CommandSourceStack> ctx, Opt o) {
        ctx.getSource().sendSuccess(() -> Component.literal(
                o.key + " = " + o.show() + "  (" + o.desc + ")"), false);
        return 1;
    }

    private static int choiceIndex(Opt o, String raw) {
        String r = raw.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < o.choices.length; i++) {
            if (o.choices[i].equals(r) || Integer.toString(i).equals(r)) {
                return i;
            }
        }
        return -1;
    }

    private static int setChoice(CommandContext<CommandSourceStack> ctx, Opt o, String raw) {
        int idx = choiceIndex(o, raw);
        if (idx < 0) {
            ctx.getSource().sendFailure(Component.literal(
                    "Unknown value. Use one of: " + String.join(", ", o.choices)));
            return 0;
        }
        return setOpt(ctx, o, idx);
    }

    private static int setOpt(CommandContext<CommandSourceStack> ctx, Opt o, double v) {
        o.value = Math.max(o.min, Math.min(o.max, v));
        if (isPresetOpt(o)) {
            difficultyName = "custom";
        }
        saveConfig();
        ctx.getSource().sendSuccess(() -> Component.literal(o.key + " set to " + o.show()), true);
        return 1;
    }

    private static void removeBot(MinecraftServer server, String name) {
        FIGHTS.remove(name);
        BOTS.remove(name);
        STOPPED.remove(name);
        LAST_HURT.remove(name);
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
        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(path)) {
            props.load(in);
        } catch (IOException ex) {
            return;
        }
        difficultyName = props.getProperty("difficulty", "custom");
        for (Opt o : OPTS.values()) {
            String raw = props.getProperty(o.key);
            if (raw == null) {
                continue;
            }
            try {
                double v;
                if (o.choices != null) {
                    int idx = choiceIndex(o, raw);
                    if (idx < 0) {
                        continue;
                    }
                    v = idx;
                } else {
                    v = o.bool
                            ? (Boolean.parseBoolean(raw.trim()) ? 1 : 0)
                            : Double.parseDouble(raw.trim());
                }
                o.value = Math.max(o.min, Math.min(o.max, v));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
    }

    private static void saveConfig() {
        Properties props = new Properties();
        props.setProperty("difficulty", difficultyName);
        StringBuilder comment = new StringBuilder("PvPBot Commands config. Change values in game with /pvpbot <setting> <value>.");
        for (Opt o : OPTS.values()) {
            props.setProperty(o.key, o.choices != null ? o.show()
                    : o.bool ? Boolean.toString(o.on()) : Double.toString(o.value));
        }
        try (Writer out = Files.newBufferedWriter(configPath())) {
            props.store(out, comment.toString());
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Runs a command as the server, without chat feedback. */
    private static void run(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    private static boolean badFood(ItemStack stack) {
        return stack.is(Items.ROTTEN_FLESH) || stack.is(Items.SPIDER_EYE) || stack.is(Items.POISONOUS_POTATO)
                || stack.is(Items.PUFFERFISH) || stack.is(Items.CHORUS_FRUIT) || stack.is(Items.CHICKEN);
    }

    /**
     * Inventory index (0-35) of the best food: golden apples first, then anything safe to eat.
     * When the bot's hunger bar is full, only golden apples can be eaten, so only those count.
     */
    private static int findFoodIndex(ServerPlayer bot) {
        boolean goldenOnly = bot.getFoodData().getFoodLevel() >= 20;
        Inventory inv = bot.getInventory();
        int any = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !stack.has(DataComponents.FOOD) || badFood(stack)) {
                continue;
            }
            if (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
                return i;
            }
            if (!goldenOnly && any < 0) {
                any = i;
            }
        }
        return any;
    }

    private static int weaponScore(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        if (stack.is(Items.NETHERITE_SWORD)) {
            return 7;
        }
        if (stack.is(Items.DIAMOND_SWORD)) {
            return 6;
        }
        if (stack.is(Items.IRON_SWORD)) {
            return 5;
        }
        if (stack.is(Items.STONE_SWORD)) {
            return 4;
        }
        if (stack.is(ItemTags.SWORDS)) {
            return 3;
        }
        if (stack.is(ItemTags.AXES)) {
            return 2;
        }
        return 0;
    }

    /** Hotbar index (0-8) of the best weapon (best sword, else an axe). -1 if none. */
    private static int findWeaponSlot(ServerPlayer bot) {
        Inventory inv = bot.getInventory();
        int best = -1;
        int bestScore = 0;
        for (int i = 0; i < 9; i++) {
            int score = weaponScore(inv.getItem(i));
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** Makes sure the item at inventory index idx is in the hotbar, and returns its hotbar index (-1 if impossible). */
    private static int toHotbar(ServerPlayer bot, int idx, int avoidSlot) {
        if (idx < 9) {
            return idx;
        }
        Inventory inv = bot.getInventory();
        int target = -1;
        for (int i = 0; i < 9; i++) {
            if (i != avoidSlot && inv.getItem(i).isEmpty()) {
                target = i;
                break;
            }
        }
        if (target < 0) {
            for (int i = 0; i < 9; i++) {
                if (i != avoidSlot) {
                    target = i;
                    break;
                }
            }
        }
        if (target < 0) {
            return -1;
        }
        ItemStack moving = inv.getItem(idx);
        ItemStack displaced = inv.getItem(target);
        inv.setItem(idx, displaced);
        inv.setItem(target, moving);
        return target;
    }

    private static EquipmentSlot armorSlotFor(ItemStack stack) {
        if (stack.is(ItemTags.HEAD_ARMOR)) {
            return EquipmentSlot.HEAD;
        }
        if (stack.is(ItemTags.CHEST_ARMOR)) {
            return EquipmentSlot.CHEST;
        }
        if (stack.is(ItemTags.LEG_ARMOR)) {
            return EquipmentSlot.LEGS;
        }
        if (stack.is(ItemTags.FOOT_ARMOR)) {
            return EquipmentSlot.FEET;
        }
        return null;
    }

    /** Puts armor from the bot's inventory into any empty armor slot. */
    private static void equipArmor(ServerPlayer bot) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            EquipmentSlot slot = armorSlotFor(stack);
            if (slot == null || !bot.getItemBySlot(slot).isEmpty()) {
                continue;
            }
            bot.setItemSlot(slot, stack.copy());
            inv.setItem(i, ItemStack.EMPTY);
        }

        // Off-hand: a totem of undying first (also refills after a totem pops), otherwise a shield.
        if (bot.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()) {
            int idx = TOTEM.on() ? findItemIndex(bot, Items.TOTEM_OF_UNDYING) : -1;
            if (idx < 0) {
                idx = findItemIndex(bot, Items.SHIELD);
            }
            if (idx >= 0) {
                bot.setItemSlot(EquipmentSlot.OFFHAND, inv.getItem(idx).copy());
                inv.setItem(idx, ItemStack.EMPTY);
            }
        }
    }

    /** Hotbar index (0-8) of an axe, or -1. */
    private static int findAxeSlot(ServerPlayer bot) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(ItemTags.AXES)) {
                return i;
            }
        }
        return -1;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /**
     * Hit web, step 1: after one of our hits lands, sometimes take out a cobweb (real placement, in a few ticks):
     * swap to it, aim at the floor at the target's feet, right-click, swap back.
     */
    private static void startWebPlace(MinecraftServer server, String name, Fight f, ServerPlayer bot, ServerPlayer target) {
        if (f.placeStage != 0 || globalTick < f.placeCooldown || HIT_WEB.value <= 0
                || ThreadLocalRandom.current().nextDouble() * 100.0 >= HIT_WEB.value) {
            return;
        }
        int idx = findItemIndex(bot, Items.COBWEB);
        if (idx < 0 || webAimPoint(bot, target, f) == null) {
            return;
        }
        int slot = toHotbar(bot, idx, findWeaponSlot(bot));
        if (slot < 0) {
            return;
        }
        run(server, "player " + name + " move"); // hold still while placing
        run(server, "player " + name + " hotbar " + (slot + 1));
        f.strafeDir = 0;
        f.placeStage = 1;
        f.placeStart = globalTick;
    }

    /** A point on the floor just in front of the target's feet (the near side of the block it stands in). */
    private static Vec3 webAimPoint(ServerPlayer bot, ServerPlayer target, Fight f) {
        if (!target.onGround()) {
            return null;
        }
        Level level = target.level();
        int floorY = target.blockPosition().getY();
        double dx = bot.getX() - target.getX();
        double dz = bot.getZ() - target.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.05) {
            return null;
        }
        double px = target.getX() + dx / len * 0.45;
        double pz = target.getZ() + dz / len * 0.45;
        BlockPos cell = BlockPos.containing(px, floorY, pz);
        if (!level.getBlockState(cell).isAir() || level.getBlockState(cell.below()).isAir()) {
            return null;
        }
        f.placeCell = cell;
        return new Vec3(px, floorY, pz);
    }

    /** Hit web, steps 2-4. Returns true while busy (the rest of the fight logic waits). */
    private static boolean webPlaceStep(MinecraftServer server, String name, Fight f, ServerPlayer bot, ServerPlayer target) {
        if (f.placeStage == 0) {
            return false;
        }
        int elapsed = globalTick - f.placeStart;
        if (f.placeStage == 1) {
            if (elapsed >= 1) {
                Vec3 aim = webAimPoint(bot, target, f); // aim again: the target moved after the hit
                if (aim == null) {
                    endWebPlace(server, name, f);
                    return false;
                }
                run(server, "player " + name + " look at " + fmt(aim.x) + " " + fmt(aim.y) + " " + fmt(aim.z));
                f.placeStage = 2;
            }
        } else if (f.placeStage == 2) {
            if (elapsed >= 2) {
                run(server, "player " + name + " use once"); // place the cobweb
                f.placeStage = 3;
            }
        } else if (elapsed >= 4) {
            if (f.placeCell != null && bot.level().getBlockState(f.placeCell).is(Blocks.COBWEB)
                    && WEB_LIFETIME.asInt() > 0) {
                PLACED_WEBS.add(new PlacedWeb(bot.level(), f.placeCell, globalTick + WEB_LIFETIME.asInt()));
            }
            endWebPlace(server, name, f);
            return false;
        }
        return true;
    }

    private static void endWebPlace(MinecraftServer server, String name, Fight f) {
        f.placeStage = 0;
        f.placeCooldown = globalTick + 30;
        f.weaponSlot = -1; // back to the sword
        run(server, "player " + name + " move forward");
        run(server, "player " + name + " sprint");
    }

    private static String lookCmd(String name, String target) {
        int t = AIM.asInt();
        return "player " + name + " look upon " + target + " eyes" + (t > 0 ? " delta " + t : "");
    }

    /** Distance from the bot's eyes to the nearest point of the target's hitbox (what melee reach is measured by). */
    private static double reachDistance(ServerPlayer bot, ServerPlayer target) {
        AABB box = target.getBoundingBox();
        double ex = bot.getX();
        double ey = bot.getEyeY();
        double ez = bot.getZ();
        double dx = Math.max(Math.max(box.minX - ex, 0.0), ex - box.maxX);
        double dy = Math.max(Math.max(box.minY - ey, 0.0), ey - box.maxY);
        double dz = Math.max(Math.max(box.minZ - ez, 0.0), ez - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** A cobweb touching any part of the bot's hitbox (this also catches webs the bot is only standing at the edge of). */
    private static BlockPos findCobweb(ServerPlayer bot) {
        AABB box = bot.getBoundingBox();
        BlockPos min = BlockPos.containing(box.minX + 1.0E-7, box.minY + 1.0E-7, box.minZ + 1.0E-7);
        BlockPos max = BlockPos.containing(box.maxX - 1.0E-7, box.maxY - 1.0E-7, box.maxZ - 1.0E-7);
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            if (bot.level().getBlockState(p).is(Blocks.COBWEB)) {
                return p.immutable();
            }
        }
        return null;
    }

    private static boolean inCobweb(ServerPlayer bot) {
        return findCobweb(bot) != null;
    }

    private static int findItemIndex(ServerPlayer bot, Item item) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Cobweb escape, checked every tick in every phase so a bot reacts the moment a web touches it.
     * With a water bucket: aim at the web, pour so the water breaks it, scoop the water straight back up
     * (and clean up by hand if the scoop missed). Without one: mine the web with the best weapon.
     * Returns true while it is busy (the rest of the fight logic waits).
     */
    private static boolean webEscape(MinecraftServer server, String name, Fight f, ServerPlayer bot) {
        if (f.webStage == 0) {
            if ((!WEB_ESCAPE.on() && !WEB_BREAK.on()) || globalTick < f.nextWebTick) {
                return false;
            }
            BlockPos web = findCobweb(bot);
            if (web == null) {
                return false;
            }
            int idx = WEB_ESCAPE.on() ? findItemIndex(bot, Items.WATER_BUCKET) : -1;
            int slot = idx < 0 ? -1 : toHotbar(bot, idx, findWeaponSlot(bot));
            if (slot < 0 && !WEB_BREAK.on()) {
                f.nextWebTick = globalTick + 100; // no water bucket: don't check every tick
                return false;
            }
            String aim = "player " + name + " look at " + fmt(web.getX() + 0.5) + " "
                    + fmt(web.getY() + 0.5) + " " + fmt(web.getZ() + 0.5);
            run(server, "player " + name + " stop");
            if (slot >= 0) {
                run(server, "player " + name + " hotbar " + (slot + 1));
                run(server, aim);
                f.webSlot = slot;
                f.webStage = 1;
            } else {
                int w = findWeaponSlot(bot);
                if (w >= 0) {
                    run(server, "player " + name + " hotbar " + (w + 1));
                }
                run(server, aim);
                run(server, "player " + name + " attack continuous"); // mine the web
                f.webStage = 10;
            }
            f.webPos = web;
            f.webStart = globalTick;
            // Whatever the bot was doing (fighting, running away, eating), it is stuck: drop it and get out first.
            f.phase = Phase.FIGHT;
            f.eaten = 0;
            f.nextEatTick = Math.max(f.nextEatTick, globalTick + 40);
            f.started = false;
            f.hopping = false;
            f.paused = false;
            f.crit = false;
            f.blocking = false;
            f.axeMode = false;
            f.placeStage = 0;
            f.strafeDir = 0;
            f.attackTick = -1;
            return true;
        }

        if (f.webStage == 1) {
            if (globalTick - f.webStart >= 1) {
                run(server, "player " + name + " use once"); // pour the water
                f.webStage = 2;
                f.webStart = globalTick;
            }
        } else if (f.webStage == 2) {
            int elapsed = globalTick - f.webStart;
            // Scoop as soon as the web is gone (or after 14 ticks at the latest).
            if ((elapsed >= 2 && !inCobweb(bot)) || elapsed >= 14) {
                run(server, "player " + name + " use once");
                f.webStage = 3;
                f.webStart = globalTick;
            }
        } else if (f.webStage == 3) {
            if (globalTick - f.webStart >= 3) {
                cleanupWater(bot, f);
                finishWeb(f, 4);
            }
        } else if (f.webStage == 10) {
            boolean timedOut = globalTick - f.webStart > WEB_BREAK_TIMEOUT_TICKS;
            if (!inCobweb(bot) || timedOut) {
                run(server, "player " + name + " stop"); // stops mining
                finishWeb(f, timedOut ? 60 : 4);
            }
        }
        return true;
    }

    private static void finishWeb(Fight f, int cooldownTicks) {
        f.webStage = 0;
        f.nextWebTick = globalTick + cooldownTicks;
        f.started = false;
        f.weaponSlot = -1; // back to the sword
    }

    /** If the scoop missed, remove any water source we poured near the web and put the water back in the bucket. */
    private static void cleanupWater(ServerPlayer bot, Fight f) {
        if (f.webPos == null || f.webSlot < 0 || f.webSlot >= 36) {
            return;
        }
        Inventory inv = bot.getInventory();
        if (!inv.getItem(f.webSlot).is(Items.BUCKET)) {
            return; // the bucket is full again: the scoop worked (or nothing was poured)
        }
        boolean removed = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos p = f.webPos.offset(dx, dy, dz);
                    FluidState fluid = bot.level().getFluidState(p);
                    if (fluid.is(FluidTags.WATER) && fluid.isSource()) {
                        bot.level().setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                        removed = true;
                    }
                }
            }
        }
        if (removed) {
            inv.setItem(f.webSlot, new ItemStack(Items.WATER_BUCKET));
        }
    }

    /** Can this player still be fought (online, alive, not a spectator, same dimension)? */
    private static boolean reachable(ServerPlayer bot, ServerPlayer t) {
        return t != null && t != bot && t.isAlive() && !t.isSpectator() && bot.level() == t.level();
    }

    // ---------------------------------------------------------------- per-tick jobs

    private static void tick(MinecraftServer server) {
        globalTick++;
        if (!MASS_QUEUE.isEmpty() && globalTick % 4 == 0) {
            tickMass(server);
        }
        if (!PLACED_WEBS.isEmpty() && globalTick % 10 == 0) {
            tickWebs();
        }
        if (!PENDING.isEmpty()) {
            tickPending(server);
        }
        if (BOTS.isEmpty()) {
            return;
        }
        if (globalTick % 10 == 0) {
            tickArmor(server);
        }
        if (REVENGE.on() && globalTick % 2 == 0) {
            tickRevenge(server);
        }
        if (AUTO_TARGET.on() && globalTick % 20 == 0) {
            tickAutoTarget(server);
        }
        if (!FIGHTS.isEmpty()) {
            tickFights(server);
        }
    }

    private static void tickMass(MinecraftServer server) {
        MassJob job = MASS_QUEUE.poll();
        if (job == null) {
            return;
        }
        ServerPlayer owner = server.getPlayerList().getPlayerByName(job.owner);
        if (owner == null) {
            MASS_QUEUE.clear();
            return;
        }
        if (server.getPlayerList().getPlayerByName(job.name) != null || PENDING.containsKey(job.name)) {
            return;
        }
        server.getCommands().performPrefixedCommand(
                owner.createCommandSourceStack().withSuppressedOutput(), "playerspawn " + job.name);
        PENDING.put(job.name, new Pending(job.owner, true));
        if (MASS_QUEUE.isEmpty()) {
            owner.sendSystemMessage(Component.literal("Mass spawn: all bots requested. They join over the next moments."));
        }
    }

    /** Removes cobwebs that bots dropped once they expire. */
    private static void tickWebs() {
        Iterator<PlacedWeb> it = PLACED_WEBS.iterator();
        while (it.hasNext()) {
            PlacedWeb w = it.next();
            if (globalTick >= w.expireTick) {
                if (w.level.getBlockState(w.pos).is(Blocks.COBWEB)) {
                    w.level.setBlock(w.pos, Blocks.AIR.defaultBlockState(), 3);
                }
                it.remove();
            }
        }
    }

    private static void tickPending(MinecraftServer server) {
        Iterator<Map.Entry<String, Pending>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Pending> e = it.next();
            String name = e.getKey();
            Pending p = e.getValue();
            ServerPlayer owner = server.getPlayerList().getPlayerByName(p.owner);

            if (server.getPlayerList().getPlayerByName(name) != null) {
                it.remove();
                BOTS.add(name);
                STOPPED.remove(name);
                run(server, "gamemode survival " + name);
                if (owner != null && !p.quiet) {
                    owner.sendSystemMessage(Component.literal(
                            "Spawned " + name + ". Use /pvpbot fight " + name + " to start"
                                    + (AUTO_TARGET.on() ? " (or wait, it will pick a target)." : ".")));
                }
            } else if (++p.waited > 200) {
                it.remove();
                if (owner != null) {
                    owner.sendSystemMessage(Component.literal(
                            "Could not spawn " + name + " (timed out). Check HeroBot's message above."));
                }
            }
        }
    }

    private static void tickArmor(MinecraftServer server) {
        for (String name : BOTS) {
            ServerPlayer bot = server.getPlayerList().getPlayerByName(name);
            if (bot != null) {
                equipArmor(bot);
            }
        }
    }

    /** Revenge: whoever hits a bot becomes its target. */
    private static void tickRevenge(MinecraftServer server) {
        for (String name : BOTS) {
            ServerPlayer bot = server.getPlayerList().getPlayerByName(name);
            if (bot == null) {
                continue;
            }
            LivingEntity hurtBy = bot.getLastHurtByMob();
            if (!(hurtBy instanceof ServerPlayer)) {
                continue;
            }
            ServerPlayer attacker = (ServerPlayer) hurtBy;
            if (attacker == bot) {
                continue;
            }
            int stamp = bot.getLastHurtByMobTimestamp();
            Integer last = LAST_HURT.get(name);
            if (last != null && last == stamp) {
                continue;
            }
            LAST_HURT.put(name, stamp);

            String attackerName = attacker.getName().getString();
            if (BOTS.contains(attackerName) && !BOT_FIGHT.on()) {
                continue;
            }
            STOPPED.remove(name);
            Fight f = FIGHTS.get(name);
            if (f == null) {
                FIGHTS.put(name, new Fight(attackerName));
            } else {
                f.target = attackerName;
            }
        }
    }

    /** Auto target: idle bots (or bots whose target left) pick the nearest survival player. */
    private static void tickAutoTarget(MinecraftServer server) {
        for (String name : BOTS) {
            if (STOPPED.contains(name)) {
                continue;
            }
            ServerPlayer bot = server.getPlayerList().getPlayerByName(name);
            if (bot == null) {
                continue;
            }
            Fight f = FIGHTS.get(name);
            if (f != null && reachable(bot, server.getPlayerList().getPlayerByName(f.target))) {
                continue;
            }

            ServerPlayer best = null;
            double bestDist = FIND_RANGE.value;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p == bot || (!BOT_FIGHT.on() && BOTS.contains(p.getName().getString()))
                        || p.isCreative() || !reachable(bot, p)) {
                    continue;
                }
                double d = bot.distanceTo(p);
                if (d <= bestDist) {
                    bestDist = d;
                    best = p;
                }
            }
            if (best != null) {
                String targetName = best.getName().getString();
                if (f == null) {
                    FIGHTS.put(name, new Fight(targetName));
                } else {
                    f.target = targetName;
                }
            }
        }
    }

    // ---------------------------------------------------------------- fight loop

    private static void tickFights(MinecraftServer server) {
        Iterator<Map.Entry<String, Fight>> it = FIGHTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Fight> e = it.next();
            String name = e.getKey();
            Fight f = e.getValue();

            ServerPlayer bot = server.getPlayerList().getPlayerByName(name);
            if (bot == null) {
                it.remove();
                BOTS.remove(name);
                continue;
            }

            // Bot respawned (new entity): its action state is gone, start over.
            if (bot != f.lastBot) {
                f.lastBot = bot;
                f.reset();
            }

            ServerPlayer target = server.getPlayerList().getPlayerByName(f.target);
            if (!reachable(bot, target)) {
                if (f.started || f.hopping || f.phase != Phase.FIGHT) {
                    run(server, "player " + name + " stop");
                    f.reset();
                }
                continue;
            }

            // Stuck in a cobweb? Get out first, whatever the bot was doing.
            if (webEscape(server, name, f, bot)) {
                continue;
            }

            double dist = bot.distanceTo(target);
            switch (f.phase) {
                case FIGHT -> fightPhase(server, name, f, bot, target, dist);
                case FLEE -> fleePhase(server, name, f, bot, target, dist);
                case EAT -> eatPhase(server, name, f, bot, target, dist);
            }
        }
    }

    private static void fightPhase(MinecraftServer server, String name, Fight f,
                                   ServerPlayer bot, ServerPlayer target, double dist) {
        // Jump reset: right after taking a hit, sometimes jump to shake off the knockback.
        double hpNow = bot.getHealth() + bot.getAbsorptionAmount();
        if (f.lastHp >= 0 && hpNow < f.lastHp - 0.01 && JUMP_RESET.value > 0 && bot.onGround()
                && ThreadLocalRandom.current().nextDouble() * 100.0 < JUMP_RESET.value) {
            run(server, "player " + name + " jump once");
        }
        f.lastHp = hpNow;

        // Low health: run away bunny hopping and eat.
        double hp = bot.getHealth() + bot.getAbsorptionAmount();
        if (EAT_HEARTS.value > 0 && hp <= EAT_HEARTS.value * 2.0 && globalTick >= f.nextEatTick
                && findFoodIndex(bot) >= 0) {
            startFlee(server, name, f);
            return;
        }

        // Placing a cobweb on the target (started after a landed hit): the rest waits.
        if (webPlaceStep(server, name, f, bot, target)) {
            return;
        }

        if (globalTick % 2 == 0) {
            run(server, lookCmd(name, f.target));
        }

        if (!f.started && !f.paused) {
            run(server, "player " + name + " autojump true");
            run(server, "player " + name + " sprint");
            run(server, "player " + name + " move forward");
            f.started = true;
        }

        // Shield stun: when the target raises a shield, sometimes swap to an axe (axes disable shields).
        boolean targetBlocks = target.isBlocking();
        if (targetBlocks && !f.targetBlocking) {
            f.targetBlocking = true;
            if (STUN_CHANCE.value > 0 && findAxeSlot(bot) >= 0
                    && ThreadLocalRandom.current().nextDouble() * 100.0 < STUN_CHANCE.value) {
                f.axeMode = true;
                f.weaponSlot = -1;
            }
        } else if (!targetBlocks && f.targetBlocking) {
            f.targetBlocking = false;
            if (f.axeMode) {
                f.axeMode = false;
                f.weaponSlot = -1; // shield is down (or broken): back to the sword
            }
        }

        // Hold the best weapon (re-checked every half second, or right after eating).
        if (f.weaponSlot < 0 || globalTick % 10 == 0) {
            int w = f.axeMode ? findAxeSlot(bot) : findWeaponSlot(bot);
            if (w < 0) {
                w = findWeaponSlot(bot);
            }
            if (w >= 0 && w != f.weaponSlot) {
                run(server, "player " + name + " hotbar " + (w + 1));
                f.weaponSlot = w;
            }
        }

        // W-tap / S-tap: two ticks after a swing (so the sprint hit has landed), let go of forward (W-tap)
        // or press backward (S-tap) for a moment, then go forward and sprint again for the next hit.
        if (f.attackTick >= 0 && globalTick - f.attackTick >= 2) {
            f.attackTick = -1;
            int mode = TAP_MODE.asInt(); // 0 off, 1 wtap, 2 stap, 3 mixed
            if (mode == 3) {
                mode = ThreadLocalRandom.current().nextBoolean() ? 1 : 2;
            }
            if (mode > 0) {
                if (f.hopping) {
                    run(server, "player " + name + " jump"); // stops continuous jumping
                    f.hopping = false;
                }
                if (mode == 1) {
                    run(server, "player " + name + " move"); // W-tap: let go of every movement key
                    f.strafeDir = 0;
                } else {
                    run(server, "player " + name + " move backward"); // S-tap: step back
                }
                f.paused = true;
                f.pauseUntil = globalTick + Math.max(1, TAP_TICKS.asInt());
            }
        }
        if (f.paused) {
            if (globalTick >= f.pauseUntil) {
                run(server, "player " + name + " move forward");
                run(server, "player " + name + " sprint");
                f.paused = false;
                f.nextStrafeTick = globalTick; // pick a strafe side again right away
            }
            return;
        }

        // Bunny hop while closing a gap; stop hopping when close.
        if (f.hopping) {
            if (!BUNNY_HOP.on() || dist <= HOP_STOP.value) {
                run(server, "player " + name + " jump");
                f.hopping = false;
            }
        } else if (BUNNY_HOP.on() && !f.crit && dist > HOP_STOP.value + HOP_RESUME_MARGIN) {
            run(server, "player " + name + " jump continuous");
            f.hopping = true;
        }

        // Strafe: switch sides every few ticks while close. "move forward" is re-sent so the bot keeps closing in.
        if (STRAFE.on() && !f.hopping && !f.crit && dist <= STRAFE_MAX_DIST) {
            if (globalTick >= f.nextStrafeTick) {
                f.strafeDir = f.strafeDir == 0
                        ? (ThreadLocalRandom.current().nextBoolean() ? 1 : -1)
                        : -f.strafeDir;
                run(server, "player " + name + " move " + (f.strafeDir > 0 ? "left" : "right"));
                run(server, "player " + name + " move forward");
                int base = Math.max(2, STRAFE_TICKS.asInt());
                f.nextStrafeTick = globalTick + base + ThreadLocalRandom.current().nextInt(base / 2 + 1);
            }
        } else if (f.strafeDir != 0 && globalTick >= f.sidestepUntil) {
            run(server, "player " + name + " move");
            run(server, "player " + name + " move forward");
            f.strafeDir = 0;
        }

        // Stuck detection: if the bot barely moved for a second while it should be closing in, hop and sidestep.
        if (STUCK.on() && globalTick % 20 == 0) {
            Vec3 here = bot.position();
            if (f.lastPos != null && f.lastPos.distanceTo(here) < 0.2 && !f.blocking
                    && dist > ATTACK_RANGE.value + 1.0) {
                run(server, "player " + name + " jump once");
                f.strafeDir = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
                run(server, "player " + name + " move " + (f.strafeDir > 0 ? "left" : "right"));
                run(server, "player " + name + " move forward");
                f.sidestepUntil = globalTick + 10;
                f.nextStrafeTick = globalTick + 12;
            }
            f.lastPos = here;
        }

        // Random jumps while close: less predictable, and a jump that ends in a fall crit if fallcrit is on.
        if (JUMP_CHANCE.value > 0 && !f.hopping && !f.crit && dist <= JUMP_RANGE.value
                && globalTick % 5 == 0 && bot.onGround()
                && ThreadLocalRandom.current().nextDouble() * 100.0 < JUMP_CHANCE.value) {
            run(server, "player " + name + " jump once");
        }

        // A sprint-hit cancels sprinting (that is what gives the extra knockback), so sprint again.
        if (!f.crit && globalTick >= f.sprintHoldUntil && !bot.isSprinting()) {
            run(server, "player " + name + " sprint");
        }

        double reach = reachDistance(bot, target);
        boolean ready = bot.getAttackStrengthScale(0.5F) >= 1.0F;

        // Hit web: when one of our hits lands, sometimes drop a cobweb on the target.
        LivingEntity lastHitter = target.getLastHurtByMob();
        int hitStamp = target.getLastHurtByMobTimestamp();
        if (lastHitter == bot && hitStamp != f.lastHitStamp) {
            f.lastHitStamp = hitStamp;
            startWebPlace(server, name, f, bot, target);
        }

        // Shield: while our own swing is recharging and the target is about to swing, sometimes block.
        boolean targetReady = target.getAttackStrengthScale(0.5F) >= 0.9F;
        if (!targetReady) {
            f.shieldRolled = false;
        }
        if (f.blocking) {
            if (globalTick >= f.blockUntil) {
                run(server, "player " + name + " use"); // lower the shield
                f.blocking = false;
            } else {
                return; // stay behind the shield: no attacking
            }
        } else if (SHIELD_CHANCE.value > 0 && targetReady && !f.shieldRolled && !ready && dist <= 6.0
                && bot.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD)) {
            f.shieldRolled = true;
            if (ThreadLocalRandom.current().nextDouble() * 100.0 < SHIELD_CHANCE.value) {
                run(server, "player " + name + " use continuous"); // hold right-click: shield up
                f.blocking = true;
                f.blockUntil = globalTick + Math.max(2, SHIELD_TICKS.asInt());
                return;
            }
        }

        // Stun strike: axe in hand and the target is blocking. Hit at once (the charge doesn't matter for
        // knocking a shield down), then go back to the sword on the very next tick.
        if (f.axeMode && bot.getMainHandItem().is(ItemTags.AXES)) {
            if (reach <= ATTACK_RANGE.value) {
                run(server, "player " + name + " attack once");
                f.attackTick = globalTick;
                f.axeMode = false;
                f.weaponSlot = -1;
                f.reactAt = -1;
            }
            return;
        }

        // Critical hit in progress: hit on the way down.
        if (f.crit) {
            boolean falling = !bot.onGround() && bot.getDeltaMovement().y < 0.0;
            if (falling && reach <= ATTACK_RANGE.value && ready) {
                run(server, "player " + name + " attack once");
                f.attackTick = globalTick;
                f.crit = false;
                f.sprintHoldUntil = globalTick + 3; // don't sprint again before the swing lands, or it won't crit
            } else if (globalTick - f.critStart > CRIT_TIMEOUT_TICKS) {
                f.crit = false;
                run(server, "player " + name + " sprint");
            }
            return;
        }

        // Falling crit: whenever the bot is on its way down within reach with a full cooldown, hit right away.
        // Sprinting hits can never be critical, so sprint is switched off first. No reaction delay: the window is short.
        if (FALL_CRIT.on() && ready && reach <= ATTACK_RANGE.value
                && !bot.onGround() && bot.getDeltaMovement().y < 0.0) {
            run(server, "player " + name + " unsprint");
            run(server, "player " + name + " attack once");
            f.attackTick = globalTick;
            f.reactAt = -1;
            f.sprintHoldUntil = globalTick + 3;
            return;
        }

        boolean critPossible = CRIT_CHANCE.value > 0 && dist <= CRIT_RANGE.value;
        if (ready && (reach <= ATTACK_RANGE.value || critPossible)) {
            // Reaction time: wait a random number of ticks between the minimum and maximum before acting.
            if (f.reactAt < 0) {
                int lo = Math.min(REACT_MIN.asInt(), REACT_MAX.asInt());
                int hi = Math.max(REACT_MIN.asInt(), REACT_MAX.asInt());
                f.reactAt = globalTick + lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
            }
            if (globalTick >= f.reactAt) {
                f.reactAt = -1;
                boolean tryCrit = critPossible && bot.onGround() && !f.hopping
                        && ThreadLocalRandom.current().nextDouble() * 100.0 < CRIT_CHANCE.value;
                if (tryCrit) {
                    // Crits need a falling, non-sprinting hit: stop sprinting and jump.
                    run(server, "player " + name + " unsprint");
                    run(server, "player " + name + " jump once");
                    f.crit = true;
                    f.critStart = globalTick;
                } else if (reach <= ATTACK_RANGE.value) {
                    run(server, "player " + name + " attack once");
                    f.attackTick = globalTick;
                }
            }
        } else {
            f.reactAt = -1;
        }
    }

    private static void startFlee(MinecraftServer server, String name, Fight f) {
        run(server, "player " + name + " stop");
        run(server, "player " + name + " autojump true");
        run(server, "player " + name + " sprint");
        run(server, "player " + name + " move forward");
        run(server, "player " + name + " jump continuous");
        f.phase = Phase.FLEE;
        f.blocking = false;
        f.axeMode = false;
        f.placeStage = 0;
        f.phaseStart = globalTick;
        f.strafeDir = 0;
        f.started = false;
        f.hopping = true;
        f.paused = false;
        f.crit = false;
        f.attackTick = -1;
    }

    private static void fleePhase(MinecraftServer server, String name, Fight f,
                                  ServerPlayer bot, ServerPlayer target, double dist) {
        if (globalTick % 2 == 0) {
            // Face directly away from the target.
            double dx = bot.getX() - target.getX();
            double dz = bot.getZ() - target.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            run(server, "player " + name + " look " + String.format(Locale.ROOT, "%.1f", yaw) + " 0");
        }
        if (!bot.isSprinting()) {
            run(server, "player " + name + " sprint");
        }
        if (dist >= FLEE_DIST.value || globalTick - f.phaseStart > FLEE_MAX_TICKS) {
            startEat(server, name, f, bot);
        }
    }

    private static void startEat(MinecraftServer server, String name, Fight f, ServerPlayer bot) {
        run(server, "player " + name + " stop");
        f.hopping = false;
        int idx = findFoodIndex(bot);
        int slot = idx < 0 ? -1 : toHotbar(bot, idx, findWeaponSlot(bot));
        if (slot < 0) {
            endRetreat(server, name, f);
            return;
        }
        ItemStack food = bot.getInventory().getItem(slot);
        f.eatSlot = slot;
        f.eatItem = food.getItem();
        f.eatStackCount = food.getCount();

        run(server, "player " + name + " hotbar " + (slot + 1));
        // Look straight up so the right-click can't hit a door, chest or button instead of the food.
        run(server, "player " + name + " look " + String.format(Locale.ROOT, "%.1f", bot.getYRot()) + " -90");
        // Eating only continues while "use" is held, so it must be continuous ("use once" cancels after one tick).
        run(server, "player " + name + " use continuous");
        // Keep drifting away while eating: forward (the way it ran, since it looks along that yaw) and hopping.
        run(server, "player " + name + " move forward");
        run(server, "player " + name + " jump continuous");
        f.phase = Phase.EAT;
        f.useStart = globalTick;
    }

    private static void eatPhase(MinecraftServer server, String name, Fight f,
                                 ServerPlayer bot, ServerPlayer target, double dist) {
        int elapsed = globalTick - f.useStart;
        ItemStack now = bot.getInventory().getItem(f.eatSlot);
        boolean consumed = now.isEmpty() || !now.is(f.eatItem) || now.getCount() < f.eatStackCount;
        boolean gaveUp = elapsed > EAT_TIMEOUT_TICKS || (elapsed >= 5 && !bot.isUsingItem());
        if (!consumed && !gaveUp) {
            return; // still eating
        }

        run(server, "player " + name + " use"); // let go of right-click so it doesn't start on its own
        f.eaten++;
        if (f.eaten >= EAT_COUNT.asInt() || findFoodIndex(bot) < 0) {
            endRetreat(server, name, f); // done: fight again
        } else {
            startEat(server, name, f, bot); // keep eating, even if the target is right on top of the bot
        }
    }

    private static void endRetreat(MinecraftServer server, String name, Fight f) {
        run(server, "player " + name + " stop");
        f.reset();
        f.nextEatTick = globalTick + EAT_COOLDOWN_TICKS;
    }
}

