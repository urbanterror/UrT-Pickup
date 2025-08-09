package de.gost0r.pickupbot.discord.jda;

import de.gost0r.pickupbot.discord.DiscordChannel;
import de.gost0r.pickupbot.discord.DiscordComponent;
import de.gost0r.pickupbot.discord.DiscordEmbed;
import de.gost0r.pickupbot.discord.DiscordMessage;
import de.gost0r.pickupbot.errorhandling.PickupException;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

import java.util.List;

import static de.gost0r.pickupbot.discord.jda.JdaUtils.mapToMessageEmbed;

@Slf4j
public class JdaDiscordChannel implements DiscordChannel {

    private final MessageChannel channel;

    public JdaDiscordChannel(MessageChannel messageChannel) {
        this.channel = messageChannel;
    }

    @Override
    public String getId() {
        return channel.getId();
    }

    @Override
    public String getName() {
        return channel.getName();
    }

    @Override
    public String getParentId() {
        if (channel instanceof ThreadChannel threadChannel) {
            return threadChannel.getParentChannel().getId();
        }
        return null;
    }

    @Override
    public String getGuildId() {
        if (channel instanceof GuildMessageChannel guildMessageChannel) {
            return guildMessageChannel.getGuild().getId();
        }
        return null;
    }

    @Override
    public boolean isThreadChannel() {
        return channel.getType() == ChannelType.GUILD_PUBLIC_THREAD;
    }

    @Override
    public boolean isPrivateChannel() {
        return channel.getType() == ChannelType.PRIVATE;
    }

    @Override
    public String getMentionString() {
        return channel.getAsMention();
    }

    @Override
    public void sendMessage(String message) {
        channel.sendMessage(message).queue();
    }

    @Override
    public void sendMessage(String message, DiscordEmbed embed) {
        MessageCreateBuilder builder = new MessageCreateBuilder();
        if (message != null) {
            builder.setContent(message);
        }
        if (embed != null) {
            builder.addEmbeds(mapToMessageEmbed(embed));
        }
        channel.sendMessage(builder.build()).queue();
    }

    @Override
    public DiscordMessage sendMessage(String message, DiscordEmbed embed, List<DiscordComponent> components) {
        MessageCreateBuilder builder = new MessageCreateBuilder();
        if (message != null) {
            builder.setContent(message);
        }
        if (embed != null) {
            builder.addEmbeds(mapToMessageEmbed(embed));
        }
        if (components != null) {
            builder.addActionRow(components.stream().map(JdaUtils::mapToItemComponent).toList());
        }
        return new JdaDiscordMessage(channel.sendMessage(builder.build()).complete());
    }

    @Override
    public void archive() {
        if (channel instanceof ThreadChannel threadChannel) {
            threadChannel.getManager().setArchived(true).queue();
        }
    }

    @Override
    public void delete() {
        channel.delete().queue();
    }

    @Override
    public DiscordChannel createThread(String name) {
        if (channel instanceof TextChannel textChannel) {
            return new JdaDiscordChannel(textChannel.createThreadChannel(name).complete());
        }
        throw new PickupException("Cannot create thread in channel of type " + channel.getType().name() + ".");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JdaDiscordChannel that = (JdaDiscordChannel) o;
        return channel.getId().equals(that.channel.getId());
    }

    @Override
    public int hashCode() {
        return super.hashCode() + channel.getId().hashCode();
    }
}