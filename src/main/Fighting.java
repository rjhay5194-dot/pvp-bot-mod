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

/** The fight loop: phases for fighting, running away, eating, cocoon and potions. */
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
            }
        }
    }

    /** One swing: a real attack, or (miss chance) a whiff that swings at air. */
    static void doAttack(MinecraftServer server, String name, Fight f) {
        if (MISS_CHANCE.value > 0 && ThreadLocalRandom.current().nextDouble() * 100.0 < MISS_CHANCE.value) {
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
        double cone = AIM_CONE.value;
        if (cone > 0 && !targetMoving) {
            double diff = Math.abs(wrapDegrees(directYaw(bot, target) - bot.getYRot()));
            if (diff < cone) {
                return; // already close enough: skip the redundant look command
            }
        }
        run(server, aimCmd(name, bot, target));
    }

    static void fightPhase(MinecraftServer server, String name, Fight f,
                                   ServerPlayer bot, ServerPlayer target, double dist) {
        double hpNow = bot.getHealth() + bot.getAbsorptionAmount();

        // Jump reset: right after taking a hit, sometimes jump to shake off the knockback.
        if (f.lastHp >= 0 && hpNow < f.lastHp - 0.01 && JUMP_RESET.value > 0 && bot.onGround()
                && ThreadLocalRandom.current().nextDouble() * 100.0 < JUMP_RESET.value) {
            run(server, "player " + name + " jump once");
        }
        f.lastHp = hpNow;

        // Splash potions: heal when hurt, buff when the target is far. It runs away first if it has to.
        int potionWanted = POTIONS.on() && globalTick >= f.nextPotionTick ? wantedPotion(bot, f, dist, hpNow) : 0;
        if (potionWanted != 0) {
            startPotionRun(server, name, f, dist, potionWanted);
            return;
        }

        // Very low health: web itself in and eat inside the cocoon.
        if (cocoonReady(bot, f, hpNow) && startCocoon(server, name, f, bot)) {
            return;
        }

        // Low health: run away bunny hopping, then eat.
        if (EAT_HEARTS.value > 0 && hpNow <= EAT_HEARTS.value * 2.0 && globalTick >= f.nextEatTick
                && findFoodIndex(bot) >= 0) {
            startFlee(server, name, f, 0);
            return;
        }

        // Placing a cobweb on the target (started after a landed hit): the rest waits.
        if (webPlaceStep(server, name, f, bot, target)) {
            return;
        }

        maybeAim(server, name, f, bot, target);

        // Ledges and lava on the way to the target and on the way back.
        double hx = target.getX() - bot.getX();
        double hz = target.getZ() - bot.getZ();
        double hl = Math.sqrt(hx * hx + hz * hz);
        boolean edgeAhead = false;
        boolean edgeBehind = false;
        if (VOID_AWARE.on() && hl > 0.3) {
            double ux = hx / hl;
            double uz = hz / hl;
            edgeAhead = hazardAt(bot.level(), bot.getX() + ux * 1.4, bot.getY(), bot.getZ() + uz * 1.4);
            edgeBehind = hazardAt(bot.level(), bot.getX() - ux * 1.4, bot.getY(), bot.getZ() - uz * 1.4);
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
            if (mode > 0 && !f.edgeHold) {
                if (f.hopping) {
                    run(server, "player " + name + " jump"); // stops continuous jumping
                    f.hopping = false;
                }
                if (mode == 1) {
                    run(server, "player " + name + " move"); // W-tap: let go of every movement key
                    f.strafeDir = 0;
                } else if (!edgeBehind) {
                    run(server, "player " + name + " move backward"); // S-tap: step back
                } else {
                    run(server, "player " + name + " move"); // a ledge is behind us: just stop instead
                    f.strafeDir = 0;
                }
                f.paused = true;
                f.pauseUntil = globalTick + Math.max(1, TAP_TICKS.asInt());
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
                if (!BUNNY_HOP.on() || dist <= HOP_STOP.value) {
                    run(server, "player " + name + " jump");
                    f.hopping = false;
                }
            } else if (BUNNY_HOP.on() && !f.crit && dist > HOP_STOP.value + HOP_RESUME_MARGIN) {
                run(server, "player " + name + " jump continuous");
                f.hopping = true;
                if (f.keepMode != 1) {
                    f.keepMode = 1;
                    run(server, "player " + name + " move forward");
                    run(server, "player " + name + " sprint");
                }
            }

            // Keep distance: back off when too close, close in when too far, hold the gap in between.
            if (KEEP_DISTANCE.value > 0) {
                if (!f.hopping && !f.crit) {
                    double keep = KEEP_DISTANCE.value;
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
            if (STRAFE.on() && !f.hopping && !f.crit && dist <= STRAFE_MAX_DIST) {
                if (globalTick >= f.nextStrafeTick) {
                    if (ThreadLocalRandom.current().nextDouble() * 100.0 < STRAFE_CHANCE.value) {
                        f.strafeDir = f.strafeDir == 0
                                ? (ThreadLocalRandom.current().nextBoolean() ? 1 : -1)
                                : -f.strafeDir;
                        run(server, "player " + name + " move " + (f.strafeDir > 0 ? "left" : "right"));
                        axisCmd(server, name, f);
                    } else if (f.strafeDir != 0) {
                        stopStrafe(server, name, f);
                    }
                    int base = Math.max(2, STRAFE_TICKS.asInt());
                    f.nextStrafeTick = globalTick + base + ThreadLocalRandom.current().nextInt(base / 2 + 1);
                }
            } else if (f.strafeDir != 0 && globalTick >= f.sidestepUntil) {
                stopStrafe(server, name, f);
            }

            // Stuck detection: if the bot barely moved for a second while it should be closing in, hop and sidestep.
            if (STUCK.on() && globalTick % 20 == 0) {
                Vec3 here = bot.position();
                if (f.lastPos != null && f.lastPos.distanceTo(here) < 0.2 && !f.blocking
                        && dist > ATTACK_RANGE.value + 1.0) {
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
            if (JUMP_CHANCE.value > 0 && !f.hopping && !f.crit && dist <= JUMP_RANGE.value
                    && globalTick % 5 == 0 && bot.onGround()
                    && ThreadLocalRandom.current().nextDouble() * 100.0 < JUMP_CHANCE.value) {
                run(server, "player " + name + " jump once");
            }
        }

        // A sprint-hit cancels sprinting (that is what gives the extra knockback), so sprint again.
        if (!f.crit && !f.edgeHold && f.keepMode > 0 && globalTick >= f.sprintHoldUntil && !bot.isSprinting()) {
            run(server, "player " + name + " sprint");
        }

        double reach = reachDistance(bot, target);
        boolean ready = bot.getAttackStrengthScale(0.5F) >= 1.0F;

        // Hit web: when one of our hits lands, sometimes really place a cobweb at the target's feet.
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
                int lo = Math.min(SHIELD_TICKS.asInt(), SHIELD_TICKS_MAX.asInt());
                int hi = Math.max(SHIELD_TICKS.asInt(), SHIELD_TICKS_MAX.asInt());
                f.blockUntil = globalTick + Math.max(2, lo + ThreadLocalRandom.current().nextInt(hi - lo + 1));
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
                doAttack(server, name, f);
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
            doAttack(server, name, f);
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
                boolean chaining = f.critChainUntil > globalTick;
                boolean tryCrit;
                if (chaining) {
                    // Guaranteed crit for the rest of the burst window.
                    tryCrit = critPossible && bot.onGround() && !f.hopping;
                } else {
                    tryCrit = critPossible && bot.onGround() && !f.hopping
                            && ThreadLocalRandom.current().nextDouble() * 100.0 < CRIT_CHANCE.value;
                    // A crit against a target that's eating or sprinting away can kick off a chain of guaranteed crits.
                    boolean vulnerable = tryCrit && (target.isUsingItem() || (target.isSprinting() && movingAway(bot, target)));
                    if (vulnerable && CRIT_CHAIN_CHANCE.value > 0
                            && ThreadLocalRandom.current().nextDouble() * 100.0 < CRIT_CHAIN_CHANCE.value) {
                        int lo = Math.min(CRIT_CHAIN_MIN.asInt(), CRIT_CHAIN_MAX.asInt());
                        int hi = Math.max(CRIT_CHAIN_MIN.asInt(), CRIT_CHAIN_MAX.asInt());
                        f.critChainUntil = globalTick + lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
                    }
                }
                if (tryCrit) {
                    // Crits need a falling, non-sprinting hit: stop sprinting and jump.
                    run(server, "player " + name + " unsprint");
                    run(server, "player " + name + " jump once");
                    f.crit = true;
                    f.critStart = globalTick;
                } else if (reach <= ATTACK_RANGE.value) {
                    doAttack(server, name, f);
                }
            }
        } else {
            f.reactAt = -1;
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
    }

    static void fleePhase(MinecraftServer server, String name, Fight f,
                                  ServerPlayer bot, ServerPlayer target, double dist) {
        double hp = bot.getHealth() + bot.getAbsorptionAmount();
        if (f.fleeFor == 0 && cocoonReady(bot, f, hp) && startCocoon(server, name, f, bot)) {
            return; // hurt badly while running: web in right here instead
        }
        if (globalTick % 2 == 0) {
            // Face directly away from the target, turning aside if a ledge or lava is in the way.
            double dx = bot.getX() - target.getX();
            double dz = bot.getZ() - target.getZ();
            double baseYaw = Math.toDegrees(Math.atan2(-dx, dz));
            double yaw = baseYaw;
            if (VOID_AWARE.on()) {
                double[] turns = {0, 50, -50, 100, -100};
                for (double turn : turns) {
                    double a = Math.toRadians(baseYaw + turn);
                    if (!hazardAt(bot.level(), bot.getX() - Math.sin(a) * 1.6, bot.getY(), bot.getZ() + Math.cos(a) * 1.6)) {
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
        if (dist >= FLEE_DIST.value || globalTick - f.phaseStart > FLEE_MAX_TICKS) {
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
        if (slot < 0) {
            return false;
        }
        run(server, "player " + name + " stop");
        run(server, "player " + name + " hotbar " + (slot + 1));
        run(server, "player " + name + " look " + String.format(Locale.ROOT, "%.1f", bot.getYRot()) + " 90");
        f.phase = Phase.COCOON;
        f.cocoonStage = 1;
        f.cocoonStart = globalTick;
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

    static void cocoonPhase(MinecraftServer server, String name, Fight f,
                                    ServerPlayer bot, ServerPlayer target, double dist) {
        int elapsed = globalTick - f.cocoonStart;
        if (f.cocoonStage == 1) {
            if (elapsed >= 1) {
                run(server, "player " + name + " use once"); // first web, in the bot's own block
                f.cocoonStage = 2;
            }
        } else if (f.cocoonStage == 2) {
            if (elapsed >= 3) {
                run(server, "player " + name + " use once"); // second web, on top of the first
                f.cocoonStage = 3;
            }
        } else if (elapsed >= 5) {
            f.inCocoon = true; // from now on the cobweb escape stays off until the last bite
            startEat(server, name, f, bot, true);
        }
    }

    /**
     * Potion run: throw straight down at its own feet, far away from the target so the target never gets an
     * effect — except a healing potion at very close range, which is thrown from right where the bot stands.
     */
    static void startPotionRun(MinecraftServer server, String name, Fight f, double dist, int kind) {
        f.thrownMask = 0;
        boolean closeHeal = kind == 1 && dist <= POTION_HEAL_CLOSE_DIST;
        if (dist >= FLEE_DIST.value || closeHeal) {
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
            int kind = wantedPotion(bot, f, dist, hp);
            boolean closeHeal = kind == 1 && dist <= POTION_HEAL_CLOSE_DIST;
            if (dist < POTION_SAFE_DIST && !closeHeal) {
                // The target kept up: no potion at all (it would get the effect too), except healing up close.
                f.nextPotionTick = globalTick + POTION_DELAY.asInt();
                endRetreat(server, name, f);
                f.nextEatTick = globalTick;
                return;
            }
            if (kind == 0) {
                f.nextPotionTick = globalTick + POTION_DELAY.asInt();
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
                f.thrownMask |= 1 << (f.potionKind - 1);
                f.potionStage = 2;
                f.potionStart = globalTick;
            }
        } else if (globalTick - f.potionStart >= 3) {
            f.potionStage = 0; // next potion, if any
        }
    }
}
