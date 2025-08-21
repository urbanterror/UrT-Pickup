package de.gost0r.pickupbot.permission;

import de.gost0r.pickupbot.discord.DiscordRole;
import de.gost0r.pickupbot.discord.DiscordUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RoleService {

    public boolean hasRole(DiscordUser user, DiscordRole role) {
        return role.getGuild().hasRole(user, role);
    }

    public void addRole(DiscordUser user, DiscordRole role) {
        if (!role.getGuild().hasRole(user, role)) {
            role.getGuild().addRoleToMember(user, role);
            log.info("Added role {} to user {}", role.getName(), user.getUsername());
        }
    }

    public void removeRole(DiscordUser user, DiscordRole role) {
        if (role.getGuild().hasRole(user, role)) {
            role.getGuild().removeRoleFromMember(user, role);
            log.info("Removed role {} from user {}", role.getName(), user.getUsername());
        }
    }
}
