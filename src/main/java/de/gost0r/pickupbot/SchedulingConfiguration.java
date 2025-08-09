package de.gost0r.pickupbot;

import de.gost0r.pickupbot.pickup.PickupBot;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class SchedulingConfiguration {

    private final PickupBot bot;

    public SchedulingConfiguration(PickupBot bot) {
        this.bot = bot;
    }

    @Scheduled(fixedRate = 30000)
    public void scheduledTick() {
        bot.tick();
    }
}
