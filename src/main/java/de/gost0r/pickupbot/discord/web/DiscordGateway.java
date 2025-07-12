package de.gost0r.pickupbot.discord.web;

import de.gost0r.pickupbot.discord.DiscordBot;
import de.gost0r.pickupbot.discord.web.WsClientEndPoint.MessageHandler;
import io.sentry.Sentry;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Timer;

@Slf4j
public class DiscordGateway implements MessageHandler {

    private Timer heartbeatTimer = new Timer();
    private HeartbeatTask heartbeatTask;

    private WsClientEndPoint clientEP;
    private DiscordPacket latestPacket;

    private DiscordBot bot;

    public DiscordGateway(DiscordBot bot, WsClientEndPoint clientEP) {
        this.bot = bot;
        this.clientEP = clientEP;
    }

    public void sendMessage(String msg) {
        if (clientEP.isConnected()) {

            msg = msg.replaceAll("\"__null__\"", "null");

            log.trace("OUT DiscordPacket: {}", msg);
            clientEP.sendMessage(msg);
        } else {
            log.trace("Cancel heartbeatTimer");
            heartbeatTimer.cancel();
        }
    }

    @Override
    public void handleMessage(String message) {
        try {
            JSONObject jsonObj = new JSONObject(message);

            // Parse to packet
            int op = jsonObj.getInt("op");

            JSONObject d;

            if (jsonObj.isNull("d")) {
                d = null;
            } else {
                d = jsonObj.getJSONObject("d");
            }

            int s = jsonObj.isNull("s") ? -1 : jsonObj.getInt("s");
            String t = jsonObj.isNull("t") ? null : jsonObj.getString("t");
            DiscordPacket incPak = new DiscordPacket(DiscordGatewayOpcode.values()[op], d, s, t);

            // DON'T LOG THIS EVENT [spamming all user info of the server]
            if (t == null || !t.equals(DiscordGatewayEvent.GUILD_CREATE.name())) {
                log.trace("INC DiscordPacket: {}", incPak);
            }
            handlePacket(incPak);

        } catch (JSONException e) {
            log.warn("Exception for '{}': ", message, e);
            Sentry.captureException(e);
        }
    }

    private void handlePacket(DiscordPacket p) {
        latestPacket = p;
        switch (p.op) {
            case Hello:
                handlePacketHello(p);
                break;
            case Dispatch:
                bot.handleEvent(DiscordGatewayEvent.valueOf(p.t), p.d);
                break;
            default:
                break;
        }
    }

    private void handlePacketHello(DiscordPacket p) {
        // setting heartbeat timer
        try {
            if (heartbeatTask != null) {
                heartbeatTask.cancel();
            }
            heartbeatTask = new HeartbeatTask(this);
            long interval = p.d.getLong("heartbeat_interval");

            // Cancel existing timer before creating a new one
            if (heartbeatTimer != null) {
                heartbeatTimer.cancel();
                heartbeatTimer = new Timer();
            }

            heartbeatTimer.scheduleAtFixedRate(heartbeatTask, interval / 2, interval);
        } catch (JSONException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        } catch (Exception e) {
            log.error("Unexpected exception in handlePacketHello: ", e);
            Sentry.captureException(e);
        }

        // sending identify
        try {
            JSONObject msg = new JSONObject();
            msg.put("token", DiscordBot.getToken());

            JSONObject prop = new JSONObject();
            prop.put("$os", "linux");
            prop.put("$browser", "urtpickupbot");
            prop.put("$device", "urtpickupbot");
            msg.put("properties", prop);

            msg.put("compress", false);
            msg.put("large_threshold", 50);
            JSONArray ar = new JSONArray("[0, 1]");
            msg.put("shard", ar);

            JSONObject presence = new JSONObject();
            presence.put("since", "__null__");
            presence.put("game", "__null__");
            presence.put("status", "online");
            presence.put("afk", false);
            msg.put("presence", presence);

            msg.put("intents", 32365);

            DiscordPacket outP = new DiscordPacket(DiscordGatewayOpcode.Identify, msg, -1, "__null__");

            sendMessage(outP.toSend());

        } catch (JSONException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        } catch (Exception e) {
            log.error("Unexpected exception in identify: ", e);
            Sentry.captureException(e);
        }

    }

    public void setClientEP(WsClientEndPoint clientEP) {
        this.clientEP = clientEP;
    }

    public void reconnect() {
        // do nothing for now.
    }

    public int getLatestSeq() {
        if (latestPacket == null) {
            return -1;
        }
        return latestPacket.s;
    }
}
