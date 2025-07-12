package de.gost0r.pickupbot.discord.web;

import de.gost0r.pickupbot.discord.DiscordBot;
import io.sentry.Sentry;
import jakarta.websocket.*;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;

@ClientEndpoint
@Slf4j
public class WsClientEndPoint {

    Session userSession = null;
    private MessageHandler messageHandler;

    DiscordBot bot;

    URI endpointURI = null;

    public WsClientEndPoint(DiscordBot bot, URI endpointURI) {
        this.bot = bot;
        this.endpointURI = endpointURI;
        connect();
    }

    private void connect() {
        try {
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.connectToServer(this, endpointURI);
        } catch (DeploymentException | IOException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
    }

    public void reconnect() {
        try {
            if (userSession != null && userSession.isOpen()) {
                userSession.close();
            }
            connect();
        } catch (IOException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
    }

    @OnOpen
    public void onOpen(Session userSession) {
        log.warn("WebSocket opened. ");
        Sentry.captureMessage("WebSocket opened. ");
        this.userSession = userSession;
    }

    @OnClose
    public void onClose(Session userSession, CloseReason reason) {
        log.warn("WebSocket closed. " + reason.toString());
        Sentry.captureMessage("WebSocket closed. " + reason.toString());
        this.userSession = null;
        bot.reconnect();
    }

    @OnMessage
    public void onMessage(String message) {
        if (this.messageHandler != null) {
            this.messageHandler.handleMessage(message);
        }
    }

    public void addMessageHandler(MessageHandler msgHandler) {
        this.messageHandler = msgHandler;
    }

    public void sendMessage(String message) {
        if (this.userSession != null && this.userSession.isOpen()) {
            this.userSession.getAsyncRemote().sendText(message);
        } else {
            log.warn("Unable to send msg, session is closed.");
            Sentry.captureMessage("Unable to send msg, session is closed.");
        }
    }

    public boolean isConnected() {
        return this.userSession != null && this.userSession.isOpen();
    }

    public static interface MessageHandler {
        public void handleMessage(String message);
    }

}
