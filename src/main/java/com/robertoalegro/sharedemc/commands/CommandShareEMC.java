package com.robertoalegro.sharedemc.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.robertoalegro.sharedemc.util.Util;
import com.robertoalegro.sharedemc.data.ShareEMCData;
import com.robertoalegro.sharedemc.util.ColorStyle;
import moze_intel.projecte.api.capabilities.IKnowledgeProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

public class CommandShareEMC {

    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null || overworld.isClientSide || (overworld.getGameTime() % 20L) != 12) return;

        ShareEMCData data = overworld.getDataStorage().computeIfAbsent(ShareEMCData::load, ShareEMCData::new, ShareEMCData.KEY);

        for (Set<UUID> team : data.teams()) {
            List<UUID> availablePlayers = team.stream().filter(playerUUID -> {
                @Nullable IKnowledgeProvider provider = Util.getKnowledgeProvider(playerUUID);
                return Util.getPlayer(playerUUID) != null && provider != null;
            }).toList();
            // Prevent divide by zero cases when no one is online
            if (team.size() == 0 || availablePlayers.size() == 0) continue;

            BigInteger totalEMC = availablePlayers.stream()
                    .map(Util::getKnowledgeProvider)
                    .map(Objects::requireNonNull)
                    .map(IKnowledgeProvider::getEmc)
                    .reduce(BigInteger.ZERO, BigInteger::add);
            long availablePlayerCount = availablePlayers.size();

            BigInteger avg = totalEMC.divide(BigInteger.valueOf(availablePlayerCount));
            BigInteger remainder = totalEMC.remainder(BigInteger.valueOf(availablePlayerCount));

            UUID[] players = new UUID[(int) availablePlayerCount];
            players = availablePlayers.toArray(players);

            for (int i = 0; i < players.length; i++) {
                ServerPlayer player = Util.getPlayer(players[i]);
                @Nullable IKnowledgeProvider provider = Util.getKnowledgeProvider(players[i]);
                BigInteger emcGiven = avg.add(i == 0 ? remainder : BigInteger.ZERO);
                if (player == null || provider == null || provider.getEmc().equals(emcGiven)) continue;
                provider.setEmc(emcGiven);
                provider.syncEmc(player);
            }
        }
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> cmd = Commands.literal("shareEMC")
                .then(Commands.literal("team")
                        .then(Commands.literal("create")
                                .executes((ctx) -> handle(ctx, ActionType.ADD_TEAM))
                        )
                )
                .then(Commands.literal("team")
                        .then(Commands.literal("leave")
                                .executes((ctx) -> handle(ctx, ActionType.LEAVE_TEAM))
                        )
                )
                .then(Commands.literal("team")
                        .then(Commands.literal("list")
                                .executes((ctx) -> handle(ctx, ActionType.LIST_TEAM))
                        )
                )
                .then(Commands.literal("team")
                        .then(Commands.literal("invite")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes((ctx) -> handle(ctx, ActionType.INVITE_PLAYER))
                                )
                        )
                );

        dispatcher.register(cmd);
    }

    private static int handle(CommandContext<CommandSourceStack> ctx, ActionType action) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();

        MinecraftServer server = player.getServer();
        if (server == null) return 0;
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return 0;

        ShareEMCData data = overworld.getDataStorage().computeIfAbsent(ShareEMCData::load, ShareEMCData::new, ShareEMCData.KEY);

        Optional<Set<UUID>> team_membership = data.team_membership(player.getUUID());

        switch (action) {
            case ADD_TEAM -> {
                if (team_membership.isPresent()) {
                    ctx.getSource().sendFailure(new TranslatableComponent("command.shareemc.add_team.already_member").setStyle(ColorStyle.RED));
                    return 0;
                }
                data.create_team(player.getUUID());
                ctx.getSource().sendSuccess(new TranslatableComponent("command.shareemc.add_team.success").setStyle(ColorStyle.GREEN), false);
            }
            case LEAVE_TEAM -> {
                if (team_membership.isEmpty()) {
                    ctx.getSource().sendFailure(new TranslatableComponent("command.shareemc.remove_team.not_member").setStyle(ColorStyle.RED));
                    return 0;
                }
                data.leave_team(player.getUUID());
                ctx.getSource().sendSuccess(new TranslatableComponent("command.shareemc.remove_team.success", data.membersText(team_membership.get())).setStyle(ColorStyle.GREEN), false);
            }
            case INVITE_PLAYER -> {
                if (team_membership.isEmpty()) {
                    ctx.getSource().sendFailure(new TranslatableComponent("command.shareemc.remove_team.not_member").setStyle(ColorStyle.RED));
                    return 0;
                }
                ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                Optional<Set<UUID>> target_membership = data.team_membership(target.getUUID());
                if (target_membership.isPresent()) {
                    ctx.getSource().sendFailure(new TranslatableComponent("command.shareemc.invite_team.target_busy", target.getScoreboardName()).setStyle(ColorStyle.RED));
                    return 0;
                }

                data.invite_player(player.getUUID(), target.getUUID());

                target.sendMessage(new TranslatableComponent("command.shareemc.invite_team.target", player.getScoreboardName(), data.membersText(team_membership.get())).setStyle(ColorStyle.GREEN), target.getUUID());

                ctx.getSource().sendSuccess(new TranslatableComponent("command.shareemc.invite_team.success", target.getScoreboardName(), data.membersText(team_membership.get())).setStyle(ColorStyle.GREEN), false);
            }
            case LIST_TEAM -> {
                if (team_membership.isEmpty()) {
                    ctx.getSource().sendFailure(new TranslatableComponent("command.shareemc.remove_team.not_member").setStyle(ColorStyle.RED));
                    return 0;
                }

                ctx.getSource().sendSuccess(
                        new TranslatableComponent(
                                "command.shareemc.list_team",
                                data.membersText(team_membership.get())
                        ).setStyle(ColorStyle.GREEN),
                        false
                );

                return 0;
            }
        };

        data.setDirty();

        return 1;
    }

    private enum ActionType {
        ADD_TEAM,
        LEAVE_TEAM,
        INVITE_PLAYER,
        LIST_TEAM,
    }
}