package org.lts.smartcolony.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.colony.ColonyManager;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Diplomacy;

final class DiplomacyCommand {
    private static final SuggestionProvider<CommandSourceStack> POLICIES = (context, builder) -> {
        List<String> keys = new ArrayList<>();
        for (Diplomacy.Policy policy : Diplomacy.Policy.values()) {
            keys.add(policy.key());
        }
        return SharedSuggestionProvider.suggest(keys, builder);
    };
    private static final SuggestionProvider<CommandSourceStack> COLONIES = (context, builder) -> {
        List<String> keys = new ArrayList<>();
        for (Colony colony : ColonyManager.all()) {
            keys.add(colony.id());
        }
        return SharedSuggestionProvider.suggest(keys, builder);
    };

    private DiplomacyCommand() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> diplomacy() {
        return Commands.literal("diplomacy")
                .executes(DiplomacyCommand::relations)
                .then(Commands.literal("policy")
                        .then(Commands.argument("policy", StringArgumentType.word()).suggests(POLICIES).executes(DiplomacyCommand::policy)))
                .then(Commands.literal("envoy")
                        .then(Commands.argument("colony", StringArgumentType.word()).suggests(COLONIES)
                                .executes(ctx -> envoy(ctx, false))))
                .then(Commands.literal("peace")
                        .then(Commands.argument("colony", StringArgumentType.word()).suggests(COLONIES)
                                .executes(ctx -> envoy(ctx, true))))
                .then(Commands.literal("road")
                        .then(Commands.argument("colony", StringArgumentType.word()).suggests(COLONIES)
                                .executes(DiplomacyCommand::road)))
                .then(Commands.literal("raid")
                        .then(Commands.argument("colony", StringArgumentType.word()).suggests(COLONIES)
                                .executes(DiplomacyCommand::raid)))
                .then(Commands.literal("war")
                        .then(Commands.argument("colony", StringArgumentType.word()).suggests(COLONIES)
                                .executes(DiplomacyCommand::war)));
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

    private static Colony other(CommandContext<CommandSourceStack> context, Colony colony) {
        String id = StringArgumentType.getString(context, "colony");
        Colony other = ColonyManager.byId(id);
        if (other == null) {
            for (Colony candidate : ColonyManager.all()) {
                if (candidate.data().name.equalsIgnoreCase(id)) {
                    other = candidate;
                }
            }
        }
        if (other == null || other == colony) {
            context.getSource().sendFailure(Component.translatable("smartcolony.command.diplomacy_unknown", id));
            return null;
        }
        return other;
    }

    static int relations(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null) {
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("smartcolony.command.diplomacy_header", colony.data().name,
                Component.translatable("smartcolony.diplomacy.temper." + colony.embassy().temper().key()),
                Component.translatable("smartcolony.diplomacy.policy." + colony.embassy().policy().key())), false);
        if (colony.data().relations.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("smartcolony.ui.diplomacy.none"), false);
            return 0;
        }
        int count = 0;
        for (Map.Entry<String, ColonyData.Relation> entry : colony.data().relations.entrySet()) {
            Colony other = ColonyManager.byId(entry.getKey());
            if (other == null) {
                continue;
            }
            ColonyData.Relation relation = entry.getValue();
            ColonyData.Relation theirs = other.data().relations.get(colony.id());
            int distance = (int) Math.sqrt(colony.center().distSqr(other.center()));
            source.sendSuccess(() -> Component.translatable("smartcolony.command.diplomacy_line", other.data().name, other.id(), distance,
                    Component.translatable("smartcolony.diplomacy.stance." + Diplomacy.stance(relation.standing, relation.treaty()).key()),
                    Component.translatable("smartcolony.diplomacy.treaty_name." + relation.treaty), relation.standing,
                    theirs == null ? 0 : theirs.standing), false);
            count++;
        }
        return count;
    }

    private static int policy(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null) {
            return 0;
        }
        Diplomacy.Policy policy = Diplomacy.Policy.byKey(StringArgumentType.getString(context, "policy"));
        colony.embassy().setPolicy(policy);
        colony.journal(source.getLevel(), "decree: diplomacy policy " + policy.key());
        source.sendSuccess(() -> Component.translatable("smartcolony.command.diplomacy_policy",
                Component.translatable("smartcolony.diplomacy.policy." + policy.key())), false);
        return 1;
    }

    private static int envoy(CommandContext<CommandSourceStack> context, boolean peace) {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null) {
            return 0;
        }
        Colony other = other(context, colony);
        if (other == null) {
            return 0;
        }
        if (colony.embassy().activeMissions(other) > 0) {
            source.sendFailure(Component.translatable("smartcolony.command.diplomacy_busy", other.data().name));
            return 0;
        }
        Diplomacy.Action action = colony.embassy().suggestedEnvoy(other);
        if (peace) {
            ColonyData.Relation relation = colony.embassy().relation(other);
            if (relation == null || relation.treaty() != Diplomacy.Treaty.WAR) {
                source.sendFailure(Component.translatable("smartcolony.command.diplomacy_not_war", other.data().name));
                return 0;
            }
            action = Diplomacy.Action.PROPOSE_TRUCE;
        }
        String how = colony.embassy().sendEnvoy(source.getLevel(), other, action);
        Diplomacy.Action sent = action;
        source.sendSuccess(() -> Component.translatable("smartcolony.command.diplomacy_sent." + how, other.data().name,
                Component.translatable("smartcolony.diplomacy.action." + sent.key())), false);
        return 1;
    }

    private static int road(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null) {
            return 0;
        }
        Colony other = other(context, colony);
        if (other == null) {
            return 0;
        }
        String result = colony.highways().order(source.getLevel(), other);
        Component message = Component.translatable("smartcolony.command.road." + result, other.data().name);
        if (result.equals("ordered") || result.equals("retry")) {
            source.sendSuccess(() -> message, false);
            return 1;
        }
        source.sendFailure(message);
        return 0;
    }

    private static int raid(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null) {
            return 0;
        }
        Colony other = other(context, colony);
        if (other == null) {
            return 0;
        }
        ColonyData.Relation relation = colony.embassy().relation(other);
        if (relation == null || relation.treaty() != Diplomacy.Treaty.WAR) {
            source.sendFailure(Component.translatable("smartcolony.command.diplomacy_not_war", other.data().name));
            return 0;
        }
        int sent = colony.embassy().orderRaid(source.getLevel(), other);
        if (sent == 0) {
            source.sendFailure(Component.translatable("smartcolony.command.diplomacy_no_raiders", other.data().name));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("smartcolony.command.diplomacy_raid", sent, other.data().name), false);
        return sent;
    }

    private static int war(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null) {
            return 0;
        }
        Colony other = other(context, colony);
        if (other == null) {
            return 0;
        }
        colony.embassy().declareWar(source.getLevel(), other, source.getLevel().getGameTime());
        return 1;
    }
}
