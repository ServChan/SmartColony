package org.lts.smartcolony.work;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.CraftPlanner;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.Schedule;
import org.lts.smartcolony.core.Specialists;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.util.WorldUtil;

public final class ScribeJob extends Job {
    private static final int BOOK_TARGET = 8;
    private static final int PAPER_TARGET = 24;
    private static final int CANE_TARGET = 18;
    private static final int PREPARE_TICKS = 140;
    private static final int CHRONICLE_EVERY_DAYS = 3;
    private static final int PAGES = 12;

    private String craftItem;
    private int craftCount;
    private int progress;
    private BlockPos cane;
    private BlockPos water;
    private int misses;
    private int cut;
    private long caneUntil;

    public ScribeJob(Colonist colonist) {
        super(colonist);
        depositThreshold = 6;
    }

    @Override
    public Role role() {
        return Role.SCRIBE;
    }

    @Override
    protected Predicate<ItemStack> tools() {
        return stack -> false;
    }

    @Override
    public void onRelease(ServerLevel level, Villager villager) {
        super.onRelease(level, villager);
        craftItem = null;
        cane = null;
    }

    @Override
    public void tick(ServerLevel level, Villager villager) {
        if (wait > 0) {
            wait--;
            return;
        }
        switch (phase) {
            case "start" -> start(level, villager);
            case "write" -> write(level, villager);
            case "chronicle" -> chronicle(level, villager);
            case "enchant" -> enchant(level, villager);
            case "cane" -> cane(level, villager);
            case "deposit" -> deposit(level, villager);
            default -> phase = "start";
        }
    }

    private ColonyData.Building library() {
        return colony().buildingOfKind(Specialists.LIBRARY);
    }

    private BlockPos find(ServerLevel level, Block block) {
        ColonyData.Building library = library();
        if (library == null) {
            return null;
        }
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(library.min.x(), library.origin.y(), library.min.z()),
                new BlockPos(library.max.x(), library.max.y(), library.max.z()))) {
            if (level.getBlockState(pos).is(block)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private void start(ServerLevel level, Villager villager) {
        if (hasCargo(villager) && (cargoFull(villager) || !onShift(level, villager))) {
            phase = "deposit";
            return;
        }
        if (!onShift(level, villager)) {
            wait = 100;
            return;
        }
        Stock stock = colony().warehouse().stock(level);
        long day = Schedule.day(level.getDefaultClockTime());
        ColonyData data = colony().data();
        if (day - data.chronicleDay >= CHRONICLE_EVERY_DAYS && find(level, Blocks.LECTERN) != null
                && (stock.get(Ids.mc("writable_book")) > 0 || CraftPlanner.plan(stock, Ids.mc("writable_book"), 1).isPresent())) {
            phase = "chronicle";
            return;
        }
        if (data.knowledge >= 3 && stock.get(Ids.mc("lapis_lazuli")) >= 1 && find(level, Blocks.ENCHANTING_TABLE) != null
                && enchantable(level) != null) {
            phase = "enchant";
            return;
        }
        if (stock.get(Ids.mc("enchanting_table")) > 0 && library() != null && find(level, Blocks.ENCHANTING_TABLE) == null
                && !library().swaps.containsKey(Ids.mc("red_carpet"))) {
            library().swaps.put(Ids.mc("red_carpet"), Ids.mc("enchanting_table"));
            colony().journal(level, "the scribe asked for the enchanting table to be set up in the library");
            colony().markDirty();
        }
        ColonyData.Building library = library();
        if (library != null && stock.get(Ids.mc("book")) >= 12 && (library.swaps.containsKey(Ids.mc("bookshelf"))
                || library.swaps.containsKey(Ids.mc("lectern")))) {
            library.swaps.remove(Ids.mc("bookshelf"));
            library.swaps.remove(Ids.mc("lectern"));
            colony().journal(level, "the scribe has enough books: the library gets its real shelves and lectern");
            colony().markDirty();
        }
        if (stock.get(Ids.mc("book")) < BOOK_TARGET && CraftPlanner.plan(stock, Ids.mc("book"), 1).isPresent()) {
            startWrite(Ids.mc("book"), Math.min(3, BOOK_TARGET - stock.get(Ids.mc("book"))));
            return;
        }
        if (stock.get(Ids.mc("book")) < BOOK_TARGET && stock.get(Ids.mc("leather")) == 0) {
            colony().request(Ids.mc("leather"));
        }
        if (stock.get(Ids.mc("paper")) < PAPER_TARGET && stock.get(Ids.mc("sugar_cane")) >= 3) {
            startWrite(Ids.mc("paper"), Math.min(9, stock.get(Ids.mc("sugar_cane")) / 3 * 3));
            return;
        }
        if (stock.get(Ids.mc("sugar_cane")) < CANE_TARGET && level.getGameTime() >= caneUntil) {
            phase = "cane";
            cane = null;
            cut = 0;
            misses = 0;
            return;
        }
        status = "reading in the library";
        BlockPos lectern = find(level, Blocks.LECTERN);
        if (lectern != null && Mover.distance(villager, lectern) > 3.0) {
            move(level, villager, lectern, 2.5);
            return;
        }
        idle(level, 600);
    }

    private void startWrite(String item, int count) {
        craftItem = item;
        craftCount = Math.max(1, count);
        progress = PREPARE_TICKS;
        phase = "write";
    }

    private BlockPos desk(ServerLevel level) {
        BlockPos lectern = find(level, Blocks.LECTERN);
        return lectern != null ? lectern : colony().depot();
    }

    private void write(ServerLevel level, Villager villager) {
        BlockPos desk = desk(level);
        status = "making " + Ids.path(craftItem);
        if (Mover.distance(villager, desk) > 3.0) {
            if (move(level, villager, desk, 2.5) == Mover.Result.FAILED) {
                phase = "start";
                wait = 100;
            }
            return;
        }
        villager.getNavigation().stop();
        villager.getLookControl().setLookAt(desk.getX() + 0.5, desk.getY() + 0.8, desk.getZ() + 0.5);
        if (progress % 20 == 0) {
            villager.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            level.playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_WORK_LIBRARIAN, SoundSource.NEUTRAL, 0.5F, 1.0F);
        }
        if (--progress > 0) {
            return;
        }
        if (colony().craft(level, craftItem, craftCount)) {
            gainXp(level, villager, 2);
            if (craftItem.equals(Ids.mc("book"))) {
                colony().data().knowledge += craftCount;
                colony().data().bump("books_written", craftCount);
                colony().markDirty();
            }
        }
        craftItem = null;
        phase = "start";
        wait = 20;
    }

    private void chronicle(ServerLevel level, Villager villager) {
        BlockPos lectern = find(level, Blocks.LECTERN);
        if (lectern == null) {
            phase = "start";
            return;
        }
        status = "writing the village chronicle";
        if (Mover.distance(villager, lectern) > 3.0) {
            if (move(level, villager, lectern, 2.5) == Mover.Result.FAILED) {
                phase = "start";
                wait = 200;
            }
            return;
        }
        if (colony().warehouse().stock(level).get(Ids.mc("writable_book")) == 0) {
            colony().craft(level, Ids.mc("writable_book"), 1);
        }
        if (colony().warehouse().take(level, Ids.mc("writable_book"), 1) < 1) {
            phase = "start";
            return;
        }
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough(colony().data().name), colony().data().name, 0, pages(level), true));
        BlockState state = level.getBlockState(lectern);
        if (state.getValue(LecternBlock.HAS_BOOK) && level.getBlockEntity(lectern) instanceof LecternBlockEntity entity) {
            ItemStack old = entity.getBook();
            entity.setBook(book);
            if (!old.isEmpty() && !old.is(Items.WRITTEN_BOOK)) {
                colony().warehouse().insert(level, old);
            }
        } else {
            LecternBlock.tryPlaceBook(villager, level, lectern, state, book);
        }
        villager.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        level.playSound(null, lectern, SoundEvents.BOOK_PUT, SoundSource.BLOCKS, 1.0F, 1.0F);
        colony().data().chronicleDay = Schedule.day(level.getDefaultClockTime());
        colony().data().knowledge++;
        colony().data().bump("chronicles", 1);
        colony().journal(level, "the scribe updated the village chronicle on the library lectern");
        colony().markDirty();
        gainXp(level, villager, 4);
        phase = "start";
        wait = 40;
    }

    private List<Filterable<Component>> pages(ServerLevel level) {
        ColonyData data = colony().data();
        List<Filterable<Component>> pages = new ArrayList<>();
        long day = Schedule.day(level.getDefaultClockTime());
        pages.add(Filterable.passThrough(Component.translatable("smartcolony.chronicle.cover", data.name, day,
                colony().colonists().size(), data.buildings.size(), data.stat("deaths"), data.stat("raids_won"))));
        List<String> lines = data.journal;
        StringBuilder page = new StringBuilder();
        int count = 0;
        for (int i = Math.max(0, lines.size() - PAGES * 4); i < lines.size(); i++) {
            String line = lines.get(i);
            int space = line.indexOf(' ');
            String text = space > 0 ? line.substring(space + 1) : line;
            long tick = 0;
            try {
                tick = Long.parseLong(space > 0 ? line.substring(0, space) : "0");
            } catch (NumberFormatException ignored) {
                tick = 0;
            }
            page.append("[").append(Math.floorDiv(tick, 24000L)).append("] ").append(text).append("\n");
            if (++count % 4 == 0) {
                pages.add(Filterable.passThrough(Component.literal(page.toString())));
                page.setLength(0);
            }
        }
        if (!page.isEmpty()) {
            pages.add(Filterable.passThrough(Component.literal(page.toString())));
        }
        return pages.size() > PAGES ? new ArrayList<>(pages.subList(0, PAGES)) : pages;
    }

    private ItemStack enchantable(ServerLevel level) {
        ItemStack best = ItemStack.EMPTY;
        for (Container container : colony().warehouse().all(level)) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty() || stack.isEnchanted() || !stack.isEnchantable()) {
                    continue;
                }
                if (!(stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SWORDS) || stack.is(ItemTags.SHOVELS)
                        || stack.is(Items.BOW) || stack.is(Items.IRON_CHESTPLATE))) {
                    continue;
                }
                if (best.isEmpty() || Tools.tier(stack) > Tools.tier(best)) {
                    best = stack;
                }
            }
        }
        return best;
    }

    private void enchant(ServerLevel level, Villager villager) {
        BlockPos table = find(level, Blocks.ENCHANTING_TABLE);
        if (table == null) {
            phase = "start";
            return;
        }
        status = "enchanting a tool";
        if (Mover.distance(villager, table) > 3.0) {
            if (move(level, villager, table, 2.5) == Mover.Result.FAILED) {
                phase = "start";
                wait = 200;
            }
            return;
        }
        ItemStack chosen = enchantable(level);
        if (chosen.isEmpty()) {
            phase = "start";
            return;
        }
        ItemStack tool = colony().warehouse().extractOne(level, stack -> stack == chosen);
        if (tool.isEmpty()) {
            phase = "start";
            return;
        }
        ColonyData data = colony().data();
        int shelves = shelves(level, table);
        int cost = Math.max(1, Math.min(30, Math.min(data.knowledge * 2, 8 + shelves * 2)));
        int lapis = cost >= 20 ? 3 : cost >= 10 ? 2 : 1;
        if (colony().warehouse().take(level, Ids.mc("lapis_lazuli"), lapis) < lapis) {
            colony().warehouse().insert(level, tool);
            phase = "start";
            return;
        }
        Optional<HolderSet.Named<Enchantment>> pool = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .get(EnchantmentTags.IN_ENCHANTING_TABLE);
        ItemStack enchanted = EnchantmentHelper.enchantItem(villager.getRandom(), tool, cost, level.registryAccess(), pool);
        data.knowledge = Math.max(0, data.knowledge - Math.max(1, cost / 5));
        data.bump("tools_enchanted", 1);
        colony().warehouse().insert(level, enchanted);
        level.playSound(null, table, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.ENCHANT, table.getX() + 0.5, table.getY() + 1.2, table.getZ() + 0.5, 30, 0.6, 0.6, 0.6, 0.5);
        colony().journal(level, "the scribe enchanted a " + Ids.path(org.lts.smartcolony.util.ItemIds.id(enchanted)));
        colony().markDirty();
        gainXp(level, villager, 5);
        phase = "start";
        wait = 60;
    }

    private static int shelves(ServerLevel level, BlockPos table) {
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(table.offset(-2, 0, -2), table.offset(2, 1, 2))) {
            if (level.getBlockState(pos).is(Blocks.BOOKSHELF)) {
                count++;
            }
        }
        return Math.min(15, count);
    }

    private void cane(ServerLevel level, Villager villager) {
        if (!onShift(level, villager) || cargoFull(villager) || cut >= 12) {
            phase = hasCargo(villager) ? "deposit" : "start";
            return;
        }
        if (cane == null) {
            status = "looking for sugar cane";
            cane = probe(level, villager);
            if (cane == null) {
                if (++misses > 25) {
                    caneUntil = level.getGameTime() + 4800;
                    plant(level, villager);
                    phase = hasCargo(villager) ? "deposit" : "start";
                }
                wait = 6;
                return;
            }
        }
        if (!level.getBlockState(cane).is(Blocks.SUGAR_CANE) || !level.getBlockState(cane.below()).is(Blocks.SUGAR_CANE)) {
            cane = null;
            return;
        }
        status = "cutting sugar cane";
        Mover.Result result = move(level, villager, cane, 2.2);
        if (result == Mover.Result.FAILED) {
            cane = null;
            misses++;
            return;
        }
        if (result != Mover.Result.ARRIVED) {
            return;
        }
        villager.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        level.destroyBlock(cane, false, villager);
        stash(level, villager, List.of(new ItemStack(Items.SUGAR_CANE)));
        cut++;
        cane = null;
        wait = 10;
    }

    private BlockPos probe(ServerLevel level, Villager villager) {
        BlockPos center = colony().center();
        int radius = colony().data().radius + 24;
        if (water == null || level.getGameTime() % 1200 == 0) {
            water = colony().findWater(level, center, radius);
        }
        var random = villager.getRandom();
        for (int i = 0; i < 40; i++) {
            BlockPos origin = water != null && i % 2 == 0 ? water : center;
            int spread = origin == water ? 10 : radius;
            int x = origin.getX() + random.nextInt(spread * 2 + 1) - spread;
            int z = origin.getZ() + random.nextInt(spread * 2 + 1) - spread;
            if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
                continue;
            }
            BlockPos pos = WorldUtil.surface(level, x, z).below();
            if (!level.getBlockState(pos).is(Blocks.SUGAR_CANE)) {
                continue;
            }
            BlockPos base = pos;
            while (level.getBlockState(base.below()).is(Blocks.SUGAR_CANE)) {
                base = base.below();
            }
            if (!base.equals(pos)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private void plant(ServerLevel level, Villager villager) {
        if (water == null) {
            return;
        }
        int planted = 0;
        for (BlockPos pos : BlockPos.betweenClosed(water.offset(-6, -1, -6), water.offset(6, 1, 6))) {
            if (planted >= 4) {
                break;
            }
            BlockState ground = level.getBlockState(pos);
            if (!(ground.is(BlockTags.DIRT) || ground.is(BlockTags.SAND)) || !level.getBlockState(pos.above()).isAir()
                    || colony().isProtected(pos.above(), true)) {
                continue;
            }
            boolean wet = false;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                if (level.getFluidState(pos.relative(side)).isSource()) {
                    wet = true;
                    break;
                }
            }
            if (!wet || colony().warehouse().take(level, Ids.mc("sugar_cane"), 1) < 1) {
                continue;
            }
            level.setBlock(pos.above(), Blocks.SUGAR_CANE.defaultBlockState(), Block.UPDATE_ALL);
            planted++;
        }
        if (planted > 0) {
            colony().data().bump("cane_planted", planted);
        }
    }

    private void deposit(ServerLevel level, Villager villager) {
        if (!atDepot(level, villager)) {
            if (goDepot(level, villager) == Mover.Result.FAILED) {
                wait = 100;
            }
            return;
        }
        depositAll(level, villager);
        phase = "start";
    }
}
