package dev.volansvo.svo.bots;

import dev.volansvo.svo.VolanSVO;
import dev.volansvo.svo.bots.nms.BotNms;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Боты для СВО: создаёт их к началу раунда, ведёт их мозги каждый тик,
 * пересылает им события (урон, выстрелы рядом, смерть) и убирает после игры.
 *
 * Бот на сервере - обычный игрок: в табе, в getOnlinePlayers, получает урон, лутает
 * и стреляет через те же события, что и человек. Отличия от человека держим здесь:
 * без сообщений о входе/выходе, без статистики, автоматический респавн.
 */
public final class BotManager implements Listener {

    public static final int MAX_BOTS = 10;

    private static final String[] DEFAULT_NAMES = {
        "Vanya_2011", "dimon4ik", "KRUTOY_PVP", "Sasha_Pro", "artem_kill", "Zhenya_YT", "TankistRU",
        "nikitos228", "EgorPvP", "maks_ultra", "Lesha_777", "Danya_Best", "Timur_pro", "Vladik2010",
        "Pashka_Gamer", "Serega_X", "ilya_mc", "Grisha_Top", "Kostya_nub", "Roma_Shturm", "Andryxa",
        "Misha_Sniper", "kirill_bro", "Stepan_Pro", "Lev_Boss", "Fedya_2012", "Tolik_Tank", "Ruslan_RU",
        "Yarik_mine", "Seva_play", "Gleb_Strike", "Bogdan_kill", "Denis_Desant", "Oleg_Spetsnaz"
    };
    private static final String[] DEFAULT_SKINS = {
        "Soldier", "Sniper", "Army", "Military", "Commando", "Ranger", "Spetsnaz", "Tankist", "Partisan",
        "Mercenary", "Survivor", "Hunter", "Ghost", "Raider", "Trooper", "Gunner"
    };
    private static final String[] KILL_CHAT = {"gg", "изи", "ez", "минус один", "лол", "сорян", "+1"};
    private static final String[] DEATH_CHAT = {"бл", "лаг", "гг", "ну норм", "читер?", "ладно", "ааа"};

    private final VolanSVO plugin;
    private final VolanHooks hooks;
    private final ItemLearning learning;
    private BotSkill skill;
    private final Map<UUID, Bot> bots = new LinkedHashMap<UUID, Bot>();
    /** UUID ботов, которые сейчас входят на сервер (PlayerJoinEvent прилетает до регистрации). */
    private final Set<UUID> joining = new HashSet<UUID>();
    private final Map<String, String[]> skins = new ConcurrentHashMap<String, String[]>();
    private final Random rnd = new Random();
    private int tick;
    private SquadRadio radio;

    /** Приказ рации для команды (null - автономно). */
    SquadRadio.Order order(int teamId) { return radio == null ? null : radio.order(teamId); }

    public BotManager(VolanSVO plugin) {
        this.plugin = plugin;
        this.hooks = new VolanHooks(plugin);
        this.learning = new ItemLearning(plugin.getDataFolder());
        appendBotsSectionIfMissing();
        reloadSkill();
        loadSkinCache();
        fetchSkinsAsync();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        this.radio = new SquadRadio(plugin, this, hooks);
        try { new TeamGlow(plugin, this, hooks); }
        catch (Throwable t) { plugin.getLogger().warning("[Подсветка тиммейтов] не запустилась: " + t); }
        new BukkitRunnable() { @Override public void run() { tickAll(); } }.runTaskTimer(plugin, 1L, 1L);
        new BukkitRunnable() { @Override public void run() { learning.save(); } }.runTaskTimer(plugin, 6000L, 6000L);
    }

    /**
     * На сервере config.yml уже есть и сам не обновляется. Если в нём нет раздела bots -
     * дописываем его в конец файла из конфига внутри jar (с комментариями), остальное не трогаем.
     */
    private void appendBotsSectionIfMissing() {
        if (plugin.getConfig().contains("bots", true)) return; // true: без учёта значений по умолчанию из jar
        File cfg = new File(plugin.getDataFolder(), "config.yml");
        if (!cfg.exists()) return;
        try (java.io.InputStream in = plugin.getResource("config.yml")) {
            if (in == null) return;
            String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int at = text.indexOf("# Боты");
            if (at < 0) return;
            java.nio.file.Files.writeString(cfg.toPath(), "\n" + text.substring(at),
                java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            plugin.reloadConfig();
            plugin.getLogger().info("[Боты] В config.yml добавлен раздел bots с настройками ботов.");
        } catch (Exception e) {
            plugin.getLogger().warning("[Боты] Не удалось дописать раздел bots в config.yml: " + e);
        }
    }

    public void reloadSkill() {
        skill = BotSkill.from(plugin.getConfig().getConfigurationSection("bots"));
        for (Items.Custom c : Items.Custom.values()) {
            if (c == Items.Custom.UNKNOWN) continue;
            Items.setMatch(c, plugin.getConfig().getStringList("bots.custom-items." + c.name().toLowerCase(java.util.Locale.ROOT)));
        }
    }

    // ===================================================================== API для GameManager

    public boolean isBot(UUID uid) {
        return bots.containsKey(uid) || joining.contains(uid);
    }

    public boolean isBot(Player p) {
        return p != null && (isBot(p.getUniqueId()) || BotNms.isBot(p));
    }

    public int count() { return bots.size(); }

    public Collection<UUID> botIds() { return new ArrayList<UUID>(bots.keySet()); }

    /** Создаёт count ботов в мире игры (до телепорта в зону высадки). */
    public List<Player> spawnForGame(int count, World world) {
        List<Player> out = new ArrayList<Player>();
        count = Math.max(0, Math.min(MAX_BOTS, count));
        Location at = world.getSpawnLocation();
        Set<String> used = new HashSet<String>();
        for (Player p : Bukkit.getOnlinePlayers()) used.add(p.getName().toLowerCase(Locale.ROOT));
        List<String> names = new ArrayList<String>(Arrays.asList(namePool()));
        Collections.shuffle(names, rnd);
        List<String[]> skinList = new ArrayList<String[]>(skins.values());
        Collections.shuffle(skinList, rnd);

        int made = 0;
        for (String n : names) {
            if (made >= count) break;
            if (n.length() > 16 || used.contains(n.toLowerCase(Locale.ROOT))) continue;
            Player p = spawnOne(n, at, skinList.isEmpty() ? null : skinList.get(made % skinList.size()));
            if (p != null) { out.add(p); made++; used.add(n.toLowerCase(Locale.ROOT)); }
        }
        for (int i = 0; made < count && i < 50; i++) {
            String n = "Igrok" + (1000 + rnd.nextInt(9000));
            if (used.contains(n.toLowerCase(Locale.ROOT))) continue;
            Player p = spawnOne(n, at, null);
            if (p != null) { out.add(p); made++; }
        }
        plugin.getLogger().info("[Боты] Создано ботов: " + made);
        return out;
    }

    private Player spawnOne(String name, Location at, String[] skin) {
        UUID uid = UUID.nameUUIDFromBytes(("SvoBot:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        joining.add(uid);
        try {
            Player p = BotNms.spawn(name, uid, at, skin == null ? null : skin[0], skin == null ? null : skin[1]);
            p.getInventory().clear();
            p.addScoreboardTag("insvo");
            p.addScoreboardTag("svobot");
            limitChunks(p);
            authLogin(p);
            bots.put(uid, new Bot(this, hooks, skill, uid, name));
            return p;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "[Боты] Не удалось создать бота " + name, t);
            return null;
        } finally {
            joining.remove(uid);
        }
    }

    /** Окончательно выбывший бот уходит с сервера через пару секунд (как вышедший игрок). */
    public void onEliminated(final UUID uid) {
        if (!bots.containsKey(uid)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> remove(uid), 40L + rnd.nextInt(60));
    }

    public void remove(UUID uid) {
        Bot b = bots.remove(uid);
        Player p = Bukkit.getPlayer(uid);
        if (p != null && BotNms.isBot(p)) {
            p.getInventory().clear();
            BotNms.remove(p);
        }
        plugin.getStatsManager().forget(uid);
        if (b != null) Bukkit.getScheduler().runTaskLater(plugin, () -> deletePlayerFiles(uid), 20L);
    }

    private boolean removingAll;

    /** Идёт уборка всех ботов (конец игры): их выход не должен трогать игру. */
    public boolean isRemovingAll() { return removingAll; }

    public void removeAll() {
        removingAll = true;
        try {
            for (UUID uid : new ArrayList<UUID>(bots.keySet())) remove(uid);
            // Страховка: боты, которых почему-то нет в списке (например после /reload).
            for (Player p : new ArrayList<Player>(Bukkit.getOnlinePlayers())) {
                if (BotNms.isBot(p)) { p.getInventory().clear(); BotNms.remove(p); }
            }
        } finally {
            removingAll = false;
        }
    }

    public void shutdown() {
        removeAll();
        learning.save();
    }

    // ===================================================================== для ботов

    int now() { return tick; }

    BotSkill skill() { return skill; }

    ItemLearning learning() { return learning; }

    void warn(String where, Throwable t) {
        if (tick % 200 == 0) plugin.getLogger().log(Level.WARNING, "[Боты] " + where, t);
    }

    void debug(String msg) {
        plugin.getLogger().info("[Боты] " + msg);
    }

    public List<String> debugLines() {
        List<String> out = new ArrayList<String>();
        for (Bot b : bots.values()) out.add(b.debug());
        return out;
    }

    private void tickAll() {
        tick++;
        if (bots.isEmpty()) { thinkEvery = 4; return; }
        long start = System.nanoTime();
        for (Bot b : new ArrayList<Bot>(bots.values())) {
            Player p = b.player();
            if (p == null) { bots.remove(b.id); continue; }
            long t0 = System.nanoTime();
            b.tick(tick);
            b.cpuNanos += System.nanoTime() - t0;
        }
        secNanos += System.nanoTime() - start;
        if (tick % 20 == 0) adaptLoad();
    }

    // ===================================================================== нагрузка

    /** Как часто боты думают (тиков). Растёт сам, если боты едят много времени тика. */
    private int thinkEvery = 4;
    private long secNanos;
    private double lastMsPerTick;
    private int heavySeconds;
    private int lastWarnTick = -100000;

    int thinkEvery() { return thinkEvery; }

    /** Раз в секунду: сколько миллисекунд за тик съели боты, при перегрузе думаем реже. */
    private void adaptLoad() {
        double ms = secNanos / 20.0 / 1e6;
        secNanos = 0;
        lastMsPerTick = ms;
        double mspt = 0;
        try { mspt = Bukkit.getServer().getAverageTickTime(); } catch (Throwable ignored) {}
        boolean overloaded = ms > 8 || mspt > 48;
        if (overloaded) heavySeconds++; else heavySeconds = 0;
        if (overloaded && thinkEvery < 12) thinkEvery += 2;
        else if (!overloaded && ms < 3 && mspt < 40 && thinkEvery > 4) thinkEvery--;
        Navigator.setPathsPerTick(thinkEvery >= 8 ? 1 : (thinkEvery >= 6 ? 2 : 3));
        if (heavySeconds >= 3 && tick - lastWarnTick > 20 * 60) {
            lastWarnTick = tick;
            Bot worst = null;
            for (Bot b : bots.values()) if (worst == null || b.cpuNanos > worst.cpuNanos) worst = b;
            plugin.getLogger().warning(String.format(java.util.Locale.ROOT,
                "[Боты] Сервер перегружен: тик %.1f мс, боты %.1f мс/тик, думают раз в %d тиков. Самый тяжёлый: %s",
                mspt, ms, thinkEvery, worst == null ? "-" : worst.debug()));
        }
        for (Bot b : bots.values()) { b.cpuWindow = b.cpuNanos; b.cpuNanos = 0; }
    }

    public List<String> perfLines() {
        List<String> out = new ArrayList<String>();
        double mspt = 0;
        try { mspt = Bukkit.getServer().getAverageTickTime(); } catch (Throwable ignored) {}
        out.add(String.format(java.util.Locale.ROOT, "Тик сервера %.1f мс, боты %.2f мс/тик, думают раз в %d тиков",
            mspt, lastMsPerTick, thinkEvery));
        for (Bot b : bots.values()) {
            out.add(String.format(java.util.Locale.ROOT, "%s: %.2f мс/тик", b.name, b.cpuWindow / 20.0 / 1e6));
        }
        return out;
    }

    /**
     * Бот грузит чанки вокруг себя как игрок. Дальность прорисовки ему не нужна, поэтому
     * держим вокруг него мало чанков: иначе каждый бот грузит и тикает сотни чанков.
     */
    private void limitChunks(Player p) {
        try { p.setSendViewDistance(2); } catch (Throwable ignored) {}
        try { p.setSimulationDistance(Math.min(4, Bukkit.getSimulationDistance())); } catch (Throwable ignored) {}
        try { p.setViewDistance(Math.min(6, Bukkit.getViewDistance())); } catch (Throwable ignored) {}
    }

    /**
     * Плагины авторизации (AuthMe) не дают незалогиненному игроку ни получать урон, ни бить,
     * ни ходить. Бот пароль не вводит, поэтому логиним его через API плагина.
     */
    private void authLogin(Player p) {
        if (Bukkit.getPluginManager().getPlugin("AuthMe") == null) return;
        try {
            Class<?> c = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            Object api = c.getMethod("getInstance").invoke(null);
            boolean reg = (Boolean) c.getMethod("isRegistered", String.class).invoke(api, p.getName());
            if (!reg) c.getMethod("registerPlayer", String.class, String.class)
                .invoke(api, p.getName(), UUID.randomUUID().toString().substring(0, 16));
            c.getMethod("forceLogin", Player.class).invoke(api, p);
        } catch (Throwable t) {
            plugin.getLogger().warning("[Боты] Не удалось залогинить бота в AuthMe: " + t);
        }
    }

    private int lastGuardLog = -100000;

    /**
     * Урон по боту или от бота в идущей игре отменил чужой плагин (защита незалогиненных,
     * защита после входа и т.п.): возвращаем. Свои правила (тиммейты) соблюдаем.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamageGuard(EntityDamageEvent e) {
        if (!e.isCancelled() || !(e.getEntity() instanceof Player)) return;
        Player victim = (Player) e.getEntity();
        LivingEntity attacker = (e instanceof EntityDamageByEntityEvent) ? source(((EntityDamageByEntityEvent) e).getDamager()) : null;
        boolean victimBot = isBot(victim);
        boolean attackerBot = attacker instanceof Player && isBot((Player) attacker);
        if (!victimBot && !attackerBot) return;
        if (!hooks.gameActive() || !hooks.inGame(victim.getUniqueId())) return;
        if (victim.getGameMode() != org.bukkit.GameMode.SURVIVAL && victim.getGameMode() != org.bukkit.GameMode.ADVENTURE) return;
        if (attacker instanceof Player) {
            if (!hooks.inGame(attacker.getUniqueId())) return;
            if (hooks.sameTeam(victim.getUniqueId(), attacker.getUniqueId())) return;
        }
        e.setCancelled(false);
        if (tick - lastGuardLog > 20 * 120) {
            lastGuardLog = tick;
            plugin.getLogger().info("[Боты] Урон по боту/от бота (" + e.getCause() + ", " + victim.getName()
                + ") отменял другой плагин, урон возвращён.");
        }
    }

    // ===================================================================== события

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent e) {
        if (joining.contains(e.getPlayer().getUniqueId())) e.setJoinMessage(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent e) {
        if (isBot(e.getPlayer())) e.setQuitMessage(null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        final Player dead = e.getEntity();
        Player killer = dead.getKiller();
        if (killer != null) {
            Bot kb = bots.get(killer.getUniqueId());
            if (kb != null) {
                kb.onKill(dead);
                maybeChat(killer, KILL_CHAT, 0.25);
            }
        }
        Bot b = bots.get(dead.getUniqueId());
        if (b == null) return;
        if (skill.debug) {
            EntityDamageEvent last = dead.getLastDamageCause();
            plugin.getLogger().info("[Боты] " + dead.getName() + " погиб: " + (last == null ? "?" : last.getCause())
                + (killer != null ? " от " + killer.getName() : "") + " | " + b.debug());
        }
        b.onDeath();
        maybeChat(dead, DEATH_CHAT, 0.2);
        // У бота нет кнопки «Возродиться» - жмём её сами через тик.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(b.id);
            if (p != null && p.isDead()) p.spigot().respawn();
        }, 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player victim = (Player) e.getEntity();
        Entity damager = (e instanceof EntityDamageByEntityEvent) ? ((EntityDamageByEntityEvent) e).getDamager() : null;
        Bot b = bots.get(victim.getUniqueId());
        if (b != null) b.onDamaged(damager, e.getFinalDamage(), tick);

        // Бьют тиммейта бота - бот вступается.
        LivingEntity attacker = source(damager);
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        int team = hooks.teamIdOf(victim.getUniqueId());
        if (team < 0) return;
        for (Bot mate : bots.values()) {
            if (mate.id.equals(victim.getUniqueId())) continue;
            if (hooks.teamIdOf(mate.id) != team) continue;
            if (attacker instanceof Player && hooks.sameTeam(mate.id, attacker.getUniqueId())) continue;
            Player mp = mate.player();
            if (mp == null || !mp.getWorld().equals(victim.getWorld())) continue;
            double d2 = mp.getLocation().distanceSquared(victim.getLocation());
            if (d2 < 60 * 60) mate.onTeammateHurt(attacker, tick);
            if (d2 < 150 * 150) mate.allyInFight(victim, tick);
        }
        // Тиммейт бота сам напал на кого-то - бот тоже идёт помогать.
        if (attacker instanceof Player) {
            int at = hooks.teamIdOf(attacker.getUniqueId());
            if (at >= 0 && !hooks.sameTeam(attacker.getUniqueId(), victim.getUniqueId())) {
                for (Bot mate : bots.values()) {
                    if (mate.id.equals(attacker.getUniqueId()) || hooks.teamIdOf(mate.id) != at) continue;
                    Player mp = mate.player();
                    if (mp != null && mp.getWorld().equals(attacker.getWorld())
                            && mp.getLocation().distanceSquared(attacker.getLocation()) < 150 * 150) {
                        mate.allyInFight((Player) attacker, tick);
                    }
                }
            }
        }
    }

    /**
     * Ботов во время игры нельзя вытащить из игрового мира. Плагины спавна телепортируют
     * каждого «зашедшего» игрока на спавн хаба с задержкой - для бота это значило побег с
     * арены и выбывание (отсюда моментальный конец игры).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBotTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (!isBot(p) || e.getTo() == null) return;
        World gw = hooks.gameWorld();
        if (gw == null || !plugin.getGameManager().isGameRunning()) return;
        if (!hooks.inGame(p.getUniqueId()) && !plugin.getGameManager().isInQueue(p)) return;
        if (e.getFrom().getWorld().equals(gw) && !e.getTo().getWorld().equals(gw)) e.setCancelled(true);
    }

    /** Подключить отрисовку ботов к карте с этим id (один раз). */
    public void attachMap(int mapId) {
        if (mapId < 0) return;
        org.bukkit.map.MapView view = Bukkit.getMap(mapId);
        if (view == null) return;
        for (org.bukkit.map.MapRenderer r : view.getRenderers()) if (r instanceof BotMapRenderer) return;
        view.addRenderer(new BotMapRenderer(this));
    }

    /** Выстрел из оружия MilitaryCraft (ПКМ с пушкой) слышно далеко. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onShot(PlayerInteractEvent e) {
        if (bots.isEmpty()) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null) return;
        Items.Kind k = Items.kind(it, hooks);
        if (k != Items.Kind.GUN && k != Items.Kind.LAUNCHER && k != Items.Kind.SPRAYER) return;
        hear(e.getPlayer(), 56);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBow(EntityShootBowEvent e) {
        if (bots.isEmpty() || !(e.getEntity() instanceof Player)) return;
        hear(e.getEntity(), 24);
    }

    private void hear(LivingEntity source, double radius) {
        double r2 = radius * radius;
        for (Bot b : bots.values()) {
            Player p = b.player();
            if (p == null || !p.getWorld().equals(source.getWorld())) continue;
            if (p.getLocation().distanceSquared(source.getLocation()) <= r2) b.onHeard(source, tick);
        }
    }

    private static LivingEntity source(Entity e) {
        if (e instanceof LivingEntity) return (LivingEntity) e;
        if (e instanceof Projectile && ((Projectile) e).getShooter() instanceof LivingEntity)
            return (LivingEntity) ((Projectile) e).getShooter();
        return null;
    }

    /** Реплика бота в чат с вероятностью chance (если чат ботам разрешён). */
    void say(Player p, String[] lines, double chance) {
        maybeChat(p, lines, chance);
    }

    private void maybeChat(final Player p, String[] lines, double chance) {
        if (!skill.chat || rnd.nextDouble() > chance) return;
        final String msg = lines[rnd.nextInt(lines.length)];
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) p.chat(msg);
        }, 20L + rnd.nextInt(40));
    }

    // ===================================================================== имена и скины

    private String[] namePool() {
        List<String> cfg = plugin.getConfig().getStringList("bots.names");
        return cfg.isEmpty() ? DEFAULT_NAMES : cfg.toArray(new String[0]);
    }

    private File skinFile() {
        return new File(plugin.getDataFolder(), "bot_skins.yml");
    }

    private void loadSkinCache() {
        File f = skinFile();
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection s = y.getConfigurationSection("skins");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            String v = s.getString(k + ".value"), sig = s.getString(k + ".signature");
            if (v != null && sig != null) skins.put(k, new String[]{v, sig});
        }
    }

    /** Скачивает скины ников из config (bots.skin-sources) у Mojang и кэширует в bot_skins.yml. */
    private void fetchSkinsAsync() {
        List<String> src = plugin.getConfig().getStringList("bots.skin-sources");
        final List<String> wanted = src.isEmpty() ? Arrays.asList(DEFAULT_SKINS) : src;
        final List<String> missing = new ArrayList<String>();
        for (String n : wanted) if (!skins.containsKey(n)) missing.add(n);
        if (missing.isEmpty()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            Pattern idP = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f]{32})\"");
            Pattern valP = Pattern.compile("\"value\"\\s*:\\s*\"([^\"]+)\"");
            Pattern sigP = Pattern.compile("\"signature\"\\s*:\\s*\"([^\"]+)\"");
            int got = 0;
            for (String n : missing) {
                try {
                    String prof = get(http, "https://api.mojang.com/users/profiles/minecraft/" + n);
                    Matcher m = prof == null ? null : idP.matcher(prof);
                    if (m == null || !m.find()) continue;
                    String sess = get(http, "https://sessionserver.mojang.com/session/minecraft/profile/" + m.group(1) + "?unsigned=false");
                    if (sess == null) continue;
                    Matcher v = valP.matcher(sess), s = sigP.matcher(sess);
                    if (v.find() && s.find()) { skins.put(n, new String[]{v.group(1), s.group(1)}); got++; }
                    Thread.sleep(400);
                } catch (Exception ignored) {}
            }
            if (got > 0) {
                YamlConfiguration y = new YamlConfiguration();
                for (Map.Entry<String, String[]> e : skins.entrySet()) {
                    y.set("skins." + e.getKey() + ".value", e.getValue()[0]);
                    y.set("skins." + e.getKey() + ".signature", e.getValue()[1]);
                }
                try { y.save(skinFile()); } catch (Exception ignored) {}
                plugin.getLogger().info("[Боты] Загружено скинов: " + got);
            }
        });
    }

    private static String get(HttpClient http, String url) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? r.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Ботам не нужны файлы игрока: инвентарь, статистика и достижения удаляются. */
    private void deletePlayerFiles(UUID uid) {
        World main = Bukkit.getWorlds().get(0);
        File dir = main.getWorldFolder();
        String id = uid.toString();
        for (String path : new String[]{"playerdata/" + id + ".dat", "playerdata/" + id + ".dat_old",
                "stats/" + id + ".json", "advancements/" + id + ".json"}) {
            File f = new File(dir, path);
            if (f.exists()) f.delete();
        }
    }
}
