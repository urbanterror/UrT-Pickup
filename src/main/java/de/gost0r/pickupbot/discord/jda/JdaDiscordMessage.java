package de.gost0r.pickupbot.discord.jda;

import de.gost0r.pickupbot.discord.DiscordChannel;
import de.gost0r.pickupbot.discord.DiscordEmbed;
import de.gost0r.pickupbot.discord.DiscordMessage;
import de.gost0r.pickupbot.discord.DiscordUser;
import lombok.Getter;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

import static de.gost0r.pickupbot.discord.jda.JdaUtils.mapToMessageEmbed;

public class JdaDiscordMessage implements DiscordMessage {

    @Getter
    private final DiscordChannel channel;

    @Getter
    private final DiscordUser user;

    private final Message message;

    public JdaDiscordMessage(Message message) {
        this.message = message;
        channel = new JdaDiscordChannel(message.getChannel());
        user = new JdaDiscordUser(message.getMember(), message.getAuthor());
    }


    @Override
    public String getId() {
        return message.getId();
    }

    @Override
    public String getContent() {
        return message.getContentRaw();
    }

    @Override
    public void edit(DiscordEmbed embed) {
        message.editMessage(MessageEditData.fromEmbeds(mapToMessageEmbed(embed))).queue();
    }

    public void delete() {
        message.delete().queue();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JdaDiscordMessage that = (JdaDiscordMessage) o;
        return message.getId().equals(that.message.getId());
    }

    @Override
    public int hashCode() {
        return super.hashCode() + message.getId().hashCode();
    }
}
