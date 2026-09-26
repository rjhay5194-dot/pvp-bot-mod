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
    static void equipArmor(ServerPlayer bot) {
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

    /** At very low health, swap whatever is in the off-hand (shield, etc.) for a totem of undying, if carried. */
    static void maybeSwitchTotem(ServerPlayer bot) {
        if (!TOTEM.on() || TOTEM_HEARTS.value <= 0) {
            return;
        }
        double hp = bot.getHealth() + bot.getAbsorptionAmount();
        if (hp > TOTEM_HEARTS.value * 2.0) {
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
    static void maybeSwitchTotemBack(ServerPlayer bot) {
        if (!TOTEM.on() || TOTEM_HEARTS.value <= 0) {
            return;
        }
        double hp = bot.getHealth() + bot.getAbsorptionAmount();
        if (hp < TOTEM_HEARTS.value * 2.0 + 4.0) { // a buffer above the switch-to-totem line, so it doesn't flicker
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
        if (idx < 0 || randomWebAimPoint(bot, target, f) == null) {
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
     * of it. Tries a few candidate distances (in front of, on, and just behind the target) and works even when
     * the target is briefly airborne (it aims at the floor it's about to land on, using its current feet level).
     */
    static Vec3 webAimPoint(ServerPlayer bot, ServerPlayer target, Fight f) {
        Level level = target.level();
        int floorY = target.blockPosition().getY();
        double lead = WEB_LEAD_TICKS.value;
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
        double[] fracs = {0.45, 0.0, -0.3};
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
    static Vec3 randomWebAimPoint(ServerPlayer bot, ServerPlayer target, Fight f) {
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
        double lo = Math.min(RANDOMWEB_MINDIST.value, RANDOMWEB_MAXDIST.value);
        double hi = Math.max(RANDOMWEB_MINDIST.value, RANDOMWEB_MAXDIST.value);
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
            if (elapsed >= 1) {
                Vec3 aim = f.placeRandom ? randomWebAimPoint(bot, target, f) : webAimPoint(bot, target, f);
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
            boolean placed = f.placeCell != null && bot.level().getBlockState(f.placeCell).is(Blocks.COBWEB);
            if (!placed && !f.placeRetried && findItemIndex(bot, Items.COBWEB) >= 0) {
                Vec3 aim = f.placeRandom ? randomWebAimPoint(bot, target, f) : webAimPoint(bot, target, f);
                if (aim != null) {
                    f.placeRetried = true;
                    run(server, "player " + name + " look at " + fmt(aim.x) + " " + fmt(aim.y) + " " + fmt(aim.z));
                    f.placeStage = 2;
                    f.placeStart = globalTick - 2; // skip straight to the click on the next tick
                    return true;
                }
            }
            if (placed) {
                if (WEB_LIFETIME.asInt() > 0) {
                    PLACED_WEBS.add(new PlacedWeb(bot.level(), f.placeCell, globalTick + WEB_LIFETIME.asInt()));
                }
                // Cross-web combo: the target is stuck, so follow up with a guaranteed run of crits.
                if (CRIT_CHAIN_CHANCE.value > 0) {
                    int lo = Math.min(CRIT_CHAIN_MIN.asInt(), CRIT_CHAIN_MAX.asInt());
                    int hi = Math.max(CRIT_CHAIN_MIN.asInt(), CRIT_CHAIN_MAX.asInt());
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
        int t = AIM.asInt();
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

    /** A cobweb touching any part of the bot's hitbox (this also catches webs the bot is only standing at the edge of). */
    static BlockPos findCobweb(ServerPlayer bot) {
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

    static boolean inCobweb(ServerPlayer bot) {
        return findCobweb(bot) != null;
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
            if (f.inCocoon || (!WEB_ESCAPE.on() && !WEB_BREAK.on()) || globalTick < f.nextWebTick) {
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
     * Aim command: a smooth look at the target's eyes (HeroBot's own tracking), unless aim wobble is on
     * or predictive lead applies (only at low aim_ticks, where the bot's turn is already near-instant and
     * benefits from leading a moving target instead of aiming at where it just was).
     */
    static String aimCmd(String name, ServerPlayer bot, ServerPlayer target) {
        double w = WOBBLE.value;
        double lead = PREDICT_TICKS.value;
        boolean leading = lead > 0 && AIM.asInt() <= 2;
        if (w <= 0 && !leading) {
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
        double dy = target.getEyeY() - bot.getEyeY();
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        if (w > 0) {
            yaw += (ThreadLocalRandom.current().nextDouble() * 2 - 1) * w;
            pitch += (ThreadLocalRandom.current().nextDouble() * 2 - 1) * w * 0.5;
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
        return 0;
    }

    /** 1 = healing, 2 = speed, 3 = strength, 0 = anything else (only splash potions count). */
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
     * The next potion kind worth throwing, or 0. Healing when hurt; speed and strength only when the target is
     * far away (a splash also hits anyone standing close, and the target must never get the effect).
     * Speed and strength are gated only by whether the effect is currently active, not by thrownMask, so a bot
     * throws another one as soon as the earlier potion's effect actually runs out, for as long as this fight lasts.
     */
    static int wantedPotion(ServerPlayer bot, Fight f, double dist, double hp) {
        if ((f.thrownMask & 1) == 0 && POTION_HEARTS.value > 0 && hp <= POTION_HEARTS.value * 2.0
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
        }
        return 0;
    }

    static boolean cocoonReady(ServerPlayer bot, Fight f, double hp) {
        return COCOON.on() && COCOON_HEARTS.value > 0 && hp <= COCOON_HEARTS.value * 2.0
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
        if (REVENGE.on() && globalTick % 2 == 0) {
            tickRevenge(server);
        }
        if (FOCUS_CHANCE.value > 0 && globalTick % 20 == 0) {
            tickFocus(server);
        }
        if ((AUTO_TARGET.on() || ffaActive) && globalTick % 20 == 0) {
            tickAutoTarget(server);
        }
        if (LOOT.on() && globalTick % 5 == 0) {
            tickLoot(server);
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
        PENDING.put(job.name, new Pending(job.owner, true, job.team, job.pos));
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

    static void tickArmor(MinecraftServer server) {
        for (String name : BOTS) {
            ServerPlayer bot = server.getPlayerList().getPlayerByName(name);
            if (bot != null) {
                equipArmor(bot);
                maybeSwitchTotem(bot);
                maybeSwitchTotemBack(bot);
                Fight f = FIGHTS.get(name);
                if (f != null) {
                    maybeSwapDurability(bot, f);
                }
            }
        }
    }

    /** Is this stack's remaining durability at or below the swap threshold? Non-damageable items are never "low". */
    static boolean lowDurability(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageableItem()) {
            return false;
        }
        int max = stack.getMaxDamage();
        if (max <= 0) {
            return false;
        }
        int remaining = max - stack.getDamageValue();
        return remaining * 100.0 / max <= DURABILITY_THRESHOLD.value;
    }

    /** If the held sword or a worn armor piece is nearly broken, swap in a spare of the same kind from the inventory. */
    static void maybeSwapDurability(ServerPlayer bot, Fight f) {
        if (!DURABILITY_SWAP.on() || globalTick < f.nextDurabilityCheck) {
            return;
        }
        f.nextDurabilityCheck = globalTick + 20;
        Inventory inv = bot.getInventory();
        ItemStack main = bot.getMainHandItem();
        if (main.is(ItemTags.SWORDS) && lowDurability(main)) {
            int mainSlot = inv.getSelectedSlot();
            for (int i = 0; i < 36; i++) {
                ItemStack cand = inv.getItem(i);
                if (i != mainSlot && cand.is(ItemTags.SWORDS) && !lowDurability(cand)) {
                    inv.setItem(i, main.copy());
                    inv.setItem(mainSlot, cand.copy());
                    f.weaponSlot = -1; // re-pick and re-equip next tick
                    break;
                }
            }
        }
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack worn = bot.getItemBySlot(slot);
            if (worn.isEmpty() || !lowDurability(worn)) {
                continue;
            }
            for (int i = 0; i < 36; i++) {
                ItemStack cand = inv.getItem(i);
                if (armorSlotFor(cand) == slot && !lowDurability(cand)) {
                    inv.setItem(i, worn.copy());
                    bot.setItemSlot(slot, cand.copy());
                    break;
                }
            }
        }
    }

    /** Revenge: whoever hits a bot becomes its target. */
    static void tickRevenge(MinecraftServer server) {
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
                    run(server, "player " + name + " look at " + fmt(item.getX()) + " " + fmt(item.getY()) + " "
                            + fmt(item.getZ()));
                }
                continue;
            }
            if (fightClose || bot.getInventory().getFreeSlot() < 0) {
                continue;
            }
            ItemEntity best = null;
            double bestDist = LOOT_RANGE.value;
            for (ItemEntity candidate : bot.level().getEntitiesOfClass(ItemEntity.class,
                    bot.getBoundingBox().inflate(LOOT_RANGE.value))) {
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
                run(server, "player " + name + " look at " + fmt(best.getX()) + " " + fmt(best.getY()) + " "
                        + fmt(best.getZ()));
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
            return "A match is already running. Use /pvpbot ffa stop first.";
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
            broadcast(server, "<" + killer + "> "
                    + TAUNT_LINES[ThreadLocalRandom.current().nextInt(TAUNT_LINES.length)]);
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
