package dev.volansvo.svo.managers;

import dev.volansvo.svo.VolanSVO;
import dev.volansvo.svo.maps.MapData;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Evoker;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.SpectralArrow;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Vindicator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Модификатор "Хаос" (включается в GUI /svoplay). Все события с координатами (метеор, молния,
 * банды/ООН, вызыватель) действуют ТОЛЬКО в уже загруженных чанках (т.е. там, где реально есть
 * игроки) - если чанк не загружен естественно, событие просто пропускается, чанк ради этого
 * принудительно не грузится. Плюс раз в CHUNK_SWEEP_INTERVAL_SEC (2 мин) страховочно снимается
 * форс-загрузка с любых чанков, оказавшихся за уже сжавшимся барьером. Пока активен:
 *  - раз в 10 сек падает фаербол-метеор (сила 20) в случайном месте пересечения квадрата
 *    спавна и ТЕКУЩЕГО (сжавшегося) барьера;
 *  - у каждого игрока раз в случайный интервал 30-90 сек (независимо) - рядом появляется
 *    дымящаяся точка (5 сек предупреждения), после чего там призывается настоящий ТНТ
 *    (без сообщения в чат) - не срабатывает в воздухе или в транспорте, игрок успевает уйти;
 *  - у каждого игрока раз в случайный интервал 30-90 сек (независимо) - случайный эффект на 20 сек;
 *  - у каждого игрока раз в случайный интервал 30-90 сек (независимо) проверяется ночь - если да,
 *    над ним спавнится фантом-подрывник, взрывающийся при приближении к любому игроку ближе 4 блоков;
 *  - раз в 1 мин дождь из спектральных стрел (10 сек) над ОДНИМ случайным игроком внутри
 *    ИГРОВОЙ ЗОНЫ (привязан к его текущей позиции - он и так внутри барьера),
 *    попадание само подсвечивает игрока;
 *  - раз в 30 сек молния в случайной точке зоны (без урона, может поджечь траву);
 *  - день/ночь идёт в 5 раз быстрее (чаще ночь - чаще фантомы-подрывники);
 *  - раз в 6 мин свап инвентарей двух случайных игроков;
 *  - на старте 3 стенда razboist падают с неба в случайных точках квадрата спавна;
 *  - раз в случайный интервал 30 сек - 2 мин спавнятся 2-6 арбалетчиков и 2-6 топорщиков
 *    ХЕЗБОЛЛЫ/МЕЦАХ (на Донбассе - боец ДНР/боец Украины), в пределах 20 бл от случайного
 *    игрока, без оповещения (потолок 10 на банду) - игнорируют игроков, дерутся друг с другом
 *    при виде врага в 200 бл и с крестьянами "Наблюдателя ООН" (именованы, не нападают ни на
 *    кого, спавнятся так же, потолок 10); каждый 4-й топорщик ХЕЗБОЛЛЫ - смертник (красные
 *    партиклы), взрывается если в 3 бл окажется МЕЦАХ/ООН;
 *  - раз в 3 мин спавнится вызыватель в пределах 100 бл от случайного игрока (без оповещения) -
 *    враждебен ко ВСЕМ сущностям в 50 бл; пока агрится на игрока, тому играет музыка pigstep
 *    (до смерти любого из двоих или потери агро);
 *  - раз в 10 мин случайному живому игроку выдаётся ядерная бомба ООН (nukestrike:nuke give
 *    от его же имени) с сообщением в чат.
 */
public class ChaosManager {

    private final VolanSVO plugin;
    private final Random rng = new Random();

    private volatile boolean active = false;
    private World world;

    private BukkitTask mainTask;
    private BukkitTask phantomTask;
    private BukkitTask timeTask;

    private final Map<UUID, Integer> nextBlast   = new HashMap<UUID, Integer>();
    private final Map<UUID, Integer> nextEffect  = new HashMap<UUID, Integer>();
    private final Map<UUID, Integer> nextPhantom = new HashMap<UUID, Integer>();

    private int nextArrowRain;
    private int nextLightning;
    private int nextSwap;
    private int nextNukeGive;

    private static final int METEOR_INTERVAL_SEC = 10; // раз в 10 сек (вдвое чаще базовых 20 сек)
    private static final float METEOR_POWER = 20f;
    private static final double METEOR_START_Y = 250.0;
    private static final double METEOR_VISUAL_RADIUS = 200.0; // видимость партиклов полёта
    private static final double METEOR_LANDING_SOUND_RADIUS = 50.0; // звуки (свист/взрыв) слышны только рядом с местом приземления
    private static final double METEOR_FALL_SPEED = 6.0;
    /** За сколько тиков ДО появления самого фаербола играть предупреждающий свист - раньше
     *  звук играл ровно в момент запуска, а сам полёт (при текущей высоте/скорости) длится
     *  всего ~1.5 сек, так что предупреждение приходило слишком поздно, чтобы среагировать. */
    private static final long METEOR_WARNING_TICKS = 80L; // 4 сек

    /** Раз в 2 мин - страховочный сброс форс-загрузки с любых чанков, оказавшихся за
     *  сжавшимся барьером (барьер двигается, а форс-тикеты сами не снимаются). */
    private static final int CHUNK_SWEEP_INTERVAL_SEC = 120;

    private static final String PHANTOM_TAG = "svo_chaos_phantom";
    /** Живые фантомы-подрывники, отслеживаем по UUID вместо full-world скана каждый тик. */
    private final Set<UUID> phantomIds = new HashSet<UUID>();
    /** Как часто (сек) пробуем кинуть кубик на спавн ночного фантома у игрока. Спавн происходит,
     *  только если В МОМЕНТ срабатывания сейчас ночь - таймер тикает и днём, чтобы не промахиваться
     *  мимо короткого ночного окна (при ускоренном x5 цикле ночь короче диапазона отката). */
    private static final int PHANTOM_ROLL_MIN = 30, PHANTOM_ROLL_MAX = 90;

    /** Тег стрел из "дождя". */
    public static final String ARROW_TAG = "svo_chaos_arrow";
    private static final int ARROW_RAIN_INTERVAL = 60;  // раз в 1 мин
    private static final int ARROW_RAIN_DURATION = 200; // 10 сек
    private static final double ARROW_RAIN_RADIUS = 10.0;
    private static final double BORDER_BUFFER = 5.0;    // отступ от барьера, чтобы не улететь за зону

    private static final int LIGHTNING_INTERVAL = 15; // раз в 15 сек (ещё вдвое чаще прежних 30 сек)

    private static final int SWAP_INTERVAL = 360; // раз в 6 мин (вдвое реже прежних 3 мин)

    private static final int NUKE_GIVE_INTERVAL = 600; // раз в 10 мин

    /** Тег-сигнал "режим Хаос сейчас идёт" - для внешних плагинов без прямой зависимости от
     *  VolanSVO (напр. ItemUpgrader: machine.chaos.mode=tag, value=svo_chaos_active). Ставится
     *  на всех участников раунда в start(), снимается со всех онлайн в stop(). */
    public static final String CHAOS_ACTIVE_TAG = "svo_chaos_active";

    /** Координаты игрового автомата ItemUpgrader на карте east (в игровом мире, не в шаблоне).
     *  Если автомат перенесут - поменять только эти три числа. */
    private static final int UPGRADER_MACHINE_X = 422;
    private static final int UPGRADER_MACHINE_Y = 75;
    private static final int UPGRADER_MACHINE_Z = -555;

    private static final int TIME_SPEED = 5; // день/ночь идёт в 5 раз быстрее

    /** Общий тег обеих враждующих банд - по нему EntityListener гасит их агро на игроков. */
    public static final String FACTION_TAG = "svo_chaos_faction";
    private static final String HEZ_TAG    = "svo_chaos_hez";
    private static final String TZAHAL_TAG = "svo_chaos_tzahal";
    private static final int FACTION_SPAWN_MIN = 30;  // случайный интервал 30 сек - 2 мин
    private static final int FACTION_SPAWN_MAX = 120;
    private static final double FACTION_SPAWN_RADIUS = 20.0; // спавн у случайного игрока, не по всей зоне
    private static final double FACTION_AGGRO_RANGE = 200.0;
    /** Потолок населения КАЖДОЙ банды - иначе за долгую игру толпа только растёт (спавн чаще
     *  чем взаимное истребление) и full-world сканы/парные сравнения душат TPS. */
    private static final int FACTION_CAP_PER_SIDE = 10;
    private int nextFactionSpawn;
    /** Живые бойцы банд, отслеживаем по UUID вместо full-world скана каждую секунду. */
    private final Set<UUID> hezIds = new HashSet<UUID>();
    private final Set<UUID> tzahalIds = new HashSet<UUID>();
    /** Перенацеливание банд крутится ЧАЩЕ основного 1-сек тика (отдельная задача) - иначе
     *  между проверками ванильный AI успевает сам сбросить цель (потеря видимости и т.п.),
     *  и топорщики выглядят так, будто дёргано теряют/хватают агро каждые пару секунд. */
    private BukkitTask factionTask;
    private static final long FACTION_RETARGET_PERIOD_TICKS = 5L;

    /** Топорщик-смертник ХЕЗБОЛЛЫ (каждый 4-й): красные партиклы, взрывается в 3 бл от МЕЦАХ/ООН. */
    private static final String HEZ_BOMBER_TAG = "svo_chaos_hez_bomber";
    private static final int HEZ_BOMBER_EVERY = 4; // каждый 4-й топорщик - смертник
    private static final double HEZ_BOMBER_RANGE = 3.0;
    private final Set<UUID> hezBomberIds = new HashSet<UUID>();
    /** Сквозной счётчик заспавненных топорщиков ХЕЗБОЛЛЫ - для "каждый 4-й". */
    private int hezVindSpawnCount = 0;

    /** Игрок, ударивший банду - вся банда (доп. цель, без приоритета) агрится на него 1 мин. */
    private static final long FACTION_HATE_DURATION_MS = 60_000L;
    private final Map<UUID, Long> hezHatedUntil = new HashMap<UUID, Long>();
    private final Map<UUID, Long> tzahalHatedUntil = new HashMap<UUID, Long>();

    /** Третья, невраждебная сторона - "Наблюдатель ООН", обычные крестьяне. Обе банды
     *  считают их врагами (доп. цели), сами крестьяне не нападают ни на кого. */
    private static final String UN_TAG = "svo_chaos_un";
    private static final int UN_CAP = 10;
    private final Set<UUID> unIds = new HashSet<UUID>();

    private static final String EVOKER_TAG = "svo_chaos_evoker";
    private static final int EVOKER_SPAWN_INTERVAL = 180; // раз в 3 мин
    private static final double EVOKER_SPAWN_RADIUS = 100.0; // только в пределах 100 бл от игрока
    private static final double EVOKER_AGGRO_RANGE = 50.0;
    private static final int EVOKER_CAP = 6;
    private static final int PIGSTEP_DURATION_SEC = 148; // длительность диска pigstep
    private int nextEvokerSpawn;
    private final Set<UUID> evokerIds = new HashSet<UUID>();
    private final Map<UUID, UUID> evokerMusicTarget = new HashMap<UUID, UUID>(); // evokerId -> playerId
    private final Map<UUID, Integer> evokerMusicTicks = new HashMap<UUID, Integer>();

    private static final PotionEffectType[] GOOD_EFFECTS = {
        PotionEffectType.SPEED, PotionEffectType.JUMP_BOOST, PotionEffectType.FIRE_RESISTANCE,
        PotionEffectType.NIGHT_VISION, PotionEffectType.HASTE, PotionEffectType.LUCK
    };
    private static final PotionEffectType[] BAD_EFFECTS = {
        PotionEffectType.SLOWNESS, PotionEffectType.WEAKNESS, PotionEffectType.NAUSEA,
        PotionEffectType.BLINDNESS, PotionEffectType.MINING_FATIGUE, PotionEffectType.UNLUCK,
        PotionEffectType.HUNGER, PotionEffectType.GLOWING
    };

    public ChaosManager(VolanSVO plugin) {
        this.plugin = plugin;
    }

    public void start(final World gameWorld) {
        stop();
        // Пересеиваем ГСЧ свежей энтропией на каждый запуск Хаоса - убирает даже теоретическую
        // возможность, что подряд идущие игры на одном аптайме сервера видят похожую последовательность.
        rng.setSeed(System.nanoTime() ^ System.currentTimeMillis() ^ gameWorld.hashCode());
        this.world = gameWorld;
        this.active = true;
        this.nextArrowRain = ARROW_RAIN_INTERVAL;
        this.nextLightning = 30 + rng.nextInt(30);
        this.nextSwap = SWAP_INTERVAL;
        this.nextNukeGive = NUKE_GIVE_INTERVAL;
        this.nextFactionSpawn = randSec(FACTION_SPAWN_MIN, FACTION_SPAWN_MAX);
        this.nextEvokerSpawn = EVOKER_SPAWN_INTERVAL;
        this.evokerMusicTarget.clear();
        this.evokerMusicTicks.clear();
        this.phantomIds.clear();
        this.hezIds.clear();
        this.tzahalIds.clear();
        this.hezBomberIds.clear();
        this.hezVindSpawnCount = 0;
        this.hezHatedUntil.clear();
        this.tzahalHatedUntil.clear();
        this.evokerIds.clear();
        this.unIds.clear();

        spawnFallingStands(gameWorld);

        // Сигнал для внешних плагинов (напр. ItemUpgrader: machine.chaos.mode=tag,
        // value=CHAOS_ACTIVE_TAG) - "режим Хаос сейчас идёт". Тегируем всех участников раунда,
        // а не только текущих живых - тег должен быть виден, пока Хаос активен, независимо
        // от того, кто именно из них жив/наблюдает в данный момент.
        try {
            for (Player p : plugin.getGameManager().getAllSvoPlayers()) p.addScoreboardTag(CHAOS_ACTIVE_TAG);
        } catch (Throwable ignored) {}

        // Игровой автомат ItemUpgrader на east - спавним ПРЯМО в текущем игровом мире, а не
        // в мире-шаблоне: стенд из шаблона переживает клонирование физически, но плагин не
        // всегда успевает его переоткрыть в клоне, поэтому проще заново ставить каждый раунд
        // напрямую в eastgame_ (там команда отрабатывает сразу и без сбоев).
        if ("east".equals(plugin.getMapManager().getActiveMap() != null
                ? plugin.getMapManager().getActiveMap().getId() : null)) {
            try {
                gameWorld.loadChunk(UPGRADER_MACHINE_X >> 4, UPGRADER_MACHINE_Z >> 4, true);
                String cmd = "upgrader spawn " + gameWorld.getName() + " "
                    + UPGRADER_MACHINE_X + " " + UPGRADER_MACHINE_Y + " " + UPGRADER_MACHINE_Z;
                boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                plugin.getLogger().info("[СВО] Автомат апгрейдера: '" + cmd + "' -> " + ok);
            } catch (Throwable ignored) {}
        }

        mainTask = new BukkitRunnable() {
            int sec = 0;
            @Override public void run() {
                if (!active) { cancel(); return; }
                sec++;
                try {
                    if (sec % METEOR_INTERVAL_SEC == 0) spawnMeteor();
                    tickPlayers(sec);
                    if (sec >= nextArrowRain) { startArrowRain(); nextArrowRain = sec + ARROW_RAIN_INTERVAL; }
                    if (sec >= nextLightning) { strikeLightning(); nextLightning = sec + LIGHTNING_INTERVAL; }
                    if (sec >= nextSwap) { trySwapInventories(); nextSwap = sec + SWAP_INTERVAL; }
                    if (sec >= nextNukeGive) { giveRandomNuke(); nextNukeGive = sec + NUKE_GIVE_INTERVAL; }
                    if (sec >= nextFactionSpawn) {
                        spawnFactionWave();
                        spawnUnWave();
                        nextFactionSpawn = sec + randSec(FACTION_SPAWN_MIN, FACTION_SPAWN_MAX);
                    }
                    if (sec >= nextEvokerSpawn) { spawnEvoker(); nextEvokerSpawn = sec + EVOKER_SPAWN_INTERVAL; }
                    tickEvokers();
                    if (sec % CHUNK_SWEEP_INTERVAL_SEC == 0) sweepForcedChunksOutsideBorder();
                } catch (Throwable ignored) {}
            }
        }.runTaskTimer(plugin, 20L, 20L);

        phantomTask = new BukkitRunnable() {
            @Override public void run() {
                if (!active) { cancel(); return; }
                try { tickPhantoms(); } catch (Throwable ignored) {}
            }
        }.runTaskTimer(plugin, 20L, 20L);

        // Отдельная, более частая задача - перенацеливание банд друг на друга (см. комментарий
        // у FACTION_RETARGET_PERIOD_TICKS).
        factionTask = new BukkitRunnable() {
            @Override public void run() {
                if (!active) { cancel(); return; }
                try {
                    tickFactionTargeting();
                    tickHezBombers();
                } catch (Throwable ignored) {}
            }
        }.runTaskTimer(plugin, FACTION_RETARGET_PERIOD_TICKS, FACTION_RETARGET_PERIOD_TICKS);

        // Ускоренный день/ночь цикл (x5): гасим ванильный тик времени и крутим его сами.
        try { gameWorld.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false); } catch (Throwable ignored) {}
        timeTask = new BukkitRunnable() {
            @Override public void run() {
                if (!active || world == null) { cancel(); return; }
                world.setFullTime(world.getFullTime() + TIME_SPEED);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public void stop() {
        active = false;
        World w = world;
        if (mainTask != null) { mainTask.cancel(); mainTask = null; }
        if (phantomTask != null) { phantomTask.cancel(); phantomTask = null; }
        if (factionTask != null) { factionTask.cancel(); factionTask = null; }
        if (timeTask != null) { timeTask.cancel(); timeTask = null; }
        if (w != null) {
            try { w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true); } catch (Throwable ignored) {}
        }
        // Останавливаем любую висящую музыку pigstep, чтобы не играла после конца хаоса.
        for (UUID playerId : evokerMusicTarget.values()) {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null) p.stopSound(Sound.MUSIC_DISC_PIGSTEP, SoundCategory.RECORDS);
        }
        evokerMusicTarget.clear();
        evokerMusicTicks.clear();
        phantomIds.clear();
        hezIds.clear();
        tzahalIds.clear();
        hezBomberIds.clear();
        hezHatedUntil.clear();
        tzahalHatedUntil.clear();
        evokerIds.clear();
        unIds.clear();
        nextBlast.clear();
        nextEffect.clear();
        nextPhantom.clear();
        // Снимаем сигнал "Хаос идёт" (см. start()) со всех, у кого он мог остаться - в т.ч.
        // с тех, кто вышел/переподключился за время раунда, поэтому проще снять со всех онлайн.
        try {
            for (Player p : Bukkit.getOnlinePlayers()) p.removeScoreboardTag(CHAOS_ACTIVE_TAG);
        } catch (Throwable ignored) {}
        world = null;
    }

    // ----- метеор -----

    private void spawnMeteor() {
        final World w = world;
        if (w == null) return;
        List<Player> active = plugin.getGameManager().getActivePlayers();
        if (active.isEmpty()) return;

        double[] box = spawnBoxOf(plugin.getMapManager().getActiveMap());
        double minX = box[0], maxX = box[1], minZ = box[2], maxZ = box[3];

        // Пересекаем с ТЕКУЩЕЙ (уже сжавшейся) зоной - иначе метеор к концу игры бьёт
        // далеко за пределами реальной играбельной области (там где никого никогда
        // не будет), форс-грузя чанки за барьером - лишняя, ничем не оправданная
        // нагрузка, которая и топит TPS к концу долгой игры.
        WorldBorder wb = w.getWorldBorder();
        double half = wb.getSize() / 2.0 - BORDER_BUFFER;
        if (half > 0) {
            double bcx = wb.getCenter().getX();
            double bcz = wb.getCenter().getZ();
            minX = Math.max(minX, bcx - half);
            maxX = Math.min(maxX, bcx + half);
            minZ = Math.max(minZ, bcz - half);
            maxZ = Math.min(maxZ, bcz + half);
        }
        if (minX > maxX || minZ > maxZ) return; // зона вне спавн-бокса - пропускаем этот метеор

        final double fx = minX + rng.nextDouble() * (maxX - minX);
        final double fz = minZ + rng.nextDouble() * (maxZ - minZ);

        final int cx = ((int) Math.floor(fx)) >> 4;
        final int cz = ((int) Math.floor(fz)) >> 4;

        // Не форс-грузим чанк искусственно: если он и так не загружен, значит рядом
        // никого нет - просто пропускаем этот метеор. Иначе получали лишнюю нагрузку
        // (грузили далёкие чанки без единого игрока) и риск "забытых" форс-чанков.
        if (!w.isChunkLoaded(cx, cz)) return;

        final int groundY = w.getHighestBlockYAt((int) Math.floor(fx), (int) Math.floor(fz));

        // Долгий свист (кастомный звук) - играем СЕЙЧАС, за METEOR_WARNING_TICKS (4 сек) ДО
        // того как фаербол реально появится и полетит (см. spawnMeteorFall ниже). Раньше звук
        // играл прямо в момент появления, а сам полёт длится ~1.5 сек - предупреждение приходило
        // слишком поздно, чтобы среагировать. Точка приземления уже решена, так что бьём по
        // тем же игрокам в радиусе поражения (withinLandingSound), что и раньше.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(w) && withinLandingSound(p, fx, groundY, fz)) {
                p.playSound(p.getLocation(), "custom:svist_dolgyi", 3.0f, 1.0f);
            }
        }

        new BukkitRunnable() {
            @Override public void run() {
                if (!ChaosManager.this.active || world == null) return;
                // Чанк выгрузился за эти 4 сек (игрок ушёл) - фаербол просто не появляется,
                // форс-грузить его ради этого не нужно.
                if (!w.isChunkLoaded(cx, cz)) return;
                spawnMeteorFall(w, fx, fz, cx, cz, groundY);
            }
        }.runTaskLater(plugin, METEOR_WARNING_TICKS);
    }

    /** Сам полёт и приземление фаербола - вынесено отдельно, запускается через
     *  METEOR_WARNING_TICKS после предупреждающего свиста (см. spawnMeteor). */
    private void spawnMeteorFall(final World w, final double fx, final double fz,
                                  final int cx, final int cz, final int groundY) {
        new BukkitRunnable() {
            double y = METEOR_START_Y;
            int t = 0;
            @Override public void run() {
              try {
                // Чанк выгрузился посреди полёта (игрок ушёл) - просто гасим метеор,
                // а не тянем чанк обратно ради существа без единого зрителя.
                if (!ChaosManager.this.active || world == null || !w.isChunkLoaded(cx, cz)) {
                    cancel();
                    return;
                }
                t++;
                y -= METEOR_FALL_SPEED;
                boolean landed = y <= groundY;
                double drawY = landed ? groundY : y;

                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!p.getWorld().equals(w)) continue;
                    // Партиклы - видны рядом с текущим положением фаербола (визуал полёта).
                    if (withinVisual(p, fx, drawY, fz)) {
                        p.spawnParticle(Particle.FLAME, fx, drawY, fz, 6, 0.3, 0.3, 0.3, 0.01);
                        p.spawnParticle(Particle.LARGE_SMOKE, fx, drawY, fz, 2, 0.2, 0.2, 0.2, 0.01);
                        if (!landed) {
                            for (int a = 0; a < 360; a += 30) {
                                double rad = Math.toRadians(a);
                                double rx = fx + Math.cos(rad) * 4.0;
                                double rz = fz + Math.sin(rad) * 4.0;
                                p.spawnParticle(Particle.DUST, rx, groundY + 0.2, rz, 0, 0, 0, 0, 0,
                                    new Particle.DustOptions(Color.ORANGE, 1.4f), true);
                            }
                        }
                    }
                    // Звук полёта больше не повторяем каждые 4 тика - долгий свист уже
                    // проигран заранее (см. spawnMeteor).
                }

                if (landed) {
                    Location impact = new Location(w, fx, groundY, fz);
                    try { w.createExplosion(fx, groundY, fz, METEOR_POWER, false, true); } catch (Throwable ignored) {}
                    cleanupExplosionDrops(w, impact, 24.0); // не даём тысячам предметов из кратора валяться вечно
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getWorld().equals(w) && withinLandingSound(p, fx, groundY, fz)) {
                            p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 4.0f, 0.8f);
                        }
                    }
                    cancel();
                    return;
                }
                if (t > 400) cancel(); // защита от бесконечного полёта
              } catch (Throwable ex) {
                cancel(); // любое исключение просто гасит метеор - никаких чанков держать не нужно
              }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Подчищает предметы, выпавшие из блоков, разрушенных нашим взрывом (не даём им валяться
     *  вечно и раздувать TPS - за долгую игру метеоров/взрывов десятки, предметов - тысячи). */
    private void cleanupExplosionDrops(final World w, final Location center, final double radius) {
        new BukkitRunnable() {
            @Override public void run() {
                for (Entity e : w.getNearbyEntities(center, radius, radius, radius)) {
                    if (e instanceof Item) e.remove();
                }
            }
        }.runTaskLater(plugin, 3L); // даём взрыву долёт создать дропы, потом чистим
    }

    /** Видимость партиклов полёта метеора (текущая высота, не место приземления). */
    private boolean withinVisual(Player p, double x, double y, double z) {
        return p.getLocation().distanceSquared(new Location(p.getWorld(), x, y, z)) <= METEOR_VISUAL_RADIUS * METEOR_VISUAL_RADIUS;
    }

    /** Слышимость звуков метеора (свист/взрыв) - только рядом с местом ПРИЗЕМЛЕНИЯ. */
    private boolean withinLandingSound(Player p, double x, double y, double z) {
        return p.getLocation().distanceSquared(new Location(p.getWorld(), x, y, z))
            <= METEOR_LANDING_SOUND_RADIUS * METEOR_LANDING_SOUND_RADIUS;
    }

    private void unforce(World w, int cx, int cz) {
        if (w == null) return;
        try { w.setChunkForceLoaded(cx, cz, false); } catch (Throwable ignored) {}
    }

    /** Страховка: снимает форс-загрузку с любых чанков мира, оказавшихся за текущим
     *  (сжавшимся) барьером. Барьер двигается сам, но форс-тикеты за собой не убирает -
     *  без этой чистки такой чанк остался бы загруженным до конца игры (а форс-тикеты
     *  Paper переживают даже рестарт сервера). */
    private void sweepForcedChunksOutsideBorder() {
        World w = world;
        if (w == null) return;
        WorldBorder wb = w.getWorldBorder();
        for (Chunk c : new ArrayList<Chunk>(w.getForceLoadedChunks())) {
            Location center = new Location(w, (c.getX() << 4) + 8, 64, (c.getZ() << 4) + 8);
            if (!wb.isInside(center)) {
                try { w.setChunkForceLoaded(c.getX(), c.getZ(), false); } catch (Throwable ignored) {}
            }
        }
    }

    // ----- per-player: мини-взрыв / эффект / ночной фантом -----

    private void tickPlayers(int sec) {
        boolean night = isNight();
        for (Player p : plugin.getGameManager().getActivePlayers()) {
            if (p == null || !p.isOnline()) continue;
            UUID id = p.getUniqueId();

            Integer nb = nextBlast.get(id);
            if (nb == null) nextBlast.put(id, sec + randSec(30, 90));
            else if (sec >= nb) {
                if (p.isOnGround() && !p.isInsideVehicle()) {
                    smallBlastNear(p);
                    nextBlast.put(id, sec + randSec(30, 90));
                } else {
                    nextBlast.put(id, sec + 5); // в воздухе/в транспорте - пробуем снова через 5 сек
                }
            }

            Integer ne = nextEffect.get(id);
            if (ne == null) nextEffect.put(id, sec + randSec(30, 90));
            else if (sec >= ne) {
                applyRandomEffect(p);
                nextEffect.put(id, sec + randSec(30, 90));
            }

            // Таймер крутится и днём и ночью (не завязан на день/ночь) - спавн срабатывает,
            // только если СЕЙЧАС ночь. При ускоренном x5 цикле ночь короче диапазона отката,
            // поэтому таймер, который стартует только в момент наступления ночи, часто
            // промахивался мимо неё целиком - отсюда фантомы почти не спавнились.
            Integer np = nextPhantom.get(id);
            if (np == null) nextPhantom.put(id, sec + randSec(PHANTOM_ROLL_MIN, PHANTOM_ROLL_MAX));
            else if (sec >= np) {
                if (night) spawnBomberPhantom(p);
                nextPhantom.put(id, sec + randSec(PHANTOM_ROLL_MIN, PHANTOM_ROLL_MAX));
            }
        }
    }

    private int randSec(int minSec, int maxSec) {
        return minSec + rng.nextInt(maxSec - minSec + 1);
    }

    private boolean isNight() {
        if (world == null) return false;
        long t = world.getTime() % 24000L;
        return t >= 13000L && t <= 23000L;
    }

    private static final int BLAST_SMOKE_TICKS = 100; // 5 сек предупреждения дымом
    private static final int BLAST_TNT_FUSE = 30;      // ещё 1.5 сек - видно сам ТНТ перед взрывом
    /** Тег ТНТ мини-взрыва - по нему EntityListener капает урон игроку, чтобы не убивало
     *  с одного взрыва. Урон капается ДО применения (через EntityDamageEvent), а не ручным
     *  setHealth() после взрыва - тем самым багом, что раньше ломал респавн. */
    public static final String BLAST_TNT_TAG = "svo_chaos_blast_tnt";
    /** 4 сердца - потолок урона от мини-взрыва для игрока БЕЗ брони (с бронёй будет меньше,
     *  она снижает урон как обычно поверх этого потолка). */
    public static final double BLAST_TNT_DAMAGE_CAP = 8.0;

    /** Точка в паре блоков от игрока начинает дымиться (предупреждение), через 5 сек там
     *  призывается настоящий ТНТ - самостоятельно взрывается по ванильным правилам. Никакого
     *  ручного вмешательства в здоровье/дамаг после этого не делаем (в отличие от старой версии,
     *  где ручной setHealth() сразу после createExplosion иногда ломал респавн) - реальную
     *  смерть от такого ТНТ ванильный пайплайн (DeathListener/eliminatePlayer) обработает
     *  штатно. Игрок предупреждён дымом заранее и может уйти - без сообщения в чат. */
    /** Места, где дымит и скоро появится динамит (для ботов: отойти заранее). */
    private final java.util.List<Location> pendingBlasts = new java.util.concurrent.CopyOnWriteArrayList<Location>();

    public java.util.List<Location> pendingBlasts() { return pendingBlasts; }

    private void smallBlastNear(Player p) {
        if (p == null || !p.isOnline()) return;
        if (p.isInsideVehicle()) return;   // сидит на чём-то - не рвём рядом
        if (!p.isOnGround()) return;       // в воздухе - тоже пропускаем (решается в tickPlayers)
        double ox = rng.nextDouble() * 6 - 3;
        double oz = rng.nextDouble() * 6 - 3;
        final Location loc = p.getLocation().add(ox, 0.2, oz);
        final World w = loc.getWorld();
        pendingBlasts.add(loc);
        new BukkitRunnable() {
            @Override public void run() { pendingBlasts.remove(loc); }
        }.runTaskLater(plugin, BLAST_SMOKE_TICKS + BLAST_TNT_FUSE + 5L);

        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!active || world == null || !w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                    cancel();
                    return;
                }
                t++;
                w.spawnParticle(Particle.LARGE_SMOKE, loc.getX(), loc.getY() + t * 0.03, loc.getZ(),
                    2, 0.15, 0.1, 0.15, 0.01);
                if (t % 20 == 0) {
                    w.playSound(loc, Sound.BLOCK_FIRE_AMBIENT, 2.0f, 0.6f);
                }
                if (t >= BLAST_SMOKE_TICKS) {
                    cancel();
                    try {
                        TNTPrimed tnt = (TNTPrimed) w.spawnEntity(loc, EntityType.TNT);
                        tnt.setFuseTicks(BLAST_TNT_FUSE);
                        tnt.setIsIncendiary(false);
                        tnt.addScoreboardTag(BLAST_TNT_TAG);
                    } catch (Throwable ignored) {}
                    // Чистим дроп только ПОСЛЕ того как ТНТ реально взорвётся (фитиль ещё горит).
                    new BukkitRunnable() {
                        @Override public void run() { cleanupExplosionDrops(w, loc, 16.0); }
                    }.runTaskLater(plugin, BLAST_TNT_FUSE + 2L);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Случайный статус-эффект ИЛИ прямое здоровье (не более 3 сердец), никогда не убивает. */
    private void applyRandomEffect(Player p) {
        if (p == null || !p.isOnline()) return;
        if (rng.nextInt(10) < 3) {
            double delta = 2.0 + rng.nextDouble() * 4.0; // 2..6 HP = 1..3 сердца
            boolean heal = rng.nextBoolean();
            if (heal) {
                org.bukkit.attribute.AttributeInstance maxAttr = p.getAttribute(Attribute.MAX_HEALTH);
                double maxHealth = maxAttr != null ? maxAttr.getValue() : 20.0;
                p.setHealth(Math.min(maxHealth, p.getHealth() + delta));
                p.sendMessage(ChatColor.GREEN + "Хаос: тебя исцелило!");
                p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 2, 0), 6, 0.3, 0.3, 0.3, 0);
            } else {
                p.setHealth(Math.max(1.0, p.getHealth() - delta));
                p.sendMessage(ChatColor.RED + "Хаос: тебя ранило!");
                p.getWorld().spawnParticle(Particle.CRIT, p.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0);
            }
            return;
        }
        boolean good = rng.nextBoolean();
        PotionEffectType[] pool = good ? GOOD_EFFECTS : BAD_EFFECTS;
        PotionEffectType type = pool[rng.nextInt(pool.length)];
        p.addPotionEffect(new PotionEffect(type, 400, 0, true, true, true)); // 20 сек
        p.sendMessage((good ? ChatColor.GREEN : ChatColor.RED) + "Хаос: " + effectRuName(type));
    }

    private String effectRuName(PotionEffectType t) {
        if (t == PotionEffectType.SPEED) return "ускорение";
        if (t == PotionEffectType.JUMP_BOOST) return "прыгучесть";
        if (t == PotionEffectType.FIRE_RESISTANCE) return "огнестойкость";
        if (t == PotionEffectType.NIGHT_VISION) return "ночное зрение";
        if (t == PotionEffectType.HASTE) return "спешка";
        if (t == PotionEffectType.LUCK) return "удача";
        if (t == PotionEffectType.SLOWNESS) return "медлительность";
        if (t == PotionEffectType.WEAKNESS) return "слабость";
        if (t == PotionEffectType.NAUSEA) return "тошнота";
        if (t == PotionEffectType.BLINDNESS) return "слепота";
        if (t == PotionEffectType.MINING_FATIGUE) return "усталость";
        if (t == PotionEffectType.UNLUCK) return "неудача";
        if (t == PotionEffectType.HUNGER) return "голод";
        if (t == PotionEffectType.GLOWING) return "свечение";
        return t.getKey().getKey();
    }

    // ----- ночной фантом-подрывник -----

    private void spawnBomberPhantom(Player p) {
        if (p == null || !p.isOnline()) return;
        World w = p.getWorld();
        Location loc = p.getLocation().add(0, 15 + rng.nextInt(6), 0);
        try {
            final Phantom ph = (Phantom) w.spawnEntity(loc, EntityType.PHANTOM);
            ph.setSize(0);
            ph.addScoreboardTag(PHANTOM_TAG);
            ph.setRemoveWhenFarAway(false);
            ph.setPersistent(true);
            phantomIds.add(ph.getUniqueId());
            new BukkitRunnable() {
                @Override public void run() {
                    if (ph.isValid()) ph.remove();
                    phantomIds.remove(ph.getUniqueId());
                }
            }.runTaskLater(plugin, 600L); // 30 сек - не подорвался, сам исчезает
        } catch (Throwable ignored) {}
    }

    /** Отслеживаем фантомов по UUID (не сканируем весь мир каждый тик - дорого на долгой игре). */
    private void tickPhantoms() {
        World w = world;
        if (w == null) return;
        Iterator<UUID> it = phantomIds.iterator();
        while (it.hasNext()) {
            Entity e = Bukkit.getEntity(it.next());
            if (e == null || e.isDead() || !(e instanceof Phantom)) { it.remove(); continue; }
            Location eloc = e.getLocation();
            double bestDist = Double.MAX_VALUE;
            for (Player p : plugin.getGameManager().getActivePlayers()) {
                if (p == null || !p.isOnline() || !p.getWorld().equals(w)) continue;
                double d = p.getLocation().distanceSquared(eloc);
                if (d < bestDist) bestDist = d;
            }
            if (bestDist <= 4 * 4) {
                try { w.createExplosion(eloc, 4.0f, false, true); } catch (Throwable ignored) {}
                cleanupExplosionDrops(w, eloc, 12.0);
                e.remove(); // фантом пропадает сразу после взрыва
                it.remove();
            }
        }
    }

    // ----- дождь из стрел (только внутри игровой зоны - текущего worldborder) -----

    private void startArrowRain() {
        final World w = world;
        if (w == null) return;
        List<Player> activePlayers = plugin.getGameManager().getActivePlayers();
        if (activePlayers.isEmpty()) return;

        // Дождь идёт только над ОДНИМ случайным игроком (а не над случайными точками всей,
        // зачастую огромной, зоны) - иначе стрелы падали там где никого нет и рейн выглядел
        // как "ничего не происходит". Игрок гарантированно внутри барьера, так что это
        // автоматически удовлетворяет "только внутри игровой зоны".
        final List<Player> targets = java.util.Collections.singletonList(
            activePlayers.get(rng.nextInt(activePlayers.size())));

        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!active || world == null) { cancel(); return; }
                t++;
                if (t % 4 == 0) {
                    for (Player target : targets) {
                        if (target == null || !target.isOnline() || !target.getWorld().equals(w)) continue;
                        Location tl = target.getLocation();
                        spawnRainArrow(w, tl.getX(), tl.getZ());
                        spawnRainArrow(w, tl.getX(), tl.getZ());
                    }
                }
                if (t >= ARROW_RAIN_DURATION) {
                    cancel();
                    cleanupRainArrows(w);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Спектральные стрелы сами подсвечивают того, в кого попадут - никаких доп. эффектов не нужно. */
    private void spawnRainArrow(World w, double centerX, double centerZ) {
        double x = centerX + (rng.nextDouble() * 2 - 1) * ARROW_RAIN_RADIUS;
        double z = centerZ + (rng.nextDouble() * 2 - 1) * ARROW_RAIN_RADIUS;
        int groundY = w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
        double y = groundY + 20 + rng.nextInt(10);
        try {
            SpectralArrow arrow = w.spawn(new Location(w, x, y, z), SpectralArrow.class);
            arrow.setVelocity(new Vector(0, -1.2, 0));
            arrow.setGravity(true);
            arrow.setShooter(null);
            arrow.setPickupStatus(AbstractArrow.PickupStatus.CREATIVE_ONLY);
            arrow.addScoreboardTag(ARROW_TAG);
        } catch (Throwable ignored) {}
    }

    /** Стрелы, не попавшие ни в кого и оставшиеся валяться - подчищаем через 5 сек после конца дождя. */
    private void cleanupRainArrows(final World w) {
        new BukkitRunnable() {
            @Override public void run() {
                for (Entity e : new ArrayList<Entity>(w.getEntities())) {
                    if (e.getScoreboardTags().contains(ARROW_TAG)) e.remove();
                }
            }
        }.runTaskLater(plugin, 100L);
    }

    // ----- молния (только внутри игровой зоны, без урона, может поджечь траву) -----

    private void strikeLightning() {
        World w = world;
        if (w == null) return;
        WorldBorder wb = w.getWorldBorder();
        double half = wb.getSize() / 2.0 - BORDER_BUFFER;
        if (half <= 0) return;
        double x = wb.getCenter().getX() + (rng.nextDouble() * 2 - 1) * half;
        double z = wb.getCenter().getZ() + (rng.nextDouble() * 2 - 1) * half;
        // Нет игроков рядом - чанк не загружен естественно, пропускаем удар вместо
        // того чтобы грузить пустой чанк ради никем не увиденной молнии.
        if (!w.isChunkLoaded(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4)) return;
        int y = w.getHighestBlockYAt((int) x, (int) z);
        Location loc = new Location(w, x, y, z);
        try { w.strikeLightningEffect(loc); } catch (Throwable ignored) {} // только звук/вспышка, без урона

        // С шансом поджигаем траву/листья под ударом (сама strikeLightningEffect ничего не зажигает).
        if (rng.nextInt(100) < 35) {
            try {
                Block below = loc.clone().subtract(0, 1, 0).getBlock();
                Block target = loc.getBlock();
                if (target.getType() == Material.AIR && isFlammableGround(below.getType())) {
                    target.setType(Material.FIRE);
                }
            } catch (Throwable ignored) {}
        }
    }

    private boolean isFlammableGround(Material m) {
        switch (m) {
            case GRASS_BLOCK: case SHORT_GRASS: case TALL_GRASS:
            case OAK_LEAVES: case SPRUCE_LEAVES: case BIRCH_LEAVES:
            case JUNGLE_LEAVES: case ACACIA_LEAVES: case DARK_OAK_LEAVES:
            case HAY_BLOCK:
                return true;
            default:
                return false;
        }
    }

    // ----- своп инвентарей -----

    private void trySwapInventories() {
        List<Player> activePlayers = plugin.getGameManager().getActivePlayers();
        if (activePlayers.size() < 2) return;
        Player a = activePlayers.get(rng.nextInt(activePlayers.size()));
        Player b;
        do { b = activePlayers.get(rng.nextInt(activePlayers.size())); } while (b.equals(a));

        ItemStack[] aContents = a.getInventory().getContents().clone();
        ItemStack[] bContents = b.getInventory().getContents().clone();
        ItemStack[] aArmor = a.getInventory().getArmorContents().clone();
        ItemStack[] bArmor = b.getInventory().getArmorContents().clone();
        ItemStack aOff = a.getInventory().getItemInOffHand().clone();
        ItemStack bOff = b.getInventory().getItemInOffHand().clone();

        a.getInventory().setContents(bContents);
        a.getInventory().setArmorContents(bArmor);
        a.getInventory().setItemInOffHand(bOff);

        b.getInventory().setContents(aContents);
        b.getInventory().setArmorContents(aArmor);
        b.getInventory().setItemInOffHand(aOff);

        // Тег shsender не из этого плагина (ставится где-то вне Java-кода) - после свопа
        // инвентарей он уже не актуален ни у кого из пары, снимаем с обоих.
        a.removeScoreboardTag("shsender");
        b.removeScoreboardTag("shsender");

        a.sendMessage(ChatColor.LIGHT_PURPLE + "Хаос: тебя поменяло местами с " + ChatColor.WHITE + b.getName() + ChatColor.LIGHT_PURPLE + "!");
        b.sendMessage(ChatColor.LIGHT_PURPLE + "Хаос: тебя поменяло местами с " + ChatColor.WHITE + a.getName() + ChatColor.LIGHT_PURPLE + "!");
    }

    // ----- ядерная бомба ООН случайному игроку -----

    /** Раз в NUKE_GIVE_INTERVAL выдаёт случайному активному игроку ядерную бомбу командой
     *  внешнего плагина (nukestrike). Выполняем от консоли с явным ником целью - от имени
     *  самого игрока команда проходила бы через его права и могла молча не сработать
     *  без разрешения на nukestrike, у консоли прав хватает всегда. */
    private void giveRandomNuke() {
        // Только живому человеку: ботам ядерка не выдаётся никогда.
        List<Player> active = new java.util.ArrayList<Player>();
        for (Player o : plugin.getGameManager().getActivePlayers()) {
            if (!plugin.getGameManager().isBot(o.getUniqueId())) active.add(o);
        }
        if (active.isEmpty()) return;
        Player p = active.get(rng.nextInt(active.size()));
        // Команда плагина MilitaryCraft - "nuke", а не "nukestrike:nuke" (ошибка в префиксе
        // была причиной того, что предмет не выдавался, хотя сообщение в чат уже уходило).
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "nuke give " + p.getName());
        p.sendMessage(ChatColor.GOLD + "Ты счастливчик, ООН передал тебе миротворческую бомбу");
    }

    // ----- падающие стенды razboist на старте -----

    private void spawnFallingStands(final World w) {
        double[] box = spawnBoxOf(plugin.getMapManager().getActiveMap());
        double minX = box[0], maxX = box[1], minZ = box[2], maxZ = box[3], spawnY = box[4];

        for (int i = 0; i < 3; i++) {
            double x = minX + rng.nextDouble() * (maxX - minX);
            double z = minZ + rng.nextDouble() * (maxZ - minZ);
            final int cx = ((int) Math.floor(x)) >> 4;
            final int cz = ((int) Math.floor(z)) >> 4;
            try {
                w.getChunkAt(cx, cz);
                w.setChunkForceLoaded(cx, cz, true);
            } catch (Throwable ignored) {}

            try {
                ArmorStand stand = (ArmorStand) w.spawnEntity(new Location(w, x, spawnY + 150, z), EntityType.ARMOR_STAND);
                stand.setGravity(true);
                stand.setRemoveWhenFarAway(false);
                stand.addScoreboardTag("svorazboist");
            } catch (Throwable ignored) {}

            new BukkitRunnable() {
                @Override public void run() { unforce(w, cx, cz); }
            }.runTaskLater(plugin, 200L); // 10 сек - достаточно чтобы упасть на землю
        }
    }

    /** [minX, maxX, minZ, maxZ, spawnY] квадрата спавна активной карты (или config-дефолт). */
    private double[] spawnBoxOf(MapData map) {
        double minX, maxX, minZ, maxZ, y;
        if (map != null && map.hasSpawnBox()) {
            minX = map.getSpawnMinX(); maxX = map.getSpawnMaxX();
            minZ = map.getSpawnMinZ(); maxZ = map.getSpawnMaxZ();
            y = map.getSpawnY();
        } else {
            minX = plugin.getConfig().getInt("spawn_box.min_x", -1085);
            maxX = plugin.getConfig().getInt("spawn_box.max_x", -85);
            minZ = plugin.getConfig().getInt("spawn_box.min_z", -36);
            maxZ = plugin.getConfig().getInt("spawn_box.max_z", 964);
            y = plugin.getConfig().getInt("spawn_box.min_y", 310);
        }
        return new double[]{minX, maxX, minZ, maxZ, y};
    }

    private void setAttr(LivingEntity e, Attribute attr, double val) {
        AttributeInstance ai = e.getAttribute(attr);
        if (ai != null) ai.setBaseValue(val);
    }

    // ----- ХЕЗБОЛЛА vs МЕЦАХ (боец ДНР vs боец Украины на Донбассе): игнорируют игроков,
    // дерутся друг с другом и с "Наблюдателем ООН" -----

    /** Названия банд зависят от карты: на Донбассе - боец ДНР/боец Украины, иначе - тема востока. */
    private String[] factionNames() {
        MapData m = plugin.getMapManager().getActiveMap();
        if (m != null && "svo".equals(m.getId())) {
            return new String[]{"боец ДНР", "боец Украины"};
        }
        return new String[]{"ХЕЗБОЛЛА", "МЕЦАХ"};
    }

    private void spawnFactionWave() {
        World w = world;
        if (w == null) return;
        if (plugin.getGameManager().getActivePlayers().isEmpty()) return;
        String[] names = factionNames();

        spawnFactionGroup(w, HEZ_TAG, hezIds, true, ChatColor.RED + names[0], ChatColor.DARK_RED + names[0]);
        spawnFactionGroup(w, TZAHAL_TAG, tzahalIds, false, ChatColor.BLUE + names[1], ChatColor.DARK_BLUE + names[1]);
    }

    private void spawnFactionGroup(World w, String factionTag, Set<UUID> tracked, boolean bomberEligible,
                                   String pilName, String vinName) {
        pruneDead(tracked);
        if (tracked.size() >= FACTION_CAP_PER_SIDE) return; // потолок - иначе банда растёт бесконечно

        double pHealth = plugin.getConfig().getDouble("bandit.pillager_health", 60.0);
        double pDmg    = plugin.getConfig().getDouble("bandit.pillager_attack_damage", 20.0);
        double vHealth = plugin.getConfig().getDouble("bandit.vindicator_health", 20.0);

        int pilCount  = 2 + rng.nextInt(5); // 2..6 (вдвое больше базовых 1-3)
        int vindCount = 2 + rng.nextInt(5); // 2..6

        for (int i = 0; i < pilCount && tracked.size() < FACTION_CAP_PER_SIDE; i++) {
            Location loc = randomNearActivePlayer(w, FACTION_SPAWN_RADIUS);
            if (loc == null) break;
            try {
                Pillager pil = (Pillager) w.spawnEntity(loc, EntityType.PILLAGER);
                setAttr(pil, Attribute.MAX_HEALTH, pHealth);
                pil.setHealth(pHealth);
                setAttr(pil, Attribute.ATTACK_DAMAGE, pDmg);
                pil.addScoreboardTag(FACTION_TAG);
                pil.addScoreboardTag(factionTag);
                pil.setRemoveWhenFarAway(false);
                pil.setCustomName(pilName);
                pil.setCustomNameVisible(true);
                tracked.add(pil.getUniqueId());
            } catch (Throwable ignored) {}
        }
        for (int i = 0; i < vindCount && tracked.size() < FACTION_CAP_PER_SIDE; i++) {
            Location loc = randomNearActivePlayer(w, FACTION_SPAWN_RADIUS);
            if (loc == null) break;
            try {
                Vindicator vin = (Vindicator) w.spawnEntity(loc, EntityType.VINDICATOR);
                setAttr(vin, Attribute.MAX_HEALTH, vHealth);
                vin.setHealth(vHealth);
                vin.addScoreboardTag(FACTION_TAG);
                vin.addScoreboardTag(factionTag);
                vin.setRemoveWhenFarAway(false);
                vin.setCustomName(vinName);
                vin.setCustomNameVisible(true);
                tracked.add(vin.getUniqueId());
                // Топорщик-смертник ХЕЗБОЛЛЫ (каждый 4-й по счёту): взорвётся при виде МЕЦАХ/ООН.
                if (bomberEligible) {
                    hezVindSpawnCount++;
                    if (hezVindSpawnCount % HEZ_BOMBER_EVERY == 0) {
                        vin.addScoreboardTag(HEZ_BOMBER_TAG);
                        hezBomberIds.add(vin.getUniqueId());
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    /** Третья сторона - обычные крестьяне (villager) с именем "Наблюдатель ООН", без баффов.
     *  Сами не нападают - просто добавочная цель для обеих банд. */
    private void spawnUnWave() {
        World w = world;
        if (w == null) return;
        pruneDead(unIds);
        if (unIds.size() >= UN_CAP) return;
        int count = 2 + rng.nextInt(3); // 2..4 за волну
        for (int i = 0; i < count && unIds.size() < UN_CAP; i++) {
            Location loc = randomNearActivePlayer(w, FACTION_SPAWN_RADIUS);
            if (loc == null) break;
            try {
                Villager v = (Villager) w.spawnEntity(loc, EntityType.VILLAGER);
                v.setRemoveWhenFarAway(false);
                v.addScoreboardTag(UN_TAG);
                v.setCustomName(ChatColor.AQUA + "Наблюдатель ООН");
                v.setCustomNameVisible(true);
                unIds.add(v.getUniqueId());
            } catch (Throwable ignored) {}
        }
    }

    /** Партиклы у топорщиков-смертников ХЕЗБОЛЛЫ + проверка взрыва (МЕЦАХ/ООН в 3 бл). */
    private void tickHezBombers() {
        if (world == null) return;
        Iterator<UUID> it = hezBomberIds.iterator();
        while (it.hasNext()) {
            Entity e = Bukkit.getEntity(it.next());
            if (e == null || e.isDead() || !(e instanceof Vindicator)) { it.remove(); continue; }
            World w = e.getWorld();
            Location loc = e.getLocation().add(0, 1, 0);
            w.spawnParticle(Particle.DUST, loc, 6, 0.3, 0.5, 0.3, 0, new Particle.DustOptions(Color.RED, 1.2f));

            boolean shouldExplode = false;
            for (Entity nearby : w.getNearbyEntities(loc, HEZ_BOMBER_RANGE, HEZ_BOMBER_RANGE, HEZ_BOMBER_RANGE)) {
                if (nearby.equals(e)) continue;
                Set<String> tags = nearby.getScoreboardTags();
                if (tags.contains(TZAHAL_TAG) || tags.contains(UN_TAG)) { shouldExplode = true; break; }
            }
            if (shouldExplode) {
                try { w.createExplosion(loc, 3.0f, false, true); } catch (Throwable ignored) {}
                cleanupExplosionDrops(w, loc, 10.0);
                e.remove();
                it.remove();
            }
        }
    }

    /** Точка в радиусе от случайного активного игрока (на земле) - чтобы бои были на виду,
     *  а не где-то в случайной точке зачастую огромной карты. Возвращает null и если чанк
     *  вдруг не загружен естественно (радиус большой - на слабом view-distance края могут
     *  выпасть за пределы прогрузки) - лишний повод грузить пустой чанк ни к чему. */
    private Location randomNearActivePlayer(World w, double radius) {
        List<Player> active = plugin.getGameManager().getActivePlayers();
        if (active.isEmpty()) return null;
        Location pl = active.get(rng.nextInt(active.size())).getLocation();
        double x = pl.getX() + (rng.nextDouble() * 2 - 1) * radius;
        double z = pl.getZ() + (rng.nextDouble() * 2 - 1) * radius;
        if (!w.isChunkLoaded(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4)) return null;
        int y = w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
        return new Location(w, x, y + 1, z);
    }

    /** Перенацеливание банд друг на друга (в 200 бл) + на крестьян ООН + на игроков, которые
     *  недавно ударили эту банду (см. markHatedByVictimFaction) - на остальных игроков они
     *  не нацелятся, т.к. EntityListener гасит EntityTargetEvent с target=Player для FACTION_TAG,
     *  если только этот игрок сейчас не "ненавидим" этой же бандой.
     *  Отслеживаем по UUID (не сканируем весь мир - дорого на долгой игре). Крутится чаще
     *  основного 1-сек тика (см. FACTION_RETARGET_PERIOD_TICKS), иначе ванильный AI успевает
     *  сам сбросить цель между проверками. */
    private void tickFactionTargeting() {
        if (world == null) return;
        if (hezIds.isEmpty() && tzahalIds.isEmpty()) return;
        List<LivingEntity> hez = resolveLiving(hezIds);
        List<LivingEntity> tzahal = resolveLiving(tzahalIds);
        List<LivingEntity> un = resolveLiving(unIds);

        List<LivingEntity> hezEnemies = new ArrayList<LivingEntity>(tzahal);
        hezEnemies.addAll(un);
        addHatedPlayers(hezEnemies, hezHatedUntil);

        List<LivingEntity> tzahalEnemies = new ArrayList<LivingEntity>(hez);
        tzahalEnemies.addAll(un);
        addHatedPlayers(tzahalEnemies, tzahalHatedUntil);

        retargetGroup(hez, hezEnemies);
        retargetGroup(tzahal, tzahalEnemies);
    }

    /** Добавляет в список целей игроков, которые недавно ударили эту банду (окно ещё не истекло) -
     *  доп. цель БЕЗ приоритета, побеждает обычная логика "ближайший" в retargetGroup. */
    private void addHatedPlayers(List<LivingEntity> enemies, Map<UUID, Long> hatedUntil) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = hatedUntil.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> en = it.next();
            if (now >= en.getValue()) { it.remove(); continue; }
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null && p.isOnline()) enemies.add(p);
        }
    }

    /** Удар по бойцу банды - вся банда агрится на ударившего игрока на 1 мин (см. addHatedPlayers). */
    public void markHatedByVictimFaction(Entity victim, UUID attackerId) {
        if (victim == null || attackerId == null) return;
        Set<String> tags = victim.getScoreboardTags();
        long until = System.currentTimeMillis() + FACTION_HATE_DURATION_MS;
        if (tags.contains(HEZ_TAG)) hezHatedUntil.put(attackerId, until);
        else if (tags.contains(TZAHAL_TAG)) tzahalHatedUntil.put(attackerId, until);
    }

    /** Сейчас ли эта банда (по тегу mob) "ненавидит" этого игрока - используется EntityListener,
     *  чтобы НЕ гасить EntityTargetEvent для такого игрока. */
    public boolean isHatedByEntityFaction(Entity mob, Player player) {
        if (mob == null || player == null) return false;
        Set<String> tags = mob.getScoreboardTags();
        long now = System.currentTimeMillis();
        if (tags.contains(HEZ_TAG)) {
            Long until = hezHatedUntil.get(player.getUniqueId());
            return until != null && now < until;
        }
        if (tags.contains(TZAHAL_TAG)) {
            Long until = tzahalHatedUntil.get(player.getUniqueId());
            return until != null && now < until;
        }
        return false;
    }

    private List<LivingEntity> resolveLiving(Set<UUID> ids) {
        List<LivingEntity> result = new ArrayList<LivingEntity>();
        Iterator<UUID> it = ids.iterator();
        while (it.hasNext()) {
            Entity e = Bukkit.getEntity(it.next());
            if (e == null || e.isDead()) { it.remove(); continue; }
            if (e instanceof LivingEntity) result.add((LivingEntity) e);
        }
        return result;
    }

    private void pruneDead(Set<UUID> ids) {
        Iterator<UUID> it = ids.iterator();
        while (it.hasNext()) {
            Entity e = Bukkit.getEntity(it.next());
            if (e == null || e.isDead()) it.remove();
        }
    }

    private void retargetGroup(List<LivingEntity> group, List<LivingEntity> enemies) {
        for (LivingEntity le : group) {
            if (!(le instanceof Mob)) continue;
            Mob mob = (Mob) le;
            LivingEntity current = mob.getTarget();
            // Уже дерётся с кем-то из вражеской банды, и тот всё ещё в радиусе - цель НЕ трогаем.
            // Иначе setTarget() на ту же цель каждую секунду сбрасывал замах топором/арбалетом -
            // топорщики выглядели так, будто теряют агро и прячут оружие каждые пару секунд.
            if (current != null && !current.isDead() && enemies.contains(current)) {
                double dCur = current.getLocation().distanceSquared(le.getLocation());
                if (dCur <= FACTION_AGGRO_RANGE * FACTION_AGGRO_RANGE) continue;
            }
            LivingEntity nearest = null;
            double bestDist = FACTION_AGGRO_RANGE * FACTION_AGGRO_RANGE;
            for (LivingEntity enemy : enemies) {
                if (enemy.isDead()) continue;
                double d = enemy.getLocation().distanceSquared(le.getLocation());
                if (d <= bestDist) { bestDist = d; nearest = enemy; }
            }
            if (nearest != current) {
                try { mob.setTarget(nearest); } catch (Throwable ignored) {}
            }
        }
    }

    // ----- вызыватель: враждебен ко всем, музыка pigstep пока агрится на игрока -----

    private void spawnEvoker() {
        World w = world;
        if (w == null) return;
        pruneDead(evokerIds);
        if (evokerIds.size() >= EVOKER_CAP) return;
        Location loc = randomNearActivePlayer(w, EVOKER_SPAWN_RADIUS);
        if (loc == null) return;
        if (!w.getWorldBorder().isInside(loc)) return; // не за барьер
        try {
            Evoker evoker = (Evoker) w.spawnEntity(loc, EntityType.EVOKER);
            evoker.setRemoveWhenFarAway(false);
            evoker.addScoreboardTag(EVOKER_TAG);
            evokerIds.add(evoker.getUniqueId());
        } catch (Throwable ignored) {}
    }

    /** Отслеживаем вызывателей по UUID (не сканируем весь мир каждую секунду). */
    private void tickEvokers() {
        if (world == null) return;
        Set<UUID> aliveEvokers = new HashSet<UUID>();
        Iterator<UUID> it = evokerIds.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            Entity e = Bukkit.getEntity(id);
            if (e == null || e.isDead() || !(e instanceof Evoker)) { it.remove(); continue; }
            aliveEvokers.add(id);
            try { processEvoker((Evoker) e); } catch (Throwable ignored) {}
        }
        // Чистим музыку у вызывателей, которых больше нет (умерли/удалены).
        Iterator<Map.Entry<UUID, UUID>> mit = evokerMusicTarget.entrySet().iterator();
        while (mit.hasNext()) {
            Map.Entry<UUID, UUID> en = mit.next();
            if (!aliveEvokers.contains(en.getKey())) {
                Player p = Bukkit.getPlayer(en.getValue());
                if (p != null) p.stopSound(Sound.MUSIC_DISC_PIGSTEP, SoundCategory.RECORDS);
                mit.remove();
                evokerMusicTicks.remove(en.getKey());
            }
        }
    }

    private void processEvoker(Evoker mob) {
        UUID evokerId = mob.getUniqueId();
        LivingEntity target = mob.getTarget();
        // Проверяем не только "жива ли цель", но и что она ВСЁ ЕЩЁ в радиусе агро -
        // иначе вызыватель застревал на первой попавшейся (пусть даже далеко ушедшей
        // или неигровой) цели навсегда и не реагировал на подошедшего позже игрока.
        boolean targetStillValid = target != null && !target.isDead()
            && target.getWorld().equals(mob.getWorld())
            && target.getLocation().distanceSquared(mob.getLocation()) <= EVOKER_AGGRO_RANGE * EVOKER_AGGRO_RANGE;
        if (!targetStillValid) {
            target = findNearestAnyEntity(mob, EVOKER_AGGRO_RANGE);
            mob.setTarget(target);
        }

        UUID musicPlayerId = evokerMusicTarget.get(evokerId);
        boolean targetIsPlayer = target instanceof Player && !target.isDead();

        if (targetIsPlayer) {
            Player targetPlayer = (Player) target;
            if (musicPlayerId == null || !musicPlayerId.equals(targetPlayer.getUniqueId())) {
                stopEvokerMusic(evokerId);
                targetPlayer.playSound(targetPlayer.getLocation(), Sound.MUSIC_DISC_PIGSTEP,
                    SoundCategory.RECORDS, 4.0f, 1.0f);
                evokerMusicTarget.put(evokerId, targetPlayer.getUniqueId());
                evokerMusicTicks.put(evokerId, PIGSTEP_DURATION_SEC);
            } else {
                Integer left = evokerMusicTicks.get(evokerId);
                int remaining = (left != null ? left : PIGSTEP_DURATION_SEC) - 1;
                if (remaining <= 0) {
                    targetPlayer.playSound(targetPlayer.getLocation(), Sound.MUSIC_DISC_PIGSTEP,
                        SoundCategory.RECORDS, 4.0f, 1.0f);
                    remaining = PIGSTEP_DURATION_SEC;
                }
                evokerMusicTicks.put(evokerId, remaining);
            }
        } else {
            stopEvokerMusic(evokerId);
        }
    }

    private void stopEvokerMusic(UUID evokerId) {
        UUID prevPlayerId = evokerMusicTarget.remove(evokerId);
        evokerMusicTicks.remove(evokerId);
        if (prevPlayerId != null) {
            Player p = Bukkit.getPlayer(prevPlayerId);
            if (p != null) p.stopSound(Sound.MUSIC_DISC_PIGSTEP, SoundCategory.RECORDS);
        }
    }

    /** Ближайшая живая сущность (игрок или моб, не декоративный стенд) в радиусе - вызыватель
     *  враждебен ко ВСЕМ, а не только к игрокам. */
    private LivingEntity findNearestAnyEntity(LivingEntity self, double range) {
        LivingEntity nearest = null;
        double bestDist = range * range;
        for (Entity e : self.getWorld().getNearbyEntities(self.getLocation(), range, range, range)) {
            if (e.equals(self) || e instanceof ArmorStand) continue;
            if (!(e instanceof LivingEntity) || ((LivingEntity) e).isDead()) continue;
            double d = e.getLocation().distanceSquared(self.getLocation());
            if (d <= bestDist) { bestDist = d; nearest = (LivingEntity) e; }
        }
        return nearest;
    }
}
