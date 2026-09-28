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
import java.util.function.Consumer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import static dev.pvpbotcmd.BotSupport.*;
import static dev.pvpbotcmd.Fighting.*;
import static dev.pvpbotcmd.PvpBotMod.*;

/**
 * Adds /bot. Everything is done by running HeroBot's own commands
 * (/playerspawn and /player), so this mod has no compile-time dependency on HeroBot.
 *
 * Bot commands:
 *    /bot spawn <name>                spawn a survival bot at your position (no items)
 *    /bot adopt <name>                take control of a bot you already spawned
 *    /bot fight <name> [target]       start (or retarget) a fight
 *    /bot stop <name>                 stop a bot, it won't pick a target again on its own
 *    /bot remove <name> / clear       disconnect one bot, or every bot
 *    /bot list / status <name>        see what's going on
 *    /bot mass_spawn <count> [team]   spawn a ring of bots, named randomly from config/pvpbotcmd_names.txt
 *    /bot ffa start [teams] / stop / restart / stats
 *    /bot difficulty <1-6 or name>    bulk-set the difficulty preset
 *    /bot playstyle <name>            bulk-set the playstyle preset (independent of difficulty)
 *    /bot gui                         opens the settings menu (chest-style, no client mod needed)
 *    /bot options / help / reload
 *
 * Every setting below is also its own command: /bot <name> shows it, /bot <name> <value> sets it.
 */
public class PvpBotMod implements ModInitializer {

    // ---------------------------------------------------------------- settings

    static final Map<String, Opt> OPTS = new LinkedHashMap<>();

    /** These describe server-wide/administrative behavior rather than one bot's fighting style, so they stay global. */
    static final Set<String> GLOBAL_ONLY_CMDS = Set.of(
            "opsonly", "massmax", "ffaleave", "taunts", "focuschance", "focusmin", "focusmax",
            "autotarget", "findrange", "botfight");

    /** Per-bot difficulty/playstyle labels, for display only — the actual values live in each Opt's perBot map. */
    static final Map<String, String> BOT_DIFFICULTY = new HashMap<>();
    static final Map<String, String> BOT_PLAYSTYLE = new HashMap<>();

    static final class Opt {
        final String cmd;
        final String key;
        final double min;
        final double max;
        final boolean bool;
        final String desc;
        final String[] choices;
        double value;
        final Map<String, Double> perBot = new HashMap<>();

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

        /** This bot's own value if it has an override, otherwise the shared global default. */
        double v(String botName) {
            Double o = botName == null ? null : perBot.get(botName);
            return o != null ? o : value;
        }

        boolean on(String botName) {
            return v(botName) >= 0.5;
        }

        int asInt(String botName) {
            return (int) Math.round(v(botName));
        }

        boolean hasOverride(String botName) {
            return botName != null && perBot.containsKey(botName);
        }

        void setFor(String botName, double newValue) {
            perBot.put(botName, Math.max(min, Math.min(max, newValue)));
        }

        void clearFor(String botName) {
            perBot.remove(botName);
        }

        String show() {
            return show(value);
        }

        String show(String botName) {
            return show(v(botName));
        }

        private String show(double v) {
            if (choices != null) {
                return choices[(int) Math.round(v)];
            }
            if (bool) {
                return v >= 0.5 ? "on" : "off";
            }
            if (v == Math.rint(v)) {
                return Long.toString(Math.round(v));
            }
            return Double.toString(v);
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
            "blocks: melee reach");
    static final Opt BUNNY_HOP = opt("bunnyhop", "bunny_hop", 1, 0, 1, true,
            "bunny hop while closing a gap");
    static final Opt HOP_STOP = opt("hopstop", "hop_stop_distance", 3.5, 1.0, 8.0, false,
            "stop hopping within this many blocks of the target");
    static final Opt REVENGE = opt("revenge", "revenge", 1, 0, 1, true,
            "whoever hits a bot becomes its target");
    static final Opt EAT_HEARTS = opt("eat", "eat_below_hearts", 5.0, 0, 10, false,
            "run and eat once health drops to or below this many hearts (0 = never)");
    static final Opt EAT_COUNT = opt("eatcount", "eat_count", 3, 1, 10, false,
            "food items eaten per retreat, minimum");
    static final Opt EAT_UNTIL_HEARTS = opt("eatuntil", "eat_until_hearts", 9, 0, 20, false,
            "keep eating past eatcount until health reaches this many hearts (0 = ignore)");
    static final Opt HUNGER_EAT_LEVEL = opt("hungereat", "hunger_eat_level", 6, 0, 20, false,
            "run and eat if the hunger bar drops to or below this (0 = never)");
    static final Opt EAT_FULL_SPEED = opt("eatfullspeed", "eat_full_speed", 1, 0, 1, true,
            "ignore vanilla's eating slowdown: keep sprinting while eating");
    static final Opt DURABILITY_SWAP = opt("durabilityswap", "durability_swap", 1, 0, 1, true,
            "swap in a spare weapon/armor piece when the current one is nearly broken");
    static final Opt DURABILITY_THRESHOLD = opt("durabilitythreshold", "durability_swap_percent", 15, 1, 100, false,
            "percent durability remaining that counts as \"nearly broken\"");
    static final Opt FLEE_DIST = opt("flee", "flee_distance", 8.0, 3.0, 30.0, false,
            "blocks to put between the bot and the target before it starts eating");
    static final Opt CRIT_CHANCE = opt("critchance", "crit_chance", 25, 0, 100, false,
            "percent chance to go for a jump-crit when it can");
    static final Opt CRIT_RANGE = opt("critrange", "crit_range", 4.0, 1.0, 6.0, false,
            "blocks within which a crit can be attempted");
    static final Opt FALL_CRIT = opt("fallcrit", "fall_crit", 1, 0, 1, true,
            "always crit while already falling and in range");
    static final Opt CRIT_CHAIN_CHANCE = opt("critchainchance", "crit_chain_chance", 40, 0, 100, false,
            "percent chance a crit against a vulnerable target starts a guaranteed-crit burst");
    static final Opt CRIT_CHAIN_MIN = opt("critchainmin", "crit_chain_min_ticks", 20, 0, 200, false,
            "shortest a guaranteed-crit burst lasts");
    static final Opt CRIT_CHAIN_MAX = opt("critchainmax", "crit_chain_max_ticks", 60, 0, 200, false,
            "longest a guaranteed-crit burst lasts");
    static final Opt AUTO_TARGET = opt("autotarget", "auto_target", 1, 0, 1, true,
            "idle bots pick the nearest survival player");
    static final Opt FIND_RANGE = opt("findrange", "find_range", 24, 4, 128, false,
            "blocks within which auto-target and focus fire look for a target");
    static final Opt AIM = opt("aim", "aim_ticks", 3, 0, 20, false,
            "HeroBot look-delta ticks (lower = snappier aim)");
    static final Opt REACT_MIN = opt("reactmin", "reaction_min_ticks", 3, 0, 40, false,
            "shortest reaction delay before a swing, in ticks");
    static final Opt REACT_MAX = opt("reactmax", "reaction_max_ticks", 7, 0, 40, false,
            "longest reaction delay before a swing, in ticks");
    static final Opt PREDICT_TICKS = opt("predictaim", "predictive_aim_ticks", 3, 0, 10, false,
            "ticks of target movement led when aim ticks is low (0 = no lead)");
    static final Opt AIM_CONE = opt("aimcone", "aim_cone_degrees", 4, 0, 20, false,
            "skip re-aiming while already within this many degrees of the target");
    static final Opt AIM_SPREAD = opt("aimspread", "aim_spread_chance", 40, 0, 100, false,
            "percent chance to aim somewhere on the target's body other than its head");
    static final Opt OVERSHOOT_CHANCE = opt("overshootchance", "aim_overshoot_chance", 30, 0, 100, false,
            "percent chance per aim update to flick past the target and correct next tick");
    static final Opt OVERSHOOT_DEGREES = opt("overshootdegrees", "aim_overshoot_degrees", 15, 1, 60, false,
            "how far past the target an overshoot flick swings");
    static final Opt BOT_FIGHT = opt("botfight", "bots_fight_each_other", 1, 0, 1, true,
            "PvP bots fight each other (not just human players)");
    static final Opt WEB_ESCAPE = opt("webescape", "web_escape", 1, 0, 1, true,
            "use a water bucket to break out of a cobweb, if carried");
    static final Opt WEB_BREAK = opt("webbreak", "web_break", 1, 0, 1, true,
            "mine out of a cobweb with the best weapon if no water bucket is carried");
    static final Opt STRAFE = opt("strafe", "strafe", 1, 0, 1, true,
            "strafe left/right while close to the target");
    static final Opt STRAFE_TICKS = opt("strafeticks", "strafe_ticks", 12, 2, 40, false,
            "base ticks between strafe-side re-rolls");
    static final Opt COMBO_CHANCE = opt("combochance", "combo_chance", 50, 0, 100, false,
            "percent chance to start a combo after a hit lands");
    static final Opt COMBO_STAP_CHANCE = opt("combostap", "combo_stap_weight", 30, 0, 100, false,
            "percent chance to pick s-tap when a combo starts");
    static final Opt COMBO_WTAP_CHANCE = opt("combowtap", "combo_wtap_weight", 30, 0, 100, false,
            "percent chance to pick w-tap when a combo starts");
    static final Opt COMBO_UPPERCUT_CHANCE = opt("combouppercut", "combo_uppercut_weight", 20, 0, 100, false,
            "percent chance to pick uppercut when a combo starts");
    static final Opt COMBO_STRAFECOMBO_CHANCE = opt("combostrafecombo", "combo_strafecombo_weight", 20, 0, 100, false,
            "percent chance to pick strafecombo when a combo starts");
    static final Opt COMBO_TAP_TICKS = opt("combotapticks", "combo_tap_ticks", 2, 1, 10, false,
            "ticks an s-tap/w-tap pause lasts");
    static final Opt COMBO_UPPERCUT_RANGE = opt("uppercutrange", "combo_uppercut_range", 3.5, 1.0, 8.0, false,
            "blocks within which an uppercut can be started");
    static final Opt COMBO_UPPERCUT_WINDOW = opt("uppercutwindow", "combo_uppercut_window", 6, 1, 20, false,
            "ticks the uppercut's rising hit window stays open");
    static final Opt COMBO_STRAFE_SWITCH_TICKS = opt("combostrafeticks", "combo_strafecombo_ticks", 6, 2, 30, false,
            "ticks between side switches during strafecombo");
    static final Opt MIX_CHANCE = opt("mixchance", "combo_mix_chance", 30, 0, 100, false,
            "percent chance a started combo is MixCombo instead of one fixed type");
    static final Opt MIX_MAXHITS = opt("mixmaxhits", "combo_mix_max_hits", 5, 1, 20, false,
            "hits a MixCombo series runs before ending");
    static final Opt MIX_SWITCHCHANCE = opt("mixswitchchance", "combo_mix_switch_chance", 60, 0, 100, false,
            "percent chance MixCombo re-rolls its type on each hit");
    static final Opt COMBOHITS_MINHIT = opt("combohitsmin", "combo_hits_escape_min", 8, 1, 60, false,
            "shortest run of hits taken before RandomRepeatedComboHits can trigger");
    static final Opt COMBOHITS_MAXHIT = opt("combohitsmax", "combo_hits_escape_max", 15, 1, 60, false,
            "longest run of hits taken before RandomRepeatedComboHits can trigger");
    static final Opt COMBOHITS_ESCAPECHANCE = opt("combohitsescapechance", "combo_hits_escape_chance", 80, 0, 100, false,
            "percent chance the streak actually triggers an escape once the threshold is hit");
    static final Opt COMBOHITS_WINDCHARGECHANCE = opt("windchargechance", "combo_hits_windcharge_weight", 34, 0, 100, false,
            "percent chance the RandomRepeatedComboHits escape is a wind charge launch");
    static final Opt WINDCHARGE_HEAL_CHANCE = opt("windchargehealchance", "windcharge_heal_chance", 20, 0, 100, false,
            "percent chance a low-health/hungry retreat uses an item escape (wind charge or ender pearl) instead of just running");
    static final Opt WINDCHARGE_WAIT_TICKS = opt("windchargewaitticks", "windcharge_wait_ticks", 5, 1, 20, false,
            "ticks a wind-charge escape holds still (still drifting away, no hop) before jumping and throwing together");
    static final Opt WINDCHARGE_BLOCKED_TICKS = opt("windchargeblockedticks", "windcharge_blocked_ticks", 10, 2, 60, false,
            "consecutive stuck ticks while fleeing before it tries a wind charge over the obstacle");
    static final Opt COMBOHITS_WEBCHANCE = opt("escapewebchance", "combo_hits_webtrap_weight", 33, 0, 100, false,
            "percent chance the RandomRepeatedComboHits escape is the panic web trap");
    static final Opt COMBOHITS_RUNCHANCE = opt("escaperunchance", "combo_hits_run_weight", 33, 0, 100, false,
            "percent chance the RandomRepeatedComboHits escape is just running away to eat");
    static final Opt JUMP_CHANCE = opt("jumpchance", "jump_chance", 15, 0, 100, false,
            "percent chance, every quarter second, that a bot close to its target jumps");
    static final Opt JUMP_RANGE = opt("jumprange", "jump_range", 5.0, 1.0, 12.0, false,
            "blocks within which bots randomly jump on their target");
    static final Opt JUMP_RESET = opt("jumpreset", "jump_reset_chance", 20, 0, 100, false,
            "percent chance to jump right after taking a hit, to shake off the knockback");
    static final Opt FLINCH_CHANCE = opt("flinchchance", "flinch_chance", 35, 0, 100, false,
            "percent chance to hesitate a moment before swinging back after taking a hit");
    static final Opt FLINCH_TICKS_MIN = opt("flinchticksmin", "flinch_ticks_min", 4, 0, 40, false,
            "shortest a flinch delay lasts");
    static final Opt FLINCH_TICKS_MAX = opt("flinchticksmax", "flinch_ticks_max", 10, 0, 40, false,
            "longest a flinch delay lasts");
    static final Opt SHIELD_CHANCE = opt("shield", "shield_chance", 45, 0, 100, false,
            "percent chance to raise a shield when the target is about to swing and in range (needs a shield in the inventory)");
    static final Opt SHIELD_RANGE = opt("shieldrange", "shield_range", 1.5, 0.5, 6.0, false,
            "blocks within which the bot will consider raising a shield (it never shields while comboing)");
    static final Opt SHIELD_TICKS = opt("shieldticks", "shield_ticks", 10, 2, 100, false,
            "shortest a shield hold lasts (a shield needs about 5 ticks to start blocking, so keep this well above that)");
    static final Opt SHIELD_TICKS_MAX = opt("shieldticksmax", "shield_ticks_max", 50, 2, 150, false,
            "longest a shield hold lasts; each hold is randomized between shieldticks and this");
    static final Opt POSTSWING_SHIELD_CHANCE = opt("postswingshield", "postswing_shield_chance", 80, 0, 100, false,
            "percent chance the bot raises its shield right after landing a hit (only when it isn't running a combo)");
    static final Opt POSTSWING_SHIELD_TICKS_MIN = opt("postswingshieldticksmin", "postswing_shield_ticks_min", 8, 2, 100, false,
            "shortest a post-swing shield hold lasts");
    static final Opt POSTSWING_SHIELD_TICKS_MAX = opt("postswingshieldticksmax", "postswing_shield_ticks_max", 13, 2, 150, false,
            "longest a post-swing shield hold lasts");
    static final Opt STUN_CHANCE = opt("stun", "shield_stun_chance", 35, 0, 100, false,
            "percent chance to swap to an axe when the target raises a shield, to disable it");
    static final Opt HIT_WEB = opt("hitweb", "hit_web_chance", 25, 0, 100, false,
            "percent chance that a landed hit is followed by really placing a cobweb at the target's feet (needs cobwebs in the inventory)");
    static final Opt WEB_CRIT = opt("webcrit", "guaranteed_web_crit", 1, 0, 1, true,
            "guaranteed critical hit while the target is webbed; breaks an enclosing web open first if the target is boxed in");
    static final Opt WEB_AVOID = opt("webavoid", "web_avoidance", 1, 0, 1, true,
            "steer around cobwebs while closing in or fleeing (the target's own cell is never avoided)");
    static final Opt PUNISH_CRIT_CHANCE = opt("punishcritchance", "punish_crit_chance", 50, 0, 100, false,
            "percent chance to guarantee a crit right back after the target lands a hit on the bot");
    static final Opt MACE_AWARE = opt("maceaware", "mace_awareness", 1, 0, 1, true,
            "raise the shield immediately if the target is holding a mace and airborne above the bot");
    static final Opt RECOVERY_CHANCE = opt("recoverychance", "recovery_punish_chance", 60, 0, 100, false,
            "percent chance to skip the normal reaction delay right after the target's weapon goes on cooldown");
    static final Opt TOTEM_PUNISH_TICKS = opt("totempunishticks", "totem_punish_ticks", 40, 0, 200, false,
            "ticks of guaranteed crits right after the target's totem of undying pops");
    static final Opt PRECISION_LOCK = opt("precisionlock", "precision_lock", 1, 0, 1, true,
            "aim true (no wobble/spread/overshoot) on the exact tick a swing actually fires");
    static final Opt ARCHER_RUSH = opt("archerrush", "archer_rush", 1, 0, 1, true,
            "tighten up and rush in if the target draws a bow or crossbow");
    static final Opt WEB_LIFETIME = opt("webtime", "web_lifetime_ticks", 100, 0, 1200, false,
            "ticks before a cobweb dropped by a bot disappears again (0 = never)");
    static final Opt WEB_LEAD_TICKS = opt("webleadticks", "web_lead_ticks", 3, 0, 10, false,
            "ticks of target movement a placed web leads by, so a moving target doesn't just walk out of it");
    static final Opt WEB_ZONE_AWARE = opt("webzone", "web_zone_awareness", 1, 0, 1, true,
            "flee toward a nearby existing cobweb, when there is one, to slow the chase");
    static final Opt WEB_ZONE_RADIUS = opt("webzoneradius", "web_zone_radius", 8, 2, 20, false,
            "blocks searched for a nearby cobweb to flee toward");
    static final Opt STUCK = opt("stuck", "stuck_detection", 1, 0, 1, true,
            "hop and sidestep if barely moving while trying to close a gap");
    static final Opt TOTEM = opt("totem", "totem_offhand", 1, 0, 1, true,
            "swap a totem of undying into the off-hand at very low health, and back out again after recovering");
    static final Opt TOTEM_HEARTS = opt("totemhearts", "totem_switch_hearts", 2.0, 0, 10, false,
            "health at or below which the totem swap happens");
    static final Opt MASS_MAX = opt("massmax", "mass_spawn_max", 20, 1, 100, false,
            "most bots /bot mass_spawn will queue at once");
    static final Opt KEEP_DISTANCE = opt("keepdistance", "keep_distance", 2.8, 0, 6, false,
            "blocks the bot tries to hold from the target (0 = always close in)");
    static final Opt PRESSURE_KEEP_DIST = opt("pressurekeep", "pressure_keep_distance", 0.5, 0, 3, false,
            "tighter keep-distance used while the target is eating or fleeing");
    static final Opt STRAFE_CHANCE = opt("strafechance", "strafe_chance", 60, 0, 100, false,
            "percent chance to switch (or hold) strafe side on each roll");
    static final Opt MISS_CHANCE = opt("miss", "miss_chance", 10, 0, 100, false,
            "percent chance a swing whiffs at air instead of landing");
    static final Opt WOBBLE = opt("wobble", "aim_wobble_degrees", 2, 0, 15, false,
            "degrees of random aim wobble");
    static final Opt VOID_AWARE = opt("voidaware", "void_lava_awareness", 1, 0, 1, true,
            "steer around ledges, void and lava on the way to (and back from) the target");
    static final Opt COCOON = opt("cocoon", "web_cocoon", 1, 0, 1, true,
            "web itself in and eat inside the cocoon at very low health");
    static final Opt COCOON_HEARTS = opt("cocoonhearts", "cocoon_hearts", 3.0, 0, 10, false,
            "health at or below which the cocoon is used instead of just running");
    static final Opt WEBTRAP_CHANCE = opt("webtrapchance", "panic_webtrap_chance", 40, 0, 100, false,
            "percent chance to lay a wall of cobwebs behind itself before running, at panic health");
    static final Opt WEBTRAP_HEARTS = opt("webtraphearts", "panic_webtrap_hearts", 2.0, 0, 10, false,
            "health at or below which the panic web trap can trigger");
    static final Opt WEBTRAP_COUNT = opt("webtrapcount", "panic_webtrap_count", 2, 2, 6, false,
            "cobwebs laid by the panic web trap");
    static final Opt RANDOMWEB_CHANCE = opt("randomwebchance", "random_web_chance", 0, 0, 100, false,
            "disabled: random web placement with no hit needed (kept at 0)");
    static final Opt RANDOMWEB_MINDIST = opt("randomwebmindist", "random_web_min_dist", 0.5, 0.2, 3.0, false,
            "shortest distance for a random web placement");
    static final Opt RANDOMWEB_MAXDIST = opt("randomwebmaxdist", "random_web_max_dist", 1.4, 0.5, 4.0, false,
            "longest distance for a random web placement");
    static final Opt RANDOMWEB_COOLDOWN = opt("randomwebcooldown", "random_web_cooldown_ticks", 60, 10, 600, false,
            "ticks between random web placement attempts");
    static final Opt POTIONS = opt("potions", "splash_potions", 1, 0, 1, true,
            "bots use splash potions of healing/speed/strength/fire resistance");
    static final Opt POTION_HEARTS = opt("potionhearts", "potion_heal_hearts", 6.0, 0, 10, false,
            "health at or below which a healing splash potion is thrown");
    static final Opt POTION_HEARTS2 = opt("potionhearts2", "potion_heal_hearts_double", 3.0, 0, 10, false,
            "health at or below which two healing potions are queued instead of one");
    static final Opt POTION_DELAY = opt("potiondelay", "potion_delay_ticks", 200, 20, 2400, false,
            "ticks before another potion run can start after one finishes");
    static final Opt LOOT = opt("loot", "loot_pickup", 1, 0, 1, true,
            "bots walk to dropped items they don't have when no fight is close");
    static final Opt LOOT_RANGE = opt("lootrange", "loot_range", 12, 3, 48, false,
            "blocks within which bots notice dropped items");
    static final Opt EXP_BOTTLE = opt("expbottle", "exp_bottle_mending_repair", 1, 0, 1, true,
            "throw an experience bottle at itself to repair mending armor when all of it is low on durability");
    static final Opt EXP_BOTTLE_THRESHOLD = opt("expbottlethreshold", "exp_bottle_durability_percent", 15, 1, 100, false,
            "percent durability remaining, for every mending armor piece, that triggers this");
    static final Opt FOCUS_CHANCE = opt("focuschance", "focus_chance", 60, 0, 100, false,
            "percent chance a bot joins its team's focus target (0 = no focus fire)");
    static final Opt FOCUS_MIN = opt("focusmin", "focus_min_ticks", 100, 20, 2400, false,
            "shortest time a team keeps one focus target");
    static final Opt FOCUS_MAX = opt("focusmax", "focus_max_ticks", 300, 20, 2400, false,
            "longest time a team keeps one focus target");
    static final Opt OPS_ONLY = opt("opsonly", "ops_only_commands", 1, 0, 1, true,
            "only operators (those who can use /gamemode) may run /bot");
    static final Opt TAUNTS = opt("taunts", "taunts", 0, 0, 1, true,
            "bots post short chat lines when they win a kill or the match");
    static final Opt FFA_LEAVE = opt("ffaleave", "ffa_bot_leave_on_death", 1, 0, 1, true,
            "an FFA match turns HeroBot's botleaveondeath on (and off again afterwards)");

    // ---- difficulty presets: /bot difficulty <1-6 or name> sets all of these at once
    static final String[] LEVEL_NAMES = {"beginner", "easy", "normal", "hard", "insane", "perfect"};
    static final List<String> DIFFICULTY_CHOICES =
            List.of("1", "2", "3", "4", "5", "6", "beginner", "easy", "normal", "hard", "insane", "perfect");
    static final Opt[] PRESET_OPTS = {
            AIM, REACT_MIN, REACT_MAX, CRIT_CHANCE, STRAFE, STRAFE_TICKS,
            COMBO_CHANCE, MIX_CHANCE, BUNNY_HOP, EAT_HEARTS, FALL_CRIT, JUMP_CHANCE, JUMP_RESET,
            SHIELD_CHANCE, STUN_CHANCE, STRAFE_CHANCE, MISS_CHANCE, WOBBLE, FOCUS_CHANCE, POTIONS,
            POTION_HEARTS, SHIELD_TICKS, SHIELD_RANGE
    };
    // aim rmin rmax crit strafe sticks combochance mixchance hop eat fcrit jump jreset shield stun
    // schance miss wobble focus potions phearts shticks shrange
    static final double[][] PRESETS = {
            /* 1 beginner */ {8, 8, 16, 0, 0, 14, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 30, 6, 0, 0, 5, 10, 1.0},
            /* 2 easy     */ {5, 5, 10, 10, 1, 16, 20, 10, 0, 3, 0, 5, 5, 20, 10, 25, 20, 4, 30, 0, 5, 14, 1.2},
            /* 3 normal   */ {3, 3, 7, 25, 1, 12, 45, 25, 1, 5, 1, 15, 20, 45, 35, 55, 10, 2, 60, 1, 6, 20, 1.5},
            /* 4 hard     */ {2, 1, 4, 45, 1, 9, 65, 40, 1, 6, 1, 25, 35, 65, 60, 75, 4, 1, 80, 1, 7, 20, 1.5},
            /* 5 insane   */ {1, 0, 2, 70, 1, 6, 85, 60, 1, 7, 1, 35, 55, 85, 85, 90, 1, 0, 95, 1, 8, 18, 1.5},
            /* 6 perfect  */ {0, 0, 0, 100, 1, 6, 100, 80, 1, 7, 1, 50, 100, 100, 100, 100, 0, 0, 100, 1, 8, 12, 1.5},
    };
    static String difficultyName = "normal";

    // ---- playstyle presets: /bot playstyle <name>, independent of difficulty (touches a different set of opts)
    static final String[] PLAYSTYLE_NAMES = {"aggressive", "defensive", "combo"};
    static final List<String> PLAYSTYLE_CHOICES = List.of("aggressive", "defensive", "combo");
    static final Opt[] PLAYSTYLE_OPTS = {
            KEEP_DISTANCE, STRAFE_CHANCE, CRIT_CHAIN_CHANCE, EAT_HEARTS, POTION_HEARTS,
            COMBO_CHANCE, MIX_CHANCE, JUMP_CHANCE, STUN_CHANCE, COCOON_HEARTS, HIT_WEB
    };
    // keepdist strafe% critchain% eat pot combochance mixchance jump% stun% cocoon hitweb%
    static final double[][] PLAYSTYLES = {
            /* aggressive */ {1.8, 75, 55, 3, 4, 55, 20, 25, 45, 2, 35},
            /* defensive  */ {3.5, 45, 30, 7, 8, 25, 10, 8, 25, 4, 20},
            /* combo      */ {2.2, 20, 70, 5, 6, 85, 55, 5, 60, 3, 30},
    };
    static String playstyleName = "none";

    static final double STRAFE_MAX_DIST = 6.0;
    static final double HOP_RESUME_MARGIN = 1.0;
    static final int FLEE_MAX_TICKS = 80;
    static final int EAT_TIMEOUT_TICKS = 60;
    static final int EAT_COOLDOWN_TICKS = 60;
    static final int CRIT_TIMEOUT_TICKS = 25;
    static final int WEB_BREAK_TIMEOUT_TICKS = 80;
    static final double POTION_SAFE_DIST = 5.0;
    static final double POTION_HEAL_CLOSE_DIST = 2.0; // a healing potion may still be thrown this close (others may not)
    static final double KEEP_BAND = 0.5;
    static final double LOOT_FIGHT_DIST = 10.0;
    static final int PEARL_TIMEOUT_TICKS = 40;
    static final double PEARL_TELEPORT_DIST = 3.0;

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
        final boolean massSpawned;
        int waited;

        Pending(String owner, boolean quiet, String team, Vec3 tpPos, boolean massSpawned) {
            this.owner = owner;
            this.quiet = quiet;
            this.team = team;
            this.tpPos = tpPos;
            this.massSpawned = massSpawned;
        }
    }

    /** One bot waiting to be spawned by /bot mass_spawn (they are spawned a few ticks apart). */
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

    enum Phase { FIGHT, FLEE, EAT, COCOON, POTION, WEBTRAP, WINDESCAPE, PEARLESCAPE }

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
        int botHurtStamp = Integer.MIN_VALUE; // target's hurt-timestamp on the bot, for the combo-hits-escape counter
        int placeStage;
        int placeStart;
        int placeCooldown;
        BlockPos placeCell;
        boolean placeRandom;      // true while placing a random web (no hit needed), false for a hit-web
        boolean placeRetried;     // one retry allowed per web-placement attempt
        int nextRandomWebTick;
        int strafeDir;
        int nextStrafeTick;
        int webStage;
        int webStart;
        int nextWebTick;
        int webSlot;
        BlockPos webPos;
        int keepMode = 1;         // 1 = closing in, -1 = backing off, 0 = holding the gap
        boolean edgeHold;         // standing at a ledge / lava instead of walking on
        int fleeFor;              // 0 = running away to eat, 1 = running away to throw potions
        int fleeStuckTicks;       // consecutive ticks of barely moving while fleeing (wall in the way)
        double fleeLastX = Double.NaN;
        double fleeLastZ = Double.NaN;
        int thrownMask;           // potion kinds already thrown in this run (bit per kind)
        int potionStage;
        int potionStart;
        int potionKind;
        int nextPotionTick;
        int cocoonStage;
        int cocoonStart;
        int cocoonRetries;
        BlockPos cocoonCheckCell;
        boolean inCocoon;         // eating inside its own webs: don't try to escape them
        boolean webWasEating;     // web escape interrupted an EAT phase: resume eating (don't reset progress) once free
        int critChainUntil;       // while globalTick is below this, every possible crit is guaranteed

        // combo system
        int comboActive;          // 0 = no combo running
        int comboType = -1;       // 0 stap, 1 wtap, 2 uppercut, 3 strafecombo
        boolean comboMixed;
        int comboMixHitsLeft;
        boolean comboUppercutRising;
        int comboUppercutStart;
        int comboHitsStreak;        // consecutive hits taken from the target without landing one back
        int comboHitsThreshold = -1; // random threshold that triggers RandomRepeatedComboHits (-1 = not rolled yet)

        // panic web trap
        int webtrapStage;
        int webtrapStart;
        int webtrapPlaced;

        // wind charge escape: stage 0 = holding still (waiting), 1 = airborne (thrown), 2 unused
        int windStage;
        int windStart;

        // ender pearl escape
        int pearlStage;
        int pearlStart;
        Vec3 pearlFrom;

        // human-mistake state
        int flinchUntil;          // no swinging back until globalTick reaches this
        boolean overshotLast;     // the previous aim update overshot; this one must snap back cleanly

        // guaranteed web crit / boxed-in breakout
        int breakStage;
        int breakStart;
        BlockPos breakPos;

        // punish crit, recovery punish, totem punish
        boolean punishCrit;
        double lastTargetScale = 1;
        boolean targetHadTotem;

        // healing potions queued for the current potion run (double-heal at very low hearts)
        int healsRemaining;

        // weapon/armor durability swap
        int nextDurabilityCheck;
        int nextExpBottleTick;

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
            placeStage = 0;
            placeRandom = false;
            placeRetried = false;
            lastHp = -1;
            lastPos = null;
            keepMode = 1;
            edgeHold = false;
            fleeFor = 0;
            fleeStuckTicks = 0;
            fleeLastX = Double.NaN;
            fleeLastZ = Double.NaN;
            thrownMask = 0;
            potionStage = 0;
            healsRemaining = 0;
            cocoonStage = 0;
            cocoonRetries = 0;
            inCocoon = false;
            webWasEating = false;
            critChainUntil = 0;
            comboActive = 0;
            comboType = -1;
            comboMixed = false;
            comboMixHitsLeft = 0;
            comboUppercutRising = false;
            comboHitsStreak = 0;
            comboHitsThreshold = -1;
            webtrapStage = 0;
            windStage = 0;
            pearlStage = 0;
            pearlFrom = null;
            flinchUntil = 0;
            overshotLast = false;
            breakStage = 0;
            breakPos = null;
            punishCrit = false;
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
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("bot");
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
        root.then(Commands.literal("gui").executes(PvpBotMod::openGui));
        root.then(Commands.literal("teamgui").executes(ctx -> {
            TeamGui.open(ctx.getSource().getPlayerOrException());
            return 1;
        }));

        root.then(Commands.literal("status")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BOT_NAMES)
                        .executes(ctx -> status(ctx, StringArgumentType.getString(ctx, "name")))));

        root.then(Commands.literal("mass_spawn")
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                        .executes(ctx -> massSpawn(ctx, IntegerArgumentType.getInteger(ctx, "count"), null))
                        .then(Commands.argument("team", StringArgumentType.word())
                                .executes(ctx -> massSpawn(ctx,
                                        IntegerArgumentType.getInteger(ctx, "count"),
                                        StringArgumentType.getString(ctx, "team"))))));

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

        root.then(Commands.literal("playstyle")
                .executes(PvpBotMod::showPlaystyle)
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(PLAYSTYLE_CHOICES, builder))
                        .executes(ctx -> setPlaystyle(ctx, StringArgumentType.getString(ctx, "name")))));

        // One command per setting: /bot <name> shows it, /bot <name> <value> changes it.
        for (Opt o : OPTS.values()) {
            LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(o.cmd).executes(ctx -> showOpt(ctx, o));
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

        // Per-bot overrides: /bot <bot> <setting> [value], plus /bot <bot> difficulty|playstyle|reset.
        var botNode = Commands.argument("bot", StringArgumentType.word()).suggests(BOT_NAMES);
        botNode.then(Commands.literal("difficulty")
                .executes(ctx -> showBotDifficulty(ctx, StringArgumentType.getString(ctx, "bot")))
                .then(Commands.argument("level", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DIFFICULTY_CHOICES, builder))
                        .executes(ctx -> setBotDifficulty(ctx,
                                StringArgumentType.getString(ctx, "bot"),
                                StringArgumentType.getString(ctx, "level")))));
        botNode.then(Commands.literal("playstyle")
                .executes(ctx -> showBotPlaystyle(ctx, StringArgumentType.getString(ctx, "bot")))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(PLAYSTYLE_CHOICES, builder))
                        .executes(ctx -> setBotPlaystyle(ctx,
                                StringArgumentType.getString(ctx, "bot"),
                                StringArgumentType.getString(ctx, "name")))));
        botNode.then(Commands.literal("reset")
                .executes(ctx -> resetBot(ctx, StringArgumentType.getString(ctx, "bot"))));
        for (Opt o : OPTS.values()) {
            if (GLOBAL_ONLY_CMDS.contains(o.cmd)) {
                continue;
            }
            LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(o.cmd)
                    .executes(ctx -> showBotOpt(ctx, StringArgumentType.getString(ctx, "bot"), o));
            if (o.choices != null) {
                node.then(Commands.argument("value", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Arrays.asList(o.choices), builder))
                        .executes(ctx -> {
                            int idx = choiceIndex(o, StringArgumentType.getString(ctx, "value"));
                            if (idx < 0) {
                                ctx.getSource().sendFailure(Component.literal("Unknown value. Use one of: " + String.join(", ", o.choices)));
                                return 0;
                            }
                            return setBotOpt(ctx, StringArgumentType.getString(ctx, "bot"), o, idx);
                        }));
            } else if (o.bool) {
                node.then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> setBotOpt(ctx, StringArgumentType.getString(ctx, "bot"), o,
                                BoolArgumentType.getBool(ctx, "value") ? 1 : 0)));
            } else {
                node.then(Commands.argument("value", DoubleArgumentType.doubleArg(o.min, o.max))
                        .executes(ctx -> setBotOpt(ctx, StringArgumentType.getString(ctx, "bot"), o,
                                DoubleArgumentType.getDouble(ctx, "value"))));
            }
            botNode.then(node);
        }
        root.then(botNode);

        d.register(root);
    }

    static boolean checkBot(CommandContext<CommandSourceStack> ctx, String botName) {
        if (!BOTS.contains(botName)) {
            ctx.getSource().sendFailure(Component.literal(botName + " is not a PvP bot. Use /bot adopt " + botName + " first if you spawned it with /playerspawn."));
            return false;
        }
        return true;
    }

    static int showBotDifficulty(CommandContext<CommandSourceStack> ctx, String botName) {
        if (!checkBot(ctx, botName)) {
            return 0;
        }
        String d = BOT_DIFFICULTY.getOrDefault(botName, "(using the global default: " + difficultyName + ")");
        ctx.getSource().sendSuccess(() -> Component.literal(botName + " difficulty: " + d), false);
        return 1;
    }

    static int setBotDifficulty(CommandContext<CommandSourceStack> ctx, String botName, String raw) {
        if (!checkBot(ctx, botName)) {
            return 0;
        }
        String r = raw.trim().toLowerCase(Locale.ROOT);
        int level = -1;
        for (int i = 0; i < LEVEL_NAMES.length; i++) {
            if (LEVEL_NAMES[i].equals(r) || Integer.toString(i + 1).equals(r)) {
                level = i;
                break;
            }
        }
        if (level < 0) {
            ctx.getSource().sendFailure(Component.literal("Unknown difficulty. Use 1-6 or beginner, easy, normal, hard, insane, perfect."));
            return 0;
        }
        int lvl = level;
        for (int i = 0; i < PRESET_OPTS.length; i++) {
            PRESET_OPTS[i].setFor(botName, PRESETS[lvl][i]);
        }
        BOT_DIFFICULTY.put(botName, LEVEL_NAMES[lvl]);
        savePerBotConfig();
        ctx.getSource().sendSuccess(() -> Component.literal(botName + "'s difficulty set to " + LEVEL_NAMES[lvl] + "."), true);
        return 1;
    }

    static int showBotPlaystyle(CommandContext<CommandSourceStack> ctx, String botName) {
        if (!checkBot(ctx, botName)) {
            return 0;
        }
        String p = BOT_PLAYSTYLE.getOrDefault(botName, "(using the global default: " + playstyleName + ")");
        ctx.getSource().sendSuccess(() -> Component.literal(botName + " playstyle: " + p), false);
        return 1;
    }

    static int setBotPlaystyle(CommandContext<CommandSourceStack> ctx, String botName, String raw) {
        if (!checkBot(ctx, botName)) {
            return 0;
        }
        String r = raw.trim().toLowerCase(Locale.ROOT);
        int idx = -1;
        for (int i = 0; i < PLAYSTYLE_NAMES.length; i++) {
            if (PLAYSTYLE_NAMES[i].equals(r)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            ctx.getSource().sendFailure(Component.literal("Unknown playstyle. Use aggressive, defensive, or combo."));
            return 0;
        }
        int st = idx;
        for (int i = 0; i < PLAYSTYLE_OPTS.length; i++) {
            PLAYSTYLE_OPTS[i].setFor(botName, PLAYSTYLES[st][i]);
        }
        BOT_PLAYSTYLE.put(botName, PLAYSTYLE_NAMES[st]);
        savePerBotConfig();
        ctx.getSource().sendSuccess(() -> Component.literal(botName + "'s playstyle set to " + PLAYSTYLE_NAMES[st] + "."), true);
        return 1;
    }

    static int resetBot(CommandContext<CommandSourceStack> ctx, String botName) {
        if (!checkBot(ctx, botName)) {
            return 0;
        }
        for (Opt o : OPTS.values()) {
            o.clearFor(botName);
        }
        BOT_DIFFICULTY.remove(botName);
        BOT_PLAYSTYLE.remove(botName);
        savePerBotConfig();
        ctx.getSource().sendSuccess(() -> Component.literal(botName + "'s settings reset to the global defaults."), true);
        return 1;
    }

    static int showBotOpt(CommandContext<CommandSourceStack> ctx, String botName, Opt o) {
        if (!checkBot(ctx, botName)) {
            return 0;
        }
        String suffix = o.hasOverride(botName) ? "" : "   (using the global default)";
        ctx.getSource().sendSuccess(() -> Component.literal(botName + "'s " + o.key + " = " + o.show(botName) + suffix), false);
        return 1;
    }

    static int setBotOpt(CommandContext<CommandSourceStack> ctx, String botName, Opt o, double v) {
        if (!checkBot(ctx, botName)) {
            return 0;
        }
        o.setFor(botName, v);
        savePerBotConfig();
        ctx.getSource().sendSuccess(() -> Component.literal(botName + "'s " + o.key + " set to " + o.show(botName)), true);
        return 1;
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
        PENDING.put(name, new Pending(me.getName().getString(), false, null, null, false));
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
            src.sendFailure(Component.literal(name + " is not a PvP bot yet. If you spawned it with /playerspawn, run /bot adopt " + name + " first."));
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
            src.sendFailure(Component.literal(name + " is not a PvP bot yet. If you spawned it with /playerspawn, run /bot adopt " + name + " first."));
            return 0;
        }
        FIGHTS.remove(name);
        STOPPED.add(name);
        run(src.getServer(), "player " + name + " stop");
        src.sendSuccess(() -> Component.literal(name + " stopped. It won't pick targets again until you use /bot fight " + name + "."), false);
        return 1;
    }

    static int ping(CommandContext<CommandSourceStack> ctx, String name, int ms) {
        CommandSourceStack src = ctx.getSource();
        if (!BOTS.contains(name)) {
            src.sendFailure(Component.literal(name + " is not a PvP bot yet. If you spawned it with /playerspawn, run /bot adopt " + name + " first."));
            return 0;
        }
        run(src.getServer(), "player " + name + " ping " + ms);
        src.sendSuccess(() -> Component.literal(name + " ping set to " + ms + " ms."), false);
        return 1;
    }

    static int remove(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack src = ctx.getSource();
        if (!BOTS.contains(name)) {
            src.sendFailure(Component.literal(name + " is not a PvP bot yet. If you spawned it with /playerspawn, run /bot adopt " + name + " first."));
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
                    "/bot " + o.cmd + " = " + o.show() + "     (" + o.desc + ")"), false);
        }
        return OPTS.size();
    }

    static int help(CommandContext<CommandSourceStack> ctx) {
        String[] lines = {
                "/bot spawn <name>     |    mass_spawn <count> [team]     |    adopt <name>",
                "/bot fight <name> [target]    |    stop <name>    |   remove <name>    |    clear    |    list",
                "/bot status <name>    |    ping <name> <ms>    |    reload    |    options    |    gui",
                "/bot ffa start [teams]    |    ffa stop    |    ffa restart    |    ffa stats",
                "/bot difficulty <1-6 or beginner/easy/normal/hard/insane/perfect> (perfect is brutal)",
                "Teams use vanilla /team (/team add red, /team join red Bot1). Bots leave creative players alone.",
                "Every setting is /bot <setting> [value]. Run /bot options to see them all, or /bot gui for a menu."
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

    static int openGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer me = ctx.getSource().getPlayerOrException();
        Gui.open(me);
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

    static int massSpawn(CommandContext<CommandSourceStack> ctx, int count, String team) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer me = src.getPlayerOrException();
        MinecraftServer server = src.getServer();
        if (!herobotPresent()) {
            src.sendFailure(Component.literal("HeroBot is not installed: /playerspawn and /player were not found."));
            return 0;
        }
        if (count > MASS_MAX.asInt()) {
            src.sendFailure(Component.literal("At most " + MASS_MAX.asInt() + " bots at once. Change it with /bot massmax <number>."));
            return 0;
        }
        Set<String> used = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (MassJob job : MASS_QUEUE) {
            used.add(job.name);
        }
        List<String> names = pickRandomNames(server, count, used);
        if (names.isEmpty()) {
            src.sendFailure(Component.literal("No usable names found (or generated) in " + namesPath().getFileName() + "."));
            return 0;
        }
        queueMassJobs(server, me, names, team);
        int total = names.size();
        String shown = String.join(", ", names.subList(0, Math.min(3, names.size()))) + (names.size() > 3 ? ", ..." : "");
        src.sendSuccess(() -> Component.literal("Spawning " + total + " bots: " + shown
                + (team != null ? " on team " + team : "")), false);
        return total;
    }

    /**
     * Picks up to count unique, currently-unused names from config/pvpbotcmd_names.txt (generated with
     * defaults on first use), in random order. If the list can't supply enough unique names, names already
     * used in this batch are re-picked with a number appended so the requested count is still met.
     */
    static List<String> pickRandomNames(MinecraftServer server, int count, Set<String> used) {
        List<String> pool = new ArrayList<>(loadNamePool());
        java.util.Collections.shuffle(pool, ThreadLocalRandom.current());
        List<String> picked = new ArrayList<>();
        for (String name : pool) {
            if (picked.size() >= count) {
                break;
            }
            if (isNameFree(server, used, name)) {
                used.add(name);
                picked.add(name);
            }
        }
        int guard = 0;
        while (picked.size() < count && !pool.isEmpty() && guard++ < count * 20) {
            String base = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            for (int n = 2; n < 100; n++) {
                String candidate = base + n;
                if (candidate.length() <= 16 && isNameFree(server, used, candidate)) {
                    used.add(candidate);
                    picked.add(candidate);
                    break;
                }
            }
        }
        return picked;
    }

    static boolean isNameFree(MinecraftServer server, Set<String> used, String name) {
        return name.length() <= 16 && !used.contains(name) && !PENDING.containsKey(name)
                && server.getPlayerList().getPlayerByName(name) == null;
    }

    static final String[] DEFAULT_NAMES = {
            "Ash", "Blaze", "Cinder", "Drake", "Ember", "Frost", "Glint", "Havoc", "Ivory", "Jinx",
            "Kilo", "Lynx", "Maverick", "Nova", "Onyx", "Prowl", "Quartz", "Raven", "Slate", "Talon",
            "Umbra", "Vex", "Wraith", "Xeno", "Yeti", "Zephyr", "Ashen", "Bramble", "Crux", "Dusk",
            "Echo", "Flint", "Grit", "Hollow", "Iron", "Jolt", "Karma", "Lurk", "Marrow", "Night",
            "Orbit", "Pyre", "Quill", "Rift", "Storm", "Thorn", "Ursa", "Vortex", "Warden", "Zed"
    };

    /** Reads config/pvpbotcmd_names.txt (one name per line, '#' comments allowed); generates it with defaults if missing. */
    static List<String> loadNamePool() {
        Path path = namesPath();
        if (!Files.exists(path)) {
            try (Writer out = Files.newBufferedWriter(path)) {
                out.write("# PvPBot mass_spawn draws names from this file, one per line, in random order.\n");
                out.write("# Edit it however you like; blank lines and lines starting with # are ignored.\n");
                for (String n : DEFAULT_NAMES) {
                    out.write(n);
                    out.write('\n');
                }
            } catch (IOException ignored) {
                // fall through: still usable in-memory even if the file couldn't be written
            }
            return new ArrayList<>(Arrays.asList(DEFAULT_NAMES));
        }
        List<String> names = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(path)) {
                String n = line.trim();
                if (!n.isEmpty() && !n.startsWith("#") && n.length() <= 16) {
                    names.add(n);
                }
            }
        } catch (IOException ignored) {
        }
        return names.isEmpty() ? new ArrayList<>(Arrays.asList(DEFAULT_NAMES)) : names;
    }

    static Path namesPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("pvpbotcmd_names.txt");
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
            ctx.getSource().sendFailure(Component.literal("Unknown difficulty. Use 1-6 or beginner, easy, normal, hard, insane, perfect."));
            return 0;
        }
        setDifficultyLevel(level);
        String msg = "Difficulty set to " + LEVEL_NAMES[level] + " (level " + (level + 1) + " of 6).";
        ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
        return 1;
    }

    /** Same effect as setDifficulty, callable without a command context (used by the GUI). */
    static void setDifficultyLevel(int level) {
        for (int i = 0; i < PRESET_OPTS.length; i++) {
            Opt o = PRESET_OPTS[i];
            o.value = Math.max(o.min, Math.min(o.max, PRESETS[level][i]));
        }
        difficultyName = LEVEL_NAMES[level];
        saveConfig();
    }

    static boolean isPlaystyleOpt(Opt o) {
        for (Opt p : PLAYSTYLE_OPTS) {
            if (p == o) {
                return true;
            }
        }
        return false;
    }

    static int showPlaystyle(CommandContext<CommandSourceStack> ctx) {
        String text = "Playstyle: " + playstyleName;
        if (playstyleName.equals("custom")) {
            text += " (a setting was changed after choosing one)";
        }
        String shown = text;
        ctx.getSource().sendSuccess(() -> Component.literal(shown), false);
        return 1;
    }

    static int setPlaystyle(CommandContext<CommandSourceStack> ctx, String raw) {
        String r = raw.trim().toLowerCase(Locale.ROOT);
        int idx = -1;
        for (int i = 0; i < PLAYSTYLE_NAMES.length; i++) {
            if (PLAYSTYLE_NAMES[i].equals(r)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            ctx.getSource().sendFailure(Component.literal("Unknown playstyle. Use aggressive, defensive, or combo."));
            return 0;
        }
        setPlaystyleIndex(idx);
        String msg = "Playstyle set to " + PLAYSTYLE_NAMES[idx] + ".";
        ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
        return 1;
    }

    /** Same effect as setPlaystyle, callable without a command context (used by the GUI). */
    static void setPlaystyleIndex(int idx) {
        for (int i = 0; i < PLAYSTYLE_OPTS.length; i++) {
            Opt o = PLAYSTYLE_OPTS[i];
            o.value = Math.max(o.min, Math.min(o.max, PLAYSTYLES[idx][i]));
        }
        playstyleName = PLAYSTYLE_NAMES[idx];
        saveConfig();
    }

    static int showOpt(CommandContext<CommandSourceStack> ctx, Opt o) {
        ctx.getSource().sendSuccess(() -> Component.literal(o.key + " = " + o.show() + "   (" + o.desc + ")"), false);
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
            ctx.getSource().sendFailure(Component.literal("Unknown value. Use one of: " + String.join(", ", o.choices)));
            return 0;
        }
        return setOpt(ctx, o, idx);
    }

    static int setOpt(CommandContext<CommandSourceStack> ctx, Opt o, double v) {
        setOptValue(o, v);
        ctx.getSource().sendSuccess(() -> Component.literal(o.key + " set to " + o.show()), true);
        return 1;
    }

    /** Same effect as setOpt, callable without a command context (used by the GUI). */
    static void setOptValue(Opt o, double v) {
        o.value = Math.max(o.min, Math.min(o.max, v));
        if (isPresetOpt(o)) {
            difficultyName = "custom";
        }
        if (isPlaystyleOpt(o)) {
            playstyleName = "custom";
        }
        saveConfig();
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
            loadPerBotConfig();
            return;
        }
        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(path)) {
            props.load(in);
        } catch (IOException ex) {
            return;
        }
        difficultyName = props.getProperty("difficulty", "custom");
        playstyleName = props.getProperty("playstyle", "none");
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
        loadPerBotConfig();
    }

    static void saveConfig() {
        Properties props = new Properties();
        props.setProperty("difficulty", difficultyName);
        props.setProperty("playstyle", playstyleName);
        String comment = "PvPBot Commands config. Change values in game with /bot <setting> <value>, or /bot gui.";
        for (Opt o : OPTS.values()) {
            props.setProperty(o.key, o.choices != null ? o.show()
                    : o.bool ? Boolean.toString(o.on()) : Double.toString(o.value));
        }
        try (Writer out = Files.newBufferedWriter(configPath())) {
            props.store(out, comment);
        } catch (IOException ignored) {
        }
    }

    static Path botsPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("pvpbotcmd_bots.properties");
    }

    /** Persists every bot's overrides (and its difficulty/playstyle label) as "<bot>.<key>=<value>" lines. */
    static void savePerBotConfig() {
        Properties props = new Properties();
        Set<String> bots = new TreeSet<>();
        for (Opt o : OPTS.values()) {
            bots.addAll(o.perBot.keySet());
        }
        bots.addAll(BOT_DIFFICULTY.keySet());
        bots.addAll(BOT_PLAYSTYLE.keySet());
        for (String bot : bots) {
            String diff = BOT_DIFFICULTY.get(bot);
            if (diff != null) {
                props.setProperty(bot + ".difficulty", diff);
            }
            String style = BOT_PLAYSTYLE.get(bot);
            if (style != null) {
                props.setProperty(bot + ".playstyle", style);
            }
            for (Opt o : OPTS.values()) {
                Double v = o.perBot.get(bot);
                if (v != null) {
                    props.setProperty(bot + "." + o.key, o.bool ? Boolean.toString(v >= 0.5) : Double.toString(v));
                }
            }
        }
        String comment = "Per-bot setting overrides. Delete a bot's lines here (or use /bot <name> reset in game) to revert it to the shared defaults.";
        try (Writer out = Files.newBufferedWriter(botsPath())) {
            props.store(out, comment);
        } catch (IOException ignored) {
        }
    }

    static void loadPerBotConfig() {
        Path path = botsPath();
        if (!Files.exists(path)) {
            return;
        }
        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(path)) {
            props.load(in);
        } catch (IOException ex) {
            return;
        }
        Map<String, Map<String, String>> byBot = new HashMap<>();
        for (String key : props.stringPropertyNames()) {
            int dot = key.indexOf('.');
            if (dot < 0) {
                continue;
            }
            byBot.computeIfAbsent(key.substring(0, dot), k -> new HashMap<>()).put(key.substring(dot + 1), props.getProperty(key));
        }
        for (Map.Entry<String, Map<String, String>> e : byBot.entrySet()) {
            String bot = e.getKey();
            Map<String, String> vals = e.getValue();
            String diff = vals.get("difficulty");
            if (diff != null) {
                BOT_DIFFICULTY.put(bot, diff);
            }
            String style = vals.get("playstyle");
            if (style != null) {
                BOT_PLAYSTYLE.put(bot, style);
            }
            for (Opt o : OPTS.values()) {
                String raw = vals.get(o.key);
                if (raw == null) {
                    continue;
                }
                try {
                    double v = o.bool ? (Boolean.parseBoolean(raw.trim()) ? 1 : 0) : Double.parseDouble(raw.trim());
                    o.setFor(bot, v);
                } catch (NumberFormatException ignored) {
                    // skip this one line
                }
            }
        }
    }
}

/** Helpers, per-tick jobs, loot, focus fire and FFA matches. */
final class BotSupport {

    // ---------------------------------------------------------------- helpers

    /** Runs a command as the server, without chat feedback. */
    static void run(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    static boolean badFood(ItemStack stack) {
        return stack.is(Items.ROTTEN_FLESH) || stack.is(Items.SPIDER_EYE) || stack.is(Items.POISONOUS_POTATO)
                || stack.is(Items.PUFFERFISH) || stack.is(Items.CHORUS_FRUIT) || stack.is(Items.CHICKEN);
    }

    /**
     * Inventory index (0-35) of the best food: golden apples first, then anything safe to eat.
     * When the bot's hunger bar is full, only golden apples can be eaten, so only those count.
     */
    static int findFoodIndex(ServerPlayer bot) {
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

    static int weaponScore(ItemStack stack) {
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
    static int findWeaponSlot(ServerPlayer bot) {
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
    static int toHotbar(ServerPlayer bot, int idx, int avoidSlot) {
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

    static EquipmentSlot armorSlotFor(ItemStack stack) {
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
    static void equipArmor(ServerPlayer bot, String name) {
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
            int idx = TOTEM.on(name) ? findItemIndex(bot, Items.TOTEM_OF_UNDYING) : -1;
            if (idx < 0) {
                idx = findItemIndex(bot, Items.SHIELD);
            }
            if (idx >= 0) {
                bot.setItemSlot(EquipmentSlot.OFFHAND, inv.getItem(idx).copy());
                inv.setItem(idx, ItemStack.EMPTY);
            }
        }
    }

    /** At very low health, swap whatever is in the off-hand (shield, etc.) for a totem of undying, if carried. */
    static void maybeSwitchTotem(ServerPlayer bot, String name) {
        if (!TOTEM.on(name) || TOTEM_HEARTS.v(name) <= 0) {
            return;
        }
        double hp = bot.getHealth() + bot.getAbsorptionAmount();
        if (hp > TOTEM_HEARTS.v(name) * 2.0) {
            return;
        }
        ItemStack off = bot.getItemBySlot(EquipmentSlot.OFFHAND);
        if (off.is(Items.TOTEM_OF_UNDYING)) {
            return;
        }
        Inventory inv = bot.getInventory();
        int idx = findItemIndex(bot, Items.TOTEM_OF_UNDYING);
        if (idx < 0) {
            return;
        }
        ItemStack totem = inv.getItem(idx);
        inv.setItem(idx, off.isEmpty() ? ItemStack.EMPTY : off.copy());
        bot.setItemSlot(EquipmentSlot.OFFHAND, totem.copy());
    }

    /** Once health recovers well above the totem line, swap the totem back out for a shield, if it has one. */
    static void maybeSwitchTotemBack(ServerPlayer bot, String name) {
        if (!TOTEM.on(name) || TOTEM_HEARTS.v(name) <= 0) {
            return;
        }
        double hp = bot.getHealth() + bot.getAbsorptionAmount();
        if (hp < TOTEM_HEARTS.v(name) * 2.0 + 4.0) { // a buffer above the switch-to-totem line, so it doesn't flicker
            return;
        }
        ItemStack off = bot.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!off.is(Items.TOTEM_OF_UNDYING)) {
            return;
        }
        Inventory inv = bot.getInventory();
        int idx = findItemIndex(bot, Items.SHIELD);
        if (idx < 0) {
            return; // no shield to switch back to: keep the totem up
        }
        ItemStack shield = inv.getItem(idx);
        inv.setItem(idx, off.copy());
        bot.setItemSlot(EquipmentSlot.OFFHAND, shield.copy());
    }

    /** Hotbar index (0-8) of an axe, or -1. */
    static int findAxeSlot(ServerPlayer bot) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(ItemTags.AXES)) {
                return i;
            }
        }
        return -1;
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /**
     * Hit web, step 1: after one of our hits lands, sometimes take out a cobweb (real placement, in a few ticks):
     * swap to it, aim at the floor at the target's feet, right-click, swap back.
     */
    static void startWebPlace(MinecraftServer server, String name, Fight f, ServerPlayer bot, ServerPlayer target) {
        if (f.placeStage != 0 || globalTick < f.placeCooldown || HIT_WEB.v(name) <= 0
                || ThreadLocalRandom.current().nextDouble() * 100.0 >= HIT_WEB.v(name)) {
            return;
        }
        int idx = findItemIndex(bot, Items.COBWEB);
        if (idx < 0 || webAimPoint(bot, target, f, name) == null) {
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
        f.placeRandom = false;
        f.placeRetried = false;
        f.placeStart = globalTick;
    }

    /** Random web, no hit needed: same pipeline as a hit-web, but a random distance and no trigger requirement. */
    static void startRandomWebPlace(MinecraftServer server, String name, Fight f, ServerPlayer bot, ServerPlayer target) {
        if (f.placeStage != 0) {
            return;
        }
        int idx = findItemIndex(bot, Items.COBWEB);
        if (idx < 0 || randomWebAimPoint(bot, target, f, name) == null) {
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
        f.placeRandom = true;
        f.placeRetried = false;
        f.placeStart = globalTick;
    }

    /**
     * A point on the floor near the target's feet, led by its movement so a moving target doesn't just walk out
     * of it. Tries several candidate distances (on top of, just in front of, and just behind the target) and
     * works even when the target is briefly airborne (it aims at the floor it's about to land on, using its
     * current feet level). Lead is only applied lightly: right after a hit the target's knockback velocity is
     * decaying fast, so a full-velocity extrapolation overshoots — this is why placement re-aims fresh each
     * time it's called instead of trusting one early guess.
     */
    static Vec3 webAimPoint(ServerPlayer bot, ServerPlayer target, Fight f, String name) {
        Level level = target.level();
        int floorY = target.blockPosition().getY();
        double lead = WEB_LEAD_TICKS.v(name) * 0.4; // damped: knockback decays, so a full-strength lead overshoots
        Vec3 vel = target.getDeltaMovement();
        double tx = target.getX() + (lead > 0 ? vel.x * lead : 0);
        double tz = target.getZ() + (lead > 0 ? vel.z * lead : 0);
        double dx = bot.getX() - tx;
        double dz = bot.getZ() - tz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.05) {
            dx = 1;
            dz = 0;
            len = 1;
        }
        double[] fracs = {0.0, 0.3, 0.5, -0.3};
        for (double frac : fracs) {
            double px = tx + dx / len * frac;
            double pz = tz + dz / len * frac;
            BlockPos cell = BlockPos.containing(px, floorY, pz);
            if (level.getBlockState(cell).isAir() && !level.getBlockState(cell.below()).isAir()) {
                f.placeCell = cell;
                return new Vec3(px, floorY, pz);
            }
        }
        return null;
    }

    /** Like webAimPoint, but at a random distance between randomwebmindist and randomwebmaxdist, either side of the target. */
    static Vec3 randomWebAimPoint(ServerPlayer bot, ServerPlayer target, Fight f, String name) {
        Level level = target.level();
        int floorY = target.blockPosition().getY();
        double dx = bot.getX() - target.getX();
        double dz = bot.getZ() - target.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.05) {
            dx = 1;
            dz = 0;
            len = 1;
        }
        double lo = Math.min(RANDOMWEB_MINDIST.v(name), RANDOMWEB_MAXDIST.v(name));
        double hi = Math.max(RANDOMWEB_MINDIST.v(name), RANDOMWEB_MAXDIST.v(name));
        double frac = lo + ThreadLocalRandom.current().nextDouble() * (hi - lo);
        if (ThreadLocalRandom.current().nextBoolean()) {
            frac = -frac;
        }
        double px = target.getX() + dx / len * frac;
        double pz = target.getZ() + dz / len * frac;
        BlockPos cell = BlockPos.containing(px, floorY, pz);
        if (!level.getBlockState(cell).isAir() || level.getBlockState(cell.below()).isAir()) {
            return null;
        }
        f.placeCell = cell;
        return new Vec3(px, floorY, pz);
    }

    /** Hit web / random web, steps 2-4, with one retry if the click missed. Returns true while busy. */
    static boolean webPlaceStep(MinecraftServer server, String name, Fight f, ServerPlayer bot, ServerPlayer target) {
        if (f.placeStage == 0) {
            return false;
        }
        int elapsed = globalTick - f.placeStart;
        if (f.placeStage == 1) {
            // Wait out most of the knockback from the hit before aiming at all: right after a hit, the target's
            // velocity is highest and decaying fast, so aiming too early just extrapolates the overshoot.
            if (elapsed >= 3) {
                Vec3 aim = f.placeRandom ? randomWebAimPoint(bot, target, f, name) : webAimPoint(bot, target, f, name);
                if (aim == null || bot.distanceToSqr(aim.x, aim.y, aim.z) > 16.0) { // out of reach: give up cleanly
                    endWebPlace(server, name, f);
                    return false;
                }
                run(server, "player " + name + " look at " + fmt(aim.x) + " " + fmt(aim.y) + " " + fmt(aim.z));
                f.placeStage = 2;
                f.placeStart = globalTick;
            }
        } else if (f.placeStage == 2) {
            if (elapsed >= 1) {
                // Re-aim fresh right at the moment of the click: whatever the target did in the last tick, this
                // is the aim that actually lands, so it matters far more than the one taken a tick earlier.
                Vec3 aim = f.placeRandom ? randomWebAimPoint(bot, target, f, name) : webAimPoint(bot, target, f, name);
                if (aim != null && bot.distanceToSqr(aim.x, aim.y, aim.z) <= 16.0) {
                    run(server, "player " + name + " look at " + fmt(aim.x) + " " + fmt(aim.y) + " " + fmt(aim.z));
                }
                run(server, "player " + name + " use once"); // place the cobweb
                f.placeStage = 3;
                f.placeStart = globalTick;
            }
        } else if (elapsed >= 2) {
            boolean placed = f.placeCell != null && bot.level().getBlockState(f.placeCell).is(Blocks.COBWEB);
            if (!placed && !f.placeRetried && findItemIndex(bot, Items.COBWEB) >= 0) {
                Vec3 aim = f.placeRandom ? randomWebAimPoint(bot, target, f, name) : webAimPoint(bot, target, f, name);
                if (aim != null && bot.distanceToSqr(aim.x, aim.y, aim.z) <= 16.0) {
                    f.placeRetried = true;
                    f.placeStage = 2; // re-aim and click together again, one more time
                    f.placeStart = globalTick - 1;
                    return true;
                }
            }
            if (placed) {
                if (WEB_LIFETIME.asInt(name) > 0) {
                    PLACED_WEBS.add(new PlacedWeb(bot.level(), f.placeCell, globalTick + WEB_LIFETIME.asInt(name)));
                }
                // Cross-web combo: the target is stuck, so follow up with a guaranteed run of crits.
                if (CRIT_CHAIN_CHANCE.v(name) > 0) {
                    int lo = Math.min(CRIT_CHAIN_MIN.asInt(name), CRIT_CHAIN_MAX.asInt(name));
                    int hi = Math.max(CRIT_CHAIN_MIN.asInt(name), CRIT_CHAIN_MAX.asInt(name));
                    f.critChainUntil = globalTick + lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
                }
            }
            endWebPlace(server, name, f);
            return false;
        }
        return true;
    }

    static void endWebPlace(MinecraftServer server, String name, Fight f) {
        f.placeStage = 0;
        f.placeRandom = false;
        f.placeRetried = false;
        f.placeCooldown = globalTick + 30;
        f.weaponSlot = -1; // back to the sword
        run(server, "player " + name + " move forward");
        run(server, "player " + name + " sprint");
    }

    static String lookCmd(String name, String target) {
        int t = AIM.asInt(name);
        return "player " + name + " look upon " + target + " eyes" + (t > 0 ? " delta " + t : "");
    }

    /** Distance from the bot's eyes to the nearest point of the target's hitbox (what melee reach is measured by). */
    static double reachDistance(ServerPlayer bot, ServerPlayer target) {
        AABB box = target.getBoundingBox();
        double ex = bot.getX();
        double ey = bot.getEyeY();
        double ez = bot.getZ();
        double dx = Math.max(Math.max(box.minX - ex, 0.0), ex - box.maxX);
        double dy = Math.max(Math.max(box.minY - ey, 0.0), ey - box.maxY);
        double dz = Math.max(Math.max(box.minZ - ez, 0.0), ez - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** A cobweb touching any part of this entity's hitbox (also catches webs only touched at the edge). Works for a bot or its target. */
    static BlockPos findCobweb(LivingEntity who) {
        AABB box = who.getBoundingBox();
        BlockPos min = BlockPos.containing(box.minX + 1.0E-7, box.minY + 1.0E-7, box.minZ + 1.0E-7);
        BlockPos max = BlockPos.containing(box.maxX - 1.0E-7, box.maxY - 1.0E-7, box.maxZ - 1.0E-7);
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            if (who.level().getBlockState(p).is(Blocks.COBWEB)) {
                return p.immutable();
            }
        }
        return null;
    }

    static boolean inCobweb(LivingEntity who) {
        return findCobweb(who) != null;
    }

    /**
     * The target is fully boxed in by cobwebs (its own cell plus the block above, mirroring the bot's own
     * cocoon shape, or at least 2 of its 4 side neighbors, from a trap wall). Returns one of the *surrounding*
     * webs to mine open (never the one at the target's own feet, which is what keeps it slowed).
     */
    static BlockPos targetBoxedInWeb(ServerPlayer target) {
        Level level = target.level();
        BlockPos feet = target.blockPosition();
        if (!level.getBlockState(feet).is(Blocks.COBWEB)) {
            return null;
        }
        if (level.getBlockState(feet.above()).is(Blocks.COBWEB)) {
            return feet.above();
        }
        int sides = 0;
        BlockPos pick = null;
        for (BlockPos n : new BlockPos[]{feet.north(), feet.south(), feet.east(), feet.west()}) {
            if (level.getBlockState(n).is(Blocks.COBWEB)) {
                sides++;
                if (pick == null) {
                    pick = n.immutable();
                }
            }
        }
        return sides >= 2 ? pick : null;
    }

    /** The nearest existing cobweb (anyone's) within radius blocks of the bot, or null. */
    static BlockPos nearbyWeb(ServerPlayer bot, double radius) {
        BlockPos center = bot.blockPosition();
        int r = (int) Math.ceil(radius);
        double bestDistSq = radius * radius;
        BlockPos best = null;
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -2, -r), center.offset(r, 2, r))) {
            if (bot.level().getBlockState(p).is(Blocks.COBWEB)) {
                double d = p.distToCenterSqr(bot.getX(), bot.getY(), bot.getZ());
                if (d < bestDistSq) {
                    bestDistSq = d;
                    best = p.immutable();
                }
            }
        }
        return best;
    }

    /** True while the target is eating or sprinting away — the window used for jump-crits, tight pressure and crit chains. */
    static boolean targetVulnerable(ServerPlayer bot, ServerPlayer target) {
        return target.isUsingItem() || (target.isSprinting() && movingAway(bot, target));
    }

    static int findItemIndex(ServerPlayer bot, Item item) {
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
    static boolean webEscape(MinecraftServer server, String name, Fight f, ServerPlayer bot) {
        if (f.webStage == 0) {
            if (f.inCocoon || (!WEB_ESCAPE.on(name) && !WEB_BREAK.on(name)) || globalTick < f.nextWebTick) {
                return false;
            }
            BlockPos web = findCobweb(bot);
            if (web == null) {
                return false;
            }
            int idx = WEB_ESCAPE.on(name) ? findItemIndex(bot, Items.WATER_BUCKET) : -1;
            int slot = idx < 0 ? -1 : toHotbar(bot, idx, findWeaponSlot(bot));
            if (slot < 0 && !WEB_BREAK.on(name)) {
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
            // Whatever the bot was doing, it is stuck: drop it and get out first — but if it was mid-meal, remember
            // that so it can pick eating back up (with progress intact) instead of falling back into fighting.
            boolean wasEating = f.phase == Phase.EAT;
            f.webWasEating = wasEating;
            f.phase = Phase.FIGHT;
            if (!wasEating) {
                f.eaten = 0;
                f.nextEatTick = Math.max(f.nextEatTick, globalTick + 40);
            }
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
                finishWeb(server, name, f, bot, 4);
            }
        } else if (f.webStage == 10) {
            boolean timedOut = globalTick - f.webStart > WEB_BREAK_TIMEOUT_TICKS;
            if (!inCobweb(bot) || timedOut) {
                run(server, "player " + name + " stop"); // stops mining
                finishWeb(server, name, f, bot, timedOut ? 60 : 4);
            }
        }
        return true;
    }

    static void finishWeb(MinecraftServer server, String name, Fight f, ServerPlayer bot, int cooldownTicks) {
        f.webStage = 0;
        f.nextWebTick = globalTick + cooldownTicks;
        f.started = false;
        f.weaponSlot = -1; // back to the sword
        if (f.webWasEating) {
            f.webWasEating = false;
            if (findFoodIndex(bot) >= 0) {
                startEat(server, name, f, bot, f.inCocoon);
            }
        }
    }

    /**
     * Guaranteed crit + boxed-in breakout: while the target is fully boxed in by cobwebs, mine one of the
     * *surrounding* webs open first (the target's own cell stays webbed, still slowing it). Returns true while busy.
     */
    static boolean breakoutStep(MinecraftServer server, String name, Fight f, ServerPlayer bot, ServerPlayer target) {
        if (f.breakStage == 0) {
            if (!WEB_CRIT.on(name)) {
                return false;
            }
            BlockPos web = targetBoxedInWeb(target);
            if (web == null) {
                return false;
            }
            int w = findWeaponSlot(bot);
            if (w >= 0 && w != f.weaponSlot) {
                run(server, "player " + name + " hotbar " + (w + 1));
                f.weaponSlot = w;
            }
            run(server, "player " + name + " look at " + fmt(web.getX() + 0.5) + " " + fmt(web.getY() + 0.5) + " " + fmt(web.getZ() + 0.5));
            run(server, "player " + name + " attack continuous");
            f.breakPos = web;
            f.breakStage = 1;
            f.breakStart = globalTick;
            return true;
        }
        boolean gone = f.breakPos == null || !bot.level().getBlockState(f.breakPos).is(Blocks.COBWEB);
        boolean timedOut = globalTick - f.breakStart > WEB_BREAK_TIMEOUT_TICKS;
        if (gone || timedOut) {
            run(server, "player " + name + " stop");
            f.breakStage = 0;
            f.breakPos = null;
            return false;
        }
        return true;
    }

    /** If the scoop missed, remove any water source we poured near the web and put the water back in the bucket. */
    static void cleanupWater(ServerPlayer bot, Fight f) {
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

    static boolean allowed(CommandSourceStack src) {
        if (!OPS_ONLY.on() || dispatcher == null) {
            return true;
        }
        // Same permission as the vanilla /gamemode command (operators, or cheats on in single player).
        CommandNode<CommandSourceStack> gamemode = dispatcher.getRoot().getChild("gamemode");
        return gamemode == null || gamemode.getRequirement().test(src);
    }

    static String teamName(ServerPlayer p) {
        return p.getTeam() == null ? null : p.getTeam().getName();
    }

    /** In a normal fight teammates never fight each other; a free-for-all match ignores teams. */
    static boolean allied(ServerPlayer a, ServerPlayer b) {
        return !(ffaActive && !ffaTeams) && a.isAlliedTo(b);
    }

    static boolean botFight() {
        return BOT_FIGHT.on() || ffaActive;
    }

    static boolean ffaCounting() {
        return ffaActive && globalTick < ffaGoTick;
    }

    static void broadcast(MinecraftServer server, String text) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(text), false);
    }

    static int countItem(ServerPlayer bot, Item item) {
        Inventory inv = bot.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getItem(i);
            if (st.is(item)) {
                n += st.getCount();
            }
        }
        return n;
    }

    /**
     * Aim command: a smooth look at the target's eyes (HeroBot's own tracking) by default. Falls back to a
     * manual yaw/pitch calculation whenever wobble, predictive lead, aim spread or an overshoot flick apply,
     * since those all need to steer where exactly the bot looks.
     */
    static String aimCmd(String name, ServerPlayer bot, ServerPlayer target, Fight f) {
        double w = WOBBLE.v(name);
        double lead = PREDICT_TICKS.v(name);
        boolean leading = lead > 0 && AIM.asInt(name) <= 2;
        boolean spread = AIM_SPREAD.v(name) > 0 && ThreadLocalRandom.current().nextDouble() * 100.0 < AIM_SPREAD.v(name);
        boolean overshootRoll = !f.overshotLast && OVERSHOOT_CHANCE.v(name) > 0
                && ThreadLocalRandom.current().nextDouble() * 100.0 < OVERSHOOT_CHANCE.v(name);
        if (w <= 0 && !leading && !spread && !overshootRoll && !f.overshotLast) {
            return lookCmd(name, target.getName().getString());
        }
        double tx = target.getX();
        double tz = target.getZ();
        if (leading) {
            Vec3 vel = target.getDeltaMovement();
            tx += vel.x * lead;
            tz += vel.z * lead;
        }
        double dx = tx - bot.getX();
        double dz = tz - bot.getZ();
        double targetY = spread
                ? target.getY() + ThreadLocalRandom.current().nextDouble() * Math.max(0.1, target.getBbHeight() - 0.2)
                : target.getEyeY();
        double dy = targetY - bot.getEyeY();
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        if (w > 0) {
            yaw += (ThreadLocalRandom.current().nextDouble() * 2 - 1) * w;
            pitch += (ThreadLocalRandom.current().nextDouble() * 2 - 1) * w * 0.5;
        }
        if (f.overshotLast) {
            // The previous update overshot on purpose; this one always snaps straight back to true aim.
            f.overshotLast = false;
        } else if (overshootRoll) {
            double diff = wrapDegrees(yaw - bot.getYRot());
            yaw += Math.copySign(OVERSHOOT_DEGREES.v(name), diff == 0 ? 1 : diff);
            f.overshotLast = true;
        }
        return "player " + name + " look " + String.format(Locale.ROOT, "%.1f %.1f", yaw, pitch);
    }

    /** Yaw toward the target's current position, no lead and no wobble — used only for the aim-cone check. */
    static double directYaw(ServerPlayer bot, ServerPlayer target) {
        double dx = target.getX() - bot.getX();
        double dz = target.getZ() - bot.getZ();
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Wraps a degree difference into -180..180 so an aim-angle comparison is never off by a full turn. */
    static double wrapDegrees(double deg) {
        deg %= 360.0;
        if (deg >= 180.0) {
            deg -= 360.0;
        } else if (deg < -180.0) {
            deg += 360.0;
        }
        return deg;
    }

    /** True while the target is moving away from the bot (used for the crit-chain trigger). */
    static boolean movingAway(ServerPlayer bot, ServerPlayer target) {
        double vx = target.getDeltaMovement().x;
        double vz = target.getDeltaMovement().z;
        if (vx * vx + vz * vz < 0.001) {
            return false; // barely moving: not "running away"
        }
        double dx = target.getX() - bot.getX();
        double dz = target.getZ() - bot.getZ();
        return vx * dx + vz * dz > 0;
    }

    /** Would standing at this spot mean lava, or nothing to stand on for 4 blocks (void or a deep drop)? */
    static boolean hazardAt(Level level, double x, double y, double z) {
        BlockPos feet = BlockPos.containing(x, y, z);
        if (level.getFluidState(feet).is(FluidTags.LAVA) || level.getFluidState(feet.above()).is(FluidTags.LAVA)) {
            return true;
        }
        for (int i = 1; i <= 4; i++) {
            BlockPos below = feet.below(i);
            if (level.getFluidState(below).is(FluidTags.LAVA)) {
                return true;
            }
            if (!level.getBlockState(below).isAir()) {
                return false; // ground (or water) to land on
            }
        }
        return true;
    }

    /** A cobweb at this spot, other than the target's own cell (which is left alone on purpose, for the web-crit rule). */
    static boolean webAheadOf(Level level, double x, double y, double z, BlockPos targetCell) {
        BlockPos p = BlockPos.containing(x, y, z);
        return !p.equals(targetCell) && level.getBlockState(p).is(Blocks.COBWEB);
    }

    // ---- splash potions

    static int effectKind(MobEffectInstance effect) {
        String id = effect.getDescriptionId();
        if (id.endsWith(".instant_health")) {
            return 1;
        }
        if (id.endsWith(".speed")) {
            return 2;
        }
        if (id.endsWith(".strength")) {
            return 3;
        }
        if (id.endsWith(".fire_resistance")) {
            return 4;
        }
        return 0;
    }

    /** 1 = healing, 2 = speed, 3 = strength, 4 = fire resistance, 0 = anything else (only splash potions count). */
    static int potionKind(ItemStack stack) {
        if (!stack.is(Items.SPLASH_POTION)) {
            return 0;
        }
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return 0;
        }
        for (MobEffectInstance effect : contents.getAllEffects()) {
            int kind = effectKind(effect);
            if (kind != 0) {
                return kind;
            }
        }
        return 0;
    }

    static boolean hasEffectKind(ServerPlayer bot, int kind) {
        for (MobEffectInstance effect : bot.getActiveEffects()) {
            if (effectKind(effect) == kind) {
                return true;
            }
        }
        return false;
    }

    static int findPotionIndex(ServerPlayer bot, int kind) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < 36; i++) {
            if (potionKind(inv.getItem(i)) == kind) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The next potion kind worth throwing, or 0. Healing when hurt; speed, strength and fire resistance only
     * when the target is far away (a splash also hits anyone standing close, and the target must never get
     * the effect). Each buff kind is gated only by whether the effect is currently active, not by thrownMask,
     * so a bot throws another one as soon as the earlier potion's effect actually runs out.
     */
    static int wantedPotion(ServerPlayer bot, Fight f, double dist, double hp, String name) {
        if ((f.thrownMask & 1) == 0 && POTION_HEARTS.v(name) > 0 && hp <= POTION_HEARTS.v(name) * 2.0
                && findPotionIndex(bot, 1) >= 0) {
            return 1;
        }
        if (dist >= POTION_SAFE_DIST + 1.0) {
            if (!hasEffectKind(bot, 2) && findPotionIndex(bot, 2) >= 0) {
                return 2;
            }
            if (!hasEffectKind(bot, 3) && findPotionIndex(bot, 3) >= 0) {
                return 3;
            }
            if (!hasEffectKind(bot, 4) && findPotionIndex(bot, 4) >= 0) {
                return 4;
            }
        }
        return 0;
    }

    static boolean cocoonReady(ServerPlayer bot, Fight f, double hp, String name) {
        return COCOON.on(name) && COCOON_HEARTS.v(name) > 0 && hp <= COCOON_HEARTS.v(name) * 2.0
                && globalTick >= f.nextEatTick && countItem(bot, Items.COBWEB) >= 2 && findFoodIndex(bot) >= 0;
    }

    /** Solid ground under the bot and clear air in the two cells the cocoon webs go into, so it doesn't fail on uneven terrain. */
    static boolean cocoonGroundOk(ServerPlayer bot) {
        Level level = bot.level();
        BlockPos feet = bot.blockPosition();
        return !level.getBlockState(feet.below()).isAir()
                && level.getBlockState(feet).isAir()
                && level.getBlockState(feet.above()).isAir();
    }

    // ---- loot

    static boolean hasArmorFor(ServerPlayer bot, EquipmentSlot slot) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < 36; i++) {
            if (armorSlotFor(inv.getItem(i)) == slot) {
                return true;
            }
        }
        return false;
    }

    static boolean hasPotionKind(ServerPlayer bot, int kind) {
        return findPotionIndex(bot, kind) >= 0;
    }

    /** Is this dropped item worth walking to? Only item types the bot doesn't already have. */
    static boolean wantsItem(ServerPlayer bot, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        EquipmentSlot slot = armorSlotFor(stack);
        if (slot != null) {
            return bot.getItemBySlot(slot).isEmpty() && !hasArmorFor(bot, slot);
        }
        int kind = potionKind(stack);
        if (kind != 0) {
            return !hasPotionKind(bot, kind);
        }
        Item item = stack.getItem();
        if (findItemIndex(bot, item) >= 0 || bot.getItemBySlot(EquipmentSlot.OFFHAND).is(item)) {
            return false;
        }
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(Items.SHIELD)
                || stack.is(Items.TOTEM_OF_UNDYING) || stack.is(Items.COBWEB) || stack.is(Items.WATER_BUCKET)
                || stack.is(Items.WIND_CHARGE) || stack.is(Items.ENDER_PEARL) || stack.is(Items.EXPERIENCE_BOTTLE)
                || (stack.has(DataComponents.FOOD) && !badFood(stack));
    }

    /** Can this player be fought (online, alive, not spectator or creative, same dimension, not a teammate)? */
    static boolean reachable(ServerPlayer bot, ServerPlayer t) {
        return t != null && t != bot && t.isAlive() && !t.isSpectator() && !t.isCreative()
                && bot.level() == t.level() && !allied(bot, t);
    }

    // ---------------------------------------------------------------- per-tick jobs

    static void tick(MinecraftServer server) {
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
        tickFfa(server);
        if (BOTS.isEmpty()) {
            return;
        }
        if (globalTick % 10 == 0) {
            tickArmor(server);
        }
        if (ffaCounting()) {
            return; // bots hold still until the match countdown ends
        }
        if (globalTick % 2 == 0) {
            tickRevenge(server); // per-bot revenge gate is checked inside the loop
        }
        if (FOCUS_CHANCE.value > 0 && globalTick % 20 == 0) {
            tickFocus(server);
        }
        if ((AUTO_TARGET.on() || ffaActive) && globalTick % 20 == 0) {
            tickAutoTarget(server);
        }
        if (globalTick % 5 == 0) {
            tickLoot(server); // per-bot loot gate is checked inside the loop
        }
        if (!FIGHTS.isEmpty()) {
            tickFights(server);
        }
    }

    static void tickMass(MinecraftServer server) {
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
        PENDING.put(job.name, new Pending(job.owner, true, job.team, job.pos, true));
        if (MASS_QUEUE.isEmpty()) {
            owner.sendSystemMessage(Component.literal("Mass spawn: all bots requested. They join over the next moments."));
        }
    }

    /** Removes cobwebs that bots dropped once they expire. */
    static void tickWebs() {
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

    /** A mass-spawned bot gets a random difficulty and playstyle of its own, so a batch comes out mixed. */
    static void varyMassSpawnedBot(String name) {
        int level = ThreadLocalRandom.current().nextInt(LEVEL_NAMES.length);
        for (int i = 0; i < PRESET_OPTS.length; i++) {
            PRESET_OPTS[i].setFor(name, PRESETS[level][i]);
        }
        BOT_DIFFICULTY.put(name, LEVEL_NAMES[level]);
        int style = ThreadLocalRandom.current().nextInt(PLAYSTYLE_NAMES.length);
        for (int i = 0; i < PLAYSTYLE_OPTS.length; i++) {
            PLAYSTYLE_OPTS[i].setFor(name, PLAYSTYLES[style][i]);
        }
        BOT_PLAYSTYLE.put(name, PLAYSTYLE_NAMES[style]);
        savePerBotConfig();
    }

    static void tickPending(MinecraftServer server) {
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
                if (p.team != null) {
                    run(server, "team join " + p.team + " " + name);
                }
                if (p.tpPos != null) {
                    run(server, "tp " + name + " " + fmt(p.tpPos.x) + " " + fmt(p.tpPos.y) + " " + fmt(p.tpPos.z));
                }
                if (p.massSpawned) {
                    varyMassSpawnedBot(name);
                }
                if (owner != null && !p.quiet) {
                    owner.sendSystemMessage(Component.literal(
                            "Spawned " + name + ". Use /bot fight " + name + " to start"
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

    static void tickArmor(MinecraftServer server) {
        for (String name : BOTS) {
            ServerPlayer bot = server.getPlayerList().getPlayerByName(name);
            if (bot != null) {
                equipArmor(bot, name);
                maybeSwitchTotem(bot, name);
                maybeSwitchTotemBack(bot, name);
                Fight f = FIGHTS.get(name);
                if (f != null) {
                    maybeSwapDurability(bot, f, name);
                    maybeThrowExpBottle(server, name, bot, f);
                }
            }
        }
    }

    /** Is this stack's remaining durability at or below the swap threshold? Non-damageable items are never "low". */
    static boolean lowDurability(ItemStack stack, String name) {
        return lowDurabilityAt(stack, DURABILITY_THRESHOLD.v(name));
    }

    /** Same check as lowDurability, but against an arbitrary percent threshold instead of the swap setting. */
    static boolean lowDurabilityAt(ItemStack stack, double thresholdPercent) {
        if (stack.isEmpty() || !stack.isDamageableItem()) {
            return false;
        }
        int max = stack.getMaxDamage();
        if (max <= 0) {
            return false;
        }
        int remaining = max - stack.getDamageValue();
        return remaining * 100.0 / max <= thresholdPercent;
    }

    /** Does this stack carry the Mending enchantment? */
    static boolean hasMending(ItemStack stack, ServerPlayer bot) {
        if (stack.isEmpty()) {
            return false;
        }
        try {
            var enchantments = bot.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            var mending = enchantments.getOrThrow(Enchantments.MENDING);
            return EnchantmentHelper.getItemEnchantmentLevel(mending, stack) > 0;
        } catch (Exception ex) {
            return false; // be safe on any registry lookup mismatch rather than crash the tick
        }
    }

    /** If the held sword or a worn armor piece is nearly broken, swap in a spare of the same kind from the inventory. */
    static void maybeSwapDurability(ServerPlayer bot, Fight f, String name) {
        if (!DURABILITY_SWAP.on(name) || globalTick < f.nextDurabilityCheck) {
            return;
        }
        f.nextDurabilityCheck = globalTick + 20;
        Inventory inv = bot.getInventory();
        ItemStack main = bot.getMainHandItem();
        if (main.is(ItemTags.SWORDS) && lowDurability(main, name)) {
            int mainSlot = inv.getSelectedSlot();
            for (int i = 0; i < 36; i++) {
                ItemStack cand = inv.getItem(i);
                if (i != mainSlot && cand.is(ItemTags.SWORDS) && !lowDurability(cand, name)) {
                    inv.setItem(i, main.copy());
                    inv.setItem(mainSlot, cand.copy());
                    f.weaponSlot = -1; // re-pick and re-equip next tick
                    break;
                }
            }
        }
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack worn = bot.getItemBySlot(slot);
            if (worn.isEmpty() || !lowDurability(worn, name)) {
                continue;
            }
            for (int i = 0; i < 36; i++) {
                ItemStack cand = inv.getItem(i);
                if (armorSlotFor(cand) == slot && !lowDurability(cand, name)) {
                    inv.setItem(i, worn.copy());
                    bot.setItemSlot(slot, cand.copy());
                    break;
                }
            }
        }
    }

    /**
     * Exp bottle mending repair: when every equipped armor piece is enchanted with Mending and every one of
     * them is at or below expbottlethreshold durability, throw an experience bottle at itself so the XP orbs
     * get soaked up by the mending gear.
     */
    static void maybeThrowExpBottle(MinecraftServer server, String name, ServerPlayer bot, Fight f) {
        if (!EXP_BOTTLE.on(name) || globalTick < f.nextExpBottleTick) {
            return;
        }
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot slot : slots) {
            ItemStack piece = bot.getItemBySlot(slot);
            if (piece.isEmpty() || !hasMending(piece, bot) || !lowDurabilityAt(piece, EXP_BOTTLE_THRESHOLD.v(name))) {
                return; // not every piece qualifies: nothing to do yet
            }
        }
        f.nextExpBottleTick = globalTick + 100; // don't spam-throw once it qualifies
        int idx = findItemIndex(bot, Items.EXPERIENCE_BOTTLE);
        if (idx < 0) {
            return;
        }
        int slot = toHotbar(bot, idx, findWeaponSlot(bot));
        if (slot < 0) {
            return;
        }
        run(server, "player " + name + " hotbar " + (slot + 1));
        run(server, "player " + name + " look " + fmt(bot.getYRot()) + " 90");
        run(server, "player " + name + " use once");
        f.weaponSlot = -1; // back to the sword next tick
    }

    /** Revenge: whoever hits a bot becomes its target. */
    static void tickRevenge(MinecraftServer server) {
        for (String name : BOTS) {
            if (!REVENGE.on(name)) {
                continue;
            }
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
            if ((BOTS.contains(attackerName) && !botFight()) || attacker.isCreative() || allied(bot, attacker)) {
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
    static void tickAutoTarget(MinecraftServer server) {
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
                if (p == bot || (!botFight() && BOTS.contains(p.getName().getString()))
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

    /** Team focus fire: each team picks a random enemy for a random time, and each bot joins it by chance. */
    static void tickFocus(MinecraftServer server) {
        Map<String, List<ServerPlayer>> teams = new HashMap<>();
        for (String name : BOTS) {
            if (STOPPED.contains(name)) {
                continue;
            }
            ServerPlayer bot = server.getPlayerList().getPlayerByName(name);
            if (bot == null) {
                continue;
            }
            String team = teamName(bot);
            if (team != null) {
                teams.computeIfAbsent(team, k -> new ArrayList<>()).add(bot);
            }
        }
        for (Map.Entry<String, List<ServerPlayer>> entry : teams.entrySet()) {
            List<ServerPlayer> members = entry.getValue();
            Focus focus = FOCUS.get(entry.getKey());
            boolean fresh = false;
            if (focus == null || globalTick >= focus.expire
                    || !reachable(members.get(0), server.getPlayerList().getPlayerByName(focus.target))) {
                ServerPlayer pick = pickFocusTarget(server, members);
                if (pick == null) {
                    FOCUS.remove(entry.getKey());
                    continue;
                }
                int lo = Math.min(FOCUS_MIN.asInt(), FOCUS_MAX.asInt());
                int hi = Math.max(FOCUS_MIN.asInt(), FOCUS_MAX.asInt());
                focus = new Focus(pick.getName().getString(),
                        globalTick + lo + ThreadLocalRandom.current().nextInt(hi - lo + 1));
                FOCUS.put(entry.getKey(), focus);
                fresh = true;
            }
            ServerPlayer focusTarget = server.getPlayerList().getPlayerByName(focus.target);
            for (ServerPlayer member : members) {
                String memberName = member.getName().getString();
                Fight f = FIGHTS.get(memberName);
                boolean idle = f == null || !reachable(member, server.getPlayerList().getPlayerByName(f.target));
                if (!(fresh || idle) || !reachable(member, focusTarget)) {
                    continue;
                }
                if (ThreadLocalRandom.current().nextDouble() * 100.0 >= FOCUS_CHANCE.value) {
                    continue; // this bot keeps doing its own thing
                }
                if (f == null) {
                    FIGHTS.put(memberName, new Fight(focus.target));
                } else {
                    f.target = focus.target;
                }
            }
        }
    }

    /** A random enemy that every member can fight and that is within find range of at least one of them. */
    static ServerPlayer pickFocusTarget(MinecraftServer server, List<ServerPlayer> members) {
        List<ServerPlayer> candidates = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!botFight() && BOTS.contains(p.getName().getString())) {
                continue;
            }
            boolean fightable = true;
            boolean near = false;
            for (ServerPlayer m : members) {
                if (p == m || !reachable(m, p)) {
                    fightable = false;
                    break;
                }
                if (m.distanceTo(p) <= FIND_RANGE.value) {
                    near = true;
                }
            }
            if (fightable && near) {
                candidates.add(p);
            }
        }
        return candidates.isEmpty() ? null : candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    /** Loot pickup: bots that aren't in a close fight walk to dropped items they don't have yet. */
    static void tickLoot(MinecraftServer server) {
        for (String name : BOTS) {
            ServerPlayer bot = server.getPlayerList().getPlayerByName(name);
            if (bot == null) {
                LOOTING.remove(name);
                continue;
            }
            Fight f = FIGHTS.get(name);
            boolean fightClose = false;
            if (f != null) {
                if (f.phase != Phase.FIGHT || f.webStage != 0) {
                    continue; // busy running away, eating, throwing potions or escaping a web
                }
                ServerPlayer t = server.getPlayerList().getPlayerByName(f.target);
                fightClose = reachable(bot, t) && bot.distanceTo(t) <= LOOT_FIGHT_DIST;
            }
            LootState loot = LOOTING.get(name);
            if (loot != null) {
                Entity item = bot.level().getEntity(loot.entityId);
                if (fightClose || !(item instanceof ItemEntity) || !item.isAlive()
                        || globalTick - loot.start > 240 || bot.distanceTo(item) < 0.8) {
                    LOOTING.remove(name);
                    run(server, "player " + name + " stop");
                    if (f != null) {
                        f.reset();
                    }
                } else if (globalTick % 10 == 0) {
                    run(server, "player " + name + " look at " + fmt(item.getX()) + " " + fmt(item.getY())
                            + " " + fmt(item.getZ()));
                }
                continue;
            }
            if (fightClose || bot.getInventory().getFreeSlot() < 0 || !LOOT.on(name)) {
                continue;
            }
            ItemEntity best = null;
            double bestDist = LOOT_RANGE.v(name);
            for (ItemEntity candidate : bot.level().getEntitiesOfClass(ItemEntity.class,
                    bot.getBoundingBox().inflate(LOOT_RANGE.v(name)))) {
                double d = bot.distanceTo(candidate);
                if (d < bestDist && wantsItem(bot, candidate.getItem())) {
                    bestDist = d;
                    best = candidate;
                }
            }
            if (best != null) {
                LOOTING.put(name, new LootState(best.getId(), globalTick));
                if (f != null) {
                    f.reset();
                }
                run(server, "player " + name + " stop");
                run(server, "player " + name + " autojump true");
                run(server, "player " + name + " look at " + fmt(best.getX()) + " " + fmt(best.getY())
                        + " " + fmt(best.getZ()));
                run(server, "player " + name + " move forward");
                run(server, "player " + name + " sprint");
            }
        }
    }

    // ---- FFA matches

    static String leaderboard() {
        if (FFA_KILLS.isEmpty()) {
            return "Kills: none yet";
        }
        List<Map.Entry<String, Integer>> list = new ArrayList<>(FFA_KILLS.entrySet());
        list.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
        StringBuilder sb = new StringBuilder("Kills: ");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(list.get(i).getKey()).append(' ').append(list.get(i).getValue());
        }
        return sb.toString();
    }

    /** The team that every one of these players belongs to, or null if they are not all on one team. */
    static String commonTeam(MinecraftServer server, Set<String> names) {
        String team = null;
        for (String n : names) {
            ServerPlayer p = server.getPlayerList().getPlayerByName(n);
            String t = p == null ? null : teamName(p);
            if (t == null) {
                return null;
            }
            if (team == null) {
                team = t;
            } else if (!team.equals(t)) {
                return null;
            }
        }
        return team;
    }

    /** Returns an error message, or null when the match starts (after a 5 second countdown). */
    static String startFfa(MinecraftServer server, String ownerName, boolean teams) {
        if (ffaActive) {
            return "A match is already running. Use /bot ffa stop first.";
        }
        FFA_ALIVE.clear();
        FFA_BOTS.clear();
        FFA_HUMANS.clear();
        FFA_OUT_HUMANS.clear();
        FFA_KILLS.clear();
        LAST_ATTACKER.clear();
        for (String name : BOTS) {
            ServerPlayer bot = server.getPlayerList().getPlayerByName(name);
            if (bot != null && bot.isAlive() && !bot.isCreative()) {
                FFA_ALIVE.add(name);
                FFA_BOTS.add(name);
            }
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String n = p.getName().getString();
            if (BOTS.contains(n) || p.isCreative() || p.isSpectator() || !p.isAlive()) {
                continue;
            }
            FFA_ALIVE.add(n);
            FFA_HUMANS.add(n);
        }
        if (FFA_ALIVE.size() < 2) {
            FFA_ALIVE.clear();
            return "A match needs at least 2 fighters: PvP bots or players in survival mode.";
        }
        ffaActive = true;
        ffaTeams = teams;
        ffaOwner = ownerName;
        ffaGoTick = globalTick + 100;
        ffaLastCount = -1;
        if (FFA_LEAVE.on()) {
            run(server, "herobot botleaveondeath true");
        }
        for (String n : FFA_BOTS) {
            STOPPED.remove(n);
            FIGHTS.remove(n);
            LOOTING.remove(n);
            run(server, "player " + n + " stop");
        }
        broadcast(server, "FFA match" + (teams ? " (teams)" : "") + ": " + FFA_ALIVE.size()
                + " fighters. Starting in 5 seconds...");
        return null;
    }

    static void endFfa(MinecraftServer server, boolean natural) {
        if (!ffaActive) {
            return;
        }
        ffaActive = false;
        if (FFA_LEAVE.on()) {
            run(server, "herobot botleaveondeath false");
        }
        for (String n : FFA_OUT_HUMANS) {
            run(server, "gamemode survival " + n);
        }
        String winner = null;
        if (natural) {
            if (FFA_ALIVE.size() == 1) {
                winner = FFA_ALIVE.iterator().next();
            } else if (ffaTeams) {
                String team = commonTeam(server, FFA_ALIVE);
                if (team != null) {
                    winner = "team " + team;
                }
            }
            broadcast(server, winner != null ? "FFA over! Winner: " + winner : "FFA over! Nobody is left standing.");
        } else {
            broadcast(server, "FFA match stopped.");
        }
        broadcast(server, leaderboard());
        if (TAUNTS.on() && winner != null && FFA_BOTS.contains(winner)) {
            broadcast(server, "<" + winner + "> gg");
        }
        for (String n : FFA_BOTS) {
            if (server.getPlayerList().getPlayerByName(n) != null) {
                STOPPED.add(n);
                FIGHTS.remove(n);
                run(server, "player " + n + " stop");
            }
        }
    }

    static void ffaEliminate(MinecraftServer server, String name, ServerPlayer player) {
        if (!FFA_ALIVE.remove(name)) {
            return;
        }
        String killer = LAST_ATTACKER.get(name);
        boolean credited = killer != null && !killer.equalsIgnoreCase(name)
                && (FFA_BOTS.contains(killer) || FFA_HUMANS.contains(killer));
        if (credited) {
            FFA_KILLS.merge(killer, 1, Integer::sum);
        }
        if (FFA_HUMANS.contains(name)) {
            FFA_OUT_HUMANS.add(name);
        } else if (player != null) {
            run(server, "player " + name + " disconnect"); // still online (botleaveondeath is off): remove it
        }
        broadcast(server, name + " was eliminated" + (credited ? " by " + killer : "")
                + " (" + FFA_ALIVE.size() + " left)");
        if (credited && TAUNTS.on() && FFA_BOTS.contains(killer)) {
            broadcast(server, "<" + killer + "> " + TAUNT_LINES[ThreadLocalRandom.current().nextInt(TAUNT_LINES.length)]);
        }
    }

    static void tickFfa(MinecraftServer server) {
        if (ffaAutoStart && MASS_QUEUE.isEmpty() && PENDING.isEmpty()) {
            ffaAutoStart = false;
            String err = startFfa(server, ffaOwner, ffaAutoTeams);
            if (err != null) {
                broadcast(server, err);
            }
            return;
        }
        if (!ffaActive) {
            return;
        }
        if (globalTick < ffaGoTick) {
            int left = (ffaGoTick - globalTick + 19) / 20;
            if (left != ffaLastCount) {
                ffaLastCount = left;
                broadcast(server, "FFA starts in " + left + "...");
            }
            return;
        }
        if (ffaLastCount != 0) {
            ffaLastCount = 0;
            broadcast(server, "FIGHT!");
        }
        if (globalTick % 5 != 0) {
            return;
        }
        for (String name : new ArrayList<>(FFA_ALIVE)) {
            ServerPlayer p = server.getPlayerList().getPlayerByName(name);
            if (p == null) {
                continue;
            }
            LivingEntity hurtBy = p.getLastHurtByMob();
            if (hurtBy instanceof ServerPlayer) {
                LAST_ATTACKER.put(name, ((ServerPlayer) hurtBy).getName().getString());
            }
        }
        for (String name : new ArrayList<>(FFA_ALIVE)) {
            ServerPlayer p = server.getPlayerList().getPlayerByName(name);
            if (p == null || !p.isAlive()) {
                ffaEliminate(server, name, p);
            }
        }
        if (globalTick % 20 == 0) {
            for (String n : FFA_OUT_HUMANS) {
                ServerPlayer p = server.getPlayerList().getPlayerByName(n);
                if (p != null && p.isAlive() && !p.isSpectator()) {
                    run(server, "gamemode spectator " + n); // out of the match: spectate once respawned
                }
            }
        }
        if (FFA_ALIVE.size() <= 1 || (ffaTeams && commonTeam(server, FFA_ALIVE) != null)) {
            endFfa(server, true);
        }
    }
}

/** The fight loop: phases for fighting, running away, eating, cocoon, web trap, escapes and potions. */
final class Fighting {

    // ---------------------------------------------------------------- fight loop

    static void tickFights(MinecraftServer server) {
        Iterator<Map.Entry<String, Fight>> it = FIGHTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Fight> e = it.next();
            String name = e.getKey();
            Fight f = e.getValue();
            ServerPlayer bot = server.getPlayerList().getPlayerByName(name);
            if (bot == null) {
                it.remove();
                BOTS.remove(name);
                LOOTING.remove(name);
                continue;
            }
            if (LOOTING.containsKey(name)) {
                continue; // walking to a dropped item (tickLoot drives it)
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
            // Stuck in a cobweb? Get out first, whatever the bot was doing (except inside its own cocoon).
            if (f.phase != Phase.COCOON && webEscape(server, name, f, bot)) {
                continue;
            }
            double dist = bot.distanceTo(target);
            switch (f.phase) {
                case FIGHT -> fightPhase(server, name, f, bot, target, dist);
                case FLEE -> fleePhase(server, name, f, bot, target, dist);
                case EAT -> eatPhase(server, name, f, bot, target, dist);
                case COCOON -> cocoonPhase(server, name, f, bot, target, dist);
                case POTION -> potionPhase(server, name, f, bot, target, dist);
                case WEBTRAP -> webtrapPhase(server, name, f, bot, target, dist);
                case WINDESCAPE -> windEscapePhase(server, name, f, bot, target, dist);
                case PEARLESCAPE -> pearlEscapePhase(server, name, f, bot, target, dist);
            }
        }
    }

    /** One swing: a real attack, or (miss chance) a whiff that swings at air. */
    static void doAttack(MinecraftServer server, String name, Fight f, ServerPlayer target) {
        if (PRECISION_LOCK.on(name)) {
            // Aim true right on the tick the swing fires, ignoring wobble/spread/overshoot for this one look
            // command only — human-mistake settings still affect every other tick, just not the one that counts.
            run(server, lookCmd(name, target.getName().getString()));
        }
        if (MISS_CHANCE.v(name) > 0 && ThreadLocalRandom.current().nextDouble() * 100.0 < MISS_CHANCE.v(name)) {
            run(server, "player " + name + " swing");
        } else {
            run(server, "player " + name + " attack once");
        }
        f.attackTick = globalTick;
    }

    /** Re-sends the forward/backward key that matches the current keep-distance mode. */
    static void axisCmd(MinecraftServer server, String name, Fight f) {
        if (f.keepMode > 0) {
            run(server, "player " + name + " move forward");
        } else if (f.keepMode < 0) {
            run(server, "player " + name + " move backward");
        }
    }

    static void resumeMove(MinecraftServer server, String name, Fight f) {
        if (f.edgeHold) {
            return;
        }
        axisCmd(server, name, f);
        if (f.keepMode > 0) {
            run(server, "player " + name + " sprint");
        }
    }

    static void stopStrafe(MinecraftServer server, String name, Fight f) {
        run(server, "player " + name + " move");
        axisCmd(server, name, f);
        f.strafeDir = 0;
    }

    /**
     * Re-aims every tick while the target is moving (faster micro-corrections against a strafing target),
     * every other tick while it holds still, and skips the call entirely when already within the aim cone.
     * Depends only on the target's own movement, never the bot's, so the bot's own strafing never throws this off.
     */
    static void maybeAim(MinecraftServer server, String name, Fight f, ServerPlayer bot, ServerPlayer target) {
        Vec3 tv = target.getDeltaMovement();
        boolean targetMoving = tv.x * tv.x + tv.z * tv.z > 0.01;
        int interval = targetMoving ? 1 : 2;
        if (globalTick % interval != 0) {
            return;
        }
        double cone = AIM_CONE.v(name);
        if (cone > 0 && !targetMoving) {
            double diff = Math.abs(wrapDegrees(directYaw(bot, target) - bot.getYRot()));
            if (diff < cone) {
                return; // already close enough: skip the redundant look command
            }
        }
        run(server, aimCmd(name, bot, target, f));
    }

    static void fightPhase(MinecraftServer server, String name, Fight f,
                            ServerPlayer bot, ServerPlayer target, double dist) {
        double hpNow = bot.getHealth() + bot.getAbsorptionAmount();
        // Jump reset and flinch: right after taking a hit, sometimes jump to shake off the knockback,
        // and separately sometimes hesitate a moment before swinging back (a human-like flinch).
        if (f.lastHp >= 0 && hpNow < f.lastHp - 0.01) {
            if (JUMP_RESET.v(name) > 0 && bot.onGround()
                    && ThreadLocalRandom.current().nextDouble() * 100.0 < JUMP_RESET.v(name)) {
                run(server, "player " + name + " jump once");
            }
            // Punish crit: guarantee the very next swing crits, and skip any flinch — retaliating hard wins out.
            if (PUNISH_CRIT_CHANCE.v(name) > 0
                    && ThreadLocalRandom.current().nextDouble() * 100.0 < PUNISH_CRIT_CHANCE.v(name)) {
                f.punishCrit = true;
                f.flinchUntil = globalTick;
            } else if (FLINCH_CHANCE.v(name) > 0
                    && ThreadLocalRandom.current().nextDouble() * 100.0 < FLINCH_CHANCE.v(name)) {
                int lo = Math.min(FLINCH_TICKS_MIN.asInt(name), FLINCH_TICKS_MAX.asInt(name));
                int hi = Math.max(FLINCH_TICKS_MIN.asInt(name), FLINCH_TICKS_MAX.asInt(name));
                f.flinchUntil = globalTick + lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
            }
        }
        f.lastHp = hpNow;
        // Recovery punish: the target's own weapon just went on cooldown (it swung, hit or miss) — sometimes
        // skip the bot's normal reaction wait so it hits back before the target's weapon recharges.
        double targetScale = target.getAttackStrengthScale(0.5F);
        if (f.lastTargetScale > 0.9 && targetScale < 0.3 && RECOVERY_CHANCE.v(name) > 0
                && ThreadLocalRandom.current().nextDouble() * 100.0 < RECOVERY_CHANCE.v(name)) {
            f.reactAt = globalTick;
        }
        f.lastTargetScale = targetScale;
        // Totem punish: the target just consumed a totem of undying (it was in an equipment slot, now it isn't).
        boolean hasTotemNow = target.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.TOTEM_OF_UNDYING)
                || target.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.TOTEM_OF_UNDYING);
        if (f.targetHadTotem && !hasTotemNow && target.isAlive() && TOTEM_PUNISH_TICKS.asInt(name) > 0) {
            f.critChainUntil = Math.max(f.critChainUntil, globalTick + TOTEM_PUNISH_TICKS.asInt(name));
        }
        f.targetHadTotem = hasTotemNow;
        // Track hits taken from this target: feeds RandomRepeatedComboHits, and cancels any combo we're running.
        LivingEntity botHurtBy = bot.getLastHurtByMob();
        int botHurtStampNow = bot.getLastHurtByMobTimestamp();
        if (botHurtBy == target && botHurtStampNow != f.botHurtStamp) {
            f.botHurtStamp = botHurtStampNow;
            f.comboHitsStreak++;
            if (f.comboActive != 0) {
                f.comboActive = 0;
                f.comboType = -1;
            }
        }
        // RandomRepeatedComboHits: being comboed for a random run of hits triggers an escape attempt.
        if (f.comboHitsStreak > 0) {
            if (f.comboHitsThreshold < 0) {
                int lo = Math.min(COMBOHITS_MINHIT.asInt(name), COMBOHITS_MAXHIT.asInt(name));
                int hi = Math.max(COMBOHITS_MINHIT.asInt(name), COMBOHITS_MAXHIT.asInt(name));
                f.comboHitsThreshold = lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
            }
            if (f.comboHitsStreak >= f.comboHitsThreshold
                    && ThreadLocalRandom.current().nextDouble() * 100.0 < COMBOHITS_ESCAPECHANCE.v(name)) {
                f.comboHitsStreak = 0;
                f.comboHitsThreshold = -1;
                if (startComboEscape(server, name, f, bot, target)) {
                    return;
                }
            }
        }
        // Weapon/armor durability swap and exp-bottle mending repair run continuously in tickArmor.
        // Splash potions: heal when hurt, buff when the target is far. It runs away first if it has to.
        int potionWanted = POTIONS.on(name) && globalTick >= f.nextPotionTick ? wantedPotion(bot, f, dist, hpNow, name) : 0;
        if (potionWanted != 0) {
            startPotionRun(server, name, f, dist, potionWanted, hpNow);
            return;
        }
        // Very low health: web itself in and eat inside the cocoon.
        if (cocoonReady(bot, f, hpNow, name) && startCocoon(server, name, f, bot)) {
            return;
        }
        // Panic: at very low health, sometimes lay cobwebs behind itself before running, instead of just running.
        if (WEBTRAP_CHANCE.v(name) > 0 && WEBTRAP_HEARTS.v(name) > 0 && hpNow <= WEBTRAP_HEARTS.v(name) * 2.0
                && globalTick >= f.nextEatTick && findFoodIndex(bot) >= 0
                && countItem(bot, Items.COBWEB) >= Math.max(2, WEBTRAP_COUNT.asInt(name))
                && ThreadLocalRandom.current().nextDouble() * 100.0 < WEBTRAP_CHANCE.v(name)
                && startWebTrap(server, name, f, bot, target)) {
            return;
        }
        // Low health, or low hunger: run away bunny hopping, then eat — or, sometimes, launch/teleport away instead.
        boolean hungry = HUNGER_EAT_LEVEL.v(name) > 0 && bot.getFoodData().getFoodLevel() <= HUNGER_EAT_LEVEL.asInt(name);
        boolean lowHealth = EAT_HEARTS.v(name) > 0 && hpNow <= EAT_HEARTS.v(name) * 2.0;
        if ((lowHealth || hungry) && globalTick >= f.nextEatTick && findFoodIndex(bot) >= 0) {
            if (startItemEscape(server, name, f, bot, target)) {
                return;
            }
            startFlee(server, name, f, 0);
            return;
        }
        // Placing a cobweb on the target (started after a landed hit): the rest waits.
        if (webPlaceStep(server, name, f, bot, target)) {
            return;
        }
        // Guaranteed web crit: if the target is fully boxed in by cobwebs, mine one of the surrounding
        // webs open first (its own cell stays webbed) before anything else.
        if (breakoutStep(server, name, f, bot, target)) {
            return;
        }
        maybeAim(server, name, f, bot, target);
        // Vulnerable-target pressure: the target is eating or fleeing, so force an airborne fall-crit and close in hard.
        boolean vulnerable = targetVulnerable(bot, target);
        // Archer rush: the target drew a bow/crossbow — rush in tight and strafe hard instead of holding the
        // normal gap, since normal keep-distance just gives it a clean shot.
        boolean archer = ARCHER_RUSH.on(name)
                && (target.getMainHandItem().is(Items.BOW) || target.getMainHandItem().is(Items.CROSSBOW));
        if (vulnerable && FALL_CRIT.on(name) && bot.onGround() && !f.hopping && !f.crit
                && dist <= JUMP_RANGE.v(name)) {
            run(server, "player " + name + " jump once");
        }
        // Ledges and lava on the way to the target and on the way back.
        double hx = target.getX() - bot.getX();
        double hz = target.getZ() - bot.getZ();
        double hl = Math.sqrt(hx * hx + hz * hz);
        boolean edgeAhead = false;
        boolean edgeBehind = false;
        if (VOID_AWARE.on(name) && hl > 0.3) {
            double ux = hx / hl;
            double uz = hz / hl;
            edgeAhead = hazardAt(bot.level(), bot.getX() + ux * 1.4, bot.getY(), bot.getZ() + uz * 1.4);
            edgeBehind = hazardAt(bot.level(), bot.getX() - ux * 1.4, bot.getY(), bot.getZ() - uz * 1.4);
        }
        // Web avoidance: a cobweb dead ahead (that isn't the target's own cell) gets sidestepped, not walked into.
        if (WEB_AVOID.on(name) && hl > 0.3 && f.strafeDir == 0 && !f.hopping && !f.edgeHold) {
            double ux = hx / hl;
            double uz = hz / hl;
            if (webAheadOf(bot.level(), bot.getX() + ux * 1.2, bot.getY(), bot.getZ() + uz * 1.2, target.blockPosition())) {
                f.strafeDir = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
                run(server, "player " + name + " move " + (f.strafeDir > 0 ? "left" : "right"));
                axisCmd(server, name, f);
                f.sidestepUntil = globalTick + 10;
                f.nextStrafeTick = globalTick + 12;
            }
        }
        if (!f.started && !f.paused) {
            run(server, "player " + name + " autojump true");
            run(server, "player " + name + " sprint");
            run(server, "player " + name + " move forward");
            f.keepMode = 1;
            f.edgeHold = false;
            f.started = true;
        }
        if (f.edgeHold) {
            if (!edgeAhead) {
                f.edgeHold = false;
                f.started = false; // walk on again next tick
            }
        } else if (edgeAhead && f.keepMode >= 0 && dist > 1.5 && !f.paused) {
            f.edgeHold = true;
            f.keepMode = 0;
            f.strafeDir = 0;
            run(server, "player " + name + " move"); // stop at the ledge
            if (f.hopping) {
                run(server, "player " + name + " jump");
                f.hopping = false;
            }
        }
        // Shield stun: when the target raises a shield, sometimes swap to an axe (axes disable shields).
        boolean targetBlocks = target.isBlocking();
        if (targetBlocks && !f.targetBlocking) {
            f.targetBlocking = true;
            if (STUN_CHANCE.v(name) > 0 && findAxeSlot(bot) >= 0
                    && ThreadLocalRandom.current().nextDouble() * 100.0 < STUN_CHANCE.v(name)) {
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
        // Combo: two ticks after a swing (so the sprint hit has landed), run the active combo's next step.
        if (f.attackTick >= 0 && globalTick - f.attackTick >= 2) {
            f.attackTick = -1;
            if (f.comboActive != 0 && !f.edgeHold) {
                int type = f.comboMixed
                        ? (MIX_SWITCHCHANCE.v(name) > 0
                                && ThreadLocalRandom.current().nextDouble() * 100.0 < MIX_SWITCHCHANCE.v(name)
                                ? pickComboType(name) : f.comboType)
                        : f.comboType;
                f.comboType = type;
                runComboStep(server, name, f, bot, target, type, edgeBehind);
                if (f.comboMixed) {
                    f.comboMixHitsLeft--;
                    if (f.comboMixHitsLeft <= 0) {
                        f.comboActive = 0;
                        f.comboType = -1;
                    }
                }
            } else if (POSTSWING_SHIELD_CHANCE.v(name) > 0 && !f.blocking
                    && bot.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD)
                    && ThreadLocalRandom.current().nextDouble() * 100.0 < POSTSWING_SHIELD_CHANCE.v(name)) {
                // Not comboing: raise the shield right after the hit lands instead of swinging straight through.
                run(server, "player " + name + " use continuous");
                f.blocking = true;
                int lo = Math.min(POSTSWING_SHIELD_TICKS_MIN.asInt(name), POSTSWING_SHIELD_TICKS_MAX.asInt(name));
                int hi = Math.max(POSTSWING_SHIELD_TICKS_MIN.asInt(name), POSTSWING_SHIELD_TICKS_MAX.asInt(name));
                f.blockUntil = globalTick + Math.max(2, lo + ThreadLocalRandom.current().nextInt(hi - lo + 1));
            }
        }
        if (f.paused) {
            if (globalTick >= f.pauseUntil) {
                resumeMove(server, name, f);
                f.paused = false;
                f.nextStrafeTick = globalTick; // pick a strafe side again right away
            }
            return;
        }
        if (!f.edgeHold) {
            // Bunny hop while closing a gap; stop hopping when close.
            if (f.hopping) {
                if (!BUNNY_HOP.on(name) || dist <= HOP_STOP.v(name)) {
                    run(server, "player " + name + " jump");
                    f.hopping = false;
                }
            } else if (BUNNY_HOP.on(name) && !f.crit && dist > HOP_STOP.v(name) + HOP_RESUME_MARGIN) {
                run(server, "player " + name + " jump continuous");
                f.hopping = true;
                if (f.keepMode != 1) {
                    f.keepMode = 1;
                    run(server, "player " + name + " move forward");
                    run(server, "player " + name + " sprint");
                }
            }
            // Keep distance: back off when too close, close in when too far, hold the gap in between.
            // While the target is vulnerable (eating/fleeing), use the tight pressure distance instead.
            double effectiveKeep = (vulnerable || archer) ? PRESSURE_KEEP_DIST.v(name) : KEEP_DISTANCE.v(name);
            if (effectiveKeep > 0) {
                if (!f.hopping && !f.crit) {
                    double keep = effectiveKeep;
                    int want = dist < keep - KEEP_BAND ? -1 : (dist > keep + KEEP_BAND ? 1 : 0);
                    if (want < 0 && edgeBehind) {
                        want = 0;
                    }
                    if (want != f.keepMode) {
                        f.keepMode = want;
                        if (want < 0) {
                            run(server, "player " + name + " move backward");
                        } else if (want > 0) {
                            run(server, "player " + name + " move forward");
                            run(server, "player " + name + " sprint");
                        } else {
                            run(server, "player " + name + " move"); // hold the gap; strafing carries on
                            f.strafeDir = 0;
                            f.nextStrafeTick = globalTick;
                        }
                    }
                }
            } else if (f.keepMode != 1) {
                f.keepMode = 1;
                run(server, "player " + name + " move forward");
                run(server, "player " + name + " sprint");
            }
            // Strafe: each interval, roll the strafe chance to switch sides (or walk straight).
            if (STRAFE.on(name) && !f.hopping && !f.crit && dist <= STRAFE_MAX_DIST) {
                if (globalTick >= f.nextStrafeTick) {
                    if (ThreadLocalRandom.current().nextDouble() * 100.0 < (archer ? 100.0 : STRAFE_CHANCE.v(name))) {
                        f.strafeDir = f.strafeDir == 0
                                ? (ThreadLocalRandom.current().nextBoolean() ? 1 : -1)
                                : -f.strafeDir;
                        run(server, "player " + name + " move " + (f.strafeDir > 0 ? "left" : "right"));
                        axisCmd(server, name, f);
                    } else if (f.strafeDir != 0) {
                        stopStrafe(server, name, f);
                    }
                    int base = Math.max(2, STRAFE_TICKS.asInt(name));
                    f.nextStrafeTick = globalTick + base + ThreadLocalRandom.current().nextInt(base / 2 + 1);
                }
            } else if (f.strafeDir != 0 && globalTick >= f.sidestepUntil) {
                stopStrafe(server, name, f);
            }
            // Stuck detection: if the bot barely moved for a second while it should be closing in, hop and sidestep.
            if (STUCK.on(name) && globalTick % 20 == 0) {
                Vec3 here = bot.position();
                if (f.lastPos != null && f.lastPos.distanceTo(here) < 0.2 && !f.blocking
                        && dist > ATTACK_RANGE.v(name) + 1.0) {
                    run(server, "player " + name + " jump once");
                    f.strafeDir = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
                    run(server, "player " + name + " move " + (f.strafeDir > 0 ? "left" : "right"));
                    axisCmd(server, name, f);
                    f.sidestepUntil = globalTick + 10;
                    f.nextStrafeTick = globalTick + 12;
                }
                f.lastPos = here;
            }
            // Random jumps while close: less predictable, and a jump that ends in a fall crit if fallcrit is on.
            if (JUMP_CHANCE.v(name) > 0 && !f.hopping && !f.crit && dist <= JUMP_RANGE.v(name)
                    && globalTick % 5 == 0 && bot.onGround()
                    && ThreadLocalRandom.current().nextDouble() * 100.0 < JUMP_CHANCE.v(name)) {
                run(server, "player " + name + " jump once");
            }
        }
        // A sprint-hit cancels sprinting (that is what gives the extra knockback), so sprint again.
        if (!f.crit && !f.edgeHold && f.keepMode > 0 && globalTick >= f.sprintHoldUntil && !bot.isSprinting()) {
            run(server, "player " + name + " sprint");
        }
        double reach = reachDistance(bot, target);
        boolean ready = bot.getAttackStrengthScale(0.5F) >= 1.0F && globalTick >= f.flinchUntil;
        // Uppercut combo in progress: hit while still rising from the jump (the opposite of a falling crit).
        if (f.comboUppercutRising) {
            if (!bot.onGround() && bot.getDeltaMovement().y > 0.0 && reach <= ATTACK_RANGE.v(name) && ready) {
                doAttack(server, name, f, target);
                f.comboUppercutRising = false;
                f.sprintHoldUntil = globalTick + 3;
                bot.fallDistance = 0.0F; // the jump-in shouldn't hurt it landing back down
                return;
            } else if (globalTick - f.comboUppercutStart > COMBO_UPPERCUT_WINDOW.asInt(name)
                    || (bot.onGround() && bot.getDeltaMovement().y <= 0.0)) {
                f.comboUppercutRising = false;
                bot.fallDistance = 0.0F;
            }
        }
        // Hit web + combo hit-select: when one of our hits lands, sometimes place a cobweb, and hit-select a combo.
        LivingEntity lastHitter = target.getLastHurtByMob();
        int hitStamp = target.getLastHurtByMobTimestamp();
        if (lastHitter == bot && hitStamp != f.lastHitStamp) {
            f.lastHitStamp = hitStamp;
            f.comboHitsStreak = 0;
            f.comboHitsThreshold = -1;
            startWebPlace(server, name, f, bot, target);
            if (f.comboActive == 0 && COMBO_CHANCE.v(name) > 0
                    && ThreadLocalRandom.current().nextDouble() * 100.0 < COMBO_CHANCE.v(name)) {
                startCombo(f, name);
            }
        }
        // Mace awareness: the target is airborne above the bot with a mace out — block immediately, no roll,
        // no range check. A mace's damage scales with the wielder's fall distance, so this is worth blocking outright.
        boolean maceThreat = MACE_AWARE.on(name) && !f.blocking
                && (target.getMainHandItem().is(Items.MACE) || target.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.MACE))
                && !target.onGround() && target.getY() > bot.getY() + 1.0
                && bot.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD);
        if (maceThreat) {
            run(server, "player " + name + " use continuous");
            f.blocking = true;
            f.blockUntil = globalTick + Math.max(2, SHIELD_TICKS_MAX.asInt(name));
            return;
        }
        // Shield: only within shieldrange of the target, on a per-swing chance roll, and never while comboing.
        if (f.blocking) {
            if (globalTick >= f.blockUntil) {
                run(server, "player " + name + " use"); // lower the shield
                f.blocking = false;
            } else {
                return; // stay behind the shield: no attacking
            }
        } else if (SHIELD_CHANCE.v(name) > 0 && f.critChainUntil <= globalTick && !ready
                && dist <= SHIELD_RANGE.v(name) && f.comboActive == 0
                && bot.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD)
                && ThreadLocalRandom.current().nextDouble() * 100.0 < SHIELD_CHANCE.v(name)) {
            run(server, "player " + name + " use continuous"); // hold right-click: shield up
            f.blocking = true;
            int lo = Math.min(SHIELD_TICKS.asInt(name), SHIELD_TICKS_MAX.asInt(name));
            int hi = Math.max(SHIELD_TICKS.asInt(name), SHIELD_TICKS_MAX.asInt(name));
            f.blockUntil = globalTick + Math.max(2, lo + ThreadLocalRandom.current().nextInt(hi - lo + 1));
            return;
        }
        // Stun strike: axe in hand and the target is blocking. Hit at once (the charge doesn't matter for
        // knocking a shield down), then go back to the sword on the very next tick.
        if (f.axeMode && bot.getMainHandItem().is(ItemTags.AXES)) {
            if (reach <= ATTACK_RANGE.v(name)) {
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
            if (falling && reach <= ATTACK_RANGE.v(name) && ready) {
                doAttack(server, name, f, target);
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
        if (FALL_CRIT.on(name) && ready && reach <= ATTACK_RANGE.v(name)
                && !bot.onGround() && bot.getDeltaMovement().y < 0.0) {
            run(server, "player " + name + " unsprint");
            doAttack(server, name, f, target);
            f.reactAt = -1;
            f.sprintHoldUntil = globalTick + 3;
            return;
        }
        // Guaranteed web crit: the target is currently slowed by a cobweb, so skip the crit-chance roll entirely.
        boolean guaranteedWebCrit = WEB_CRIT.on(name) && inCobweb(target);
        boolean critPossible = CRIT_CHANCE.v(name) > 0 && dist <= CRIT_RANGE.v(name);
        if (ready && (reach <= ATTACK_RANGE.v(name) || critPossible || guaranteedWebCrit || f.punishCrit)) {
            // Reaction time: wait a random number of ticks between the minimum and maximum before acting.
            if (f.reactAt < 0) {
                int lo = Math.min(REACT_MIN.asInt(name), REACT_MAX.asInt(name));
                int hi = Math.max(REACT_MIN.asInt(name), REACT_MAX.asInt(name));
                f.reactAt = globalTick + lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
            }
            if (globalTick >= f.reactAt) {
                f.reactAt = -1;
                boolean chaining = f.critChainUntil > globalTick;
                boolean tryCrit;
                if (f.punishCrit) {
                    tryCrit = bot.onGround() && !f.hopping;
                    f.punishCrit = false;
                } else if (guaranteedWebCrit) {
                    tryCrit = bot.onGround() && !f.hopping;
                } else if (chaining) {
                    // Guaranteed crit for the rest of the burst window.
                    tryCrit = critPossible && bot.onGround() && !f.hopping;
                } else {
                    tryCrit = critPossible && bot.onGround() && !f.hopping
                            && ThreadLocalRandom.current().nextDouble() * 100.0 < CRIT_CHANCE.v(name);
                    // A crit against a target that's eating or sprinting away can kick off a chain of guaranteed crits.
                    boolean critVulnerable = tryCrit && vulnerable;
                    if (critVulnerable && CRIT_CHAIN_CHANCE.v(name) > 0
                            && ThreadLocalRandom.current().nextDouble() * 100.0 < CRIT_CHAIN_CHANCE.v(name)) {
                        int lo = Math.min(CRIT_CHAIN_MIN.asInt(name), CRIT_CHAIN_MAX.asInt(name));
                        int hi = Math.max(CRIT_CHAIN_MIN.asInt(name), CRIT_CHAIN_MAX.asInt(name));
                        f.critChainUntil = globalTick + lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
                    }
                }
                if (tryCrit) {
                    // Crits need a falling, non-sprinting hit: stop sprinting and jump.
                    run(server, "player " + name + " unsprint");
                    run(server, "player " + name + " jump once");
                    f.crit = true;
                    f.critStart = globalTick;
                } else if (reach <= ATTACK_RANGE.v(name)) {
                    doAttack(server, name, f, target);
                }
            }
        } else {
            f.reactAt = -1;
        }
    }

    // ---------------------------------------------------------------- combo system

    /**
     * Each of these is rolled as its own literal percent chance, in turn, not as a share of some total — so
     * setting combouppercut to 20 always means a 20% chance of uppercut on this roll, regardless of what the
     * other three combo settings are set to. The first one to hit wins; if none hit, it falls back to s-tap.
     */
    static int pickComboType(String name) {
        if (COMBO_STAP_CHANCE.v(name) > 0
                && ThreadLocalRandom.current().nextDouble() * 100.0 < COMBO_STAP_CHANCE.v(name)) {
            return 0;
        }
        if (COMBO_WTAP_CHANCE.v(name) > 0
                && ThreadLocalRandom.current().nextDouble() * 100.0 < COMBO_WTAP_CHANCE.v(name)) {
            return 1;
        }
        if (COMBO_UPPERCUT_CHANCE.v(name) > 0
                && ThreadLocalRandom.current().nextDouble() * 100.0 < COMBO_UPPERCUT_CHANCE.v(name)) {
            return 2;
        }
        if (COMBO_STRAFECOMBO_CHANCE.v(name) > 0
                && ThreadLocalRandom.current().nextDouble() * 100.0 < COMBO_STRAFECOMBO_CHANCE.v(name)) {
            return 3;
        }
        return 0;
    }

    /** Hit-select decided to combo: pick MixCombo (a series that may change type each hit) or one single type. */
    static void startCombo(Fight f, String name) {
        boolean mixed = MIX_CHANCE.v(name) > 0
                && ThreadLocalRandom.current().nextDouble() * 100.0 < MIX_CHANCE.v(name);
        f.comboActive = 1;
        f.comboMixed = mixed;
        f.comboMixHitsLeft = mixed ? Math.max(1, MIX_MAXHITS.asInt(name)) : Integer.MAX_VALUE;
        f.comboType = pickComboType(name);
    }

    /** Runs one step of the active combo, two ticks after a swing landed. */
    static void runComboStep(MinecraftServer server, String name, Fight f, ServerPlayer bot, ServerPlayer target,
                              int type, boolean edgeBehind) {
        if (f.hopping) {
            run(server, "player " + name + " jump"); // stops continuous jumping
            f.hopping = false;
        }
        switch (type) {
            case 0 -> { // s-tap: step back a moment, then go again
                if (!edgeBehind) {
                    run(server, "player " + name + " move backward");
                } else {
                    run(server, "player " + name + " move");
                    f.strafeDir = 0;
                }
                f.paused = true;
                f.pauseUntil = globalTick + Math.max(1, COMBO_TAP_TICKS.asInt(name));
            }
            case 1 -> { // w-tap: let go of forward a moment, then go again
                run(server, "player " + name + " move");
                f.strafeDir = 0;
                f.paused = true;
                f.pauseUntil = globalTick + Math.max(1, COMBO_TAP_TICKS.asInt(name));
            }
            case 2 -> { // uppercut: sprint-jump in and try to hit on the way up (not a falling crit)
                double dx = target.getX() - bot.getX();
                double dz = target.getZ() - bot.getZ();
                double d2 = Math.sqrt(dx * dx + dz * dz);
                if (bot.onGround() && d2 <= COMBO_UPPERCUT_RANGE.v(name)) {
                    run(server, "player " + name + " sprint");
                    run(server, "player " + name + " move forward");
                    run(server, "player " + name + " jump once");
                    f.comboUppercutRising = true;
                    f.comboUppercutStart = globalTick;
                }
                // no pause here: the bot keeps closing in normally while the rising-hit window is open
            }
            case 3 -> { // strafecombo: circle strafe while still hitting
                f.strafeDir = f.strafeDir == 0
                        ? (ThreadLocalRandom.current().nextBoolean() ? 1 : -1)
                        : -f.strafeDir;
                run(server, "player " + name + " move " + (f.strafeDir > 0 ? "left" : "right"));
                axisCmd(server, name, f);
                f.paused = true;
                f.pauseUntil = globalTick + Math.max(1, COMBO_STRAFE_SWITCH_TICKS.asInt(name));
            }
            default -> { }
        }
    }

    /**
     * RandomRepeatedComboHits: the bot has taken a long streak of hits without landing one back.
     * Randomly weighted pick between wind charge escape, ender pearl escape, panic web trap, or just running.
     * Each option is rolled as its own literal percent chance, in order — not a share of some combined total.
     */
    static boolean startComboEscape(MinecraftServer server, String name, Fight f, ServerPlayer bot, ServerPlayer target) {
        boolean hasWind = findItemIndex(bot, Items.WIND_CHARGE) >= 0;
        boolean hasPearl = findItemIndex(bot, Items.ENDER_PEARL) >= 0;
        boolean hasWeb = countItem(bot, Items.COBWEB) >= Math.max(2, WEBTRAP_COUNT.asInt(name));
        if (COMBOHITS_WINDCHARGECHANCE.v(name) > 0
                && ThreadLocalRandom.current().nextDouble() * 100.0 < COMBOHITS_WINDCHARGECHANCE.v(name)) {
            if (hasWind && startWindEscape(server, name, f, bot, target)) {
                return true;
            }
            if (hasPearl && startPearlEscape(server, name, f, bot, target)) {
                return true;
            }
        }
        if (hasWeb && COMBOHITS_WEBCHANCE.v(name) > 0
                && ThreadLocalRandom.current().nextDouble() * 100.0 < COMBOHITS_WEBCHANCE.v(name)
                && startWebTrap(server, name, f, bot, target)) {
            return true;
        }
        if (COMBOHITS_RUNCHANCE.v(name) > 0
                && ThreadLocalRandom.current().nextDouble() * 100.0 < COMBOHITS_RUNCHANCE.v(name)) {
            startFlee(server, name, f, 0);
            return true;
        }
        startFlee(server, name, f, 0); // nothing rolled: it still has to do something, so run anyway
        return true;
    }

    /**
     * Low-health/hungry retreat: try an item escape (wind charge or ender pearl) before falling back to a
     * normal bunny-hop run. Wind charge and ender pearl are picked 50/50 when the bot has both, otherwise
     * whichever one it has is used; with neither, this does nothing and the caller falls back to startFlee.
     */
    static boolean startItemEscape(MinecraftServer server, String name, Fight f, ServerPlayer bot, ServerPlayer target) {
        if (WINDCHARGE_HEAL_CHANCE.v(name) <= 0
                || ThreadLocalRandom.current().nextDouble() * 100.0 >= WINDCHARGE_HEAL_CHANCE.v(name)) {
            return false;
        }
        boolean hasWind = findItemIndex(bot, Items.WIND_CHARGE) >= 0;
        boolean hasPearl = findItemIndex(bot, Items.ENDER_PEARL) >= 0;
        if (hasWind && hasPearl) {
            boolean windFirst = ThreadLocalRandom.current().nextBoolean();
            if (windFirst) {
                return startWindEscape(server, name, f, bot, target) || startPearlEscape(server, name, f, bot, target);
            }
            return startPearlEscape(server, name, f, bot, target) || startWindEscape(server, name, f, bot, target);
        }
        if (hasWind) {
            return startWindEscape(server, name, f, bot, target);
        }
        if (hasPearl) {
            return startPearlEscape(server, name, f, bot, target);
        }
        return false;
    }

    // ---------------------------------------------------------------- panic web trap

    /** Turns away from the target and lays cobwebs behind itself, then runs off to eat as normal. */
    static boolean startWebTrap(MinecraftServer server, String name, Fight f, ServerPlayer bot, ServerPlayer target) {
        int idx = findItemIndex(bot, Items.COBWEB);
        int slot = idx < 0 ? -1 : toHotbar(bot, idx, findWeaponSlot(bot));
        if (slot < 0) {
            return false;
        }
        run(server, "player " + name + " stop");
        run(server, "player " + name + " hotbar " + (slot + 1));
        double dx = bot.getX() - target.getX();
        double dz = bot.getZ() - target.getZ();
        double yaw = Math.toDegrees(Math.atan2(-dx, dz)); // facing away from the target
        run(server, "player " + name + " look " + String.format(Locale.ROOT, "%.1f", yaw) + " 90");
        f.phase = Phase.WEBTRAP;
        f.webtrapStage = 1;
        f.webtrapStart = globalTick;
        f.webtrapPlaced = 0;
        f.strafeDir = 0;
        f.started = false;
        f.hopping = false;
        f.paused = false;
        f.crit = false;
        f.blocking = false;
        f.axeMode = false;
        f.placeStage = 0;
        f.attackTick = -1;
        f.edgeHold = false;
        return true;
    }

    static void webtrapPhase(MinecraftServer server, String name, Fight f,
                              ServerPlayer bot, ServerPlayer target, double dist) {
        int elapsed = globalTick - f.webtrapStart;
        int need = Math.max(2, WEBTRAP_COUNT.asInt(name));
        if (f.webtrapStage == 1) {
            if (elapsed >= 1) {
                run(server, "player " + name + " use once"); // web right where the bot is standing
                f.webtrapStage = 2;
                f.webtrapStart = globalTick;
            }
        } else if (f.webtrapStage == 2) {
            if (elapsed >= 2) {
                f.webtrapPlaced++;
                if (f.webtrapPlaced >= need || findItemIndex(bot, Items.COBWEB) < 0) {
                    startFlee(server, name, f, 0); // done: put distance behind the wall of webs, then eat
                    return;
                }
                double dx = bot.getX() - target.getX();
                double dz = bot.getZ() - target.getZ();
                double yaw = Math.toDegrees(Math.atan2(-dx, dz));
                run(server, "player " + name + " look " + String.format(Locale.ROOT, "%.1f", yaw) + " 0");
                run(server, "player " + name + " move backward"); // step back before the next web
                f.webtrapStage = 3;
                f.webtrapStart = globalTick;
            }
        } else if (f.webtrapStage == 3) {
            if (elapsed >= 4) {
                run(server, "player " + name + " move");
                double dx = bot.getX() - target.getX();
                double dz = bot.getZ() - target.getZ();
                double yaw = Math.toDegrees(Math.atan2(-dx, dz));
                run(server, "player " + name + " look " + String.format(Locale.ROOT, "%.1f", yaw) + " 90");
                f.webtrapStage = 1;
                f.webtrapStart = globalTick;
            }
        }
    }

    // ---------------------------------------------------------------- wind charge escape

    /**
     * Faces away from the target, then holds still (still drifting away via "move forward", no hop) for
     * windchargewaitticks before jumping and throwing the wind charge in the same beat — the jump's upward
     * velocity stacks with the charge's launch for extra height. Eats mid-air while still moving forward.
     */
    static boolean startWindEscape(MinecraftServer server, String name, Fight f, ServerPlayer bot, ServerPlayer target) {
        int idx = findItemIndex(bot, Items.WIND_CHARGE);
        int slot = idx < 0 ? -1 : toHotbar(bot, idx, findWeaponSlot(bot));
        if (slot < 0) {
            return false;
        }
        run(server, "player " + name + " stop");
        run(server, "player " + name + " hotbar " + (slot + 1));
        double dx = bot.getX() - target.getX();
        double dz = bot.getZ() - target.getZ();
        double yaw = Math.toDegrees(Math.atan2(-dx, dz)); // facing away from the target
        run(server, "player " + name + " look " + String.format(Locale.ROOT, "%.1f", yaw) + " 65"); // down and away
        run(server, "player " + name + " move forward"); // keep drifting away during the wait, no hop
        f.phase = Phase.WINDESCAPE;
        f.windStage = 0;
        f.windStart = globalTick;
        f.started = false;
        f.hopping = false;
        f.paused = false;
        f.crit = false;
        f.blocking = false;
        f.axeMode = false;
        f.placeStage = 0;
        f.strafeDir = 0;
        f.attackTick = -1;
        f.edgeHold = false;
        return true;
    }

    static void windEscapePhase(MinecraftServer server, String name, Fight f,
                                 ServerPlayer bot, ServerPlayer target, double dist) {
        int elapsed = globalTick - f.windStart;
        bot.fallDistance = 0.0F; // the self-launch shouldn't hurt the bot when it comes back down
        if (f.windStage == 0) {
            if (elapsed >= Math.max(1, WINDCHARGE_WAIT_TICKS.asInt(name))) {
                run(server, "player " + name + " jump once");
                run(server, "player " + name + " use once"); // throw the wind charge under itself
                f.windStage = 1;
                f.windStart = globalTick;
            }
        } else if (f.windStage == 1) {
            if (!bot.onGround() || elapsed > 20) {
                startEat(server, name, f, bot, false); // eat while airborne, still drifting forward
            }
        }
    }

    // ---------------------------------------------------------------- ender pearl escape

    /** Faces away from the target and throws an ender pearl to teleport off, then eats once it lands. */
    static boolean startPearlEscape(MinecraftServer server, String name, Fight f, ServerPlayer bot, ServerPlayer target) {
        int idx = findItemIndex(bot, Items.ENDER_PEARL);
        int slot = idx < 0 ? -1 : toHotbar(bot, idx, findWeaponSlot(bot));
        if (slot < 0) {
            return false;
        }
        run(server, "player " + name + " stop");
        run(server, "player " + name + " hotbar " + (slot + 1));
        double dx = bot.getX() - target.getX();
        double dz = bot.getZ() - target.getZ();
        double yaw = Math.toDegrees(Math.atan2(-dx, dz)); // facing away from the target
        run(server, "player " + name + " look " + String.format(Locale.ROOT, "%.1f", yaw) + " -10"); // slightly up, for distance
        run(server, "player " + name + " move forward"); // keep drifting away while it lines up the throw
        f.phase = Phase.PEARLESCAPE;
        f.pearlStage = 1;
        f.pearlStart = globalTick;
        f.pearlFrom = bot.position();
        f.started = false;
        f.hopping = false;
        f.paused = false;
        f.crit = false;
        f.blocking = false;
        f.axeMode = false;
        f.placeStage = 0;
        f.strafeDir = 0;
        f.attackTick = -1;
        f.edgeHold = false;
        return true;
    }

    static void pearlEscapePhase(MinecraftServer server, String name, Fight f,
                                  ServerPlayer bot, ServerPlayer target, double dist) {
        int elapsed = globalTick - f.pearlStart;
        bot.fallDistance = 0.0F; // teleport landings shouldn't hurt the bot either
        if (f.pearlStage == 1) {
            if (elapsed >= 1) {
                run(server, "player " + name + " use once"); // throw the pearl
                f.pearlStage = 2;
                f.pearlStart = globalTick;
            }
        } else if (f.pearlStage == 2) {
            boolean teleported = f.pearlFrom != null && bot.position().distanceTo(f.pearlFrom) > PEARL_TELEPORT_DIST;
            if (teleported || elapsed > PEARL_TIMEOUT_TICKS) {
                run(server, "player " + name + " move forward"); // keep drifting on landing
                startEat(server, name, f, bot, false);
            }
        }
    }

    // ---------------------------------------------------------------- running away, eating, cocoon, potions

    /** purpose 0 = run away to eat, purpose 1 = run away to throw potions out of the target's reach. */
    static void startFlee(MinecraftServer server, String name, Fight f, int purpose) {
        run(server, "player " + name + " stop");
        run(server, "player " + name + " autojump true");
        run(server, "player " + name + " sprint");
        run(server, "player " + name + " move forward");
        run(server, "player " + name + " jump continuous");
        f.phase = Phase.FLEE;
        f.fleeFor = purpose;
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
        f.edgeHold = false;
        f.fleeStuckTicks = 0;
        f.fleeLastX = Double.NaN;
        f.fleeLastZ = Double.NaN;
    }

    static void fleePhase(MinecraftServer server, String name, Fight f,
                           ServerPlayer bot, ServerPlayer target, double dist) {
        double hp = bot.getHealth() + bot.getAbsorptionAmount();
        if (f.fleeFor == 0 && cocoonReady(bot, f, hp, name) && startCocoon(server, name, f, bot)) {
            return; // hurt badly while running: web in right here instead
        }
        // Stuck against something (a wall, a fence, autojump not clearing it): barely moved despite sprinting.
        // If it can't run through it, and it has a wind charge, launch over it instead of staying stuck.
        if (!Double.isNaN(f.fleeLastX)) {
            double sdx = bot.getX() - f.fleeLastX;
            double sdz = bot.getZ() - f.fleeLastZ;
            if (sdx * sdx + sdz * sdz < 0.0009) {
                f.fleeStuckTicks++;
            } else {
                f.fleeStuckTicks = 0;
            }
        }
        f.fleeLastX = bot.getX();
        f.fleeLastZ = bot.getZ();
        if (f.fleeStuckTicks >= Math.max(2, WINDCHARGE_BLOCKED_TICKS.asInt(name))
                && findItemIndex(bot, Items.WIND_CHARGE) >= 0
                && startWindEscape(server, name, f, bot, target)) {
            return;
        }
        if (globalTick % 2 == 0) {
            // Face directly away from the target — or toward a nearby cobweb, if there is one, to slow the chase.
            BlockPos web = WEB_ZONE_AWARE.on(name) && f.fleeFor == 0 ? nearbyWeb(bot, WEB_ZONE_RADIUS.v(name)) : null;
            double baseYaw;
            if (web != null) {
                double dx = web.getX() + 0.5 - bot.getX();
                double dz = web.getZ() + 0.5 - bot.getZ();
                baseYaw = Math.toDegrees(Math.atan2(-dx, dz));
            } else {
                double dx = bot.getX() - target.getX();
                double dz = bot.getZ() - target.getZ();
                baseYaw = Math.toDegrees(Math.atan2(-dx, dz));
            }
            double yaw = baseYaw;
            if (VOID_AWARE.on(name) || WEB_AVOID.on(name)) {
                double[] turns = {0, 50, -50, 100, -100};
                BlockPos targetCell = target.blockPosition();
                for (double turn : turns) {
                    double a = Math.toRadians(baseYaw + turn);
                    double px = bot.getX() - Math.sin(a) * 1.6;
                    double pz = bot.getZ() + Math.cos(a) * 1.6;
                    boolean bad = (VOID_AWARE.on(name) && hazardAt(bot.level(), px, bot.getY(), pz))
                            || (WEB_AVOID.on(name) && webAheadOf(bot.level(), px, bot.getY(), pz, targetCell));
                    if (!bad) {
                        yaw = baseYaw + turn;
                        break;
                    }
                }
            }
            run(server, "player " + name + " look " + String.format(Locale.ROOT, "%.1f", yaw) + " 0");
        }
        if (!bot.isSprinting()) {
            run(server, "player " + name + " sprint");
        }
        if (dist >= FLEE_DIST.v(name) || globalTick - f.phaseStart > FLEE_MAX_TICKS) {
            if (f.fleeFor == 1) {
                enterPotionPhase(server, name, f);
            } else {
                startEat(server, name, f, bot, false);
            }
        }
    }

    static void startEat(MinecraftServer server, String name, Fight f, ServerPlayer bot, boolean stayPut) {
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
        // Look mostly forward (a slight upward tilt, not straight up) so it looks human while it eats.
        run(server, "player " + name + " look " + String.format(Locale.ROOT, "%.1f", bot.getYRot()) + " -20");
        // Eating only continues while "use" is held, so it must be continuous ("use once" cancels after one tick).
        run(server, "player " + name + " use continuous");
        if (!stayPut) {
            // Keep drifting away while eating: forward (the way it ran, since it looks along that yaw) and hopping.
            run(server, "player " + name + " move forward");
            run(server, "player " + name + " jump continuous");
        }
        f.phase = Phase.EAT;
        f.useStart = globalTick;
    }

    static void eatPhase(MinecraftServer server, String name, Fight f,
                          ServerPlayer bot, ServerPlayer target, double dist) {
        // Vanilla cancels sprinting the moment an item starts being used: re-assert it so eating doesn't slow the bot.
        if (EAT_FULL_SPEED.on(name) && !bot.isSprinting() && globalTick % 2 == 0) {
            run(server, "player " + name + " sprint");
        }
        int elapsed = globalTick - f.useStart;
        ItemStack now = bot.getInventory().getItem(f.eatSlot);
        boolean consumed = now.isEmpty() || !now.is(f.eatItem) || now.getCount() < f.eatStackCount;
        boolean gaveUp = elapsed > EAT_TIMEOUT_TICKS || (elapsed >= 5 && !bot.isUsingItem());
        if (!consumed && !gaveUp) {
            return; // still eating
        }
        run(server, "player " + name + " use"); // let go of right-click so it doesn't start on its own
        f.eaten++;
        double hpNow = bot.getHealth() + bot.getAbsorptionAmount();
        boolean healthy = EAT_UNTIL_HEARTS.v(name) <= 0 || hpNow >= EAT_UNTIL_HEARTS.v(name) * 2.0;
        if ((f.eaten >= EAT_COUNT.asInt(name) && healthy) || findFoodIndex(bot) < 0) {
            endRetreat(server, name, f); // done: fight again (and climb out of the cocoon if it was in one)
        } else {
            startEat(server, name, f, bot, f.inCocoon); // keep eating, even if the target is right on top of the bot
        }
    }

    static void endRetreat(MinecraftServer server, String name, Fight f) {
        run(server, "player " + name + " stop");
        f.reset();
        f.nextEatTick = globalTick + EAT_COOLDOWN_TICKS;
    }

    /** Web cocoon: look straight down and right-click twice. The first web fills the bot's own block, the second lands on top of it. */
    static boolean startCocoon(MinecraftServer server, String name, Fight f, ServerPlayer bot) {
        int idx = findItemIndex(bot, Items.COBWEB);
        int slot = idx < 0 ? -1 : toHotbar(bot, idx, findWeaponSlot(bot));
        if (slot < 0 || !cocoonGroundOk(bot)) {
            return false;
        }
        run(server, "player " + name + " stop");
        run(server, "player " + name + " hotbar " + (slot + 1));
        f.phase = Phase.COCOON;
        f.cocoonStage = 1;
        f.cocoonStart = globalTick;
        f.cocoonRetries = 0;
        f.cocoonCheckCell = null;
        f.eaten = 0;
        f.inCocoon = false;
        f.started = false;
        f.hopping = false;
        f.paused = false;
        f.crit = false;
        f.blocking = false;
        f.axeMode = false;
        f.placeStage = 0;
        f.strafeDir = 0;
        f.attackTick = -1;
        f.edgeHold = false;
        return true;
    }

    static boolean cocoonSettled(ServerPlayer bot) {
        Vec3 v = bot.getDeltaMovement();
        return bot.onGround() && v.x * v.x + v.z * v.z < 0.0025;
    }

    static void cocoonPhase(MinecraftServer server, String name, Fight f,
                             ServerPlayer bot, ServerPlayer target, double dist) {
        int elapsed = globalTick - f.cocoonStart;
        if (f.cocoonStage == 1) {
            // Wait out any combo knockback before placing the first web, so it lands at the bot's actual feet.
            if (!cocoonSettled(bot)) {
                f.cocoonStart = globalTick;
                return;
            }
            if (elapsed >= 1) {
                if (findItemIndex(bot, Items.COBWEB) < 0) {
                    startFlee(server, name, f, 0); // ran out mid-retry: just run and eat instead
                    return;
                }
                f.cocoonCheckCell = bot.blockPosition();
                run(server, "player " + name + " look " + String.format(Locale.ROOT, "%.1f", bot.getYRot()) + " 90");
                run(server, "player " + name + " use once"); // first web, in the bot's own block
                f.cocoonStage = 2;
                f.cocoonStart = globalTick;
            }
        } else if (f.cocoonStage == 2) {
            if (elapsed >= 2) {
                boolean placed = f.cocoonCheckCell != null && bot.level().getBlockState(f.cocoonCheckCell).is(Blocks.COBWEB);
                if (!placed) {
                    f.cocoonRetries++;
                    if (f.cocoonRetries > 3) {
                        startFlee(server, name, f, 0); // knockback kept beating us to it: give up and just run
                        return;
                    }
                    f.cocoonStage = 1; // retry web 1
                    f.cocoonStart = globalTick;
                    return;
                }
                if (!cocoonSettled(bot)) {
                    f.cocoonStart = globalTick; // wait for the bot to stop moving before the second web too
                    return;
                }
                if (findItemIndex(bot, Items.COBWEB) < 0) {
                    f.inCocoon = true;
                    startEat(server, name, f, bot, true); // only one web landed, but it's out of cobwebs: eat anyway
                    return;
                }
                f.cocoonCheckCell = bot.blockPosition().above();
                run(server, "player " + name + " look " + String.format(Locale.ROOT, "%.1f", bot.getYRot()) + " 90");
                run(server, "player " + name + " use once"); // second web, on top of the first
                f.cocoonStage = 3;
                f.cocoonStart = globalTick;
            }
        } else if (f.cocoonStage == 3) {
            if (elapsed >= 2) {
                boolean placed = f.cocoonCheckCell != null && bot.level().getBlockState(f.cocoonCheckCell).is(Blocks.COBWEB);
                if (!placed && f.cocoonRetries <= 3 && findItemIndex(bot, Items.COBWEB) >= 0) {
                    f.cocoonRetries++;
                    f.cocoonStage = 2; // retry web 2 only
                    f.cocoonStart = globalTick;
                    return;
                }
                f.inCocoon = true; // from now on the cobweb escape stays off until the last bite
                startEat(server, name, f, bot, true);
            }
        }
    }

    /**
     * Potion run: throw straight down at its own feet, far away from the target so the target never gets an
     * effect — except a healing potion at very close range, which is thrown from right where the bot stands.
     */
    static void startPotionRun(MinecraftServer server, String name, Fight f, double dist, int kind, double hp) {
        f.thrownMask = 0;
        f.healsRemaining = kind == 1 && POTION_HEARTS2.v(name) > 0 && hp <= POTION_HEARTS2.v(name) * 2.0 ? 2 : 0;
        boolean closeHeal = kind == 1 && dist <= POTION_HEAL_CLOSE_DIST;
        if (dist >= FLEE_DIST.v(name) || closeHeal) {
            enterPotionPhase(server, name, f);
        } else {
            startFlee(server, name, f, 1);
        }
    }

    static void enterPotionPhase(MinecraftServer server, String name, Fight f) {
        run(server, "player " + name + " stop");
        f.phase = Phase.POTION;
        f.potionStage = 0;
        f.potionStart = globalTick;
        f.started = false;
        f.hopping = false;
        f.paused = false;
        f.crit = false;
        f.blocking = false;
        f.axeMode = false;
        f.placeStage = 0;
        f.strafeDir = 0;
        f.attackTick = -1;
        f.edgeHold = false;
    }

    static void potionPhase(MinecraftServer server, String name, Fight f,
                             ServerPlayer bot, ServerPlayer target, double dist) {
        if (f.potionStage == 0) {
            double hp = bot.getHealth() + bot.getAbsorptionAmount();
            int kind = f.healsRemaining > 0 && findPotionIndex(bot, 1) >= 0 ? 1 : wantedPotion(bot, f, dist, hp, name);
            boolean closeHeal = kind == 1 && dist <= POTION_HEAL_CLOSE_DIST;
            if (dist < POTION_SAFE_DIST && !closeHeal) {
                // The target kept up: no potion at all (it would get the effect too), except healing up close.
                f.nextPotionTick = globalTick + POTION_DELAY.asInt(name);
                endRetreat(server, name, f);
                f.nextEatTick = globalTick;
                return;
            }
            if (kind == 0) {
                f.nextPotionTick = globalTick + POTION_DELAY.asInt(name);
                endRetreat(server, name, f); // everything useful has been thrown
                return;
            }
            int idx = findPotionIndex(bot, kind);
            int slot = idx < 0 ? -1 : toHotbar(bot, idx, findWeaponSlot(bot));
            if (slot < 0) {
                f.thrownMask |= 1 << (kind - 1); // can't use this one: try the next kind
                return;
            }
            run(server, "player " + name + " hotbar " + (slot + 1));
            run(server, "player " + name + " look " + String.format(Locale.ROOT, "%.1f", bot.getYRot()) + " 90");
            f.potionKind = kind;
            f.potionStage = 1;
            f.potionStart = globalTick;
        } else if (f.potionStage == 1) {
            if (globalTick - f.potionStart >= 1) {
                run(server, "player " + name + " use once"); // throw it at its own feet
                if (f.potionKind == 1 && f.healsRemaining > 0) {
                    f.healsRemaining--; // part of a queued double heal: leave the normal gate untouched
                } else {
                    f.thrownMask |= 1 << (f.potionKind - 1);
                }
                f.potionStage = 2;
                f.potionStart = globalTick;
            }
        } else if (globalTick - f.potionStart >= 3) {
            f.potionStage = 0; // next potion, if any
        }
    }
}

/**
 * A chest-style settings menu: a vanilla container GUI, so opening and using it needs no client-side mod —
 * only the server needs this jar, exactly like the "stick = range" style menu VexBot uses. Left-click bumps
 * a numeric setting up a step, right-click bumps it down, shift-click makes a bigger jump; a boolean setting
 * toggles on click; the first page cycles the difficulty and playstyle presets.
 */
final class Gui {

    private Gui() {
    }

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int BODY_SIZE = 45; // rows 0-4: settings live here
    private static final int PREV_SLOT = 45;
    private static final int PAGE_LABEL_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    private static final int DIFFICULTY_ROW = 9;   // row 1
    private static final int PLAYSTYLE_ROW = 27;    // row 3

    /** cmd -> category. Order of first insertion decides page order. */
    private static final Map<String, String> CATEGORY = new LinkedHashMap<>();
    private static final List<String> CATEGORY_ORDER = new ArrayList<>();

    private static void cat(String category, String... cmds) {
        if (!CATEGORY_ORDER.contains(category)) {
            CATEGORY_ORDER.add(category);
        }
        for (String cmd : cmds) {
            CATEGORY.put(cmd, category);
        }
    }

    static {
        cat("Combat", "range", "autotarget", "findrange", "aim", "reactmin", "reactmax", "predictaim",
                "aimcone", "critchance", "critrange", "fallcrit", "critchainchance", "critchainmin", "critchainmax",
                "jumpchance", "jumprange", "jumpreset", "keepdistance", "pressurekeep", "strafe", "strafeticks",
                "strafechance", "botfight", "bunnyhop", "hopstop", "punishcritchance", "recoverychance",
                "totempunishticks", "archerrush");
        cat("Eating", "eat", "eatcount", "eatuntil", "hungereat", "eatfullspeed", "flee", "revenge");
        cat("Shield", "shield", "shieldrange", "shieldticks", "shieldticksmax", "postswingshield",
                "postswingshieldticksmin", "postswingshieldticksmax", "stun", "maceaware");
        cat("Combo", "combochance", "combostap", "combowtap", "combouppercut", "combostrafecombo",
                "combotapticks", "uppercutrange", "uppercutwindow", "combostrafeticks",
                "mixchance", "mixmaxhits", "mixswitchchance");
        cat("Escape", "combohitsmin", "combohitsmax", "combohitsescapechance", "windchargechance",
                "windchargehealchance", "windchargewaitticks", "windchargeblockedticks",
                "escapewebchance", "escaperunchance");
        cat("Webs", "hitweb", "webcrit", "webavoid", "webtime", "webleadticks", "webzone", "webzoneradius",
                "webescape", "webbreak", "stuck", "cocoon", "cocoonhearts", "webtrapchance",
                "webtraphearts", "webtrapcount", "randomwebchance", "randomwebmindist",
                "randomwebmaxdist", "randomwebcooldown");
        cat("Potions & Items", "potions", "potionhearts", "potionhearts2", "potiondelay", "totem",
                "totemhearts", "loot", "lootrange", "durabilityswap", "durabilitythreshold",
                "expbottle", "expbottlethreshold", "massmax");
        cat("Human Mistakes", "miss", "wobble", "flinchchance", "flinchticksmin", "flinchticksmax",
                "overshootchance", "overshootdegrees", "aimspread", "precisionlock");
        cat("Team & FFA", "focuschance", "focusmin", "focusmax", "taunts", "ffaleave", "opsonly", "voidaware");
    }

    /** cmd -> icon for numeric/range settings (boolean settings always show a lime/gray dye toggle instead). */
    private static final Map<String, Item> ICON = new LinkedHashMap<>();

    private static void icon(Item item, String... cmds) {
        for (String cmd : cmds) {
            ICON.put(cmd, item);
        }
    }

    static {
        icon(Items.DIAMOND_SWORD, "range", "critchance", "critrange", "critchainchance", "critchainmin",
                "critchainmax", "jumpchance", "jumprange", "jumpreset", "punishcritchance", "recoverychance",
                "totempunishticks");
        icon(Items.BOW, "aim", "reactmin", "reactmax", "predictaim", "aimcone", "findrange");
        icon(Items.FEATHER, "keepdistance", "pressurekeep", "strafeticks", "strafechance", "hopstop");
        icon(Items.SPECTRAL_ARROW, "archerrush");
        icon(Items.GOLDEN_APPLE, "eat", "eatcount", "eatuntil", "hungereat", "flee");
        icon(Items.SHIELD, "shield", "shieldrange", "shieldticks", "shieldticksmax", "postswingshield",
                "postswingshieldticksmin", "postswingshieldticksmax", "stun", "maceaware");
        icon(Items.PISTON, "combostap", "combowtap", "combotapticks");
        icon(Items.TRIDENT, "combouppercut", "uppercutrange", "uppercutwindow");
        icon(Items.FEATHER, "combostrafecombo", "combostrafeticks");
        icon(Items.NETHER_STAR, "combochance", "mixchance", "mixmaxhits", "mixswitchchance");
        icon(Items.WIND_CHARGE, "windchargechance", "windchargehealchance", "windchargewaitticks",
                "windchargeblockedticks");
        icon(Items.ENDER_PEARL, "combohitsmin", "combohitsmax", "combohitsescapechance", "escaperunchance");
        icon(Items.COBWEB, "hitweb", "webtime", "webleadticks", "webzoneradius", "cocoonhearts",
                "webtrapchance", "webtraphearts", "webtrapcount", "randomwebchance", "randomwebmindist",
                "randomwebmaxdist", "randomwebcooldown", "escapewebchance");
        icon(Items.SPLASH_POTION, "potionhearts", "potionhearts2", "potiondelay");
        icon(Items.TOTEM_OF_UNDYING, "totemhearts");
        icon(Items.EXPERIENCE_BOTTLE, "expbottlethreshold");
        icon(Items.ANVIL, "durabilitythreshold");
        icon(Items.CHEST, "lootrange");
        icon(Items.PLAYER_HEAD, "massmax");
        icon(Items.CLOCK, "miss", "wobble", "flinchchance", "flinchticksmin", "flinchticksmax",
                "overshootchance", "overshootdegrees", "aimspread");
        icon(Items.WHITE_BANNER, "focuschance", "focusmin", "focusmax");
        icon(Items.NAME_TAG, "taunts");
    }

    private static List<String> pageNames() {
        List<String> pages = new ArrayList<>();
        pages.add("Difficulty & Playstyle");
        pages.addAll(CATEGORY_ORDER);
        return pages;
    }

    private static List<String> cmdsFor(String category) {
        List<String> list = new ArrayList<>();
        for (Map.Entry<String, String> e : CATEGORY.entrySet()) {
            if (e.getValue().equals(category)) {
                list.add(e.getKey());
            }
        }
        return list;
    }

    static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inv, p) -> new GuiMenu(syncId),
                Component.literal("PvpBot Settings")));
    }

    /** The container menu itself. Every slot is read-only display; clicks are intercepted in clicked(). */
    static final class GuiMenu extends AbstractContainerMenu {
        private final Container container = new SimpleContainer(SIZE);
        private int page;

        GuiMenu(int syncId) {
            super(MenuType.GENERIC_9x6, syncId);
            for (int row = 0; row < ROWS; row++) {
                for (int col = 0; col < 9; col++) {
                    int index = row * 9 + col;
                    addSlot(new Slot(container, index, 8 + col * 18, 18 + row * 18) {
                        @Override
                        public boolean mayPlace(ItemStack stack) {
                            return false;
                        }

                        @Override
                        public boolean mayPickup(Player who) {
                            return false;
                        }
                    });
                }
            }
            render();
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        /** Read-only menu: shift-clicking into player inventory never moves anything real. */
        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            if (slotId < 0 || slotId >= SIZE || !(player instanceof ServerPlayer)) {
                return;
            }
            if (slotId == PREV_SLOT) {
                page = Math.floorMod(page - 1, pageNames().size());
                render();
                return;
            }
            if (slotId == NEXT_SLOT) {
                page = Math.floorMod(page + 1, pageNames().size());
                render();
                return;
            }
            boolean rightClick = button == 1;
            boolean shift = clickType == ClickType.QUICK_MOVE;
            if (page == 0) {
                handlePresetClick(slotId);
            } else {
                handleSettingClick(slotId, rightClick, shift);
            }
            render();
        }

        private void handleSettingClick(int slotId, boolean rightClick, boolean shift) {
            if (slotId >= BODY_SIZE) {
                return;
            }
            List<String> cmds = cmdsFor(pageNames().get(page));
            if (slotId >= cmds.size()) {
                return;
            }
            Opt o = OPTS.get(cmds.get(slotId));
            if (o == null) {
                return;
            }
            if (o.bool) {
                setOptValue(o, o.on() ? 0 : 1);
                return;
            }
            double range = o.max - o.min;
            double smallStep = Math.max(range / 100.0, range >= 1 ? 1 : 0.05);
            double bigStep = Math.max(smallStep * 5, range / 20.0);
            double step = shift ? bigStep : smallStep;
            setOptValue(o, o.value + (rightClick ? -step : step));
        }

        private void handlePresetClick(int slotId) {
            if (slotId >= DIFFICULTY_ROW && slotId < DIFFICULTY_ROW + LEVEL_NAMES.length) {
                setDifficultyLevel(slotId - DIFFICULTY_ROW);
            } else if (slotId >= PLAYSTYLE_ROW && slotId < PLAYSTYLE_ROW + PLAYSTYLE_NAMES.length) {
                setPlaystyleIndex(slotId - PLAYSTYLE_ROW);
            }
        }

        private void render() {
            for (int i = 0; i < SIZE; i++) {
                container.setItem(i, ItemStack.EMPTY);
            }
            List<String> pages = pageNames();
            String pageName = pages.get(page);
            container.setItem(PAGE_LABEL_SLOT, labelItem(Items.BOOK, pageName,
                    List.of("Page " + (page + 1) + " of " + pages.size())));
            container.setItem(PREV_SLOT, labelItem(Items.ARROW, "<- Previous page", List.of()));
            container.setItem(NEXT_SLOT, labelItem(Items.ARROW, "Next page ->", List.of()));

            if (page == 0) {
                for (int i = 0; i < LEVEL_NAMES.length; i++) {
                    boolean current = LEVEL_NAMES[i].equals(difficultyName);
                    container.setItem(DIFFICULTY_ROW + i, labelItem(current ? Items.LIME_DYE : Items.GRAY_DYE,
                            (i + 1) + ". " + LEVEL_NAMES[i],
                            List.of(current ? "Current difficulty" : "Click to select")));
                }
                for (int i = 0; i < PLAYSTYLE_NAMES.length; i++) {
                    boolean current = PLAYSTYLE_NAMES[i].equals(playstyleName);
                    container.setItem(PLAYSTYLE_ROW + i, labelItem(current ? Items.LIME_DYE : Items.GRAY_DYE,
                            PLAYSTYLE_NAMES[i],
                            List.of(current ? "Current playstyle" : "Click to select")));
                }
                return;
            }
            List<String> cmds = cmdsFor(pageName);
            for (int i = 0; i < cmds.size() && i < BODY_SIZE; i++) {
                Opt o = OPTS.get(cmds.get(i));
                if (o == null) {
                    continue;
                }
                List<String> lore = new ArrayList<>();
                lore.add("/bot " + o.cmd);
                lore.add(o.desc);
                lore.add(o.bool ? "Click to toggle" : "Left: increase   Right: decrease   Shift: bigger step");
                Item icon = o.bool ? (o.on() ? Items.LIME_DYE : Items.GRAY_DYE)
                        : ICON.getOrDefault(o.cmd, Items.STICK);
                container.setItem(i, labelItem(icon, o.key + " = " + o.show(), lore));
            }
        }

        private static ItemStack labelItem(Item item, String name, List<String> lore) {
            ItemStack stack = new ItemStack(item);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
            if (!lore.isEmpty()) {
                List<Component> lines = new ArrayList<>();
                for (String line : lore) {
                    lines.add(Component.literal(line));
                }
                stack.set(DataComponents.LORE, new ItemLore(lines));
            }
            return stack;
        }
    }
}

/**
 * A chest-style team management menu (/bot teamgui): create, rename, disband teams, and add or remove bots
 * and players from them. Everything here drives real vanilla /team commands under the hood, so it always
 * stays in sync with anything done via commands too. Naming uses a vanilla anvil-style text field, since a
 * chest menu has no text box of its own.
 */
final class TeamGui {

    private TeamGui() {
    }

    private enum Mode { LIST, DETAIL, ADD_BOT, ADD_PLAYER }

    private static final int SIZE = 54;
    private static final int BODY = 45; // rows 0-4: list content
    private static final int BACK_SLOT = 45;
    private static final int ADD_BOT_SLOT = 47;
    private static final int ADD_PLAYER_SLOT = 49;
    private static final int RENAME_SLOT = 51;
    private static final int DISBAND_SLOT = 53;
    private static final int CREATE_SLOT = 53; // list page only

    static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inv, p) -> new TeamGuiMenu(syncId, (ServerPlayer) p),
                Component.literal("PvpBot Teams")));
    }

    private static Item woolFor(ChatFormatting color) {
        if (color == null) {
            return Items.WHITE_WOOL;
        }
        return switch (color) {
            case RED, DARK_RED -> Items.RED_WOOL;
            case BLUE, DARK_BLUE -> Items.BLUE_WOOL;
            case GREEN, DARK_GREEN -> Items.GREEN_WOOL;
            case YELLOW -> Items.YELLOW_WOOL;
            case GOLD -> Items.ORANGE_WOOL;
            case AQUA -> Items.LIGHT_BLUE_WOOL;
            case DARK_AQUA -> Items.CYAN_WOOL;
            case LIGHT_PURPLE -> Items.PINK_WOOL;
            case DARK_PURPLE -> Items.PURPLE_WOOL;
            case GRAY -> Items.LIGHT_GRAY_WOOL;
            case DARK_GRAY -> Items.GRAY_WOOL;
            case BLACK -> Items.BLACK_WOOL;
            default -> Items.WHITE_WOOL;
        };
    }

    private static final ChatFormatting[] PALETTE = {
            ChatFormatting.RED, ChatFormatting.BLUE, ChatFormatting.GREEN, ChatFormatting.YELLOW,
            ChatFormatting.LIGHT_PURPLE, ChatFormatting.AQUA, ChatFormatting.GOLD, ChatFormatting.DARK_PURPLE
    };

    private static ItemStack labelItem(Item item, String name, List<String> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(Component.literal(line));
            }
            stack.set(DataComponents.LORE, new ItemLore(lines));
        }
        return stack;
    }

    /** The vanilla anvil-style naming screen: type a name, close the menu (or take the item) to confirm it. */
    static final class RenameMenu extends AnvilMenu {
        private String pendingName = "";
        private boolean fired;
        private final Consumer<String> onConfirm;

        RenameMenu(int syncId, Inventory inv, Consumer<String> onConfirm) {
            super(syncId, inv);
            this.onConfirm = onConfirm;
            getSlot(0).set(new ItemStack(Items.PAPER));
        }

        @Override
        public void setItemName(String name) {
            this.pendingName = name == null ? "" : name;
            super.setItemName(name);
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            confirm();
        }

        @Override
        protected void onTake(Player player, ItemStack stack) {
            confirm();
        }

        private void confirm() {
            if (!fired && pendingName != null && !pendingName.isBlank() && onConfirm != null) {
                fired = true;
                onConfirm.accept(pendingName.trim());
            }
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    static void openRename(ServerPlayer player, String title, Consumer<String> onConfirm) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inv, p) -> new RenameMenu(syncId, inv, onConfirm),
                Component.literal(title)));
    }

    /** Creates a team (auto-colored from a rotating palette) and opens its detail page. */
    static void createTeam(ServerPlayer player, String rawName) {
        MinecraftServer server = player.getServer();
        String name = rawName.length() > 16 ? rawName.substring(0, 16) : rawName;
        run(server, "team add " + name);
        Scoreboard sb = server.getScoreboard();
        PlayerTeam team = sb.getPlayerTeam(name);
        if (team != null) {
            ChatFormatting color = PALETTE[Math.floorMod(sb.getPlayerTeams().size() - 1, PALETTE.length)];
            run(server, "team modify " + name + " color " + color.getName());
        }
        open(player);
    }

    /** Renames a team by recreating it under the new name (vanilla has no in-place team rename) and rejoining its members. */
    static void renameTeam(ServerPlayer player, String oldName, String rawNewName) {
        MinecraftServer server = player.getServer();
        Scoreboard sb = server.getScoreboard();
        PlayerTeam old = sb.getPlayerTeam(oldName);
        if (old == null) {
            open(player);
            return;
        }
        String newName = rawNewName.length() > 16 ? rawNewName.substring(0, 16) : rawNewName;
        List<String> members = new ArrayList<>(old.getPlayers());
        ChatFormatting color = old.getColor();
        run(server, "team remove " + oldName);
        run(server, "team add " + newName);
        if (color != null && color != ChatFormatting.RESET) {
            run(server, "team modify " + newName + " color " + color.getName());
        }
        for (String member : members) {
            run(server, "team join " + newName + " " + member);
        }
        open(player);
    }

    static final class TeamGuiMenu extends AbstractContainerMenu {
        private final Container container = new SimpleContainer(SIZE);
        private Mode mode = Mode.LIST;
        private String currentTeam;
        private ServerPlayer lastViewer;

        TeamGuiMenu(int syncId, ServerPlayer viewer) {
            super(MenuType.GENERIC_9x6, syncId);
            this.lastViewer = viewer;
            for (int row = 0; row < 6; row++) {
                for (int col = 0; col < 9; col++) {
                    int index = row * 9 + col;
                    addSlot(new Slot(container, index, 8 + col * 18, 18 + row * 18) {
                        @Override
                        public boolean mayPlace(ItemStack stack) {
                            return false;
                        }

                        @Override
                        public boolean mayPickup(Player who) {
                            return false;
                        }
                    });
                }
            }
            render();
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        private List<PlayerTeam> teams(ServerPlayer sp) {
            return new ArrayList<>(sp.getServer().getScoreboard().getPlayerTeams());
        }

        private List<String> membersOf(ServerPlayer sp, String team) {
            PlayerTeam t = sp.getServer().getScoreboard().getPlayerTeam(team);
            return t == null ? List.of() : new ArrayList<>(t.getPlayers());
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            if (!(player instanceof ServerPlayer sp) || slotId < 0 || slotId >= SIZE) {
                return;
            }
            switch (mode) {
                case LIST -> clickList(slotId, sp);
                case DETAIL -> clickDetail(slotId, sp);
                case ADD_BOT -> clickAddBot(slotId, sp);
                case ADD_PLAYER -> clickAddPlayer(slotId, sp);
            }
            render();
        }

        private void clickList(int slotId, ServerPlayer sp) {
            if (slotId == CREATE_SLOT) {
                openRename(sp, "Name the new team", name -> createTeam(sp, name));
                return;
            }
            List<PlayerTeam> teams = teams(sp);
            if (slotId < teams.size()) {
                currentTeam = teams.get(slotId).getName();
                mode = Mode.DETAIL;
            }
        }

        private void clickDetail(int slotId, ServerPlayer sp) {
            if (currentTeam == null) {
                mode = Mode.LIST;
                return;
            }
            if (slotId == BACK_SLOT) {
                mode = Mode.LIST;
                currentTeam = null;
            } else if (slotId == ADD_BOT_SLOT) {
                mode = Mode.ADD_BOT;
            } else if (slotId == ADD_PLAYER_SLOT) {
                mode = Mode.ADD_PLAYER;
            } else if (slotId == RENAME_SLOT) {
                String team = currentTeam;
                openRename(sp, "Rename " + team, newName -> renameTeam(sp, team, newName));
            } else if (slotId == DISBAND_SLOT) {
                run(sp.getServer(), "team remove " + currentTeam);
                mode = Mode.LIST;
                currentTeam = null;
            } else if (slotId < BODY) {
                List<String> members = membersOf(sp, currentTeam);
                if (slotId < members.size()) {
                    run(sp.getServer(), "team leave " + members.get(slotId));
                }
            }
        }

        private void clickAddBot(int slotId, ServerPlayer sp) {
            if (slotId == BACK_SLOT) {
                mode = Mode.DETAIL;
                return;
            }
            if (slotId >= BODY || currentTeam == null) {
                return;
            }
            List<String> onTeam = membersOf(sp, currentTeam);
            List<String> candidates = new ArrayList<>();
            for (String bot : BOTS) {
                if (!onTeam.contains(bot)) {
                    candidates.add(bot);
                }
            }
            if (slotId < candidates.size()) {
                run(sp.getServer(), "team join " + currentTeam + " " + candidates.get(slotId));
                mode = Mode.DETAIL;
            }
        }

        private void clickAddPlayer(int slotId, ServerPlayer sp) {
            if (slotId == BACK_SLOT) {
                mode = Mode.DETAIL;
                return;
            }
            if (slotId >= BODY || currentTeam == null) {
                return;
            }
            List<String> onTeam = membersOf(sp, currentTeam);
            List<String> candidates = new ArrayList<>();
            for (ServerPlayer p : sp.getServer().getPlayerList().getPlayers()) {
                String n = p.getName().getString();
                if (!onTeam.contains(n)) {
                    candidates.add(n);
                }
            }
            if (slotId < candidates.size()) {
                run(sp.getServer(), "team join " + currentTeam + " " + candidates.get(slotId));
                mode = Mode.DETAIL;
            }
        }

        private void render() {
            for (int i = 0; i < SIZE; i++) {
                container.setItem(i, ItemStack.EMPTY);
            }
            ServerPlayer sp = lastViewer;
            if (sp == null) {
                container.setItem(49, labelItem(Items.BOOK, "Open this from /bot teamgui", List.of()));
                return;
            }
            switch (mode) {
                case LIST -> renderList(sp);
                case DETAIL -> renderDetail(sp);
                case ADD_BOT -> renderAddBot(sp);
                case ADD_PLAYER -> renderAddPlayer(sp);
            }
        }

        private void renderList(ServerPlayer sp) {
            container.setItem(49, labelItem(Items.BOOK, "Teams", List.of()));
            container.setItem(CREATE_SLOT, labelItem(Items.NAME_TAG, "+ Create Team", List.of()));
            List<PlayerTeam> teams = teams(sp);
            for (int i = 0; i < teams.size() && i < BODY; i++) {
                PlayerTeam t = teams.get(i);
                int count = t.getPlayers().size();
                container.setItem(i, labelItem(woolFor(t.getColor()), t.getName(),
                        List.of(count + (count == 1 ? " member" : " members"), "Click to open")));
            }
        }

        private void renderDetail(ServerPlayer sp) {
            container.setItem(49, labelItem(Items.BOOK, currentTeam == null ? "Team" : currentTeam, List.of()));
            container.setItem(BACK_SLOT, labelItem(Items.ARROW, "<- Back to teams", List.of()));
            if (currentTeam == null) {
                return;
            }
            container.setItem(ADD_BOT_SLOT, labelItem(Items.LIME_DYE, "+ Add Bot", List.of()));
            container.setItem(ADD_PLAYER_SLOT, labelItem(Items.CYAN_DYE, "+ Add Player", List.of()));
            container.setItem(RENAME_SLOT, labelItem(Items.NAME_TAG, "Rename", List.of()));
            container.setItem(DISBAND_SLOT, labelItem(Items.BARRIER, "Disband Team", List.of()));
            List<String> members = membersOf(sp, currentTeam);
            for (int i = 0; i < members.size() && i < BODY; i++) {
                String m = members.get(i);
                boolean bot = BOTS.contains(m);
                container.setItem(i, labelItem(Items.PLAYER_HEAD, m,
                        List.of(bot ? "bot" : "player", "Click to remove from team")));
            }
        }

        private void renderAddBot(ServerPlayer sp) {
            container.setItem(49, labelItem(Items.BOOK, "Add a bot to " + currentTeam, List.of()));
            container.setItem(BACK_SLOT, labelItem(Items.ARROW, "<- Back", List.of()));
            List<String> onTeam = membersOf(sp, currentTeam);
            List<String> candidates = new ArrayList<>();
            for (String bot : BOTS) {
                if (!onTeam.contains(bot)) {
                    candidates.add(bot);
                }
            }
            for (int i = 0; i < candidates.size() && i < BODY; i++) {
                container.setItem(i, labelItem(Items.PLAYER_HEAD, candidates.get(i), List.of("bot", "Click to add")));
            }
        }

        private void renderAddPlayer(ServerPlayer sp) {
            container.setItem(49, labelItem(Items.BOOK, "Add a player to " + currentTeam, List.of()));
            container.setItem(BACK_SLOT, labelItem(Items.ARROW, "<- Back", List.of()));
            List<String> onTeam = membersOf(sp, currentTeam);
            List<String> candidates = new ArrayList<>();
            for (ServerPlayer p : sp.getServer().getPlayerList().getPlayers()) {
                String n = p.getName().getString();
                if (!onTeam.contains(n)) {
                    candidates.add(n);
                }
            }
            for (int i = 0; i < candidates.size() && i < BODY; i++) {
                container.setItem(i, labelItem(Items.PLAYER_HEAD, candidates.get(i), List.of("player", "Click to add")));
            }
        }
    }
}
