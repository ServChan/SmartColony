package org.lts.smartcolony.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.colony.ColonyManager;
import org.lts.smartcolony.colony.Contracts;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Decree;
import org.lts.smartcolony.util.ItemIds;

final class DecreeCommand {
    private static final SuggestionProvider<CommandSourceStack> FOCUS = (context, builder) -> {
        List<String> keys = new ArrayList<>();
        for (Decree.Focus focus : Decree.Focus.values()) {
            keys.add(focus.key());
        }
        return SharedSuggestionProvider.suggest(keys, builder);
    };

    private DecreeCommand() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> decree() {
        return Commands.literal("decree")
                .executes(DecreeCommand::status)
                .then(Commands.literal("focus")
                        .executes(DecreeCommand::status)
                        .then(Commands.argument("focus", StringArgumentType.word()).suggests(FOCUS).executes(DecreeCommand::focus)))
                .then(Commands.literal("zone")
                        .then(Commands.literal("add").executes(ctx -> addZone(ctx, 8))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(Decree.MIN_ZONE, Decree.MAX_ZONE))
                                        .executes(ctx -> addZone(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))
                        .then(Commands.literal("remove").executes(DecreeCommand::removeZone))
                        .then(Commands.literal("list").executes(DecreeCommand::listZones)));
    }

    static LiteralArgumentBuilder<CommandSourceStack> order(CommandBuildContext context) {
        return Commands.literal("order")
                .executes(DecreeCommand::listOrders)
                .then(Commands.literal("list").executes(DecreeCommand::listOrders))
                .then(Commands.literal("cancel").executes(DecreeCommand::cancelOrders))
                .then(Commands.argument("item", ItemArgument.item(context))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, Decree.MAX_CONTRACT))
                                .executes(DecreeCommand::addOrder)));
    }

    private static Colony colony(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        Colony colony = ColonyManager.colonyAt(level, pos);
        if (colony == null) {
            colony = ColonyManager.colonyNear(level, pos, 256);
        }
        if (colony == null) {
            source.sendFailure(Component.translatable("smartcolony.command.none"));
        }
        return colony;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null) {
            return 0;
        }
        Decree.Focus focus = Decree.Focus.byKey(colony.data().focus);
        source.sendSuccess(() -> Component.translatable("smartcolony.command.focus_status",
                Component.translatable("smartcolony.decree.focus." + focus.key()), colony.data().zones.size(),
                colony.data().contracts.size()), false);
        return 1;
    }

    private static int focus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null) {
            return 0;
        }
        Decree.Focus focus = Decree.Focus.byKey(StringArgumentType.getString(context, "focus"));
        colony.data().focus = focus.key();
        colony.journal(source.getLevel(), "decree: focus on " + focus.key());
        colony.planRoles(source.getLevel());
        source.sendSuccess(() -> Component.translatable("smartcolony.command.focus_set",
                Component.translatable("smartcolony.decree.focus." + focus.key())), false);
        return 1;
    }

    private static int addZone(CommandContext<CommandSourceStack> context, int radius) {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null) {
            return 0;
        }
        if (colony.data().zones.size() >= Decree.MAX_ZONES) {
            source.sendFailure(Component.translatable("smartcolony.command.zone_full", Decree.MAX_ZONES));
            return 0;
        }
        BlockPos pos = BlockPos.containing(source.getPosition());
        int r = Decree.clampRadius(radius);
        colony.data().zones.add(new Decree.Zone(pos.getX(), pos.getZ(), r));
        colony.journal(source.getLevel(), "decree: no building within " + r + " of " + pos.getX() + " " + pos.getZ());
        colony.markDirty();
        source.sendSuccess(() -> Component.translatable("smartcolony.command.zone_added", pos.getX(), pos.getZ(), r), false);
        return 1;
    }

    private static int removeZone(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null) {
            return 0;
        }
        BlockPos pos = BlockPos.containing(source.getPosition());
        Decree.Zone nearest = null;
        double best = Double.MAX_VALUE;
        for (Decree.Zone zone : colony.data().zones) {
            double distance = Math.hypot(zone.x() - pos.getX(), zone.z() - pos.getZ());
            if (distance < best) {
                best = distance;
                nearest = zone;
            }
        }
        if (nearest == null || best > nearest.radius() + 16) {
            source.sendFailure(Component.translatable("smartcolony.command.zone_none"));
            return 0;
        }
        colony.data().zones.remove(nearest);
        colony.markDirty();
        Decree.Zone removed = nearest;
        source.sendSuccess(() -> Component.translatable("smartcolony.command.zone_removed", removed.x(), removed.z()), false);
        return 1;
    }

    private static int listZones(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null) {
            return 0;
        }
        if (colony.data().zones.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("smartcolony.command.zone_empty"), false);
            return 0;
        }
        for (Decree.Zone zone : colony.data().zones) {
            source.sendSuccess(() -> Component.translatable("smartcolony.command.zone_line", zone.x(), zone.z(), zone.radius()), false);
        }
        return colony.data().zones.size();
    }

    private static int addOrder(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null || !(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        String item = ItemIds.id(ItemArgument.getItem(context, "item").item().value());
        int count = IntegerArgumentType.getInteger(context, "count");
        Contracts.Result result = colony.contracts().add(source.getLevel(), player, item, count);
        switch (result) {
            case FULL -> {
                source.sendFailure(Component.translatable("smartcolony.command.order_full", Decree.MAX_CONTRACTS));
                return 0;
            }
            case UNKNOWN_ITEM -> {
                source.sendFailure(Component.translatable("smartcolony.command.order_unknown", item));
                return 0;
            }
            default -> {
            }
        }
        source.sendSuccess(() -> Component.translatable("smartcolony.command.order_added", count,
                ItemIds.stack(item, 1).getHoverName()), false);
        return 1;
    }

    private static int cancelOrders(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null || !(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        int removed = colony.contracts().cancel(source.getLevel(), player.getUUID());
        source.sendSuccess(() -> Component.translatable("smartcolony.command.order_cancelled", removed), false);
        return removed;
    }

    private static int listOrders(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null) {
            return 0;
        }
        if (colony.data().contracts.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("smartcolony.command.order_empty"), false);
            return 0;
        }
        for (ColonyData.Contract contract : colony.data().contracts) {
            source.sendSuccess(() -> Component.translatable("smartcolony.command.order_line", ItemIds.stack(contract.item, 1).getHoverName(),
                    contract.delivered + contract.parcel, contract.count, contract.parcel), false);
        }
        return colony.data().contracts.size();
    }
}
