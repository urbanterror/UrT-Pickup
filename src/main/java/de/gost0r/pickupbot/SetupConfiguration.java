package de.gost0r.pickupbot;

import de.gost0r.pickupbot.discord.DiscordBot;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import de.gost0r.pickupbot.pickup.Country;
import de.gost0r.pickupbot.pickup.PickupBot;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Locale;

@Slf4j
@Configuration
@EnableScheduling
public class SetupConfiguration {

    private final String stage;
    private final String discordToken;
    private final String discordApplicationId;

    private final FtwglApi ftwglApi;

    public SetupConfiguration(@Value("${app.stage}") String stage,
                              @Value("${app.discord.token}") String discordToken,
                              @Value("${app.discord.application-id}") String discordApplicationId,
                              FtwglApi ftwglApi) {
        this.stage = stage;
        this.discordToken = discordToken;
        this.discordApplicationId = discordApplicationId;
        this.ftwglApi = ftwglApi;
    }

    @PostConstruct
    public void init() {
        Locale.setDefault(Locale.ENGLISH);

        DiscordBot.setToken(discordToken);
        DiscordBot.setApplicationId(discordApplicationId);

        Country.initCountryCodes();

        log.info("Bot started.");
    }

    @Bean
    public DiscordBot discordBot() {
        PickupBot bot = new PickupBot();
        bot.init(stage, ftwglApi);

        return bot;
    }
}
