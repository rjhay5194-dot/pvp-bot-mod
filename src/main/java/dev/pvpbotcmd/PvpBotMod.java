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
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
 *
 * Settings (each is /pvpbot <name> [value]; saved to config/pvpbotcmd.properties): see the OPT constants below.
 *
 * Behaviour of bots made with /pvpbot spawn or /pvpbot adopt:
 *   - wear any armor in their inventory (empty armor slots only)
 *   - hold their best sword (or axe) from the hotbar while fighting
 *   - chase with sprint + bunny hop, stop hopping when close
 *   - after each hit they stand still for a few ticks, then go again
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
        double value;

        Opt(String cmd, String key, double def, double min, double max, boolean bool, String desc) {
            this.cmd = cmd;
            this.key = key;
            this.min = min;
            this.max = max;
            this.bool = bool;
            this.desc = desc;
            this.value = def;
        }

        boolean on() {
            return value >= 0.5;
        }

        int asInt() {
            return (int) Math.round(value);
        }

        String show() {
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
        Opt o = new Opt(cmd, key, def, min, max, bool, desc);
        OPTS.put(cmd, o);
        return o;
    }

    private static final Opt ATTACK_RANGE = opt("range", "attack_range", 3.0, 1.0, 6.0, false,
            "reach in blocks, measured from the bot's eyes to the target's hitbox");
    private static final Opt BUNNY_HOP = opt("bunnyhop", "bunny_hop", 1, 0, 1, true,
            "sprint-jump while chasing");
    private static final Opt HOP_STOP = opt("hopstop", "hop_stop_distance", 3.5, 1.0, 8.0, false,
            "bunny hopping stops this many blocks from the target");
    private static final Opt HIT_PAUSE = opt("hitpause", "hit_pause_ticks", 6, 0, 40, false,
            "ticks a bot stands still after a hit (0 = never)");
    private static final Opt REVENGE = opt("revenge", "revenge", 1, 0, 1, true,
            "bots fight back against whoever hits them");
    private static final Opt EAT_HEARTS = opt("eat", "eat_below_hearts", 6.0, 0, 10, false,
            "bots run away and eat at this many hearts or fewer (0 = never)");
    private static final Opt EAT_COUNT = opt("eatcount", "eat_count", 3, 1, 10, false,
            "items eaten in a row before the bot fights again (it runs away only once, then eats them all)");
    private static final Opt FLEE_DIST = opt("flee", "flee_distance", 8.0, 3.0, 30.0, false,
            "blocks to run away before eating");
    private static final Opt CRIT_CHANCE = opt("critchance", "crit_chance", 30, 0, 100, false,
            "percent chance to try a critical hit when close");
    private static final Opt CRIT_RANGE = opt("critrange", "crit_range", 4.0, 1.0, 6.0, false,
            "blocks within which bots try critical hits");
    private static final Opt FALL_CRIT = opt("fallcrit", "fall_crit", 1, 0, 1, true,
            "bots that are falling within reach always hit as a critical (ignores critchance/critrange)");
    private static final Opt AUTO_TARGET = opt("autotarget", "auto_target", 1, 0, 1, true,
            "idle bots pick the nearest player as their target");
    private static final Opt FIND_RANGE = opt("findrange", "find_range", 24, 4, 128, false,
            "blocks within which bots look for a target");

    private static final Opt AIM = opt("aim", "aim_ticks", 2, 0, 20, false,
            "ticks the bot takes to turn toward its target (0 = instant)");
    private static final Opt REACT_MIN = opt("reactmin", "reaction_min_ticks", 2, 0, 40, false,
            "shortest wait before the bot swings once a hit is possible");
    private static final Opt REACT_MAX = opt("reactmax", "reaction_max_ticks", 6, 0, 40, false,
            "longest wait before the bot swings once a hit is possible");
    private static final Opt BOT_FIGHT = opt("botfight", "bots_fight_each_other", 1, 0, 1, true,
            "bots can target and take revenge on other bots");
    private static final Opt WEB_ESCAPE = opt("webescape", "web_escape", 1, 0, 1, true,
            "bots stuck in a cobweb use a water bucket from their inventory to get out");
    private static final Opt STRAFE = opt("strafe", "strafe", 1, 0, 1, true,
            "bots strafe left and right while close to their target");
    private static final Opt STRAFE_TICKS = opt("strafeticks", "strafe_ticks", 10, 2, 40, false,
            "ticks between strafe direction changes");

    private static final double STRAFE_MAX_DIST = 6.0;
    private static final double HOP_RESUME_MARGIN = 1.0;
    private static final int FLEE_MAX_TICKS = 80;
    private static final int EAT_TIMEOUT_TICKS = 60;
    private static final int EAT_COOLDOWN_TICKS = 60;
    private static final int CRIT_TIMEOUT_TICKS = 25;

    // ---------------------------------------------------------------- state

    private static final Set<String> BOTS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private static final Set<String> STOPPED = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private static final Map<String, Fight> FIGHTS = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private static final Map<String, Pending> PENDING = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private static final Map<String, Integer> LAST_HURT = new HashMap<>();
    private static CommandDispatcher<CommandSourceStack> dispatcher;
    private static int globalTick = 0;

    private static final SuggestionProvider<CommandSourceStack> BOT_NAMES =
            (ctx, builder) -> SharedSuggestionProvider.suggest(BOTS, builder);

    /** A bot that was asked to spawn but has not joined yet (HeroBot spawns bots asynchronously). */
    private static final class Pending {
        final String owner;
        int waited;

        Pending(String owner) {
            this.owner = owner;
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
        int strafeDir;
        int nextStrafeTick;
        int webStage;
        int webStart;
        int nextWebTick;

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

        // One command per setting: /pvpbot <name> shows it, /pvpbot <name> <value> changes it.
        for (Opt o : OPTS.values()) {
            LiteralArgumentBuilder<CommandSourceStack> node =
                    Commands.literal(o.cmd).executes(ctx -> showOpt(ctx, o));
            if (o.bool) {
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
        PENDING.put(name, new Pending(me.getName().getString()));
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
        for (Opt o : OPTS.values()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "/pvpbot " + o.cmd + " = " + o.show() + "  (" + o.desc + ")"), false);
        }
        return OPTS.size();
    }

    private static int showOpt(CommandContext<CommandSourceStack> ctx, Opt o) {
        ctx.getSource().sendSuccess(() -> Component.literal(
                o.key + " = " + o.show() + "  (" + o.desc + ")"), false);
        return 1;
    }

    private static int setOpt(CommandContext<CommandSourceStack> ctx, Opt o, double v) {
        o.value = Math.max(o.min, Math.min(o.max, v));
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
        for (Opt o : OPTS.values()) {
            String raw = props.getProperty(o.key);
            if (raw == null) {
                continue;
            }
            try {
                double v = o.bool
                        ? (Boolean.parseBoolean(raw.trim()) ? 1 : 0)
                        : Double.parseDouble(raw.trim());
                o.value = Math.max(o.min, Math.min(o.max, v));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
    }

    private static void saveConfig() {
        Properties props = new Properties();
        StringBuilder comment = new StringBuilder("PvPBot Commands config. Change values in game with /pvpbot <setting> <value>.");
        for (Opt o : OPTS.values()) {
            props.setProperty(o.key, o.bool ? Boolean.toString(o.on()) : Double.toString(o.value));
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

    private static boolean inCobweb(ServerPlayer bot) {
        BlockPos pos = bot.blockPosition();
        return bot.level().getBlockState(pos).is(Blocks.COBWEB)
                || bot.level().getBlockState(pos.above()).is(Blocks.COBWEB);
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
     * Cobweb escape: look down, pour the water bucket so the water breaks the web,
     * then pick the water back up. Returns true while it is busy (the rest of the fight logic waits).
     */
    private static boolean webEscape(MinecraftServer server, String name, Fight f, ServerPlayer bot) {
        if (f.webStage == 0) {
            if (!WEB_ESCAPE.on() || globalTick < f.nextWebTick || !inCobweb(bot)) {
                return false;
            }
            int idx = findItemIndex(bot, Items.WATER_BUCKET);
            int slot = idx < 0 ? -1 : toHotbar(bot, idx, findWeaponSlot(bot));
            if (slot < 0) {
                f.nextWebTick = globalTick + 100; // no water bucket: don't check every tick
                return false;
            }
            run(server, "player " + name + " stop");
            run(server, "player " + name + " hotbar " + (slot + 1));
            run(server, "player " + name + " look " + String.format(Locale.ROOT, "%.1f", bot.getYRot()) + " 90");
            f.started = false;
            f.hopping = false;
            f.paused = false;
            f.crit = false;
            f.strafeDir = 0;
            f.attackTick = -1;
            f.webStage = 1;
            f.webStart = globalTick;
            return true;
        }
        if (f.webStage == 1) {
            if (globalTick - f.webStart >= 2) {
                run(server, "player " + name + " use once"); // pour the water
                f.webStage = 2;
                f.webStart = globalTick;
            }
        } else if (f.webStage == 2) {
            if (globalTick - f.webStart >= 12) {
                run(server, "player " + name + " use once"); // scoop the water back up
                f.webStage = 3;
                f.webStart = globalTick;
            }
        } else if (globalTick - f.webStart >= 6) {
            f.webStage = 0;
            f.nextWebTick = globalTick + 40;
            f.started = false;
            f.weaponSlot = -1; // back to the sword
        }
        return true;
    }

    /** Can this player still be fought (online, alive, not a spectator, same dimension)? */
    private static boolean reachable(ServerPlayer bot, ServerPlayer t) {
        return t != null && t != bot && t.isAlive() && !t.isSpectator() && bot.level() == t.level();
    }

    // ---------------------------------------------------------------- per-tick jobs

    private static void tick(MinecraftServer server) {
        globalTick++;
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
                if (owner != null) {
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
        // Stuck in a cobweb: use a water bucket to get out.
        if (webEscape(server, name, f, bot)) {
            return;
        }

        // Low health: run away bunny hopping and eat.
        double hp = bot.getHealth() + bot.getAbsorptionAmount();
        if (EAT_HEARTS.value > 0 && hp <= EAT_HEARTS.value * 2.0 && globalTick >= f.nextEatTick
                && findFoodIndex(bot) >= 0) {
            startFlee(server, name, f);
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

        // Hold the best weapon (re-checked every half second, or right after eating).
        if (f.weaponSlot < 0 || globalTick % 10 == 0) {
            int w = findWeaponSlot(bot);
            if (w >= 0 && w != f.weaponSlot) {
                run(server, "player " + name + " hotbar " + (w + 1));
                f.weaponSlot = w;
            }
        }

        // Hit-and-pause: two ticks after a swing (so the sprint hit lands), stand still for a moment.
        if (f.attackTick >= 0 && globalTick - f.attackTick >= 2) {
            f.attackTick = -1;
            int pause = HIT_PAUSE.asInt();
            if (pause > 0) {
                run(server, "player " + name + " move"); // stops all movement
                if (f.hopping) {
                    run(server, "player " + name + " jump"); // stops continuous jumping
                    f.hopping = false;
                }
                f.started = false;
                f.strafeDir = 0;
                f.paused = true;
                f.pauseUntil = globalTick + pause;
            }
        }
        if (f.paused) {
            if (globalTick >= f.pauseUntil) {
                f.paused = false; // movement starts again next tick
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
        } else if (f.strafeDir != 0) {
            run(server, "player " + name + " move");
            run(server, "player " + name + " move forward");
            f.strafeDir = 0;
        }

        // A sprint-hit cancels sprinting (that is what gives the extra knockback), so sprint again.
        if (!f.crit && globalTick >= f.sprintHoldUntil && !bot.isSprinting()) {
            run(server, "player " + name + " sprint");
        }

        double reach = reachDistance(bot, target);
        boolean ready = bot.getAttackStrengthScale(0.5F) >= 1.0F;

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
