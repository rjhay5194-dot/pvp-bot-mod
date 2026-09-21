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
import com.mojang.brigadier.tree.CommandNode;
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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
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

import static dev.pvpbotcmd.BotSupport.*;
import static dev.pvpbotcmd.Fighting.*;
import static dev.pvpbotcmd.PvpBotMod.*;

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

    static final Map<String, Opt> OPTS = new LinkedHashMap<>();

    static final class Opt {
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

    static Opt opt(String cmd, String key, double def, double min, double max, boolean bool, String desc) {
        Opt o = new Opt(cmd, key, def, min, max, bool, desc, null);
        OPTS.put(cmd, o);
        return o;
    }

    static Opt choiceOpt(String cmd, String key, int def, String desc, String... choices) {
        Opt o = new Opt(cmd, key, def, 0, choices.length - 1, false, desc, choices);
        OPTS.put(cmd, o);
        return o;
    }

    static final Opt ATTACK_RANGE = opt("range", "attack_range", 3.0, 1.0, 6.0, false,
            "reach in blocks, measured from the bot's eyes to the target's hitbox");
    static final Opt BUNNY_HOP = opt("bunnyhop", "bunny_hop", 1, 0, 1, true,
            "sprint-jump while chasing");
    static final Opt HOP_STOP = opt("hopstop", "hop_stop_distance", 3.5, 1.0, 8.0, false,
            "bunny hopping stops this many blocks from the target");
    static final Opt REVENGE = opt("revenge", "revenge", 1, 0, 1, true,
            "bots fight back against whoever hits them");
    static final Opt EAT_HEARTS = opt("eat", "eat_below_hearts", 5.0, 0, 10, false,
            "bots run away and eat at this many hearts or fewer (0 = never)");
    static final Opt EAT_COUNT = opt("eatcount", "eat_count", 3, 1, 10, false,
            "items eaten in a row before the bot fights again (it runs away only once, then eats them all)");
    static final Opt FLEE_DIST = opt("flee", "flee_distance", 8.0, 3.0, 30.0, false,
            "blocks to run away before eating");
    static final Opt CRIT_CHANCE = opt("critchance", "crit_chance", 25, 0, 100, false,
            "percent chance to try a critical hit when close");
    static final Opt CRIT_RANGE = opt("critrange", "crit_range", 4.0, 1.0, 6.0, false,
            "blocks within which bots try critical hits");
    static final Opt FALL_CRIT = opt("fallcrit", "fall_crit", 1, 0, 1, true,
            "bots that are falling within reach always hit as a critical (ignores critchance/critrange)");
    static final Opt AUTO_TARGET = opt("autotarget", "auto_target", 1, 0, 1, true,
            "idle bots pick the nearest player as their target");
    static final Opt FIND_RANGE = opt("findrange", "find_range", 24, 4, 128, false,
            "blocks within which bots look for a target");

    static final Opt AIM = opt("aim", "aim_ticks", 3, 0, 20, false,
            "ticks the bot takes to turn toward its target (0 = instant)");
    static final Opt REACT_MIN = opt("reactmin", "reaction_min_ticks", 3, 0, 40, false,
            "shortest wait before the bot swings once a hit is possible");
    static final Opt REACT_MAX = opt("reactmax", "reaction_max_ticks", 7, 0, 40, false,
            "longest wait before the bot swings once a hit is possible");
    static final Opt BOT_FIGHT = opt("botfight", "bots_fight_each_other", 1, 0, 1, true,
            "bots can target and take revenge on other bots");
    static final Opt WEB_ESCAPE = opt("webescape", "web_escape", 1, 0, 1, true,
            "bots stuck in a cobweb use a water bucket from their inventory to get out");
    static final Opt WEB_BREAK = opt("webbreak", "web_break", 1, 0, 1, true,
            "bots stuck in a cobweb with no water bucket break it with their weapon");
    static final Opt STRAFE = opt("strafe", "strafe", 1, 0, 1, true,
            "bots strafe left and right while close to their target");
    static final Opt STRAFE_TICKS = opt("strafeticks", "strafe_ticks", 12, 2, 40, false,
            "ticks between strafe direction changes");

    static final Opt TAP_MODE = choiceOpt("tapmode", "tap_mode", 1,
            "how the bot taps after each hit: off, wtap (let go of forward), stap (step back), mixed (random)",
            "off", "wtap", "stap", "mixed");
    static final Opt TAP_TICKS = opt("tapticks", "tap_ticks", 2, 1, 10, false,
            "ticks a W-tap or S-tap lasts");
    static final Opt JUMP_CHANCE = opt("jumpchance", "jump_chance", 15, 0, 100, false,
            "percent chance, every quarter second, that a bot close to its target jumps");
    static final Opt JUMP_RANGE = opt("jumprange", "jump_range", 5.0, 1.0, 12.0, false,
            "blocks within which bots randomly jump on their target");

    static final Opt JUMP_RESET = opt("jumpreset", "jump_reset_chance", 20, 0, 100, false,
            "percent chance to jump right after taking a hit, to shake off the knockback");
    static final Opt SHIELD_CHANCE = opt("shield", "shield_chance", 45, 0, 100, false,
            "percent chance to raise a shield when the target is about to swing (needs a shield in the inventory)");
    static final Opt SHIELD_TICKS = opt("shieldticks", "shield_ticks", 20, 2, 100, false,
            "ticks the shield stays up (a shield needs about 5 ticks to start blocking, so keep this well above that)");
    static final Opt STUN_CHANCE = opt("stun", "shield_stun_chance", 35, 0, 100, false,
            "percent chance to swap to an axe when the target raises a shield, to disable it");
    static final Opt HIT_WEB = opt("hitweb", "hit_web_chance", 25, 0, 100, false,
            "percent chance that a landed hit is followed by really placing a cobweb at the target's feet (needs cobwebs in the inventory)");
    static final Opt WEB_LIFETIME = opt("webtime", "web_lifetime_ticks", 100, 0, 1200, false,
            "ticks before a cobweb dropped by a bot disappears again (0 = never)");
    static final Opt STUCK = opt("stuck", "stuck_detection", 1, 0, 1, true,
            "bots that stop making progress jump and sidestep");
    static final Opt TOTEM = opt("totem", "totem_offhand", 1, 0, 1, true,
            "bots put a totem of undying (or else a shield) from their inventory into the off-hand");
    static final Opt MASS_MAX = opt("massmax", "mass_spawn_max", 20, 1, 100, false,
            "most bots /pvpbot mass_spawn may create at once");

    static final Opt KEEP_DISTANCE = opt("keepdistance", "keep_distance", 2.8, 0, 6, false,
            "blocks the bot tries to keep from its target so its sword hits properly (0 = off)");
    static final Opt STRAFE_CHANCE = opt("strafechance", "strafe_chance", 60, 0, 100, false,
            "percent chance, each strafe interval, that the bot strafes instead of walking straight");
    static final Opt MISS_CHANCE = opt("miss", "miss_chance", 10, 0, 100, false,
            "percent chance a swing whiffs (swings at air)");
    static final Opt WOBBLE = opt("wobble", "aim_wobble_degrees", 2, 0, 15, false,
            "random aim error in degrees (turns instantly while this is above 0)");
    static final Opt VOID_AWARE = opt("voidaware", "void_lava_awareness", 1, 0, 1, true,
            "bots stop at ledges and lava and won't run or back off into them");
    static final Opt COCOON = opt("cocoon", "web_cocoon", 1, 0, 1, true,
            "at very low hearts a bot with 2+ cobwebs and food webs itself in and eats inside");
    static final Opt COCOON_HEARTS = opt("cocoonhearts", "cocoon_hearts", 3.0, 0, 10, false,
            "hearts at or below which the cocoon is used");
    static final Opt POTIONS = opt("potions", "splash_potions", 1, 0, 1, true,
            "bots use splash potions of healing, speed and strength from their inventory");
    static final Opt POTION_HEARTS = opt("potionhearts", "potion_heal_hearts", 6.0, 0, 10, false,
            "hearts at or below which a bot throws a healing potion");
    static final Opt POTION_DELAY = opt("potiondelay", "potion_delay_ticks", 200, 20, 2400, false,
            "ticks between potion runs");
    static final Opt LOOT = opt("loot", "loot_pickup", 1, 0, 1, true,
            "bots walk to dropped items they don't have when no fight is close");
    static final Opt LOOT_RANGE = opt("lootrange", "loot_range", 12, 3, 48, false,
            "blocks within which bots notice dropped items");
    static final Opt FOCUS_CHANCE = opt("focuschance", "focus_chance", 60, 0, 100, false,
            "percent chance a bot joins its team's focus target (0 = no focus fire)");
    static final Opt FOCUS_MIN = opt("focusmin", "focus_min_ticks", 100, 20, 2400, false,
            "shortest time a team keeps one focus target");
    static final Opt FOCUS_MAX = opt("focusmax", "focus_max_ticks", 300, 20, 2400, false,
            "longest time a team keeps one focus target");
    static final Opt OPS_ONLY = opt("opsonly", "ops_only_commands", 1, 0, 1, true,
            "only operators (those who can use /gamemode) may run /pvpbot");
    static final Opt TAUNTS = opt("taunts", "taunts", 0, 0, 1, true,
            "bots post short chat lines when they win a kill or the match");
    static final Opt FFA_LEAVE = opt("ffaleave", "ffa_bot_leave_on_death", 1, 0, 1, true,
            "an FFA match turns HeroBot's botleaveondeath on (and off again afterwards)");

    // ---- difficulty presets: /pvpbot difficulty <1-6 or name> sets all of these at once
    static final String[] LEVEL_NAMES = {"beginner", "easy", "normal", "hard", "insane", "perfect"};
    static final List<String> DIFFICULTY_CHOICES =
            List.of("1", "2", "3", "4", "5", "6", "beginner", "easy", "normal", "hard", "insane", "perfect");
    static final Opt[] PRESET_OPTS = {
            AIM, REACT_MIN, REACT_MAX, CRIT_CHANCE, STRAFE, STRAFE_TICKS, TAP_MODE, TAP_TICKS,
            BUNNY_HOP, EAT_HEARTS, FALL_CRIT, JUMP_CHANCE, JUMP_RESET, SHIELD_CHANCE, STUN_CHANCE,
            STRAFE_CHANCE, MISS_CHANCE, WOBBLE, FOCUS_CHANCE, POTIONS, POTION_HEARTS, SHIELD_TICKS
    };
    //                                           aim rmin rmax crit strafe sticks tap tticks hop eat fcrit jump jreset shield stun
    //                                           schance miss wobble focus potions phearts shticks
    static final double[][] PRESETS = {
            /* 1 beginner */ {8, 8, 16, 0, 0, 14, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 30, 6, 0, 0, 5, 10},
            /* 2 easy     */ {5, 5, 10, 10, 1, 16, 1, 3, 0, 3, 0, 5, 5, 20, 10, 25, 20, 4, 30, 0, 5, 14},
            /* 3 normal   */ {3, 3, 7, 25, 1, 12, 1, 2, 1, 5, 1, 15, 20, 45, 35, 55, 10, 2, 60, 1, 6, 20},
            /* 4 hard     */ {2, 1, 4, 45, 1, 9, 3, 2, 1, 6, 1, 25, 35, 65, 60, 75, 4, 1, 80, 1, 7, 20},
            /* 5 insane   */ {1, 0, 2, 70, 1, 6, 3, 1, 1, 7, 1, 35, 55, 85, 85, 90, 1, 0, 95, 1, 8, 18},
            /* 6 perfect  */ {0, 0, 0, 100, 1, 6, 3, 1, 1, 7, 1, 50, 100, 100, 100, 100, 0, 0, 100, 1, 8, 12},
    };
    static String difficultyName = "normal";

    static final double STRAFE_MAX_DIST = 6.0;
    static final double HOP_RESUME_MARGIN = 1.0;
    static final int FLEE_MAX_TICKS = 80;
    static final int EAT_TIMEOUT_TICKS = 60;
    static final int EAT_COOLDOWN_TICKS = 60;
    static final int CRIT_TIMEOUT_TICKS = 25;
    static final int WEB_BREAK_TIMEOUT_TICKS = 80;
    static final double POTION_SAFE_DIST = 5.0;
    static final double KEEP_BAND = 0.5;
    static final double LOOT_FIGHT_DIST = 10.0;
    static final String[] TAUNT_LINES = {"gg", "ez", "too slow", "nice try", "sit down", "get good"};

    // ---------------------------------------------------------------- state

    static final Set<String> BOTS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    static final Set<String> STOPPED = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    static final Map<String, Fight> FIGHTS = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    static final Map<String, Pending> PENDING = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    static final Map<String, Integer> LAST_HURT = new HashMap<>();
    static final ArrayDeque<MassJob> MASS_QUEUE = new ArrayDeque<>();
    static final ArrayList<PlacedWeb> PLACED_WEBS = new ArrayList<>();
    static CommandDispatcher<CommandSourceStack> dispatcher;
    static int globalTick = 0;

    static final Map<String, LootState> LOOTING = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    static final Map<String, Focus> FOCUS = new HashMap<>();
    static final Set<String> FFA_ALIVE = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    static final Set<String> FFA_BOTS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    static final Set<String> FFA_HUMANS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    static final Set<String> FFA_OUT_HUMANS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    static final Map<String, Integer> FFA_KILLS = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    static final Map<String, String> LAST_ATTACKER = new HashMap<>();
    static boolean ffaActive;
    static boolean ffaTeams;
    static boolean ffaAutoStart;
    static boolean ffaAutoTeams;
    static int ffaGoTick;
    static int ffaLastCount = -1;
    static String ffaOwner = "";

    static final SuggestionProvider<CommandSourceStack> BOT_NAMES =
            (ctx, builder) -> SharedSuggestionProvider.suggest(BOTS, builder);

    /** A bot that was asked to spawn but has not joined yet (HeroBot spawns bots asynchronously). */
    static final class Pending {
        final String owner;
        final boolean quiet;
        final String team;
        final Vec3 tpPos;
        int waited;

        Pending(String owner, boolean quiet, String team, Vec3 tpPos) {
            this.owner = owner;
            this.quiet = quiet;
            this.team = team;
            this.tpPos = tpPos;
        }
    }

    /** One bot waiting to be spawned by /pvpbot mass_spawn (they are spawned a few ticks apart). */
    static final class MassJob {
        final String owner;
        final String name;
        final String team;
        final Vec3 pos;

        MassJob(String owner, String name, String team, Vec3 pos) {
            this.owner = owner;
            this.name = name;
            this.team = team;
            this.pos = pos;
        }
    }

    /** A cobweb dropped by a bot, removed again when it expires. */
    static final class PlacedWeb {
        final Level level;
        final BlockPos pos;
        final int expireTick;

        PlacedWeb(Level level, BlockPos pos, int expireTick) {
            this.level = level;
            this.pos = pos;
            this.expireTick = expireTick;
        }
    }

    /** The enemy a team is currently ganging up on. */
    static final class Focus {
        final String target;
        final int expire;

        Focus(String target, int expire) {
            this.target = target;
            this.expire = expire;
        }
    }

    /** The dropped item a bot is walking to. */
    static final class LootState {
        final int entityId;
        final int start;

        LootState(int entityId, int start) {
            this.entityId = entityId;
            this.start = start;
        }
    }

    enum Phase { FIGHT, FLEE, EAT, COCOON, POTION }

    static final class Fight {
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
        int keepMode = 1;       // 1 = closing in, -1 = backing off, 0 = holding the gap
        boolean edgeHold;       // standing at a ledge / lava instead of walking on
        int fleeFor;            // 0 = running away to eat, 1 = running away to throw potions
        int thrownMask;         // potion kinds already thrown in this run (bit per kind)
        int potionStage;
        int potionStart;
        int potionKind;
        int nextPotionTick;
        int cocoonStage;
        int cocoonStart;
        boolean inCocoon;       // eating inside its own webs: don't try to escape them

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
            keepMode = 1;
            edgeHold = false;
            fleeFor = 0;
            thrownMask = 0;
            potionStage = 0;
            cocoonStage = 0;
            inCocoon = false;
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
        ServerTickEvents.END_SERVER_TICK.register(BotSupport::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            BOTS.clear();
            STOPPED.clear();
            FIGHTS.clear();
            PENDING.clear();
            LAST_HURT.clear();
            MASS_QUEUE.clear();
            PLACED_WEBS.clear();
            LOOTING.clear();
            FOCUS.clear();
            FFA_ALIVE.clear();
            FFA_OUT_HUMANS.clear();
            ffaActive = false;
            ffaAutoStart = false;
        });
    }

    // ---------------------------------------------------------------- commands

    static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("pvpbot");
        root.requires(BotSupport::allowed);

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
                        .executes(ctx -> massSpawn(ctx, IntegerArgumentType.getInteger(ctx, "count"), "Bot", null))
                        .then(Commands.argument("prefix", StringArgumentType.word())
                                .executes(ctx -> massSpawn(ctx,
                                        IntegerArgumentType.getInteger(ctx, "count"),
                                        StringArgumentType.getString(ctx, "prefix"), null))
                                .then(Commands.argument("team", StringArgumentType.word())
                                        .executes(ctx -> massSpawn(ctx,
                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                StringArgumentType.getString(ctx, "prefix"),
                                                StringArgumentType.getString(ctx, "team")))))));

        root.then(Commands.literal("ffa")
                .then(Commands.literal("start")
                        .executes(ctx -> ffaStart(ctx, false))
                        .then(Commands.literal("teams").executes(ctx -> ffaStart(ctx, true))))
                .then(Commands.literal("stop").executes(PvpBotMod::ffaStop))
                .then(Commands.literal("restart").executes(PvpBotMod::ffaRestart))
                .then(Commands.literal("stats").executes(PvpBotMod::ffaStats)));

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

    static boolean herobotPresent() {
        return dispatcher != null
                && dispatcher.getRoot().getChild("playerspawn") != null
                && dispatcher.getRoot().getChild("player") != null;
    }

    static int spawn(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
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
        PENDING.put(name, new Pending(me.getName().getString(), false, null, null));
        return 1;
    }

    static int adopt(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
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

    static int fight(CommandContext<CommandSourceStack> ctx, String name, String target) throws CommandSyntaxException {
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

    static int stop(CommandContext<CommandSourceStack> ctx, String name) {
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

    static int ping(CommandContext<CommandSourceStack> ctx, String name, int ms) {
        CommandSourceStack src = ctx.getSource();
        if (!BOTS.contains(name)) {
            src.sendFailure(Component.literal(name + " is not a PvP bot yet. If you spawned it with /playerspawn, run /pvpbot adopt " + name + " first."));
            return 0;
        }
        run(src.getServer(), "player " + name + " ping " + ms);
        src.sendSuccess(() -> Component.literal(name + " ping set to " + ms + " ms."), false);
        return 1;
    }

    static int remove(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack src = ctx.getSource();
        if (!BOTS.contains(name)) {
            src.sendFailure(Component.literal(name + " is not a PvP bot yet. If you spawned it with /playerspawn, run /pvpbot adopt " + name + " first."));
            return 0;
        }
        removeBot(src.getServer(), name);
        src.sendSuccess(() -> Component.literal("Removed " + name + "."), false);
        return 1;
    }

    static int clear(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        for (String name : new TreeSet<>(BOTS)) {
            removeBot(src.getServer(), name);
        }
        src.sendSuccess(() -> Component.literal("Removed all PvP bots."), false);
        return 1;
    }

    static int list(CommandContext<CommandSourceStack> ctx) {
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

    static int listOptions(CommandContext<CommandSourceStack> ctx) {
        showDifficulty(ctx);
        for (Opt o : OPTS.values()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "/pvpbot " + o.cmd + " = " + o.show() + "  (" + o.desc + ")"), false);
        }
        return OPTS.size();
    }

    static int help(CommandContext<CommandSourceStack> ctx) {
        String[] lines = {
                "/pvpbot spawn <name>  |  mass_spawn <count> [prefix] [team]  |  adopt <name>",
                "/pvpbot fight <name> [target]  |  stop <name>  |  remove <name>  |  clear  |  list",
                "/pvpbot status <name>  |  ping <name> <ms>  |  reload  |  options",
                "/pvpbot ffa start [teams]  |  ffa stop  |  ffa restart  |  ffa stats",
                "/pvpbot difficulty <1-6 or beginner/easy/normal/hard/insane/perfect> (perfect is brutal)",
                "Teams use vanilla /team (/team add red, /team join red Bot1). Bots leave creative players alone.",
                "Every setting is /pvpbot <setting> [value]. Run /pvpbot options to see them all."
        };
        for (String line : lines) {
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    static int reload(CommandContext<CommandSourceStack> ctx) {
        loadConfig();
        ctx.getSource().sendSuccess(() -> Component.literal("Reloaded config/pvpbotcmd.properties."), true);
        return 1;
    }

    static int status(CommandContext<CommandSourceStack> ctx, String name) {
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

    static int massSpawn(CommandContext<CommandSourceStack> ctx, int count, String prefix, String team) throws CommandSyntaxException {
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
        List<String> names = new ArrayList<>();
        for (int n = 1; names.size() < count && n < 1000; n++) {
            String name = prefix + n;
            if (name.length() > 16 || used.contains(name) || PENDING.containsKey(name)
                    || server.getPlayerList().getPlayerByName(name) != null) {
                continue;
            }
            used.add(name);
            names.add(name);
        }
        queueMassJobs(server, me, names, team);
        int total = names.size();
        src.sendSuccess(() -> Component.literal("Spawning " + total + " bots named " + prefix + "1, " + prefix + "2, ..."
                + (team != null ? " on team " + team : "")), false);
        return total;
    }

    /** Queues the bots for spawning in a ring around the owner, optionally on a team (created if it doesn't exist). */
    static void queueMassJobs(MinecraftServer server, ServerPlayer owner, List<String> names, String team) {
        Vec3 base = owner.position();
        double radius = Math.max(3.0, Math.min(12.0, names.size() * 0.7));
        String ownerName = owner.getName().getString();
        if (team != null) {
            run(server, "team add " + team); // harmless if it already exists
        }
        for (int i = 0; i < names.size(); i++) {
            double angle = 2.0 * Math.PI * i / names.size();
            Vec3 pos = new Vec3(base.x + Math.cos(angle) * radius, base.y, base.z + Math.sin(angle) * radius);
            MASS_QUEUE.add(new MassJob(ownerName, names.get(i), team, pos));
        }
    }

    static int ffaStart(CommandContext<CommandSourceStack> ctx, boolean teams) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer me = src.getPlayerOrException();
        String err = startFfa(src.getServer(), me.getName().getString(), teams);
        if (err != null) {
            src.sendFailure(Component.literal(err));
            return 0;
        }
        return 1;
    }

    static int ffaStop(CommandContext<CommandSourceStack> ctx) {
        if (!ffaActive) {
            ctx.getSource().sendFailure(Component.literal("No FFA match is running."));
            return 0;
        }
        endFfa(ctx.getSource().getServer(), false);
        return 1;
    }

    static int ffaRestart(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer me = src.getPlayerOrException();
        MinecraftServer server = src.getServer();
        ffaOwner = me.getName().getString();
        boolean teams = ffaTeams;
        if (ffaActive) {
            endFfa(server, false);
        }
        List<String> missing = new ArrayList<>();
        for (String n : FFA_BOTS) {
            if (server.getPlayerList().getPlayerByName(n) == null && !PENDING.containsKey(n)) {
                missing.add(n);
            }
        }
        if (missing.isEmpty()) {
            String err = startFfa(server, ffaOwner, teams);
            if (err != null) {
                src.sendFailure(Component.literal(err));
                return 0;
            }
            return 1;
        }
        queueMassJobs(server, me, missing, null);
        ffaAutoStart = true;
        ffaAutoTeams = teams;
        int n = missing.size();
        src.sendSuccess(() -> Component.literal("Rematch: respawning " + n + " bots, then a new countdown."), false);
        return 1;
    }

    static int ffaStats(CommandContext<CommandSourceStack> ctx) {
        String text = (ffaActive ? "Match running, " + FFA_ALIVE.size() + " left. " : "No match running. ") + leaderboard();
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    static boolean isPresetOpt(Opt o) {
        for (Opt p : PRESET_OPTS) {
            if (p == o) {
                return true;
            }
        }
        return false;
    }

    static int showDifficulty(CommandContext<CommandSourceStack> ctx) {
        String text = "Difficulty: " + difficultyName;
        for (int i = 0; i < LEVEL_NAMES.length; i++) {
            if (LEVEL_NAMES[i].equals(difficultyName)) {
                text += " (level " + (i + 1) + " of 6)";
            }
        }
        if (difficultyName.equals("custom")) {
            text += " (a setting was changed after choosing a preset)";
        }
        String shown = text;
        ctx.getSource().sendSuccess(() -> Component.literal(shown), false);
        return 1;
    }

    static int setDifficulty(CommandContext<CommandSourceStack> ctx, String raw) {
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
                    "Unknown difficulty. Use 1-6 or beginner, easy, normal, hard, insane, perfect."));
            return 0;
        }
        for (int i = 0; i < PRESET_OPTS.length; i++) {
            Opt o = PRESET_OPTS[i];
            o.value = Math.max(o.min, Math.min(o.max, PRESETS[level][i]));
        }
        difficultyName = LEVEL_NAMES[level];
        saveConfig();
        String msg = "Difficulty set to " + LEVEL_NAMES[level] + " (level " + (level + 1) + " of 6).";
        ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
        return 1;
    }

    static int showOpt(CommandContext<CommandSourceStack> ctx, Opt o) {
        ctx.getSource().sendSuccess(() -> Component.literal(
                o.key + " = " + o.show() + "  (" + o.desc + ")"), false);
        return 1;
    }

    static int choiceIndex(Opt o, String raw) {
        String r = raw.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < o.choices.length; i++) {
            if (o.choices[i].equals(r) || Integer.toString(i).equals(r)) {
                return i;
            }
        }
        return -1;
    }

    static int setChoice(CommandContext<CommandSourceStack> ctx, Opt o, String raw) {
        int idx = choiceIndex(o, raw);
        if (idx < 0) {
            ctx.getSource().sendFailure(Component.literal(
                    "Unknown value. Use one of: " + String.join(", ", o.choices)));
            return 0;
        }
        return setOpt(ctx, o, idx);
    }

    static int setOpt(CommandContext<CommandSourceStack> ctx, Opt o, double v) {
        o.value = Math.max(o.min, Math.min(o.max, v));
        if (isPresetOpt(o)) {
            difficultyName = "custom";
        }
        saveConfig();
        ctx.getSource().sendSuccess(() -> Component.literal(o.key + " set to " + o.show()), true);
        return 1;
    }

    static void removeBot(MinecraftServer server, String name) {
        FIGHTS.remove(name);
        BOTS.remove(name);
        STOPPED.remove(name);
        LAST_HURT.remove(name);
        LOOTING.remove(name);
        run(server, "player " + name + " disconnect");
    }

    // ---------------------------------------------------------------- config

    static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("pvpbotcmd.properties");
    }

    static void loadConfig() {
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

    static void saveConfig() {
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
}

