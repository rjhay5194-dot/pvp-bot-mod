package dev.pvpbotcmd;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.pvpbotcmd.PvpBotMod.*;

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
                "strafechance", "botfight", "bunnyhop", "hopstop");
        cat("Eating", "eat", "eatcount", "eatuntil", "hungereat", "eatfullspeed", "flee", "revenge");
        cat("Shield", "shield", "shieldrange", "shieldticks", "shieldticksmax", "postswingshield",
                "postswingshieldticksmin", "postswingshieldticksmax", "stun");
        cat("Combo", "combochance", "combostap", "combowtap", "combouppercut", "combostrafecombo",
                "combotapticks", "uppercutrange", "uppercutwindow", "combostrafeticks",
                "mixchance", "mixmaxhits", "mixswitchchance");
        cat("Escape", "combohitsmin", "combohitsmax", "combohitsescapechance", "windchargechance",
                "windchargehealchance", "windchargewaitticks", "windchargeblockedticks",
                "escapewebchance", "escaperunchance");
        cat("Webs", "hitweb", "webcrit", "webtime", "webleadticks", "webzone", "webzoneradius",
                "webescape", "webbreak", "stuck", "cocoon", "cocoonhearts", "webtrapchance",
                "webtraphearts", "webtrapcount", "randomwebchance", "randomwebmindist",
                "randomwebmaxdist", "randomwebcooldown");
        cat("Potions & Items", "potions", "potionhearts", "potionhearts2", "potiondelay", "totem",
                "totemhearts", "loot", "lootrange", "durabilityswap", "durabilitythreshold",
                "expbottle", "expbottlethreshold", "massmax");
        cat("Human Mistakes", "miss", "wobble", "flinchchance", "flinchticksmin", "flinchticksmax",
                "overshootchance", "overshootdegrees", "aimspread");
        cat("Team & FFA", "focuschance", "focusmin", "focusmax", "taunts", "ffaleave", "opsonly", "voidaware");
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
                lore.add("/pvpbot " + o.cmd);
                lore.add(o.desc);
                lore.add(o.bool ? "Click to toggle" : "Left: increase   Right: decrease   Shift: bigger step");
                Item icon = o.bool ? (o.on() ? Items.LIME_DYE : Items.GRAY_DYE) : Items.STICK;
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
