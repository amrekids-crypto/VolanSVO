package dev.volansvo.svo.managers;

import dev.volansvo.svo.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class GameManager {

    private final VolanSVO plugin;
    private GameState state = GameState.IDLE;

    private int startingLives = 1;
    private int timerTicks = 18000;
    private boolean fastZone = false;
    private boolean zirEnabled = true;
    private boolean glowEnabled = true;
    private boolean airdropsEnabled = true;
    private boolean highlightTeammates = false; // зелёные партиклы над тиммейтами (только в командных режимах)
    private boolean chaosEnabled = false; // модификатор "Хаос" (метеоры/эффекты/фантомы, см. ChaosManager)
    private int teamSize = 1; // 1=одиночный, 2=дуо, 3=трио
    /** Идёт ли эта игра в статистику. Если людей в матче меньше двух (один игрок
     *  против ботов) - игры/победы/убийства не засчитываются и винрейт не меняется. */
    private boolean statsCounted = true;
    /** currentTick, когда в игре не осталось живых людей (-1 - люди ещё живы). */
    private int noHumansSinceTick = -1;

    /** Префикс scoreboard-команд для боевых тимов (Дуо/Трио) с выключенным friendly fire. */
    private static final String SQUAD_TEAM_PREFIX = "svosquad";
    /** Сколько боевых команд было создано в этой игре (для очистки). */
    private int squadTeamCount = 0;
    /** ХП тиммейтов персональным боссбаром (под боссбаром Жириновского/Нетаньяху) - в любом
     *  командном режиме (не только с подсветкой). Ключ - UUID зрителя (у каждого свой бар без
     *  собственного ХП в списке - см. updateTeamHpBars). */
    private final Map<UUID, BossBar> teamHpBars = new HashMap<UUID, BossBar>();

    // ---- Ручное формирование команд через GUI (Дуо/Трио) ----
    private boolean formationActive = false;
    private int formationTeamSize = 2;
    private UUID formationInitiator = null;
    private VotingSession formationSession = null;
    /** Игрок -> индекс команды (0..19), куда он вступил. */
    private final Map<UUID, Integer> formPlayerTeam = new HashMap<UUID, Integer>();
    /** Индекс команды -> её участники (в порядке вступления). */
    private final Map<Integer, LinkedHashSet<UUID>> formTeams = new LinkedHashMap<Integer, LinkedHashSet<UUID>>();
    private int formNextId = 100; // для авто-команд при финализации (не пересекается с 0..19)
    /** Индекс команды -> игроки, подавшие заявку на вступление. */
    private final Map<Integer, LinkedHashSet<UUID>> joinRequests = new HashMap<Integer, LinkedHashSet<UUID>>();
    private BukkitTask formationTask = null;
    private int formationSecondsLeft = 0;
    /** Кто нажал «Начинаем»: когда нажмут все в очереди, формирование заканчивается сразу. */
    private final Set<UUID> formReady = new HashSet<UUID>();
    private boolean formationFinishing = false;
    /** Слот кнопки «Начинаем» в меню команд. */
    public static final int TEAM_GUI_READY_SLOT = 22;
    /** Заранее заданное распределение по командам (player -> уникальный номер команды). null = рандом. */
    private Map<UUID, Integer> predeterminedTeams = null;
    /** Боты, которых хост поставил в команды при ручном формировании: индекс команды -> сколько. */
    private final Map<Integer, Integer> formBots = new HashMap<Integer, Integer>();
    /** Номера команд для расставленных хостом ботов (по одному на бота), применяются при старте. */
    private final List<Integer> pendingBotTeams = new ArrayList<Integer>();

    // ---- GUI настроек /svoplay (выбор всех аргументов) ----
    public static final String SETUP_GUI_TITLE = ChatColor.DARK_AQUA + "Настройка СВО";
    /** Текущие выборы игрока, открывшего настройку (один сетап за раз). */
    private static class SetupChoice {
        String mapId = null;
        int lives = 1;          // 1, 2 или 3
        int time = 15;          // 15 или 30
        boolean zir = true;
        boolean glow = true;
        boolean fast = false;
        boolean air = true;
        boolean teamGlow = false; // подсветка тиммейтов (только в командных режимах)
        boolean chaos = false;    // модификатор "Хаос"
        String mode = "solo";   // solo|duo|trio|randomduo|randomtrio
        int bots = 0;           // сколько ботов добавить (0..10)
    }
    private final Map<UUID, SetupChoice> setupChoices = new HashMap<UUID, SetupChoice>();

    /** PDC-ключи на стенде /asvorazboi - сколько топорщиков/арбалетчиков он заспавнит. */
    public static final String RAZBOI_VIND_KEY = "razboi_vind_count";
    public static final String RAZBOI_PIL_KEY  = "razboi_pil_count";

    /** Кол-во команд в GUI. */
    public static final int TEAM_COUNT = 20;
    /** Названия команд - украинские бригады/полки. */
    public static final String[] TEAM_NAMES = {
        "3-тя ОШБр", "47-ма ОМБр Магура", "93-тя ОМБр Холодний Яр", "92-га ОШБр",
        "24-та ОМБр", "28-ма ОМБр", "53-тя ОМБр", "57-ма ОМБр",
        "58-ма ОМБр", "59-та ОМБр", "72-га ОМБр", "79-та ОДШБр",
        "80-та ОДШБр", "95-та ОДШБр", "25-та ОПДБр", "36-та ОБрМП",
        "38-ма ОБрМП", "1-ша ОТБр", "14-та ОМБр", "110-та ОМБр"
    };

    public String teamName(int index) {
        return (index >= 0 && index < TEAM_NAMES.length) ? TEAM_NAMES[index] : ("Команда " + index);
    }

    private final Map<UUID, SvoPlayer> players = new LinkedHashMap<UUID, SvoPlayer>();
    private final Set<UUID> queue = new LinkedHashSet<UUID>();

    private int currentTick = 0;
    private int glowTimer = 4800;
    private int airdropTimer = 0;

    private static final int AIRDROP_INTERVAL = 4000;

    // Voting state
    // Maps voteId -> info about pending vote
    private VotingSession activeVote = null;

    private BukkitTask tickTask;
    private UUID winner = null;
    private boolean timeRanOut = false;

    /** Для вотчдога: значение currentTick при прошлой проверке и сколько проверок оно не менялось. */
    private int watchdogLastSeenTick = -1;
    private int watchdogStallCount = 0;


    /** Сколько тиков ПОДРЯД игрок замечен вне игрового мира (checkDimensions). Страховка от
     *  ложного выбывания из-за одиночного "моргания" (напр. сильный взрыв в Хаосе выкинул
     *  игрока knockback-ом, лаг/просадка TPS на тик). */
    private final Map<UUID, Integer> dimensionMismatchTicks = new HashMap<UUID, Integer>();
    private static final int DIMENSION_MISMATCH_THRESHOLD = 60; // ~3 сек подряд не в игровом мире

    /** Игроки, реально поспавшие на кровати В ЭТОЙ игре (сбрасывается при старте/конце). */
    private final Set<UUID> sleptThisRound = new HashSet<UUID>();

    /** До какого System.currentTimeMillis игрок защищён от dimension-check (после потери жизни). */
    private final Map<UUID, Long> respawnGrace = new HashMap<UUID, Long>();
    private static final long RESPAWN_GRACE_MS = 3000L;

    /** Игроки, которым МЫ выдали медленное падение при старте игры/возрождении в воздухе (не
     *  игрок сам выпил зелье) - только для них снимаем эффект по касанию земли в
     *  handleSlowFallingRemoval(), чтобы не срезать честно выпитое зелье на пару минут. */
    private final Set<UUID> pendingDropSlowFall = new HashSet<UUID>();

    /** Время последнего удара молнии для каждого яныка (по UUID). */
    private final Map<UUID, Long> yanikLastStrike = new HashMap<UUID, Long>();
    private static final double YANIK_RADIUS = 30.0;
    private static final long YANIK_COOLDOWN_MS = 10000L;
    private static final double BANDIT_WAKE_RADIUS = 30.0;

    /** Кэш UUID яныков/спящих бандитов/стендов-порталов, найденных периодическим full-scan'ом
     *  (rediscoverWatcherEntities) - раз в WATCHER_DISCOVERY_PERIOD, а не каждый тик. Раньше
     *  watcherBody сканировал getEntitiesByClass/getLivingEntities ПО ВСЕМ МИРАМ СЕРВЕРА
     *  4 раза в секунду не переставая - это топило TPS сильнее чем что-либо связанное с чанками.
     *  Компромисс через сужение списка миров сломал свойство "стенд/бандит работает в ЛЮБОМ
     *  мире" (svolobbyst переставал ловить игроков, если стенд стоял вне текущей активной карты).
     *  Кэш по UUID решает оба: full-scan редкий (дёшево), а между сканами обработка идёт по
     *  уже найденным сущностям (дёшево и работает в любом мире, где они реально стоят). */
    private final Set<UUID> yanikIds = new HashSet<UUID>();
    private final Set<UUID> banditSleepIds = new HashSet<UUID>();
    private final Set<UUID> lobbyStandIds = new HashSet<UUID>();
    private static final int WATCHER_DISCOVERY_PERIOD = 20; // раз в 20 вызовов вотчера (~5 сек)

    public GameManager(VolanSVO plugin) {
        this.plugin = plugin;
    }

    /**
     * Создаёт scoreboard objectives если их нет. Существующие не трогает.
     * Вызывается из VolanSVO.onEnable.
     */
    public void ensureScoreboards() {
        ensureObjective("IsGameSvo");
        ensureObjective("svogames");
        ensureObjective("svowins");
        ensureObjective("svowinrate");
        ensureObjective("svokills");
        // Номер команды игрока для executable items (friendly fire по своим выключен).
        // 0 = нет команды (одиночный режим). >0 = номер команды.
        ensureObjective("svoteam");
    }

    private void ensureObjective(String name) {
        org.bukkit.scoreboard.Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        if (board.getObjective(name) == null) {
            board.registerNewObjective(name, org.bukkit.scoreboard.Criteria.DUMMY, name);
        }
    }

    /**
     * Пересчитывает svowinrate по сглаженной формуле из оригинала:
     *   delit = svogames + 20
     *   svowinrate = (svowins * 100) / delit + 10   (только при svogames >= 3)
     * Раньше тут было "operation svowinrate = svowins", из-за чего winrate
     * становился равен числу побед.
     */
    public void updateWinrateScore(String playerName) {
        org.bukkit.scoreboard.Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        int games = readObjective(board, "svogames", playerName);
        int wins  = readObjective(board, "svowins",  playerName);
        if (games < 3) return; // мало игр - не трогаем
        int delit = games + 20;
        int wr = (wins * 100) / delit + 10;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            "scoreboard players set " + playerName + " svowinrate " + wr);
    }

    private int readObjective(org.bukkit.scoreboard.Scoreboard board, String objName, String playerName) {
        org.bukkit.scoreboard.Objective obj = board.getObjective(objName);
        if (obj == null) return 0;
        org.bukkit.scoreboard.Score s = obj.getScore(playerName);
        return s.isScoreSet() ? s.getScore() : 0;
    }

    private static final String HIDE_TEAM = "svohide";

    /** Команда у которой скрыты ники над головой. Создаёт если нет. */
    private org.bukkit.scoreboard.Team getHideTeam() {
        org.bukkit.scoreboard.Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        org.bukkit.scoreboard.Team team = board.getTeam(HIDE_TEAM);
        if (team == null) team = board.registerNewTeam(HIDE_TEAM);
        team.setOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY,
                       org.bukkit.scoreboard.Team.OptionStatus.NEVER);
        team.setCanSeeFriendlyInvisibles(false);
        return team;
    }

    /** Скрывает ник игрока над головой (добавляет в команду svohide). */
    public void hideNameTag(Player p) {
        getHideTeam().addEntry(p.getName());
    }

    /** Идёт ли сейчас игра (любая фаза кроме IDLE/QUEUE). */
    public boolean isGameRunning() {
        return state == GameState.ACTIVE || state == GameState.STARTING || state == GameState.ENDING;
    }

    /** Является ли игрок участником текущей игры (в players или с тегом svoplayer). */
    public boolean isParticipant(Player p) {
        if (players.containsKey(p.getUniqueId())) return true;
        return p.getScoreboardTags().contains("svoplayer");
    }

    /** Отправляет кликабельную кнопку "Наблюдать" не-участнику во время идущей игры. */
    public void sendSpectateOffer(Player p) {
        dev.volansvo.svo.maps.MapData map = plugin.getMapManager().getActiveMap();
        String mapName = map != null ? map.getDisplayName() : "СВО";
        int sec = Math.max(0, currentTick / 20);
        String time = String.format("%02d:%02d", sec / 60, sec % 60);
        p.sendMessage(ChatColor.RED + "Игра уже идёт на карте " + ChatColor.WHITE + mapName
            + ChatColor.RED + ". Осталось времени: " + ChatColor.YELLOW + time);
        boolean inGameLive = isPlayerInGame(p.getUniqueId());
        String hover = inGameLive ? "Сдаться и наблюдать" : "Нажми чтобы наблюдать за игрой";
        if (inGameLive) {
            p.sendMessage(ChatColor.GRAY + "Ты в игре. Наблюдение = сдача.");
        }
        String tellraw = "tellraw " + p.getName()
            + " {\"text\":\"[ Наблюдать ]\",\"color\":\"aqua\",\"bold\":true"
            + ",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/svowatch\"}"
            + ",\"hoverEvent\":{\"action\":\"show_text\",\"contents\":\"" + hover + "\"}}";
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), tellraw);

        // Список живых игроков - клик по нику тепает прямо к нему
        List<Player> live = getActivePlayers();
        if (!live.isEmpty()) {
            p.sendMessage(ChatColor.GRAY + "Или наблюдать за конкретным игроком:");
            StringBuilder names = new StringBuilder();
            names.append("tellraw ").append(p.getName()).append(" [\"\"");
            for (Player lp : live) {
                names.append(",{\"text\":\"[").append(lp.getName()).append("] \",\"color\":\"yellow\"")
                     .append(",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/svowatch ").append(lp.getName()).append("\"}")
                     .append(",\"hoverEvent\":{\"action\":\"show_text\",\"contents\":\"Наблюдать за ").append(lp.getName()).append("\"}}");
            }
            names.append("]");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), names.toString());
        }
    }

    /** Переводит не-участника в режим наблюдателя в игровом мире. */
    public boolean startSpectating(Player p) {
        return startSpectating(p, null);
    }

    /**
     * Переводит не-участника в SPECTATOR и телепортирует к игроку (или к любому живому).
     * Режим выставляется И сразу, И повторно через 3 тика - на случай если
     * Multiverse enforce-gamemode сбрасывает режим при входе в мир.
     */
    public boolean startSpectating(final Player p, String targetName) {
        if (!isGameRunning()) {
            p.sendMessage(ChatColor.RED + "Сейчас нет игры.");
            return false;
        }
        // Живой участник, выбравший наблюдение - это сдача (выбывает из игры).
        if (isPlayerInGame(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "Ты сдался и теперь наблюдаешь.");
            forceEliminate(p.getUniqueId());
        }
        World gw = plugin.getWorldManager().getGameWorld();
        if (gw == null) {
            p.sendMessage(ChatColor.RED + "Игровой мир не найден.");
            return false;
        }

        Location target = null;
        if (targetName != null) {
            Player tp = Bukkit.getPlayerExact(targetName);
            if (tp != null && isParticipant(tp) && tp.getWorld().equals(gw)) {
                target = tp.getLocation();
            }
        }
        if (target == null) {
            List<Player> active = getActivePlayers();
            target = !active.isEmpty() ? active.get(0).getLocation() : gw.getSpawnLocation();
        }
        final Location dest = target;

        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(dest);
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() {
                if (!p.isOnline()) return;
                if (p.getGameMode() != GameMode.SPECTATOR) p.setGameMode(GameMode.SPECTATOR);
                p.teleport(dest);
            }
        }, 3L);
        p.sendMessage(ChatColor.AQUA + "Ты наблюдаешь за игрой. /svolobby чтобы выйти когда игра закончится.");
        return true;
    }

    /** Возвращает ник игрока (убирает из команды svohide). */
    public void showNameTag(Player p) {
        org.bukkit.scoreboard.Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        org.bukkit.scoreboard.Team team = board.getTeam(HIDE_TEAM);
        if (team != null) team.removeEntry(p.getName());
    }

    /**
     * Раскидывает участников по командам размера teamSize.
     * teamSize=1 (одиночный) - команды не создаются, каждый сам за себя.
     * Для Дуо/Трио создаёт scoreboard-команды с ВЫКЛЮЧЕННЫМ friendly fire,
     * присваивает каждому SvoPlayer teamId и сообщает игрокам состав команды.
     */
    private void assignTeams() {
        clearSquadTeams(); // на всякий случай чистим хвосты прошлой игры
        squadTeamCount = 0;

        org.bukkit.scoreboard.Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        int effectiveSize = Math.max(1, teamSize);
        plugin.getLogger().info("[СВО] assignTeams: игроков=" + players.size()
            + " effectiveSize=" + effectiveSize + " predetermined=" + (predeterminedTeams != null));

        // Группировка игроков по номеру команды (teamNumber, с 1).
        // Если есть заранее заданное распределение (ручной выбор тиммейтов) - берём его,
        // иначе раскидываем случайно по effectiveSize.
        LinkedHashMap<Integer, List<UUID>> groups = new LinkedHashMap<Integer, List<UUID>>();

        if (effectiveSize > 1 && predeterminedTeams != null) {
            List<UUID> unassigned = new ArrayList<UUID>();
            for (UUID uid : players.keySet()) {
                Player p = Bukkit.getPlayer(uid);
                if (p == null) continue;
                Integer num = predeterminedTeams.get(uid);
                if (num == null) { unassigned.add(uid); continue; }
                List<UUID> g = groups.get(num);
                if (g == null) { g = new ArrayList<UUID>(); groups.put(num, g); }
                g.add(uid);
            }
            // Боты (и все, кого нет в ручном распределении) добивают неполные команды,
            // остальные собираются в свои команды.
            Collections.shuffle(unassigned);
            for (List<UUID> g : groups.values()) {
                while (g.size() < effectiveSize && !unassigned.isEmpty()) g.add(unassigned.remove(0));
            }
            int nextNum = 1;
            for (Integer k : groups.keySet()) nextNum = Math.max(nextNum, k + 1);
            while (!unassigned.isEmpty()) {
                List<UUID> g = new ArrayList<UUID>();
                for (int k = 0; k < effectiveSize && !unassigned.isEmpty(); k++) g.add(unassigned.remove(0));
                groups.put(nextNum++, g);
            }
        } else {
            List<UUID> ids = new ArrayList<UUID>();
            for (UUID uid : players.keySet()) {
                if (Bukkit.getPlayer(uid) != null) ids.add(uid);
            }
            Collections.shuffle(ids);
            int num = 1;
            for (int i = 0; i < ids.size(); i += effectiveSize) {
                List<UUID> g = new ArrayList<UUID>();
                for (int j = i; j < i + effectiveSize && j < ids.size(); j++) g.add(ids.get(j));
                groups.put(num++, g);
            }
        }

        // ВАЖНО: svoteam присваивается УНИКАЛЬНО даже в одиночном режиме.
        // Тогда условие в executable items "unless score @s svoteam = %player% svoteam"
        // работает единообразно: в одиночном у всех РАЗНЫЕ номера, в Дуо/Трио у тиммейтов одинаковый.
        int teamIdx = 0;
        for (Map.Entry<Integer, List<UUID>> e : groups.entrySet()) {
            int teamNumber = e.getKey();
            List<UUID> members = e.getValue();
            org.bukkit.scoreboard.Team team = null;
            if (effectiveSize > 1) {
                String teamName = SQUAD_TEAM_PREFIX + teamIdx;
                team = board.getTeam(teamName);
                if (team != null) team.unregister();
                team = board.registerNewTeam(teamName);
                team.setAllowFriendlyFire(false);
                team.setCanSeeFriendlyInvisibles(true);
                // Ник тиммейта виден ТОЛЬКО в режиме с подсветкой (highlightTeammates) - иначе,
                // как и в одиночном режиме, ники скрыты для всех (см. hideNameTag).
                // ВАЖНО: FOR_OTHER_TEAMS означает "ограничение (скрытие) применяется к чужим
                // командам" - т.е. видно СВОЕЙ команде, а не наоборот (см. javadoc OptionStatus).
                // FOR_OWN_TEAM было перепутано местами - от этого подсветка ников работала наоборот.
                team.setOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY,
                    highlightTeammates ? org.bukkit.scoreboard.Team.OptionStatus.FOR_OTHER_TEAMS
                                        : org.bukkit.scoreboard.Team.OptionStatus.NEVER);
            }
            // Боссбар ХП тиммейтов создаётся лениво в updateTeamHpBars (после setupTimerBar,
            // чтобы встать под ним) - здесь ничего не создаём.

            List<String> memberNames = new ArrayList<String>();
            for (UUID uid : members) {
                SvoPlayer sp = players.get(uid);
                Player p = Bukkit.getPlayer(uid);
                if (sp == null || p == null) continue;
                sp.setTeamId(effectiveSize > 1 ? teamIdx : -1);
                if (team != null) team.addEntry(p.getName());
                // Зрителей добавляем позже (в updateTeamHpBars), ПОСЛЕ setupTimerBar() -
                // иначе этот боссбар рендерился бы НАД таймером, а не под Жириновским.
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "scoreboard players set " + p.getName() + " svoteam " + teamNumber);
                memberNames.add(p.getName());
            }

            if (effectiveSize > 1) {
                String roster = String.join(", ", memberNames);
                for (String n : memberNames) {
                    Player p = Bukkit.getPlayerExact(n);
                    if (p != null) {
                        p.sendMessage(ChatColor.AQUA + "Твоя команда (" + teamModeName(teamSize) + "): "
                            + ChatColor.WHITE + roster);
                        p.sendMessage(ChatColor.GRAY + "Урон по своим выключен.");
                    }
                }
            }
            teamIdx++;
        }
        squadTeamCount = (effectiveSize > 1) ? teamIdx : 0;
        predeterminedTeams = null; // распределение использовано
    }

    /**
     * Полностью снимает командную принадлежность:
     * - обнуляет svoteam у всех, кто был в боевых scoreboard-командах,
     * - удаляет сами scoreboard-команды (svosquad*),
     * - сбрасывает teamId у всех SvoPlayer.
     * Так после катки НИКТО не остаётся в команде.
     */
    private void clearSquadTeams() {
        org.bukkit.scoreboard.Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (org.bukkit.scoreboard.Team t : new ArrayList<org.bukkit.scoreboard.Team>(board.getTeams())) {
            if (!t.getName().startsWith(SQUAD_TEAM_PREFIX)) continue;
            // Обнуляем svoteam каждому участнику команды перед удалением.
            for (String entry : new HashSet<String>(t.getEntries())) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "scoreboard players set " + entry + " svoteam 0");
            }
            t.unregister();
        }
        // Сбрасываем teamId у всех известных игроков.
        for (SvoPlayer sp : players.values()) sp.setTeamId(-1);
        squadTeamCount = 0;
        // Гасим боссбары ХП тиммейтов прошлой игры.
        for (BossBar bar : teamHpBars.values()) { bar.removeAll(); bar.setVisible(false); }
        teamHpBars.clear();
    }

    /** Обнуляет svoteam у ВСЕХ онлайн-игроков (гарантия: никто не в команде после катки). */
    private void resetAllSvoteamScores() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "scoreboard players set " + p.getName() + " svoteam 0");
        }
    }

    /** В одной ли команде эти два игрока (для блокировки урона по своим). */
    public boolean sameTeam(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) return false;
        SvoPlayer sa = players.get(a);
        SvoPlayer sb = players.get(b);
        if (sa == null || sb == null) return false;
        if (sa.getTeamId() < 0 || sb.getTeamId() < 0) return false;
        return sa.getTeamId() == sb.getTeamId();
    }

    /**
     * Глобальный watcher по тегам (запускается из VolanSVO.onEnable).
     * Ищет яныков и бандитов в ЛЮБОМ мире по тегу - решает проблему клонирования мира.
     */
    public void startGlobalWatcher() {
        new BukkitRunnable() {
            int tick = 0;
            @Override
            public void run() {
                tick++;

                // ВОТЧДОГ: если игра активна, но таймер не двигается - перезапускаем тик-луп.
                // Проверяем не только "задача отменена", но и РЕАЛЬНОЕ продвижение currentTick:
                // задача может остаться "живой", но перестать что-либо делать.
                try {
                    if (state == GameState.ACTIVE) {
                        boolean dead = (tickTask == null || tickTask.isCancelled());
                        // watcher тикает каждые 5 игровых тиков; за это время currentTick
                        // обязан уменьшиться. Если 3 проверки подряд (0.75 сек) не менялся - луп завис.
                        boolean stalled = false;
                        if (currentTick == watchdogLastSeenTick) {
                            watchdogStallCount++;
                            if (watchdogStallCount >= 3) stalled = true;
                        } else {
                            watchdogLastSeenTick = currentTick;
                            watchdogStallCount = 0;
                        }
                        if (dead || stalled) {
                            plugin.getLogger().warning("[СВО] Таймер завис (dead=" + dead
                                + " stalled=" + stalled + " tick=" + currentTick + ") - перезапуск тик-лупа.");
                            if (tickTask != null) { tickTask.cancel(); tickTask = null; }
                            watchdogStallCount = 0;
                            watchdogLastSeenTick = currentTick;
                            startTickLoop();
                        }
                    } else {
                        watchdogLastSeenTick = -1;
                        watchdogStallCount = 0;
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("[СВО] Ошибка вотчдога: " + t);
                }

                // Всё тело watcher-а в try/catch - чтобы он сам никогда не умер.
                try {
                    watcherBody(tick);
                } catch (Throwable t) {
                    plugin.getLogger().warning("[СВО] Ошибка глобального watcher-а: " + t);
                }
            }
        }.runTaskTimer(plugin, 5L, 5L); // 0.25 сек
    }

    /** Тело глобального watcher-а (вынесено чтобы обернуть в try/catch). */
    private void watcherBody(int tick) {
        // Редкий (раз в ~5 сек) full-scan ПО ВСЕМ МИРАМ - только чтобы (пере)найти UUID
        // яныков/спящих бандитов/стендов-порталов. Сам поиск дешёвый, потому что редкий -
        // вся дальнейшая обработка ниже идёт по уже найденным UUID, а не сканом мира.
        if (tick == 1 || tick % WATCHER_DISCOVERY_PERIOD == 0) rediscoverWatcherEntities();

        Random rng = new Random();

        // ЯНЫКИ - по кэшированным UUID.
        Iterator<UUID> yit = yanikIds.iterator();
        while (yit.hasNext()) {
            Entity e = Bukkit.getEntity(yit.next());
            if (e == null || e.isDead() || !(e instanceof Zombie)) { yit.remove(); continue; }
            Zombie yanyk = (Zombie) e;
            World world = yanyk.getWorld();
            if (yanyk.getFireTicks() > 0) yanyk.setFireTicks(0);

            boolean sleeping = yanyk.getScoreboardTags().contains("yaniksvo_sleep");
            if (sleeping) {
                // Любой svoplayer в SURVIVAL/ADVENTURE будит
                Player trigger = findSvoPlayerInRange(yanyk, YANIK_RADIUS);
                if (trigger != null) {
                    yanyk.setAI(true);
                    yanyk.removeScoreboardTag("yaniksvo_sleep");
                }
                continue;
            }

            // Активный янык - партиклы каждый вызов вотчера
            Location head = yanyk.getLocation().add(0, 1.8, 0);
            for (Player viewer : world.getPlayers()) {
                viewer.spawnParticle(Particle.ELECTRIC_SPARK,
                    head.getX(), head.getY(), head.getZ(), 12, 0.4, 0.3, 0.4, 0.15);
            }

            // Удар молнии раз в YANIK_COOLDOWN_MS если рядом цели
            long now = System.currentTimeMillis();
            Long last = yanikLastStrike.get(yanyk.getUniqueId());
            if (last != null && (now - last) < YANIK_COOLDOWN_MS) continue;

            List<Player> targets = new ArrayList<Player>();
            for (Entity ne : yanyk.getNearbyEntities(YANIK_RADIUS, YANIK_RADIUS, YANIK_RADIUS)) {
                if (!(ne instanceof Player)) continue;
                Player p = (Player) ne;
                GameMode gm = p.getGameMode();
                if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) continue;
                targets.add(p);
            }
            if (targets.isEmpty()) continue;
            yanikLastStrike.put(yanyk.getUniqueId(), now);
            // Только 1 случайная цель - без боковых ударов
            Player chosen = targets.get(rng.nextInt(targets.size()));
            world.strikeLightning(chosen.getLocation());
        }

        if (tick % 4 == 0) { // каждую секунду
            // ИГРОКИ В ОЧЕРЕДИ - поддерживаем saturation + instant_health пока в gameWorld
            // (т.е. с /svolobby до начала игры или до выхода из мира)
            World gw = plugin.getWorldManager().getGameWorld();
            if (gw != null) {
                for (UUID uid : queue) {
                    Player p = Bukkit.getPlayer(uid);
                    if (p == null || !p.getWorld().equals(gw)) continue;
                    p.addPotionEffect(new PotionEffect(
                        PotionEffectType.SATURATION, 60, 255, true, false, false));
                    // Сила лечения 4 << amplifier: при 255 сдвиг переполняется и лечит 0 - берём 3 (+32 хп).
                    p.addPotionEffect(new PotionEffect(
                        PotionEffectType.INSTANT_HEALTH, 60, 3, true, false, false));
                }
            }

            // СПЯЩИЕ БАНДИТЫ - по кэшированным UUID.
            Iterator<UUID> bit = banditSleepIds.iterator();
            while (bit.hasNext()) {
                Entity e = Bukkit.getEntity(bit.next());
                if (e == null || e.isDead() || !(e instanceof LivingEntity)) { bit.remove(); continue; }
                LivingEntity le = (LivingEntity) e;
                if (!le.getScoreboardTags().contains("svobandit_sleep")) { bit.remove(); continue; }
                // Пробуждаем от ЛЮБОГО игрока (не только svoplayer) - чтобы разбой
                // работал и вне игры (напр. ХАМАС на eastgame_).
                Player trigger = findPlayerInRange(le, BANDIT_WAKE_RADIUS);
                if (trigger != null) {
                    le.setAI(true);
                    le.removeScoreboardTag("svobandit_sleep");
                    bit.remove();
                }
            }

            // СТЕНД-ПОРТАЛ В ЛОББИ - по кэшированным UUID. Игроки в радиусе 2 блоков
            // от стенда svolobbyst выполняют /svolobby (как ориг. командный блок).
            Iterator<UUID> sit = lobbyStandIds.iterator();
            while (sit.hasNext()) {
                Entity e = Bukkit.getEntity(sit.next());
                if (e == null || e.isDead() || !(e instanceof ArmorStand)) { sit.remove(); continue; }
                // Переименовываем уже стоящие стенды (без пересоздания).
                String want = ChatColor.AQUA + "Встать в очередь";
                if (!want.equals(e.getCustomName())) {
                    e.setCustomName(want); e.setCustomNameVisible(true);
                }
                for (Entity near : e.getNearbyEntities(2, 2, 2)) {
                    if (!(near instanceof Player)) continue;
                    Player p = (Player) near;
                    Bukkit.dispatchCommand(p, "svolobby");
                }
            }
        }
    }

    /** Редкий full-scan ПО ВСЕМ МИРАМ СЕРВЕРА - (пере)находит яныков/спящих бандитов/стенды-
     *  порталы по тегу и кэширует их UUID. Раньше этот скан (getEntitiesByClass/getLivingEntities)
     *  шёл по всем мирам 4 РАЗА В СЕКУНДУ не переставая - топило TPS сильнее всего, что связано
     *  с чанками. Теперь - раз в WATCHER_DISCOVERY_PERIOD вызовов (~5 сек), а между сканами
     *  обработка идёт по уже найденным UUID (Bukkit.getEntity) - дёшево и работает в ЛЮБОМ
     *  мире, где реально стоит стенд/мобы, а не только в мире активной карты. */
    private void rediscoverWatcherEntities() {
        yanikIds.clear();
        banditSleepIds.clear();
        lobbyStandIds.clear();
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntitiesByClass(Zombie.class)) {
                if (e.getScoreboardTags().contains("yaniksvo")) yanikIds.add(e.getUniqueId());
            }
            for (Entity e : w.getLivingEntities()) {
                if (e.getScoreboardTags().contains("svobandit_sleep")) banditSleepIds.add(e.getUniqueId());
            }
            for (Entity e : w.getEntitiesByClass(ArmorStand.class)) {
                if (e.getScoreboardTags().contains("svolobbyst")) lobbyStandIds.add(e.getUniqueId());
            }
        }
    }

    /** Ищет ЛЮБОГО игрока (в выживании/приключении, не спектатора/креатива) в радиусе. */
    private Player findPlayerInRange(LivingEntity entity, double radius) {
        for (Entity e : entity.getNearbyEntities(radius, radius, radius)) {
            if (!(e instanceof Player)) continue;
            Player p = (Player) e;
            GameMode gm = p.getGameMode();
            if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) continue;
            return p;
        }
        return null;
    }

    /** Ищет svoplayer (с тегом, в выживании/приключении) в радиусе от entity. */
    private Player findSvoPlayerInRange(LivingEntity entity, double radius) {
        for (Entity e : entity.getNearbyEntities(radius, radius, radius)) {
            if (!(e instanceof Player)) continue;
            Player p = (Player) e;
            GameMode gm = p.getGameMode();
            if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) continue;
            if (!p.getScoreboardTags().contains("svoplayer")) continue;
            return p;
        }
        return null;
    }

    // ----- Queue -----

    public void addToQueue(Player player) {
        if (state != GameState.IDLE && state != GameState.QUEUE) return;
        if (queue.contains(player.getUniqueId())) return;
        queue.add(player.getUniqueId());
        state = GameState.QUEUE;
        // Голосование уже идёт: новичку тоже нужна кнопка, иначе без его голоса оно не пройдёт.
        if (activeVote != null) sendVotePrompt(player);
        // Сообщаем ВСЕМ в очереди о новом игроке.
        for (Player q : getQueuedPlayers()) {
            q.sendMessage(ChatColor.YELLOW + player.getName() + ChatColor.GRAY + " зашёл в очередь. "
                + ChatColor.GRAY + "В очереди: " + ChatColor.WHITE + queue.size());
        }
    }

    public void removeFromQueue(Player player) {
        removeFromQueue(player.getUniqueId());
    }

    /** Если ушедший игрок - инициатор активного голосования, автоматически отменяем его. */
    private void cancelVoteIfInitiator(UUID uid) {
        if (activeVote == null || activeVote.initiator == null) return;
        if (!activeVote.initiator.equals(uid)) return;
        activeVote = null;
        removeCancelVoteItemFromAll();
        plugin.getMapManager().clearActiveMap();
        for (Player q : getQueuedPlayers()) {
            q.sendMessage(ChatColor.RED + "Голосование отменено - инициатор вышел из очереди.");
        }
    }

    public void removeFromQueue(UUID uid) {
        queue.remove(uid);
        // Инициатор голосования вышел (из очереди или с сервера) - отменяем голосование.
        cancelVoteIfInitiator(uid);
        // Если шла фаза формирования - убираем игрока из его команды/заявок.
        if (formationActive) {
            removeFromFormation(uid);
        }
        // ВАЖНО: сбрасывать state в IDLE можно ТОЛЬКО в фазе лобби (QUEUE).
        // Во время игры queue уже пуст, и любой выход игрока (даже не из СВО)
        // обнулял бы state -> тик-луп видел state != ACTIVE и отменял таймер.
        // Это и была причина заморозки таймера при выходе любого игрока.
        if (queue.isEmpty() && state == GameState.QUEUE) state = GameState.IDLE;
        // Если в фазе формирования никого не осталось - отменяем.
        if (formationActive && queue.isEmpty()) cancelFormation();
        // Ушёл тот, кто не голосовал: возможно, проголосовали уже все оставшиеся. Проверяем
        // тиком позже (сейчас может идти событие выхода игрока).
        if (activeVote != null && state == GameState.QUEUE && plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (activeVote != null && state == GameState.QUEUE) checkVoteComplete();
            });
        }
    }

    /** Убирает игрока из команд/заявок формирования (при выходе из очереди). */
    private void removeFromFormation(UUID uid) {
        formReady.remove(uid);
        // Снимаем все его заявки на вступление.
        for (LinkedHashSet<UUID> s : joinRequests.values()) s.remove(uid);
        Integer tid = formPlayerTeam.remove(uid);
        if (tid != null) {
            LinkedHashSet<UUID> set = formTeams.get(tid);
            if (set != null) {
                set.remove(uid);
                if (set.isEmpty()) formTeams.remove(tid);
            }
        }
        refreshGuiForQueue();
        checkFormationReady(); // ушёл последний, кто ещё не был готов
    }

    public boolean isInQueue(Player player) { return queue.contains(player.getUniqueId()); }
    public int getQueueSize() { return queue.size(); }
    public Set<UUID> getQueueUuids() { return new LinkedHashSet<UUID>(queue); }

    public List<Player> getQueuedPlayers() {
        List<Player> result = new ArrayList<Player>();
        for (UUID uid : queue) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) result.add(p);
        }
        return result;
    }

    /**
     * Removes players from queue who lost the "insvo" tag.
     * Called periodically and before checking queue size.
     */
    public void purgeQueue() {
        Iterator<UUID> it = queue.iterator();
        while (it.hasNext()) {
            UUID uid = it.next();
            Player p = Bukkit.getPlayer(uid);
            if (p == null || !p.getScoreboardTags().contains("insvo")) {
                it.remove();
            }
        }
        if (queue.isEmpty() && state == GameState.QUEUE) state = GameState.IDLE;
    }

    // ----- Mode dispatch (solo / duo / trio / randomduo / randomtrio) -----

    /**
     * Точка входа из /svoplay. Сначала ВСЕГДА голосование, затем:
     *  - solo / randomduo / randomtrio: сразу старт (случайное распределение).
     *  - duo/trio: после голосования - фаза РУЧНОГО формирования команд (GUI), потом старт.
     */
    public void requestStartMode(Player initiator, int lives, int timeMins,
                                 boolean withZir, boolean withGlow, boolean withFastZone,
                                 boolean withAirdrops, boolean withTeamGlow, String mode) {
        requestStartMode(initiator, lives, timeMins, withZir, withGlow, withFastZone,
            withAirdrops, withTeamGlow, false, mode);
    }

    public void requestStartMode(Player initiator, int lives, int timeMins,
                                 boolean withZir, boolean withGlow, boolean withFastZone,
                                 boolean withAirdrops, boolean withTeamGlow, boolean withChaos, String mode) {
        requestStartMode(initiator, lives, timeMins, withZir, withGlow, withFastZone,
            withAirdrops, withTeamGlow, withChaos, mode, 0);
    }

    public void requestStartMode(Player initiator, int lives, int timeMins,
                                 boolean withZir, boolean withGlow, boolean withFastZone,
                                 boolean withAirdrops, boolean withTeamGlow, boolean withChaos, String mode, int bots) {
        bots = Math.max(0, Math.min(dev.volansvo.svo.bots.BotManager.MAX_BOTS, bots));
        if (state == GameState.ACTIVE || state == GameState.STARTING) {
            initiator.sendMessage(ChatColor.RED + "Игра уже идёт!");
            return;
        }
        if (state == GameState.TEAM_FORMATION) {
            initiator.sendMessage(ChatColor.RED + "Уже идёт формирование команд. Дождись окончания.");
            return;
        }
        if (activeVote != null) {
            initiator.sendMessage(ChatColor.RED + "Уже идёт голосование. Дождись окончания.");
            return;
        }
        purgeQueue();
        if (!queue.contains(initiator.getUniqueId())) {
            initiator.sendMessage(ChatColor.RED + "Ты не в очереди. Встань через /svolobby.");
            return;
        }

        int teamSizeArg;
        boolean manual;
        if (mode.equals("duo"))            { teamSizeArg = 2; manual = true; }
        else if (mode.equals("trio"))      { teamSizeArg = 3; manual = true; }
        else if (mode.equals("randomduo")) { teamSizeArg = 2; manual = false; }
        else if (mode.equals("randomtrio")){ teamSizeArg = 3; manual = false; }
        else                               { teamSizeArg = 1; manual = false; } // solo

        // Минимум игроков: дуо требует 3, трио требует 4, одиночный 2.
        int minPlayers = (teamSizeArg == 2) ? 3 : (teamSizeArg == 3) ? 4 : 2;
        if (queue.size() + bots < minPlayers) {
            initiator.sendMessage(ChatColor.RED + "Для режима " + teamModeName(teamSizeArg)
                + " нужно минимум " + minPlayers + " участник(ов) (игроки + боты). Сейчас: "
                + queue.size() + (bots > 0 ? " + " + bots + " бот(ов)" : ""));
            return;
        }

        predeterminedTeams = null; // по умолчанию рандом
        // ВСЕГДА сначала голосование. Если режим ручной (duo/trio) - после голосования
        // запустится фаза формирования команд (см. checkVoteComplete).
        requestStart(initiator, lives, timeMins, withZir, withGlow, withFastZone, withAirdrops, withTeamGlow, withChaos, teamSizeArg, manual, bots);
    }

    // ----- Manual team formation (Дуо/Трио с выбором тиммейтов) -----

    public boolean isFormationActive() { return formationActive; }
    public int getFormationTeamSize() { return formationTeamSize; }

    /** Запускается ПОСЛЕ удачного голосования для ручных режимов (Дуо/Трио). */
    private void startTeamFormation(VotingSession s) {
        formationActive = true;
        formationTeamSize = s.teamSize;
        formationInitiator = s.initiator != null ? s.initiator : getInitiatorFromQueue();
        formBots.clear();
        pendingBotTeams.clear();
        formPlayerTeam.clear();
        formTeams.clear();
        joinRequests.clear();
        formReady.clear();
        formationFinishing = false;
        formNextId = 100;
        state = GameState.TEAM_FORMATION;
        formationSession = s; // параметры уже согласованы голосованием

        broadcastQueue(ChatColor.GOLD + "===== Формирование команд (" + teamModeName(s.teamSize) + ") =====");
        broadcastQueue(ChatColor.GRAY + "Открой меню команд: " + ChatColor.WHITE + "/svoteam");
        broadcastQueue(ChatColor.GRAY + "Размер команды: " + ChatColor.WHITE + s.teamSize
            + ChatColor.GRAY + ". У вас 60 секунд. Кто не в команде - попадёт в случайную.");
        broadcastQueue(ChatColor.GRAY + "Когда все нажмут " + ChatColor.GREEN + "«Начинаем»"
            + ChatColor.GRAY + " (в меню команд или в чате), игра стартует сразу.");
        Player host = formationInitiator != null ? Bukkit.getPlayer(formationInitiator) : null;
        if (host != null) {
            host.sendMessage(ChatColor.AQUA + "Ты хост: в меню команд " + ChatColor.WHITE + "ПКМ"
                + ChatColor.AQUA + " по команде - добавить бота, " + ChatColor.WHITE + "Shift+ПКМ"
                + ChatColor.AQUA + " - убрать. Можно собрать команду из одних ботов.");
        }
        // Открываем GUI всем в очереди.
        for (Player p : getQueuedPlayers()) openTeamGui(p);
        sendOpenGuiButton();

        formationSecondsLeft = 60;
        if (formationTask != null) formationTask.cancel();
        formationTask = new BukkitRunnable() {
            @Override public void run() {
                formationSecondsLeft--;
                if (state != GameState.TEAM_FORMATION) { cancel(); formationTask = null; return; }
                // Каждые 10 секунд - кликабельная кнопка открыть меню команд.
                if (formationSecondsLeft > 0 && formationSecondsLeft % 10 == 0) {
                    broadcastQueue(ChatColor.YELLOW + "Формирование команд: осталось "
                        + formationSecondsLeft + " сек.");
                    sendOpenGuiButton();
                }
                if (formationSecondsLeft <= 0) {
                    cancel(); formationTask = null;
                    finishTeamFormation();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    /** Рассылает всем в очереди кликабельные кнопки "Открыть меню команд" и "Начинаем". */
    private void sendOpenGuiButton() {
        for (Player p : getQueuedPlayers()) {
            String tellraw = "tellraw " + p.getName() + " [\"\""
                + ",{\"text\":\"[Открыть меню команд]\",\"color\":\"aqua\",\"bold\":true"
                + ",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/svoteam\"}"
                + ",\"hoverEvent\":{\"action\":\"show_text\",\"contents\":\"Выбрать или сменить команду\"}}";
            if (!formReady.contains(p.getUniqueId())) {
                tellraw += ",{\"text\":\" \"},{\"text\":\"[Начинаем]\",\"color\":\"green\",\"bold\":true"
                    + ",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/svoteam ready\"}"
                    + ",\"hoverEvent\":{\"action\":\"show_text\",\"contents\":\"Я готов. Когда нажмут все, игра стартует сразу\"}}";
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), tellraw + "]");
        }
    }

    /** Возвращает индекс команды игрока (или -1). */
    private int teamIdOf(UUID uid) {
        Integer t = formPlayerTeam.get(uid);
        return t == null ? -1 : t;
    }

    private int teamSizeOf(int teamId) {
        LinkedHashSet<UUID> s = formTeams.get(teamId);
        return (s == null ? 0 : s.size()) + botsIn(teamId);
    }

    /** Сколько ботов хост поставил в команду. */
    private int botsIn(int teamId) {
        Integer n = formBots.get(teamId);
        return n == null ? 0 : n;
    }

    /** Сколько ботов хост уже расставил по командам. */
    private int placedBots() {
        int n = 0;
        for (int v : formBots.values()) n += v;
        return n;
    }

    /** Хост (ПКМ/Shift+ПКМ по команде) добавляет или убирает бота. */
    private void hostChangeBots(Player host, int teamId, boolean remove) {
        if (remove) {
            int n = botsIn(teamId);
            if (n <= 0) { host.sendMessage(ChatColor.RED + "В этой команде нет ботов."); return; }
            if (n == 1) formBots.remove(teamId); else formBots.put(teamId, n - 1);
            host.sendMessage(ChatColor.YELLOW + "Бот убран из команды " + teamName(teamId) + ".");
        } else {
            if (teamSizeOf(teamId) >= formationTeamSize) {
                host.sendMessage(ChatColor.RED + "Команда " + teamName(teamId) + " заполнена.");
                return;
            }
            // Ботов в командах не больше, чем выбрали в настройках игры.
            int max = formationSession != null ? formationSession.bots : dev.volansvo.svo.bots.BotManager.MAX_BOTS;
            if (placedBots() >= max) {
                host.sendMessage(ChatColor.RED + (max == 0 ? "В настройках игры выбрано 0 ботов."
                    : "Выбрано ботов: " + max + ", больше добавить нельзя."));
                return;
            }
            formBots.put(teamId, botsIn(teamId) + 1);
            host.sendMessage(ChatColor.GREEN + "Бот добавлен в команду " + teamName(teamId)
                + ChatColor.GRAY + " (ботов расставлено: " + placedBots() + (formationSession != null ? "/" + formationSession.bots : "") + ")");
        }
        refreshGuiForQueue();
    }

    // ----- GUI настроек /svoplay -----

    /** Открывает GUI настройки игры (выбор всех параметров). */
    public void openSetupGui(final Player p) {
        if (isGameRunning()) { p.sendMessage(ChatColor.RED + "Игра уже идёт."); return; }
        if (!isInQueue(p)) { p.sendMessage(ChatColor.RED + "Ты не в очереди. Используй /svolobby."); return; }
        SetupChoice c = setupChoices.get(p.getUniqueId());
        if (c == null) {
            c = new SetupChoice();
            // По умолчанию - активная карта или первая готовая.
            for (dev.volansvo.svo.maps.MapData m : plugin.getMapManager().getAll().values()) {
                if (m.isReady()) { c.mapId = m.getId(); break; }
            }
            setupChoices.put(p.getUniqueId(), c);
        }
        final SetupChoice fc = c;
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                if (!p.isOnline()) return;
                openSetupGuiNow(p, fc);
            }
        });
    }

    private void openSetupGuiNow(Player p, SetupChoice c) {
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(null, 27, SETUP_GUI_TITLE);

        // Карта (slot 10)
        dev.volansvo.svo.maps.MapData md = c.mapId != null ? plugin.getMapManager().get(c.mapId) : null;
        String mapName = md != null ? md.getDisplayName() : "не выбрана";
        inv.setItem(10, setupItem(Material.FILLED_MAP, ChatColor.GOLD + "Карта: " + ChatColor.WHITE + mapName,
            ChatColor.GRAY + "Клик - следующая карта"));
        // Жизни (slot 11)
        inv.setItem(11, setupItem(Material.TOTEM_OF_UNDYING, ChatColor.GOLD + "Жизни: " + ChatColor.WHITE + c.lives,
            ChatColor.GRAY + "Клик - 1/2/3"));
        // Время (slot 12)
        inv.setItem(12, setupItem(Material.CLOCK, ChatColor.GOLD + "Время: " + ChatColor.WHITE + c.time + " мин",
            ChatColor.GRAY + "Клик - 15/30"));
        // Жириновский (slot 13)
        inv.setItem(13, setupItem(c.zir ? Material.LIME_DYE : Material.GRAY_DYE,
            ChatColor.GOLD + "Жириновский: " + onOff(c.zir), ChatColor.GRAY + "Клик - вкл/выкл"));
        // Подсветка (slot 14)
        inv.setItem(14, setupItem(c.glow ? Material.GLOWSTONE_DUST : Material.GUNPOWDER,
            ChatColor.GOLD + "Подсветка: " + onOff(c.glow), ChatColor.GRAY + "Клик - вкл/выкл"));
        // Быстрая зона (slot 15)
        inv.setItem(15, setupItem(c.fast ? Material.SUGAR : Material.GRAY_DYE,
            ChatColor.GOLD + "Быстрая зона: " + onOff(c.fast), ChatColor.GRAY + "Клик - вкл/выкл"));
        // Аирдропы (slot 16)
        inv.setItem(16, setupItem(c.air ? Material.CHEST : Material.GRAY_DYE,
            ChatColor.GOLD + "Аирдропы: " + onOff(c.air), ChatColor.GRAY + "Клик - вкл/выкл"));
        // Хаос (slot 17)
        inv.setItem(17, setupItem(c.chaos ? Material.TNT : Material.GRAY_DYE,
            ChatColor.GOLD + "Хаос: " + onOff(c.chaos),
            ChatColor.GRAY + "Метеоры, случайные взрывы/эффекты,",
            ChatColor.GRAY + "ночные фантомы-подрывники",
            ChatColor.GRAY + "Клик - вкл/выкл"));
        // Режим (slot 22)
        inv.setItem(22, setupItem(Material.WHITE_BANNER,
            ChatColor.GOLD + "Режим: " + ChatColor.WHITE + modeName(c.mode),
            ChatColor.GRAY + "Клик - след. режим",
            ChatColor.GRAY + "одиночный/дуо/трио/рандом-дуо/рандом-трио"));
        // Подсветка тиммейтов (slot 23) - работает только в командных режимах
        boolean teamMode = !c.mode.equals("solo");
        inv.setItem(23, setupItem(c.teamGlow ? Material.LIME_DYE : Material.GRAY_DYE,
            ChatColor.GOLD + "Подсветка тиммейтов: " + onOff(c.teamGlow),
            ChatColor.GRAY + "Зелёные частицы над сокомандниками",
            teamMode ? (ChatColor.GRAY + "Клик - вкл/выкл")
                     : (ChatColor.DARK_GRAY + "Только в командных режимах")));
        // Боты (slot 21)
        inv.setItem(21, setupItem(c.bots > 0 ? Material.ZOMBIE_HEAD : Material.SKELETON_SKULL,
            ChatColor.GOLD + "Боты: " + ChatColor.WHITE + c.bots,
            ChatColor.GRAY + "Боты играют как игроки: лутают,",
            ChatColor.GRAY + "стреляют, лечатся, держат зону",
            ChatColor.GRAY + "ЛКМ +1, ПКМ -1 (0-" + dev.volansvo.svo.bots.BotManager.MAX_BOTS + ")"));
        // Старт (slot 26)
        inv.setItem(26, setupItem(Material.EMERALD_BLOCK, ChatColor.GREEN + "" + ChatColor.BOLD + "ЗАПУСТИТЬ",
            ChatColor.GRAY + "Голосование за старт с этими настройками"));

        p.openInventory(inv);
    }

    private org.bukkit.inventory.ItemStack setupItem(Material mat, String name, String... lore) {
        org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(mat);
        org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(name);
            if (lore.length > 0) m.setLore(Arrays.asList(lore));
            it.setItemMeta(m);
        }
        return it;
    }

    private String onOff(boolean b) { return b ? (ChatColor.GREEN + "ВКЛ") : (ChatColor.RED + "ВЫКЛ"); }

    // ===== Предмет "Начать СВО" (выдаётся в очереди в мире svogame_, по клику = /svoplay) =====

    private static final String START_ITEM_KEY = "svo_start_item";

    private org.bukkit.NamespacedKey startItemKey() {
        return new org.bukkit.NamespacedKey(plugin, START_ITEM_KEY);
    }

    /** Создаёт предмет-«старт» с PDC-меткой. */
    public org.bukkit.inventory.ItemStack createStartItem() {
        org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(Material.NETHER_STAR);
        org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Начать СВО");
            m.setLore(Arrays.asList(
                ChatColor.GRAY + "ПКМ - открыть меню запуска",
                ChatColor.DARK_GRAY + "(голосование за старт игры)"));
            m.getPersistentDataContainer().set(startItemKey(),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(m);
        }
        return it;
    }

    /** Это наш предмет-«старт»? */
    public boolean isStartItem(org.bukkit.inventory.ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        Byte b = it.getItemMeta().getPersistentDataContainer().get(startItemKey(),
            org.bukkit.persistence.PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }

    private boolean hasStartItem(Player p) {
        for (org.bukkit.inventory.ItemStack it : p.getInventory().getContents())
            if (isStartItem(it)) return true;
        return false;
    }

    /** Выдаёт предмет-«старт» - только в мире svogame_, в очереди, когда игра не идёт. */
    public void giveStartItem(Player p) {
        if (p == null || !p.isOnline()) return;
        if (isGameRunning()) return;
        if (!isInQueue(p)) return;      // /svolobby больше не телепортирует - предмет даём по очереди
        // Кладём в свободный слот (инвентарь в хабе не чистим), лишнее роняем.
        if (!hasStartItem(p)) dropLeftover(p, p.getInventory().addItem(createStartItem()));
        if (!hasLeaveQueueItem(p)) dropLeftover(p, p.getInventory().addItem(createLeaveQueueItem()));
    }

    private void dropLeftover(Player p, java.util.HashMap<Integer, org.bukkit.inventory.ItemStack> left) {
        for (org.bukkit.inventory.ItemStack rest : left.values())
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
    }

    // ===== Компас "Покинуть очередь" =====

    private static final String LEAVE_ITEM_KEY = "svo_leave_queue";
    private org.bukkit.NamespacedKey leaveKey() { return new org.bukkit.NamespacedKey(plugin, LEAVE_ITEM_KEY); }

    public org.bukkit.inventory.ItemStack createLeaveQueueItem() {
        org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(Material.COMPASS);
        org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Покинуть очередь");
            m.setLore(Arrays.asList(ChatColor.GRAY + "ПКМ - выйти из очереди СВО"));
            m.getPersistentDataContainer().set(leaveKey(),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(m);
        }
        return it;
    }

    public boolean isLeaveQueueItem(org.bukkit.inventory.ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        Byte b = it.getItemMeta().getPersistentDataContainer().get(leaveKey(),
            org.bukkit.persistence.PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }

    private boolean hasLeaveQueueItem(Player p) {
        for (org.bukkit.inventory.ItemStack it : p.getInventory().getContents())
            if (isLeaveQueueItem(it)) return true;
        return false;
    }

    /** Клик по компасу - выход из очереди. */
    public void leaveQueueByItem(Player p) {
        if (!isInQueue(p)) { p.sendMessage(ChatColor.RED + "Ты не в очереди."); removeStartItem(p); return; }
        removeFromQueue(p);
        p.removeScoreboardTag("insvo");
        removeStartItem(p); // убирает и звезду, и компас
        p.sendMessage(ChatColor.YELLOW + "Ты покинул очередь СВО.");
        for (Player q : getQueuedPlayers()) {
            q.sendMessage(ChatColor.GRAY + p.getName() + " покинул очередь. В очереди: "
                + ChatColor.WHITE + getQueueSize());
        }
    }

    /**
     * Автоматически убирает из очереди игрока, который покинул мир, где стоит стенд-точка
     * возврата /svolobby (standtpspawn2) - например, ушёл порталом в посторонний мир. Предметы
     * очереди (звезда/компас, и барьер отмены голосования если он был инициатором - через
     * removeFromQueue -> cancelVoteIfInitiator) там больше не нужны и не работают.
     */
    public void leaveQueueOnWorldExit(Player p) {
        if (p == null || !isInQueue(p)) return;
        removeFromQueue(p);
        p.removeScoreboardTag("insvo");
        removeStartItem(p);
        p.sendMessage(ChatColor.YELLOW + "Ты покинул мир лобби СВО - автоматически выведен из очереди.");
        for (Player q : getQueuedPlayers()) {
            q.sendMessage(ChatColor.GRAY + p.getName() + " покинул очередь. В очереди: "
                + ChatColor.WHITE + getQueueSize());
        }
    }

    /** Убирает предметы очереди (звезда + компас) из инвентаря игрока. */
    public void removeStartItem(Player p) {
        if (p == null) return;
        org.bukkit.inventory.ItemStack[] cont = p.getInventory().getContents();
        for (int i = 0; i < cont.length; i++)
            if (isStartItem(cont[i]) || isLeaveQueueItem(cont[i])) p.getInventory().setItem(i, null);
    }

    /** Убирает предметы очереди у всех онлайн-игроков (при старте игры). */
    public void removeStartItemFromAll() {
        for (Player p : Bukkit.getOnlinePlayers()) removeStartItem(p);
    }

    // ===== Барьер "Отменить голосование" (инициатору; по клику отменяет активное голосование) =====

    private static final String CANCELVOTE_ITEM_KEY = "svo_cancelvote";

    private org.bukkit.NamespacedKey cancelVoteKey() {
        return new org.bukkit.NamespacedKey(plugin, CANCELVOTE_ITEM_KEY);
    }

    public org.bukkit.inventory.ItemStack createCancelVoteItem() {
        org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(Material.BARRIER);
        org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Отменить голосование");
            m.setLore(Arrays.asList(ChatColor.GRAY + "ПКМ - отменить текущее голосование за старт"));
            m.getPersistentDataContainer().set(cancelVoteKey(),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(m);
        }
        return it;
    }

    public boolean isCancelVoteItem(org.bukkit.inventory.ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        Byte b = it.getItemMeta().getPersistentDataContainer().get(cancelVoteKey(),
            org.bukkit.persistence.PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }

    /** Выдаёт инициатору барьер отмены голосования (правый край хотбара). */
    public void giveCancelVoteItem(Player p) {
        if (p == null) return;
        // Правый край хотбара, если он свободен; иначе любой свободный слот. Раньше барьер
        // ложился в 9-й слот поверх вещи игрока (инвентарь в хабе не чистится) - вещь пропадала.
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        org.bukkit.inventory.ItemStack at8 = inv.getItem(8);
        if (at8 == null || at8.getType() == Material.AIR) inv.setItem(8, createCancelVoteItem());
        else inv.addItem(createCancelVoteItem()); // места нет - без барьера (отмена и так по таймауту)
        p.updateInventory();
    }

    /** Убирает барьер отмены у всех онлайн-игроков. */
    public void removeCancelVoteItemFromAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            org.bukkit.inventory.ItemStack[] cont = p.getInventory().getContents();
            for (int i = 0; i < cont.length; i++)
                if (isCancelVoteItem(cont[i])) p.getInventory().setItem(i, null);
        }
    }

    /** Клик по барьеру: отменяет голосование, если кликнул его инициатор. */
    public void cancelVoteByItem(Player p) {
        if (activeVote == null) {
            p.sendMessage(ChatColor.RED + "Сейчас нет активного голосования.");
            removeCancelVoteItemFromAll();
            return;
        }
        if (activeVote.initiator == null || !activeVote.initiator.equals(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "Отменить голосование может только тот, кто его начал.");
            return;
        }
        activeVote = null;
        removeCancelVoteItemFromAll();
        plugin.getMapManager().clearActiveMap(); // idle -> дефолт svo
        for (Player q : getQueuedPlayers()) {
            q.sendMessage(ChatColor.RED + "Голосование отменено инициатором (" + p.getName() + ").");
        }
    }

    // ===== Ядерная кнопка (убийце Жириновского; по клику - выбор цели и запуск ракеты) =====

    private static final String NUKE_ITEM_KEY = "svo_nuke_button";
    public static final String NUKE_GUI_TITLE = ChatColor.DARK_RED + "" + ChatColor.BOLD + "Цель ракеты";

    private org.bukkit.NamespacedKey nukeItemKey() {
        return new org.bukkit.NamespacedKey(plugin, NUKE_ITEM_KEY);
    }

    public org.bukkit.inventory.ItemStack createNukeButton() {
        org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(Material.TNT);
        org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "☢ ЯДЕРНАЯ КНОПКА ☢");
            m.setLore(Arrays.asList(
                ChatColor.GRAY + "ПКМ - выбрать цель и запустить ракету",
                ChatColor.DARK_GRAY + "Запуск = победа в игре"));
            m.getPersistentDataContainer().set(nukeItemKey(),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(m);
        }
        return it;
    }

    public boolean isNukeButton(org.bukkit.inventory.ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        Byte b = it.getItemMeta().getPersistentDataContainer().get(nukeItemKey(),
            org.bukkit.persistence.PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }

    public boolean hasNukeButton(Player p) {
        if (p == null) return false;
        for (org.bukkit.inventory.ItemStack it : p.getInventory().getContents())
            if (isNukeButton(it)) return true;
        return false;
    }

    public boolean dropsContainNuke(java.util.List<org.bukkit.inventory.ItemStack> drops) {
        for (org.bukkit.inventory.ItemStack it : drops) if (isNukeButton(it)) return true;
        return false;
    }

    public void removeNukeButtons(Player p) {
        if (p == null) return;
        org.bukkit.inventory.ItemStack[] cont = p.getInventory().getContents();
        for (int i = 0; i < cont.length; i++) if (isNukeButton(cont[i])) p.getInventory().setItem(i, null);
    }

    /** Выдаёт кнопку ВМЕСТО предмета в руке; вытесненный предмет - в пустой слот или на землю. */
    public void giveNuclearButton(Player p) {
        if (p == null || hasNukeButton(p)) return;
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        int slot = inv.getHeldItemSlot();
        org.bukkit.inventory.ItemStack held = inv.getItem(slot);
        inv.setItem(slot, createNukeButton());
        if (held != null && held.getType() != Material.AIR) {
            java.util.HashMap<Integer, org.bukkit.inventory.ItemStack> leftover = inv.addItem(held);
            for (org.bukkit.inventory.ItemStack rest : leftover.values())
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
        p.updateInventory();
    }

    /** Free-for-all: кнопку каждому живому игроку (кто первый запустит - тот победит). */
    public void giveNukeButtonToAllLiving() {
        for (Player p : getActivePlayers()) giveNuclearButton(p);
    }

    /** Открывает GUI выбора цели ракеты (3 панели). Открытие отложено на тик. */
    public void openNukeGui(final Player p) {
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                org.bukkit.inventory.Inventory inv = Bukkit.createInventory(null, 27, NUKE_GUI_TITLE);
                inv.setItem(11, setupItem(Material.LIGHT_BLUE_WOOL,
                    ChatColor.AQUA + "" + ChatColor.BOLD + "Вашингтон", ChatColor.GRAY + "Клик - запустить ракету сюда"));
                inv.setItem(13, setupItem(Material.YELLOW_WOOL,
                    ChatColor.YELLOW + "" + ChatColor.BOLD + "Киев", ChatColor.GRAY + "Клик - запустить ракету сюда"));
                inv.setItem(15, setupItem(Material.RED_WOOL,
                    ChatColor.RED + "" + ChatColor.BOLD + "Москва", ChatColor.GRAY + "Клик - запустить ракету сюда"));
                p.openInventory(inv);
            }
        });
    }

    /** Клик в GUI выбора цели - запускает ракету по выбранной цели. */
    public void handleNukeGuiClick(Player p, int slot) {
        final String target;
        if (slot == 11) target = "washington";
        else if (slot == 13) target = "kyiv";
        else if (slot == 15) target = "moscow";
        else return;
        final Player fp = p;
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                fp.closeInventory();
                plugin.getWardenManager().launchRocket(fp, target);
            }
        });
    }

    private String modeName(String mode) {
        if (mode.equals("duo")) return "Дуо";
        if (mode.equals("trio")) return "Трио";
        if (mode.equals("randomduo")) return "Рандом-дуо";
        if (mode.equals("randomtrio")) return "Рандом-трио";
        return "Одиночный";
    }

    /** Клик в GUI настройки. */
    public void handleSetupGuiClick(Player p, int slot) {
        handleSetupGuiClick(p, slot, false);
    }

    public void handleSetupGuiClick(Player p, int slot, boolean rightClick) {
        SetupChoice c = setupChoices.get(p.getUniqueId());
        if (c == null) { p.closeInventory(); return; }
        switch (slot) {
            case 10: c.mapId = nextReadyMap(c.mapId); break;
            case 11: c.lives = (c.lives >= 3) ? 1 : c.lives + 1; break;
            case 12: c.time  = (c.time == 15) ? 30 : 15; break;
            case 13: c.zir = !c.zir; break;
            case 14: c.glow = !c.glow; break;
            case 15: c.fast = !c.fast; break;
            case 16: c.air = !c.air; break;
            case 17: c.chaos = !c.chaos; break;
            case 22: c.mode = nextMode(c.mode); break;
            case 23: c.teamGlow = !c.teamGlow; break;
            case 21: {
                int max = dev.volansvo.svo.bots.BotManager.MAX_BOTS;
                c.bots = rightClick ? (c.bots <= 0 ? max : c.bots - 1) : (c.bots >= max ? 0 : c.bots + 1);
                break;
            }
            case 26:
                // Запуск.
                if (c.mapId == null) { p.sendMessage(ChatColor.RED + "Сначала выбери карту."); return; }
                final SetupChoice fc = c;
                final Player fp = p;
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override public void run() {
                        fp.closeInventory();
                        plugin.getMapManager().setActiveMap(fc.mapId);
                        requestStartMode(fp, fc.lives, fc.time, fc.zir, fc.glow, fc.fast, fc.air, fc.teamGlow, fc.chaos, fc.mode, fc.bots);
                    }
                });
                return;
            default: return;
        }
        // Перерисовываем меню после изменения.
        openSetupGuiNow(p, c);
    }

    private String nextReadyMap(String current) {
        List<String> ready = new ArrayList<String>();
        for (dev.volansvo.svo.maps.MapData m : plugin.getMapManager().getAll().values()) {
            if (m.isReady()) ready.add(m.getId());
        }
        if (ready.isEmpty()) return current;
        int idx = current == null ? -1 : ready.indexOf(current);
        return ready.get((idx + 1) % ready.size());
    }

    private String nextMode(String mode) {
        String[] order = {"solo", "duo", "trio", "randomduo", "randomtrio"};
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(mode)) return order[(i + 1) % order.length];
        }
        return "solo";
    }

    // ----- GUI команд -----

    public static final String TEAM_GUI_TITLE = ChatColor.DARK_GREEN + "Выбор команды";

    /** Открывает GUI выбора команды (всегда на следующем тике - безопасно для кликов). */
    public void openTeamGui(final Player viewer) {
        if (!formationActive) { viewer.sendMessage(ChatColor.RED + "Сейчас нет формирования команд."); return; }
        // ВАЖНО: открытие инвентаря НИКОГДА не делаем синхронно внутри обработки
        // InventoryClickEvent - это рассинхронит window id и кикнет игрока
        // ("network protocol error"). Поэтому открываем на следующем тике.
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                if (!formationActive || !viewer.isOnline()) return;
                openTeamGuiNow(viewer);
            }
        });
    }

    /** Немедленно строит и открывает GUI (вызывать только НЕ из inventory-события). */
    private void openTeamGuiNow(Player viewer) {
        if (!formationActive) return;
        // 20 команд -> сетка. 27 слотов хватает на 20 + индикаторы.
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(null, 27,
            TEAM_GUI_TITLE + " (" + teamModeName(formationTeamSize) + ", " + formationSecondsLeft + "с)");

        for (int i = 0; i < TEAM_COUNT; i++) {
            int size = teamSizeOf(i);
            String roster = rosterNames(i);
            boolean mine = teamIdOf(viewer.getUniqueId()) == i;
            boolean full = size >= formationTeamSize;
            boolean requested = joinRequests.containsKey(i)
                && joinRequests.get(i).contains(viewer.getUniqueId());

            org.bukkit.Material mat;
            if (mine) mat = org.bukkit.Material.LIME_BANNER;
            else if (full) mat = org.bukkit.Material.GRAY_BANNER;
            else if (requested) mat = org.bukkit.Material.YELLOW_BANNER;
            else mat = org.bukkit.Material.WHITE_BANNER;

            org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(mat);
            org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + teamName(i)
                    + ChatColor.GRAY + " (" + size + "/" + formationTeamSize + ")");
                List<String> lore = new ArrayList<String>();
                if (size == 0) {
                    lore.add(ChatColor.GRAY + "Пусто - нажми чтобы создать и вступить");
                } else if (teamSizeOf(i) - botsIn(i) == 0) {
                    lore.add(ChatColor.WHITE + roster);
                    lore.add(full ? (ChatColor.RED + "Команда заполнена")
                                  : (ChatColor.AQUA + "Только боты - нажми чтобы вступить"));
                } else {
                    lore.add(ChatColor.WHITE + roster);
                    if (mine) lore.add(ChatColor.GREEN + "Ты в этой команде (нажми чтобы выйти)");
                    else if (full) lore.add(ChatColor.RED + "Команда заполнена");
                    else if (requested) lore.add(ChatColor.YELLOW + "Заявка отправлена, ждём одобрения");
                    else lore.add(ChatColor.AQUA + "Нажми чтобы подать заявку");
                }
                if (viewer.getUniqueId().equals(formationInitiator)) {
                    lore.add(ChatColor.DARK_AQUA + "Хост: ПКМ - добавить бота, Shift+ПКМ - убрать");
                }
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            inv.setItem(i, item);
        }

        // Кнопка обновить / выйти из команды в нижнем ряду.
        org.bukkit.inventory.ItemStack info = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER);
        org.bukkit.inventory.meta.ItemMeta im = info.getItemMeta();
        if (im != null) {
            im.setDisplayName(ChatColor.YELLOW + "Без команды - попаду в случайную");
            List<String> lore = new ArrayList<String>();
            lore.add(ChatColor.GRAY + "Кто не выбрал команду к концу таймера,");
            lore.add(ChatColor.GRAY + "будет распределён случайно.");
            im.setLore(lore);
            info.setItemMeta(im);
        }
        inv.setItem(26, info);

        // «Начинаем»: когда её нажмут все в очереди, игра стартует, не дожидаясь таймера.
        int[] rc = readyCount();
        String readyLine = ChatColor.GRAY + "Готовы: " + ChatColor.WHITE + rc[0] + "/" + rc[1];
        inv.setItem(TEAM_GUI_READY_SLOT, formReady.contains(viewer.getUniqueId())
            ? setupItem(Material.EMERALD_BLOCK, ChatColor.GREEN + "" + ChatColor.BOLD + "Ты готов",
                readyLine, ChatColor.YELLOW + "Нажми ещё раз, чтобы отменить")
            : setupItem(Material.LIME_CONCRETE, ChatColor.GREEN + "" + ChatColor.BOLD + "Начинаем",
                readyLine, ChatColor.GRAY + "Когда нажмут все, игра стартует сразу"));

        viewer.openInventory(inv);
    }

    /** Обработка клика по слоту GUI (вызывается из listener). */
    public void handleTeamGuiClick(final Player viewer, int slot) {
        handleTeamGuiClick(viewer, slot, false, false);
    }

    public void handleTeamGuiClick(final Player viewer, int slot, boolean rightClick, boolean shift) {
        if (formationActive && rightClick && slot >= 0 && slot < TEAM_COUNT
                && viewer.getUniqueId().equals(formationInitiator)) {
            hostChangeBots(viewer, slot, shift);
            return;
        }
        if (!formationActive) {
            // Закрываем на следующем тике - НЕ синхронно внутри события клика.
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override public void run() { viewer.closeInventory(); }
            });
            return;
        }
        if (slot == TEAM_GUI_READY_SLOT) { setFormationReady(viewer, !formReady.contains(viewer.getUniqueId())); return; }
        if (slot < 0 || slot >= TEAM_COUNT) return; // клик не по команде
        int target = slot;

        int current = teamIdOf(viewer.getUniqueId());
        if (current == target) {
            // Выход из своей команды.
            leaveTeam(viewer);
            refreshGuiForQueue();
            return;
        }

        LinkedHashSet<UUID> set = formTeams.get(target);
        int size = (set == null) ? 0 : set.size(); // только люди

        if (size == 0 && teamSizeOf(target) >= formationTeamSize) {
            viewer.sendMessage(ChatColor.RED + "Команда " + teamName(target) + " заполнена (боты).");
            return;
        }
        if (size == 0) {
            // Пустая команда - создаём и сразу вступаем (без одобрения).
            leaveTeam(viewer); // выходим из прежней, если был
            LinkedHashSet<UUID> ns = new LinkedHashSet<UUID>();
            ns.add(viewer.getUniqueId());
            formTeams.put(target, ns);
            formPlayerTeam.put(viewer.getUniqueId(), target);
            viewer.sendMessage(ChatColor.GREEN + "Ты создал команду " + teamName(target) + ".");
            refreshGuiForQueue();
            return;
        }

        if (teamSizeOf(target) >= formationTeamSize) {
            viewer.sendMessage(ChatColor.RED + "Команда " + teamName(target) + " заполнена.");
            return;
        }

        // АБУЗ: нельзя проситься в другую команду, оставаясь в своей.
        // Сначала выйди (клик по своей команде) - чтобы не держать слот в двух местах.
        if (current != -1) {
            viewer.sendMessage(ChatColor.RED + "Сначала выйди из своей команды (" + teamName(current)
                + ") - кликни по ней в меню.");
            return;
        }

        // Подаём заявку на вступление - нужно одобрение участника команды.
        LinkedHashSet<UUID> reqs = joinRequests.get(target);
        if (reqs == null) { reqs = new LinkedHashSet<UUID>(); joinRequests.put(target, reqs); }
        if (!reqs.add(viewer.getUniqueId())) {
            viewer.sendMessage(ChatColor.YELLOW + "Заявка уже отправлена.");
            return;
        }
        viewer.sendMessage(ChatColor.GREEN + "Заявка в команду " + teamName(target) + " отправлена. Ждём одобрения.");
        // Уведомляем членов команды кликабельной кнопкой одобрения.
        for (UUID uid : set) {
            Player member = Bukkit.getPlayer(uid);
            if (member == null) continue;
            member.sendMessage(ChatColor.GOLD + viewer.getName() + ChatColor.WHITE
                + " хочет в твою команду " + teamName(target) + "!");
            String accept = "/svoteam approve " + viewer.getName();
            String tellraw = "tellraw " + member.getName() + " [\"\""
                + ",{\"text\":\"[Одобрить] \",\"color\":\"green\",\"bold\":true"
                + ",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"" + accept + "\"}"
                + ",\"hoverEvent\":{\"action\":\"show_text\",\"contents\":\"Принять " + viewer.getName() + "\"}}"
                + ",{\"text\":\"(или /svoteam approve " + viewer.getName() + ")\",\"color\":\"gray\"}]";
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), tellraw);
        }
        refreshGuiForQueue();
    }

    /** /svoteam approve <ник> - участник команды одобряет заявку. */
    public void approveJoin(Player approver, String applicantName) {
        if (!formationActive) { approver.sendMessage(ChatColor.RED + "Сейчас нет формирования команд."); return; }
        int teamId = teamIdOf(approver.getUniqueId());
        if (teamId == -1) { approver.sendMessage(ChatColor.RED + "Ты не в команде - не можешь одобрять."); return; }
        Player applicant = Bukkit.getPlayerExact(applicantName);
        if (applicant == null) { approver.sendMessage(ChatColor.RED + "Игрок оффлайн."); return; }
        // АБУЗ: одобрять можно только игрока, который ещё в очереди.
        if (!queue.contains(applicant.getUniqueId())) {
            approver.sendMessage(ChatColor.RED + applicant.getName() + " больше не в очереди.");
            return;
        }
        LinkedHashSet<UUID> reqs = joinRequests.get(teamId);
        if (reqs == null || !reqs.contains(applicant.getUniqueId())) {
            approver.sendMessage(ChatColor.RED + "От " + applicant.getName() + " нет заявки в твою команду.");
            return;
        }
        if (teamSizeOf(teamId) >= formationTeamSize) {
            approver.sendMessage(ChatColor.RED + "Команда уже заполнена.");
            return;
        }
        if (teamIdOf(applicant.getUniqueId()) != -1) {
            approver.sendMessage(ChatColor.RED + applicant.getName() + " уже в другой команде.");
            reqs.remove(applicant.getUniqueId());
            return;
        }
        // Вступление.
        formTeams.get(teamId).add(applicant.getUniqueId());
        formPlayerTeam.put(applicant.getUniqueId(), teamId);
        // ВСЕ заявки этого игрока в любые команды становятся недействительны.
        for (LinkedHashSet<UUID> rs : joinRequests.values()) rs.remove(applicant.getUniqueId());

        applicant.sendMessage(ChatColor.GREEN + "Тебя приняли в команду " + teamName(teamId) + "!");
        String roster = rosterNames(teamId);
        for (UUID uid : formTeams.get(teamId)) {
            Player mp = Bukkit.getPlayer(uid);
            if (mp != null) mp.sendMessage(ChatColor.AQUA + "Команда " + teamName(teamId) + ": "
                + ChatColor.WHITE + roster + ChatColor.GRAY + " (" + teamSizeOf(teamId) + "/" + formationTeamSize + ")");
        }
        refreshGuiForQueue();
    }

    /** Выводит игрока из его команды (внутреннее). */
    private void leaveTeam(Player p) {
        int teamId = teamIdOf(p.getUniqueId());
        if (teamId == -1) return;
        LinkedHashSet<UUID> set = formTeams.get(teamId);
        if (set != null) {
            set.remove(p.getUniqueId());
            if (set.isEmpty()) {
                formTeams.remove(teamId);
                // Команда исчезла - чистим висящие заявки в неё.
                joinRequests.remove(teamId);
            }
        }
        formPlayerTeam.remove(p.getUniqueId());
    }

    /** /svoteam leave */
    public void formationLeave(Player p) {
        if (!formationActive) { p.sendMessage(ChatColor.RED + "Сейчас нет формирования команд."); return; }
        if (teamIdOf(p.getUniqueId()) == -1) { p.sendMessage(ChatColor.RED + "Ты не в команде."); return; }
        leaveTeam(p);
        p.sendMessage(ChatColor.YELLOW + "Ты вышел из команды.");
        refreshGuiForQueue();
    }

    /** /svoteam (без аргументов) - открыть GUI. */
    public void sendFormationStatus(Player p) {
        openTeamGui(p);
    }

    /** Перерисовывает GUI всем, у кого он сейчас открыт. */
    private void refreshGuiForQueue() {
        for (Player p : getQueuedPlayers()) {
            org.bukkit.inventory.InventoryView view = p.getOpenInventory();
            if (view != null && view.getTitle() != null && view.getTitle().startsWith(TEAM_GUI_TITLE)) {
                openTeamGui(p);
            }
        }
    }

    private String rosterNames(int teamId) {
        LinkedHashSet<UUID> set = formTeams.get(teamId);
        List<String> names = new ArrayList<String>();
        if (set != null) {
            for (UUID uid : set) {
                Player p = Bukkit.getPlayer(uid);
                if (p != null) names.add(p.getName());
            }
        }
        int bots = botsIn(teamId);
        if (bots > 0) names.add("бот" + (bots > 1 ? " x" + bots : ""));
        return String.join(", ", names);
    }

    /** /svoteam ready (кнопка «Начинаем» в чате): игрок готов начинать. */
    public void formationReady(Player p) {
        if (!formationActive) { p.sendMessage(ChatColor.RED + "Сейчас нет формирования команд."); return; }
        if (formReady.contains(p.getUniqueId())) {
            int[] c = readyCount();
            p.sendMessage(ChatColor.YELLOW + "Ты уже готов, ждём остальных (" + c[0] + "/" + c[1] + ").");
            return;
        }
        setFormationReady(p, true);
    }

    /** Отметка «Начинаем» или её отмена. Когда готовы все в очереди, игра стартует сразу. */
    private void setFormationReady(Player p, boolean ready) {
        if (!formationActive || formationFinishing) return;
        if (!queue.contains(p.getUniqueId())) { p.sendMessage(ChatColor.RED + "Ты не в очереди."); return; }
        boolean changed = ready ? formReady.add(p.getUniqueId()) : formReady.remove(p.getUniqueId());
        if (!changed) return;
        int[] c = readyCount();
        broadcastQueue((ready ? ChatColor.GREEN + p.getName() + " готов начинать"
            : ChatColor.YELLOW + p.getName() + " пока не готов") + ChatColor.GRAY + " (" + c[0] + "/" + c[1] + ")");
        if (!checkFormationReady()) refreshGuiForQueue();
    }

    /** {готовы, всего} среди тех, кто сейчас в очереди. */
    private int[] readyCount() {
        int ready = 0, all = 0;
        for (Player q : getQueuedPlayers()) {
            all++;
            if (formReady.contains(q.getUniqueId())) ready++;
        }
        return new int[]{ready, all};
    }

    /** Все в очереди нажали «Начинаем»: заканчиваем формирование, не дожидаясь таймера. */
    private boolean checkFormationReady() {
        if (!formationActive || formationFinishing) return false;
        int[] c = readyCount();
        if (c[1] == 0 || c[0] < c[1]) return false;
        formationFinishing = true;
        if (formationTask != null) { formationTask.cancel(); formationTask = null; }
        broadcastQueue(ChatColor.GREEN + "" + ChatColor.BOLD + "Все готовы, начинаем!");
        // Следующим тиком: старт телепортирует и закрывает меню, а мы можем быть внутри клика по нему.
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() { if (formationActive) finishTeamFormation(); }
        });
        return true;
    }

    private String nameOf(UUID uid) {
        Player p = uid == null ? null : Bukkit.getPlayer(uid);
        return p != null ? p.getName() : "?";
    }

    /**
     * Завершает фазу формирования: добивает неполные/одиночные команды случайными
     * игроками без команды, фиксирует распределение и запускает голосование.
     */
    private void finishTeamFormation() {
        formationActive = false;
        plugin.getLogger().info("[СВО] finishTeamFormation: команд=" + formTeams.size()
            + " размер=" + formationTeamSize + " в очереди=" + queue.size());
        // Закрываем GUI у всех - на следующем тике, чтобы не пересечься с обработкой клика.
        final List<UUID> toClose = new ArrayList<UUID>(queue);
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                for (UUID uid : toClose) {
                    Player p = Bukkit.getPlayer(uid);
                    if (p == null) continue;
                    org.bukkit.inventory.InventoryView view = p.getOpenInventory();
                    if (view != null && view.getTitle() != null && view.getTitle().startsWith(TEAM_GUI_TITLE)) {
                        p.closeInventory();
                    }
                }
            }
        });
        // Список игроков без команды (онлайн, в очереди).
        purgeQueue();
        List<UUID> free = new ArrayList<UUID>();
        for (UUID uid : queue) {
            if (teamIdOf(uid) == -1 && Bukkit.getPlayer(uid) != null) free.add(uid);
        }
        Collections.shuffle(free);

        // Добиваем существующие неполные команды (места, занятые ботами хоста, считаются).
        for (Integer tid : formBots.keySet()) {
            if (!formTeams.containsKey(tid)) formTeams.put(tid, new LinkedHashSet<UUID>());
        }
        for (Integer tid : formTeams.keySet()) {
            LinkedHashSet<UUID> set = formTeams.get(tid);
            while (set.size() + botsIn(tid) < formationTeamSize && !free.isEmpty()) {
                UUID add = free.remove(0);
                set.add(add);
                formPlayerTeam.put(add, tid);
            }
        }
        // Оставшихся свободных собираем в новые команды.
        while (!free.isEmpty()) {
            int tid = formNextId++;
            LinkedHashSet<UUID> set = new LinkedHashSet<UUID>();
            for (int k = 0; k < formationTeamSize && !free.isEmpty(); k++) {
                UUID add = free.remove(0);
                set.add(add);
                formPlayerTeam.put(add, tid);
            }
            formTeams.put(tid, set);
        }

        // Фиксируем распределение: player -> уникальный номер команды (с 1).
        predeterminedTeams = new HashMap<UUID, Integer>();
        pendingBotTeams.clear();
        int num = 1;
        for (Integer tid : formTeams.keySet()) {
            for (UUID uid : formTeams.get(tid)) {
                predeterminedTeams.put(uid, num);
            }
            for (int b = 0; b < botsIn(tid); b++) pendingBotTeams.add(num);
            num++;
        }

        // Показываем итог.
        broadcastQueue(ChatColor.GOLD + "===== Команды сформированы =====");
        for (Integer tid : formTeams.keySet()) {
            broadcastQueue(ChatColor.AQUA + teamName(tid) + ": " + ChatColor.WHITE + rosterNames(tid));
        }

        // Голосование уже прошло ДО формирования - запускаем игру напрямую.
        VotingSession s = formationSession;
        formationSession = null;
        state = GameState.QUEUE;
        if (s == null) { predeterminedTeams = null; pendingBotTeams.clear(); return; }
        // Ботов ровно столько, сколько выбрали в настройках (расставить больше хост не может).
        while (pendingBotTeams.size() > s.bots) pendingBotTeams.remove(pendingBotTeams.size() - 1);
        startGame(s);
    }

    /** UUID инициатора из очереди (для отметки в фазе формирования). */
    private UUID getInitiatorFromQueue() {
        for (UUID uid : queue) return uid; // первый в очереди как «инициатор» по умолчанию
        return null;
    }

    private void broadcastQueue(String msg) {
        for (Player p : getQueuedPlayers()) p.sendMessage(msg);
    }

    private void cancelFormation() {
        if (formationActive) {
            for (Player p : getQueuedPlayers()) {
                org.bukkit.inventory.InventoryView view = p.getOpenInventory();
                if (view != null && view.getTitle() != null && view.getTitle().startsWith(TEAM_GUI_TITLE)) {
                    p.closeInventory();
                }
            }
        }
        formationActive = false;
        if (formationTask != null) { formationTask.cancel(); formationTask = null; }
        formationSession = null;
        formBots.clear();
        pendingBotTeams.clear();
        formPlayerTeam.clear();
        formTeams.clear();
        joinRequests.clear();
        formReady.clear();
        predeterminedTeams = null;
        if (state == GameState.TEAM_FORMATION) state = GameState.QUEUE;
    }

    // ----- Voting flow -----

    /**
     * Called by /svoplay. Starts a vote in chat with clickable [Да] button.
     * Initiator's vote is counted automatically (they ran the command).
     */
    public void requestStart(Player initiator, int lives, int timeMins, boolean withZir, boolean withGlow, boolean withFastZone, boolean withAirdrops, int teamSizeArg) {
        requestStart(initiator, lives, timeMins, withZir, withGlow, withFastZone, withAirdrops, false, false, teamSizeArg, false);
    }

    public void requestStart(Player initiator, int lives, int timeMins, boolean withZir, boolean withGlow, boolean withFastZone, boolean withAirdrops, boolean withTeamGlow, int teamSizeArg, boolean manualTeams) {
        requestStart(initiator, lives, timeMins, withZir, withGlow, withFastZone, withAirdrops, withTeamGlow, false, teamSizeArg, manualTeams);
    }

    public void requestStart(Player initiator, int lives, int timeMins, boolean withZir, boolean withGlow, boolean withFastZone, boolean withAirdrops, boolean withTeamGlow, boolean withChaos, int teamSizeArg, boolean manualTeams) {
        requestStart(initiator, lives, timeMins, withZir, withGlow, withFastZone, withAirdrops, withTeamGlow, withChaos, teamSizeArg, manualTeams, 0);
    }

    public void requestStart(Player initiator, int lives, int timeMins, boolean withZir, boolean withGlow, boolean withFastZone, boolean withAirdrops, boolean withTeamGlow, boolean withChaos, int teamSizeArg, boolean manualTeams, int bots) {
        if (initiator == null) return;
        if (state == GameState.ACTIVE || state == GameState.STARTING) {
            initiator.sendMessage(ChatColor.RED + "Игра уже идёт!");
            return;
        }

        purgeQueue();

        if (queue.size() + bots < 2) {
            initiator.sendMessage(ChatColor.RED + "Меньше 2 участников - не могу начать. Пускай встанут в очередь или добавь ботов.");
            return;
        }

        if (activeVote != null) {
            initiator.sendMessage(ChatColor.RED + "Уже идёт голосование. Дождись окончания.");
            return;
        }

        // Initiator must be in queue too
        if (!queue.contains(initiator.getUniqueId())) {
            initiator.sendMessage(ChatColor.RED + "Ты не в очереди. Встань в очередь сначала.");
            return;
        }

        plugin.getLogger().info("[СВО] requestStart от " + initiator.getName()
            + ": lives=" + lives + " time=" + timeMins + " teamSize=" + teamSizeArg + " manual=" + manualTeams);
        activeVote = new VotingSession(lives, timeMins, withZir, withGlow, withFastZone, withAirdrops, withTeamGlow, withChaos, teamSizeArg, manualTeams);
        activeVote.bots = bots;
        activeVote.initiator = initiator.getUniqueId();

        // Auto-vote for initiator
        activeVote.votes.add(initiator.getUniqueId());
        // Инициатору - барьер для отмены голосования.
        giveCancelVoteItem(initiator);

        // Broadcast clickable vote prompt to QUEUED players ONLY
        for (Player p : getQueuedPlayers()) {
            if (p.getUniqueId().equals(initiator.getUniqueId())) continue; // skip initiator, they already voted
            sendVotePrompt(p);
        }
        initiator.sendMessage(ChatColor.GREEN + "Твой голос засчитан автоматически (" + validVotes() + "/" + queue.size() + ").");

        // Auto-cancel after 60 seconds
        final VotingSession session = activeVote;
        new BukkitRunnable() {
            @Override
            public void run() {
                if (activeVote == session && state != GameState.ACTIVE && state != GameState.STARTING) {
                    activeVote = null;
                    removeCancelVoteItemFromAll();
                    plugin.getMapManager().clearActiveMap(); // idle -> дефолт svo
                    for (Player q : getQueuedPlayers()) {
                        q.sendMessage(ChatColor.RED + "Голосование отменено (вышло время).");
                    }
                }
            }
        }.runTaskLater(plugin, 1200L);

        checkVoteComplete();
    }

    /** Сообщение о голосовании с кнопкой [ДА] (и тем, кто встал в очередь, пока оно идёт). */
    private void sendVotePrompt(Player p) {
        VotingSession v = activeVote;
        if (v == null) return;
        Player init = v.initiator == null ? null : Bukkit.getPlayer(v.initiator);
        p.sendMessage("");
        p.sendMessage(ChatColor.GOLD + "===== Голосование за начало СВО =====");
        dev.volansvo.svo.maps.MapData voteMap = plugin.getMapManager().getActiveMap();
        String mapName = voteMap != null ? voteMap.getDisplayName() : "не выбрана";
        p.sendMessage(ChatColor.GRAY + "Карта: " + ChatColor.AQUA + mapName);
        p.sendMessage(ChatColor.GRAY + "Инициатор: " + ChatColor.WHITE + (init != null ? init.getName() : "?"));
        p.sendMessage(ChatColor.GRAY + "Жизней: " + ChatColor.WHITE + v.lives + ChatColor.GRAY + " | Время: " + ChatColor.WHITE + v.timeMins + " мин");
        p.sendMessage(ChatColor.GRAY + "Жириновский: " + boolStr(v.withZir) + ChatColor.GRAY + " | Подсветка: " + boolStr(v.withGlow));
        p.sendMessage(ChatColor.GRAY + "Быстрая зона: " + boolStr(v.withFastZone) + ChatColor.GRAY + " | Аирдропы: " + boolStr(v.withAirdrops));
        if (v.withChaos) p.sendMessage(ChatColor.GRAY + "Хаос: " + boolStr(true));
        if (v.teamSize > 1) p.sendMessage(ChatColor.GRAY + "Подсветка тиммейтов: " + boolStr(v.withTeamGlow));
        p.sendMessage(ChatColor.GRAY + "Режим: " + ChatColor.AQUA + fullModeName(v.teamSize, v.manualTeams));
        if (v.bots > 0) p.sendMessage(ChatColor.GRAY + "Боты: " + ChatColor.WHITE + v.bots);
        String tellraw = "tellraw " + p.getName() + " {\"text\":\"[ДА]\",\"color\":\"green\",\"bold\":true,\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/svovote\"},\"hoverEvent\":{\"action\":\"show_text\",\"contents\":\"Нажми чтобы проголосовать\"}}";
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), tellraw);
    }

    /** Called by /svovote. Returns true if vote was registered. */
    public boolean castVote(Player player) {
        if (activeVote == null) {
            player.sendMessage(ChatColor.RED + "Сейчас нет активного голосования.");
            return false;
        }
        // Голосуют все, кто сейчас в очереди: и те, кто встал в неё уже во время голосования
        // (раньше их не пускали, а голос требовался от всей очереди - голосование зависало).
        if (!queue.contains(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "Ты не в очереди.");
            return false;
        }
        if (activeVote.votes.contains(player.getUniqueId())) {
            player.sendMessage(ChatColor.YELLOW + "Ты уже проголосовал.");
            return false;
        }
        activeVote.votes.add(player.getUniqueId());
        // Notify only queued players
        for (Player q : getQueuedPlayers()) {
            q.sendMessage(ChatColor.GREEN + player.getName() + " проголосовал (" + validVotes() + "/" + queue.size() + ")");
        }
        checkVoteComplete();
        return true;
    }

    /** Голоса тех, кто ещё в очереди (ушедшие не считаются). */
    private int validVotes() {
        if (activeVote == null) return 0;
        int valid = 0;
        for (UUID uid : activeVote.votes) if (queue.contains(uid)) valid++;
        return valid;
    }

    private void checkVoteComplete() {
        if (activeVote == null) return;
        purgeQueue();

        // Count valid votes (must still be in queue with insvo tag)
        int valid = validVotes();
        // Required = ALL currently queued players must vote
        if (valid >= queue.size() && !queue.isEmpty() && queue.size() + activeVote.bots >= 2) {
            VotingSession s = activeVote;
            activeVote = null;
            removeCancelVoteItemFromAll();
            if (s.manualTeams) {
                // Голосование прошло - теперь ручное формирование команд (GUI), затем старт.
                startTeamFormation(s);
            } else {
                startGame(s);
            }
        }
    }

    private String boolStr(boolean b) {
        return b ? (ChatColor.GREEN + "вкл") : (ChatColor.RED + "выкл");
    }

    private String teamModeName(int size) {
        switch (size) {
            case 2: return "Дуо";
            case 3: return "Трио";
            default: return "Одиночный";
        }
    }

    /** Полное название режима с учётом ручной/рандомной командной раздачи. */
    private String fullModeName(int size, boolean manual) {
        if (size == 2) return manual ? "Дуо" : "Рандом-дуо";
        if (size == 3) return manual ? "Трио" : "Рандом-трио";
        return "Одиночный";
    }

    // ----- Start (actual game start, no world recreation) -----

    /** Номер попытки старта: колбэк клонирования мира от старой (отменённой) попытки ничего не делает. */
    private int startGeneration;

    private void startGame(final VotingSession s) {
        purgeQueue();
        if (queue.isEmpty() || queue.size() + s.bots < 2) {
            broadcastGame(ChatColor.RED + "Меньше 2 игроков - игра отменена.");
            return;
        }

        // Мир создаётся ТОЛЬКО сейчас (после голосования) под выбранную карту.
        final dev.volansvo.svo.maps.MapData map = plugin.getMapManager().getActiveMap();
        final String targetGW = plugin.getWorldManager().gameWorldNameFor(map);
        state = GameState.STARTING; // блокируем повторный старт пока клонируется мир
        final int gen = ++startGeneration;
        for (Player p : getQueuedPlayers()) {
            p.sendMessage(ChatColor.YELLOW + "Готовим карту "
                + (map != null ? map.getDisplayName() : "СВО") + "...");
        }
        plugin.getWorldManager().cloneMapWorld(map, new Runnable() {
            @Override public void run() {
                // Пока клонировался мир, старт отменили (/asvostop, все вышли) или начали заново.
                if (gen != startGeneration || state != GameState.STARTING) {
                    // Лишний мир убираем, только пока никакой игры нет (иначе это может быть её мир).
                    if ((state == GameState.IDLE || state == GameState.QUEUE) && Bukkit.getWorld(targetGW) != null) {
                        try { plugin.getWorldManager().deleteNamedWorld(targetGW); } catch (Throwable ignored) {}
                    }
                    return;
                }
                World gw = Bukkit.getWorld(targetGW);
                if (gw == null) {
                    abortStart(null, "Не удалось создать мир карты. Игра отменена.");
                    return;
                }
                // Для карты east: после создания мира размещаем поезда на всех точках.
                if (map != null && "east".equals(map.getId())) {
                    int[][] trainSpots = {
                        {500, 72, -100},
                        {850, 72, -950},
                        {244, 83, -783}
                    };
                    for (int[] t : trainSpots) {
                        try { gw.loadChunk(t[0] >> 4, t[2] >> 4, true); } catch (Throwable ignored) {}
                        String trainCmd = "train place " + targetGW + " " + t[0] + " " + t[1] + " " + t[2];
                        boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), trainCmd);
                        plugin.getLogger().info("[СВО] Поезд: '" + trainCmd + "' -> " + ok);
                    }

                    // Военная техника - синтаксис "<имя> place X Y Z <мир>" (мир ПОСЛЕДНИМ
                    // аргументом, в отличие от train, где мир идёт сразу после "place").
                    Object[][] vehicleSpots = {
                        {"kamaz",   371, 63, -705},
                        {"bpla",    373, 66, -715},
                        {"jet",     347, 63, -722},
                        {"airship", 424, 63, -761},
                        {"tank",    348, 63, -695},
                        {"pickup",  372, 63, -690}
                    };
                    for (Object[] v : vehicleSpots) {
                        String name = (String) v[0];
                        int vx = (Integer) v[1], vy = (Integer) v[2], vz = (Integer) v[3];
                        try { gw.loadChunk(vx >> 4, vz >> 4, true); } catch (Throwable ignored) {}
                        String vehCmd = name + " place " + vx + " " + vy + " " + vz + " " + targetGW;
                        boolean vok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), vehCmd);
                        plugin.getLogger().info("[СВО] Техника: '" + vehCmd + "' -> " + vok);
                    }
                }
                // doStartGame сам телепортирует всех участников в арену (spawn box).
                finishStartGame(s);
            }
        });
    }

    /** Финальная часть старта (мир уже создан): теги, поля раунда, doStartGame. */
    private void finishStartGame(VotingSession s) {
        purgeQueue();
        int humans = queue.size();
        if (humans < 1 || humans + s.bots < 2) {
            abortStart(plugin.getWorldManager().getGameWorld(), "Меньше 2 участников - игра отменена.");
            return;
        }
        // Один человек против ботов - игра в статистику не идёт (винрейт не меняется).
        statsCounted = humans >= 2;
        noHumansSinceTick = -1;

        // Боты входят на сервер и становятся в очередь как обычные игроки.
        if (s.bots > 0) {
            World gw0 = plugin.getWorldManager().getGameWorld();
            if (gw0 != null) {
                int bi = 0;
                for (Player b : plugin.getBotManager().spawnForGame(s.bots, gw0)) {
                    queue.add(b.getUniqueId());
                    if (predeterminedTeams != null && bi < pendingBotTeams.size()) {
                        predeterminedTeams.put(b.getUniqueId(), pendingBotTeams.get(bi));
                    }
                    bi++;
                }
                pendingBotTeams.clear();
            }
            if (!statsCounted) {
                for (UUID uid : queue) {
                    Player p = Bukkit.getPlayer(uid);
                    if (p != null && !isBot(uid)) p.sendMessage(ChatColor.GRAY
                        + "Игра с ботами без других игроков: статистика и винрейт не меняются.");
                }
            }
        }

        // ПЕРВЫМ ДЕЛОМ при удачном голосовании: IsGameSvo=1 и тег svoplayer всем в очереди.
        // Objectives уже созданы в onEnable - просто пишем значения.
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "scoreboard players set Kirqum IsGameSvo 1");
        for (UUID uid : queue) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                p.addScoreboardTag("svoplayer");
                // +1 к играм каждому человеку в очереди (если игра идёт в статистику)
                if (statsCounted && !isBot(uid)) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        "scoreboard players add " + p.getName() + " svogames 1");
                }
            }
        }

        this.startingLives    = s.lives;
        plugin.getLogger().info("[СВО] startGame: startingLives=" + startingLives + " (из голосования lives=" + s.lives + ")");
        this.timerTicks       = (s.timeMins == 30) ? 36000 : 18000;
        this.zirEnabled       = s.withZir;
        this.glowEnabled      = s.withGlow;
        this.fastZone         = s.withFastZone;
        this.airdropsEnabled  = s.withAirdrops;
        this.chaosEnabled     = s.withChaos;
        this.highlightTeammates = s.withTeamGlow;
        this.teamSize         = s.teamSize;
        this.airdropTimer     = AIRDROP_INTERVAL;
        this.glowTimer        = 4800;

        World gameWorld = plugin.getWorldManager().getGameWorld();
        if (gameWorld == null) {
            abortStart(null, "Мир игры не создан. Игра отменена.");
            return;
        }

        doStartGame(gameWorld);
    }

    /**
     * Старт сорвался после того, как мир склонирован или участники помечены: возвращаем людей
     * в очередь, снимаем метки игры, убираем ботов и лишний мир. Раньше такие отмены оставляли
     * склонированный мир, тег svoplayer, скрытые ники и состояние STARTING/IDLE при живой очереди.
     */
    private void abortStart(World clonedWorld, String reason) {
        plugin.getBotManager().removeAll();
        Set<UUID> back = new LinkedHashSet<UUID>(queue);
        for (UUID uid : players.keySet()) if (!isBot(uid)) back.add(uid);
        players.clear();
        queue.clear();
        clearSquadTeams();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "scoreboard players set Kirqum IsGameSvo 0");
        state = GameState.QUEUE; // giveStartItem не выдаёт предмет, пока идёт старт
        for (UUID uid : back) {
            Player p = Bukkit.getPlayer(uid);
            if (p == null || isBot(uid)) continue;
            p.removeScoreboardTag("svoplayer");
            showNameTag(p);
            p.sendMessage(ChatColor.RED + reason);
            if (p.getScoreboardTags().contains("insvo")) {
                queue.add(uid);
                giveStartItem(p);
            }
        }
        state = queue.isEmpty() ? GameState.IDLE : GameState.QUEUE;
        if (clonedWorld != null) {
            try { plugin.getWorldManager().deleteNamedWorld(clonedWorld.getName()); } catch (Throwable ignored) {}
        }
        // Как и при отмене голосования: карта больше не выбрана, лобби и рестарт - снова хаб.
        plugin.getMapManager().clearActiveMap();
    }

    private void doStartGame(World gameWorld) {
        plugin.getLogger().info("[СВО] doStartGame: мир=" + gameWorld.getName()
            + " в очереди=" + queue.size() + " teamSize=" + teamSize
            + " predetermined=" + (predeterminedTeams != null));
        state = GameState.STARTING;
        removeStartItemFromAll(); // игра началась - убираем предмет-«старт» у всех
        players.clear();
        sleptThisRound.clear(); // новая игра - забываем кровати прошлой
        respawnGrace.clear();
        pendingDropSlowFall.clear();
        winner = null;
        currentTick = timerTicks;

        for (UUID uid : queue) {
            Player p = Bukkit.getPlayer(uid);
            if (p == null) continue;
            SvoPlayer sp = plugin.getStatsManager().getOrCreate(p);
            sp.resetRound(startingLives);
            players.put(uid, sp);
            p.addScoreboardTag("svoplayer");
            hideNameTag(p); // ники не видны во время игры
        }
        queue.clear();

        if (players.size() < 2) {
            abortStart(gameWorld, "Меньше 2 игроков онлайн - игра отменена.");
            return;
        }

        // Раскидываем игроков по командам (Дуо/Трио) с выключенным friendly fire.
        assignTeams();

        plugin.getLootManager().placeAndFill(gameWorld, plugin.getMapManager().getActiveMap());

        WorldBorder wb = gameWorld.getWorldBorder();
        dev.volansvo.svo.maps.MapData bmap = plugin.getMapManager().getActiveMap();
        if (bmap != null && bmap.hasBorder()) {
            wb.setCenter(bmap.getBorderCenterX(), bmap.getBorderCenterZ());
            wb.setSize(bmap.getBorderSize());
        } else {
            wb.setCenter(plugin.getConfig().getDouble("worldborder.center_x"),
                         plugin.getConfig().getDouble("worldborder.center_z"));
            wb.setSize(plugin.getConfig().getDouble("worldborder.start_size"));
        }

        plugin.getBossbarManager().setupTimerBar(timerTicks);

        // Рандомный TP в spawn_box активной карты (или из config для дефолта).
        Random rng = new Random();
        int minX, maxX, minY, maxY, minZ, maxZ;
        dev.volansvo.svo.maps.MapData smap = plugin.getMapManager().getActiveMap();
        if (smap != null && smap.hasSpawnBox()) {
            minX = smap.getSpawnMinX(); maxX = smap.getSpawnMaxX();
            minY = smap.getSpawnY();    maxY = smap.getSpawnY();
            minZ = smap.getSpawnMinZ(); maxZ = smap.getSpawnMaxZ();
        } else {
            minX = plugin.getConfig().getInt("spawn_box.min_x", -1085);
            maxX = plugin.getConfig().getInt("spawn_box.max_x", -85);
            minY = plugin.getConfig().getInt("spawn_box.min_y", 310);
            maxY = plugin.getConfig().getInt("spawn_box.max_y", 310);
            minZ = plugin.getConfig().getInt("spawn_box.min_z", -36);
            maxZ = plugin.getConfig().getInt("spawn_box.max_z", 964);
        }

        // Базовая точка спавна на КАЖДУЮ команду - тиммейты падают рядом.
        // Для одиночек (teamId == -1) точка у каждого своя.
        final double spawnY = (maxY == minY) ? minY : (minY + rng.nextDouble() * (maxY - minY));
        Map<Integer, double[]> teamSpawn = new HashMap<Integer, double[]>();

        List<Player> active = getActivePlayers();
        for (Player p : active) {
            SvoPlayer sp = players.get(p.getUniqueId());
            int tid = (sp != null) ? sp.getTeamId() : -1;

            double x, z;
            if (tid >= 0) {
                double[] base = teamSpawn.get(tid);
                if (base == null) {
                    // Первый из команды задаёт базовую точку (с запасом от краёв для разлёта тиммейтов).
                    double bx = (minX + 6) + rng.nextDouble() * (maxX - minX - 12);
                    double bz = (minZ + 6) + rng.nextDouble() * (maxZ - minZ - 12);
                    base = new double[]{bx, bz};
                    teamSpawn.put(tid, base);
                }
                // Небольшой разброс вокруг базовой точки (+-4 блока), чтобы не падали в одну точку.
                x = base[0] + (rng.nextDouble() * 8 - 4);
                z = base[1] + (rng.nextDouble() * 8 - 4);
                // На всякий случай держим в пределах spawn_box.
                if (x < minX) x = minX; if (x > maxX) x = maxX;
                if (z < minZ) z = minZ; if (z > maxZ) z = maxZ;
            } else {
                x = minX + rng.nextDouble() * (maxX - minX);
                z = minZ + rng.nextDouble() * (maxZ - minZ);
            }

            p.teleport(new Location(gameWorld, x, spawnY, z, p.getLocation().getYaw(), p.getLocation().getPitch()));
            p.setGameMode(GameMode.SURVIVAL);
            p.getInventory().clear();
            clearCombatState(p); // против пре-баффов: снимаем зелья/огонь/абсорбцию, полное здоровье/сытость
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 800, 30, true, false));
            pendingDropSlowFall.add(p.getUniqueId());
            // Карта - соответствующая активной карте СВО (или дефолтная если нет)
            dev.volansvo.svo.maps.MapData mapData = plugin.getMapManager().getActiveMap();
            if (mapData != null && mapData.hasMapId()) {
                plugin.getLootManager().giveMap(p, mapData.getMapId());
            } else if (mapData == null) {
                plugin.getLootManager().giveMap(p); // легаси-дефолт (id 106) только если карты нет
            }
            // если карта активна, но без map_id (east) - миникарту не выдаём
            p.getInventory().addItem(new ItemStack(Material.MILK_BUCKET));
            if (statsCounted && !isBot(p.getUniqueId())) plugin.getStatsManager().getOrCreate(p).recordGameStart();
        }

        // Боты с картой видны на ней так же, как игроки.
        dev.volansvo.svo.maps.MapData botMap = plugin.getMapManager().getActiveMap();
        if (botMap == null) plugin.getBotManager().attachMap(106);
        else if (botMap.hasMapId()) plugin.getBotManager().attachMap(botMap.getMapId());

        gameWorld.setTime(6000);
        gameWorld.setStorm(false);
        gameWorld.setDifficulty(Difficulty.EASY);
        gameWorld.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        gameWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        // ВАЖНО: смерть не должна сохранять лут (мир клонируется из лобби, где keepInventory мог быть true).
        gameWorld.setGameRule(GameRule.KEEP_INVENTORY, false);

        // ВАЖНО: игру переводим в ACTIVE и запускаем таймер ДО спавна Жириновского.
        // Если спавн варда упадёт с ошибкой - таймер и зона всё равно будут работать.
        state = GameState.ACTIVE;
        setMarkerSign(gameWorld, "Игра идёт", "");
        dev.volansvo.svo.maps.MapData startedMap = plugin.getMapManager().getActiveMap();
        String startedName = startedMap != null ? startedMap.getDisplayName() : "СВО";
        broadcastGame(ChatColor.GOLD + "СВО началось на карте " + ChatColor.AQUA + startedName + ChatColor.GOLD + "!");
        broadcastGame(ChatColor.YELLOW + "Жизней: " + ChatColor.WHITE + startingLives
            + ChatColor.YELLOW + " | Время: " + ChatColor.WHITE + (timerTicks / 1200) + " мин"
            + ChatColor.YELLOW + " | Режим: " + ChatColor.WHITE + teamModeName(teamSize));
        startTickLoop();

        // Жириновский (Warden) - в try/catch, координаты из активной карты (или config).
        if (zirEnabled) {
            try {
                if (startedMap != null && startedMap.hasWarden()) {
                    plugin.getWardenManager().spawnWarden(gameWorld,
                        startedMap.getWardenX(), startedMap.getWardenY(), startedMap.getWardenZ());
                } else {
                    plugin.getWardenManager().spawnWarden(gameWorld);
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Не удалось заспавнить Жириновского: " + t);
            }
        }

        // Модификатор "Хаос" - метеоры, случайные взрывы/эффекты у игроков, ночные фантомы.
        if (chaosEnabled) {
            try {
                plugin.getChaosManager().start(gameWorld);
            } catch (Throwable t) {
                plugin.getLogger().warning("Не удалось запустить модификатор Хаос: " + t);
            }
        }
    }

    private void startTickLoop() {
        // Защита от двойного запуска: если старый луп ещё жив - гасим его.
        if (tickTask != null) { tickTask.cancel(); tickTask = null; }
        tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (state != GameState.ACTIVE) { cancel(); return; }
                currentTick--;

                // КАЖДЫЙ обработчик в своём try/catch. Иначе одно исключение
                // отменяет весь BukkitRunnable и таймер замораживается навсегда.
                safe("updateTimerProgress", new Runnable() { public void run() {
                    plugin.getBossbarManager().updateTimerProgress(currentTick, timerTicks); } });
                safe("borderShrink", new Runnable() { public void run() {
                    handleBorderShrink(); } });
                safe("slowFalling", new Runnable() { public void run() {
                    handleSlowFallingRemoval(); } });
                safe("glow", new Runnable() { public void run() {
                    handleGlow(); } });
                if (teamSize > 1 && currentTick % 8 == 0) {
                    // Боссбар ХП тиммейтов - всегда в командных режимах (не только с подсветкой).
                    safe("teamHpBars", new Runnable() { public void run() {
                        updateTeamHpBars(); } });
                    // Зелёные партиклы над тиммейтами - только в режиме с подсветкой.
                    if (highlightTeammates) safe("teamHighlight", new Runnable() { public void run() {
                        handleTeammateHighlight(); } });
                }
                if (airdropsEnabled) safe("airdrop", new Runnable() { public void run() {
                    handleAirdrop(); } });

                // Первые 200 тиков после старта - грейс-период, не проверяем
                final boolean inGrace = currentTick > timerTicks - 200;
                if (!inGrace) safe("checkDimensions", new Runnable() { public void run() {
                    checkDimensions(); } });
                if (currentTick % 20 == 0 && !inGrace) safe("checkWin", new Runnable() { public void run() {
                    checkWinCondition(); } });
                if (zirEnabled && currentTick % 20 == 0) safe("wardenForceKill", new Runnable() { public void run() {
                    checkWardenForceKill(); } });
                if (currentTick % 20 == 0) safe("banditStands", new Runnable() { public void run() {
                    handleBanditStands(); } });
                // Обновляем NIGHT_VISION спектаторам каждые 4 сек (длительность 15 сек)
                if (currentTick % 80 == 0) safe("spectators", new Runnable() { public void run() {
                    handleSpectators(); } });

                if (currentTick <= 0) {
                    timeRanOut = true;
                    safe("endGame", new Runnable() { public void run() { endGame(null); } });
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Выполняет шаг тик-лупа, гася любые исключения - чтобы таймер не замёрз. */
    private void safe(String label, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            plugin.getLogger().warning("Ошибка в тик-лупе СВО [" + label + "]: " + t);
        }
    }

    private void handleBorderShrink() {
        World gw = plugin.getWorldManager().getGameWorld();
        if (gw == null) return;
        WorldBorder wb = gw.getWorldBorder();
        // Итоговый размер зоны в конце ОДИНАКОВ для обоих режимов (940+940 блоков сужения) -
        // "быстрая зона" влияет только на скорость (сколько секунд едет до той же точки),
        // а не на то, где зона в итоге останавливается.
        for (long[] ph : zoneSchedule()) {
            if (currentTick != ph[0]) continue;
            wb.setSize(wb.getSize() - ph[1], ph[2]);
            if (ph[0] == 15000) broadcastGame(ChatColor.AQUA + "Зона начала сужаться!");
            else broadcastGame(ChatColor.AQUA + (fastZone ? "Зона быстро сужается!" : "Зона сужается"));
        }
    }

    /**
     * Расписание сужений зоны: {тик обратного отсчёта, на сколько уменьшается размер, секунд едет}.
     * Боты по нему заранее знают, когда и куда поедет зона.
     */
    public long[][] zoneSchedule() {
        long duration15 = fastZone ? 500L : 627L;    // рейт: on 1.88/с, off ~1.5/с (как раньше)
        long duration30 = fastZone ? 1000L : 1567L;  // рейт: on 0.94/с, off ~0.6/с (как раньше)
        return new long[][]{{30000, 940, duration30}, {15000, 940, duration15}};
    }

    /** Помечает игрока как получившего от НАС медленное падение (старт игры/возрождение в
     *  воздухе) - только для таких handleSlowFallingRemoval() снимает эффект по касанию земли. */
    public void markPendingDropSlowFall(UUID uid) {
        pendingDropSlowFall.add(uid);
    }

    /** Снимает медленное падение по касанию земли - но ТОЛЬКО у тех, кому эффект выдали МЫ
     *  (старт игры/возрождение в воздухе, см. pendingDropSlowFall), а не у любого игрока с
     *  этим эффектом - иначе честно выпитое зелье на пару минут срезалось бы первым же
     *  касанием земли вместо того чтобы отработать полную длительность. */
    private void handleSlowFallingRemoval() {
        if (pendingDropSlowFall.isEmpty()) return;
        Iterator<UUID> it = pendingDropSlowFall.iterator();
        while (it.hasNext()) {
            UUID uid = it.next();
            Player p = Bukkit.getPlayer(uid);
            if (p == null || !p.isOnline() || !isPlayerInGame(uid)) { it.remove(); continue; }
            if (!p.hasPotionEffect(PotionEffectType.SLOW_FALLING)) { it.remove(); continue; } // истёк сам
            Block below = p.getLocation().subtract(0, 1, 0).getBlock();
            if (below.getType().isSolid()) {
                p.removePotionEffect(PotionEffectType.SLOW_FALLING);
                it.remove();
            }
        }
    }

    private void handleGlow() {
        if (!glowEnabled) return;
        if (currentTick <= 2000) { applyGlowAll(); return; }
        glowTimer--;
        if (glowTimer <= 0) { applyGlowAll(); glowTimer = 4800; }
    }

    private void applyGlowAll() {
        for (Player p : getActivePlayers()) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 120, 1, false, false));
        }
    }

    /**
     * Зелёные партиклы над головами тиммейтов (аналог чёрных squid_ink у чёрной дыры).
     * Каждый игрок видит метки ТОЛЬКО над своими сокомандниками (отправляем партикл
     * персонально через viewer.spawnParticle). Работает только в командных режимах.
     */
    private void handleTeammateHighlight() {
        List<Player> active = getActivePlayers();
        for (Player viewer : active) {
            SvoPlayer vsp = players.get(viewer.getUniqueId());
            if (vsp == null || vsp.getTeamId() < 0) continue;
            for (Player mate : active) {
                if (mate.equals(viewer)) continue;
                SvoPlayer msp = players.get(mate.getUniqueId());
                if (msp == null || msp.getTeamId() != vsp.getTeamId()) continue;
                Location head = mate.getLocation().add(0, 1.4, 0); // на 1 блок ниже головы
                // force=true -> партикл шлётся на дальнюю дистанцию (до 512 блоков)
                // и игнорирует настройку частиц у клиента, иначе пропадает дальше ~32 блоков.
                // Те же count/spread/size, что и у красных партиклов топорщика-смертника
                // ХЕЗБОЛЛЫ (см. ChaosManager.tickHezBombers) - просто зелёным вместо красного.
                viewer.spawnParticle(Particle.DUST, head, 6, 0.3, 0.5, 0.3, 0,
                    new Particle.DustOptions(Color.LIME, 1.2f), true);
            }
        }
    }

    /** Обновляет персональные боссбары ХП тиммейтов - по одному НА ЖИВОГО игрока в команде
     *  (не считая его самого), видимому только ему. Своё ХП не пишем (оно и так на экране).
     *  Если в команде остался один живой (остальные погибли/вышли) - бара у него нет вовсе. */
    private void updateTeamHpBars() {
        Map<Integer, List<SvoPlayer>> byTeam = new HashMap<Integer, List<SvoPlayer>>();
        for (SvoPlayer sp : players.values()) {
            if (!sp.isAlive() || sp.getTeamId() < 0) continue;
            Player p = Bukkit.getPlayer(sp.getUuid());
            if (p == null || !p.isOnline()) continue;
            List<SvoPlayer> list = byTeam.get(sp.getTeamId());
            if (list == null) { list = new ArrayList<SvoPlayer>(); byTeam.put(sp.getTeamId(), list); }
            list.add(sp);
        }

        Set<UUID> stillNeeded = new HashSet<UUID>();
        for (List<SvoPlayer> mates : byTeam.values()) {
            if (mates.size() < 2) continue; // один в команде - бара не показываем
            for (SvoPlayer viewerSp : mates) {
                Player viewer = Bukkit.getPlayer(viewerSp.getUuid());
                if (viewer == null) continue;
                stillNeeded.add(viewerSp.getUuid());
                BossBar bar = teamHpBars.get(viewerSp.getUuid());
                if (bar == null) {
                    bar = Bukkit.createBossBar(ChatColor.GREEN + "Тиммейты", BarColor.GREEN, BarStyle.SOLID);
                    bar.setVisible(true);
                    // Добавляем зрителя лениво (не при assignTeams) - к этому моменту таймер-бар
                    // уже создан, боссбар ХП встаёт под ним (и под варден-баром, см. bumpTeamHpBars).
                    bar.addPlayer(viewer);
                    teamHpBars.put(viewerSp.getUuid(), bar);
                }

                StringBuilder title = new StringBuilder(ChatColor.GREEN + "Тиммейты: " + ChatColor.RESET);
                double minFrac = 1.0;
                boolean first = true;
                for (SvoPlayer mateSp : mates) {
                    if (mateSp.getUuid().equals(viewerSp.getUuid())) continue; // своё ХП не пишем
                    Player mate = Bukkit.getPlayer(mateSp.getUuid());
                    if (mate == null) continue;
                    double hp = mate.getHealth();
                    AttributeInstance maxAttr = mate.getAttribute(Attribute.MAX_HEALTH);
                    double maxHp = maxAttr != null ? maxAttr.getValue() : 20.0;
                    double frac = maxHp > 0 ? Math.max(0.0, Math.min(1.0, hp / maxHp)) : 0.0;
                    if (frac < minFrac) minFrac = frac;
                    if (!first) title.append(ChatColor.GRAY + " | ");
                    first = false;
                    title.append(ChatColor.WHITE).append(mate.getName())
                         .append(ChatColor.GRAY).append(" ").append(String.format("%.1f", hp))
                         .append(ChatColor.DARK_GRAY).append(" (").append(mateSp.getLives()).append(")");
                }
                bar.setTitle(title.toString());
                bar.setProgress(minFrac);
            }
        }

        // Убираем бар у тех, кому он больше не нужен (остался один в команде / вышел из игры).
        Iterator<Map.Entry<UUID, BossBar>> it = teamHpBars.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, BossBar> e = it.next();
            if (!stillNeeded.contains(e.getKey())) {
                e.getValue().removeAll();
                e.getValue().setVisible(false);
                it.remove();
            }
        }
    }

    /** Пере-регистрирует зрителей боссбаров ХП тиммейтов - сдвигает их визуально вниз
     *  относительно только что созданного боссбара Жириновского/Нетаньяху (варден спавнится
     *  позже старта игры, а клиент рендерит боссбары в порядке их добавления игроку). */
    public void bumpTeamHpBars() {
        for (BossBar bar : teamHpBars.values()) {
            List<Player> viewers = new ArrayList<Player>(bar.getPlayers());
            for (Player p : viewers) bar.removePlayer(p);
            for (Player p : viewers) bar.addPlayer(p);
        }
    }

    private void handleAirdrop() {
        airdropTimer--;
        if (airdropTimer <= 0) {
            World gw = plugin.getWorldManager().getGameWorld();
            if (gw != null) {
                plugin.getAirdropManager().triggerAirdrop(gw, getActivePlayers());
            }
            airdropTimer = AIRDROP_INTERVAL;
        }
    }

    /**
     * Аналог: execute as @e[tag=svorazboist] at @s if entity @a[distance=..30,tag=svoplayer,gamemode=survival]
     *   - спавнит pillager + vindicator на стенде, удаляет стенд.
     */
    private void handleBanditStands() {
        World gw = plugin.getWorldManager().getGameWorld();
        if (gw == null) return;
        Random rng = new Random();
        double pHealth = plugin.getConfig().getDouble("bandit.pillager_health", 60.0);
        double pDmg    = plugin.getConfig().getDouble("bandit.pillager_attack_damage", 20.0);
        double vHealth = plugin.getConfig().getDouble("bandit.vindicator_health", 20.0);

        // На карте eastgame_ бандиты со стенда - "ХАМАС", на карте svogame_ - "ЛНРовец".
        boolean hamas = gw.getName().equalsIgnoreCase("eastgame_");
        boolean lnr   = gw.getName().equalsIgnoreCase("svogame_");
        String pilName = hamas ? (ChatColor.RED + "ХАМАС") : lnr ? (ChatColor.RED + "ЛНРовец") : (ChatColor.RED + "Разбойник");
        String vinName = hamas ? (ChatColor.DARK_RED + "ХАМАС") : lnr ? (ChatColor.DARK_RED + "ЛНРовец") : (ChatColor.DARK_RED + "Карательный отряд");

        for (Entity e : new ArrayList<Entity>(gw.getEntities())) {
            if (!(e instanceof ArmorStand)) continue;
            if (!e.getScoreboardTags().contains("svorazboist")) continue;

            Location standLoc = e.getLocation();
            boolean trigger = false;
            for (Entity nearby : gw.getNearbyEntities(standLoc, 30, 30, 30)) {
                if (!(nearby instanceof Player)) continue;
                Player p = (Player) nearby;
                if (p.getGameMode() != GameMode.SURVIVAL) continue;
                if (!p.getScoreboardTags().contains("svoplayer")) continue;
                trigger = true;
                break;
            }
            if (!trigger) continue;

            // Кол-во топорщиков/арбалетчиков - из PDC стенда (задано /asvorazboi при постановке),
            // иначе дефолт как раньше (1 топорщик, 1-2 арбалетчика).
            org.bukkit.persistence.PersistentDataContainer pdc = ((ArmorStand) e).getPersistentDataContainer();
            Integer storedVind = pdc.get(new org.bukkit.NamespacedKey(plugin, RAZBOI_VIND_KEY),
                org.bukkit.persistence.PersistentDataType.INTEGER);
            Integer storedPil = pdc.get(new org.bukkit.NamespacedKey(plugin, RAZBOI_PIL_KEY),
                org.bukkit.persistence.PersistentDataType.INTEGER);
            int vindCount = (storedVind != null) ? storedVind : 1;
            int pilCount  = (storedPil  != null) ? storedPil  : (rng.nextInt(2) + 1);

            for (int i = 0; i < vindCount; i++) {
                Vindicator vin = (Vindicator) gw.spawnEntity(standLoc, EntityType.VINDICATOR);
                AttributeInstance vAttr = vin.getAttribute(Attribute.MAX_HEALTH);
                if (vAttr != null) { vAttr.setBaseValue(vHealth); vin.setHealth(vHealth); }
                vin.addScoreboardTag("svobandit");
                vin.setRemoveWhenFarAway(false);
                vin.setCustomName(vinName);
                vin.setCustomNameVisible(true);
            }

            for (int i = 0; i < pilCount; i++) {
                Pillager pil = (Pillager) gw.spawnEntity(standLoc, EntityType.PILLAGER);
                AttributeInstance pAttr = pil.getAttribute(Attribute.MAX_HEALTH);
                if (pAttr != null) { pAttr.setBaseValue(pHealth); pil.setHealth(pHealth); }
                AttributeInstance pDmgAttr = pil.getAttribute(Attribute.ATTACK_DAMAGE);
                if (pDmgAttr != null) pDmgAttr.setBaseValue(pDmg);
                pil.addScoreboardTag("svobandit");
                pil.setRemoveWhenFarAway(false);
                pil.setCustomName(pilName);
                pil.setCustomNameVisible(true);
            }

            // Удаляем стенд - больше не сработает
            e.remove();
        }
    }

    /**
     * Обновляет NIGHT_VISION (без партиклов) для всех игроков в режиме SPECTATOR,
     * которые числятся в players. Срок 300 тиков (15 сек), обновляется каждые 4 сек.
     */
    private void handleSpectators() {
        for (UUID uid : players.keySet()) {
            Player p = Bukkit.getPlayer(uid);
            if (p == null) continue;
            if (p.getGameMode() != GameMode.SPECTATOR) continue;
            p.addPotionEffect(new PotionEffect(
                PotionEffectType.NIGHT_VISION, 300, 0, true, false, false));
        }
    }

    private void checkWardenForceKill() {
        if (!plugin.getWardenManager().isWardenAlive()) return;
        long alive = 0;
        for (SvoPlayer sp : players.values()) if (sp.isAlive()) alive++;
        if (alive == 1) plugin.getWardenManager().forceKillWarden();
    }

    private void checkDimensions() {
        World gw = plugin.getWorldManager().getGameWorld();
        if (gw == null) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, SvoPlayer> e : new HashMap<UUID, SvoPlayer>(players).entrySet()) {
            UUID uid = e.getKey();
            if (!e.getValue().isInGame()) { dimensionMismatchTicks.remove(uid); continue; }
            // Игрок только что потерял жизнь и респавнится - не трогаем пока действует грейс.
            Long grace = respawnGrace.get(uid);
            if (grace != null) {
                if (now < grace) { dimensionMismatchTicks.remove(uid); continue; }
                respawnGrace.remove(uid);
            }
            Player p = Bukkit.getPlayer(uid);
            boolean mismatch = (p == null || !p.getWorld().equals(gw));
            if (!mismatch) {
                dimensionMismatchTicks.remove(uid);
                continue;
            }
            if (p != null && isBot(uid)) { returnBotToArena(uid); continue; }
            // Требуем НЕСКОЛЬКО тиков подряд вне игрового мира, а не одно "моргание" -
            // иначе сильный взрыв (метеор/фантом в Хаосе) с мощным knockback-ом мог на миг
            // сбить это условие и ложно списать жизнь игроку, который на самом деле жив
            // и никуда не делся (жизнь снималась, а респавна/телепорта не происходило).
            int streak = dimensionMismatchTicks.containsKey(uid) ? dimensionMismatchTicks.get(uid) + 1 : 1;
            if (streak >= DIMENSION_MISMATCH_THRESHOLD) {
                dimensionMismatchTicks.remove(uid);
                eliminatePlayer(uid, false);
            } else {
                dimensionMismatchTicks.put(uid, streak);
            }
        }
    }

    private void checkWinCondition() {
        // Не завершать игру в первые 200 тиков - даём игрокам приземлиться
        if (state == GameState.ACTIVE && currentTick > timerTicks - 200) return;

        // Все люди выбыли, дерутся одни боты: даём досмотреть 30 секунд, потом заканчиваем,
        // победитель - живой бот с наибольшим числом убийств.
        if (plugin.getBotManager().count() > 0) {
            boolean humanAlive = false;
            SvoPlayer bestBot = null;
            for (SvoPlayer sp : players.values()) {
                if (!sp.isAlive()) continue;
                if (!isBot(sp.getUuid())) { humanAlive = true; break; }
                if (bestBot == null || sp.getRoundKills() > bestBot.getRoundKills()) bestBot = sp;
            }
            if (humanAlive) noHumansSinceTick = -1;
            else if (bestBot != null) {
                if (noHumansSinceTick < 0) {
                    noHumansSinceTick = currentTick;
                    broadcastGame(ChatColor.GRAY + "Живых игроков не осталось. Боты доигрывают 30 секунд.");
                } else if (noHumansSinceTick - currentTick >= 600) {
                    endGame(bestBot.getUuid());
                    return;
                }
            }
        }

        if (teamSize <= 1) {
            // Одиночный - победа когда остался <=1 живой.
            long alive = 0;
            UUID lastAlive = null;
            for (SvoPlayer sp : players.values()) {
                if (sp.isAlive()) { alive++; lastAlive = sp.getUuid(); }
            }
            if (alive <= 1) endGame(lastAlive);
            return;
        }

        // Командный режим - победа когда живые игроки остались только в ОДНОЙ команде.
        Set<Integer> aliveTeams = new HashSet<Integer>();
        UUID anyAlive = null;
        int aliveCount = 0;
        for (SvoPlayer sp : players.values()) {
            if (!sp.isAlive()) continue;
            aliveCount++;
            anyAlive = sp.getUuid();
            aliveTeams.add(sp.getTeamId());
        }
        // Осталась максимум одна команда (или никого) - игра окончена.
        if (aliveTeams.size() <= 1) {
            // Победитель для записи статистики/титула - любой живой из оставшейся команды.
            endGame(aliveCount > 0 ? anyAlive : null);
        }
    }

    /**
     * Перезаполняет (рандомит заново) все сундуки на активной карте.
     * Вызывается после смерти каждого игрока - выжившим даётся свежий лут.
     */
    public void refreshChests() {
        if (state != GameState.ACTIVE) return;
        World gw = plugin.getWorldManager().getGameWorld();
        if (gw == null) return;
        plugin.getLootManager().placeAndFill(gw, plugin.getMapManager().getActiveMap());
        broadcastActive(ChatColor.GREEN + "Сундуки обновлены!");
    }

    // ===== Combat-log / уход из арены / античит-сброс =====

    /** Кто последним нанёс урон игроку и когда (для засчёта килла при combat-log). */
    private final Map<UUID, UUID> lastDamager = new HashMap<UUID, UUID>();
    private final Map<UUID, Long> lastDamageTime = new HashMap<UUID, Long>();
    private static final long COMBAT_TAG_MS = 15000; // 15с - окно "был в бою"

    /** Вызывается из EntityListener при уроне игрок-игроку. */
    public void recordCombat(UUID victim, UUID attacker) {
        if (victim == null || attacker == null || victim.equals(attacker)) return;
        lastDamager.put(victim, attacker);
        lastDamageTime.put(victim, System.currentTimeMillis());
    }

    /**
     * Кто бил игрока последним за 15 секунд (враг, участник игры) или null. Нужен, когда смерть
     * не от прямого удара (взрыв, плагинное оружие, дрон, падение после удара): getKiller() тогда
     * пуст, и убийство раньше никому не засчитывалось.
     */
    public UUID recentAttacker(UUID victim) {
        UUID a = lastDamager.get(victim);
        Long t = lastDamageTime.get(victim);
        if (a == null || t == null || System.currentTimeMillis() - t > COMBAT_TAG_MS) return null;
        if (sameTeam(a, victim) || !isPlayerInGame(a)) return null;
        return a;
    }

    /** Смерть засчитана - прошлый бой не тянется в следующую жизнь. */
    public void clearCombatTag(UUID victim) {
        lastDamager.remove(victim);
        lastDamageTime.remove(victim);
    }

    /** Кому засчитана последняя смерть игрока (для ботов: месть, реплики). */
    private final Map<UUID, UUID> lastKiller = new HashMap<UUID, UUID>();

    public void noteKill(UUID victim, UUID killer) {
        if (killer == null) lastKiller.remove(victim); else lastKiller.put(victim, killer);
    }

    /** Убийца этой смерти (читается один раз: следующая смерть того же игрока - уже другая). */
    public UUID killerOf(UUID victim) { return lastKiller.remove(victim); }

    /**
     * Полный сброс боевого состояния игрока (против пре-баффов): снимает все зелья,
     * огонь, падение, восстанавливает здоровье/сытость/воздух, выключает полёт.
     */
    public void clearCombatState(Player p) {
        if (p == null) return;
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setExhaustion(0f);
        try {
            org.bukkit.attribute.AttributeInstance hp = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (hp != null) p.setHealth(hp.getValue());
        } catch (Throwable ignored) {}
        p.setRemainingAir(p.getMaximumAir());
        if (p.getGameMode() != GameMode.CREATIVE) { p.setAllowFlight(false); p.setFlying(false); }
    }

    /**
     * Combat-log: игрок ВЫШЕЛ во время игры, находясь в игровом мире = честная смерть.
     * Дропает его инвентарь на месте (кроме ядерной кнопки - ею рулит WardenManager) и
     * засчитывает килл последнему, кто бил его недавно. Выбывание делает вызывающий код.
     */
    public void handleCombatLog(Player player) {
        if (state != GameState.ACTIVE) return;
        World gw = plugin.getWorldManager().getGameWorld();
        if (gw == null || !player.getWorld().equals(gw)) return;
        UUID uid = player.getUniqueId();
        Location loc = player.getLocation();
        for (ItemStack it : player.getInventory().getContents()) {
            if (it == null || it.getType() == Material.AIR) continue;
            if (isNukeButton(it)) continue; // кнопку не дропаем тут - её обрабатывает onNukeOwnerLeft
            gw.dropItemNaturally(loc, it.clone());
        }
        player.getInventory().clear();
        creditCombatKill(uid, player.getName());
        broadcastGame(ChatColor.RED + player.getName() + ChatColor.GRAY + " вышел в бою - засчитано как смерть.");
        lastDamager.remove(uid);
        lastDamageTime.remove(uid);
    }

    /** Засчитывает килл последнему дамагеру, если он бил жертву недавно и он враг. */
    private void creditCombatKill(UUID victimUid, String victimName) {
        UUID killerUid = lastDamager.get(victimUid);
        Long t = lastDamageTime.get(victimUid);
        if (killerUid == null || t == null) return;
        if (System.currentTimeMillis() - t > COMBAT_TAG_MS) return;
        if (sameTeam(killerUid, victimUid)) return;
        Player killer = Bukkit.getPlayer(killerUid);
        if (killer == null || !isPlayerInGame(killerUid)) return;
        SvoPlayer ksp = players.get(killerUid);
        boolean counts = countsForStats(killerUid, victimUid);
        if (ksp != null) {
            if (counts) { ksp.recordKill(); plugin.getStatsManager().save(ksp); }
            else ksp.recordRoundKill();
        }
        if (counts) Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            "scoreboard players add " + killer.getName() + " svokills 1");
        killer.sendMessage(ChatColor.GOLD + "Засчитано убийство (combat log): " + ChatColor.YELLOW + victimName);
    }

    /**
     * Участник ПОКИНУЛ игровой мир во время игры (телепортом/командой) = честная смерть:
     * полное выбывание. Лут не дропаем (игрок уже не в арене), но из раунда он выбывает -
     * нельзя уйти от смерти телепортом.
     */
    public void handleArenaLeave(UUID uid) {
        if (state != GameState.ACTIVE) return;
        if (!isPlayerInGame(uid)) return;
        // Бота мог утащить чужой плагин (спавн при входе и т.п.) - возвращаем, а не выбиваем.
        if (isBot(uid)) { returnBotToArena(uid); return; }
        Player p = Bukkit.getPlayer(uid);
        if (p != null) {
            p.sendMessage(ChatColor.RED + "Ты покинул арену СВО - засчитано как смерть.");
            creditCombatKill(uid, p.getName());
        }
        broadcastGame(ChatColor.RED + (p != null ? p.getName() : "Игрок")
            + ChatColor.GRAY + " покинул арену - выбыл из игры.");
        forceEliminate(uid);
    }

    /**
     * Полное выбывание независимо от числа жизней (для выхода игрока с сервера).
     * Обнуляет жизни и прогоняет через стандартный путь окончательного выбывания.
     */
    public void forceEliminate(UUID uid) {
        SvoPlayer sp = players.get(uid);
        if (sp == null || !sp.isInGame()) return;
        sp.setLives(1); // чтобы eliminatePlayer пошёл по ветке окончательного выбывания
        respawnGrace.remove(uid);
        // Вышедший игрок не должен числиться в команде - снимаем принадлежность.
        clearPlayerTeamMembership(uid, sp);
        eliminatePlayer(uid, false);
        // Если ушёл владелец ядерной кнопки - кнопка достаётся всем живым (кто первый - победит).
        plugin.getWardenManager().onNukeOwnerLeft(uid);
    }

    /** Публичный хук: снять командную принадлежность с любого вышедшего игрока. */
    public void clearTeamMembershipOnQuit(UUID uid) {
        clearPlayerTeamMembership(uid, players.get(uid));
    }

    /**
     * Снимает с игрока всю командную принадлежность: scoreboard svoteam=0,
     * вывод из боевой scoreboard-команды и сброс SvoPlayer.teamId.
     * Вызывается при выходе игрока и не оставляет следов в командах.
     */
    private void clearPlayerTeamMembership(UUID uid, SvoPlayer sp) {
        String name = null;
        Player p = Bukkit.getPlayer(uid);
        if (p != null) name = p.getName();
        else if (sp != null) name = sp.getName();
        if (name != null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "scoreboard players set " + name + " svoteam 0");
            org.bukkit.scoreboard.Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            for (org.bukkit.scoreboard.Team t : new ArrayList<org.bukkit.scoreboard.Team>(board.getTeams())) {
                if (t.getName().startsWith(SQUAD_TEAM_PREFIX) && t.hasEntry(name)) t.removeEntry(name);
            }
        }
        if (sp != null) sp.setTeamId(-1);
    }

    public void eliminatePlayer(UUID uid, boolean byDeath) {
        SvoPlayer sp = players.get(uid);
        if (sp == null || !sp.isInGame()) return;

        // ЗАЩИТА ОТ ДВОЙНОГО ВЫБЫВАНИЯ:
        // если игрок ТОЛЬКО ЧТО потерял жизнь и сейчас респавнится (активен грейс),
        // то повторная смерть в этом окне НЕ должна сжигать вторую жизнь.
        // Иначе при immediate-respawn падение/зона/лава добивали игрока сразу же
        // после первой смерти, и обе жизни сгорали за один "реальный" заход.
        if (byDeath && respawnGrace.containsKey(uid)
                && System.currentTimeMillis() < respawnGrace.get(uid)) {
            plugin.getLogger().info("[СВО] eliminatePlayer " + sp.getName()
                + " - повторная смерть в грейсе, жизнь не снимаем (lives=" + sp.getLives() + ")");
            return;
        }

        // ДИАГНОСТИКА: что реально с жизнями в момент выбывания.
        plugin.getLogger().info("[СВО] eliminatePlayer " + sp.getName()
            + " byDeath=" + byDeath + " lives=" + sp.getLives()
            + " startingLives=" + startingLives);

        if (sp.getLives() > 1) {
            sp.setLives(sp.getLives() - 1);
            // Защита от мгновенного повторного выбывания: пока игрок респавнится,
            // он на миг оказывается вне игрового мира - dimension-check не должен
            // его за это сразу же выбить.
            respawnGrace.put(uid, System.currentTimeMillis() + RESPAWN_GRACE_MS);
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                p.sendTitle(ChatColor.DARK_RED + "ПОРАЖЕНИЕ", ChatColor.YELLOW + "Осталось жизней: " + sp.getLives(), 10, 60, 10);
                // Краткая защита после респавна, чтобы вторая смерть не наступила
                // мгновенно (падение/зона/лава). Неуязвимость + резист на 3 сек.
                grantRespawnProtection(uid);
            }
            // Локация респавна выбирается в PlayerRespawnEvent (кровать или рандом).
            return;
        }

        sp.setLives(-1);
        sp.setInGame(false);
        if (isBot(uid)) plugin.getBotManager().onEliminated(uid);

        Player p = Bukkit.getPlayer(uid);
        if (p != null) {
            p.setGameMode(GameMode.SPECTATOR);
            // Night vision без партиклов - регулярно обновляется в handleSpectators()
            p.addPotionEffect(new PotionEffect(
                PotionEffectType.NIGHT_VISION, 320, 0, true, false, false));
            p.sendTitle(ChatColor.DARK_RED + "ПОРАЖЕНИЕ", ChatColor.YELLOW + "Ты выбыл", 10, 60, 10);
            p.removeScoreboardTag("svoplayer");
            p.removeScoreboardTag("nearzir");
            p.removeScoreboardTag("insvo");
            p.removeScoreboardTag("bombmann");

            // ТП к живому игроку чуть позже (после того как отработает респавн)
            final UUID fuid = uid;
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override public void run() {
                    Player pl = Bukkit.getPlayer(fuid);
                    if (pl == null || pl.getGameMode() != GameMode.SPECTATOR) return;
                    List<Player> live = getActivePlayers();
                    if (!live.isEmpty()) pl.teleport(live.get(0).getLocation());
                }
            }, 5L);
        }
        checkWinCondition();
    }

    /**
     * Даёт игроку краткую защиту после респавна (потеря жизни в режиме 2 жизней):
     * 3 сек неуязвимости + Resistance + сытость, чтобы он не умер мгновенно
     * от падения/зоны/лавы и не сжёг вторую жизнь сразу.
     */
    private void grantRespawnProtection(UUID uid) {
        // Через 2 тика - после того как отработает PlayerRespawnEvent и ТП.
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() {
                Player p = Bukkit.getPlayer(uid);
                if (p == null) return;
                p.setNoDamageTicks(60); // 3 сек ванильной неуязвимости
                p.setFireTicks(0);
                p.setFallDistance(0f);
                p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 60, 4, true, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, 60, 255, true, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.INSTANT_HEALTH, 1, 3, true, false, false)); // 255 переполнялось и не лечило
            }
        }, 2L);
    }

    public void endGame(UUID winnerUUID) {
        // Жёсткая защита: НЕ завершать игру в первые 200 тиков после старта.
        // Любой нелегитимный путь завершения (фантомные смерти, dimension check и т.д.) блокируется.
        if (state == GameState.ACTIVE && currentTick > timerTicks - 200) return;
        endGameNow(winnerUUID);
    }

    /** Завершает игру немедленно, минуя грейс-период. Только для админских команд. */
    public void endGameNow(UUID winnerUUID) {
        if (state == GameState.ENDING || state == GameState.IDLE) return;
        state = GameState.ENDING;

        // Захватываем игровой мир ДО очистки активной карты (иначе getGameWorld станет дефолтным).
        final World endedGameWorld = plugin.getWorldManager().getGameWorld();
        final String endedGameName = plugin.getWorldManager().getGameWorldName();
        // Карту тоже захватываем заранее - clearActiveMap() ниже обнулит её.
        dev.volansvo.svo.maps.MapData endedMap = plugin.getMapManager().getActiveMap();
        final boolean eastMap = endedMap != null && "east".equals(endedMap.getId());

        // Сбрасываем IsGameSvo чтобы внешние системы знали что игра закончилась
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "scoreboard players set Kirqum IsGameSvo 0");
        plugin.getMapManager().clearActiveMap(); // после этого лобби = хаб svo (дефолт)

        if (tickTask != null) { tickTask.cancel(); tickTask = null; }
        try { plugin.getChaosManager().stop(); } catch (Throwable ignored) {}
        plugin.getBossbarManager().hideTimerBar();
        this.winner = winnerUUID;

        if (winnerUUID != null) {
            SvoPlayer sp = players.get(winnerUUID);
            if (sp != null) {
                // Список победителей: в командном режиме - вся команда победителя,
                // в одиночном - только он сам.
                List<SvoPlayer> winners = new ArrayList<SvoPlayer>();
                if (teamSize > 1 && sp.getTeamId() >= 0) {
                    for (SvoPlayer other : players.values()) {
                        if (other.getTeamId() == sp.getTeamId()) winners.add(other);
                    }
                } else {
                    winners.add(sp);
                }

                for (SvoPlayer w : winners) {
                    if (isBot(w.getUuid())) { try { plugin.getBotManager().onWin(w.getUuid()); } catch (Throwable ignored) {} }
                    if (!statsCounted || isBot(w.getUuid())) continue; // боты и игры против ботов не в статистике
                    w.recordWin();
                    plugin.getStatsManager().save(w);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        "scoreboard players add " + w.getName() + " svowins 1");
                    // Пересчитываем svowinrate по сглаженной формуле (как в /svostats).
                    updateWinrateScore(w.getName());
                }

                StringBuilder names = new StringBuilder();
                for (int i = 0; i < winners.size(); i++) {
                    if (i > 0) names.append(", ");
                    names.append(winners.get(i).getName());
                }
                String title = (winners.size() > 1)
                    ? "Команда побеждает!"
                    : sp.getName() + " побеждает!";
                for (Player p : getAllSvoPlayers()) {
                    p.sendTitle(ChatColor.LIGHT_PURPLE + title, ChatColor.GOLD + names.toString(), 10, 100, 20);
                }
                broadcastGame(ChatColor.LIGHT_PURPLE + (winners.size() > 1 ? "Победила команда: " : "Победитель: ")
                    + ChatColor.WHITE + names.toString());
            }
        } else if (timeRanOut) {
            // Время вышло - все проиграли. На east, если в конце осталось 2+ живых
            // (чистого победителя не определилось) - миротворческая тема вместо "ДНР".
            int aliveCount = getActivePlayers().size();
            String failMsg = (eastMap && aliveCount >= 2)
                ? "Миротворческая миссия закончилась неудачей"
                : "Украина захватила ДНР";
            for (Player p : getAllSvoPlayers()) {
                p.sendTitle(ChatColor.DARK_RED + "ПОРАЖЕНИЕ", ChatColor.YELLOW + failMsg, 10, 120, 20);
            }
            broadcastGame(ChatColor.DARK_RED + "" + ChatColor.BOLD + failMsg + "!");
        } else {
            broadcastGame(ChatColor.GRAY + "Игра остановлена.");
        }

        if (endedGameWorld != null) setMarkerSign(endedGameWorld, "Спасибо", "за игру!");

        pendingEnding = new Runnable() {
            @Override
            public void run() {
                plugin.getBotManager().removeAll();
                teleportAllToLobby(endedGameWorld);
                cleanupRound();
                // Игровой мир временный (создаётся только при голосовании) - удаляем оба (svogame_/eastgame_).
                if (endedGameName != null) {
                    plugin.getWorldManager().deleteNamedWorld(endedGameName);
                }
                state = GameState.IDLE;
            }
        };
        // Сервер выключается: задачу уже не поставить (исключение), уборку доделает forceStop.
        if (plugin.isEnabled()) endingTask = Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() { runPendingEnding(); }
        }, 100L);
    }

    /** Уборка после конца игры (через 5 сек после итогов) и её задача. */
    private Runnable pendingEnding;
    private BukkitTask endingTask;

    /**
     * Доделать уборку после конца игры прямо сейчас. Нужна, когда ждать нельзя: сервер
     * выключается (отложенные задачи уже не выполнятся) или админ остановил игру - раньше
     * /asvostop сначала стирал список игроков, и отложенная уборка потом никого не возвращала в лобби.
     */
    private void runPendingEnding() {
        Runnable r = pendingEnding;
        pendingEnding = null;
        if (endingTask != null) { endingTask.cancel(); endingTask = null; }
        if (r == null) return;
        try {
            r.run();
        } catch (Throwable t) {
            plugin.getLogger().warning("[СВО] Ошибка уборки после игры: " + t);
            state = GameState.IDLE;
        }
    }

    public void forceStop() { endGameNow(null); runPendingEnding(); }

    /**
     * Проверяет, остался ли в игре хоть один онлайн-участник.
     * Если все вышли - аварийно останавливает и полностью очищает игру.
     * Запускается с задержкой в 1 тик, чтобы только что вышедший игрок
     * уже не числился онлайн.
     */
    public void checkAbandoned() {
        if (!isGameRunning() && state != GameState.QUEUE) return;
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() {
                // Игра уже завершилась за этот тик?
                if (state == GameState.IDLE || state == GameState.ENDING) return;

                boolean anyOnline = false;
                // Участники текущей игры
                for (UUID uid : players.keySet()) {
                    if (isBot(uid)) continue;
                    Player p = Bukkit.getPlayer(uid);
                    if (p != null && p.isOnline()) { anyOnline = true; break; }
                }
                // Игроки в очереди (фаза лобби)
                if (!anyOnline) {
                    for (UUID uid : queue) {
                        Player p = Bukkit.getPlayer(uid);
                        if (p != null && p.isOnline()) { anyOnline = true; break; }
                    }
                }

                if (!anyOnline) {
                    if (state == GameState.ACTIVE && plugin.getBotManager().count() > 0) {
                        // Людей не осталось, одни боты: заканчиваем игру обычным путём
                        // (боты уходят, мир удаляется, всё сбрасывается).
                        plugin.getLogger().info("Все игроки СВО вышли, остались только боты - завершаем игру.");
                        endGameNow(null);
                        return;
                    }
                    plugin.getLogger().info("Все игроки СВО вышли - аварийная остановка и полная очистка.");
                    emergencyCleanup();
                }
            }
        }, 1L);
    }

    /**
     * Аварийная полная очистка: останавливает таймер, снимает теги/босбары/варда/аирдроп,
     * сбрасывает IsGameSvo, активную карту и всё состояние в IDLE.
     * Используется когда все игроки вышли или после краша сервера.
     */
    public void emergencyCleanup() {
        if (tickTask != null) { tickTask.cancel(); tickTask = null; }
        timeRanOut = false;
        glowTimer = 4800;
        airdropTimer = 0;
        respawnGrace.clear();
        sleptThisRound.clear();
        yanikLastStrike.clear();
        plugin.getMapManager().clearActiveMap();
        // purgeAllTagsAndBars сам сбрасывает players/queue/activeVote/state=IDLE/IsGameSvo
        purgeAllTagsAndBars();
        plugin.getStatsManager().saveAll();
    }

    /**
     * Стартовая очистка при включении плагина (после краша/перезапуска).
     * Сбрасывает IsGameSvo, активную карту и чистит теги у всех онлайн-игроков.
     * Сами игроки дочищаются в onJoin при заходе (теги хранятся в их playerdata).
     */
    public void startupCleanup() {
        cancelFormation();
        state = GameState.IDLE;
        players.clear();
        queue.clear();
        activeVote = null;
        winner = null;
        currentTick = 0;
        respawnGrace.clear();
        sleptThisRound.clear();
        yanikLastStrike.clear();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "scoreboard players set Kirqum IsGameSvo 0");
        plugin.getMapManager().clearActiveMap();
        clearSquadTeams(); // снимаем боевые команды прошлой игры
        // ВАЖНО: чистим ТОЛЬКО игроков с тегами СВО. Обычных игроков в обычном
        // мире не трогаем - иначе им сотрёт инвентарь при рестарте/перезагрузке.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (hasSvoTags(p)) stripSvoState(p, true);
        }
        plugin.getLogger().info("Стартовая очистка СВО выполнена.");
    }

    /**
     * Снимает с игрока ВСЕ следы СВО: теги, эффекты, инвентарь+броню,
     * возвращает ник, ставит ADVENTURE. Если teleport=true - кидает на стенд лобби.
     * Используется при возвращении игрока после краша, когда игра не идёт.
     */
    public void stripSvoState(Player p, boolean teleport) {
        p.removeScoreboardTag("svoplayer");
        p.removeScoreboardTag("nearzir");
        p.removeScoreboardTag("insvo");
        p.removeScoreboardTag("bombmann");
        p.removeScoreboardTag("airdropped");
        p.removeScoreboardTag("toairdrop");
        showNameTag(p);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.getInventory().clear();
        p.getInventory().setHelmet(null);
        p.getInventory().setChestplate(null);
        p.getInventory().setLeggings(null);
        p.getInventory().setBoots(null);
        if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.SURVIVAL) {
            p.setGameMode(GameMode.ADVENTURE);
        }
        if (teleport) {
            World lobby = plugin.getWorldManager().getLobbyWorld();
            if (lobby != null) {
                Location loc = plugin.getWorldManager().getRestartLocation(lobby);
                if (loc != null) p.teleport(loc);
            }
        }
    }

    /**
     * Снимает ТОЛЬКО SVO-теги, не трогая инвентарь/режим/телепорт.
     * Для случая, когда осиротевший тег оказался у игрока в ПОСТОРОННЕМ мире -
     * там нельзя сносить инвентарь (это не игрок СВО, теги навесил кто-то извне).
     */
    public void stripSvoTagsOnly(Player p) {
        p.removeScoreboardTag("svoplayer");
        p.removeScoreboardTag("nearzir");
        p.removeScoreboardTag("insvo");
        p.removeScoreboardTag("bombmann");
        p.removeScoreboardTag("airdropped");
        p.removeScoreboardTag("toairdrop");
        showNameTag(p);
    }

    /**
     * Есть ли у игрока СИЛЬНЫЕ маркеры СВО (для определения "осиротевшего" игрока).
     * ТОЛЬКО svoplayer/insvo - их ставит сам плагин участникам. Раньше сюда входили
     * generic-теги (bombmann/nearzir/airdropped/toairdrop), которые используются ещё и
     * командными блоками - из-за коллизии посторонний игрок попадал под SVO-очистку.
     */
    public boolean hasSvoTags(Player p) {
        Set<String> t = p.getScoreboardTags();
        return t.contains("svoplayer") || t.contains("insvo");
    }

    public void forceStopAndDelete() {
        endGameNow(null);
        runPendingEnding(); // вернуть всех в лобби, пока список игроков ещё цел
        // Жёсткая чистка - всё что endGameNow мог пропустить
        purgeAllTagsAndBars();
        new BukkitRunnable() {
            @Override
            public void run() {
                purgeAllTagsAndBars();      // ещё раз после задержки чтобы точно
                plugin.getWorldManager().deleteGameWorld();
            }
        }.runTaskLater(plugin, 120L);
    }

    /**
     * Полная чистка состояния:
     * - снимает теги svoplayer/nearzir/insvo со ВСЕХ онлайн игроков (не только из players)
     * - убирает боссбары
     * - чистит warden + airdrop entities
     * - сбрасывает state в IDLE
     * - чистит queue, players, голосование
     * - IsGameSvo=0
     * Используется для /asvostop чтобы зачистить любые висящие хвосты.
     */
    public void purgeAllTagsAndBars() {
        plugin.getBotManager().removeAll();
        lastKiller.clear();
        lastDamager.clear();
        lastDamageTime.clear();
        // Теги у всех онлайн игроков (на случай если есть осиротевшие)
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.removeScoreboardTag("svoplayer");
            p.removeScoreboardTag("nearzir");
            p.removeScoreboardTag("insvo");
            p.removeScoreboardTag("airdropped");
            p.removeScoreboardTag("toairdrop");
            showNameTag(p);
            p.removeScoreboardTag("bombmann");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "scoreboard players set " + p.getName() + " svoteam 0");
        }
        // Боссбары
        plugin.getBossbarManager().cleanup();
        // Warden
        plugin.getWardenManager().removeWarden();
        // Airdrop
        plugin.getAirdropManager().cleanupEntities();
        // Боевые команды Дуо/Трио
        clearSquadTeams();
        // IsGameSvo
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "scoreboard players set Kirqum IsGameSvo 0");
        // Формирование команд (если шло)
        cancelFormation();
        // Состояние
        if (tickTask != null) { tickTask.cancel(); tickTask = null; }
        players.clear();
        queue.clear();
        activeVote = null;
        winner = null;
        currentTick = 0;
        state = GameState.IDLE;
    }

    /** Ищет арморстенд с заданным тегом в любом загруженном мире. Возвращает его локацию. */
    private Location findStand(String tag) {
        for (World w : Bukkit.getWorlds()) {
            for (org.bukkit.entity.Entity e : w.getEntitiesByClass(org.bukkit.entity.ArmorStand.class)) {
                if (e.getScoreboardTags().contains(tag)) {
                    Location loc = e.getLocation();
                    w.loadChunk(loc.getBlockX() >> 4, loc.getBlockZ() >> 4, true);
                    return loc;
                }
            }
        }
        return null;
    }

    /**
     * Точка возврата в лобби - та же, куда телепортирует всех участников по окончании игры
     * (стенд standtpspawn2, с фолбэком на лобби svo). Используется и в /svolobby.
     */
    public Location getLobbyReturnLocation() {
        Location rl = findStand("standtpspawn2");
        if (rl == null) {
            World lobby = plugin.getWorldManager().getLobbyWorld();
            if (lobby != null) {
                rl = plugin.getWorldManager().getRestartLocation(lobby);
                if (rl == null) rl = lobby.getSpawnLocation();
            }
        }
        return rl;
    }

    private void teleportAllToLobby(World endedGameWorld) {
        // Точка возврата - арморстенд с тегом standtpspawn2 (в любом загруженном мире).
        Location rl = findStand("standtpspawn2");
        if (rl == null) {
            // Фолбэк - лобби svo (стенд svorestart / спавн), чтобы никогда не оставить null.
            World lobby = plugin.getWorldManager().getLobbyWorld();
            if (lobby != null) {
                rl = plugin.getWorldManager().getRestartLocation(lobby);
                if (rl == null) rl = lobby.getSpawnLocation();
            }
        }
        if (rl == null) return;
        final Location restartLoc = rl;
        final World gameWorld = endedGameWorld;

        // Собираем уникальный сет: всех из players + тех кто физически в gameWorld
        // НО ТОЛЬКО участников СВО (с тегами). Посторонних в игровом мире не трогаем,
        // иначе им сотрёт инвентарь.
        Set<Player> toCleanup = new LinkedHashSet<Player>(getAllSvoPlayers());
        if (gameWorld != null) {
            for (Player p : gameWorld.getPlayers()) {
                if (hasSvoTags(p)) toCleanup.add(p);
            }
        }

        final Set<UUID> targetUuids = new HashSet<UUID>();
        for (Player p : toCleanup) {
            targetUuids.add(p.getUniqueId());
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.getInventory().setHelmet(null);
            p.getInventory().setChestplate(null);
            p.getInventory().setLeggings(null);
            p.getInventory().setBoots(null);
            for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
            p.removeScoreboardTag("svoplayer");
            p.removeScoreboardTag("nearzir");
            p.removeScoreboardTag("insvo");
            p.removeScoreboardTag("bombmann");
            // Сбрасываем номер команды чтобы не утёк в следующую (одиночную) игру.
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "scoreboard players set " + p.getName() + " svoteam 0");
            showNameTag(p); // возвращаем ник
            p.teleport(restartLoc);
        }

        // Сторонние ЗРИТЕЛИ (наблюдатели со стороны без тегов СВО) - тоже выгоняем из
        // игрового мира при конце игры, но БЕЗ очистки инвентаря (у них свои вещи).
        if (gameWorld != null) {
            for (Player p : new ArrayList<Player>(gameWorld.getPlayers())) {
                if (targetUuids.contains(p.getUniqueId())) continue;     // участник - уже обработан
                if (p.getGameMode() != GameMode.SPECTATOR) continue;     // трогаем только зрителей
                targetUuids.add(p.getUniqueId());                        // чтобы страховка дотолкала
                p.setGameMode(GameMode.ADVENTURE);
                p.teleport(restartLoc);
            }
        }

        // СТРАХОВКА: иногда первый телепорт не срабатывает (асинхронная загрузка
        // чанка, игрок ещё в спектаторе). Несколько раз проверяем, что эти КОНКРЕТНЫЕ
        // игроки покинули игровой мир, и доталкиваем оставшихся. Чужих не трогаем.
        if (gameWorld != null && plugin.isEnabled()) { // при выключении сервера задачу уже не поставить
            new BukkitRunnable() {
                int tries = 0;
                @Override public void run() {
                    tries++;
                    boolean anyLeft = false;
                    for (UUID uid : targetUuids) {
                        Player p = Bukkit.getPlayer(uid);
                        if (p == null) continue;
                        if (!p.getWorld().equals(gameWorld)) continue; // уже в лобби - ок
                        anyLeft = true;
                        p.setGameMode(GameMode.ADVENTURE);
                        p.teleport(restartLoc);
                    }
                    if (!anyLeft || tries >= 5) { cancel(); }
                }
            }.runTaskTimer(plugin, 10L, 10L);
        }
    }

    private void cleanupRound() {
        plugin.getWardenManager().removeWarden();
        plugin.getAirdropManager().cleanupEntities();
        plugin.getBossbarManager().cleanup(); // полностью убираем все боссбары
        plugin.getStatsManager().saveAll();
        clearSquadTeams();        // убираем боевые команды Дуо/Трио + обнуляем их svoteam
        resetAllSvoteamScores();  // ГАРАНТИЯ: ни у кого онлайн не остаётся командной принадлежности
        players.clear();
        queue.clear();
        winner = null;
        currentTick = 0;
        glowTimer = 4800;
        airdropTimer = 0;
        activeVote = null;
        timeRanOut = false;
        dimensionMismatchTicks.clear();
        lastKiller.clear();
        lastDamager.clear();
        lastDamageTime.clear();
    }

    /**
     * Ставит табличку-маркер на -578 312 453 в игровом мире.
     * Снизу и сверху всегда ставит барьеры.
     */
    private void setMarkerSign(World gw, String line0, String line1) {
        // Координаты таблички от карты svo. На других картах этот чанк часто не сгенерирован,
        // и его синхронная генерация вешала сервер на 10+ секунд прямо на старте игры.
        if (!gw.isChunkGenerated(-578 >> 4, 453 >> 4)) return;
        gw.getBlockAt(-578, 311, 453).setType(Material.BARRIER);
        gw.getBlockAt(-578, 313, 453).setType(Material.BARRIER);
        Block b = gw.getBlockAt(-578, 312, 453);
        b.setType(Material.OAK_SIGN);
        if (b.getState() instanceof Sign) {
            Sign sign = (Sign) b.getState();
            sign.getSide(Side.FRONT).setLine(0, line0);
            sign.getSide(Side.FRONT).setLine(1, line1);
            sign.update(true);
        }
    }

    public void broadcastGame(String msg) {
        for (Player p : getAllSvoPlayers()) p.sendMessage(msg);
    }

    /** Сообщение только активным (живым) игрокам СВО. Используется для аирдропа. */
    public void broadcastActive(String msg) {
        for (Player p : getActivePlayers()) p.sendMessage(msg);
    }

    public List<Player> getActivePlayers() {
        List<Player> result = new ArrayList<Player>();
        for (SvoPlayer sp : players.values()) {
            if (sp.isAlive()) {
                Player p = Bukkit.getPlayer(sp.getUuid());
                if (p != null) result.add(p);
            }
        }
        return result;
    }

    public List<Player> getAllSvoPlayers() {
        List<Player> result = new ArrayList<Player>();
        for (UUID uid : players.keySet()) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) result.add(p);
        }
        return result;
    }

    public SvoPlayer getSvoPlayer(UUID uid) { return players.get(uid); }


    /** Отмечает, что игрок поспал на кровати в текущей игре. */
    public void markSleptThisRound(UUID uid) { sleptThisRound.add(uid); }

    /** Спал ли игрок на кровати в текущей игре. */
    public boolean hasSleptThisRound(UUID uid) { return sleptThisRound.contains(uid); }

    /**
     * Рандомная точка в spawn_box (Y=304), но ОБЯЗАТЕЛЬНО внутри текущей зоны
     * (world border). Иначе при сужении зоны игрок с 2 жизнями мог респавнится
     * за барьером и сразу умереть от урона зоны.
     */
    public Location getRandomSpawnLocation(World gw) {
        Random rng = new Random();
        double bMinX, bMaxX, bMinZ, bMaxZ; int y;
        dev.volansvo.svo.maps.MapData smap = plugin.getMapManager().getActiveMap();
        if (smap != null && smap.hasSpawnBox()) {
            bMinX = smap.getSpawnMinX(); bMaxX = smap.getSpawnMaxX();
            bMinZ = smap.getSpawnMinZ(); bMaxZ = smap.getSpawnMaxZ();
            y = smap.getSpawnY();
        } else {
            bMinX = plugin.getConfig().getInt("spawn_box.min_x", -1085);
            bMaxX = plugin.getConfig().getInt("spawn_box.max_x", -85);
            bMinZ = plugin.getConfig().getInt("spawn_box.min_z", -36);
            bMaxZ = plugin.getConfig().getInt("spawn_box.max_z", 964);
            y     = plugin.getConfig().getInt("spawn_box.min_y", 304);
        }

        // Пересекаем spawn_box с текущей зоной (с запасом от края, чтобы точно внутри).
        WorldBorder wb = gw.getWorldBorder();
        double half = wb.getSize() / 2.0 - 4.0; // -4 блока запас от барьера
        if (half > 0) {
            double cx = wb.getCenter().getX();
            double cz = wb.getCenter().getZ();
            bMinX = Math.max(bMinX, cx - half);
            bMaxX = Math.min(bMaxX, cx + half);
            bMinZ = Math.max(bMinZ, cz - half);
            bMaxZ = Math.min(bMaxZ, cz + half);
        }

        // Если пересечение пустое/перевёрнутое (spawn_box целиком вне зоны) -
        // респавним в центре зоны.
        if (bMinX > bMaxX || bMinZ > bMaxZ) {
            return new Location(gw, wb.getCenter().getX(), y, wb.getCenter().getZ());
        }

        double x = bMinX + rng.nextDouble() * (bMaxX - bMinX);
        double z = bMinZ + rng.nextDouble() * (bMaxZ - bMinZ);
        return new Location(gw, x, y, z);
    }
    /** Возвращает бота в зону высадки игрового мира. */
    private void returnBotToArena(UUID uid) {
        Player p = Bukkit.getPlayer(uid);
        World gw = plugin.getWorldManager().getGameWorld();
        if (p == null || gw == null) return;
        p.teleport(getRandomSpawnLocation(gw));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 800, 30, true, false));
        markPendingDropSlowFall(uid);
    }

    /** Бот ли это (боты не ведут статистику). */
    public boolean isBot(UUID uid) {
        return plugin.getBotManager() != null && plugin.getBotManager().isBot(uid);
    }

    /** Идёт ли убийство в постоянную статистику: игра засчитывается и обе стороны - люди. */
    public boolean countsForStats(UUID killer, UUID victim) {
        return statsCounted && !isBot(killer) && !isBot(victim);
    }

    public boolean isPlayerInGame(UUID uid) {
        SvoPlayer sp = players.get(uid);
        return sp != null && sp.isInGame();
    }

    public GameState getState() { return state; }
    public int getCurrentTick() { return currentTick; }
    public int getTimerTicks() { return timerTicks; }
    public int getStartingLives() { return startingLives; }
    public boolean isZirEnabled() { return zirEnabled; }
    public boolean isGameActive() { return state == GameState.ACTIVE; }

    // Inner class for vote state
    private static class VotingSession {
        final int lives;
        final int timeMins;
        final boolean withZir, withGlow, withFastZone, withAirdrops, withTeamGlow, withChaos;
        final int teamSize;
        final boolean manualTeams; // true = после голосования ручное формирование команд (Дуо/Трио)
        final Set<UUID> votes = new HashSet<UUID>();
        UUID initiator;            // кто начал голосование (может отменить его барьером)
        int bots;                  // сколько ботов добавить в игру

        VotingSession(int lives, int timeMins, boolean withZir, boolean withGlow, boolean withFastZone, boolean withAirdrops, boolean withTeamGlow, boolean withChaos, int teamSize, boolean manualTeams) {
            this.lives = lives;
            this.timeMins = timeMins;
            this.withZir = withZir;
            this.withGlow = withGlow;
            this.withFastZone = withFastZone;
            this.withAirdrops = withAirdrops;
            this.withTeamGlow = withTeamGlow;
            this.withChaos = withChaos;
            this.teamSize = teamSize;
            this.manualTeams = manualTeams;
        }
    }
}
