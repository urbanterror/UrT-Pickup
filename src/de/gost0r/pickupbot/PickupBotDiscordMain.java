package de.gost0r.pickupbot;

import java.io.IOException;
import java.util.Locale;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import de.gost0r.pickupbot.ftwgl.FtwglAPI;
import io.github.cdimascio.dotenv.Dotenv;
import org.json.JSONException;

import de.gost0r.pickupbot.discord.DiscordBot;
import de.gost0r.pickupbot.pickup.Country;
import de.gost0r.pickupbot.pickup.PickupBot;

import io.sentry.Sentry;

public class PickupBotDiscordMain {

    private final static Logger LOGGER = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
	private static String env;
	private static Dotenv dotenv;

	public static void main(String[] args) {
		Locale.setDefault(new Locale("en", "EN"));
		try {
			dotenv = Dotenv.configure().filename(".prod.env").load();
			env = "prod";
			if (args.length > 0 && args[0].equals("dev")){
				dotenv = Dotenv.configure().filename(".dev.env").load();
				env = "dev";
			}

			setupLogger(dotenv.get("LOG_LEVEL", "WARNING"));
			
			Country.initCountryCodes();

			DiscordBot.setToken(dotenv.get("DISCORD_TOKEN"));
			DiscordBot.setApplicationId(dotenv.get("DISCORD_APPLICATION_ID"));
			FtwglAPI.setupCredentials(dotenv.get("FTW_URL"), dotenv.get("FTW_KEY"));
			PickupBot bot = new PickupBot();
			bot.init(env);


			while (true) {
				Thread.sleep(5000);
			}

		} catch (IOException | JSONException | SecurityException | InterruptedException e) {
			LOGGER.log(Level.WARNING, "Exception: ", e);
			Sentry.capture(e);
		}
	}
	
	public static void setupLogger(String levelName) throws SecurityException, IOException {
		Level logLevel = Level.parse(levelName);

		System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s - %2$s(): %5$s%6$s%n");
		Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
		logger.setLevel(logLevel);

		logger.setUseParentHandlers(false);
		ConsoleHandler handler = new ConsoleHandler();
		handler.setLevel(logLevel);
		logger.addHandler(handler);

		FileHandler logfile = new FileHandler("bot.log");
		logfile.setFormatter(new SimpleFormatter());
		logfile.setLevel(logLevel);
		logger.addHandler(logfile);

		Sentry.init(dotenv.get("SENTRY_DSN") + "?environment=" + env);

		LOGGER.severe("Bot started.");
		Sentry.capture("Bot started");
	}
}
