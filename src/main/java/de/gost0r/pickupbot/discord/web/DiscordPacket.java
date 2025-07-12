package de.gost0r.pickupbot.discord.web;

import io.sentry.Sentry;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONException;
import org.json.JSONObject;

@Slf4j
public class DiscordPacket {

    public DiscordGatewayOpcode op;    // opcode
    public JSONObject d;            // event data
    public int s;                    // sequence number
    public String t;                // event name

    public DiscordPacket(DiscordGatewayOpcode op, JSONObject d, int s, String t) {
        this.op = op;
        this.d = d;
        this.s = s;
        this.t = t;
    }

    @Override
    public String toString() {
        return op.name() + ": d=" + d + " s=" + s + " t=" + t;

    }

    public String toSend() {
        try {
            JSONObject msg = new JSONObject();
            msg.put("op", op.ordinal());
            msg.put("d", d);
//			msg.put("s", s < 0 ? "__null__" : s);
//			msg.put("t", t);
            return msg.toString();
        } catch (JSONException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
        return null;
    }
}
