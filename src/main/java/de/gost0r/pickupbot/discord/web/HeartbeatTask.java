package de.gost0r.pickupbot.discord.web;

import io.sentry.Sentry;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.TimerTask;

@Slf4j
public class HeartbeatTask extends TimerTask {

    private DiscordGateway dg;

    public HeartbeatTask(DiscordGateway dg) {
        this.dg = dg;
    }

    @Override
    public void run() {
        try {
            sendHeatbeat();
        } catch (Exception e) {
            log.error("HeartbeatTask failed: ", e);
            Sentry.captureException(e);
        }
    }

    private void sendHeatbeat() {
        try {
            JSONObject msg = new JSONObject();
            msg.put("op", DiscordGatewayOpcode.Heatbeat.ordinal());
            msg.put("d", dg.getLatestSeq() < 0 ? "__null__" : dg.getLatestSeq()); // last sequence number
            dg.sendMessage(msg.toString());
        } catch (JSONException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        } catch (Exception e) {
            log.warn("Unexpected exception in heartbeat: ", e);
            Sentry.captureException(e);
        }
    }

}
