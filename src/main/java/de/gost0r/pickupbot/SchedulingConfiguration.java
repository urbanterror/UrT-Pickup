package de.gost0r.pickupbot;

import de.gost0r.pickupbot.discord.DiscordBot;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class SchedulingConfiguration {

    private final DiscordBot bot;

    public SchedulingConfiguration(DiscordBot bot) {
        this.bot = bot;
    }

    @Scheduled(fixedRate = 30000)
    public void scheduledTick() {
        bot.tick();
    }
}
