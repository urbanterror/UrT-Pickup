package de.gost0r.pickupbot.discord.jda;

import de.gost0r.pickupbot.discord.DiscordRole;
import lombok.Getter;
import net.dv8tion.jda.api.entities.Role;

public class JdaDiscordRole implements DiscordRole {

    @Getter
    private final Role role;

    public JdaDiscordRole(Role role) {
        this.role = role;
    }

    @Override
    public String getId() {
        return role.getId();
    }

    @Override
    public String getName() {
        return role.getName();
    }

    @Override
    public String getMentionString() {
        return role.getAsMention();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JdaDiscordRole that = (JdaDiscordRole) o;
        return role.getId().equals(that.role.getId());
    }

    @Override
    public int hashCode() {
        return super.hashCode() + role.getId().hashCode();
    }

}
