package de.gost0r.pickupbot.discord;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public interface DiscordMessage {

    String getId();

    DiscordUser getUser();

    DiscordChannel getChannel();

    String getContent();

    void edit(DiscordEmbed embed);

    void reply(@NotNull String message);

    void reply(@Nullable String message, @NotNull DiscordEmbed embed);

    void reply(@Nullable String message, @Nullable DiscordEmbed embed, @NotNull List<DiscordComponent> components);

    void delete();
}
