package org.lts.smartcolony.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lts.smartcolony.colony.ColonyUi;
import org.lts.smartcolony.colony.ColonyView;
import org.lts.smartcolony.colony.Market;

public final class ColonyScreen extends Screen {
    private static final int BG_CONTAINER = 0xF00D141F;
    private static final int BG_HEADER = 0xFF182638;
    private static final int BORDER_PRIMARY = 0xFF2E435E;
    private static final int BG_SLOT = 0x80000000;
    private static final int ACCENT_CYAN = 0xFF00E5FF;
    private static final int ACCENT_GOLD = 0xFFFFC14D;
    private static final int TEXT_DIM = 0xFF8FA7BF;
    private static final int TEXT_NORMAL = 0xFFE0E0E0;
    private static final int STATUS_ON = 0xFF55FF55;
    private static final int STATUS_WARN = 0xFFFFC14D;
    private static final int STATUS_OFF = 0xFFFF5555;
    private static final String[] TABS = {"overview", "storage", "villagers", "journal", "market"};
    private static final Map<String, String> ROLE_ICONS = Map.of(
            "idle", "minecraft:bread", "lumberjack", "minecraft:stone_axe", "miner", "minecraft:stone_pickaxe",
            "farmer", "minecraft:stone_hoe", "fisherman", "minecraft:fishing_rod", "herder", "minecraft:wheat",
            "builder", "minecraft:bricks", "guard", "minecraft:iron_sword");
    private static final Map<String, String> STORAGE_ICONS = Map.of(
            "wood", "minecraft:oak_log", "mining", "minecraft:cobblestone", "food", "minecraft:wheat",
            "animal", "minecraft:white_wool", "arsenal", "minecraft:iron_sword");

    private ColonyView view;
    private String tab;
    private final List<Button> tabButtons = new ArrayList<>();
    private CompletableFuture<Optional<ColonyView>> pending;
    private int ticks;
    private double scroll;
    private int contentHeight;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int contentTop;
    private int contentBottom;
    private int mouseX;
    private int mouseY;
    private ItemStack hoverStack = ItemStack.EMPTY;

    public ColonyScreen(ColonyView view, String tab) {
        super(Component.translatable("smartcolony.ui.title"));
        this.view = view;
        this.tab = tab;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(460, width - 16);
        panelHeight = Math.min(300, height - 16);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        tabButtons.clear();
        int gap = 4;
        int tabWidth = (panelWidth - 16 - gap * (TABS.length - 1)) / TABS.length;
        int tabY = top + 24;
        for (int i = 0; i < TABS.length; i++) {
            String key = TABS[i];
            Button button = Button.builder(Component.translatable("smartcolony.ui.tab." + key), b -> select(key))
                    .bounds(left + 8 + i * (tabWidth + gap), tabY, tabWidth, 18).build();
            button.active = !key.equals(tab);
            tabButtons.add(addRenderableWidget(button));
        }
        contentTop = tabY + 24;
        contentBottom = top + panelHeight - 8;
    }

    private void select(String key) {
        tab = key;
        scroll = 0;
        for (int i = 0; i < TABS.length; i++) {
            tabButtons.get(i).active = !TABS[i].equals(key);
        }
    }

    @Override
    public void tick() {
        ticks++;
        if (pending != null && pending.isDone()) {
            pending.getNow(Optional.empty()).ifPresent(fresh -> view = fresh);
            pending = null;
        }
        if (ticks % 20 == 0 && pending == null && minecraft != null && minecraft.player != null) {
            IntegratedServer server = minecraft.getSingleplayerServer();
            if (server != null) {
                pending = ColonyUi.request(server, minecraft.player.getUUID());
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int visible = contentBottom - contentTop;
        scroll = Math.max(0, Math.min(Math.max(0, contentHeight - visible), scroll - scrollY * 24));
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        hoverStack = ItemStack.EMPTY;
        graphics.fill(left, top, left + panelWidth, top + panelHeight, BG_CONTAINER);
        graphics.outline(left, top, panelWidth, panelHeight, BORDER_PRIMARY);
        graphics.fill(left + 1, top + 1, left + panelWidth - 1, top + 20, BG_HEADER);
        graphics.text(font, Component.translatable("smartcolony.command.header", view.name()), left + 8, top + 6, ACCENT_GOLD, true);
        Component reputation = Component.translatable("smartcolony.ui.reputation", view.reputation(),
                Component.translatable("smartcolony.status." + view.status()));
        graphics.text(font, reputation, left + panelWidth - 8 - font.width(reputation), top + 6, reputationColor(), false);
        int visible = contentBottom - contentTop;
        scroll = Math.max(0, Math.min(Math.max(0, contentHeight - visible), scroll));
        graphics.enableScissor(left + 4, contentTop, left + panelWidth - 4, contentBottom);
        int x = left + 10;
        int y = contentTop - (int) scroll;
        int w = panelWidth - 26;
        int end = switch (tab) {
            case "storage" -> storage(graphics, x, y, w);
            case "villagers" -> villagers(graphics, x, y, w);
            case "journal" -> journal(graphics, x, y, w);
            case "market" -> market(graphics, x, y, w);
            default -> overview(graphics, x, y, w);
        };
        graphics.disableScissor();
        contentHeight = end - y;
        if (contentHeight > visible) {
            int barX = left + panelWidth - 7;
            graphics.fill(barX, contentTop, barX + 3, contentBottom, BG_SLOT);
            int thumb = Math.max(16, visible * visible / contentHeight);
            int thumbY = contentTop + (int) ((visible - thumb) * (scroll / (contentHeight - visible)));
            graphics.fill(barX, thumbY, barX + 3, thumbY + thumb, BORDER_PRIMARY);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (!hoverStack.isEmpty()) {
            graphics.setTooltipForNextFrame(font, hoverStack, mouseX, mouseY);
        }
    }

    private int reputationColor() {
        return switch (view.status()) {
            case "enemy" -> STATUS_OFF;
            case "suspect" -> STATUS_WARN;
            case "ally" -> STATUS_ON;
            default -> TEXT_DIM;
        };
    }

    private int overview(GuiGraphicsExtractor graphics, int x, int y, int w) {
        graphics.text(font, Component.translatable("smartcolony.ui.population", view.members(), view.children(), view.beds(),
                view.buildings()), x, y, TEXT_NORMAL, false);
        y += 16;
        y = heading(graphics, Component.translatable("smartcolony.ui.roles"), x, y);
        if (view.roles().isEmpty()) {
            graphics.text(font, Component.translatable("smartcolony.ui.empty"), x, y, TEXT_DIM, false);
            y += 14;
        }
        int columns = Math.max(1, w / 140);
        int columnWidth = w / columns;
        for (int i = 0; i < view.roles().size(); i++) {
            ColonyView.RoleCount role = view.roles().get(i);
            int cx = x + (i % columns) * columnWidth;
            int cy = y + (i / columns) * 20;
            item(graphics, ROLE_ICONS.getOrDefault(role.role(), "minecraft:bread"), 1, cx, cy);
            graphics.text(font, Component.translatable("smartcolony.role." + role.role()).append(": " + role.count()),
                    cx + 20, cy + 4, TEXT_NORMAL, false);
        }
        y += (view.roles().size() + columns - 1) / columns * 20 + 4;
        y = heading(graphics, Component.translatable("smartcolony.ui.storage"), x, y);
        for (ColonyView.Storage storage : view.storages()) {
            item(graphics, STORAGE_ICONS.get(storage.category()), 1, x, y);
            graphics.text(font, Component.translatable("smartcolony.storage." + storage.category()), x + 20, y + 4, TEXT_NORMAL, false);
            int barLeft = x + 130;
            int barRight = x + w - 40;
            bar(graphics, barLeft, y + 5, barRight, storage.percent() / 100.0, fillColor(storage.percent()));
            graphics.text(font, storage.percent() + "%", barRight + 6, y + 4, TEXT_DIM, false);
            y += 20;
        }
        y += 4;
        graphics.text(font, Component.translatable("smartcolony.command.food", view.food()), x, y, TEXT_NORMAL, false);
        y += 16;
        y = heading(graphics, Component.translatable("smartcolony.ui.construction"), x, y);
        if (view.project() == null) {
            graphics.text(font, Component.translatable("smartcolony.ui.project_none"), x, y, TEXT_DIM, false);
            y += 14;
        } else {
            graphics.text(font, Component.translatable("smartcolony.ui.project",
                    Component.translatable("smartcolony.building." + view.project()),
                    Component.translatable("smartcolony.phase." + view.phase())), x, y, TEXT_NORMAL, false);
            y += 14;
            for (String site : view.sites()) {
                String[] parts = site.split("\\|", 2);
                graphics.text(font, Component.translatable("smartcolony.ui.project",
                        Component.translatable("smartcolony.building." + parts[0]),
                        Component.translatable("smartcolony.phase." + parts[1])), x, y, TEXT_NORMAL, false);
                y += 14;
            }
            if (!view.missing().isEmpty()) {
                graphics.text(font, Component.translatable("smartcolony.ui.missing"), x, y + 4, STATUS_WARN, false);
                y = items(graphics, view.missing(), x + 80, y, w - 80);
            }
        }
        y += 4;
        y = heading(graphics, Component.translatable("smartcolony.ui.mine_title"), x, y);
        if (view.mine() == null) {
            graphics.text(font, Component.translatable("smartcolony.ui.mine_none"), x, y, TEXT_DIM, false);
            y += 14;
        } else {
            graphics.text(font, Component.translatable("smartcolony.ui.mine", view.mine(), view.mineProgress(), view.mineTotal()),
                    x, y, TEXT_NORMAL, false);
            y += 12;
            bar(graphics, x, y, x + w, view.mineTotal() == 0 ? 0 : view.mineProgress() / (double) view.mineTotal(), ACCENT_CYAN);
            y += 12;
        }
        y += 4;
        y = heading(graphics, Component.translatable("smartcolony.ui.wall_title"), x, y);
        if (view.wallMaterial() == null) {
            graphics.text(font, Component.translatable("smartcolony.ui.wall_none"), x, y, TEXT_DIM, false);
            y += 14;
        } else {
            Component material = Component.translatable(view.wallMaterial().equals("stone") ? "smartcolony.ui.wall_stone" : "smartcolony.ui.wall_wood");
            graphics.text(font, Component.translatable("smartcolony.ui.wall", material, view.wallBuilt(), view.wallTotal()), x, y, TEXT_NORMAL, false);
            y += 12;
            bar(graphics, x, y, x + w, view.wallTotal() == 0 ? 0 : view.wallBuilt() / (double) view.wallTotal(), ACCENT_GOLD);
            y += 12;
        }
        if (!view.requests().isEmpty()) {
            y += 4;
            y = heading(graphics, Component.translatable("smartcolony.ui.requests"), x, y);
            List<ColonyView.ItemCount> wanted = new ArrayList<>();
            for (String request : view.requests()) {
                wanted.add(new ColonyView.ItemCount(request, 1));
            }
            y = items(graphics, wanted, x, y, w);
        }
        return y;
    }

    private int storage(GuiGraphicsExtractor graphics, int x, int y, int w) {
        for (ColonyView.Storage storage : view.storages()) {
            item(graphics, STORAGE_ICONS.get(storage.category()), 1, x, y);
            graphics.text(font, Component.translatable("smartcolony.storage." + storage.category()), x + 20, y + 4, ACCENT_CYAN, false);
            Component percent = Component.literal(storage.percent() + "%");
            graphics.text(font, percent, x + w - font.width(percent), y + 4, fillColor(storage.percent()), false);
            y += 20;
            if (storage.items().isEmpty()) {
                graphics.text(font, Component.translatable("smartcolony.ui.empty"), x + 20, y, TEXT_DIM, false);
                y += 14;
            } else {
                y = items(graphics, storage.items(), x, y, w);
            }
            graphics.fill(x, y + 2, x + w, y + 3, BORDER_PRIMARY);
            y += 8;
        }
        return y;
    }

    private int villagers(GuiGraphicsExtractor graphics, int x, int y, int w) {
        if (view.villagers().isEmpty()) {
            graphics.text(font, Component.translatable("smartcolony.ui.empty"), x, y, TEXT_DIM, false);
            return y + 14;
        }
        for (ColonyView.Member member : view.villagers()) {
            String icon = member.hand().isEmpty() ? ROLE_ICONS.getOrDefault(member.role(), "minecraft:bread") : member.hand();
            item(graphics, icon, 1, x, y);
            Component name = member.baby() ? Component.translatable("smartcolony.ui.child")
                    : member.skill() > 0 ? Component.translatable("smartcolony.role." + member.role()).append(" ")
                    .append(Component.translatable("smartcolony.ui.skill", member.skill()))
                    : Component.translatable("smartcolony.role." + member.role());
            graphics.text(font, name, x + 20, y + 4, TEXT_NORMAL, false);
            Component state;
            int color;
            if (member.baby()) {
                state = Component.translatable("smartcolony.ui.playing");
                color = TEXT_DIM;
            } else if (member.working()) {
                state = Component.translatable("smartcolony.ui.working");
                color = STATUS_ON;
            } else {
                state = Component.translatable("smartcolony.ui.resting");
                color = TEXT_DIM;
            }
            graphics.text(font, state, x + 110, y + 4, color, false);
            double health = member.maxHealth() <= 0 ? 0 : member.health() / member.maxHealth();
            bar(graphics, x + 200, y + 5, x + 260, health, health > 0.6 ? STATUS_ON : health > 0.3 ? STATUS_WARN : STATUS_OFF);
            Component distance = Component.translatable("smartcolony.ui.distance", member.distance());
            graphics.text(font, distance, x + w - font.width(distance), y + 4, TEXT_DIM, false);
            y += 20;
        }
        return y;
    }

    private int journal(GuiGraphicsExtractor graphics, int x, int y, int w) {
        if (view.journal().isEmpty()) {
            graphics.text(font, Component.translatable("smartcolony.ui.empty"), x, y, TEXT_DIM, false);
            return y + 14;
        }
        for (String line : view.journal()) {
            graphics.text(font, font.plainSubstrByWidth(line, w), x, y, TEXT_NORMAL, false);
            y += 12;
        }
        return y;
    }

    private int market(GuiGraphicsExtractor graphics, int x, int y, int w) {
        if (!view.requests().isEmpty()) {
            y = heading(graphics, Component.translatable("smartcolony.ui.requests"), x, y);
            List<ColonyView.ItemCount> wanted = new ArrayList<>();
            for (String request : view.requests()) {
                wanted.add(new ColonyView.ItemCount(request, 1));
            }
            y = items(graphics, wanted, x, y, w) + 4;
        }
        y = heading(graphics, Component.translatable("smartcolony.ui.deals"), x, y);
        if (view.deals().isEmpty()) {
            graphics.text(font, Component.translatable("smartcolony.command.market_empty"), x, y, TEXT_DIM, false);
            return y + 14;
        }
        for (Market.Deal deal : view.deals()) {
            item(graphics, deal.sold(), deal.soldCount(), x, y);
            graphics.text(font, "->", x + 24, y + 4, TEXT_DIM, false);
            item(graphics, deal.bought(), deal.boughtCount(), x + 40, y);
            graphics.text(font, font.plainSubstrByWidth(deal.trader(), w - 70), x + 64, y + 4, TEXT_DIM, false);
            y += 20;
        }
        return y;
    }

    private int heading(GuiGraphicsExtractor graphics, Component text, int x, int y) {
        graphics.text(font, text, x, y, ACCENT_CYAN, false);
        return y + 13;
    }

    private int items(GuiGraphicsExtractor graphics, List<ColonyView.ItemCount> list, int x, int y, int w) {
        int columns = Math.max(1, w / 22);
        for (int i = 0; i < list.size(); i++) {
            ColonyView.ItemCount entry = list.get(i);
            item(graphics, entry.item(), entry.count(), x + (i % columns) * 22, y + (i / columns) * 20);
        }
        return y + (list.size() + columns - 1) / columns * 20;
    }

    private void item(GuiGraphicsExtractor graphics, String id, int count, int x, int y) {
        ItemStack stack = stack(id);
        graphics.fill(x - 1, y - 1, x + 17, y + 17, BG_SLOT);
        graphics.fakeItem(stack, x, y);
        if (count > 1) {
            graphics.itemDecorations(font, stack, x, y, compact(count));
        }
        if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16 && mouseY >= contentTop && mouseY < contentBottom) {
            hoverStack = stack;
        }
    }

    private void bar(GuiGraphicsExtractor graphics, int x0, int y, int x1, double fraction, int color) {
        graphics.fill(x0, y, x1, y + 6, BG_SLOT);
        int filled = (int) Math.round((x1 - x0) * Math.max(0, Math.min(1, fraction)));
        if (filled > 0) {
            graphics.fill(x0, y, x0 + filled, y + 6, color);
        }
        graphics.outline(x0, y, x1 - x0, 6, BORDER_PRIMARY);
    }

    private static int fillColor(int percent) {
        return percent >= 90 ? STATUS_OFF : percent >= 70 ? STATUS_WARN : STATUS_ON;
    }

    private static String compact(int count) {
        return count >= 10000 ? count / 1000 + "k" : Integer.toString(count);
    }

    private static ItemStack stack(String id) {
        Identifier key = id == null ? null : Identifier.tryParse(id);
        Item item = key == null ? Items.BARRIER : BuiltInRegistries.ITEM.getValue(key);
        return new ItemStack(item == Items.AIR ? Items.BARRIER : item);
    }
}
