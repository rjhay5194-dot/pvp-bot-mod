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
        if (PRECISION_LOCK.on()) {
            // Aim true right on the tick the swing fires, ignoring wobble/spread/overshoot for this one look
            // command only — human-mistake settings still affect every other tick, just not the one that counts.
            run(server, lookCmd(name, target.getName().getString()));
        }
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
        run(server, aimCmd(name, bot, target, f));
    }

    static void fightPhase(MinecraftServer server, String name, Fight f,
                            ServerPlayer bot, ServerPlayer target, double dist) {
        double hpNow = bot.getHealth() + bot.getAbsorptionAmount();
        // Jump reset and flinch: right after taking a hit, sometimes jump to shake off the knockback,
        // and separately sometimes hesitate a moment before swinging back (a human-like flinch).
        if (f.lastHp >= 0 && hpNow < f.lastHp - 0.01) {
            if (JUMP_RESET.value > 0 && bot.onGround()
                    && ThreadLocalRandom.current().nextDouble() * 100.0 < JUMP_RESET.value) {
                run(server, "player " + name + " jump once");
            }
            // Punish crit: guarantee the very next swing crits, and skip any flinch — retaliating hard wins out.
            if (PUNISH_CRIT_CHANCE.value > 0
                    && ThreadLocalRandom.current().nextDouble() * 100.0 < PUNISH_CRIT_CHANCE.value) {
                f.punishCrit = true;
                f.flinchUntil = globalTick;
            } else if (FLINCH_CHANCE.value > 0
                    && ThreadLocalRandom.current().nextDouble() * 100.0 < FLINCH_CHANCE.value) {
                int lo = Math.min(FLINCH_TICKS_MIN.asInt(), FLINCH_TICKS_MAX.asInt());
                int hi = Math.max(FLINCH_TICKS_MIN.asInt(), FLINCH_TICKS_MAX.asInt());
                f.flinchUntil = globalTick + lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
            }
        }
        f.lastHp = hpNow;
        // Recovery punish: the target's own weapon just went on cooldown (it swung, hit or miss) — sometimes
        // skip the bot's normal reaction wait so it hits back before the target's weapon recharges.
        double targetScale = target.getAttackStrengthScale(0.5F);
        if (f.lastTargetScale > 0.9 && targetScale < 0.3 && RECOVERY_CHANCE.value > 0
                && ThreadLocalRandom.current().nextDouble() * 100.0 < RECOVERY_CHANCE.value) {
            f.reactAt = globalTick;
        }
        f.lastTargetScale = targetScale;
        // Totem punish: the target just consumed a totem of undying (it was in an equipment slot, now it isn't).
        boolean hasTotemNow = target.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.TOTEM_OF_UNDYING)
                || target.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.TOTEM_OF_UNDYING);
        if (f.targetHadTotem && !hasTotemNow && target.isAlive() && TOTEM_PUNISH_TICKS.asInt() > 0) {
            f.critChainUntil = Math.max(f.critChainUntil, globalTick + TOTEM_PUNISH_TICKS.asInt());
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
                int lo = Math.min(COMBOHITS_MINHIT.asInt(), COMBOHITS_MAXHIT.asInt());
                int hi = Math.max(COMBOHITS_MINHIT.asInt(), COMBOHITS_MAXHIT.asInt());
                f.comboHitsThreshold = lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
            }
            if (f.comboHitsStreak >= f.comboHitsThreshold
                    && ThreadLocalRandom.current().nextDouble() * 100.0 < COMBOHITS_ESCAPECHANCE.value) {
                f.comboHitsStreak = 0;
                f.comboHitsThreshold = -1;
                if (startComboEscape(server, name, f, bot, target)) {
                    return;
                }
            }
        }
        // Weapon/armor durability swap and exp-bottle mending repair run continuously in tickArmor.
        // Splash potions: heal when hurt, buff when the target is far. It runs away first if it has to.
        int potionWanted = POTIONS.on() && globalTick >= f.nextPotionTick ? wantedPotion(bot, f, dist, hpNow) : 0;
        if (potionWanted != 0) {
            startPotionRun(server, name, f, dist, potionWanted, hpNow);
            return;
        }
        // Very low health: web itself in and eat inside the cocoon.
        if (cocoonReady(bot, f, hpNow) && startCocoon(server, name, f, bot)) {
            return;
        }
        // Panic: at very low health, sometimes lay cobwebs behind itself before running, instead of just running.
        if (WEBTRAP_CHANCE.value > 0 && WEBTRAP_HEARTS.value > 0 && hpNow <= WEBTRAP_HEARTS.value * 2.0
                && globalTick >= f.nextEatTick && findFoodIndex(bot) >= 0
                && countItem(bot, Items.COBWEB) >= Math.max(2, WEBTRAP_COUNT.asInt())
                && ThreadLocalRandom.current().nextDouble() * 100.0 < WEBTRAP_CHANCE.value
                && startWebTrap(server, name, f, bot, target)) {
            return;
        }
        // Low health, or low hunger: run away bunny hopping, then eat — or, sometimes, launch/teleport away instead.
        boolean hungry = HUNGER_EAT_LEVEL.value > 0 && bot.getFoodData().getFoodLevel() <= HUNGER_EAT_LEVEL.asInt();
        boolean lowHealth = EAT_HEARTS.value > 0 && hpNow <= EAT_HEARTS.value * 2.0;
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
        boolean archer = ARCHER_RUSH.on()
                && (target.getMainHandItem().is(Items.BOW) || target.getMainHandItem().is(Items.CROSSBOW));
        if (vulnerable && FALL_CRIT.on() && bot.onGround() && !f.hopping && !f.crit
                && dist <= JUMP_RANGE.value) {
            run(server, "player " + name + " jump once");
        }
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
        // Web avoidance: a cobweb dead ahead (that isn't the target's own cell) gets sidestepped, not walked into.
        if (WEB_AVOID.on() && hl > 0.3 && f.strafeDir == 0 && !f.hopping && !f.edgeHold) {
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
        // Combo: two ticks after a swing (so the sprint hit has landed), run the active combo's next step.
        if (f.attackTick >= 0 && globalTick - f.attackTick >= 2) {
            f.attackTick = -1;
            if (f.comboActive != 0 && !f.edgeHold) {
                int type = f.comboMixed
                        ? (MIX_SWITCHCHANCE.value > 0
                                && ThreadLocalRandom.current().nextDouble() * 100.0 < MIX_SWITCHCHANCE.value
                                ? pickComboType() : f.comboType)
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
            } else if (POSTSWING_SHIELD_CHANCE.value > 0 && !f.blocking
                    && bot.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD)
                    && ThreadLocalRandom.current().nextDouble() * 100.0 < POSTSWING_SHIELD_CHANCE.value) {
                // Not comboing: raise the shield right after the hit lands instead of swinging straight through.
                run(server, "player " + name + " use continuous");
                f.blocking = true;
                int lo = Math.min(POSTSWING_SHIELD_TICKS_MIN.asInt(), POSTSWING_SHIELD_TICKS_MAX.asInt());
                int hi = Math.max(POSTSWING_SHIELD_TICKS_MIN.asInt(), POSTSWING_SHIELD_TICKS_MAX.asInt());
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
            // While the target is vulnerable (eating/fleeing), use the tight pressure distance instead.
            double effectiveKeep = (vulnerable || archer) ? PRESSURE_KEEP_DIST.value : KEEP_DISTANCE.value;
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
            if (STRAFE.on() && !f.hopping && !f.crit && dist <= STRAFE_MAX_DIST) {
                if (globalTick >= f.nextStrafeTick) {
                    if (ThreadLocalRandom.current().nextDouble() * 100.0 < (archer ? 100.0 : STRAFE_CHANCE.value)) {
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
        boolean ready = bot.getAttackStrengthScale(0.5F) >= 1.0F && globalTick >= f.flinchUntil;
        // Uppercut combo in progress: hit while still rising from the jump (the opposite of a falling crit).
        if (f.comboUppercutRising) {
            if (!bot.onGround() && bot.getDeltaMovement().y > 0.0 && reach <= ATTACK_RANGE.value && ready) {
                doAttack(server, name, f, target);
                f.comboUppercutRising = false;
                f.sprintHoldUntil = globalTick + 3;
                bot.fallDistance = 0.0F; // the jump-in shouldn't hurt it landing back down
                return;
            } else if (globalTick - f.comboUppercutStart > COMBO_UPPERCUT_WINDOW.asInt()
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
            if (f.comboActive == 0 && COMBO_CHANCE.value > 0
                    && ThreadLocalRandom.current().nextDouble() * 100.0 < COMBO_CHANCE.value) {
                startCombo(f);
            }
        }
        // Mace awareness: the target is airborne above the bot with a mace out — block immediately, no roll,
        // no range check. A mace's damage scales with the wielder's fall distance, so this is worth blocking outright.
        boolean maceThreat = MACE_AWARE.on() && !f.blocking
                && (target.getMainHandItem().is(Items.MACE) || target.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.MACE))
                && !target.onGround() && target.getY() > bot.getY() + 1.0
                && bot.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD);
        if (maceThreat) {
            run(server, "player " + name + " use continuous");
            f.blocking = true;
            f.blockUntil = globalTick + Math.max(2, SHIELD_TICKS_MAX.asInt());
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
        } else if (SHIELD_CHANCE.value > 0 && f.critChainUntil <= globalTick && !ready
                && dist <= SHIELD_RANGE.value && f.comboActive == 0
                && bot.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD)
                && ThreadLocalRandom.current().nextDouble() * 100.0 < SHIELD_CHANCE.value) {
            run(server, "player " + name + " use continuous"); // hold right-click: shield up
            f.blocking = true;
            int lo = Math.min(SHIELD_TICKS.asInt(), SHIELD_TICKS_MAX.asInt());
            int hi = Math.max(SHIELD_TICKS.asInt(), SHIELD_TICKS_MAX.asInt());
            f.blockUntil = globalTick + Math.max(2, lo + ThreadLocalRandom.current().nextInt(hi - lo + 1));
            return;
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
        if (FALL_CRIT.on() && ready && reach <= ATTACK_RANGE.value
                && !bot.onGround() && bot.getDeltaMovement().y < 0.0) {
            run(server, "player " + name + " unsprint");
            doAttack(server, name, f, target);
            f.reactAt = -1;
            f.sprintHoldUntil = globalTick + 3;
            return;
        }
        // Guaranteed web crit: the target is currently slowed by a cobweb, so skip the crit-chance roll entirely.
        boolean guaranteedWebCrit = WEB_CRIT.on() && inCobweb(target);
        boolean critPossible = CRIT_CHANCE.value > 0 && dist <= CRIT_RANGE.value;
        if (ready && (reach <= ATTACK_RANGE.value || critPossible || guaranteedWebCrit || f.punishCrit)) {
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
                            && ThreadLocalRandom.current().nextDouble() * 100.0 < CRIT_CHANCE.value;
                    // A crit against a target that's eating or sprinting away can kick off a chain of guaranteed crits.
                    boolean critVulnerable = tryCrit && vulnerable;
                    if (critVulnerable && CRIT_CHAIN_CHANCE.value > 0
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
    static int pickComboType() {
        if (COMBO_STAP_CHANCE.value > 0
                && ThreadLocalRandom.current().nextDouble() * 100.0 < COMBO_STAP_CHANCE.value) {
            return 0;
        }
        if (COMBO_WTAP_CHANCE.value > 0
                && ThreadLocalRandom.current().nextDouble() * 100.0 < COMBO_WTAP_CHANCE.value) {
            return 1;
        }
        if (COMBO_UPPERCUT_CHANCE.value > 0
                && ThreadLocalRandom.current().nextDouble() * 100.0 < COMBO_UPPERCUT_CHANCE.value) {
            return 2;
        }
        if (COMBO_STRAFECOMBO_CHANCE.value > 0
                && ThreadLocalRandom.current().nextDouble() * 100.0 < COMBO_STRAFECOMBO_CHANCE.value) {
            return 3;
        }
        return 0;
    }

    /** Hit-select decided to combo: pick MixCombo (a series that may change type each hit) or one single type. */
    static void startCombo(Fight f) {
        boolean mixed = MIX_CHANCE.value > 0
                && ThreadLocalRandom.current().nextDouble() * 100.0 < MIX_CHANCE.value;
        f.comboActive = 1;
        f.comboMixed = mixed;
        f.comboMixHitsLeft = mixed ? Math.max(1, MIX_MAXHITS.asInt()) : Integer.MAX_VALUE;
        f.comboType = pickComboType();
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
                f.pauseUntil = globalTick + Math.max(1, COMBO_TAP_TICKS.asInt());
            }
            case 1 -> { // w-tap: let go of forward a moment, then go again
                run(server, "player " + name + " move");
                f.strafeDir = 0;
                f.paused = true;
                f.pauseUntil = globalTick + Math.max(1, COMBO_TAP_TICKS.asInt());
            }
            case 2 -> { // uppercut: sprint-jump in and try to hit on the way up (not a falling crit)
                double dx = target.getX() - bot.getX();
                double dz = target.getZ() - bot.getZ();
                double d2 = Math.sqrt(dx * dx + dz * dz);
                if (bot.onGround() && d2 <= COMBO_UPPERCUT_RANGE.value) {
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
                f.pauseUntil = globalTick + Math.max(1, COMBO_STRAFE_SWITCH_TICKS.asInt());
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
        boolean hasWeb = countItem(bot, Items.COBWEB) >= Math.max(2, WEBTRAP_COUNT.asInt());
        if (COMBOHITS_WINDCHARGECHANCE.value > 0
                && ThreadLocalRandom.current().nextDouble() * 100.0 < COMBOHITS_WINDCHARGECHANCE.value) {
            if (hasWind && startWindEscape(server, name, f, bot, target)) {
                return true;
            }
            if (hasPearl && startPearlEscape(server, name, f, bot, target)) {
                return true;
            }
        }
        if (hasWeb && COMBOHITS_WEBCHANCE.value > 0
                && ThreadLocalRandom.current().nextDouble() * 100.0 < COMBOHITS_WEBCHANCE.value
                && startWebTrap(server, name, f, bot, target)) {
            return true;
        }
        if (COMBOHITS_RUNCHANCE.value > 0
                && ThreadLocalRandom.current().nextDouble() * 100.0 < COMBOHITS_RUNCHANCE.value) {
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
        if (WINDCHARGE_HEAL_CHANCE.value <= 0
                || ThreadLocalRandom.current().nextDouble() * 100.0 >= WINDCHARGE_HEAL_CHANCE.value) {
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
        int need = Math.max(2, WEBTRAP_COUNT.asInt());
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
            if (elapsed >= Math.max(1, WINDCHARGE_WAIT_TICKS.asInt())) {
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
        if (f.fleeFor == 0 && cocoonReady(bot, f, hp) && startCocoon(server, name, f, bot)) {
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
        if (f.fleeStuckTicks >= Math.max(2, WINDCHARGE_BLOCKED_TICKS.asInt())
                && findItemIndex(bot, Items.WIND_CHARGE) >= 0
                && startWindEscape(server, name, f, bot, target)) {
            return;
        }
        if (globalTick % 2 == 0) {
            // Face directly away from the target — or toward a nearby cobweb, if there is one, to slow the chase.
            BlockPos web = WEB_ZONE_AWARE.on() && f.fleeFor == 0 ? nearbyWeb(bot, WEB_ZONE_RADIUS.value) : null;
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
            if (VOID_AWARE.on() || WEB_AVOID.on()) {
                double[] turns = {0, 50, -50, 100, -100};
                BlockPos targetCell = target.blockPosition();
                for (double turn : turns) {
                    double a = Math.toRadians(baseYaw + turn);
                    double px = bot.getX() - Math.sin(a) * 1.6;
                    double pz = bot.getZ() + Math.cos(a) * 1.6;
                    boolean bad = (VOID_AWARE.on() && hazardAt(bot.level(), px, bot.getY(), pz))
                            || (WEB_AVOID.on() && webAheadOf(bot.level(), px, bot.getY(), pz, targetCell));
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
        // Vanilla cancels sprinting the moment an item starts being used: re-assert it so eating doesn't slow the bot.
        if (EAT_FULL_SPEED.on() && !bot.isSprinting() && globalTick % 2 == 0) {
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
        boolean healthy = EAT_UNTIL_HEARTS.value <= 0 || hpNow >= EAT_UNTIL_HEARTS.value * 2.0;
        if ((f.eaten >= EAT_COUNT.asInt() && healthy) || findFoodIndex(bot) < 0) {
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
        f.healsRemaining = kind == 1 && POTION_HEARTS2.value > 0 && hp <= POTION_HEARTS2.value * 2.0 ? 2 : 0;
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
            int kind = f.healsRemaining > 0 && findPotionIndex(bot, 1) >= 0 ? 1 : wantedPotion(bot, f, dist, hp);
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
