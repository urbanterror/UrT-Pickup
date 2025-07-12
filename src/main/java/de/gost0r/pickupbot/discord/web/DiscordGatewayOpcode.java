package de.gost0r.pickupbot.discord.web;

public enum DiscordGatewayOpcode {
    Dispatch,                // R: dispatch event
    Heatbeat,                // S/R: ping check
    Identify,                // S: handshake
    Status_Update,            // S: client status update
    Voice_State_Update,        // S: join/move/leave voice channel
    Voice_Server_Ping,        // S: voice ping check
    Resume,                    // S: resume closed connex
    Reconnect,                // R: tell client to reconnect
    Request_Guild_Members,    // S: request guild members
    Invalid_Session,        // R: notfiy client of invalid session id
    Hello,                    // R: sent after connecting
    Heartbeat_ACK            // R: following heartbeat
}
