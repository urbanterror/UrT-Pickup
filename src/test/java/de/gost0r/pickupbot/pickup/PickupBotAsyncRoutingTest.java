package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordChannel;
import de.gost0r.pickupbot.discord.DiscordMessage;
import de.gost0r.pickupbot.discord.DiscordService;
import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.permission.PickupRoleCache;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PickupBotAsyncRoutingTest {

    @Test
    void queueMutationsAndReadOnlyCommandsUseDifferentExecutors() throws Exception {
        RecordingExecutor commandExecutor = new RecordingExecutor();
        RecordingExecutor queueExecutor = new RecordingExecutor();
        DiscordService discordService = mock(DiscordService.class);
        DiscordUser self = user("bot");
        DiscordUser sender = user("player");
        PickupBot bot = new PickupBot(
                "test",
                mock(FtwglApi.class),
                discordService,
                mock(PermissionService.class),
                mock(PickupRoleCache.class),
                commandExecutor,
                queueExecutor,
                Runnable::run,
                Runnable::run
        );
        setField(bot, "self", self);
        setField(bot, "logic", mock(PickupLogic.class));

        bot.recvMessage(message("!register auth", sender));
        bot.recvMessage(message("!unregister auth", sender));
        bot.recvMessage(message("!add TS", sender));
        bot.recvMessage(message("!status", sender));

        assertEquals(3, queueExecutor.tasks.size());
        assertEquals(1, commandExecutor.tasks.size());
    }

    @Test
    void scheduledChecksRunOnTheQueueExecutor() throws Exception {
        RecordingExecutor queueExecutor = new RecordingExecutor();
        PickupBot bot = new PickupBot("test", mock(FtwglApi.class), mock(DiscordService.class),
                mock(PermissionService.class), mock(PickupRoleCache.class), Runnable::run,
                queueExecutor, Runnable::run, Runnable::run);
        PickupLogic logic = mock(PickupLogic.class);
        setField(bot, "logic", logic);

        bot.tick();

        assertEquals(1, queueExecutor.tasks.size());
        queueExecutor.tasks.get(0).run();
        verify(logic).afkCheck();
        verify(logic).checkPrivateGroups();
    }

    private static DiscordMessage message(String content, DiscordUser sender) {
        DiscordChannel channel = mock(DiscordChannel.class);
        when(channel.getName()).thenReturn("pickup");
        DiscordMessage message = mock(DiscordMessage.class);
        when(message.getContent()).thenReturn(content);
        when(message.getUser()).thenReturn(sender);
        when(message.getChannel()).thenReturn(channel);
        return message;
    }

    private static DiscordUser user(String id) {
        DiscordUser user = mock(DiscordUser.class);
        when(user.getId()).thenReturn(id);
        when(user.getUsername()).thenReturn(id);
        return user;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class RecordingExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }
    }
}
