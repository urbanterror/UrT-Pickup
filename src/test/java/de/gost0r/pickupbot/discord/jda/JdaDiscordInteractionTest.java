package de.gost0r.pickupbot.discord.jda;

import de.gost0r.pickupbot.discord.DiscordButton;
import de.gost0r.pickupbot.discord.DiscordButtonStyle;
import de.gost0r.pickupbot.discord.DiscordComponent;
import de.gost0r.pickupbot.discord.DiscordEmbed;
import de.gost0r.pickupbot.pickup.Config;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdaDiscordInteractionTest {
    private GenericComponentInteractionCreateEvent event;
    private InteractionHook hook;
    private List<MessageEmbed> embeds;
    private MessageCreateAction action;
    private JdaDiscordInteraction interaction;

    @BeforeEach
    void setup() {
        event = mock(GenericComponentInteractionCreateEvent.class, RETURNS_DEEP_STUBS);
        hook = event.getHook();
        embeds = List.of(new EmbedBuilder()
                .setTitle("alpha")
                .setDescription("All time stats")
                .addField("Kills / Assists", "120/30", true)
                .addField("Wins", "7", true)
                .build());
        when(event.getMessage().getEmbeds()).thenReturn(embeds);
        action = event.getMessageChannel().sendMessageEmbeds(embeds);
        interaction = new JdaDiscordInteraction(event);
        // Do not count calls made while arranging deep-stub return values.
        clearInvocations(event, hook, action, event.getMessageChannel());
    }

    @Test
    void publishesDisplayedStatsWithoutReplyReferenceBeforeDeletingPrivateMessage() {
        interaction.deferEdit();
        interaction.publishMessage();

        verify(event).deferEdit();
        verify(event, never()).deferReply();
        verify(event.getMessageChannel()).sendMessageEmbeds(same(embeds));
        verify(hook, never()).sendMessageEmbeds(anyList());
        // Only the embeds are published, so the private Publish button is not copied.
        verify(hook, never()).deleteOriginal();

        ArgumentCaptor<Consumer<Message>> onSuccess = successCallback();
        verify(action).queue(onSuccess.capture(), any());
        verifyNoMoreInteractions(action);
        onSuccess.getValue().accept(mock(Message.class));

        verify(hook).deleteOriginal();
        verify(hook.deleteOriginal()).queue();
    }

    @Test
    void failedPublicationKeepsPrivateStatsAndAllowsRetry() {
        interaction.deferEdit();
        interaction.publishMessage();

        ArgumentCaptor<Consumer<Throwable>> onFailure = failureCallback();
        verify(action).queue(any(), onFailure.capture());
        onFailure.getValue().accept(new IllegalStateException("Missing send permission"));

        verify(hook, never()).deleteOriginal();
        WebhookMessageEditAction<Message> feedback = hook.editOriginal(
                "Could not publish stats. Please try again.");
        verify(feedback).queue();
        // Editing only the content preserves the displayed embeds and Publish button.
        verifyNoMoreInteractions(feedback);

        interaction.publishMessage();
        ArgumentCaptor<Consumer<Message>> onSuccess = successCallback();
        verify(action, times(2)).queue(onSuccess.capture(), any());
        onSuccess.getValue().accept(mock(Message.class));

        verify(hook).deleteOriginal();
        verify(event.getMessageChannel(), times(2)).sendMessageEmbeds(same(embeds));
    }

    @Test
    void publishesOriginalSnapshotWithoutUpdatingStats() {
        MessageEmbed secondEmbed = new EmbedBuilder().setDescription("Additional stats").build();
        List<MessageEmbed> snapshot = List.of(embeds.get(0), secondEmbed);
        when(event.getMessage().getEmbeds()).thenReturn(snapshot);
        MessageCreateAction snapshotAction = event.getMessageChannel().sendMessageEmbeds(snapshot);
        clearInvocations(hook, snapshotAction, event.getMessageChannel());

        interaction.publishMessage();

        verify(event.getMessageChannel()).sendMessageEmbeds(same(snapshot));
        verify(hook, never()).sendMessageEmbeds(anyList());
        verify(snapshotAction).queue(any(), any());
        verifyNoMoreInteractions(snapshotAction);
        verify(hook, never()).editOriginalEmbeds(anyList());
    }

    @Test
    void regularStatsReplyIsStillDeferredPrivately() {
        interaction.deferReply();

        verify(event).deferReply();
        verify(event.deferReply()).setEphemeral(true);
        verify(event.deferReply().setEphemeral(true)).queue();
        verify(event, never()).deferEdit();
    }

    @Test
    void privateStatsReplyContainsUsablePublishButtonAndStatsEmbed() {
        DiscordEmbed stats = new DiscordEmbed();
        stats.setTitle("alpha");
        stats.setDescription("All time stats");
        stats.addField("Wins", "7", true);
        DiscordButton publish = new DiscordButton(DiscordButtonStyle.PURPLE);
        publish.setLabel("Publish");
        publish.setCustomId(Config.INT_PUBLISHSTATS);
        ArrayList<DiscordComponent> components = new ArrayList<>(List.of(publish));
        @SuppressWarnings("unchecked")
        WebhookMessageEditAction<Message> edit = mock(WebhookMessageEditAction.class, RETURNS_SELF);
        doReturn(edit).when(hook).editOriginalEmbeds(any(MessageEmbed[].class));
        clearInvocations(hook, edit);

        interaction.respondEphemeral(null, stats, components);

        ArgumentCaptor<MessageEmbed> embed = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embed.capture());
        assertEquals("All time stats", embed.getValue().getDescription());
        assertEquals("7", embed.getValue().getFields().get(0).getValue());
        verify(edit).setComponents(argThat((Collection<MessageTopLevelComponent> rows) -> {
            var button = ((ActionRow) rows.iterator().next()).getButtons().get(0);
            return "Publish".equals(button.getLabel())
                    && Config.INT_PUBLISHSTATS.equals(button.getCustomId())
                    && !button.isDisabled();
        }));
        verify(edit).queue();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Consumer<Message>> successCallback() {
        return ArgumentCaptor.forClass(Consumer.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Consumer<Throwable>> failureCallback() {
        return ArgumentCaptor.forClass(Consumer.class);
    }
}
