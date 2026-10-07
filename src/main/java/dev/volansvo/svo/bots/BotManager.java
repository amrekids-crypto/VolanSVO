package dev.volansvo.svo.bots;

import dev.volansvo.svo.VolanSVO;
import dev.volansvo.svo.bots.human.ChatIntent;
import dev.volansvo.svo.bots.human.ChatStyle;
import dev.volansvo.svo.bots.nav.Trails;
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
        "Yarik_mine", "Seva_play", "Gleb_Strike", "Bogdan_kill", "Denis_Desant", "Oleg_Spetsnaz",
        "Kolya_PirAr", "Dima_PirAr", "Maks_PirAr", "Vovan_PirAr", "Zheka_PirAr", "Artur_PirAr"
    };
    private static final String[] DEFAULT_SKINS = {
        "Soldier", "Sniper", "Army", "Military", "Commando", "Ranger", "Spetsnaz", "Tankist", "Partisan",
        "Mercenary", "Survivor", "Hunter", "Ghost", "Raider", "Trooper", "Gunner"
    };

    private final VolanSVO plugin;
    private final VolanHooks hooks;
    private final ItemLearning learning;
    private final BotMemory memory;
    /** Тропы игроков на текущей карте и имя этой карты. */
    private Trails trails;
    private String trailsKey;
    private BotSkill skill;
    private final Map<UUID, Bot> bots = new LinkedHashMap<UUID, Bot>();
    /** UUID ботов, которые сейчас входят на сервер (PlayerJoinEvent прилетает до регистрации). */
    private final Set<UUID> joining = new HashSet<UUID>();
    private final Map<String, String[]> skins = new ConcurrentHashMap<String, String[]>();
    private final Random rnd = new Random();
    private int tick;
    private SquadRadio radio;

    /** Одна ли команда (для рисования на карте). */
    public boolean sameTeamPublic(UUID a, UUID b) { return hooks.sameTeam(a, b); }

    /** Приказ рации для команды (null - автономно). */
    SquadRadio.Order order(int teamId) { return radio == null ? null : radio.order(teamId); }

    public BotManager(VolanSVO plugin) {
        this.plugin = plugin;
        this.hooks = new VolanHooks(plugin);
        this.learning = new ItemLearning(plugin.getDataFolder());
        this.memory = new BotMemory(plugin.getDataFolder());
        appendBotsSectionIfMissing();
        reloadSkill();
        loadSkinCache();
        fetchSkinsAsync();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        this.radio = new SquadRadio(plugin, this, hooks);
        try { new TeamGlow(plugin, this, hooks); }
        catch (Throwable t) { plugin.getLogger().warning("[Подсветка тиммейтов] не запустилась: " + t); }
        new BukkitRunnable() { @Override public void run() { tickAll(); } }.runTaskTimer(plugin, 1L, 1L);
        new BukkitRunnable() { @Override public void run() { learning.save(); memory.save(); saveTrails(); } }.runTaskTimer(plugin, 6000L, 6000L);
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
            // ExecutableItems молча не запускает предмет без права на него (у людей права даёт
            // плагин прав или OP, у ботов их нет): огнемёт, калаш и дроны в руках бота молчали.
            p.addAttachment(plugin, "ei.item.*", true);
            p.addAttachment(plugin, "executableitems.item.*", true);
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
        // Кто-то выходит сразу, кто-то остаётся досмотреть.
        Bukkit.getScheduler().runTaskLater(plugin, () -> remove(uid), bots.get(uid).timing().leave());
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
        memory.save();
        saveTrails();
    }

    // ===================================================================== для ботов

    int now() { return tick; }

    BotSkill skill() { return skill; }

    ItemLearning learning() { return learning; }

    BotMemory memory() { return memory; }

    private final Map<String, Integer> warnedAt = new HashMap<String, Integer>();

    /** Ошибка в мозгу бота: пишем сразу, ту же повторно не чаще раза в 10 секунд. */
    void warn(String where, Throwable t) {
        String key = where + "|" + t;
        Integer last = warnedAt.get(key);
        if (last != null && tick - last < 200) return;
        warnedAt.put(key, tick);
        if (warnedAt.size() > 200) warnedAt.clear();
        plugin.getLogger().log(Level.WARNING, "[Боты] " + where, t);
    }

    void debug(String msg) {
        plugin.getLogger().info("[Боты] " + msg);
    }

    /** История причин бота по нику (или null). */
    public List<String> notesOf(String botName) {
        for (Bot b : bots.values()) if (b.name.equalsIgnoreCase(botName)) return b.notes();
        return null;
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
            b.guard(p);
            b.cpuNanos += System.nanoTime() - t0;
        }
        if (tick % 10 == 0) recordTrails();
        secNanos += System.nanoTime() - start;
        if (tick % 20 == 0) { adaptLoad(); chatterTick(); }
    }

    // ===================================================================== тропы игроков

    private File trailsFile(String key) {
        return new File(plugin.getDataFolder(), "bot_trails_" + key + ".dat");
    }

    private void saveTrails() {
        if (trails != null && trailsKey != null) trails.save(trailsFile(trailsKey));
    }

    /** Дважды в секунду: где сейчас идут живые игроки. По этим клеткам потом водим ботов. */
    private void recordTrails() {
        if (!skill.trails) { Navigator.setTrails(null); return; }
        if (!hooks.gameActive()) return;
        String key = hooks.mapKey();
        if (!key.equals(trailsKey)) {
            saveTrails();
            trails = Trails.load(trailsFile(key));
            trailsKey = key;
            Navigator.setTrails(trails);
        }
        for (Player p : hooks.alivePlayers()) {
            if (isBot(p) || p.getGameMode() != org.bukkit.GameMode.SURVIVAL || p.isInsideVehicle() || p.isFlying()) continue;
            if (!((Entity) p).isOnGround()) continue;
            Location l = p.getLocation();
            trails.record(l.getBlockX(), (int) Math.floor(l.getY() + 0.2), l.getBlockZ());
        }
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
        Navigator.setBudgetMs(skill.navBudgetMs * (thinkEvery >= 8 ? 0.4 : (thinkEvery >= 6 ? 0.7 : 1.0)));
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
            out.add(String.format(java.util.Locale.ROOT, "%s: %.2f мс/тик, рывков головы %d", b.name, b.cpuWindow / 20.0 / 1e6, b.headSnaps));
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
                // Обида с прошлых матчей - тоже месть.
                boolean revenge = kb.grudge(dead.getName()) > 0;
                for (Map.Entry<UUID, UUID> en : recentKills.entrySet()) {
                    if (dead.getUniqueId().equals(en.getValue()) && hooks.sameTeam(killer.getUniqueId(), en.getKey())) { revenge = true; break; }
                }
                BotChatter.Topic topic = revenge ? BotChatter.Topic.KILL_REVENGE
                    : (kb.met(dead.getName()) >= 4 && rnd.nextBoolean() ? BotChatter.Topic.KILL_AGAIN : BotChatter.Topic.KILL);
                // Пока рядом ещё стреляют, не до чата.
                if (!kb.inFight()) chat(killer, topic, 0.5, killer.getName(), dead.getName());
            }
        }
        if (killer != null && !killer.equals(dead)) recentKills.put(dead.getUniqueId(), killer.getUniqueId());
        Bot b = bots.get(dead.getUniqueId());
        if (b == null) return;
        if (skill.debug) {
            EntityDamageEvent last = dead.getLastDamageCause();
            plugin.getLogger().info("[Боты] " + dead.getName() + " погиб: " + (last == null ? "?" : last.getCause())
                + (killer != null ? " от " + killer.getName() : "") + " | " + b.debug());
        }
        if (killer != null && !killer.equals(dead)) b.killedBy(killer.getName());
        b.onDeath();
        chatNow(dead, deathTopic(dead, killer), killer == null ? null : killer.getName(), dead.getName()); // о своей смерти пишет всегда
        // У бота нет кнопки «Возродиться» - жмём её сами через тик.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(b.id);
            if (p != null && p.isDead()) p.spigot().respawn();
        }, b.timing().respawn());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player victim = (Player) e.getEntity();
        Entity damager = (e instanceof EntityDamageByEntityEvent) ? ((EntityDamageByEntityEvent) e).getDamager() : null;
        Bot b = bots.get(victim.getUniqueId());
        if (b != null) b.onDamaged(damager, e.getFinalDamage(), tick);
        LivingEntity dealer = source(damager);
        Bot db = dealer == null ? null : bots.get(dealer.getUniqueId());
        if (db != null) db.onDealt(victim, e.getFinalDamage(), tick);

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

    /** Достижения ботов в чат не пишем. */
    @EventHandler
    public void onBotAdvancement(org.bukkit.event.player.PlayerAdvancementDoneEvent e) {
        if (isBot(e.getPlayer())) e.message(null);
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
    // ===================================================================== чат

    private int lastGlobalChat = -1000;
    private int nextSmallTalk = 20 * 120;
    private boolean lastAliveSaid;
    private final Map<UUID, UUID> recentKills = new HashMap<UUID, UUID>(); // жертва -> убийца

    /** Что уже прозвучало в этой катке (реплики, вопросы и ответы): до конца катки не повторяем. */
    private final java.util.Set<String> saidThisGame = new java.util.HashSet<String>();

    private String pick(BotChatter.Topic t) {
        return pickFrom(BotChatter.LINES.get(t));
    }

    /** Ещё не звучавшая в этой катке реплика из набора; всё сказано - null (лучше промолчать, чем повторяться). */
    private String pickFrom(String[] lines) {
        if (lines == null || lines.length == 0) return null;
        boolean noWarden = !wardenInGame();
        List<String> fresh = new ArrayList<String>();
        for (String l : lines) if (!saidThisGame.contains(l) && !(noWarden && mentionsWarden(l))) fresh.add(l);
        if (fresh.isEmpty()) return null;
        String l = fresh.get(rnd.nextInt(fresh.size()));
        saidThisGame.add(l);
        return l;
    }

    /** Жириновский сейчас в игре (иначе боты про него не вспоминают). */
    private boolean wardenInGame() {
        org.bukkit.entity.Warden w = hooks.warden();
        return w != null && w.isValid() && !w.isDead();
    }

    private static boolean mentionsWarden(String s) {
        return s != null && s.toLowerCase(java.util.Locale.ROOT).contains("жиринов");
    }

    /** Реплика для этого бота: не та, что он недавно говорил, и в его манере письма. */
    private String line(Player p, BotChatter.Topic t) {
        Bot b = bots.get(p.getUniqueId());
        String raw = pick(t);
        if (raw == null || b == null) return raw;
        if (b.repeats(raw)) { String alt = pick(t); if (alt != null) raw = alt; } // в прошлой катке это уже говорил
        if (!skill.chatStyle) return raw;
        String n = t.name();
        boolean sad = n.startsWith("DEATH") || t == BotChatter.Topic.T_LOW_HP || t == BotChatter.Topic.T_NO_ITEMS;
        return ChatStyle.apply(raw, b.skill().style, rnd, sad, sad && b.angry());
    }

    /** Сколько тиков бот набирает сообщение; на это время он останавливается. */
    private long typing(Player p, String msg, long fallback) {
        Bot b = bots.get(p.getUniqueId());
        if (b == null || !skill.chatStyle) return fallback;
        int t = b.timing().typing(msg.length());
        b.typing(t);
        return t;
    }

    private static String fill(String raw, String killer, String victim, String other) {
        if (raw == null) return null;
        if (killer != null) raw = raw.replace("{k}", killer);
        if (victim != null) raw = raw.replace("{v}", victim);
        if (other != null) raw = raw.replace("{n}", other);
        return raw.replace("{k}", "ты").replace("{v}", "ты").replace("{n}", "друг");
    }

    /** Реплика для своих («держи», «понял»): видят только люди из команды бота. */
    void say(Player p, String[] lines, double chance) {
        if (!skill.chat || rnd.nextDouble() > chance) return;
        String l = pickFrom(lines);
        if (l != null) teamMessage(p, l);
    }

    void teamSay(Player p, BotChatter.Topic t, double chance) {
        if (!skill.chat || rnd.nextDouble() > chance) return;
        String msg = line(p, t);
        if (msg != null) teamMessage(p, msg);
    }

    private void teamMessage(final Player p, final String msg) {
        final int team = hooks.teamIdOf(p.getUniqueId());
        if (team < 0) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (isBot(o) || hooks.teamIdOf(o.getUniqueId()) != team) continue;
                o.sendMessage(org.bukkit.ChatColor.GREEN + "[Команда] " + org.bukkit.ChatColor.WHITE + "<" + p.getName() + "> " + msg);
            }
        }, typing(p, msg, 10L + rnd.nextInt(20)));
    }

    /**
     * Общая реплика на тему: видят все в мире игры. Иногда другой бот отвечает.
     * Не чаще раза в 2.5 секунды на всех ботов, чтобы не было спама.
     */
    void chat(Player p, BotChatter.Topic t, double chance, String killer, String victim) {
        if (!skill.chat || rnd.nextDouble() > chance || tick - lastGlobalChat < 50) return;
        final String msg = fill(line(p, t), killer, victim, null);
        if (msg == null) return;
        lastGlobalChat = tick;
        final org.bukkit.World w = p.getWorld();
        final String name = p.getName();
        long delay = typing(p, msg, 15L + rnd.nextInt(30));
        Bukkit.getScheduler().runTaskLater(plugin, () -> worldMessage(w, name, msg), delay);
        BotChatter.Topic r = BotChatter.replyTo(t);
        if (r != null && rnd.nextDouble() < 0.35) {
            final Player other = randomOtherBot(w, p.getUniqueId());
            if (other != null) {
                final String answer = fill(line(other, r), killer, victim, name);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (other.isOnline() && answer != null) worldMessage(w, other.getName(), answer);
                }, delay + 30L + rnd.nextInt(50));
            }
        }
    }

    /** Реплика без шанса и без очереди общего чата (о своей смерти), иногда с ответом другого бота. */
    void chatNow(Player p, BotChatter.Topic t, String killer, String victim) {
        if (!skill.chat) return;
        final String msg = fill(pick(t), killer, victim, null);
        if (msg == null) return;
        lastGlobalChat = tick;
        final org.bukkit.World w = p.getWorld();
        final String name = p.getName();
        long delay = 10L + rnd.nextInt(20);
        Bukkit.getScheduler().runTaskLater(plugin, () -> worldMessage(w, name, msg), delay);
        BotChatter.Topic r = BotChatter.replyTo(t);
        if (r != null && rnd.nextDouble() < 0.35) {
            final Player other = randomOtherBot(w, p.getUniqueId());
            if (other != null) {
                final String answer = fill(pick(r), killer, victim, name);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (other.isOnline() && answer != null) worldMessage(w, other.getName(), answer);
                }, delay + 30L + rnd.nextInt(50));
            }
        }
    }

    /** Крикнуть сразу, без очереди общего чата (камикадзе), с ответом другого бота. */
    void shout(Player p, BotChatter.Topic t, String victim) {
        if (!skill.chat) return;
        final String msg = fill(pick(t), null, victim, null);
        if (msg == null) return;
        lastGlobalChat = tick;
        final org.bukkit.World w = p.getWorld();
        worldMessage(w, p.getName(), msg);
        BotChatter.Topic r = BotChatter.replyTo(t);
        if (r != null && rnd.nextDouble() < 0.4) {
            final Player other = randomOtherBot(w, p.getUniqueId());
            if (other != null) {
                final String answer = fill(pick(r), null, victim, p.getName());
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (other.isOnline() && answer != null) worldMessage(w, other.getName(), answer);
                }, 25L + rnd.nextInt(30));
            }
        }
    }

    private void worldMessage(org.bukkit.World w, String name, String msg) {
        for (Player o : w.getPlayers()) o.sendMessage("<" + name + "> " + msg);
        plugin.getLogger().info("[чат бота] <" + name + "> " + msg);
    }

    private Player randomOtherBot(org.bukkit.World w, UUID not) {
        List<Player> list = new ArrayList<Player>();
        for (Bot b : bots.values()) {
            if (b.id.equals(not)) continue;
            Player o = b.player();
            if (o != null && o.isOnline() && o.getWorld().equals(w)) list.add(o);
        }
        return list.isEmpty() ? null : list.get(rnd.nextInt(list.size()));
    }

    private BotChatter.Topic deathTopic(Player dead, Player killer) {
        if (killer != null && !killer.equals(dead)) return BotChatter.Topic.DEATH_BY_PLAYER;
        EntityDamageEvent last = dead.getLastDamageCause();
        if (last == null) return BotChatter.Topic.DEATH_OTHER;
        if (last instanceof EntityDamageByEntityEvent && ((EntityDamageByEntityEvent) last).getDamager() instanceof org.bukkit.entity.Warden)
            return BotChatter.Topic.DEATH_WARDEN;
        switch (last.getCause()) {
            case WORLD_BORDER: return BotChatter.Topic.DEATH_ZONE;
            case FALL: return BotChatter.Topic.DEATH_FALL;
            case ENTITY_EXPLOSION: case BLOCK_EXPLOSION: return BotChatter.Topic.DEATH_EXPLOSION;
            case LAVA: case FIRE: case FIRE_TICK: case HOT_FLOOR: return BotChatter.Topic.DEATH_FIRE;
            case DROWNING: return BotChatter.Topic.DEATH_DROWN;
            default: return BotChatter.Topic.DEATH_OTHER;
        }
    }

    /** Бот победил - радуется в общий чат. */
    public void onWin(UUID uid) {
        Player p = Bukkit.getPlayer(uid);
        if (p != null && isBot(p)) chat(p, BotChatter.Topic.WIN, 0.9, null, null);
    }

    /** Раз в секунду: болтовня ни о чём и «финал». */
    private void chatterTick() {
        if (!hooks.gameActive()) { lastAliveSaid = false; recentKills.clear(); saidThisGame.clear(); nextSmallTalk = tick + 20 * 120; return; }
        List<Player> alive = new ArrayList<Player>();
        for (Bot b : bots.values()) {
            Player o = b.player();
            if (o != null && hooks.inGame(b.id) && o.getGameMode() == org.bukkit.GameMode.SURVIVAL) alive.add(o);
        }
        if (alive.isEmpty()) return;
        if (!lastAliveSaid && hooks.alivePlayers().size() <= 3) {
            lastAliveSaid = true;
            chat(alive.get(rnd.nextInt(alive.size())), BotChatter.Topic.LAST_ALIVE, 0.6, null, null);
        }
        if (tick >= nextSmallTalk && alive.size() >= 2) {
            nextSmallTalk = tick + 20 * (70 + rnd.nextInt(110));
            if (!skill.chat || tick - lastGlobalChat < 100) return;
            Player a = alive.get(rnd.nextInt(alive.size()));
            Player b = randomOtherBot(a.getWorld(), a.getUniqueId());
            if (b == null) return;
            boolean noWarden = !wardenInGame();
            // Разговор, который в этой катке ещё не заводили.
            List<String[]> freshPairs = new ArrayList<String[]>();
            for (String[] c : BotChatter.SMALLTALK) if (!saidThisGame.contains(c[0]) && !(noWarden && mentionsWarden(c[0]))) freshPairs.add(c);
            if (freshPairs.isEmpty()) return;
            String[] pair = freshPairs.get(rnd.nextInt(freshPairs.size()));
            List<String> answers = new ArrayList<String>();
            for (int i = 1; i < pair.length; i++) if (!saidThisGame.contains(pair[i]) && !(noWarden && mentionsWarden(pair[i]))) answers.add(pair[i]);
            if (answers.isEmpty()) return;
            final String q = pair[0], ans = answers.get(rnd.nextInt(answers.size()));
            saidThisGame.add(q);
            saidThisGame.add(ans);
            final org.bukkit.World w = a.getWorld();
            final String an = a.getName(), bn = b.getName();
            lastGlobalChat = tick;
            Bukkit.getScheduler().runTaskLater(plugin, () -> worldMessage(w, an, q), 5L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> worldMessage(w, bn, ans), 50L + rnd.nextInt(50));
        }
    }

    // ===================================================================== слова и жесты игроков

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHumanChat(org.bukkit.event.player.AsyncPlayerChatEvent e) {
        if (!skill.chatCommands) return;
        final Player who = e.getPlayer();
        final String msg = e.getMessage();
        Bukkit.getScheduler().runTask(plugin, () -> heardChat(who, msg));
    }

    /** Игрок написал в чат: приказ отряду, просьба поделиться, вопрос боту или «гг». */
    private void heardChat(Player who, String msg) {
        if (bots.isEmpty() || isBot(who) || !who.isOnline()) return;
        ChatIntent intent = ChatIntent.parse(msg);
        boolean playing = hooks.gameActive() && hooks.inGame(who.getUniqueId());
        Bot named = null;
        for (Bot b : bots.values()) if (ChatIntent.mentions(msg, b.name)) { named = b; break; }
        switch (intent) {
            case FOLLOW: if (playing) radio.order(who, SquadRadio.Mode.FOLLOW); return;
            case HOLD: if (playing) radio.order(who, SquadRadio.Mode.HOLD); return;
            case LOOT: if (playing) radio.order(who, SquadRadio.Mode.LOOT); return;
            case FREE: if (playing) radio.order(who, SquadRadio.Mode.AUTO); return;
            case ASK_HEAL: case ASK_AMMO: case ASK_FOOD: {
                Bot giver = playing ? mateOf(who, named) : null;
                if (giver == null) return;
                Items.Kind kind = intent == ChatIntent.ASK_FOOD ? Items.Kind.FOOD : Items.Kind.HEAL;
                boolean ok = giver.asked(who, kind, intent == ChatIntent.ASK_AMMO);
                Player gp = giver.player();
                if (gp != null && !ok) teamSay(gp, BotChatter.Topic.T_NO_ITEMS, 0.9);
                return;
            }
            case ARE_YOU_BOT: {
                Bot b = named != null ? named : anyBot(who.getWorld());
                if (b != null) reply(b, b.skill().deniesBot ? BotChatter.Topic.R_BOT_DENY : BotChatter.Topic.R_BOT_ADMIT);
                return;
            }
            case GG: {
                Bot b = anyBot(who.getWorld());
                if (b != null && rnd.nextInt(10) < 7) reply(b, BotChatter.Topic.R_GG);
                return;
            }
            default:
                if (named != null && rnd.nextInt(10) < 6) reply(named, BotChatter.Topic.REPLY_GENERIC);
        }
    }

    /** Ответ бота игроку в общий чат (мимо общего ограничения частоты: его спросили). */
    private void reply(Bot b, BotChatter.Topic t) {
        final Player p = b.player();
        if (p == null || !skill.chat || b.inFight()) return;
        final String msg = fill(line(p, t), null, null, null);
        if (msg == null) return;
        final org.bukkit.World w = p.getWorld();
        final String name = p.getName();
        Bukkit.getScheduler().runTaskLater(plugin, () -> worldMessage(w, name, msg), typing(p, msg, 20L + rnd.nextInt(30)));
    }

    /** Бот из команды игрока поближе к нему (названный по нику - в первую очередь). */
    private Bot mateOf(Player who, Bot named) {
        int team = hooks.teamIdOf(who.getUniqueId());
        if (team < 0) return null;
        if (named != null && hooks.teamIdOf(named.id) == team && hooks.inGame(named.id)) return named;
        Bot best = null;
        double bd = 80 * 80;
        for (Bot b : bots.values()) {
            Player bp = b.player();
            if (bp == null || hooks.teamIdOf(b.id) != team || !hooks.inGame(b.id) || !bp.getWorld().equals(who.getWorld())) continue;
            double d = bp.getLocation().distanceSquared(who.getLocation());
            if (d < bd) { bd = d; best = b; }
        }
        return best;
    }

    private Bot anyBot(World w) {
        List<Bot> list = new ArrayList<Bot>();
        for (Bot b : bots.values()) {
            Player bp = b.player();
            if (bp != null && bp.getWorld().equals(w)) list.add(b);
        }
        return list.isEmpty() ? null : list.get(rnd.nextInt(list.size()));
    }

    /** Последний жест игрока: {тик, сколько подряд, x, z}. */
    private final Map<UUID, double[]> sneaks = new HashMap<UUID, double[]>(), jumps = new HashMap<UUID, double[]>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(org.bukkit.event.player.PlayerToggleSneakEvent e) {
        if (e.isSneaking() && gesture(sneaks, e.getPlayer(), 20)) mirrorNear(e.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJump(com.destroystokyo.paper.event.player.PlayerJumpEvent e) {
        if (gesture(jumps, e.getPlayer(), 30)) mirrorNear(e.getPlayer(), true);
    }

    /** Третий раз подряд на одном месте - это уже жест, а не ходьба. */
    private boolean gesture(Map<UUID, double[]> last, Player p, int window) {
        if (bots.isEmpty() || !skill.mirror || isBot(p)) return false;
        Location l = p.getLocation();
        double[] g = last.get(p.getUniqueId());
        if (g == null) { g = new double[]{-1000, 0, 0, 0}; last.put(p.getUniqueId(), g); }
        boolean same = tick - g[0] <= window && Math.hypot(l.getX() - g[2], l.getZ() - g[3]) < 1.5;
        g[1] = same ? g[1] + 1 : 1;
        g[0] = tick; g[2] = l.getX(); g[3] = l.getZ();
        return g[1] == 3;
    }

    private void mirrorNear(Player who, boolean jump) {
        int team = hooks.teamIdOf(who.getUniqueId());
        if (team < 0) return;
        for (Bot b : bots.values()) {
            Player bp = b.player();
            if (bp == null || hooks.teamIdOf(b.id) != team || !bp.getWorld().equals(who.getWorld())) continue;
            if (bp.getLocation().distanceSquared(who.getLocation()) < 7 * 7 && bp.hasLineOfSight(who)) b.mirror(who, jump, tick);
        }
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
