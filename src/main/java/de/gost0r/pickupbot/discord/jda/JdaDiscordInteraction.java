package de.gost0r.pickupbot.discord.jda;

import de.gost0r.pickupbot.discord.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;

import java.util.ArrayList;
import java.util.List;

@Slf4j
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
    public void deleteDeferredReply() {
        event.getHook()
                .deleteOriginal()
                .queue();
    }

    @Override
    public void deferReply() {
        event.deferReply()
                .setEphemeral(true)
                .queue();
    }

    @Override
    public void deferEdit() {
        event.deferEdit().queue();
    }

    @Override
    public void publishMessage() {
        // A channel send avoids the interaction reply reference to the private message we delete.
        event.getMessageChannel()
                .sendMessageEmbeds(event.getMessage().getEmbeds())
                .queue(published -> deleteDeferredReply(), failure -> {
                    log.warn("Could not publish stats", failure);
                    respondEphemeral("Could not publish stats. Please try again.");
                });
    }

    @Override
    public void respondEphemeral(String content) {
        if (content != null) {
            event.getHook()
                    .editOriginal(content)
                    .queue();
        }
    }

    @Override
    public void respondEphemeral(String content, DiscordEmbed embed) {
        if (content != null) {
            WebhookMessageEditAction<Message> callback = event.getHook().editOriginal(content);
            if (embed != null) {
                callback.setEmbeds(JdaUtils.mapToMessageEmbed(embed));
            }
            callback.queue();
        } else if (embed != null) {
            event.getHook().editOriginalEmbeds(JdaUtils.mapToMessageEmbed(embed))
                    .queue();
        }
    }

    @Override
    public void respondEphemeral(String content, DiscordEmbed embed, ArrayList<DiscordComponent> components) {
        if (content != null) {
            WebhookMessageEditAction<Message> callback = event.getHook().editOriginal(content);
            if (embed != null) {
                callback.setEmbeds(JdaUtils.mapToMessageEmbed(embed));
            }
            if (components != null) {
                callback.setComponents(JdaUtils.mapToActionRows(components));
            }
            callback.queue();
        } else if (embed != null) {
            WebhookMessageEditAction<Message> callback = event.getHook().editOriginalEmbeds(JdaUtils.mapToMessageEmbed(embed));
            if (components != null) {
                callback.setComponents(JdaUtils.mapToActionRows(components));
            }
            callback.queue();
        } else if (components != null) {
            event.getHook().editOriginalComponents(JdaUtils.mapToActionRows(components))
                    .queue();
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
