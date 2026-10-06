package dev.volansvo.svo;

import dev.volansvo.svo.commands.*;
import dev.volansvo.svo.listeners.*;
import dev.volansvo.svo.managers.*;
import dev.volansvo.svo.maps.MapManager;
import org.bukkit.plugin.java.JavaPlugin;

public class VolanSVO extends JavaPlugin {

    private static VolanSVO instance;

    private GameManager gameManager;
    private WorldManager worldManager;
    private LootManager lootManager;
    private AirdropManager airdropManager;
    private StatsManager statsManager;
    private WardenManager wardenManager;
    private BossbarManager bossbarManager;
    private MapManager mapManager;
    private ChaosManager chaosManager;
    private dev.volansvo.svo.bots.BotManager botManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        worldManager   = new WorldManager(this);
        mapManager     = new MapManager(this);
        statsManager   = new StatsManager(this);
        bossbarManager = new BossbarManager(this);
        lootManager    = new LootManager(this);
        airdropManager = new AirdropManager(this);
        wardenManager  = new WardenManager(this);
        chaosManager   = new ChaosManager(this);
        gameManager    = new GameManager(this);
        botManager     = new dev.volansvo.svo.bots.BotManager(this);

        getCommand("svolobby").setExecutor(new StartSvoCommand(this));
        PlaySvoCommand playCmd = new PlaySvoCommand(this);
        getCommand("svoplay").setExecutor(playCmd);
        getCommand("svoplay").setTabCompleter(playCmd);
        getCommand("svostats").setExecutor(new SvoStatsCommand(this));
        LaunchSvoCommand launchCmd = new LaunchSvoCommand(this);
        getCommand("svolaunch").setExecutor(launchCmd);
        getCommand("svolaunch").setTabCompleter(launchCmd);
        getCommand("svovote").setExecutor(new SvoVoteCommand(this));
        getCommand("svowatch").setExecutor(new SvoWatchCommand(this));
        SvoTeamCommand teamCmd = new SvoTeamCommand(this);
        getCommand("svoteam").setExecutor(teamCmd);
        getCommand("svoteam").setTabCompleter(teamCmd);

        RazboiCommand razboiCmd = new RazboiCommand(this);
        getCommand("asvorazboi").setExecutor(razboiCmd);
        getCommand("asvorazboi").setTabCompleter(razboiCmd);
        getCommand("asvostop").setExecutor(new SvoStopCommand(this));
        YanykCommand yanykCmd = new YanykCommand(this);
        getCommand("asvoyanyk").setExecutor(yanykCmd);
        getCommand("asvoyanyk").setTabCompleter(yanykCmd);
        TpToLobbyStandCommand lobbyStandCmd = new TpToLobbyStandCommand(this);
        getCommand("asvotptolobbyst").setExecutor(lobbyStandCmd);
        getCommand("asvotptolobbyst").setTabCompleter(lobbyStandCmd);

        AsvoMapCommand mapCmd = new AsvoMapCommand(this);
        getCommand("asvonew").setExecutor(mapCmd);
        getCommand("asvonew").setTabCompleter(mapCmd);
        getCommand("asvomap").setExecutor(mapCmd);
        getCommand("asvomap").setTabCompleter(mapCmd);

        getCommand("asvobot").setExecutor(new dev.volansvo.svo.bots.BotCommand(this));

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new DeathListener(this), this);
        getServer().getPluginManager().registerEvents(new EntityListener(this), this);
        getServer().getPluginManager().registerEvents(new CommandListener(this), this);

        gameManager.ensureScoreboards();
        // Отключаем паузу сервера при отсутствии игроков (иначе таймер замирает).
        new ServerConfigPatcher(this).disablePauseWhenEmpty();
        // Очистка после краша/рестарта: сброс состояния, тегов, IsGameSvo, активной карты.
        gameManager.startupCleanup();
        gameManager.startGlobalWatcher();
        // Дрон-бомбила: стойка «ДРОН» за игроком, конец полёта при влёте в блок.
        dev.volansvo.svo.managers.BombDroneGuard droneGuard = new dev.volansvo.svo.managers.BombDroneGuard(this);
        droneGuard.runTaskTimer(this, 1L, 1L);
        getServer().getPluginManager().registerEvents(droneGuard, this);
        getLogger().info("VolanSVO enabled.");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) gameManager.forceStop();
        if (botManager != null) botManager.shutdown();
        if (bossbarManager != null) bossbarManager.cleanup();
        if (statsManager != null) statsManager.saveAll();
        getLogger().info("VolanSVO disabled.");
    }

    public static VolanSVO getInstance() { return instance; }

    public GameManager getGameManager()       { return gameManager; }
    public WorldManager getWorldManager()     { return worldManager; }
    public LootManager getLootManager()       { return lootManager; }
    public AirdropManager getAirdropManager() { return airdropManager; }
    public StatsManager getStatsManager()     { return statsManager; }
    public WardenManager getWardenManager()   { return wardenManager; }
    public BossbarManager getBossbarManager() { return bossbarManager; }
    public MapManager getMapManager()         { return mapManager; }
    public ChaosManager getChaosManager()     { return chaosManager; }
    public dev.volansvo.svo.bots.BotManager getBotManager() { return botManager; }
}
