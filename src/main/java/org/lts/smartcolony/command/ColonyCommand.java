package org.lts.smartcolony.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.colony.ColonyManager;
import org.lts.smartcolony.colony.Founding;
import org.lts.smartcolony.colony.Market;
import org.lts.smartcolony.core.Blueprints;
import org.lts.smartcolony.core.Category;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.ItemClassifier;
import org.lts.smartcolony.core.Reputation;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.work.Colonist;

public final class ColonyCommand {
    private static final SuggestionProvider<CommandSourceStack> ROLES = (context, builder) -> {
        List<String> keys = new ArrayList<>();
        for (Role role : Role.values()) {
            keys.add(role.key());
        }
        keys.add("auto");
        return SharedSuggestionProvider.suggest(keys, builder);
    };
    private static final SuggestionProvider<CommandSourceStack> BLUEPRINTS =
            (context, builder) -> SharedSuggestionProvider.suggest(org.lts.smartcolony.core.Staged.suggestions(Blueprints.IDS), builder);

    private ColonyCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context,
                                Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("colony")
                .executes(ColonyCommand::info)
                .then(Commands.literal("info").executes(ColonyCommand::info))
                .then(Commands.literal("found").executes(ColonyCommand::found)
                        .then(Commands.literal("here").executes(ColonyCommand::foundHere)))
                .then(Commands.literal("list").executes(ColonyCommand::list))
                .then(Commands.literal("disband").executes(ColonyCommand::disband))
                .then(Commands.literal("stock").executes(ColonyCommand::stock))
                .then(Commands.literal("journal").executes(ColonyCommand::journal))
                .then(Commands.literal("market").executes(ColonyCommand::market))
                .then(Commands.literal("reputation").executes(ColonyCommand::reputation))
                .then(Commands.literal("build")
                        .then(Commands.argument("blueprint", StringArgumentType.word()).suggests(BLUEPRINTS)
                                .executes(ColonyCommand::build)))
                .then(Commands.literal("ai").executes(ctx -> ai(ctx, "status"))
                        .then(Commands.literal("on").executes(ctx -> ai(ctx, "on")))
                        .then(Commands.literal("off").executes(ctx -> ai(ctx, "off")))
                        .then(Commands.literal("now").executes(ctx -> ai(ctx, "now")))
                        .then(Commands.literal("reload").executes(ctx -> ai(ctx, "reload"))))
                .then(Commands.literal("role")
                        .then(Commands.argument("villagers", EntityArgument.entities())
                                .then(Commands.argument("role", StringArgumentType.word()).suggests(ROLES)
                                        .executes(ColonyCommand::role)))));
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (ColonyManager.all().isEmpty()) {
            source.sendSuccess(() -> Component.translatable("smartcolony.command.list_empty"), false);
            return 0;
        }
        int count = 0;
        for (Colony colony : ColonyManager.all()) {
            source.sendSuccess(() -> Component.translatable("smartcolony.command.list_line", colony.data().name, colony.id(),
                    colony.center().toShortString(), colony.colonists().size(), colony.data().radius), false);
            count++;
        }
        return count;
    }

    private static int ai(CommandContext<CommandSourceStack> context, String mode) {
        CommandSourceStack source = context.getSource();
        if (mode.equals("reload")) {
            java.util.concurrent.CompletableFuture.runAsync(org.lts.smartcolony.SmartColonyConfig::load)
                    .thenRun(() -> source.getServer().execute(() -> source.sendSuccess(() -> Component.translatable("smartcolony.command.ai_reload",
                            org.lts.smartcolony.SmartColonyConfig.get().aiEnabled ? "on" : "off"), false)));
            return 1;
        }
        Colony colony = colony(source);
        if (colony == null) {
            return 0;
        }
        org.lts.smartcolony.SmartColonyConfig config = org.lts.smartcolony.SmartColonyConfig.get();
        switch (mode) {
            case "on" -> {
                colony.data().aiOff = false;
                colony.markDirty();
                source.sendSuccess(() -> Component.translatable(config.aiEnabled ? "smartcolony.command.ai_on" : "smartcolony.command.ai_global_off"), false);
            }
            case "off" -> {
                colony.data().aiOff = true;
                colony.markDirty();
                source.sendSuccess(() -> Component.translatable("smartcolony.command.ai_off"), false);
            }
            case "now" -> {
                if (!colony.advisor().active()) {
                    source.sendFailure(Component.translatable("smartcolony.command.ai_global_off"));
                    return 0;
                }
                colony.advisor().askNow();
                source.sendSuccess(() -> Component.translatable("smartcolony.command.ai_now"), false);
            }
            default -> {
                String state = colony.advisor().state() + (colony.advisor().error().isEmpty() ? "" : " (" + colony.advisor().error() + ")");
                source.sendSuccess(() -> Component.translatable("smartcolony.command.ai_status", state, config.aiModel, config.aiEndpoint), false);
                if (!colony.advisor().thoughts().isEmpty()) {
                    source.sendSuccess(() -> Component.literal(colony.advisor().thoughts()), false);
                }
            }
        }
        return 1;
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

    private static boolean openUi(CommandSourceStack source, Colony colony, String tab) {
        return source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                && org.lts.smartcolony.colony.ColonyUi.open(player, colony, tab);
    }

    private static MutableComponent header(Colony colony) {
        return Component.translatable("smartcolony.command.header", colony.data().name).withStyle(ChatFormatting.GOLD);
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null) {
            return 0;
        }
        if (openUi(source, colony, "overview")) {
            return 1;
        }
        ServerLevel level = source.getLevel();
        ColonyData data = colony.data();
        EnumMap<Role, Integer> roles = new EnumMap<>(Role.class);
        int babies = 0;
        for (Map.Entry<java.util.UUID, Colonist> entry : colony.colonists().entrySet()) {
            if (level.getEntity(entry.getKey()) instanceof Villager villager && villager.isBaby()) {
                babies++;
                continue;
            }
            roles.merge(entry.getValue().role(), 1, Integer::sum);
        }
        int children = babies;
        source.sendSuccess(() -> header(colony), false);
        source.sendSuccess(() -> Component.translatable("smartcolony.command.population", colony.colonists().size(), children,
                data.beds(), data.buildings.size()), false);
        MutableComponent roleLine = Component.translatable("smartcolony.command.roles");
        for (Map.Entry<Role, Integer> entry : roles.entrySet()) {
            roleLine.append(" ").append(Component.translatable("smartcolony.role." + entry.getKey().key()))
                    .append(": " + entry.getValue() + ";");
        }
        source.sendSuccess(() -> roleLine, false);
        MutableComponent stockLine = Component.translatable("smartcolony.command.storage");
        Map<Category, Double> fill = colony.fill(level);
        for (Category category : Category.values()) {
            stockLine.append(" ").append(Component.translatable("smartcolony.storage." + category.key()))
                    .append(" " + Math.round(fill.get(category) * 100) + "%;");
        }
        source.sendSuccess(() -> stockLine, false);
        source.sendSuccess(() -> Component.translatable("smartcolony.command.food", colony.foodPoints(level)), false);
        for (ColonyData.Project project : data.projects) {
            String missing = !project.phase.equals("reserve") || colony.construction().missing().isEmpty() ? "-"
                    : colony.construction().missing().toString().replace("minecraft:", "");
            source.sendSuccess(() -> Component.translatable("smartcolony.command.project",
                    Component.translatable("smartcolony.building." + project.building.blueprint),
                    Component.translatable("smartcolony.phase." + project.phase), missing), false);
        }
        if (data.mine != null) {
            source.sendSuccess(() -> Component.translatable("smartcolony.command.mine", data.mine.entrance.toString(),
                    data.mine.progress, data.mine.plan().size()), false);
        }
        if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            int value = colony.reputation(player.getUUID());
            source.sendSuccess(() -> Component.translatable("smartcolony.command.reputation", value,
                    Component.translatable("smartcolony.status." + Reputation.status(value).key())), false);
        }
        return 1;
    }

    private static int found(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        BlockPos center = Founding.nearbyBell(level, pos, 48)
                .orElseGet(() -> level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos));
        if (ColonyManager.colonyNear(level, center, 96) != null) {
            source.sendFailure(Component.translatable("smartcolony.command.exists"));
            return 0;
        }
        Colony colony = Founding.found(level, center);
        if (colony == null) {
            source.sendFailure(Component.translatable("smartcolony.command.no_site"));
            if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                Founding.showPreview(player, Founding.planHere(level, player));
            }
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("smartcolony.command.founded", colony.data().name,
                colony.colonists().size()), true);
        return 1;
    }

    private static int foundHere(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        net.minecraft.server.level.ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        Founding.Plan plan = Founding.planHere(level, player);
        if (plan == null) {
            source.sendFailure(Component.translatable("smartcolony.site.unloaded"));
            return 0;
        }
        BlockPos site = plan.layout().origin();
        BlockPos center = Founding.nearbyBell(level, site, 48).orElse(site);
        if (ColonyManager.colonyNear(level, center, 96) != null) {
            source.sendFailure(Component.translatable("smartcolony.command.exists"));
            return 0;
        }
        if (!plan.valid()) {
            Founding.showPreview(player, plan);
            source.sendFailure(Component.translatable("smartcolony.command.site_blocked", plan.reason()));
            return 0;
        }
        Colony colony = Founding.found(level, center, plan);
        source.sendSuccess(() -> Component.translatable("smartcolony.command.founded", colony.data().name,
                colony.colonists().size()), true);
        return 1;
    }

    private static int disband(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null) {
            return 0;
        }
        String name = colony.data().name;
        ColonyManager.disband(source.getLevel(), colony);
        source.sendSuccess(() -> Component.translatable("smartcolony.command.disbanded", name), true);
        return 1;
    }

    private static int stock(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null) {
            return 0;
        }
        if (openUi(source, colony, "storage")) {
            return 1;
        }
        source.sendSuccess(() -> header(colony), false);
        Map<String, Integer> stock = colony.warehouse().stock(source.getLevel()).sorted();
        for (Category category : Category.values()) {
            StringBuilder line = new StringBuilder();
            for (Map.Entry<String, Integer> entry : stock.entrySet()) {
                if (ItemClassifier.classify(entry.getKey()) == category) {
                    line.append(entry.getKey().replace("minecraft:", "")).append(" ").append(entry.getValue()).append(", ");
                }
            }
            String text = line.isEmpty() ? "-" : line.substring(0, line.length() - 2);
            source.sendSuccess(() -> Component.translatable("smartcolony.storage." + category.key()).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(": " + text).withStyle(ChatFormatting.WHITE)), false);
        }
        return 1;
    }

    private static int journal(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null) {
            return 0;
        }
        if (openUi(source, colony, "journal")) {
            return 1;
        }
        source.sendSuccess(() -> header(colony), false);
        List<String> lines = colony.data().journal;
        for (String line : lines.subList(Math.max(0, lines.size() - 12), lines.size())) {
            int space = line.indexOf(' ');
            String text = space > 0 ? line.substring(space + 1) : line;
            source.sendSuccess(() -> Component.literal("- " + text).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int market(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null) {
            return 0;
        }
        if (openUi(source, colony, "market")) {
            return 1;
        }
        source.sendSuccess(() -> header(colony), false);
        List<Market.Deal> ledger = colony.economy().market().ledger();
        if (ledger.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("smartcolony.command.market_empty"), false);
        }
        for (Market.Deal deal : ledger.subList(Math.max(0, ledger.size() - 10), ledger.size())) {
            source.sendSuccess(() -> Component.literal("- " + deal.soldCount() + " " + deal.sold().replace("minecraft:", "") + " -> "
                    + deal.boughtCount() + " " + deal.bought().replace("minecraft:", "") + " (" + deal.trader() + ")"), false);
        }
        if (!colony.requests().isEmpty()) {
            source.sendSuccess(() -> Component.translatable("smartcolony.command.requests",
                    String.join(", ", colony.requests()).replace("minecraft:", "")), false);
        }
        return 1;
    }

    private static int reputation(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null) {
            return 0;
        }
        if (openUi(source, colony, "overview")) {
            return 1;
        }
        var player = source.getPlayerOrException();
        int value = colony.reputation(player.getUUID());
        source.sendSuccess(() -> Component.translatable("smartcolony.command.reputation", value,
                Component.translatable("smartcolony.status." + Reputation.status(value).key())), false);
        return 1;
    }

    private static int build(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Colony colony = colony(source);
        if (colony == null) {
            return 0;
        }
        String blueprint = StringArgumentType.getString(context, "blueprint");
        boolean staged = org.lts.smartcolony.core.Staged.isFamily(blueprint);
        if (!staged && !Blueprints.IDS.contains(blueprint)) {
            source.sendFailure(Component.translatable("smartcolony.command.unknown_blueprint", blueprint));
            return 0;
        }
        if (colony.data().projects.size() >= 3) {
            source.sendFailure(Component.translatable("smartcolony.command.busy"));
            return 0;
        }
        if (staged || org.lts.smartcolony.core.Staged.isStaged(blueprint)) {
            org.lts.smartcolony.core.Staged.Failure failure = colony.construction().orderStaged(source.getLevel(), blueprint);
            if (failure != org.lts.smartcolony.core.Staged.Failure.NONE) {
                source.sendFailure(Component.translatable(stagedKey(failure)));
                return 0;
            }
            String ordered = colony.data().projects.getLast().building.blueprint;
            source.sendSuccess(() -> Component.translatable("smartcolony.command.planned",
                    Component.translatable("smartcolony.building." + ordered)), false);
            return 1;
        }
        if (!colony.construction().forceProject(source.getLevel(), blueprint)) {
            source.sendFailure(Component.translatable("smartcolony.command.no_site"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("smartcolony.command.planned",
                Component.translatable("smartcolony.building." + blueprint)), false);
        return 1;
    }

    private static String stagedKey(org.lts.smartcolony.core.Staged.Failure failure) {
        return switch (failure) {
            case BUSY -> "smartcolony.command.staged_busy";
            case COMPLETE -> "smartcolony.command.staged_complete";
            case WRONG_STAGE -> "smartcolony.command.staged_wrong_stage";
            case IRON_AGE -> "smartcolony.command.staged_iron_age";
            case NO_SITE, NONE, NOT_STAGED -> "smartcolony.command.staged_no_site";
        };
    }

    private static int role(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<? extends Entity> entities = EntityArgument.getEntities(context, "villagers");
        String key = StringArgumentType.getString(context, "role");
        Role role = key.equals("auto") ? null : Role.byKey(key);
        if (role == Role.IDLE && !key.equalsIgnoreCase("idle")) {
            source.sendFailure(Component.translatable("smartcolony.command.unknown_role", key));
            return 0;
        }
        int changed = 0;
        for (Entity entity : entities) {
            if (entity instanceof Villager villager) {
                Colony colony = ColonyManager.colonyOf(villager);
                if (colony != null) {
                    colony.lockRole(source.getLevel(), villager, role);
                    changed++;
                }
            }
        }
        int total = changed;
        source.sendSuccess(() -> Component.translatable("smartcolony.command.role_set", total, key), false);
        return changed;
    }
}
