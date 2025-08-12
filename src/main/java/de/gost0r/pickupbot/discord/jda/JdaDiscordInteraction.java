package de.gost0r.pickupbot.discord.jda;

import de.gost0r.pickupbot.discord.*;
import lombok.Getter;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.ItemComponent;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;

import java.util.ArrayList;
import java.util.List;

public class JdaDiscordInteraction implements DiscordInteraction {
    @Getter
    private final DiscordUser user;
    @Getter
    private final DiscordMessage message;

    private final GenericComponentInteractionCreateEvent event;
    private final List<String> values;

    public JdaDiscordInteraction(GenericComponentInteractionCreateEvent event) {
        this.event = event;
        user = new JdaDiscordUser(event.getMember(), event.getUser());
        message = new JdaDiscordMessage(event.getMessage());

        values = event instanceof StringSelectInteractionEvent ? ((StringSelectInteractionEvent) event).getInteraction().getValues() : null;
    }

    @Override
    public void deferReply() {
        event.deferReply().queue();
    }

    @Override
    public void respondEphemeral(String content) {
        if (content != null) {
            event.reply(content)
                    .setEphemeral(true)
                    .queue();
        }
    }

    @Override
    public void respondEphemeral(String content, DiscordEmbed embed) {
        if (content != null) {
            ReplyCallbackAction callback = event.reply(content);
            if (embed != null) {
                callback.setEmbeds(JdaUtils.mapToMessageEmbed(embed));
            }
            callback.setEphemeral(true)
                    .queue();
        } else if (embed != null) {
            event.replyEmbeds(JdaUtils.mapToMessageEmbed(embed))
                    .setEphemeral(true).queue();
        }
    }

    @Override
    public void respondEphemeral(String content, DiscordEmbed embed, ArrayList<DiscordComponent> components) {
        if (content != null) {
            ReplyCallbackAction callback = event.reply(content);
            if (embed != null) {
                callback.setEmbeds(JdaUtils.mapToMessageEmbed(embed));
            }
            if (components != null) {
                JdaUtils.mapToActionRows(components).forEach(callback::addActionRow);
            }
            callback.setEphemeral(true)
                    .queue();
        } else if (embed != null) {
            ReplyCallbackAction callback = event.replyEmbeds(JdaUtils.mapToMessageEmbed(embed));
            if (components != null) {
                JdaUtils.mapToActionRows(components).forEach(callback::addActionRow);
            }
            callback.setEphemeral(true)
                    .queue();
        } else if (components != null) {
            List<List<ItemComponent>> rows = JdaUtils.mapToActionRows(components);
            if (!rows.isEmpty()) {
                ReplyCallbackAction callback = event.replyComponents(ActionRow.of(rows.removeFirst()));
                rows.forEach(callback::addActionRow);
                callback.setEphemeral(true)
                        .queue();
            }
        }
    }

    @Override
    public String getId() {
        return event.getId();
    }

    @Override
    public String getToken() {
        return event.getToken();
    }

    @Override
    public String getComponentId() {
        return event.getComponentId();
    }

    @Override
    public List<String> getValues() {
        return values;
    }
}