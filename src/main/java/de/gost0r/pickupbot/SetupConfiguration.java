package de.gost0r.pickupbot;

import de.gost0r.pickupbot.discord.DiscordBot;
import de.gost0r.pickupbot.ftwgl.FtwglAPI;
import de.gost0r.pickupbot.pickup.Country;
import de.gost0r.pickupbot.pickup.PickupBot;
import io.sentry.Sentry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

@Slf4j
@Configuration
public class SetupConfiguration {

    private final String stage;
    private final String discordToken;
    private final String discordApplicationId;
    private final String ftwUrl;
    private final String ftwKey;
    private final String sentryDsn;

    public SetupConfiguration(@Value("${app.stage}") String stage,
                              @Value("${app.discord.token}") String discordToken,
                              @Value("${app.discord.application-id}") String discordApplicationId,
                              @Value("${app.ftw.url}") String ftwUrl,
                              @Value("${app.ftw.key}") String ftwKey,
                              @Value("${app.sentry.dsn}") String sentryDsn) {
        this.stage = stage;
        this.discordToken = discordToken;
        this.discordApplicationId = discordApplicationId;
        this.ftwUrl = ftwUrl;
        this.ftwKey = ftwKey;
        this.sentryDsn = sentryDsn;
    }

    @PostConstruct
    public void init() {
        Locale.setDefault(Locale.ENGLISH);

        DiscordBot.setToken(discordToken);
        DiscordBot.setApplicationId(discordApplicationId);

        FtwglAPI.setupCredentials(ftwUrl, ftwKey);

        Country.initCountryCodes();

        PickupBot bot = new PickupBot();
        bot.init(stage);

        Sentry.init(sentryDsn + "?environment=" + stage);

        log.info("Bot started.");
        Sentry.captureMessage("Bot started");
    }
}
