package de.gost0r.pickupbot.discord.jda;

import de.gost0r.pickupbot.discord.DiscordComponent;
import de.gost0r.pickupbot.discord.DiscordEmbed;
import de.gost0r.pickupbot.discord.DiscordRole;
import de.gost0r.pickupbot.discord.DiscordUser;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.util.List;
import java.util.stream.Collectors;

import static de.gost0r.pickupbot.discord.jda.JdaUtils.mapToMessageEmbed;

@Slf4j
public class JdaDiscordUser implements DiscordUser {


    private final Member member;
    private final User user;

    public JdaDiscordUser(Member member, User user) {
        this.member = member;
        this.user = user;
    }

    public JdaDiscordUser(Member member) {
        this(member, null);
    }

    public JdaDiscordUser(User user) {
        this(null, user);
    }

    @Override
    public String getMentionString() {
        return getUser().getAsMention();
    }

    @Override
    public List<DiscordRole> getRoles() {
        assert member != null;
        return member.getRoles().stream().map(JdaDiscordRole::new).collect(Collectors.toUnmodifiableList());
    }

    @Override
    public boolean hasRoleById(String roleId) {
        if (member != null) {
            return member.getRoles()
                    .stream()
                    .anyMatch(role -> role.getId().equals(roleId));
        }
        return false;
    }

    @Override
    public void removeRoleById(String roleId) {
        assert member != null;
        Role role = member.getRoles()
                .stream()
                .filter(r -> r.getId().equals(roleId))
                .findFirst()
                .orElseThrow();
        member.getGuild().removeRoleFromMember(member, role).queue();
    }

    @Override
    public void addRoleById(String roleId) {
        assert member != null;
        Role role = member.getGuild()
                .getRoles()
                .stream()
                .filter(r -> r.getId().equals(roleId))
                .findFirst()
                .orElseThrow();
        member.getGuild().addRoleToMember(member, role).queue();
    }

    @Override
    public void sendPrivateMessage(String message) {
        getUser().openPrivateChannel()
                .flatMap(channel -> channel.sendMessage(message))
                .queue();
    }

    @Override
    public void sendPrivateMessage(String message, DiscordEmbed embed, List<DiscordComponent> components) {
        MessageCreateBuilder builder = new MessageCreateBuilder();
        if (message != null) {
            builder.setContent(message);
        }

        if (embed != null) {
            builder.addEmbeds(mapToMessageEmbed(embed));
        }

        if (components != null && !components.isEmpty()) {
            JdaUtils.mapToActionRows(components).forEach(builder::addActionRow);
        }

        MessageCreateData messageData = builder.build();
        getUser().openPrivateChannel()
                .flatMap(channel -> channel.sendMessage(messageData))
                .queue();
    }

    @Override
    public String getAvatarUrl() {
        return getUser().getAvatarUrl();
    }

    @Override
    public String getId() {
        return getUser().getId();
    }

    @Override
    public String getUsername() {
        return member != null ? member.getEffectiveName() : getUser().getEffectiveName();
    }

    private User getUser() {
        if (member != null) {
            return member.getUser();
        } else {
            return user;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JdaDiscordUser that = (JdaDiscordUser) o;
        return getUser().getId().equals(that.getUser().getId());
    }

    @Override
    public int hashCode() {
        return super.hashCode() + getUser().getId().hashCode();
    }

}
