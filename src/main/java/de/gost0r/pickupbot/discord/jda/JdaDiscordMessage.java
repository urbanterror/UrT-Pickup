package de.gost0r.pickupbot.discord.jda;

import de.gost0r.pickupbot.discord.*;
import lombok.Getter;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

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

    @Override
    public void reply(@NotNull String content) {
        message.reply(content).queue();
    }

    @Override
    public void reply(@Nullable String content, @NotNull DiscordEmbed embed) {
        if (content != null) {
            message.reply(content)
                    .setEmbeds(mapToMessageEmbed(embed))
                    .queue();
        } else {
            message.replyEmbeds(mapToMessageEmbed(embed))
                    .queue();
        }
    }

    @Override
    public void reply(@Nullable String content, @Nullable DiscordEmbed embed, @NotNull List<DiscordComponent> components) {
        if (content != null) {
            MessageCreateAction action = message.reply(content);
            if (embed != null) {
                action.setEmbeds(mapToMessageEmbed(embed));
            }
            JdaUtils.mapToActionRows(components).forEach(action::addActionRow);
            action.queue();
        } else if (embed != null) {
            MessageCreateAction action = message.replyEmbeds(mapToMessageEmbed(embed));
            JdaUtils.mapToActionRows(components).forEach(action::addActionRow);
            action.queue();
        } else {
            message.replyComponents(JdaUtils.mapToActionRows(components).stream().map(ActionRow::of).toList())
                    .queue();
        }
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
