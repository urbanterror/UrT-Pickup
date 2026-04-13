package de.gost0r.pickupbot.pickup.server;

import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.pickup.Country;
import de.gost0r.pickupbot.pickup.Match;
import de.gost0r.pickupbot.pickup.Player;
import de.gost0r.pickupbot.pickup.Region;
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

            StringBuilder response = new StringBuilder();
            boolean firstPacket = true;
            while (true) {
                try {
                    this.socket.receive(recvPacket);
                    String newString = new String(recvPacket.getData(), 0, recvPacket.getLength());
                    newString = newString.substring(4); // remove 0xFFFFFFFF header
                    response.append(newString);

                    // Shorter timeout after first packet - Q3 sends multi-packet responses quickly
                    if (firstPacket) {
                        this.socket.setSoTimeout(350);
                        firstPacket = false;
                    }

                    recvBuffer = new byte[2048];
                    recvPacket = new DatagramPacket(recvBuffer, recvBuffer.length);
                } catch (SocketTimeoutException e) {
                    break;
                }
            }

            return response.toString();
        } catch (IOException e) {
            log.warn("Exception: ", e);
            return null;
        } finally {
            try { this.socket.setSoTimeout(1000); } catch (SocketException ignored) {}
        }
    }

    /**
     * Send multiple RCON commands efficiently using the vstr batching technique.
     * 
     * Q3 RCON doesn't support semicolon-separated commands directly, but the vstr
     * command executes a cvar's value as commands, which DOES process semicolons.
     * 
     * Approach:
     * 1. Define a temporary cvar with commands joined by semicolons
     * 2. Execute it with vstr
     * 3. If commands exceed the ~990 char limit, split into multiple batches
     * 
     * This reduces N commands from N RCON calls to just 2 RCON calls per batch,
     * providing ~10-15x speedup while maintaining reliability.
     * 
     * @param commands List of commands to execute
     * @return Response from the vstr execution(s)
     */
    public synchronized String sendRconBatch(java.util.List<String> commands) {
        if (commands == null || commands.isEmpty()) {
            return "";
        }
        
        // Q3 cvar value limit is ~990 chars, use 950 for safety margin
        final int MAX_BATCH_LENGTH = 950;
        
        StringBuilder responses = new StringBuilder();
        StringBuilder currentBatch = new StringBuilder();
        int batchNum = 0;
        
        for (int i = 0; i < commands.size(); i++) {
            String cmd = commands.get(i);
            String separator = currentBatch.length() > 0 ? "; " : "";
            
            // Check if adding this command would exceed the limit
            if (currentBatch.length() + separator.length() + cmd.length() > MAX_BATCH_LENGTH) {
                // Execute current batch
                if (currentBatch.length() > 0) {
                    String response = executeVstrBatch(currentBatch.toString(), batchNum++);
                    if (response != null) {
                        responses.append(response);
                    }
                    currentBatch = new StringBuilder();
                    separator = "";
                }
            }
            
            currentBatch.append(separator).append(cmd);
        }
        
        // Execute final batch
        if (currentBatch.length() > 0) {
            String response = executeVstrBatch(currentBatch.toString(), batchNum);
            if (response != null) {
                responses.append(response);
            }
        }
        
        return responses.toString();
    }
    
    private String executeVstrBatch(String batchedCommands, int batchNum) {
        String varName = "_pkbatch" + batchNum;
        sendRcon("set " + varName + " \"" + batchedCommands + "\"");
        return sendRcon("vstr " + varName);
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
        }
        return null;
    }


    public void startMonitoring(Match match, PermissionService permissionService) {
        if (this.monitor == null) {
            this.monitor = new ServerMonitor(this, match, permissionService);
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