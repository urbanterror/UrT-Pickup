package de.gost0r.pickupbot.pickup.server;

import de.gost0r.pickupbot.pickup.Country;
import de.gost0r.pickupbot.pickup.Match;
import de.gost0r.pickupbot.pickup.Player;
import de.gost0r.pickupbot.pickup.Region;
import io.sentry.Sentry;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.*;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class Server {

    public int id;

    public String IP;
    public int port;
    public String rconpassword;
    public String password;
    public boolean active;
    public Region region;
    public String country;
    public String city;
    public Map<Player, Integer> playerPing;

    private boolean taken = false;

    private DatagramSocket socket;

    private ServerMonitor monitor;
    private Thread monitorThread;

    public int matchid;

    public Server(int id, String ip, int port, String rconpassword, String password, boolean active, Region region) {
        this.id = id;
        this.IP = ip;
        this.port = port;
        this.rconpassword = rconpassword;
        this.password = password;
        this.active = active;
        this.region = region;

        connect();
        monitor = null;
        playerPing = new HashMap<Player, Integer>();
    }

    public void connect() {
        try {
            this.socket = new DatagramSocket();
            this.socket.setSoTimeout(1000);
        } catch (SocketException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
    }


    public synchronized String sendRcon(String rconString) {
        try {
            if (this.socket.isClosed()) {
                log.error("SOCKET IS CLOSED");
                connect();
            }
            String rcon = "xxxxrcon " + rconpassword + " " + rconString;

            byte[] recvBuffer = new byte[2048];
            byte[] sendBuffer = rcon.getBytes();

            sendBuffer[0] = (byte) 0xff;
            sendBuffer[1] = (byte) 0xff;
            sendBuffer[2] = (byte) 0xff;
            sendBuffer[3] = (byte) 0xff;

            log.trace(rcon);

            DatagramPacket sendPacket = new DatagramPacket(sendBuffer, sendBuffer.length, getInetIP(), port);
            DatagramPacket recvPacket = new DatagramPacket(recvBuffer, recvBuffer.length);
            this.socket.send(sendPacket);

            String string = "";
            while (true) {
                try {
                    this.socket.receive(recvPacket);
                    String newString = new String(recvPacket.getData());

                    newString = newString.substring(4); // remove the goddamn first 4 chars

                    string += newString;

                    recvBuffer = new byte[2048]; // empty buffer
                    recvPacket = new DatagramPacket(recvBuffer, recvBuffer.length);
                } catch (SocketTimeoutException e) {
                    break;
                }
            }

            string = string.replace("" + (char) 0, "");

            // Thread.sleep(100);
            return string;
        } catch (IOException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
        return null;
    }


    public synchronized String pushRcon(String rconString) {
        try {
            if (this.socket.isClosed()) {
                log.error("SOCKET IS CLOSED");
                connect();
            }
            String rcon = "xxxxrcon " + rconpassword + " " + rconString;

            byte[] sendBuffer = rcon.getBytes();

            sendBuffer[0] = (byte) 0xff;
            sendBuffer[1] = (byte) 0xff;
            sendBuffer[2] = (byte) 0xff;
            sendBuffer[3] = (byte) 0xff;

            log.trace(rcon);

            DatagramPacket sendPacket = new DatagramPacket(sendBuffer, sendBuffer.length, getInetIP(), port);
            this.socket.send(sendPacket);

        } catch (IOException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
        return null;
    }


    public void startMonitoring(Match match) {
        if (this.monitor == null) {
            this.monitor = new ServerMonitor(this, match);
            monitorThread = new Thread(this.monitor);
            monitorThread.start();
        }
    }

    public void stopMonitoring() {
        if (monitor != null) {
            this.monitor.stop();

            if (monitorThread != null) {
                try {
                    monitorThread.join(5000);
                } catch (InterruptedException e) {
                    log.warn("Interrupted while waiting for monitor thread to stop", e);
                }
            }

            this.monitor = null;
            this.monitorThread = null;
        }
    }

    public void take() {
        taken = true;
    }

    public void free() {
        taken = false;
        stopMonitoring();
    }

    public ServerMonitor getServerMonitor() {
        return monitor;
    }

    public InetAddress getInetIP() {
        try {
            return InetAddress.getByName(IP);
        } catch (UnknownHostException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
        return null;
    }

    public boolean isTaken() {
        return taken;
    }

    @Override
    public String toString() {
        String isActive = this.active ? "" : "(inactive)";
        String isTaken = this.isTaken() ? "used for Match#" + matchid : "";
        return "#" + id + " " + IP + ":" + port + " " + region + " " + isReachable() + " " + isActive + isTaken;
    }

    public boolean isOnline() {
        try {
            InetAddress.getByName(IP).isReachable(1000);
        } catch (UnknownHostException e) {
            return false;
        } catch (IOException e) {
            return false;
        }

        String rconStatusAck = sendRcon("status"); // TODO: Change to rcon players
        return rconStatusAck.contains("score ping name");
    }

    public String isReachable() {
        String status = ":red_circle: (Host Timeout)";

        try {
            InetAddress.getByName(IP).isReachable(1000);
        } catch (UnknownHostException e) {
            return status;
        } catch (IOException e) {
            return status;
        }

        String rconStatusAck = sendRcon("status"); // TODO: Change to rcon players

        if (rconStatusAck.contains("score ping name")) {
            // rcon is correct and server is up
            status = ":green_circle:";
        } else if (rconStatusAck.contains("Bad rconpassword")) {
            // server is up but rcon is wrong
            status = ":orange_circle: (bad rcon)";
        } else if (rconStatusAck.contains("No rconpassword set on the server.")) {
            // server is up but rcon not defined in server CVARs
            status = ":orange_circle: (no rcon set on server)";
        } else {
            // server is down
            status = ":red_circle: (server down)";
        }

        return status;
    }

    public String getAddress() {
        return IP + ":" + port;
    }

    public String getRegionFlag(boolean dynServer, boolean forceNoDynamic) {
        if (region == null) {
            return "";
        } else if (dynServer && !forceNoDynamic) {
            return Country.getCountryFlag(country) + " " + city + " - ";
        } else if (region == Region.NAE || region == Region.NAW) {
            return ":flag_us:";
        } else if (region == Region.OC) {
            return ":flag_au:";
        } else if (region == Region.SA) {
            return ":flag_br:";
        } else if (region == Region.EU) {
            return ":flag_eu:";
        } else {
            return region.name();
        }
    }

}