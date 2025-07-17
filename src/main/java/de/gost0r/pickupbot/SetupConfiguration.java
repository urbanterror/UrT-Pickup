package de.gost0r.pickupbot;

import de.gost0r.pickupbot.discord.DiscordBot;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
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
    private final String sentryDsn;

    private final FtwglApi ftwglApi;

    public SetupConfiguration(@Value("${app.stage}") String stage,
                              @Value("${app.discord.token}") String discordToken,
                              @Value("${app.discord.application-id}") String discordApplicationId,
                              @Value("${app.sentry.dsn}") String sentryDsn,
                              FtwglApi ftwglApi) {
        this.stage = stage;
        this.discordToken = discordToken;
        this.discordApplicationId = discordApplicationId;
        this.sentryDsn = sentryDsn;
        this.ftwglApi = ftwglApi;
    }

    @PostConstruct
    public void init() {
        Locale.setDefault(Locale.ENGLISH);

        DiscordBot.setToken(discordToken);
        DiscordBot.setApplicationId(discordApplicationId);

        Country.initCountryCodes();

        PickupBot bot = new PickupBot();
        bot.init(stage, ftwglApi);

        Sentry.init(sentryDsn + "?environment=" + stage);

        log.info("Bot started.");
        Sentry.captureMessage("Bot started");
    }
}
