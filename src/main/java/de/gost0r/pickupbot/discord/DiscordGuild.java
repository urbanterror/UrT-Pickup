package de.gost0r.pickupbot.discord;

import org.jetbrains.annotations.Nullable;

import java.util.List;

public interface DiscordGuild {

    @Nullable
    DiscordUser getMember(String userId);

    void addRoleToMember(DiscordUser user, DiscordRole role);

    void removeRoleFromMember(DiscordUser user, DiscordRole role);

    List<DiscordRole> getRolesOfMember(DiscordUser user);

    boolean hasRole(DiscordUser user, DiscordRole role);

}
