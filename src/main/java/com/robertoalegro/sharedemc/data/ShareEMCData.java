package com.robertoalegro.sharedemc.data;

import com.robertoalegro.sharedemc.util.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;
import java.util.stream.Collectors;

public class ShareEMCData extends SavedData {
    public static final String KEY = "share_emc_data";
    private Set<Set<UUID>> shares;

    private final HashMap<UUID, Set<UUID>> team_cache;

    public ShareEMCData() {
        super();
        this.shares = new HashSet<>();
        this.team_cache = new HashMap<>();
    }

    public void create_team(UUID ...players) {
        this.shares.add(new HashSet<>(Arrays.stream(players).toList()));
    }

    public Set<Set<UUID>> teams() {
        return this.shares;
    }

    public Optional<Set<UUID>> team_membership(UUID player) {
        Set<UUID> team_membership = this.team_cache.get(player);
        if (team_membership != null) return Optional.of(team_membership);

        return this.shares.stream().filter((entry) -> entry.contains(player)).findFirst();
    }

    public boolean leave_team(UUID player) {
        Optional<Set<UUID>> membership = this.team_membership(player);
        if (membership.isEmpty()) return false;

        membership.get().remove(player);
        this.team_cache.remove(player);

        return true;
    }

    public boolean invite_player(UUID player, UUID target) {
        Optional<Set<UUID>> membership = this.team_membership(player);
        if (membership.isEmpty()) return false;

        membership.get().add(target);
        this.team_cache.put(target, membership.get());

        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        this.shares.forEach(t -> {
            ListTag team = new ListTag();
            t.forEach(member -> {
                team.add(NbtUtils.createUUID(member));
            });
            list.add(team);
        });

        tag.put(KEY, list);
        return tag;
    }

    public static ShareEMCData load(CompoundTag tag) {
        ShareEMCData data = new ShareEMCData();

        try {
            ListTag list = tag.getList(KEY, 9);
            data.shares = list.stream().map(t -> {
                ListTag team = (ListTag) t;
                return team.stream().map(NbtUtils::loadUUID).collect(Collectors.toSet());
            }).collect(Collectors.toSet());
        } catch (Exception ignored) {};

        return data;
    }

    public String membersText(Set<UUID> members) {
        return members.stream()
                .map(Util::getPlayer)
                .map(Objects::requireNonNull)
                .map(Player::getScoreboardName)
                .collect(Collectors.joining(", "));
    }
}
