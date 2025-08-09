package de.gost0r.pickupbot.ftwgl;

//
//@SpringBootTest
//@ActiveProfiles("integration")
//@Disabled("Only use this for manual testing as it's testing the real ftwgl endpoints to ensure integration.")
//class FtwglApiTestIT {
//
//    @Autowired
//    private FtwglApi ftwglAPI;
//
//    @Mock
//    private Player player;
//
//    DiscordUser user = new DiscordUser("140905651432980480", "hydrum", "0000", "Avatar");
//    DiscordUser user2 = new DiscordUser("117620974806892549", "biddle", "0000", "Avatar");
//
//    @Test
//    void contextLoads() {
//    }
//
//    @Test
//    void hasLauncherOn() {
//        when(player.getDiscordUser()).thenReturn(user);
//
//        assertTrue(ftwglAPI.hasLauncherOn(player));
//    }
//
//    @Test
//    void checkIfPingStored() {
//        when(player.getDiscordUser()).thenReturn(user);
//
//        assertTrue(ftwglAPI.checkIfPingStored(player));
//    }
//
//    @Test
//    void requestPingUrl() {
//        when(player.getDiscordUser()).thenReturn(user);
//        when(player.getUrtauth()).thenReturn("gost0r");
//
//        ftwglAPI.requestPingUrl(player);
//    }
//
//    @Test
//    void launchAC() {
//        when(player.getDiscordUser()).thenReturn(user);
//        when(player.getUrtauth()).thenReturn("gost0r");
//
//        ftwglAPI.launchAC(player, "216.128.138.242:27960", "xxx");
//    }
//
//    @Test
//    void spawnDynamicServer() {
//        when(player.getDiscordUser()).thenReturn(user);
//
//        ftwglAPI.spawnDynamicServer(List.of(player));
//    }
//
//    @Test
//    void queryAndUpdateServerIp() {
//        Server server = new Server(8054, null, 27960, "rcon", "password", true, Region.EU);
//
//        ftwglAPI.queryAndUpdateServerIp(server);
//    }
//
//    @Test
//    void getSinglePlayerRating() {
//        when(player.getDiscordUser()).thenReturn(user);
//
//        ftwglAPI.getPlayerRatings(player);
//    }
//
//    @Test
//    void getMultiplePlayerRatings() {
//        when(player.getDiscordUser()).thenReturn(user);
//        Player player2 = mock(Player.class);
//        when(player2.getDiscordUser()).thenReturn(user2);
//
//        ftwglAPI.getPlayerRatings(List.of(player, player2));
//    }
//}