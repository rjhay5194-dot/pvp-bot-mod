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
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
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
 *   /pvpbot spawn <name>              spawn a survival bot at your position (no items)
 *
 * Bots made with /pvpbot spawn or /pvpbot adopt also:
 *   - wear any armor that is in their inventory (empty armor slots only)
 *   - hold their best sword (or axe) from the hotbar while fighting
 *   - eat food from anywhere in their inventory when hungry (see /pvpbot eat)
 *
 *   /pvpbot adopt <name>              take control of a bot you spawned with /playerspawn
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
    private static final Map<String, Pending> PENDING = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private static CommandDispatcher<CommandSourceStack> dispatcher;
    private static int tickCounter = 0;
    private static int globalTick = 0;

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

    /** A bot that was asked to spawn but has not joined yet (HeroBot spawns bots asynchronously). */
    private static final class Pending {
        final String owner;
        int waited;

        Pending(String owner) {
            this.owner = owner;
        }
    }

    private static final class Fight {
        final String target;
        ServerPlayer lastBot;
        boolean started;
        boolean hopping;
        boolean eating;
        int eatStartTick;
        int weaponSlot = -1;

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
            PENDING.clear();
        });
    }

    // ---------------------------------------------------------------- commands

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("pvpbot")
                .then(Commands.literal("spawn")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("adopt")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> adopt(ctx, StringArgumentType.getString(ctx, "name")))))
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

        if (PENDING.containsKey(name)) {
            src.sendFailure(Component.literal(name + " is already being spawned."));
            return 0;
        }

        String pos = String.format(Locale.ROOT, "%.2f %.2f %.2f", me.getX(), me.getY(), me.getZ());
        // Runs with the caller's own permissions and shows HeroBot's own messages, so any error is visible.
        server.getCommands().performPrefixedCommand(src,
                "playerspawn " + name + " at " + pos + " in survival");

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
        src.sendSuccess(() -> Component.literal("Now controlling " + name + ". Use /pvpbot fight " + name + " to start."), false);
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
        FIGHTS.put(name, new Fight(t));
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
        run(src.getServer(), "player " + name + " stop");
        src.sendSuccess(() -> Component.literal(name + " stopped."), false);
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

    /** Inventory index (0-35) of the best food: golden apples first, then anything edible. -1 if none. */
    private static int findFoodIndex(ServerPlayer bot) {
        Inventory inv = bot.getInventory();
        int any = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
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

    private static void tickArmor(MinecraftServer server) {
        for (String name : BOTS) {
            ServerPlayer bot = server.getPlayerList().getPlayerByName(name);
            if (bot != null) {
                equipArmor(bot);
            }
        }
    }

    // ---------------------------------------------------------------- fight loop

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
                if (owner != null) {
                    owner.sendSystemMessage(Component.literal(
                            "Spawned " + name + ". Use /pvpbot fight " + name + " to start."));
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

    private static void tick(MinecraftServer server) {
        globalTick++;
        if (!PENDING.isEmpty()) {
            tickPending(server);
        }
        if (globalTick % 10 == 0 && !BOTS.isEmpty()) {
            tickArmor(server);
        }
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
                f.weaponSlot = -1;
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
                run(server, "player " + botName + " autojump true");
                run(server, "player " + botName + " sprint");
                run(server, "player " + botName + " move forward");
                f.started = true;
            }

            if (tickCounter % 2 == 0) {
                run(server, "player " + botName + " look upon " + f.target + " eyes");
            }

            // Auto-eat: pick food from anywhere in the inventory, hold it, eat, then go back to the weapon.
            if (f.eating) {
                int elapsed = tickCounter - f.eatStartTick;
                if ((elapsed >= 5 && !bot.isUsingItem()) || elapsed > 100) {
                    f.eating = false;
                    f.weaponSlot = -1; // forces a switch back to the sword below
                }
            } else if (eatBelow > 0 && bot.getFoodData().getFoodLevel() <= eatBelow) {
                int idx = findFoodIndex(bot);
                if (idx >= 0) {
                    int slot = toHotbar(bot, idx, findWeaponSlot(bot));
                    if (slot >= 0) {
                        run(server, "player " + botName + " hotbar " + (slot + 1));
                        run(server, "player " + botName + " use once");
                        f.eating = true;
                        f.eatStartTick = tickCounter;
                    }
                }
            }
            if (f.eating) {
                if (f.hopping) {
                    run(server, "player " + botName + " jump");
                    f.hopping = false;
                }
                continue; // no hopping or attacking while eating
            }

            // Hold the best weapon in the hotbar (re-checked every half second, or right after eating).
            if (f.weaponSlot < 0 || tickCounter % 10 == 0) {
                int w = findWeaponSlot(bot);
                if (w >= 0 && w != f.weaponSlot) {
                    run(server, "player " + botName + " hotbar " + (w + 1));
                    f.weaponSlot = w;
                }
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
