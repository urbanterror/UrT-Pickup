package de.gost0r.pickupbot.discord.jda;

import de.gost0r.pickupbot.discord.DiscordGuild;
import de.gost0r.pickupbot.discord.DiscordRole;
import de.gost0r.pickupbot.discord.DiscordUser;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class JdaDiscordGuild implements DiscordGuild {

    private final Guild guild;

    public JdaDiscordGuild(Guild guild) {
        this.guild = guild;
    }

    @Nullable
    @Override
    public DiscordUser getMember(String userId) {
        Member member = guild.getMemberById(userId);
        return member == null
                ? null
                : new JdaDiscordUser(member);
    }

    @Override
    public void addRoleToMember(DiscordUser user, DiscordRole role) {
        Role jdaRole = ((JdaDiscordRole) role).getRole();
        try {
            guild.addRoleToMember(UserSnowflake.fromId(user.getId()), jdaRole).queue();
        } catch (IllegalArgumentException e) {
            log.warn("Cannot add role {} to member {}: {}", role.getName(), user.getUsername(), e.getMessage());
        }
    }

    @Override
    public void removeRoleFromMember(DiscordUser user, DiscordRole role) {
        Role jdaRole = ((JdaDiscordRole) role).getRole();
        try {
            guild.removeRoleFromMember(UserSnowflake.fromId(user.getId()), jdaRole).queue();
        } catch (IllegalArgumentException e) {
            log.warn("Cannot remove role {} from member {}: {}", role.getName(), user.getUsername(), e.getMessage());
        }
    }

    @Override
    public List<DiscordRole> getRolesOfMember(DiscordUser user) {
        Member member = guild.getMemberById(user.getId());
        return member == null ? List.of() : member.getRoles()
                .stream()
                .map(JdaDiscordRole::new)
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public boolean hasRole(DiscordUser user, DiscordRole role) {
        Member member = guild.getMemberById(user.getId());
        return member != null && member.getRoles()
                .stream()
                .anyMatch(jdaRole -> jdaRole.getId().equals(role.getId()));
    }

}
