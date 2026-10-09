package dev.volansvo.svo.bots;

import dev.volansvo.svo.bots.human.Affect;
import dev.volansvo.svo.bots.human.AimModel;
import dev.volansvo.svo.bots.human.Timing;
import dev.volansvo.svo.bots.nms.BotNms;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Lidded;
import org.bukkit.entity.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Мозг одного бота.
 *
 * Раз в 4 тика бот «думает»: осматривается (зрение с конусом обзора и проверкой
 * видимости, слух шагов и выстрелов, подсветка сквозь стены), оценивает свои силы и
 * силы врагов и выбирает цель поведения. Каждый тик он её исполняет: идёт по пути,
 * целится с человеческой скоростью поворота и ошибкой, стреляет, бьёт, лутает.
 *
 * Порядок приоритетов (сверху важнее):
 * ядерная кнопка, выход из зоны, спасение при малом ХП, бой/уклонение, погоня,
 * аирдроп, подбор, сундуки, охота на Жириновского, следование за тиммейтом, патруль.
 */
public final class Bot {

    enum Goal { DROP, NUKE, PICK_NUKE, ZONE, HEAL, EVADE, FIGHT, CHASE, AIRPIG, AIRDROP, SHARE, DODGE, PICKUP, LOOT, WARDEN, AVOID_WARDEN, FOLLOW, HUNT, ROAM, CAVE, HOLD, CENTER,
        /** Идёт к цели своего плана на матч (центр, край, засада, место боя) и держит её. */
        PLAN }

    /** Что бот знает о враге. */
    static final class Contact {
        LivingEntity entity;
        Location last;
        Vector velocity = new Vector();
        int seenTick;
        boolean visible;
        boolean attackedMe;
        /** Тик, когда цель пропала из виду (-1 - видна или ещё не видели). */
        int lostTick = -1;
        /** Сколько урона бот ей нанёс (со временем забывается): по этому он судит о её здоровье. */
        double dealt;
        int dealtTick;
        /** Когда последний раз отмечали её на карте угроз и сообщали о ней своим. */
        int heatTick = -1000, calloutTick = -1000;
        /** Где цель была в последние 8 тиков, пока бот её видел. */
        final double[] hx = new double[8], hy = new double[8], hz = new double[8];
        final int[] ht = {-1, -1, -1, -1, -1, -1, -1, -1};

        void track(int now, Location l) {
            int i = now & 7;
            hx[i] = l.getX(); hy[i] = l.getY(); hz[i] = l.getZ();
            ht[i] = now;
        }

        /**
         * Где бот «видит» цель сейчас: её место delay тиков назад плюс поправка на тогдашнюю
         * скорость. Цель свернула - поправка уводит прицел мимо, пока глаз не догонит.
         */
        Location delayed(int now, int delay, double trust, World w) {
            int a = (now - delay) & 7;
            if (ht[a] != now - delay) return null;
            double x = hx[a], z = hz[a];
            int b = (now - delay - 2) & 7;
            if (ht[b] == now - delay - 2) {
                x += (hx[a] - hx[b]) / 2.0 * delay * trust;
                z += (hz[a] - hz[b]) / 2.0 * delay * trust;
            }
            return new Location(w, x, hy[a], z);
        }
    }

    private final BotManager mgr;
    private final VolanHooks hooks;
    private final BotSkill skill;
    final UUID id;
    final String name;
    private final Random rnd = new Random();

    private final Navigator nav;
    private final Motor motor = new Motor();

    /** Кто этот бот: класс поведения, характер, план на матч. */
    final Persona persona;
    /** Характер: агрессивность и осторожность у каждого бота свои. */
    private final double aggression;
    private final double caution;
    private final int thinkPhase;

    private Goal goal = Goal.ROAM;
    private final Map<UUID, Contact> contacts = new HashMap<UUID, Contact>();
    private Contact target;
    private int reactionLeft;

    // --- лут
    private final Set<Long> searched = new HashSet<Long>();
    private int lootGen = -1;
    private int[] chest;            // выбранный сундук
    private int chestOpenAt = -1;   // тик, когда открыли крышку
    private int chestFailTicks;
    private Item pickup;
    private Location roam;
    private int roamUntil;
    private boolean roamSprint = true;

    // --- бой
    private int nextFire;
    private int bowDrawStart = -1;
    private int crossbowLoadStart = -1;
    private int strafeDir = 1;
    private int strafeSwitchAt;
    private int nextThrow;
    private int nextPearl;
    private int nextCustom;
    private int nextBuff;
    private int reloadUntil;
    private boolean critJumped;
    private int critJumpTick;
    private int wtapUntil;
    private float aimOffYaw, aimOffPitch, aimDriftYaw, aimDriftPitch;

    // --- использование предметов
    private int busyUntil;          // идёт еда/питьё/аптечка - не переключать слот
    private int stillUntil;         // аптечка: стоять на месте
    private int nextInventory;
    private int nukeAt = -1;
    private int lastHurt = -1000;
    private double lastBorderSize = -1;
    private boolean borderShrinking;
    private double borderEdgeSpeed;
    private int lastBorderTick;

    // --- изучение неизвестных предметов
    private String learnKey;
    private int learnUntil;
    private double learnTargetHp;
    private LivingEntity learnTarget;
    private double learnSelfDamage;
    /** Урон по цели изучения: свой (по событиям) и чужой (его не засчитываем предмету). */
    private double learnDealt, learnOther;

    private int deaths;

    // --- блоки, плагинное оружие, команда
    private final Builder builder;
    private Block minedBlock;
    private int towerStart, towerBestY, towerBestTick, towerBanUntil;
    private Location stillPos;
    private int undergroundSince = -1, nextCaveCheck;
    private boolean caveDigging;
    private int tunnelDx, tunnelDz, tunnelDirTick = -1000;
    private Location tunnelPos;
    private int tunnelPosTick, tunnelStuck;

    /** Цели, к которым стоит прокапываться, если пути нет. */
    private boolean tunnelGoal() {
        switch (goal) {
            case LOOT: case PICKUP: case FOLLOW: case HUNT: case CHASE: case AIRDROP: case SHARE: case ZONE: case ROAM: case CENTER: case PLAN:
                return true;
            default:
                return false;
        }
    }
    private int caveDx, caveDz, caveDirSince;
    private String caveWhy = "";
    private boolean caveTowerOk = true;
    private int caveBestY, caveBestTick, lastTunnel = -1000, caveTowerRetry;
    private int evadeStart, fightLockUntil, bumpTicks, slideUntil, slideDir, fenceSeenUntil;
    private double evadeDist0;
    private int stillTick;
    private final Rides rides;
    private final EiKit eikit;
    private int nextEiMaintain;
    long cpuNanos, cpuWindow;       // время тика (замер нагрузки)
    /** Сколько раз голова повернулась больше чем на 60 градусов за тик (признак бота). */
    int headSnaps;
    private float guardYaw;
    private boolean guardInit;

    // --- человек: задержки, состояние, память
    private final Timing timing;
    private final Affect affect = new Affect();
    private final BotMemory.Record memory;
    private int typingUntil, goalSwitchAt = -1, handReadyAt, lastStartle = -1000, orientTicks;
    private int landedTick = -1, chestNextTake;
    private final Map<UUID, double[]> heardPos = new HashMap<UUID, double[]>();
    /** Места, где бота убили или обстреляли неизвестно откуда: x, z, до какого тика помнит. */
    private final List<double[]> dangerSpots = new ArrayList<double[]>();
    private int mirrorAt = -1, mirrorLeft, mirrorNext, mirrorCooldown;
    private boolean mirrorJump;
    private UUID mirrorWho;
    private int digCount;           // сколько блоков выкопали, застряв в одном месте
    private Location digAnchor;
    private int nukeScanAt, droneScanAt;
    private Item nukeCached;
    private int towerTo = Integer.MIN_VALUE;
    private int nextCover;
    private int nextDrone;
    private boolean eiNeedsReload;
    private int eiReloadUntil;      // идёт перезарядка плагинного ствола в руке
    private final Map<String, Integer> eiDudUntil = new HashMap<String, Integer>();
    private String eiShotKey;
    private int eiShots;
    private double eiShotTargetHp;
    private LivingEntity eiShotTarget;
    private int nextShareCheck;
    private Player shareTo;
    private final List<ItemStack> shareItems = new ArrayList<ItemStack>();
    private int shareSince;
    private int nextJunkDrop;
    private UUID chaseId;
    private int chaseSince;
    private final Map<UUID, Integer> ignoreUntil = new HashMap<UUID, Integer>();

    // --- дроны: свои (полёт) и чужие (уклонение)
    private final DronePilot pilot;
    private Location droneThreat;
    private int droneThreatUntil;
    private int wardenAngryUntil;
    private int wardenRetryAt;
    private Location airdropPoint;
    private int airdropBanUntil;
    private int pickupSince;
    private boolean grabbing;       // в бою побежали за оружием/сундуком
    private final Set<UUID> badPickups = new HashSet<UUID>();

    Bot(BotManager mgr, VolanHooks hooks, BotSkill skill, UUID id, String name) {
        this.mgr = mgr;
        this.hooks = hooks;
        this.skill = skill.personal(name);
        this.id = id;
        this.name = name;
        this.persona = Persona.of(name, rnd);
        this.aggression = persona.aggressionMul();
        this.caution = persona.cautionMul();
        // Сдвиг «мысли» шире самого редкого шага (12 тиков): при перегрузке боты думают
        // в разные тики, а не все разом в первых четырёх.
        this.thinkPhase = rnd.nextInt(12);
        this.pilot = new DronePilot(hooks, id);
        this.builder = new Builder(motor, slot -> {
            Player bp = player();
            return bp != null && hold(bp, slot, mgr.now());
        }, this.skill.turnSpeed);
        this.builder.debugLog = msg -> note(name + " " + msg);
        this.nav = new Navigator(this.skill, builder::placeBlocked);
        this.nav.opener = b -> { Player bp = player(); return bp != null && clickOpen(bp, b, mgr.now()); };
        this.timing = new Timing(rnd, this.skill.tempo, this.skill.lapse);
        this.memory = mgr.memory().of(name);
        this.memory.matches++;
        if (this.skill.humanAim) {
            AimModel aim = new AimModel(rnd);
            aim.period = this.skill.aimPeriod;
            motor.setAim(aim);
        }
        this.eikit = new EiKit(mgr, id, name, motor, slot -> {
            Player bp = player();
            return bp != null && hold(bp, slot, mgr.now());
        }, (where, ticks) -> {
            droneThreat = where.clone();
            droneThreatUntil = mgr.now() + ticks;
        });
        this.rides = new Rides(mgr, hooks, id, name, motor, slot -> {
            Player bp = player();
            return bp != null && hold(bp, slot, mgr.now());
        });
        this.rides.log = this::note;
        this.rides.knownEnemy = r -> knownEnemy(r, 20 * 30);
        this.rides.vehicleLove = persona.vehicles;
    }

    Player player() {
        return hooks.player(id);
    }

    // =====================================================================  события

    /** Бота ударили. */
    void onDamaged(Entity attacker, double damage, int now) {
        lastHurt = now;
        Player me = player();
        if (me != null && !lowHpSaid && me.getHealth() - damage <= 6 && me.getHealth() - damage > 0) {
            lowHpSaid = true;
            mgr.teamSay(me, BotChatter.Topic.T_LOW_HP, 0.5); // просим аптечку у своих
        }
        if (learnKey != null && (attacker == null || attacker.getUniqueId().equals(id)
                || attacker instanceof TNTPrimed || attacker instanceof Explosive)) {
            learnSelfDamage += damage;
        }
        LivingEntity src = livingSource(attacker);
        boolean surprised = src == null || target == null || !target.visible || target.entity != src;
        affect.hurt(damage, surprised);
        typingUntil = 0;
        if (surprised && me != null && now - lastStartle > 60) {
            // Вздрогнул: прицел дёргается, полсекунды бот ничего толком не делает.
            lastStartle = now;
            motor.nudge(me, (rnd.nextFloat() - 0.5f) * 2f * (6 + rnd.nextInt(8)), (rnd.nextFloat() - 0.5f) * 8f);
            reactionLeft = Math.max(reactionLeft, timing.surprise());
        }
        // Бьёт ПВО/турель (урон идёт от её владельца издалека или вообще без источника) -
        // уходим из-под обстрела за укрытие и ставим стенку.
        if (me != null) {
            Location turret = turretShooting(me, now);
            if (turret != null && (src == null || !(src instanceof Player)
                    || src.getLocation().distanceSquared(me.getLocation()) > 40 * 40)) {
                coverFrom = turret;
                coverUntil = now + 100;
                markDanger(turret, 100, now);
                return;
            }
            if (src == null) {
                // Не видно, откуда: не стоим столбом - уходим с этого места.
                Location from = attacker != null ? attacker.getLocation()
                    : me.getLocation().add(rnd.nextDouble() * 6 - 3, 0, rnd.nextDouble() * 6 - 3);
                markDanger(from, 40, now);
                threat().add(from.getX(), from.getZ(), 2.0, now);
                remember(from, now + 20 * 60);
                return;
            }
        }
        if (src == null || src.getUniqueId().equals(id)) return;
        if (src instanceof Warden) {
            // С боссом не меряемся силами, если не охотимся на него специально.
            wardenAngryUntil = now + 200;
            return;
        }
        if (src instanceof Player && hooks.sameTeam(id, src.getUniqueId())) return;
        if (isFaction(src)) { avoidFaction(src, now); return; }
        Contact c = contact(src);
        c.last = src.getLocation();
        c.seenTick = now;
        c.attackedMe = true;
        // Человек поворачивается на удар: ставим цель сразу, но с реакцией.
        if (target == null || target.entity != src) {
            Player p = player();
            if (p != null && p.getLocation().distanceSquared(src.getLocation()) < 30 * 30) setTarget(c);
            else if (p != null && !p.hasLineOfSight(src)) markDanger(src.getLocation(), 50, now); // невидимый снайпер - в укрытие
        }
    }

    /** Рядом выстрел/взрыв: бот слышит, откуда. */
    void onHeard(LivingEntity source, int now) {
        if (source == null || source.getUniqueId().equals(id)) return;
        if (source instanceof Player && hooks.sameTeam(id, source.getUniqueId())) return;
        Contact c = contact(source);
        Location heard = source.getLocation();
        Player me = player();
        if (skill.attention && me != null && !c.visible && heard.getWorld().equals(me.getWorld())) {
            // По звуку понятно направление, но не точное место.
            double f = heard.distance(me.getLocation()) * 0.08;
            heard.add(rnd.nextGaussian() * f, 0, rnd.nextGaussian() * f);
        }
        c.last = heard;
        c.seenTick = now;
        affect.threat();
        threat().add(heard.getX(), heard.getZ(), 1.0, now);
        if (!c.visible) { heardAt = heard.clone(); heardTick = now; } // обернуться на звук

    }

    /**
     * Свой (бот или человек из команды) увидел врага и сказал где: место известно примерно,
     * врага самого бот не видит. Так отряд знает больше, чем каждый по отдельности.
     */
    void onCallout(LivingEntity enemy, Location at, int now) {
        if (enemy == null || at == null || enemy.getUniqueId().equals(id)) return;
        Contact c = contact(enemy);
        if (c.visible || now - c.seenTick < 10) return; // сам вижу лучше
        Location l = at.clone().add(rnd.nextGaussian() * 2.5, 0, rnd.nextGaussian() * 2.5);
        c.last = l;
        c.seenTick = now;
        threat().add(l.getX(), l.getZ(), 1.0, now);
    }

    /** Карта угроз отряда (или своя, если бот сам за себя). */
    private ThreatMap threat() {
        return mgr.threat(hooks.teamIdOf(id), id);
    }

    /** Тиммейта бьют: помогаем. */
    /** Союзник, которому нужна помощь в бою, и до какого тика. */
    private Player helpAlly;
    private int helpUntil;

    /** Союзник дерётся (его бьют или он бьёт) - подтянуться к нему и помочь. */
    void allyInFight(Player ally, int now) {
        Player me = player();
        if (me != null && (helpAlly == null || now >= helpUntil)) talk(me, BotChatter.Topic.T_HELP, 0.35, 20 * 60, null);
        helpAlly = ally;
        helpUntil = now + 20 * 15;
    }

    void onTeammateHurt(LivingEntity attacker, int now) {
        if (attacker == null) return;
        Contact c = contact(attacker);
        c.last = attacker.getLocation();
        c.seenTick = now;
        c.attackedMe = true;
    }

    void onDeath() {
        deaths++;
        affect.death();
        Player dead = player();
        if (dead != null) remember(dead.getLocation(), mgr.now() + 20 * 300);
        if (landedTick >= 0) { memory.landingResult(mgr.now() - landedTick > 20 * 90); landedTick = -1; }
        typingUntil = 0;
        mirrorAt = -1;
        dropSaid = false;
        zoneSaid = false;
        target = null;
        contacts.clear();
        nav.clear();
        chest = null;
        chestOpenAt = -1;
        pickup = null;
        busyUntil = 0;
        stillUntil = 0;
        bowDrawStart = -1;
        crossbowLoadStart = -1;
        learnKey = null;
        builder.stopMining(null);
        rides.reset(null);
        minedBlock = null;
        towerTo = Integer.MIN_VALUE;
        shareTo = null;
        shareItems.clear();
        eiNeedsReload = false;
        if (pilot.active()) pilot.abort(null, mgr.now());
        droneThreat = null;
        resetModes();
        goal = Goal.DROP;
    }

    /**
     * После смерти бот начинает с чистого листа: прежняя яма, крюк, отход от зоны, столб от
     * Жириновского, путь из ловушки и прочие «режимы» остались там, где он погиб. Раньше
     * часть из них переживала смерть, и бот после высадки пару секунд доделывал чужие дела
     * (крюк мог сработать прямо в падении).
     */
    private void resetModes() {
        zoneRetreat = false;
        pitMode = false;
        pitTowerTo = Integer.MIN_VALUE;
        escapeUntil = -1;
        escapeTo = null;
        escapeDrop = null;
        climbUntil = -1;
        leap = null;
        leapDest = null;
        wanderUntil = -1;
        wanderPath = null;
        gatherUntil = -1;
        gatherBlock = null;
        kamikaze = false;
        helpAlly = null;
        undergroundSince = -1;
        caveDigging = false;
        wardenPillarAt = null;
        wardenPillarTo = Integer.MIN_VALUE;
        digOutUntil = -1;
        snowUntil = -1;
        crumbs.clear();
        trapAnchor = null;
        stuckPos = null;
        stillPos = null;
        dropLanding = null;
        airdropPoint = null;
        finaleUnderSince = -1;
        fightLockUntil = 0;
        chuteSwapSlot = -1;
        coverFrom = null;
        dodgeFrom = null;
        dodgePoint = null;
        ladderUntil = -1;
        glanceUntil = -1;
        craftUntil = -1;
        planPoint = null;
        planHoldSince = -1;
        commitUntil = 0;
        gadgetSpot = null;
        lowHpSaid = false;
        for (int i = 0; i < invRaw.length; i++) invRaw[i] = null;
    }

    void onKill(Player victim) {
        affect.kill();
        if (skill.memory) memory.killed(victim.getName());
        if (target != null && target.entity == victim) target = null;
        contacts.remove(victim.getUniqueId());
    }

    /** Бот попал по кому-то: запоминает, сколько снял. */
    void onDealt(LivingEntity victim, double damage, int now) {
        if (learnKey != null && victim == learnTarget) learnDealt += damage;
        Contact c = contacts.get(victim.getUniqueId());
        if (c == null) return;
        c.dealt = hurtGuess(c, now) + damage;
        c.dealtTick = now;
    }

    /** Цель, на которой бот изучает предмет, ранил кто-то другой (или что-то другое). */
    void onTargetHurt(LivingEntity victim, LivingEntity by, double damage) {
        if (learnKey == null || victim != learnTarget) return;
        if (by != null && by.getUniqueId().equals(id)) return;
        learnOther += damage;
    }

    /** Сколько урона, по памяти бота, ещё «висит» на цели: со временем она отлечивается. */
    private double hurtGuess(Contact c, int now) {
        return Math.max(0, c.dealt - (now - c.dealtTick) * 0.02);
    }

    /** Здоровье цели, как его может оценить игрок: полное минус то, что сам снял. */
    private double hpGuess(Contact c, LivingEntity e, int now) {
        if (!skill.attention || !(e instanceof Player) || c == null) return e.getHealth();
        return Math.max(1, maxHp(e) - hurtGuess(c, now));
    }

    void killedBy(String killer) {
        if (skill.memory) memory.killedBy(killer);
    }

    /** Насколько бот зол на игрока по прошлым матчам. */
    int grudge(String player) {
        return skill.memory ? memory.grudge(player) : 0;
    }

    int met(String player) { return skill.memory ? memory.met(player) : 0; }

    /** Действует защита от падения после крюка или ранца (её отмену урона не возвращаем). */
    boolean fallImmune(int now) { return leap != null || now < leapImmuneUntil + 10; }

    /** Не повторяться: true - эту реплику бот недавно уже говорил (и запоминаем её). */
    boolean repeats(String line) { return skill.memory && memory.repeats(line); }

    /** Говорил ли бот эту реплику в прошлых катках (без записи). */
    boolean saidBefore(String line) { return skill.memory && memory.saidBefore(line); }

    BotSkill skill() { return skill; }

    Timing timing() { return timing; }

    boolean angry() { return affect.arousal > 0.5 || affect.confidence < -0.4; }

    /** Идёт перестрелка: не до чата. */
    boolean inFight() {
        return target != null && target.visible && mgr.now() - target.seenTick < 20;
    }

    /** Бот набирает сообщение: столько тиков стоит и почти не смотрит по сторонам. */
    void typing(int ticks) {
        if (target == null && calmGoal()) typingUntil = mgr.now() + ticks;
    }

    private boolean calmGoal() {
        switch (goal) {
            case LOOT: case PICKUP: case ROAM: case HUNT: case FOLLOW: case SHARE: case AIRDROP: case HOLD: case PLAN:
                return true;
            default:
                return false;
        }
    }

    private void remember(Location l, int until) {
        dangerSpots.add(new double[]{l.getX(), l.getZ(), until});
        while (dangerSpots.size() > 6) dangerSpots.remove(0);
    }

    /** Раз в тик: считает рывки головы, которых у человека не бывает. */
    void guard(Player p) {
        float y = p.getLocation().getYaw();
        if (guardInit && Math.abs(Motor.wrap(y - guardYaw)) > 60f) headSnaps++;
        guardYaw = y;
        guardInit = true;
    }

    /** Игрок попросил вещь словами. true - есть чем поделиться, несём. */
    boolean asked(Player who, Items.Kind kind, boolean ammo) {
        Player p = player();
        if (p == null || shareTo != null || inFight()) return false;
        ItemStack give;
        if (ammo) {
            int n = countCustom(p, Items.Custom.AMMO);
            give = n > 0 ? customStack(p, Items.Custom.AMMO, Math.max(1, n / 2)) : null;
            if (give == null && countArrows(p) > 1) give = new ItemStack(Material.ARROW, countArrows(p) / 2);
        } else {
            if (kind == Items.Kind.HEAL && countKind(p, Items.Kind.HEAL) < 2 && p.getHealth() < 12) return false;
            give = stackOf(p, kind, kind == Items.Kind.FOOD ? 4 : 1);
        }
        if (give == null) return false;
        shareTo = who;
        shareItems.clear();
        shareItems.add(give);
        shareSince = mgr.now();
        return true;
    }

    /** Тиммейт рядом прыгает или приседает на месте: иногда отвечаем тем же. */
    void mirror(Player who, boolean jump, int now) {
        if (!skill.mirror || now < mirrorCooldown || mirrorAt >= 0 || target != null || now < busyUntil || leap != null
                || chestOpenAt >= 0 || !calmGoal()) return;
        mirrorCooldown = now + 20 * 25;
        if (rnd.nextDouble() > skill.playful) return;
        mirrorAt = now + timing.decide();
        mirrorLeft = jump ? 2 + rnd.nextInt(2) : 4 + 2 * rnd.nextInt(2);
        mirrorJump = jump;
        mirrorWho = who.getUniqueId();
    }

    private boolean mirrorStep(Player p, int now) {
        if (mirrorAt < 0 || now < mirrorAt) return false;
        Player who = org.bukkit.Bukkit.getPlayer(mirrorWho);
        if (who == null || target != null || mirrorLeft <= 0 || !who.getWorld().equals(p.getWorld())
                || who.getLocation().distanceSquared(p.getLocation()) > 100) {
            mirrorAt = -1;
            BotNms.sneak(p, false);
            return false;
        }
        motor.turn(p, yawTo(p, who.getEyeLocation()), pitchTo(p, who.getEyeLocation()), skill.turnSpeed);
        BotNms.sprint(p, false);
        if (now < mirrorNext) { BotNms.input(p, 0f, 0f, false); return true; }
        mirrorLeft--;
        if (mirrorJump) {
            mirrorNext = now + 12 + rnd.nextInt(4);
            BotNms.input(p, 0f, 0f, BotNms.onGround(p));
        } else {
            mirrorNext = now + 3 + rnd.nextInt(3);
            BotNms.input(p, 0f, 0f, false);
            BotNms.sneak(p, mirrorLeft % 2 == 1);
        }
        return true;
    }

    // =====================================================================  главный цикл

    void tick(int now) {
        Player p = player();
        if (p == null || p.isDead()) return;
        if (pilot.active()) {
            try { pilot.tick(p, now); } catch (Throwable t) { mgr.warn("drone " + name, t); pilot.abort(p, now); }
            if (!pilot.active()) stowDrone(p, now);
            return;
        }
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        if (!hooks.gameActive() || !hooks.inGame(id)) { motor.stop(p); return; }
        try { eikit.tick(p, now); } catch (Throwable t) { mgr.warn("ei " + name, t); }
        if ((now & 1) == 0) {
            try { if (shootDownRockets(p, now)) return; } catch (Throwable t) { mgr.warn("rocket " + name, t); }
        }

        if ((now + thinkPhase) % mgr.thinkEvery() == 0) {
            try { think(p, now); } catch (Throwable t) { mgr.warn("think " + name, t); }
        }
        if (pilot.active()) return; // только что запустили дрон - ходьба больше не управляет
        try {
            if (rides.tick(p, now, visibleEnemy(now))) return; // едем на технике или тросе
            if (rides.shootFromSeat()) { fightFromSeat(p, now); return; }
        } catch (Throwable t) { mgr.warn("ride " + name, t); rides.reset(p); }
        // Подобранный дрон лёг в руку - убираем, иначе спринт/присед случайно его запустит.
        if (now >= busyUntil && isDroneItem(p.getInventory().getItemInMainHand())) stowDrone(p, now);
        if (now % 20 == thinkPhase) affect.second(target == null && p.getHealth() > 14);
        if (mirrorStep(p, now)) return;
        try { act(p, now); } catch (Throwable t) { mgr.warn("act " + name, t); motor.stop(p); }
        if (learnKey != null && now >= learnUntil) finishLearning(p);
    }

    // =====================================================================  восприятие и решение

    private void think(Player p, int now) {
        perceive(p, now);
        // Смотрели в карту, а тут враг - карту из рук, не дочитав.
        if (glanceSlot >= 0 && target != null && target.visible) { glanceSlot = -1; glanceUntil = -1; nextInventory = now; }
        if (now >= nextInventory && now >= busyUntil) {
            manageInventory(p);
            nextInventory = now + 40 + rnd.nextInt(20);
        }
        try {
            maybeLaunchDrone(p, now);
            maybePilotDrone(p, now);
            if (pilot.active()) return;
            maybeReloadEi(p, now);
            teamwork(p, now);
        } catch (Throwable t) { mgr.warn("extras " + name, t); }
        Goal prev = goal;
        goal = decide(p, now);
        if (goal != prev && calmGoal() && calm(prev)) {
            // Спокойное занятие человек не бросает в ту же секунду: доделывает шаг, потом переключается.
            if (goalSwitchAt < 0) goalSwitchAt = now + timing.decide();
            if (now < goalSwitchAt) goal = prev; else goalSwitchAt = -1;
        } else goalSwitchAt = -1;
        // В финале логово Жириновского у центра не обходим: туда и надо.
        Warden boss = goal == Goal.WARDEN || finale() ? null : hooks.warden();
        boolean bossHere = boss != null && boss.getWorld().equals(p.getWorld());
        for (int i = dangerSpots.size() - 1; i >= 0; i--) if (now > dangerSpots.get(i)[2]) dangerSpots.remove(i);
        for (int i = ownMines.size() - 1; i >= 0; i--) if (now > ownMines.get(i)[2]) ownMines.remove(i);
        double[] avoid = new double[(dangerSpots.size() + ownMines.size() + (bossHere ? 1 : 0)) * 3];
        int ai = 0;
        if (bossHere) { avoid[ai++] = boss.getLocation().getX(); avoid[ai++] = boss.getLocation().getZ(); avoid[ai++] = 24; }
        for (double[] d : dangerSpots) { avoid[ai++] = d[0]; avoid[ai++] = d[1]; avoid[ai++] = 9; }
        for (double[] d : ownMines) { avoid[ai++] = d[0]; avoid[ai++] = d[1]; avoid[ai++] = 2.2; } // свои мины не топчем
        nav.setAvoid(avoid);
        nav.threatCost = threatCost(now);
        if (now >= nextEquip) {
            nextEquip = now + 8 + rnd.nextInt(8);
            try { equipArmor(p, now); } catch (Throwable t) { mgr.warn("armor " + name, t); }
        }
        try {
            maybeCraft(p, now);
            maybeGlanceMap(p, now);
        } catch (Throwable t) { mgr.warn("craft/map " + name, t); }
        watchdog(p, now);
        recordCrumb(p, now);
        checkClimb(p, now);
        checkTrapped(p, now);
        checkPit(p, now);
        checkStuck(p, now);
        try { maybeLeap(p, now); } catch (Throwable t) { mgr.warn("leap " + name, t); leap = null; }
        maybeCryForHelp(p, now);
        if (kamikaze && goal != Goal.FIGHT) stopKamikaze(p, now);
        if (p.getHealth() > 14) lowHpSaid = false;
        if (goal == Goal.DROP && !dropSaid) { dropSaid = true; talk(p, BotChatter.Topic.DROP, 0.18, 0, null); }
        if (goal == Goal.ZONE && !zoneSaid) { zoneSaid = true; talk(p, BotChatter.Topic.ZONE, 0.12, 0, null); }
        if (goal != lastGoal) {
            if (lastGoal == Goal.DROP) {
                landedTick = now;
                if (skill.memory) memory.landed(p.getLocation().getX(), p.getLocation().getZ());
            }
            switch (goal) {
                case AIRDROP: talk(p, BotChatter.Topic.AIRDROP, 0.25, 20 * 120, null); break;
                case NUKE: talk(p, BotChatter.Topic.NUKE, 0.9, 20 * 60, null); break;
                case CAVE: talk(p, BotChatter.Topic.T_CAVE, 0.4, 20 * 90, null); break;
                case AVOID_WARDEN: talk(p, BotChatter.Topic.T_WARDEN, 0.3, 20 * 120, null); break;
                case EVADE: talk(p, BotChatter.Topic.T_EVADE, 0.12, 20 * 40, null); break;
                case HEAL: talk(p, BotChatter.Topic.T_HEAL, 0.08, 20 * 60, null); break;
                case DODGE: talk(p, BotChatter.Topic.T_DODGE, 0.25, 20 * 30, null); break;
                case CHASE:
                    if (target != null && target.entity instanceof Player) talk(p, BotChatter.Topic.CHASE, 0.05, 20 * 90, target.entity.getName());
                    break;
                default:
            }
            lastGoal = goal;
        }
        if (now >= nextEiMaintain && now >= busyUntil && leap == null) {
            nextEiMaintain = now + 40;
            try { eikit.maintain(p, now, target == null || !target.visible); } catch (Throwable t) { mgr.warn("ei " + name, t); }
        }
        try {
            if (rides.inTank() && target != null && target.last != null) rides.setDest(target.last);
            rides.plan(p, now, travelGoal(), dangerGoal());
        } catch (Throwable t) { mgr.warn("ride plan " + name, t); }
    }

    /**
     * Цена клеток пути по карте угроз: осторожный бот по своим делам обходит места, где
     * недавно стреляли и видели врагов (сильнее - чем осторожнее он и чем хуже снаряжён).
     * В бою, погоне, охоте и финале не обходит.
     */
    private java.util.function.DoubleBinaryOperator threatCost(int now) {
        if (finale()) return null;
        switch (goal) {
            case LOOT: case PICKUP: case ROAM: case AIRDROP: case SHARE: case FOLLOW: case ZONE: case PLAN:
                break;
            default:
                return null;
        }
        if (goal == Goal.PLAN && (persona.plan == Persona.Plan.HUNT || persona.plan == Persona.Plan.HOT_DROP
                || persona.plan == Persona.Plan.THIRD_PARTY)) return null; // идёт как раз туда
        final double k = persona.caution * 4 * (goal == Goal.ZONE ? 0.4 : 1.0);
        if (k < 0.8) return null;
        final ThreatMap tm = threat();
        final it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap memo = new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap();
        return (x, z) -> {
            long key = ((long) ((int) Math.floor(x) >> 3) << 32) ^ (((int) Math.floor(z) >> 3) & 0xffffffffL);
            if (memo.containsKey(key)) return memo.get(key);
            // Добавка к цене шага небольшая: обходить горячее место стоит, но не за сотню блоков.
            double c = Math.min(3, tm.heat(x, z, now) * k * 0.4);
            memo.put(key, c);
            return c;
        };
    }

    /** Куда бот идёт по своим делам (для поездок), или null. */
    private Location travelGoal() {
        switch (goal) {
            case LOOT: case AIRDROP: case ZONE: case FOLLOW: case HUNT: case ROAM: case PLAN: case CENTER:
                return nav.getGoal();
            default:
                return null;
        }
    }

    private static boolean calm(Goal g) {
        return g == Goal.LOOT || g == Goal.PICKUP || g == Goal.ROAM || g == Goal.HUNT || g == Goal.FOLLOW
            || g == Goal.SHARE || g == Goal.AIRDROP || g == Goal.HOLD || g == Goal.PLAN;
    }

    private boolean dangerGoal() {
        switch (goal) {
            case FIGHT: case EVADE: case CHASE: case DODGE: case HEAL: case AVOID_WARDEN: case WARDEN:
            case DROP: case NUKE: case PICK_NUKE:
                return true;
            default:
                return false;
        }
    }

    private LivingEntity visibleEnemy(int now) {
        Contact t = target;
        return t != null && t.visible && now - t.seenTick < 10 && t.entity != null && !t.entity.isDead() ? t.entity : null;
    }

    private void perceive(Player p, int now) {
        Location me = p.getEyeLocation();
        World w = p.getWorld();
        Vector look = me.getDirection();
        double view = skill.viewDistance;
        // Ослеплён (дымовая и т.п.) - видим только вплотную.
        if (p.hasPotionEffect(PotionEffectType.BLINDNESS) || p.hasPotionEffect(PotionEffectType.DARKNESS)) view = 5;
        if (skill.attention) {
            // Занятый бот смотрит в сундук или в чат, а не по сторонам.
            if (chestOpenAt >= 0 || now < typingUntil) view = Math.max(5, view * 0.15);
            else if (now < busyUntil) view *= 0.7;
            view *= 1.0 - 0.3 * affect.boredom;
        }
        // В перестрелке всё внимание на цели: остальных видно только почти прямо перед собой.
        boolean tunnel = skill.attention && goal == Goal.FIGHT && target != null && target.visible;

        for (Player e : hooks.alivePlayers()) {
            if (e.getUniqueId().equals(id) || !e.getWorld().equals(w)) continue;
            if (hooks.sameTeam(id, e.getUniqueId())) continue;
            if (e.getGameMode() == GameMode.SPECTATOR || e.getGameMode() == GameMode.CREATIVE) continue;
            double d = e.getLocation().distance(p.getLocation());
            boolean glowing = e.isGlowing() || e.hasPotionEffect(PotionEffectType.GLOWING);
            boolean invisible = e.hasPotionEffect(PotionEffectType.INVISIBILITY);
            boolean visible = false;
            if (d <= (invisible ? 6 : view * sight(e, glowing))) {
                Vector to = e.getEyeLocation().toVector().subtract(me.toVector());
                double ang = to.lengthSquared() < 1e-6 ? 0 : look.angle(to.normalize());
                boolean inCone = ang < Math.toRadians(tunnel && target.entity != e ? 50 : 80) || d < 4;
                visible = inCone && p.hasLineOfSight(e);
            }
            // Слышно только того, кто движется: стоящий за стеной себя не выдаёт.
            boolean heard = (moved(e) || !skill.attention) && d < (e.isSneaking() ? 3.5 : (e.isSprinting() ? 14 : 9));
            boolean known = visible || heard || (glowing && d < 160);
            Integer ign = ignoreUntil.get(e.getUniqueId());
            if (ign != null && now < ign && !visible) continue; // бесполезная погоня - пока не видим, игнорируем
            Contact c = contacts.get(e.getUniqueId());
            if (!known) {
                if (c != null) { if (c.visible) c.lostTick = now; c.visible = false; }
                continue;
            }
            if (c == null) c = contact(e);
            Location cur = e.getLocation();
            // Только услышали: место известно примерно, и догадка уточняется понемногу.
            boolean vague = skill.attention && !visible && !glowing;
            if (vague) {
                double f = 0.6 + d * 0.1;
                cur.add(rnd.nextGaussian() * f, 0, rnd.nextGaussian() * f);
                if (c.last != null && c.last.getWorld().equals(cur.getWorld()) && now - c.seenTick <= 20) {
                    cur = c.last.clone().add(cur.toVector().subtract(c.last.toVector()).multiply(0.35));
                }
            }
            if (!vague && c.last != null && c.last.getWorld().equals(cur.getWorld()) && now - c.seenTick <= 8) {
                int dt = Math.max(1, now - c.seenTick);
                c.velocity = cur.toVector().subtract(c.last.toVector()).multiply(1.0 / dt);
            } else {
                c.velocity = new Vector();
            }
            c.last = cur;
            c.seenTick = now;
            boolean sees = visible || (glowing && d < 60 && p.hasLineOfSight(e));
            if (sees && !c.visible && c == target && c.lostTick >= 0 && now - c.lostTick >= 6) {
                // Цель скрывалась и вышла снова: человеку нужно время заметить и довести прицел.
                reactionLeft = Math.max(reactionLeft, react(timing.reacquire()));
                motor.acquire();
            }
            if (!sees && c.visible) c.lostTick = now;
            c.visible = sees;
            if (sees && now - c.heatTick >= 20) {
                c.heatTick = now;
                threat().add(cur.getX(), cur.getZ(), 1.5, now);
            }
            // Увидели врага (впервые за полминуты) - говорим своим, где он.
            if (sees && now - c.calloutTick > 20 * 30) {
                c.calloutTick = now;
                mgr.callout(this, e, cur, now);
            }
        }

        // Мобы, которые охотятся на бота (бандиты, яныки, банды Хаоса).
        for (Location bl : hooks.pendingBlasts()) {
            if (bl.getWorld().equals(w) && bl.distanceSquared(p.getLocation()) < 7 * 7) markDanger(bl, 60, now);
        }
        for (Entity e : p.getNearbyEntities(18, 10, 18)) {
            if (e instanceof TNTPrimed && e.getLocation().distanceSquared(p.getLocation()) < 8 * 8) {
                markDanger(e.getLocation(), ((TNTPrimed) e).getFuseTicks() + 15, now);
                continue;
            }
            if (!(e instanceof Mob) || e instanceof Warden) continue;
            Mob mob = (Mob) e;
            if (mob.getTarget() == null || !mob.getTarget().getUniqueId().equals(id)) continue;
            if (isFaction(mob)) { avoidFaction(mob, now); continue; } // с бандами хаоса не связываемся
            // Неуязвимый (тотем-страж телекинетика и т.п.): не убить - уходим от него.
            if (mob.isInvulnerable()) { markDanger(mob.getLocation(), 40, now); continue; }
            Contact c = contact(mob);
            c.last = mob.getLocation();
            c.seenTick = now;
            c.visible = p.hasLineOfSight(mob);
            c.attackedMe = true;
        }

        // Забываем старое и мёртвое.
        Iterator<Map.Entry<UUID, Contact>> it = contacts.entrySet().iterator();
        while (it.hasNext()) {
            Contact c = it.next().getValue();
            boolean gone = c.entity == null || c.entity.isDead() || !c.entity.isValid()
                || (c.entity instanceof Player && !hooks.inGame(c.entity.getUniqueId()));
            if (gone || now - c.seenTick > 20 * 90) it.remove();
        }
        if (target != null && !contacts.containsValue(target)) target = null;

        // Выбор цели среди видимых: ближе, слабее, тот кто бьёт меня.
        Contact best = null;
        double bestScore = Double.MAX_VALUE;
        for (Contact c : contacts.values()) {
            if (!c.visible && now - c.seenTick > 30) continue;
            if (c.entity instanceof Warden) continue;
            double d = c.last.distance(p.getLocation());
            double s = d;
            if (c.attackedMe && now - lastHurt < 100) s *= 0.5;
            s *= 0.6 + hpGuess(c, c.entity, now) / Math.max(1, maxHp(c.entity)) * 0.6;
            // На того, кто уже убивал бота в прошлых матчах, он идёт охотнее.
            if (c.entity instanceof Player) s *= Math.max(0.6, 1.0 - 0.1 * grudge(c.entity.getName()));
            if (!c.visible) s *= 1.8;
            if (c == target) s *= 0.7; // не дёргаемся между целями
            if (s < bestScore) { bestScore = s; best = c; }
        }
        if (best != null && best != target) setTarget(best);
        if (best == null && target != null && now - target.seenTick > 30) target = null;

        if (now >= droneScanAt) { droneScanAt = now + 8; scanDrones(p, now); }

        // Зона.
        WorldBorder wb = w.getWorldBorder();
        double size = wb.getSize();
        if (lastBorderSize > 0) {
            double shrunk = lastBorderSize - size;
            borderShrinking = shrunk > 0.01;
            // На сколько край подходит за тик (с каждой стороны - половина сужения).
            borderEdgeSpeed = borderShrinking ? shrunk / 2.0 / Math.max(1, now - lastBorderTick) : 0;
        }
        lastBorderSize = size;
        lastBorderTick = now;

        // Сундуки перезаполнились - забываем обысканные.
        int gen = hooks.lootGeneration();
        if (gen != lootGen) { lootGen = gen; searched.clear(); }
    }

    /** Задержка реакции с поправкой на настройку мастерства и состояние бота. */
    private int react(int ticks) {
        return Math.max(1, (int) Math.round(ticks * skill.reactionTicks / 7.0 * affect.react()));
    }

    /** Насколько хорошо видно игрока: в темноте и крадущегося заметить труднее. */
    private double sight(Player e, boolean glowing) {
        if (!skill.attention || glowing) return 1.0;
        double f = 0.4 + 0.6 * e.getEyeLocation().getBlock().getLightLevel() / 15.0;
        return e.isSneaking() ? f * 0.7 : f;
    }

    /** Сдвинулся ли игрок с прошлого осмотра (шаги слышно, стоящего - нет). */
    private boolean moved(Player e) {
        Location l = e.getLocation();
        double[] o = heardPos.get(e.getUniqueId());
        if (o == null) { heardPos.put(e.getUniqueId(), new double[]{l.getX(), l.getY(), l.getZ()}); return true; }
        double dx = l.getX() - o[0], dy = l.getY() - o[1], dz = l.getZ() - o[2];
        o[0] = l.getX(); o[1] = l.getY(); o[2] = l.getZ();
        return dx * dx + dy * dy + dz * dz > 0.02;
    }

    private Goal decide(Player p, int now) {
        double hp = p.getHealth();
        boolean onGround = BotNms.onGround(p);

        // 1. Ядерная кнопка у нас - запускаем (с человеческой задержкой).
        if (hooks.hasNukeButton(p)) return Goal.NUKE;
        nukeAt = -1;

        // 2. Падение с неба в начале/после смерти.
        if (!onGround && p.hasPotionEffect(PotionEffectType.SLOW_FALLING) && !BotNms.inWater(p)
                && p.getLocation().getY() > groundY(p) + 4) return Goal.DROP;

        // 3. Кнопка лежит на земле рядом - бежим подбирать, это победа.
        if (now >= nukeScanAt) { nukeScanAt = now + 20; nukeCached = findNukeItem(p); }
        Item nuke = nukeCached != null && nukeCached.isValid() ? nukeCached : null;
        if (nuke != null) { pickup = nuke; return Goal.PICK_NUKE; }

        // 4. Зона. Враг рядом, а зона стоит (или до края ещё секунд 10 хода) - сначала бой.
        // Начали уходить от едущей зоны - уходим до конца (на 40 блоков вглубь), а не мечемся между
        // боем и отходом каждый раз, когда край чуть отстал.
        if (zoneRetreat && (zoneEdge(p) >= 40 || zoneEta(p, now) > zoneNeed(p, now) + 60)) {
            zoneRetreat = false;
            note(name + " отошёл от зоны: до края " + (int) zoneEdge(p) + ", дойдёт через " + (int) Math.min(9999, zoneEta(p, now)) + "с");
        }
        if (zoneRetreat || (zoneDanger(p) > 0 || zoneUrgent(p, now)) && !fightNearZone(p, now)) {
            if (!zoneRetreat && zoneUrgent(p, now)) {
                zoneRetreat = true;
                note(name + " зона дойдёт через " + (int) zoneEta(p, now) + "с, ухожу заранее");
            }
            return Goal.ZONE;
        }

        // 4б. Видим/слышим вражеский дрон, рядом динамит - убегаем, лучше под крышу.
        if (droneThreat != null && now < droneThreatUntil) return Goal.DODGE;

        // 4в. Собрался поделиться с тиммейтом - это быстро, делаем при любом приказе рации
        // (teamwork сам не начинает обмен, когда враг рядом).
        if (shareTo != null && shareTo.isOnline() && !shareTo.isDead() && p.getHealth() > 9
                && !(target != null && target.visible && target.last.distance(p.getLocation()) < 25)) return Goal.SHARE;

        // 4г. Приказ игрока-командира (рация отряда).
        SquadRadio.Order order = mgr.order(hooks.teamIdOf(id));
        if (order != null) {
            Goal og = orderGoal(p, now, order);
            if (og != null) return og;
        }

        double power = myPower(p);
        Contact t = target;
        boolean tVisible = t != null && t.visible;
        double tDist = t != null ? t.last.distance(p.getLocation()) : 999;

        // 4д. Пояс шахида и мало хп: не отходим, а идём на врага.
        if (kamikazeReady(p, now)) return Goal.FIGHT;

        // 5. Мало ХП: сначала уйти из-под огня (попадания сбивают лечение), потом лечиться.
        boolean canHeal = findHeal(p) >= 0;
        if (hp <= 9 && canHeal) {
            boolean exposed = tVisible && tDist < 45 && now - lastHurt < 60;
            if (exposed) return evade(now, tDist, tVisible);
            return Goal.HEAL;
        }

        // 5б. Разозлили Жириновского - убегаем со всех ног.
        if (now < wardenAngryUntil && hooks.warden() != null) return Goal.AVOID_WARDEN;

        // 5в. Дерёмся руками или слабым оружием, а рядом лежит что-то полезное или стоит
        // сундук - сначала хватаем (как сделал бы человек), потом в бой.
        grabbing = false;
        if (t != null && (tVisible || now - t.seenTick < 40) && bestWeaponFactor(p) < 1.5) {
            Goal g = grabInFight(p, t, now);
            if (g != null) { grabbing = true; return g; }
        }

        // 6. Бой. Врага у самого края едущей зоны не догоняем: зона его и так накроет, а нам туда
        // же возвращаться (в упор или когда он бьёт нас - деремся).
        // Финал: к центру зоны. Деремся с тем, кто рядом или бьёт нас, за дальними не бегаем
        // (если сам враг не у центра - там и встретимся).
        boolean finaleSkip = t != null && finale() && tDist > 18 && !(t.attackedMe && now - lastHurt < 60)
            && Math.hypot(t.last.getX() - mapCenter(p).getX(), t.last.getZ() - mapCenter(p).getZ()) > 30;
        if (t != null && !finaleSkip && !zoneDoomed(p, t, tDist, now) && (tVisible || now - t.seenTick < 40)) {
            double ratio = power / Math.max(0.05, enemyPower(t.entity));
            boolean hitRecently = t.attackedMe && now - lastHurt < 60;
            boolean armed = bestWeaponFactor(p) > 0.6;
            boolean forced = (hitRecently && tDist < 14) || (tDist < 6 && (armed || tDist < 2.5));
            boolean late = hooks.alivePlayers().size() <= 3 || p.getWorld().getWorldBorder().getSize() < 160 || finale();
            boolean mob = !(t.entity instanceof Player);
            if (mob) return (tDist < 20 || t.attackedMe) ? Goal.FIGHT : Goal.EVADE;
            // С пустыми руками в драку не лезем: сначала лут (как сделал бы человек после высадки).
            if (!armed && !forced && !late) {
                if (tDist < 10 && enemyPower(t.entity) > power) return evade(now, tDist, tVisible);
            } else if (forced || late || ratio * aggression * affect.aggression()
                    >= 0.85 / (caution * Affect.livesCaution(hooks.livesLeft(id)))) {
                if (hp <= 5 && ratio < 1.2 && !late && tDist > 4) return evade(now, tDist, tVisible);
                return tVisible ? Goal.FIGHT : Goal.CHASE;
            } else if (tDist < 28 && tVisible) {
                return evade(now, tDist, tVisible); // вооружён, но враг заметно сильнее - не подставляемся
            }
        }

        // 7. Финал: до конца меньше 3 минут - все в самый центр зоны, на поверхность, там и
        // дерёмся. Раньше эта ветка стояла после «Жириновский рядом - обойти», а он живёт у
        // центра карты: боты кружили вокруг и в центр так и не приходили.
        // Под землёй можно побыть недолго (прокопаться сквозь холм), застряли там - наверх.
        if (finale()) {
            if (belowGround(p)) { if (finaleUnderSince < 0) finaleUnderSince = now; }
            else finaleUnderSince = -1;
            if (finaleUnderSince >= 0 && now - finaleUnderSince > 20 * 8) return Goal.CAVE;
            if (hp < 12 && canHeal && !(tVisible && tDist < 25)) return Goal.HEAL;
            Location c = mapCenter(p);
            Location me = p.getLocation();
            if (Math.hypot(c.getX() - me.getX(), c.getZ() - me.getZ()) > 4) return Goal.CENTER;
            Contact any = freshestContact(now, 20 * 60);
            if (any != null && Math.hypot(any.last.getX() - c.getX(), any.last.getZ() - c.getZ()) < 20) { setTarget(any); return Goal.HUNT; }
            return Goal.CENTER;
        }

        // 8. Жириновский рядом - не будить.
        Warden warden = hooks.warden();
        if (warden != null && warden.getWorld().equals(p.getWorld())) {
            double wd = warden.getLocation().distance(p.getLocation());
            if (wd < 22 && goal != Goal.LOOT && goal != Goal.WARDEN) return Goal.AVOID_WARDEN;
            if (wd < 12 && goal != Goal.WARDEN) return Goal.AVOID_WARDEN;
        }

        // 9. Спокойное время: варианты взвешиваются (см. calmDecision).
        return calmDecision(p, now, power, hp, canHeal, warden);
    }

    // =====================================================================  спокойные решения

    /** Вариант занятия: насколько он сейчас хорош и что запомнить, если выберем его. */
    private static final class Option {
        final Goal goal;
        final double u;
        final Runnable apply;

        Option(Goal goal, double u, Runnable apply) { this.goal = goal; this.u = u; this.apply = apply; }
    }

    /** До какого тика бот держится выбранного спокойного занятия (решительность). */
    private int commitUntil;

    /**
     * Чем заняться, когда прямой опасности нет. Все варианты получают оценку - что даст это
     * занятие боту с его характером и планом на матч, - и берётся лучший. Начатое занятие
     * получает надбавку: решительный бот не бросает сундук или позицию ради чуть-чуть лучшего
     * варианта и не мечется туда-сюда. Лутать бесконечно бот тоже не станет: когда снаряжения
     * хватает его плану (Persona.lootSatiation), сундуки почти ничего не стоят, и бот идёт
     * делать то, ради чего лутал.
     */
    private Goal calmDecision(Player p, int now, double power, double hp, boolean canHeal, Warden warden) {
        Location me = p.getLocation();
        List<Option> opts = new ArrayList<Option>();
        double need = lootNeed(p, now);
        boolean lootDone = need <= 0.05;
        double aggr = persona.aggression;

        // Погоня за тем, кого только что потеряли из виду (если мы сильнее или он бил нас).
        Contact recent = freshestContact(now, 20 * 12);
        if (recent != null && power >= 1.0 * caution && (recent.attackedMe || power > 1.4)
                && !zoneDoomed(p, recent, recent.last.distance(me), now)) {
            final Contact rc = recent;
            opts.add(new Option(Goal.CHASE, 50 + aggr * 30 + (recent.attackedMe ? 15 : 0) - recent.last.distance(me) * 0.3,
                () -> setTarget(rc)));
        }

        // Лечение и еда в спокойной обстановке.
        if (hp < 14 && canHeal && hp < maxHp(p) - 6) opts.add(new Option(Goal.HEAL, 62 + (14 - hp) * 4 + persona.caution * 10, null));

        // Аирдроп: бежим к месту падения, на подлёте сбиваем из ствола, потом лутаем.
        ArmorStand pig = hooks.airpig(now);
        if (pig != null && pig.getWorld().equals(p.getWorld()) && power >= 0.9) {
            Location pl = pig.getLocation();
            double flat = Math.hypot(pl.getX() - me.getX(), pl.getZ() - me.getZ());
            double pd = pl.distance(me);
            if (airpigWeapon(p, pd) != null && p.hasLineOfSight(pig)) {
                opts.add(new Option(Goal.AIRPIG, 82, null));
            } else if (flat < 220 && insideBorder(p.getWorld(), pl.getX(), pl.getZ(), 6) && !nearWarden(pl.getX(), pl.getZ(), 26)
                    && now >= airdropBanUntil) {
                // Ждём под шаром на земле, на своей высоте (не на крыше или кроне над нами).
                final Location ap = new Location(p.getWorld(), pl.getX(), nearY(p.getWorld(), pl.getBlockX(), pl.getBlockZ(), me.getBlockY()), pl.getZ());
                opts.add(new Option(Goal.AIRDROP, 46 + persona.greed * 25 + need * 15 - flat * 0.12,
                    () -> { airdropPoint = ap; chest = null; }));
            }
        }
        Location drop = hooks.airdropChest();
        if (drop != null && drop.getWorld().equals(p.getWorld()) && power >= 0.9
                && !nearWarden(drop.getX(), drop.getZ(), 26) && insideBorder(p.getWorld(), drop.getX(), drop.getZ(), 2)
                && drop.distance(me) < 160 && containerHasLoot(drop)
                && !searched.contains(key(drop.getBlockX(), drop.getBlockY(), drop.getBlockZ()))) {
            final int[] dc = {drop.getBlockX(), drop.getBlockY(), drop.getBlockZ()};
            opts.add(new Option(Goal.AIRDROP, 50 + persona.greed * 25 + need * 15 - drop.distance(me) * 0.15,
                () -> { airdropPoint = null; chest = dc; }));
        }

        // Охота на Жириновского ради ядерной кнопки: только сильным, только если он в зоне
        // и до него можно дойти (после неудачной попытки - пауза минуту).
        if (skill.huntWarden && warden != null && warden.getWorld().equals(p.getWorld())
                && power >= 2.2 && hp >= 16 && hasGun(p) && hooks.elapsedTicks() > 20 * 120
                && now >= wardenRetryAt
                && insideBorder(p.getWorld(), warden.getLocation().getX(), warden.getLocation().getZ(), 12)
                && warden.getLocation().distance(me) < 90) {
            opts.add(new Option(Goal.WARDEN, 55 + aggr * 10, null));
        }

        // Союзник в бою - идём помогать (командный бот охотнее).
        if (helpAlly != null && (now >= helpUntil || !helpAlly.isOnline() || helpAlly.isDead()
                || !helpAlly.getWorld().equals(p.getWorld()) || !hooks.inGame(helpAlly.getUniqueId()))) helpAlly = null;
        if (helpAlly != null && helpAlly.getLocation().distance(me) > 10)
            opts.add(new Option(Goal.FOLLOW, 55 + persona.teamSpirit * 35, null));

        // Тиммейту не хватает еды/хила/стрел/патронов - несём.
        if (shareTo != null && shareTo.isOnline() && !shareTo.isDead()) opts.add(new Option(Goal.SHARE, 72, null));

        // Заблудились под землёй - выбираемся наверх.
        if (now >= nextCaveCheck) {
            nextCaveCheck = now + 40;
            if (underground(p)) { if (undergroundSince < 0) undergroundSince = now; }
            else { undergroundSince = -1; caveDigging = false; }
        }
        if (undergroundSince >= 0 && now - undergroundSince > 20 * 25) opts.add(new Option(Goal.CAVE, 88, null));

        // Ценные вещи на земле.
        Item ground = findPickup(p);
        if (ground != null) {
            final Item gi = ground;
            UUID thrower = ground.getThrower();
            boolean gift = thrower != null && hooks.sameTeam(id, thrower);
            double v = pickupWorth(p, ground.getItemStack());
            double u = 26 + Math.min(45, v) * (0.5 + need) - ground.getLocation().distance(me) * 0.9 + (gift ? 25 : 0);
            opts.add(new Option(Goal.PICKUP, u, () -> {
                if (pickup == null || !pickup.getUniqueId().equals(gi.getUniqueId())) pickupSince = now;
                pickup = gi;
            }));
        }

        // Сундуки. Чем нужнее снаряжение, тем дальше ищем и тем ценнее сундук.
        double chestRadius = need > 0.6 ? 110 : need > 0.25 ? 72 : 30;
        if (now < lootOrderUntil) chestRadius = 120;                          // приказ «лутать»
        int[] cur = chest != null && goal == Goal.LOOT && !searched.contains(key(chest)) ? chest : null;
        int[] alt = findChest(p, chestRadius);
        // Начатый сундук не бросаем ради другого, если тот не вдвое ближе (раньше бот метался
        // между двумя почти равными сундуками).
        int[] c = cur;
        if (c == null) c = alt;
        else if (alt != null && alt != cur && chestDist(me, alt) < chestDist(me, cur) * 0.5 - 4) c = alt;
        if (c != null) {
            double d = chestDist(me, c);
            double u = need > 0.05 ? 22 + 65 * need + persona.greed * 10 - d * 0.15 : (d < 14 ? 20 : 4);
            if (now < lootOrderUntil) u += 30;
            final int[] fc = c;
            opts.add(new Option(Goal.LOOT, u, () -> chest = fc));
        }

        // Охота на известного врага (видели, слышали, заметили на карте).
        Contact any = freshestContact(now, 20 * 90);
        boolean huntPlan = persona.plan == Persona.Plan.HUNT || persona.plan == Persona.Plan.HOT_DROP || persona.type == Persona.Archetype.HUNTER;
        if (any != null && (power >= 1.6 || hooks.alivePlayers().size() <= 4 || huntPlan && power >= 1.0)
                && !zoneDoomed(p, any, any.last.distance(me), now)) {
            final Contact ac = any;
            double u = 28 + aggr * 35 + (lootDone ? 15 : 0) + (huntPlan ? 15 : 0) - any.last.distance(me) * 0.08;
            if (persona.plan == Persona.Plan.EDGE || persona.type == Persona.Archetype.SURVIVOR) u -= 22;
            opts.add(new Option(Goal.HUNT, u, () -> setTarget(ac)));
        }

        // План на матч: центр, край, засада, место боя...
        Location pp = planPoint(p, now, lootDone);
        if (pp != null) opts.add(new Option(Goal.PLAN, 26 + (lootDone ? 38 : 8 * (1 - need)) + phaseBonus(), null));

        opts.add(new Option(Goal.ROAM, 10, null));

        Option best = null, now0 = null;
        for (Option o : opts) {
            if (o.goal == goal) now0 = o;
            if (best == null || o.u > best.u) best = o;
        }
        Option pick = best;
        if (now0 != null && now0 != best && calm(goal)) {
            double keep = 6 + persona.decisiveness * 14 + (now < commitUntil ? 10 + persona.decisiveness * 20 : 0);
            if (best.u < now0.u + keep) pick = now0;
        }
        if (pick.goal != goal) commitUntil = now + 40 + (int) (persona.decisiveness * 160) + (int) (persona.patience * 60);
        if (pick.apply != null) pick.apply.run();
        if (pick.goal != Goal.AIRDROP) airdropPoint = null;
        return pick.goal;
    }

    private static double chestDist(Location me, int[] c) {
        return Math.hypot(c[0] + 0.5 - me.getX(), c[2] + 0.5 - me.getZ()) + Math.abs(c[1] - me.getY()) * 2.5;
    }

    /**
     * Насколько боту ещё нужно снаряжение (0 - хватает его плану, 1 - голый). Снаряжение:
     * оружие (главное), броня, лечение, патроны или стрелы.
     */
    private double lootNeed(Player p, int now) {
        double gear = bestWeaponFactor(p) * 10 + Math.min(20, armorTotal(p)) + Math.min(9, countHeals(p) * 3)
            + (hasArrows(p) || countCustom(p, Items.Custom.AMMO) > 0 ? 2 : 0);
        return Math.max(0, Math.min(1, 1 - gear / persona.lootSatiation));
    }

    /** Чем ближе конец матча, тем важнее план, а не лут. */
    private double phaseBonus() {
        double el = hooks.elapsedTicks(), left = hooks.remainingTicks();
        double f = el / Math.max(1, el + left);
        return f < 0.25 ? 0 : f < 0.6 ? 8 : 16;
    }

    // =====================================================================  исполнение

    private void act(Player p, int now) {
        Location loc = p.getLocation();
        // Значения по умолчанию: смотреть по ходу движения.
        Location lookAt = null;
        Navigator.Move m = null;
        double speed = 1.0;
        boolean sprint = true;
        boolean sneak = false;
        float strafe = 0f;
        boolean jump = false;
        boolean directMove = false;
        double dmx = 0, dmz = 0;

        fallGuard(p, now);
        // Летим на крюке или ранце (или целимся для этого).
        if (leap != null || leapDest != null) {
            try { if (leapStep(p, now)) return; } catch (Throwable t) { mgr.warn("leap " + name, t); leap = null; }
        }
        if (now < stillUntil) {
            motor.stop(p);
            BotNms.sneak(p, true);
            return;
        }
        // Крафтим: стоим, смотрим в инвентарь (или на верстак).
        if (now < craftUntil) {
            if (target != null && target.visible) craftUntil = -1;
            else {
                motor.stop(p);
                if (craftLook != null && craftLook.getWorld().equals(p.getWorld())) motor.turn(p, yawTo(p, craftLook), pitchTo(p, craftLook), 15f);
                else motor.turn(p, motor.yaw(), 40f, 10f);
                return;
            }
        }
        // Печатает в чат: стоит на месте, пока вокруг тихо.
        if (now < typingUntil && target == null && calmGoal() && now - lastHurt > 40) {
            motor.stop(p);
            return;
        }

        nav.allowOutsideZone = false;
        nav.eager = goal == Goal.CENTER;
        nav.desperate = now < digOutUntil || goal == Goal.CENTER && nav.getFailures() >= 1;
        nav.zoneMargin = borderShrinking ? Math.min(40, borderEdgeSpeed * 20 * 25) : 0;
        builder.handLimit = nav.desperate ? 12 : nav.eager ? 6 : 1.6;
        // Вышли из боя с натянутым луком/заряжаемым арбалетом - отпускаем.
        if (goal != Goal.FIGHT && (bowDrawStart >= 0 || crossbowLoadStart >= 0)) {
            BotNms.releaseUseItem(p);
            bowDrawStart = -1;
            crossbowLoadStart = -1;
        }

        // Начатый блок доламываем до конца: иначе путь или цель меняются, трещина
        // сбрасывается, и бот раз за разом начинает тот же песок заново.
        boolean snowed = inPowderSnow(p) || now < snowUntil; // в снегу доламываем при любой цели, иначе замёрзнем
        if (builder.isMining() && (snowed && goal != Goal.DROP || goal != Goal.FIGHT && goal != Goal.DODGE && goal != Goal.EVADE
                && goal != Goal.DROP && goal != Goal.HEAL && goal != Goal.NUKE && goal != Goal.PICK_NUKE)) {
            motor.stop(p);
            if (builder.resumeMining(p, now)) return;
        }

        // Провалились в рыхлый снег - выбираемся, пока не замёрзли.
        if (goal != Goal.DROP && (inPowderSnow(p) || now < snowUntil) && snowStep(p, now)) return;

        // Не хватает блоков на столб или мостик - добываем ближайшие.
        if (now < gatherUntil) {
            if (gatherGoal() && gatherStep(p, now)) return;
        } else if (now >= gatherCheckAt) {
            gatherCheckAt = now + 20;
            maybeGather(p, now);
        }

        // Лестница, лиана, подмостки к цели выше/ниже.
        if (now < climbUntil && goal != Goal.DROP && goal != Goal.DODGE && goal != Goal.EVADE && goal != Goal.HEAL
                && climbStep(p, now)) return;

        // Лезем по стене на лестницах.
        if (now < ladderUntil && goal != Goal.DROP && goal != Goal.DODGE && goal != Goal.EVADE) {
            if (builder.ladderClimb(p, ladderDx, ladderDz, ladderTop, now)) return;
            ladderUntil = -1;
            if (p.getLocation().getY() < ladderTop - 0.5) ladderBanUntil = now + 20 * 20; // не вышло
            if (pitMode && p.getLocation().getY() >= ladderTop - 0.5) stopPit(p, now, 20 * 10);
        }

        // Ставим мину или турель (план, засада у сундука, мина за собой при отходе).
        if (gadgetSpot != null && (calmGoal() || goal == Goal.EVADE) && gadgetStep(p, now)) return;

        // Застряли (крыша без спуска, тупик) - выходим тем же путём или спрыгиваем.
        if (now < escapeUntil && goal != Goal.FIGHT && goal != Goal.DODGE && goal != Goal.EVADE
                && goal != Goal.DROP && goal != Goal.HEAL) {
            if (escapeStep(p, now)) return;
        }

        // В яме (стенки выше прыжка, пути к цели нет): столб под себя у стенки или ступеньки.
        if (pitMode && goal != Goal.DROP && goal != Goal.HEAL && goal != Goal.DODGE && goal != Goal.EVADE
                && pitStep(p, now)) return;

        // Долго стояли на месте - пару секунд идём туда, где просторнее.
        if (now < wanderUntil && wanderPath != null && goal != Goal.DODGE && goal != Goal.EVADE && goal != Goal.DROP && goal != Goal.HEAL
                && !(goal == Goal.FIGHT && target != null && target.visible && target.last.distance(loc) < 5)) {
            localPathStep(p, now);
            return;
        }

        // Враг улетел дроном, его тело рядом - ломаем (пилот погибнет, когда вернётся).
        if (goal != Goal.DROP && goal != Goal.HEAL && goal != Goal.DODGE && goal != Goal.ZONE && goal != Goal.NUKE
                && !p.isInsideVehicle() && !rides.active() && bodyStep(p, now)) return;

        nav.setModify(tunnelGoal());
        Location rideWalk = rides.walkTarget();
        if (rideWalk != null) {
            nav.setGoal(rideWalk, 0);
            m = nav.tick(p, now);
            if (nav.getFailures() >= 3) { rides.walkFailed(now); nav.clear(); }
        } else
        switch (goal) {
            case NUKE: {
                motor.stop(p);
                if (nukeAt < 0) nukeAt = now + 30 + rnd.nextInt(60);
                if (now >= nukeAt) { hooks.launchNuke(p); nukeAt = now + 200; }
                return;
            }
            case DROP: {
                // Рулим в падении к выбранной точке приземления.
                Location land = dropTarget(p);
                dmx = land.getX() - loc.getX();
                dmz = land.getZ() - loc.getZ();
                directMove = true;
                sprint = false;
                break;
            }
            case PICK_NUKE: case PICKUP: {
                if (pickup == null || !pickup.isValid()) { pickup = null; break; }
                // Предмет не достать (на дереве, в лаве, за стеной) - бросаем его.
                if (goal == Goal.PICKUP && (now - pickupSince > 20 * (grabbing ? 5 : 8) || nav.getFailures() >= 2)) {
                    badPickups.add(pickup.getUniqueId());
                    pickup = null;
                    nav.clear();
                    break;
                }
                nav.setGoal(pickup.getLocation(), 0);
                if (loc.distanceSquared(pickup.getLocation()) < 2.2) {
                    dmx = pickup.getLocation().getX() - loc.getX();
                    dmz = pickup.getLocation().getZ() - loc.getZ();
                    directMove = true;
                } else m = nav.tick(p, now);
                break;
            }
            case ZONE: {
                Location safe = zoneSafePoint(p);
                nav.allowOutsideZone = true; // уходим от зоны - путь через её край можно
                nav.setGoal(safe, 3);
                m = nav.tick(p, now);
                if (m.active && !nav.hasPath()) {
                    if (nav.getFailures() >= 2 && BotNms.onGround(p) && zoneEdge(p) > 0.5) {
                        // Пути нет (щель, завал, снег над головой) - прорубаемся к безопасной точке.
                        // За зоной ломать нельзя: там только идём напрямик.
                        double gx = safe.getX() - loc.getX(), gz = safe.getZ() - loc.getZ();
                        if (now - tunnelDirTick > 60 || tunnelDx == 0 && tunnelDz == 0) {
                            tunnelDirTick = now;
                            if (Math.abs(gx) >= Math.abs(gz)) { tunnelDx = gx > 0 ? 1 : -1; tunnelDz = 0; }
                            else { tunnelDx = 0; tunnelDz = gz > 0 ? 1 : -1; }
                        }
                        double dy = safe.getY() - loc.getY();
                        caveDx = tunnelDx; caveDz = tunnelDz;
                        tunnel(p, now, tunnelDx, tunnelDz, dy >= 2 ? 1 : (dy <= -2 ? -1 : 0));
                        tunnelDx = caveDx; tunnelDz = caveDz;
                        return;
                    }
                    dmx = safe.getX() - loc.getX(); dmz = safe.getZ() - loc.getZ(); directMove = true;
                }
                if (target != null && target.visible) {
                    Location kite = combatWhileMoving(p, now);
                    if (kite != null && p.getHealth() > 12) lookAt = kite;
                }
                break;
            }
            case HEAL: {
                // Враг видит и стреляет - сначала стенка из блоков, потом лечение.
                if (target != null && target.visible && target.last.distance(loc) < 40 && now >= nextCover
                        && now >= busyUntil && Builder.blockCount(p) >= 2) {
                    nextCover = builder.cover(p, target.last) ? now + 20 * 12 : now + 40;
                }
                if (!heal(p, now)) goal = Goal.ROAM;
                if (target != null && target.visible) {
                    // Лечимся на бегу от врага.
                    Vector away = loc.toVector().subtract(target.last.toVector());
                    dmx = away.getX(); dmz = away.getZ(); directMove = true; sprint = false;
                } else { motor.stop(p); return; }
                break;
            }
            case EVADE: {
                Location from = target != null ? target.last : loc;
                Location safe = escapePoint(p, from);
                nav.setGoal(safe, 2);
                m = nav.tick(p, now);
                if (!m.active) { Vector away = loc.toVector().subtract(from.toVector()); dmx = away.getX(); dmz = away.getZ(); directMove = true; }
                tryPearlEscape(p, from, now);
                // Тактик при отходе оставляет мину за собой.
                if (gadgetSpot == null && now >= nextGadget && persona.gadgets > 0.5 && target != null && target.visible
                        && target.last.distance(loc) > 5 && target.last.distance(loc) < 18 && BotNms.onGround(p)
                        && Gadgets.find(p.getInventory(), Gadgets.Type.MINE) >= 0) {
                    gadgetSpot = new Location(loc.getWorld(), loc.getBlockX() + 0.5, Math.floor(loc.getY() + 0.01), loc.getBlockZ() + 0.5);
                    gadgetType = Gadgets.Type.MINE;
                    gadgetSince = now;
                }
                if (target != null && target.visible && eikit.evading(p, now, target.entity, target.last.distance(loc))) { /* дымовая, мина */ }
                else if (target != null && target.visible && target.last.distance(loc) < 40) {
                    // Со стволом отходим лицом к врагу и стреляем, как игрок; при малом ХП просто бежим.
                    Location kite = combatWhileMoving(p, now);
                    if (kite != null && p.getHealth() > 8) lookAt = kite;
                }
                else if (p.getHealth() <= 12 && now - lastHurt > 30) heal(p, now); // оторвались - лечимся на бегу
                break;
            }
            case FIGHT: {
                fight(p, now);
                return;
            }
            case DODGE: {
                if (droneThreat == null) break;
                if (dodgeFrom == null || !dodgeFrom.equals(droneThreat) || dodgePoint == null) {
                    dodgeFrom = droneThreat;
                    dodgePoint = escapePoint(p, droneThreat, true);
                }
                Location safe = dodgePoint;
                // Бьёт ПВО, а мы ещё на виду - ставим стенку из блоков между нами.
                if (coverFrom != null && now < coverUntil && now - lastHurt < 15 && now >= nextCoverWall
                        && Builder.blockCount(p) >= 2 && turretSees(coverFrom, p)) {
                    nextCoverWall = now + 40;
                    if (builder.cover(p, coverFrom)) { motor.stop(p); return; }
                }
                nav.setGoal(safe, 1);
                m = nav.tick(p, now);
                if (!m.active) {
                    Vector away = loc.toVector().subtract(droneThreat.toVector());
                    dmx = away.getX(); dmz = away.getZ(); directMove = true;
                }
                break;
            }
            case SHARE: {
                if (shareTo == null || !shareTo.isOnline()) { shareTo = null; break; }
                Location tl = shareTo.getLocation();
                if (!tl.getWorld().equals(loc.getWorld())) { shareTo = null; break; }
                double sd = loc.distance(tl);
                // Не подойти (тиммейт на дереве, на уступе) - бросаем навесом, если видно.
                boolean throwFar = sd <= 10 && nav.getFailures() >= 2 && p.hasLineOfSight(shareTo);
                if (sd > 3.0 && !throwFar) {
                    nav.setGoal(tl, 2);
                    m = nav.tick(p, now);
                    if (loc.distance(tl) < 8) lookAt = shareTo.getEyeLocation();
                    break;
                }
                giveTo(p, shareTo);
                motor.stop(p);
                return;
            }
            case CHASE: case HUNT: {
                if (target == null || target.last == null) break;
                // Слышим, но 40 сек не можем ни увидеть, ни дойти - минуту не гоняемся за ним.
                UUID tid = target.entity.getUniqueId();
                if (!tid.equals(chaseId)) { chaseId = tid; chaseSince = now; }
                if (target.visible) chaseSince = now;
                // За тем, на кого зол, гонится дольше, чем выгодно.
                if (now - chaseSince > 20 * (40 + 10 * Math.min(3, grudge(target.entity.getName())))) {
                    ignoreUntil.put(tid, now + 20 * 60);
                    contacts.remove(tid);
                    target = null;
                    chaseId = null;
                    nav.clear();
                    break;
                }
                // Путь к нему есть и мы ещё не дошли - идём по пути; искать обзор, когда пришли или пути нет.
                boolean pathOn = nav.hasPath() && nav.reaches() && !nav.arrived(p, 2.5);
                if (!target.visible && !pathOn && seekLineOfSight(p, now, target.entity)) return;
                if (target == null) break;
                nav.setGoal(target.last, 2);
                m = nav.tick(p, now);
                // Доходим до последнего места, где видели, - дальше ищем вокруг.
                if (nav.arrived(p, 2.5) && !target.visible && Math.abs(target.last.getY() - loc.getY()) < 1.5) {
                    target.seenTick -= 200;
                }
                // Туда не пройти (дерево, обрыв, вода) - бросаем погоню.
                if (nav.getFailures() >= 3 && !target.visible) {
                    contacts.remove(target.entity.getUniqueId());
                    target = null;
                    nav.clear();
                    break;
                }
                // Не видим - держим прицел там, где он скорее всего (последнее место, угол),
                // а не смотрим под ноги: так и игрок встречает врага из-за угла.
                if (target.visible) lookAt = target.entity.getEyeLocation();
                else if (target.last.distance(loc) < 40) lookAt = target.last.clone().add(0, 1.5, 0);
                prepareWeapon(p, target.last.distance(loc));
                break;
            }
            case AIRPIG: {
                ArmorStand pig = hooks.airpig(now);
                if (pig == null) break;
                motor.turn(p, yawTo(p, pig.getLocation().add(0, 1, 0)), pitchTo(p, pig.getLocation().add(0, 1, 0)), skill.turnSpeed);
                double pd = pig.getLocation().distance(loc);
                Weapon pw = airpigWeapon(p, pd);
                int ps = pw == null ? -1 : weaponSlot(p, pw);
                motor.drive(p, 0, 0, 0, 0f, false, false);
                if (ps < 0 || !hold(p, ps, now)) return;
                Location aim = aimPoint(p, pig, null, pw, pd);
                motor.turn(p, yawTo(p, aim), pitchTo(p, aim), skill.turnSpeed);
                fireWeapon(p, pw, pig, aim, pd, now);
                return;
            }
            case AIRDROP: case LOOT: {
                if (chest == null && airdropPoint != null) {
                    // Аирдроп ещё в воздухе - ждём под ним.
                    nav.setGoal(airdropPoint, 4);
                    m = nav.tick(p, now);
                    // Под точку падения не пройти - этот аирдроп пропускаем.
                    if (nav.getFailures() >= 3) { airdropBanUntil = now + 20 * 60; airdropPoint = null; nav.clear(); note(name + " не дойти до аирдропа, бросаю"); }
                    // Ждём на месте - смотрим, где шар (как игрок, задрав голову).
                    ArmorStand pig = hooks.airpig(now);
                    if (pig != null && pig.getWorld().equals(loc.getWorld()) && nav.arrived(p, 5)) lookAt = pig.getLocation().add(0, 1, 0);
                    break;
                }
                if (chest == null) break;
                if (lootChest(p, now)) return; // прокапывается к сундуку или наводится на него
                if (chest != null && (goal == Goal.LOOT || goal == Goal.AIRDROP)) { // lootChest мог закрыть сундук
                    Location c = new Location(p.getWorld(), chest[0] + 0.5, chest[1] + 0.5, chest[2] + 0.5);
                    if (chestOpenAt >= 0) { motor.turn(p, yawTo(p, c), pitchTo(p, c), skill.turnSpeed); motor.stop(p); return; }
                    // Идём не к самому сундуку, а на клетку, откуда его видно и можно открыть.
                    Location stand = chestStand(p);
                    if (stand != null) {
                        double sx = stand.getX() - loc.getX(), sz = stand.getZ() - loc.getZ();
                        if (Math.hypot(sx, sz) < 1.6 && Math.abs(stand.getY() - loc.getY()) < 1.2) {
                            // Последний шаг - точно на место, глядя на сундук.
                            motor.turn(p, yawTo(p, c), pitchTo(p, c), skill.turnSpeed);
                            if (Math.hypot(sx, sz) > 0.3) motor.drive(p, sx, sz, 0.5, 0f, false, false);
                            else motor.stop(p);
                            return;
                        }
                    }
                    nav.setGoal(stand != null ? stand : c, 1);
                    m = nav.tick(p, now);
                    if (loc.distanceSquared(c) < 9) lookAt = c;
                    if (nav.getFailures() >= 3) { searched.add(key(chest)); chest = null; nav.clear(); }
                }
                break;
            }
            case WARDEN: {
                warden(p, now);
                return;
            }
            case AVOID_WARDEN: {
                Warden w = hooks.warden();
                if (w == null) break;
                // Злой и уже рядом - не убежать: столб под себя. Уже на столбе - не слезаем.
                if (now < wardenAngryUntil || wardenPillarAt != null) {
                    int pl = wardenPillar(p, w, w.getLocation().distance(loc), now);
                    if (pl == 1) return;
                    if (pl == 2) {
                        Location wa = w.getLocation().add(0, 1.6, 0);
                        motor.turn(p, yawTo(p, wa), pitchTo(p, wa), skill.turnSpeed);
                        motor.stop(p);
                        int gun = findGun(p);
                        if (gun >= 0 && hold(p, gun, now) && p.hasLineOfSight(w)) fireGun(p, now, wa);
                        return;
                    }
                }
                Location safe = escapePoint(p, w.getLocation());
                nav.setGoal(safe, 2);
                m = nav.tick(p, now);
                // Крадёмся, пока он не учуял; если уже злой - бежим.
                sneak = now >= wardenAngryUntil && w.getLocation().distance(loc) > 10;
                sprint = !sneak;
                break;
            }
            case CAVE: {
                if (caveDigging) { digStairs(p, now); return; }
                Location up = new Location(p.getWorld(), loc.getX(), p.getWorld().getHighestBlockYAt(loc) + 1, loc.getZ());
                nav.setGoal(up, 3);
                m = nav.tick(p, now);
                if (nav.getFailures() >= 3 || (m != null && !m.active && nav.stuckTicks() > 40)) {
                    caveDigging = true;
                    caveTowerOk = true;
                    caveBestY = p.getLocation().getBlockY();
                    caveBestTick = now;
                    chooseCaveDir(p, now);
                    nav.clear();
                }
                break;
            }
            case CENTER: {
                Location c = mapCenter(p);
                double cd = Math.hypot(c.getX() - loc.getX(), c.getZ() - loc.getZ());
                // В самом центре топчемся рядом с ним и ищем драку.
                if (cd < 4) {
                    if (centerRoam == null || now > centerRoamUntil || nav.arrived(p, 1.5)) {
                        double a = rnd.nextDouble() * Math.PI * 2, r = 1 + rnd.nextDouble() * 2.5;
                        int x = (int) Math.floor(c.getX() + Math.cos(a) * r), z = (int) Math.floor(c.getZ() + Math.sin(a) * r);
                        centerRoam = new Location(p.getWorld(), x + 0.5, groundTop(p.getWorld(), x, z, loc.getBlockY()), z + 0.5);
                        centerRoamUntil = now + 20 * 10;
                    }
                    c = centerRoam;
                }
                // Пути нет или он дважды обрывался, не доходя (горы, дома, завалы): какое-то время
                // идём напрямик - ломаем, ставим мостики, рубим ступеньки (блоков мало - сначала
                // добываем), потом снова пробуем найти путь уже отсюда. Центр в финале - обязательно.
                if (now < centerTunnelUntil && cd >= 4) {
                    tunnelToward(p, now, c);
                    if (now + 1 >= centerTunnelUntil) nav.clear();
                    return;
                }
                nav.setGoal(c, 1);
                m = nav.tick(p, now);
                boolean dead = (!m.active || !nav.hasPath() || !nav.reaches()) && nav.getFailures() >= 2;
                if (dead && cd >= 4 && BotNms.onGround(p)) {
                    if (Builder.blockCount(p) < 10 && now >= gatherBanUntil && skill.navPlace && !builder.placeBlocked()) {
                        startGather(p, 16, now, "к центру зоны");
                        return;
                    }
                    centerTunnelUntil = now + 20 * 10;
                    note(name + " в центр напрямик (путь обрывается)");
                    tunnelToward(p, now, c);
                    return;
                }
                break;
            }
            case PLAN: {
                Location pp = planPoint;
                if (pp == null || !pp.getWorld().equals(loc.getWorld())) break;
                if (Math.hypot(pp.getX() - loc.getX(), pp.getZ() - loc.getZ()) > 3.0 || Math.abs(pp.getY() - loc.getY()) > 3) {
                    planHoldSince = -1;
                    nav.setGoal(pp, 2);
                    m = nav.tick(p, now);
                    if (nav.getFailures() >= 3) { planPoint = null; planBanUntil = now + 20 * 20; nav.clear(); }
                    break;
                }
                if (planHoldSince < 0) {
                    planHoldSince = now;
                    note(name + " на позиции плана «" + persona.plan.title + "»");
                }
                holdPosition(p, now);
                return;
            }
            case HOLD: {
                if (holdPoint == null || !holdPoint.getWorld().equals(loc.getWorld())) break;
                nav.setGoal(holdPoint, 1);
                m = nav.tick(p, now);
                break;
            }
            case FOLLOW: {
                Player leader = helpAlly;
                if (leader == null) break;
                nav.setGoal(leader.getLocation(), 3);
                m = nav.tick(p, now);
                break;
            }
            case ROAM: default: {
                // Точку прогулки накроет едущая зона - берём новую.
                if (roam != null && borderShrinking && !insideBorder(p.getWorld(), roam.getX(), roam.getZ(), 10 + Math.min(80, borderEdgeSpeed * 20 * 60))) roam = null;
                if (roam == null || now > roamUntil || nav.arrived(p, 4) || nav.getFailures() > 3) {
                    roam = roamPoint(p);
                    roamUntil = now + 20 * 40;
                    roamSprint = rnd.nextInt(10) > 1;
                    nav.clear();
                }
                nav.setGoal(roam, 3);
                m = nav.tick(p, now);
                sprint = roamSprint;
                break;
            }
        }

        // Спокойно идём - оглядываемся (на звук, туда, где были враги, за спину), как человек.
        if (lookAt == null && skill.attention && target == null
                && (goal == Goal.LOOT || goal == Goal.ROAM || goal == Goal.PLAN || goal == Goal.AIRDROP || goal == Goal.HUNT)) {
            lookAt = attention(p, now, false);
        }
        // Смотрим на карту: голова опущена к руке, на бегу не спринтуем.
        if (glancing(now)) {
            double a = Math.toRadians(motor.yaw());
            Location eye = p.getEyeLocation();
            lookAt = eye.clone().add(-Math.sin(a) * 1.0, -1.43, Math.cos(a) * 1.0);
            sprint = false;
        }
        if (goal != Goal.DROP) {
            double sx = directMove ? dmx : (m != null ? m.dx : 0), sz = directMove ? dmz : (m != null ? m.dz : 0);
            openWayAhead(p, sx, sz, now);
            // Низкий проход (полублок над головой, ковёр под ногами): пролезаем присев.
            if (!sneak && (m != null && !directMove && m.crouch || lowGapAhead(p, sx, sz))) { sneak = true; sprint = false; }
        }
        // Присед с предметом ExecutableItems в руке запускает его (снайперка, дрон) - сначала пустая рука.
        if (sneak && now >= busyUntil && Items.eiId(p.getInventory().getItemInMainHand()) != null) {
            int free = freeHandSlot(p);
            if (free >= 0) hold(p, free, now);
        }
        BotNms.sneak(p, sneak);
        if (directMove && goal != Goal.DROP && BotNms.onGround(p) && !safeStep(p, dmx, dmz)) {
            motor.stop(p); // впереди обрыв глубже 4 блоков или лава - не шагаем
            return;
        }
        if (directMove) {
            // Упёрлись (ограда, стена) - не прыгаем в неё бесконечно, а идём вдоль.
            if (BotNms.horizontalCollision(p)) bumpTicks++; else if (bumpTicks > 0) bumpTicks--;
            if (bumpTicks > 10 && now >= slideUntil) {
                slideUntil = now + 30;
                slideDir = freeSide(p, dmx, dmz);
                bumpTicks = 0;
            }
            if (now < slideUntil && slideDir != 0) {
                double nx = -dmz * slideDir, nz = dmx * slideDir;
                dmx = nx; dmz = nz;
            }
            float yaw = Motor.yawTo(dmx, dmz);
            if (lookAt == null) motor.turn(p, yaw, walkPitch(now, 6f), skill.turnSpeed);
            else motor.turn(p, yawTo(p, lookAt), pitchTo(p, lookAt), skill.turnSpeed);
            boolean water = BotNms.inWater(p);
            boolean bump = BotNms.onGround(p) && BotNms.horizontalCollision(p);
            motor.drive(p, dmx, dmz, speed, strafe, jump || water || bump, sprint);
            return;
        }
        if (m == null || !m.active) {
            // Пути нет, а цель недалеко - не стоим, а проламываем проход к ней.
            Location g = nav.getGoal();
            if (g != null && g.getWorld().equals(loc.getWorld()) && nav.getFailures() >= 2 && tunnelGoal()
                    && Math.hypot(g.getX() - loc.getX(), g.getZ() - loc.getZ()) < 40 && BotNms.onGround(p) && zoneEdge(p) > 0.5) {
                double gx = g.getX() - loc.getX(), gz = g.getZ() - loc.getZ();
                if (now - tunnelDirTick > 60 || tunnelDx == 0 && tunnelDz == 0) {
                    tunnelDirTick = now;
                    if (Math.abs(gx) >= Math.abs(gz)) { tunnelDx = gx > 0 ? 1 : -1; tunnelDz = 0; }
                    else { tunnelDx = 0; tunnelDz = gz > 0 ? 1 : -1; }
                }
                double dy = g.getY() - loc.getY();
                caveDx = tunnelDx; caveDz = tunnelDz;
                tunnel(p, now, tunnelDx, tunnelDz, dy >= 2 ? 1 : (dy <= -2 ? -1 : 0));
                tunnelDx = caveDx; tunnelDz = caveDz; // поворот, если упёрлись в лаву/обрыв
                return;
            }
            if (lookAt != null) motor.turn(p, yawTo(p, lookAt), pitchTo(p, lookAt), skill.turnSpeed);
            motor.stop(p);
            return;
        }
        if (navWork(p, m, now)) return;
        if (handleObstacle(p, m, now)) return;
        // Пути нет (ещё ищется) - идём напрямик, но не с обрыва: и с разбега на полтора блока вперёд.
        if (!nav.hasPath() && BotNms.onGround(p) && (!safeStep(p, m.dx, m.dz) || !safeStep(p, m.dx, m.dz, 1.6))) { motor.stop(p); return; }
        if (!nav.hasPath() && !BotNms.horizontalCollision(p) && !BotNms.inWater(p)) m.jump = false;
        if (lookAt != null) motor.turn(p, yawTo(p, lookAt), pitchTo(p, lookAt), skill.turnSpeed);
        else {
            float want = Motor.yawTo(m.lookX, m.lookZ);
            float off = Math.abs(Motor.wrap(want - motor.yaw()));
            // К новому направлению голову поворачивают рывком, по ходу пути - плавно.
            motor.turn(p, want, walkPitch(now, m.lookPitch), Math.min(skill.turnSpeed, off > 60f ? 30f : 14f));
            // С места сначала разворачиваются, потом идут.
            if (off > 75f && !m.jump && BotNms.onGround(p) && p.getVelocity().lengthSquared() < 0.01 && ++orientTicks <= 6) {
                BotNms.input(p, 0f, 0f, false);
                return;
            }
            orientTicks = 0;
        }
        motor.drive(p, m.dx, m.dz, speed, strafe + m.strafeBias, jump || m.jump, (sprint || m.sprintMust) && m.sprintOk && !sneak);
    }

    /** Наклон головы при ходьбе: у каждого бота своя привычка плюс медленное покачивание. */
    private float walkPitch(int now, float base) {
        return base + skill.pitchBias + (float) (3.0 * Math.sin((now + thinkPhase * 40) / 37.0));
    }

    /**
     * Путь просит сломать блок, поставить блок под ноги или построить столб: делаем это
     * руками Builder. true - бот занят этим в этот тик.
     */
    private boolean navWork(Player p, Navigator.Move m, int now) {
        if (m.mine != null) {
            motor.stop(p);
            lastTunnel = now;
            if (builder.mine(p, m.mine, now)) return true;
            if (!m.mine.isPassable()) nav.workFailed(m.mine);
            return true;
        }
        if (m.place != null) {
            motor.stop(p);
            lastTunnel = now;
            if (now >= busyUntil) builder.place(p, m.place);
            return true;
        }
        if (m.pillarTo != Integer.MIN_VALUE) {
            lastTunnel = now;
            if (now < busyUntil) { motor.stop(p); return true; }
            if (!builder.tower(p, m.pillarTo, now) && p.getLocation().getY() < m.pillarTo - 0.05) nav.placeFailed(now);
            return true;
        }
        return false;
    }

    /**
     * Путь упёрся: ломаем мешающий блок (листва, стена, потолок над головой) или строим
     * столб, если цель прямо над нами. true - бот занят этим в этот тик.
     */
    private boolean handleObstacle(Player p, Navigator.Move m, int now) {
        if (towerTo != Integer.MIN_VALUE) {
            // Столб не растёт (потолок, не ставится блок) - бросаем его и эту цель.
            int fy = p.getLocation().getBlockY();
            if (fy > towerBestY) { towerBestY = fy; towerBestTick = now; }
            if (now - towerBestTick > 60 || now - towerStart > 20 * 20) {
                towerTo = Integer.MIN_VALUE;
                towerBanUntil = now + 20 * 30;
                BotNms.input(p, 0f, 0f, false);
                abandonGoal(now);
                return false;
            }
            if (builder.tower(p, towerTo, now)) return true;
            towerTo = Integer.MIN_VALUE;
            nav.clear();
            return false;
        }
        if (minedBlock != null) {
            motor.stop(p);
            if (builder.mine(p, minedBlock, now)) return true;
            // Проход нужен в 2 блока высотой: сломали один - добиваем соседний по высоте.
            Block done = minedBlock;
            minedBlock = null;
            if (done.getType().isAir()) digCount++;
            else if (builder.isDenied(done)) digCount += 2; // ломать не дают
            int feetY = p.getLocation().getBlockY();
            Block pair = done.getY() > feetY ? done.getRelative(org.bukkit.block.BlockFace.DOWN)
                : done.getRelative(org.bukkit.block.BlockFace.UP);
            if (Math.abs(done.getX() - p.getLocation().getBlockX()) + Math.abs(done.getZ() - p.getLocation().getBlockZ()) > 0
                    && pair.getY() >= feetY && pair.getY() <= feetY + 1
                    && pair.getType().isSolid() && Builder.canDig(p, pair)) {
                minedBlock = pair;
                return builder.mine(p, pair, now);
            }
            nav.clear();
            return false;
        }
        if (m.planned || nav.stuckTicks() < 30 || !BotNms.onGround(p)) return false;
        Location l = p.getLocation();
        // Копаем на одном месте, а пройти так и не вышло (сверху осыпается песок, гравий):
        // бросаем эту цель и идём в другое место.
        if (digAnchor == null || !digAnchor.getWorld().equals(l.getWorld()) || digAnchor.distanceSquared(l) > 16) {
            digAnchor = l.clone();
            digCount = 0;
        }
        if (digCount >= 5) {
            digCount = 0;
            abandonGoal(now);
            return false;
        }
        int feet = l.getBlockY();
        World w = p.getWorld();
        Location goalL = nav.getGoal();
        if (goalL != null && goalL.getWorld().equals(w)) {
            double flat = Math.hypot(goalL.getX() - l.getX(), goalL.getZ() - l.getZ());
            int up = goalL.getBlockY() - feet;
            // Блоков на столб мало, а лестницы есть и цель на стене рядом - лезем по ней.
            if (up >= 2 && up <= 12 && flat <= 2.5 && (Builder.blockCount(p) < up || builder.placeBlocked())) {
                double gx = goalL.getX() - l.getX(), gz = goalL.getZ() - l.getZ();
                int wx = Math.abs(gx) >= Math.abs(gz) ? (gx > 0 ? 1 : -1) : 0, wz = wx == 0 ? (gz > 0 ? 1 : -1) : 0;
                if (startLadder(p, now, wx, wz, goalL.getBlockY())) return true;
            }
            if (up >= 2 && up <= 12 && flat <= 2.5 && Builder.blockCount(p) < up && now >= towerBanUntil && !builder.placeBlocked()
                    && now >= gatherBanUntil && gatherGoal()) {
                startGather(p, up + 2, now, "столб в " + up);
                return true;
            }
            if (up >= 2 && up <= 12 && flat <= 2.5 && Builder.blockCount(p) >= up && now >= towerBanUntil && !builder.placeBlocked()) {
                towerTo = goalL.getBlockY();
                towerStart = now;
                towerBestY = feet;
                towerBestTick = now;
                return builder.tower(p, towerTo, now);
            }
        }
        double len = Math.hypot(m.dx, m.dz);
        if (len < 0.01) return false;
        int ax = (int) Math.floor(l.getX() + m.dx / len * 0.9), az = (int) Math.floor(l.getZ() + m.dz / len * 0.9);
        Block head = w.getBlockAt(ax, feet + 1, az), body = w.getBlockAt(ax, feet, az);
        Block roof = w.getBlockAt(l.getBlockX(), feet + 2, l.getBlockZ());
        Block pick = null;
        // Над блоком песок или гравий - выкопаем, он осыплется, и так без конца.
        if (fallingColumn(w, ax, feet + 2, az) > 3 || fallingColumn(w, l.getBlockX(), feet + 3, l.getBlockZ()) > 3) {
            digCount = 5;
            return false;
        }
        if (head.getType().isSolid() && Builder.canDig(p, head)) pick = head;
        else if (body.getType().isSolid() && roof.getType().isSolid() && Builder.canDig(p, roof)) pick = roof;
        else if (body.getType().isSolid() && w.getBlockAt(ax, feet + 2, az).getType().isSolid() && Builder.canDig(p, body)) pick = body;
        else if (body.getType().isSolid() && Builder.canDig(p, body) && body.getType().getHardness() <= 2.5f) pick = body;
        if (pick == null) return false;
        minedBlock = pick;
        motor.stop(p);
        return builder.mine(p, pick, now);
    }

    /** Сколько блоков песка/гравия стоит столбиком начиная с (x,y,z) вверх (до 6). */
    private static int fallingColumn(World w, int x, int y, int z) {
        int n = 0;
        while (n < 6 && w.getBlockAt(x, y + n, z).getType().hasGravity()) n++;
        return n;
    }




    // =====================================================================  яма

    private boolean pitMode, pitTowerOk, pitKeepUp;
    private int pitInChecks, pitOutChecks, pitCheckAt, pitUntil, pitBanUntil, pitDx, pitDz;
    private int pitTowerTo = Integer.MIN_VALUE, pitTowerBestY, pitTowerBestTick;

    /** Сколько твёрдых блоков стоит столбиком в (x,z) от уровня ног fy вверх (до 8). */
    private static int colHeight(World w, int x, int fy, int z) {
        int n = 0;
        while (n < 8 && !w.getBlockAt(x, fy + n, z).isPassable() && !woodenPassage(w.getBlockAt(x, fy + n, z))) n++;
        return n;
    }

    /** Деревянная дверь или калитка, которую бот откроет (или уже открытая): это проход, а не стенка. */
    private static boolean woodenPassage(Block b) {
        String t = b.getType().name();
        if (!(t.endsWith("_DOOR") || t.endsWith("_FENCE_GATE")) || t.startsWith("IRON_")) return false;
        boolean open = b.getBlockData() instanceof org.bukkit.block.data.Openable && ((org.bukkit.block.data.Openable) b.getBlockData()).isOpen();
        if (!open && isLocked(b.getX(), b.getY(), b.getZ())) return false; // не открывается
        // За зоной закрытую не открыть.
        return Builder.inZone(b) || b.getBlockData() instanceof org.bukkit.block.data.Openable
            && ((org.bukkit.block.data.Openable) b.getBlockData()).isOpen();
    }

    /**
     * Яма: в каждую из 4 сторон в пределах 8 блоков стенка в 2+ блока, на которую не
     * запрыгнуть. {высота самой низкой стенки, dx, dz к ней}; null - не яма.
     */
    private static int[] pitShape(Player p) {
        Location l = p.getLocation();
        World w = p.getWorld();
        int fy = (int) Math.floor(l.getY() + 0.01), x0 = l.getBlockX(), z0 = l.getBlockZ();
        int[] best = null;
        int bestR = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int h = 0, r = 1;
            for (; r <= 8; r++) {
                int x = x0 + d[0] * r, z = z0 + d[1] * r;
                // Пол уходит вниз - туда можно спуститься, это не яма.
                if (w.getBlockAt(x, fy - 1, z).isPassable() && w.getBlockAt(x, fy - 2, z).isPassable()) return null;
                h = colHeight(w, x, fy, z);
                if (h > 0) break;
            }
            if (h <= 1) return null; // в эту сторону выходим пешком или прыжком
            if (best == null || h < best[0] || h == best[0] && r < bestR) { best = new int[]{h, d[0], d[1]}; bestR = r; }
        }
        return best;
    }

    /** Есть чем стрелять издалека: из ямы тогда отстреливаемся, а не лезем наверх. */
    private boolean hasRanged(Player p, int now) {
        return hasGun(p) || find(p, Items.Kind.BOW) >= 0 && hasArrows(p)
            || findEi(p, Items.Custom.AUTO, now, true) >= 0 || findEi(p, Items.Custom.SHOTGUN, now, true) >= 0;
    }

    /** Бой не даёт лезть из ямы: враг рядом или его можно достать отсюда. */
    private boolean pitFightBusy(Player p, int now) {
        if (goal != Goal.FIGHT || target == null || !target.visible) return false;
        double d = target.last.distance(p.getLocation());
        return d < 4 || hasRanged(p, now);
    }

    /** Цель снаружи ямы, а пути к ней нет. */
    private boolean goalOutsidePit(Player p) {
        Location l = p.getLocation();
        Location g = goal == Goal.FIGHT && target != null ? target.last : nav.getGoal();
        if (g == null || !g.getWorld().equals(l.getWorld())) return false;
        if (g.getY() < l.getY() + 1.5 && Math.hypot(g.getX() - l.getX(), g.getZ() - l.getZ()) <= 8) return false; // цель тут же, в яме
        return !(nav.hasPath() && nav.reaches());
    }

    /**
     * Сидим в яме (стенки выше прыжка) 3 секунды, и цель не раз была снаружи без пути к
     * ней - выбираемся. Цель в яме при этом может мелькать (подбор выпавших блоков, точка
     * прогулки), это решение не сбивает.
     */
    private void checkPit(Player p, int now) {
        if (now < pitCheckAt) return;
        pitCheckAt = now + 10;
        if (pitMode) {
            if (now > pitUntil) {
                note(name + " не выбрался из ямы");
                stopPit(p, now, 20 * 60);
            }
            return;
        }
        if (now < pitBanUntil || !BotNms.onGround(p) || p.isInsideVehicle() || rides.active() || BotNms.inWater(p)) return;
        if (pitShape(p) == null) { pitInChecks = 0; pitOutChecks = 0; return; }
        pitInChecks++;
        if (goalOutsidePit(p)) pitOutChecks++;
        switch (goal) {
            case DROP: case HEAL: case DODGE: case EVADE: case NUKE: case CAVE: case PICKUP: case PICK_NUKE:
                return;
            default:
        }
        if (pitFightBusy(p, now) || pitInChecks < 6 || pitOutChecks < 3) return;
        int[] s = pitShape(p);
        if (s == null) return;
        pitInChecks = 0;
        pitOutChecks = 0;
        finishEscape(); // ловушку крыши тут не ищем, своё
        pitMode = true;
        pitUntil = now + 20 * 45;
        pitDx = s[1];
        pitDz = s[2];
        pitTowerOk = true;
        pitTowerTo = Integer.MIN_VALUE;
        nav.clear();
        note(name + " в яме (стенка " + s[0] + "), выбираюсь " + (Builder.blockCount(p) >= s[0] ? "столбом" : "ступеньками"));
    }

    private void stopPit(Player p, int now, int ban) {
        pitMode = false;
        pitInChecks = 0;
        pitOutChecks = 0;
        pitTowerTo = Integer.MIN_VALUE;
        pitBanUntil = now + ban;
        nav.clear();
        BotNms.input(p, 0f, 0f, false);
    }

    /**
     * Выбираемся из ямы: идём к самой низкой стенке; блоков хватает - столб под себя
     * вплотную к ней и шаг на край, не хватает - рубим в стенке ступеньки (с них и блоки).
     * true - тик занят.
     */
    private boolean pitStep(Player p, int now) {
        if (pitFightBusy(p, now)) return false; // отбиваемся отсюда, выбираться потом
        Location l = p.getLocation();
        World w = p.getWorld();
        int fy = (int) Math.floor(l.getY() + 0.01), x = l.getBlockX(), z = l.getBlockZ();
        if (pitTowerTo != Integer.MIN_VALUE) {
            if (fy > pitTowerBestY) { pitTowerBestY = fy; pitTowerBestTick = now; }
            if (fy < pitTowerTo && now - pitTowerBestTick <= 60 && builder.tower(p, pitTowerTo, now)) return true;
            if (fy < pitTowerTo) pitTowerOk = false; // столб не растёт (потолок, нельзя строить) - ступеньки
            pitTowerTo = Integer.MIN_VALUE;
        }
        if (now % 10 == 0 && BotNms.onGround(p) && pitShape(p) == null) {
            note(name + " выбрался из ямы");
            stopPit(p, now, 20 * 10);
            return false;
        }
        int need = colHeight(w, x + pitDx, fy, z + pitDz);
        if (skill.debug && now % 40 == 0) mgr.debug(name + " яма: стенка " + need + " к " + pitDx + "," + pitDz + " блоков " + Builder.blockCount(p)
            + " столб " + pitTowerOk + "/" + builder.towerWhy + " проход " + caveWhy + " y=" + String.format("%.2f", l.getY()));
        if (need == 0) {
            // До стенки ещё идти (или уже на краю) - шагаем.
            motor.turn(p, Motor.yawTo(pitDx, pitDz), 0f, Math.min(skill.turnSpeed, 20f));
            motor.drive(p, pitDx, pitDz, 1.0, 0f, BotNms.horizontalCollision(p) && BotNms.onGround(p), false);
            return true;
        }
        if (need >= 2 && pitTowerOk && Builder.blockCount(p) >= need && !builder.placeBlocked() && BotNms.onGround(p)) {
            pitTowerTo = fy + need;
            pitTowerBestY = fy;
            pitTowerBestTick = now;
            motor.stop(p);
            if (builder.tower(p, pitTowerTo, now)) return true;
            pitTowerOk = false;
            pitTowerTo = Integer.MIN_VALUE;
        }
        // Столб не построить (мало блоков, потолок), а лестницы есть - лезем по стенке.
        if (need >= 2 && BotNms.onGround(p) && (!pitTowerOk || Builder.blockCount(p) < need || builder.placeBlocked())
                && startLadder(p, now, pitDx, pitDz, fy + need)) return true;
        // Ступеньки вверх в стенке (не сворачиваем на ровный проход).
        caveDx = pitDx;
        caveDz = pitDz;
        pitKeepUp = true;
        tunnel(p, now, pitDx, pitDz, 1);
        pitKeepUp = false;
        pitDx = caveDx;
        pitDz = caveDz;
        return true;
    }

    // =====================================================================  крюк-кошка и ранец

    private Leap.Plan leap;
    /** Куда хотим перелететь (план ещё не посчитан: сначала останавливаемся). */
    private Location leapDest;
    private int leapPhase, leapStart, leapFired, leapBursts, leapPlanAt, leapBanUntil, leapImmuneUntil, leapFails;
    /** Тик, в который кто-то из ботов уже просчитывал прыжок: за тик считает один. */
    private static int leapPlanTick = -1;

    private int gearSlot(Player p, boolean hook) {
        return gearSlot(p, hook, true);
    }

    /** Слот крюка (hook) или ранца; readyOnly - только готовый к делу (заряды, не на перезарядке). */
    private int gearSlot(Player p, boolean hook, boolean readyOnly) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || !(hook ? Leap.isHook(it) : Leap.isJet(it))) continue;
            if (!readyOnly || Leap.ready(p, it)) return i;
        }
        return -1;
    }

    /**
     * Цель выше (крыша, скала) или за обрывом, а пешком туда не дойти (или только далеко в
     * обход), бот в яме или застрял: если есть крюк-кошка или ранец, просчитываем прыжок и летим.
     */
    private void maybeLeap(Player p, int now) {
        if (leap != null || leapDest != null || now < leapPlanAt || now < leapBanUntil) return;
        leapPlanAt = now + 20;
        if (!BotNms.onGround(p) || BotNms.inWater(p) || p.isInsideVehicle() || rides.active() || builder.isMining()
                || now < busyUntil || chestOpenAt >= 0 || now < climbUntil || pilot.active() || kamikaze) return;
        Location dest;
        switch (goal) {
            case FIGHT: {
                if (target == null || target.last == null) return;
                // Со стволом стреляем отсюда, на крышу лезем только с ближним оружием.
                if (chooseWeapon(p, target.last.distance(p.getLocation()), now) != Weapon.MELEE) return;
                dest = target.last;
                break;
            }
            case CHASE: case HUNT:
                dest = target != null && target.last != null ? target.last : nav.getGoal();
                break;
            case LOOT: case PICKUP: case PICK_NUKE: case AIRDROP: case ROAM: case FOLLOW: case SHARE: case ZONE: case CENTER: case HOLD: case PLAN:
                dest = nav.getGoal();
                break;
            default:
                return;
        }
        if (dest == null || !dest.getWorld().equals(p.getWorld())) return;
        int hook = gearSlot(p, true), jet = gearSlot(p, false);
        if (hook < 0 && jet < 0) return;
        Location l = p.getLocation();
        double flat = Math.hypot(dest.getX() - l.getX(), dest.getZ() - l.getZ()), dy = dest.getY() - l.getY();
        if (flat < 2.5 && Math.abs(dy) < 2) return;
        boolean reach = nav.hasPath() && nav.reaches();
        if (reach && nav.remaining() <= flat * 1.8 + 12) return; // пешком нормально - идём пешком
        // За врагом и к центру в финале - после первой неудачи пешком, за лутом и на прогулке - после второй.
        boolean stuck = pitMode || now < escapeUntil || now < wanderUntil
            || nav.getFailures() >= (goal == Goal.ROAM || goal == Goal.LOOT || goal == Goal.PICKUP ? 2 : 1);
        boolean detour = reach && flat <= 26 && goal != Goal.ROAM; // пешком только далеко в обход (обрыв, ущелье)
        boolean high = !reach && dy >= 2.5 && flat <= 26;          // крыша, скала
        if (!stuck && !detour && !high) return;
        // Цель далеко - летим к точке на прямой к ней, где есть на что встать.
        Location to = dest;
        if (flat > 24) {
            to = null;
            int nearY = (int) Math.floor(Math.max(l.getY(), Math.min(dest.getY(), l.getY() + 12)));
            for (double r : new double[]{22, 17, 12}) {
                int x = (int) Math.floor(l.getX() + (dest.getX() - l.getX()) / flat * r);
                int z = (int) Math.floor(l.getZ() + (dest.getZ() - l.getZ()) / flat * r);
                int y = nav.standY(p.getWorld(), x, z, nearY);
                if (y != Integer.MIN_VALUE) { to = new Location(p.getWorld(), x + 0.5, y, z + 0.5); break; }
            }
            if (to == null) { leapPlanAt = now + 60; return; }
        }
        // Сначала останавливаемся: полёт просчитывается от точки, где стоим.
        leapDest = to;
        leapStart = now;
    }

    /** Остановились: просчитать прыжок. false - прыгать некуда (или ещё не время считать). */
    private boolean planLeap(Player p, int now) {
        if (leapPlanTick == now) return true; // этот тик уже кто-то считает
        leapPlanTick = now;
        int hook = gearSlot(p, true), jet = gearSlot(p, false);
        Location to = leapDest;
        leapDest = null;
        if (hook < 0 && jet < 0) return false;
        PlayerInventory inv = p.getInventory();
        Leap.Plan pl = Leap.plan(p, to, hook < 0 ? null : inv.getItem(hook), jet < 0 ? null : inv.getItem(jet));
        if (pl == null) { leapPlanAt = now + 60; return false; }
        leap = pl;
        leapPhase = 0;
        leapStart = now;
        if (pitMode) stopPit(p, now, 20 * 10);
        if (now < escapeUntil) finishEscape();
        wanderUntil = -1;
        wanderPath = null;
        towerTo = Integer.MIN_VALUE;
        minedBlock = null;
        nav.clear();
        note(name + (pl.hook ? " цепляется крюком" : " взлетает на ранце (" + pl.bursts + ")") + " к "
            + pl.land.getBlockX() + "," + pl.land.getBlockY() + "," + pl.land.getBlockZ() + " (" + goal + ")");
        return true;
    }

    /** Шаг прыжка: взять предмет, навести, выстрелить, лететь, держа W к месту посадки. true - тик занят. */
    private boolean leapStep(Player p, int now) {
        Location l = p.getLocation();
        if (leap == null) {
            // Хотим перелететь: тормозим и на месте считаем полёт.
            if (goal == Goal.EVADE || goal == Goal.DODGE || goal == Goal.HEAL || goal == Goal.DROP || goal == Goal.NUKE
                    || now - leapStart > 30) { leapDest = null; return false; }
            motor.stop(p);
            BotNms.sneak(p, false);
            Vector v = p.getVelocity();
            if (!BotNms.onGround(p) || v.getX() * v.getX() + v.getZ() * v.getZ() > 0.002 && now - leapStart < 15) return true;
            return planLeap(p, now);
        }
        Leap.Plan pl = leap;
        if (leapPhase == 0) {
            // Пока целились, стало не до того (бегство, лечение) или нас сдвинули - не летим.
            if (goal == Goal.EVADE || goal == Goal.DODGE || goal == Goal.HEAL || goal == Goal.DROP || goal == Goal.NUKE
                    || now - leapStart > 40 || !BotNms.onGround(p) || !l.getWorld().equals(pl.from.getWorld())
                    || l.distanceSquared(pl.from) > 0.36) { leap = null; return false; }
            int slot = gearSlot(p, pl.hook);
            if (slot < 0) { leap = null; return false; }
            motor.stop(p);
            BotNms.sneak(p, false);
            if (!hold(p, slot, now)) return true;
            motor.turn(p, pl.yaw, pl.pitch, skill.turnSpeed);
            if (now < handReadyAt || Math.abs(Motor.wrap(motor.yaw() - pl.yaw)) > 0.5f || Math.abs(motor.pitch() - pl.pitch) > 0.5f) return true;
            BotNms.look(p, pl.yaw, pl.pitch);
            motor.sync(p);
            BotNms.useItem(p, false);
            leapPhase = 1;
            leapFired = now;
            leapBursts = pl.bursts - 1;
            leapImmuneUntil = now + 20 * (pl.hook ? Leap.hookNoFall : Leap.jetNoFall);
            return true;
        }
        int t = now - leapFired;
        boolean ground = BotNms.onGround(p);
        // Ничего не произошло (крюку не за что зацепиться, предмет не сработал).
        if (t >= 3 && ground && l.distanceSquared(pl.from) < 1.0) { leapDone(p, now, false); return false; }
        // Ранец: в верхней точке ещё рывок.
        if (!pl.hook && leapBursts > 0 && !ground && t > 1 && p.getVelocity().getY() <= 0.05) {
            leapBursts--;
            int slot = gearSlot(p, false);
            if (slot >= 0 && p.getInventory().getHeldItemSlot() == slot) {
                BotNms.look(p, pl.yaw, pl.pitch);
                motor.sync(p);
                BotNms.useItem(p, false);
                leapImmuneUntil = now + 20 * Leap.jetNoFall;
            }
        }
        // В полёте жмём W к месту посадки (как и было просчитано), взгляд не дёргаем.
        BotNms.sneak(p, false);
        motor.drive(p, pl.sx, pl.sz, 1.0, 0f, false, false);
        if (t >= 3 && (ground || BotNms.inWater(p) || p.isClimbing())) {
            leapDone(p, now, l.getWorld().equals(pl.land.getWorld()) && l.distanceSquared(pl.land) < 4 * 4);
            return false;
        }
        if (t > pl.ticks + 60) { leapDone(p, now, false); return false; }
        return true;
    }

    private void leapDone(Player p, int now, boolean ok) {
        Location l = p.getLocation();
        note(name + (ok ? " долетел" : " прыжок не удался") + " (" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ()
            + ", план " + leap.land.getBlockX() + "," + leap.land.getBlockY() + "," + leap.land.getBlockZ() + ")");
        leap = null;
        nav.clear();
        stuckPos = null;
        trapAnchor = null;
        leapBanUntil = now + (ok ? 30 : 20 * 12);
        if (ok) leapFails = 0;
        else if (++leapFails >= 2) { leapFails = 0; leapBanUntil = now + 20 * 45; }
        motor.stop(p);
    }

    // =====================================================================  тело пилота дрона

    private ArmorStand pilotBody;
    private int nextBodyScan, bodyHitTick = -100;

    /** Ник пилота, чьё это тело: стойка с его ником в тегах, а сам он сейчас летит дроном. */
    private static String pilotOf(Entity stand) {
        for (String t : stand.getScoreboardTags()) {
            Player o = org.bukkit.Bukkit.getPlayerExact(t);
            if (o == null || o.getGameMode() != GameMode.SPECTATOR) continue;
            java.util.Set<String> tags = o.getScoreboardTags();
            if (tags.contains("fpvfly") || tags.contains("bombfly")) return o.getName();
        }
        return null;
    }

    /** Ближайшее видимое тело вражеского пилота (bfpv, bombsender) в 30 блоках. */
    private ArmorStand findPilotBody(Player p) {
        ArmorStand best = null;
        double bd = 30 * 30;
        for (Entity e : p.getNearbyEntities(30, 15, 30)) {
            if (!(e instanceof ArmorStand) || e.isDead()) continue;
            ArmorStand a = (ArmorStand) e;
            if (a.isInvisible() || a.isMarker()) continue;
            String owner = pilotOf(a);
            if (owner == null || owner.equals(name) || isTeammateName(owner)) continue;
            double d = a.getLocation().distanceSquared(p.getLocation());
            if (d < bd && p.hasLineOfSight(a)) { bd = d; best = a; }
        }
        return best;
    }

    /**
     * Враг улетел дроном, а его тело (стойка с головой) стоит тут: ломаем, без тела он
     * погибает, когда вернётся. Стойка ломается от двух ударов подряд (за 5 тиков).
     * true - тик занят.
     */
    private boolean bodyStep(Player p, int now) {
        if (now >= nextBodyScan) {
            nextBodyScan = now + 10;
            pilotBody = findPilotBody(p);
        }
        ArmorStand b = pilotBody;
        if (b == null || b.isDead() || !b.isValid()) { pilotBody = null; return false; }
        Location l = p.getLocation(), bl = b.getLocation();
        double d = bl.distance(l);
        if ((goal == Goal.FIGHT || goal == Goal.CHASE) && d > 10) return false; // свой бой важнее дальнего тела
        Location aim = bl.clone().add(0, 1.0, 0);
        double dx = bl.getX() - l.getX(), dz = bl.getZ() - l.getZ();
        if (d > 2.6) {
            nav.setGoal(bl, 1);
            Navigator.Move m = nav.tick(p, now);
            if (m.active && nav.hasPath()) motor.drive(p, m.dx, m.dz, 1.0, m.strafeBias, m.jump, true);
            else motor.drive(p, dx, dz, 1.0, 0f, BotNms.horizontalCollision(p) && BotNms.onGround(p), true);
            motor.turn(p, yawTo(p, aim), pitchTo(p, aim), skill.turnSpeed);
            return true;
        }
        motor.drive(p, dx, dz, 0.2, 0f, false, false);
        Location eye = p.getEyeLocation();
        if (!motor.aim(p, Motor.yawTo(aim.getX() - eye.getX(), aim.getZ() - eye.getZ()),
                Motor.pitchTo(aim.getX() - eye.getX(), aim.getY() - eye.getY(), aim.getZ() - eye.getZ()), 12f)) return true;
        int slot = freeHandSlot(p);
        if (slot >= 0 && p.getInventory().getHeldItemSlot() != slot && !punchable(p.getInventory().getItemInMainHand())) hold(p, slot, now);
        if (now - bodyHitTick > 12) {
            BotNms.attack(p, b);
            bodyHitTick = now;
            note(name + " ломает тело пилота " + pilotOf(b));
            String owner = pilotOf(b);
            if (owner != null) talk(p, BotChatter.Topic.TOXIC, 0.12, 20 * 45, owner);
        } else if (now - bodyHitTick == 2) {
            BotNms.attack(p, b);
        }
        return true;
    }



    // =====================================================================  враг рядом, но не виден

    private Location losSpot;
    private int losSpotUntil, losFailUntil;

    /** Видно ли цель из точки глаз eye (блоки между не мешают). */
    private static boolean seesFrom(Location eye, LivingEntity e) {
        Location to = e.getEyeLocation();
        org.bukkit.util.Vector dir = to.toVector().subtract(eye.toVector());
        double len = dir.length();
        if (len < 0.1) return true;
        return eye.getWorld().rayTraceBlocks(eye, dir.multiply(1.0 / len), len, org.bukkit.FluidCollisionMode.NEVER, true) == null;
    }

    /**
     * Враг в паре шагов, но его не видно (угол, уступ, блок между нами) - игрок шагнул бы
     * туда, откуда видно, или сломал бы мешающий блок. Так и делаем. true - тик занят.
     */
    private boolean seekLineOfSight(Player p, int now, LivingEntity e) {
        if (now < losFailUntil || e == null || e.isDead() || p.isClimbing()) return false; // на лестнице не до этого
        Location l = p.getLocation();
        if (e.getLocation().distance(l) > 5) return false;
        World w = p.getWorld();
        if (losSpot == null || now > losSpotUntil) {
            losSpot = null;
            losSpotUntil = now + 20;
            int fy = (int) Math.floor(l.getY() + 0.01);
            double bd = Double.MAX_VALUE;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 1; dy++) {
                int x = l.getBlockX() + dx, y = fy + dy, z = l.getBlockZ() + dz;
                if (!walkable(w, x, y, z)) continue;
                Location eye = new Location(w, x + 0.5, y + 1.62, z + 0.5);
                if (!seesFrom(eye, e)) continue;
                double d = dx * dx + dz * dz + dy * dy * 2;
                if (d < bd) { bd = d; losSpot = new Location(w, x + 0.5, y, z + 0.5); }
            }
        }
        if (losSpot != null) {
            double dx = losSpot.getX() - l.getX(), dz = losSpot.getZ() - l.getZ();
            Location eye = e.getEyeLocation();
            motor.turn(p, yawTo(p, eye), pitchTo(p, eye), skill.turnSpeed);
            motor.drive(p, dx, dz, 1.0, 0f, losSpot.getY() > l.getY() + 0.5 && BotNms.onGround(p), false);
            return true;
        }
        // Шагнуть некуда - ломаем то, что между нами.
        Location eye = p.getEyeLocation();
        org.bukkit.util.Vector dir = e.getEyeLocation().toVector().subtract(eye.toVector());
        double len = dir.length();
        org.bukkit.util.RayTraceResult hit = len < 0.1 ? null
            : w.rayTraceBlocks(eye, dir.multiply(1.0 / len), len, org.bukkit.FluidCollisionMode.NEVER, true);
        Block b = hit == null ? null : hit.getHitBlock();
        // Лестницу, по которой лезем, и опору под ногами не ломаем.
        boolean underFeet = b != null && b.getY() < l.getY() && Math.abs(b.getX() + 0.5 - l.getX()) < 1 && Math.abs(b.getZ() + 0.5 - l.getZ()) < 1;
        if (b != null && !climbable(b.getType()) && !underFeet && Builder.canDig(p, b) && !builder.isDenied(b)) {
            motor.stop(p);
            if (builder.mine(p, b, now)) return true;
        }
        // Ни увидеть, ни пробиться - пусть идёт своей дорогой, займёмся другим.
        losFailUntil = now + 20 * 15;
        ignoreUntil.put(e.getUniqueId(), now + 20 * 15);
        if (target != null && target.entity == e) target = null;
        note(name + " не видит " + e.getName() + " рядом и не пробиться, бросаю");
        return false;
    }

    // =====================================================================  стоим на месте

    private Location stuckPos;
    private int stuckSince, wanderUntil = -1, wanderDx, wanderDz;

    /**
     * Стоим на месте 6 секунд, хотя цель зовёт идти (путь не находится: щель между домами,
     * угол, завал): пару секунд идём туда, где просторнее, потом планируем заново.
     */
    private Goal trapGoal, stuckGoal;

    private void checkStuck(Player p, int now) {
        Location l = p.getLocation();
        if (goal != stuckGoal) { stuckGoal = goal; stuckPos = null; }
        if (stuckPos == null || !stuckPos.getWorld().equals(l.getWorld()) || stuckPos.distanceSquared(l) > 1.5 * 1.5) {
            stuckPos = l.clone();
            stuckSince = now;
            return;
        }
        if (now - stuckSince < 120 || now < wanderUntil) return;
        switch (goal) {
            case LOOT: case PICKUP: case ROAM: case FOLLOW: case HUNT: case AIRDROP: case SHARE: case ZONE: case CHASE: case AVOID_WARDEN: case CENTER: case PLAN:
                break;
            default:
                return;
        }
        if (chestOpenAt >= 0 || builder.isMining() || now < busyUntil || pitMode || now < escapeUntil || now < climbUntil
                || p.isInsideVehicle() || rides.active() || !BotNms.onGround(p) || now - lastTunnel < 40) return;
        Location g = nav.getGoal();
        if (g != null && g.getWorld().equals(l.getWorld()) && Math.hypot(g.getX() - l.getX(), g.getZ() - l.getZ()) < 3) return; // уже на месте
        stuckSince = now;
        // Рядом закрытая дверь или калитка - открываем и ищем путь заново.
        if (openNearbyPassages(p, now) > 0) {
            nav.clear();
            note(name + " стоит на месте (" + goal + "), открыл дверь/калитку");
            return;
        }
        startLocalPath(p, now, "стоит на месте (" + goal + ")");
    }

    /** Открывает закрытые деревянные двери и калитки рядом (до которых дотянуться рукой). Сколько открыл. */
    private int openNearbyPassages(Player p, int now) {
        Location l = p.getLocation();
        World w = p.getWorld();
        int fy = (int) Math.floor(l.getY() + 0.01), n = 0;
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = -1; dy <= 1; dy++) {
            Block b = w.getBlockAt(l.getBlockX() + dx, fy + dy, l.getBlockZ() + dz);
            if (!(b.getBlockData() instanceof org.bukkit.block.data.Openable)) continue;
            String t = b.getType().name();
            if (!(t.endsWith("_DOOR") || t.endsWith("_FENCE_GATE")) || t.startsWith("IRON_")) continue;
            if (((org.bukkit.block.data.Openable) b.getBlockData()).isOpen()) continue;
            if (openPassage(p, b, false, now)) n++;
        }
        return n;
    }

    private static boolean walkable(World w, int x, int y, int z) {
        Block f = w.getBlockAt(x, y, z), h = w.getBlockAt(x, y + 1, z), floor = w.getBlockAt(x, y - 1, z);
        if (f.isLiquid() || h.isLiquid() || floor.isPassable() && f.isPassable()) return false; // вода, лава, нет пола
        Material fm = floor.getType();
        if (fm == Material.LAVA || fm == Material.MAGMA_BLOCK || fm == Material.CACTUS) return false;
        // Верх забора или стены (полтора блока): на него не запрыгнуть, это не пол.
        if (f.isPassable() && Motor.tallAt(w, x, z, y - 1)) return false;
        return headroom(w, x, y, z) >= 1.5; // присев пролезем
    }


    private java.util.List<int[]> wanderPath;
    private int wanderIdx, wanderBestTick;
    private double wanderBest;

    /** Клетка, куда можно встать: пол, присев пролезаем, или деревянная дверь/калитка (откроем). */
    private static boolean cellOk(World w, int x, int y, int z) {
        if (walkable(w, x, y, z)) return true;
        Block f = w.getBlockAt(x, y, z);
        if (!woodenPassage(f)) return false;
        Block floor = w.getBlockAt(x, y - 1, z), h = w.getBlockAt(x, y + 1, z);
        return !floor.isPassable() && (h.isPassable() || woodenPassage(h));
    }

    private static long cellKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /**
     * Свой короткий поиск пути (навигатор мобов в щели, под полублоки и через калитки не
     * ходит): в ширину на 16 блоков вокруг, с приседом, ступенькой вверх и спуском до 3.
     * Конец - клетка подальше отсюда и поближе к цели.
     */
    private java.util.List<int[]> localEscapePath(Player p, Location toward) {
        Location l = p.getLocation();
        World w = p.getWorld();
        int sx = l.getBlockX(), sy = (int) Math.floor(l.getY() + 0.01), sz = l.getBlockZ();
        java.util.Map<Long, int[]> from = new java.util.HashMap<Long, int[]>();
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<int[]>();
        int[] start = {sx, sy, sz, 0};
        q.add(start);
        from.put(cellKey(sx, sy, sz), null);
        int[] best = null;
        double bestScore = 2.5;
        double tdx = 0, tdz = 0;
        if (toward != null) {
            tdx = toward.getX() - l.getX(); tdz = toward.getZ() - l.getZ();
            double len = Math.hypot(tdx, tdz);
            if (len > 1e-3) { tdx /= len; tdz /= len; } else { tdx = 0; tdz = 0; }
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty() && from.size() < 900) {
            int[] c = q.poll();
            double ox = c[0] - sx, oz = c[2] - sz;
            if (Math.abs(ox) > 16 || Math.abs(oz) > 16) continue;
            double score = c[3] * 0.6 + Math.hypot(ox, oz) + (ox * tdx + oz * tdz) * 1.5;
            if (c[3] >= 3 && score > bestScore) { bestScore = score; best = c; }
            for (int[] d : dirs) {
                int nx = c[0] + d[0], nz = c[2] + d[1];
                int ny = Integer.MIN_VALUE;
                if (cellOk(w, nx, c[1], nz)) ny = c[1];
                else if (w.getBlockAt(c[0], c[1] + 2, c[2]).isPassable() && cellOk(w, nx, c[1] + 1, nz)
                        && w.getBlockAt(nx, c[1] + 2, nz).isPassable()) ny = c[1] + 1; // ступенька, прыжком
                else if (w.getBlockAt(nx, c[1], nz).isPassable() && w.getBlockAt(nx, c[1] + 1, nz).isPassable()) {
                    for (int dy = 1; dy <= 3; dy++) {
                        if (cellOk(w, nx, c[1] - dy, nz)) { ny = c[1] - dy; break; }
                        if (!w.getBlockAt(nx, c[1] - dy, nz).isPassable()) break;
                    }
                }
                if (ny == Integer.MIN_VALUE) continue;
                long k = cellKey(nx, ny, nz);
                if (from.containsKey(k)) continue;
                from.put(k, c);
                q.add(new int[]{nx, ny, nz, c[3] + 1});
            }
        }
        if (best == null) return null;
        java.util.LinkedList<int[]> path = new java.util.LinkedList<int[]>();
        for (int[] c = best; c != null; c = from.get(cellKey(c[0], c[1], c[2]))) path.addFirst(c);
        if (!path.isEmpty()) path.removeFirst(); // клетка, где стоим
        return path;
    }

    /** Начать идти по своему короткому пути. false - идти некуда. */
    private boolean startLocalPath(Player p, int now, String why) {
        Location g = goal == Goal.FIGHT && target != null ? target.last : goal == Goal.ZONE ? zoneSafePoint(p) : nav.getGoal();
        java.util.List<int[]> path = localEscapePath(p, g);
        if (path == null || path.isEmpty()) return false;
        wanderPath = path;
        wanderIdx = 0;
        wanderUntil = now + 40 + path.size() * 15;
        wanderBestTick = now;
        wanderBest = Double.MAX_VALUE;
        nav.clear();
        if (skill.debug) {
            int[] e = path.get(path.size() - 1);
            mgr.debug(name + " " + why + ", свой путь на " + path.size() + " шагов к " + e[0] + "," + e[1] + "," + e[2]);
        }
        return true;
    }

    /** Шаг по своему пути: присев под низким потолком, с прыжком на ступеньку, двери открываем. */
    private void localPathStep(Player p, int now) {
        Location l = p.getLocation();
        World w = p.getWorld();
        if (wanderPath == null || wanderIdx >= wanderPath.size()) { wanderUntil = -1; wanderPath = null; BotNms.sneak(p, false); return; }
        int[] c = wanderPath.get(wanderIdx);
        double dx = c[0] + 0.5 - l.getX(), dz = c[2] + 0.5 - l.getZ();
        if (dx * dx + dz * dz < 0.35 * 0.35 && Math.abs(l.getY() - c[1]) < 0.7) {
            wanderIdx++;
            wanderBest = Double.MAX_VALUE;
            wanderBestTick = now;
            if (wanderIdx >= wanderPath.size()) { wanderUntil = -1; wanderPath = null; BotNms.sneak(p, false); motor.stop(p); return; }
            c = wanderPath.get(wanderIdx);
            dx = c[0] + 0.5 - l.getX(); dz = c[2] + 0.5 - l.getZ();
        }
        // Сбились с пути (отбросило, телепорт) или 2 секунды не приближаемся к клетке - бросаем его.
        double d2 = dx * dx + dz * dz;
        if (d2 < wanderBest - 0.05) { wanderBest = d2; wanderBestTick = now; }
        if (d2 > 2.5 * 2.5 || Math.abs(l.getY() - c[1]) > 2.5 || now - wanderBestTick > 40) {
            wanderUntil = -1; wanderPath = null; BotNms.sneak(p, false); nav.clear(); return;
        }
        openPassage(p, w.getBlockAt(c[0], c[1], c[2]), false, now);
        openPassage(p, w.getBlockAt(c[0], c[1] + 1, c[2]), true, now);
        double here = headroom(w, l.getBlockX(), l.getY(), l.getBlockZ()), there = headroom(w, c[0], c[1], c[2]);
        boolean crouch = here > 0 && here < 1.8 || there > 0 && there < 1.8;
        boolean up = c[1] > l.getY() + 0.5;
        BotNms.sneak(p, crouch && !up);
        motor.turn(p, Motor.yawTo(dx, dz), 0f, Math.min(skill.turnSpeed, 20f));
        motor.drive(p, dx, dz, crouch ? 1.0 : 0.8, 0f, up && BotNms.onGround(p), false);
    }

    /** Из 8 направлений то, где дальше всего можно пройти (ровно или ступенькой в блок). */
    private int[] openDirection(Player p) {
        Location l = p.getLocation();
        World w = p.getWorld();
        int x0 = l.getBlockX(), y0 = (int) Math.floor(l.getY() + 0.01), z0 = l.getBlockZ();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        int[] best = null;
        double bestScore = 1.5; // хотя бы два шага
        for (int[] d : dirs) {
            int run = 0, y = y0;
            for (int r = 1; r <= 12; r++) {
                int x = x0 + d[0] * r, z = z0 + d[1] * r;
                if (walkable(w, x, y, z)) run++;
                else if (walkable(w, x, y + 1, z) && w.getBlockAt(x - d[0], y + 2, z - d[1]).isPassable()) { y++; run++; }
                else if (walkable(w, x, y - 1, z)) { y--; run++; }
                else break;
            }
            double score = run + rnd.nextDouble() * 0.5;
            if (score > bestScore) { bestScore = score; best = new int[]{d[0], d[1], run}; }
        }
        return best;
    }



    // =====================================================================  лестницы, лианы, подмостки

    private int climbX, climbZ, climbBottom, climbTop, climbCheckAt, climbUntil = -1, climbBanUntil;
    private boolean climbUp;

    static boolean climbable(Material m) {
        return m == Material.LADDER || m == Material.VINE || m == Material.SCAFFOLDING
            || m == Material.TWISTING_VINES || m == Material.TWISTING_VINES_PLANT || m == Material.WEEPING_VINES
            || m == Material.WEEPING_VINES_PLANT || m == Material.CAVE_VINES || m == Material.CAVE_VINES_PLANT;
    }

    /**
     * Цель заметно выше или ниже, а пути к ней нет (навигатор по лестницам не ходит): ищем
     * рядом лестницу, лиану или подмостки, которые ведут в нужную сторону, и лезем по ним.
     */
    private void checkClimb(Player p, int now) {
        if (now < climbCheckAt || now < climbUntil) return;
        climbCheckAt = now + 20;
        if (now < climbBanUntil || pitMode || now < escapeUntil || p.isInsideVehicle() || rides.active() || !BotNms.onGround(p)) return;
        switch (goal) {
            case DODGE: case EVADE: case HEAL: case DROP: case NUKE: case CAVE: case HOLD:
                return;
            case FIGHT:
                if (hasRanged(p, now)) return; // со стволом стреляем отсюда
                break;
            default:
        }
        Location l = p.getLocation();
        Location g = goal == Goal.FIGHT && target != null ? target.last : nav.getGoal();
        if (g == null || !g.getWorld().equals(l.getWorld())) return;
        double dy = g.getY() - l.getY();
        if (Math.abs(dy) < 3 || nav.hasPath() && nav.reaches()) return;
        // Лестница - к цели рядом (крыша, подвал). Далёкая цель ниже или выше просто по рельефу:
        // лезть ради неё в колодец бесполезно.
        if (Math.hypot(g.getX() - l.getX(), g.getZ() - l.getZ()) > 20) return;
        boolean up = dy > 0;
        World w = p.getWorld();
        int fy = (int) Math.floor(l.getY() + 0.01), bx = l.getBlockX(), bz = l.getBlockZ();
        double best = Double.MAX_VALUE;
        boolean found = false;
        for (int x = bx - 12; x <= bx + 12; x++) {
            for (int z = bz - 12; z <= bz + 12; z++) {
                for (int y = fy - 2; y <= fy + 1; y++) {
                    if (!climbable(w.getBlockAt(x, y, z).getType())) continue;
                    int bottom = y, top = y;
                    while (bottom > fy - 48 && climbable(w.getBlockAt(x, bottom - 1, z).getType())) bottom--;
                    while (top < fy + 48 && climbable(w.getBlockAt(x, top + 1, z).getType())) top++;
                    if (up ? top < fy + 2 : bottom > fy - 3) break; // в нужную сторону не ведёт
                    double d = (x - bx) * (x - bx) + (z - bz) * (z - bz) + 2 * Math.abs((up ? top : bottom) - g.getY());
                    if (d < best) { best = d; climbX = x; climbZ = z; climbBottom = bottom; climbTop = top; found = true; }
                    break;
                }
            }
        }
        if (!found) { climbBanUntil = now + 20 * 8; return; }
        climbUp = up;
        climbUntil = now + 20 * 45;
        nav.clear();
        note(name + " лезет " + (up ? "вверх" : "вниз") + " по " + w.getBlockAt(climbX, up ? climbBottom : climbTop, climbZ).getType()
            + " у " + climbX + "," + climbZ + " (" + climbBottom + ".." + climbTop + ")");
    }

    private void stopClimb(Player p, int now, int ban) {
        climbUntil = -1;
        climbBanUntil = now + ban;
        nav.clear();
        BotNms.sneak(p, false);
    }

    /** Куда шагнуть со столба наверху (или внизу): соседняя клетка, где можно стоять. */
    private static int[] climbExit(World w, int x, int y, int z, Location toward) {
        int[] best = null;
        double bd = Double.MAX_VALUE;
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            if (!walkable(w, x + d[0], y, z + d[1])) continue;
            double dd = toward == null ? 0 : Math.hypot(x + d[0] + 0.5 - toward.getX(), z + d[1] + 0.5 - toward.getZ());
            if (dd < bd) { bd = dd; best = d; }
        }
        return best;
    }

    /** Шаг по лестнице/лиане/подмосткам. true - тик занят. */
    private boolean climbStep(Player p, int now) {
        if (now > climbUntil) { stopClimb(p, now, 20 * 20); return false; }
        Location l = p.getLocation();
        World w = p.getWorld();
        double cx = climbX + 0.5, cz = climbZ + 0.5;
        boolean inColumn = Math.abs(l.getX() - cx) < 0.65 && Math.abs(l.getZ() - cz) < 0.65;
        Location g = goal == Goal.FIGHT && target != null ? target.last : nav.getGoal();
        if (climbUp) {
            if (l.getY() >= climbTop + 0.95) {
                // Наверху: шагаем на площадку рядом со столбом.
                int[] ex = climbExit(w, climbX, climbTop + 1, climbZ, g);
                if (ex == null || !inColumn && BotNms.onGround(p)) { stopClimb(p, now, 20 * 5); return false; }
                motor.turn(p, Motor.yawTo(ex[0], ex[1]), 0f, skill.turnSpeed);
                motor.drive(p, ex[0], ex[1], 1.0, 0f, true, false);
                return true;
            }
            if (!inColumn) {
                if (l.getY() > climbBottom + 1.5 && BotNms.onGround(p)) { stopClimb(p, now, 20 * 10); return false; } // слезли не туда
                // Подходим к низу столба.
                nav.setGoal(new Location(w, cx, climbBottom, cz), 0);
                Navigator.Move m = nav.tick(p, now);
                double dx = m.active && nav.hasPath() ? m.dx : cx - l.getX(), dz = m.active && nav.hasPath() ? m.dz : cz - l.getZ();
                motor.turn(p, Motor.yawTo(dx, dz), 0f, skill.turnSpeed);
                motor.drive(p, dx, dz, 1.0, 0f, m.jump || BotNms.horizontalCollision(p) && BotNms.onGround(p), false);
                return true;
            }
            // В столбе: прыжок держим - поднимаемся (у лестницы ещё и жмёмся к стене).
            double wx = 0, wz = 0;
            org.bukkit.block.data.BlockData bd = w.getBlockAt(climbX, (int) Math.floor(l.getY() + 0.01), climbZ).getBlockData();
            if (bd instanceof org.bukkit.block.data.Directional) {
                org.bukkit.block.BlockFace f = ((org.bukkit.block.data.Directional) bd).getFacing().getOppositeFace();
                wx = f.getModX(); wz = f.getModZ();
            }
            BotNms.sneak(p, false);
            if (wx != 0 || wz != 0) motor.turn(p, Motor.yawTo(wx, wz), -30f, skill.turnSpeed);
            motor.drive(p, wx + (cx - l.getX()), wz + (cz - l.getZ()), 0.4, 0f, true, false);
            return true;
        }
        // Вниз.
        if (BotNms.onGround(p) && l.getY() < climbBottom + 1.2) {
            // Внизу: отходим от столба и дальше обычным путём.
            stopClimb(p, now, 20 * 5);
            return false;
        }
        // Сверху столб закрыт люком - открываем (стоя рядом или прямо на нём).
        Block hatch = w.getBlockAt(climbX, climbTop + 1, climbZ);
        if (hatch.getType().name().endsWith("_TRAPDOOR") && hatch.getBlockData() instanceof org.bukkit.block.data.Openable
                && !((org.bukkit.block.data.Openable) hatch.getBlockData()).isOpen()) clickOpen(p, hatch, now);
        if (!inColumn) {
            if (l.getY() < climbTop - 0.5 && BotNms.onGround(p)) { stopClimb(p, now, 20 * 10); return false; }
            // Подходим к верху столба и шагаем на него.
            double dx = cx - l.getX(), dz = cz - l.getZ();
            if (Math.hypot(dx, dz) > 1.6) {
                nav.setGoal(new Location(w, cx, climbTop + 1, cz), 1);
                Navigator.Move m = nav.tick(p, now);
                if (m.active && nav.hasPath()) { dx = m.dx; dz = m.dz; }
            }
            motor.turn(p, Motor.yawTo(dx, dz), 20f, skill.turnSpeed);
            motor.drive(p, dx, dz, 0.6, 0f, false, false);
            return true;
        }
        // В столбе: без прыжка сползаем вниз; подмостки - присев.
        boolean scaffold = w.getBlockAt(climbX, (int) Math.floor(l.getY() - 0.2), climbZ).getType() == Material.SCAFFOLDING
            || w.getBlockAt(climbX, (int) Math.floor(l.getY() + 0.01), climbZ).getType() == Material.SCAFFOLDING;
        BotNms.sneak(p, scaffold);
        motor.drive(p, cx - l.getX(), cz - l.getZ(), 0.15, 0f, false, false);
        return true;
    }

    // =====================================================================  финал: к центру карты

    private Location centerRoam;
    private int centerRoamUntil, finaleUnderSince = -1, centerTunnelUntil;

    /** До конца игры меньше 3 минут. */
    private boolean finale() {
        int left = hooks.remainingTicks();
        return hooks.gameActive() && left > 0 && left < 20 * 180;
    }

    /** Самый центр зоны, на поверхности (крона дерева не в счёт). */
    private Location mapCenter(Player p) {
        World w = p.getWorld();
        Location c = w.getWorldBorder().getCenter();
        int x = c.getBlockX(), z = c.getBlockZ();
        return new Location(w, x + 0.5, groundTop(w, x, z, p.getLocation().getBlockY()), z + 0.5);
    }

    /** Поверхность в столбце (x,z): верх самого высокого блока без листвы. */
    private int groundTop(World w, int x, int z, int near) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return near;
        return w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
    }

    /** Под землёй: неба не видно, а над головой камень или земля (не крыша дома). */
    private static boolean belowGround(Player p) {
        Location eye = p.getEyeLocation();
        if (eye.getBlock().getLightFromSky() > 0) return false;
        World w = p.getWorld();
        int x = eye.getBlockX(), z = eye.getBlockZ(), top = Math.min(w.getMaxHeight() - 1, eye.getBlockY() + 30);
        for (int y = eye.getBlockY() + 1; y <= top; y++) {
            Material m = w.getBlockAt(x, y, z).getType();
            if (org.bukkit.Tag.BASE_STONE_OVERWORLD.isTagged(m) || org.bukkit.Tag.DIRT.isTagged(m) || m == Material.GRAVEL
                    || m == Material.SAND || m == Material.SANDSTONE || m.name().endsWith("_ORE")) return true;
        }
        return false;
    }

    /** Пути нет: прорубаемся к точке (по оси, где разница больше; вверх или вниз по высоте). */
    private void tunnelToward(Player p, int now, Location g) {
        Location loc = p.getLocation();
        double gx = g.getX() - loc.getX(), gz = g.getZ() - loc.getZ();
        if (now - tunnelDirTick > 60 || tunnelDx == 0 && tunnelDz == 0) {
            tunnelDirTick = now;
            if (Math.abs(gx) >= Math.abs(gz)) { tunnelDx = gx > 0 ? 1 : -1; tunnelDz = 0; }
            else { tunnelDx = 0; tunnelDz = gz > 0 ? 1 : -1; }
        }
        double dy = g.getY() - loc.getY();
        caveDx = tunnelDx; caveDz = tunnelDz;
        tunnel(p, now, tunnelDx, tunnelDz, dy >= 2 ? 1 : (dy <= -2 ? -1 : 0));
        tunnelDx = caveDx; tunnelDz = caveDz;
    }

    // =====================================================================  узкие места, двери, люки

    /**
     * Свободная высота в клетке (bx,bz) у уровня ног feet: пол - то, на что можно шагнуть
     * (ковёр, полублок до 0.6), потолок - ближайший блок сверху. 0 - там стена.
     */
    private static double headroom(World w, int bx, double feet, int bz) {
        int y0 = (int) Math.floor(feet);
        double floor = feet, ceil = feet + 3;
        for (int y = y0 - 1; y <= y0 + 2; y++) {
            Block b = w.getBlockAt(bx, y, bz);
            if (b.isPassable()) continue;
            for (org.bukkit.util.BoundingBox bb : b.getCollisionShape().getBoundingBoxes()) {
                double lo = y + bb.getMinY(), hi = y + bb.getMaxY();
                if (hi <= feet + 0.6) { if (hi > floor) floor = hi; continue; } // пол, ступенька
                if (lo < feet + 0.6) return 0; // стена, забор
                if (lo < ceil) ceil = lo;
            }
        }
        return ceil - floor;
    }

    /** Здесь или чуть впереди потолок ниже роста (полублок, ковёр под ногами): пролезть можно только присев. */
    private static boolean lowGapAhead(Player p, double dx, double dz) {
        Location l = p.getLocation();
        World w = p.getWorld();
        double here = headroom(w, l.getBlockX(), l.getY(), l.getBlockZ());
        if (here >= 1.5 && here < 1.8) return true;
        double len = Math.hypot(dx, dz);
        if (len < 1e-3) return false;
        double h = headroom(w, (int) Math.floor(l.getX() + dx / len * 0.7), l.getY(), (int) Math.floor(l.getZ() + dz / len * 0.7));
        return h >= 1.5 && h < 1.8;
    }

    private int nextDoorCheck;

    /** Деревянная дверь, калитка или люк прямо на пути (впереди, над головой на лестнице) - открываем, как игрок. */
    private void openWayAhead(Player p, double dx, double dz, int now) {
        if (now < nextDoorCheck) return;
        nextDoorCheck = now + 4;
        Location l = p.getLocation();
        World w = p.getWorld();
        int fy = (int) Math.floor(l.getY() + 0.01);
        double len = Math.hypot(dx, dz);
        if (len > 1e-3) {
            int fx = (int) Math.floor(l.getX() + dx / len * 0.8), fz = (int) Math.floor(l.getZ() + dz / len * 0.8);
            openPassage(p, w.getBlockAt(fx, fy, fz), false, now);
            openPassage(p, w.getBlockAt(fx, fy + 1, fz), true, now);
        }
        // Люк в потолке над лестницей.
        if (p.isClimbing()) openPassage(p, w.getBlockAt(l.getBlockX(), fy + 2, l.getBlockZ()), true, now);
    }

    /**
     * Открыть дверь/калитку/люк (не железные, не за зоной). Люк - только мешающий голове или
     * сверху, не в полу. true - открыли.
     */
    private boolean openPassage(Player p, Block b, boolean trapdoorOk, int now) {
        org.bukkit.block.data.BlockData d = b.getBlockData();
        if (!(d instanceof org.bukkit.block.data.Openable)) return false;
        String type = b.getType().name();
        if (type.startsWith("IRON_") || !Builder.inZone(b)) return false;
        boolean trap = type.endsWith("_TRAPDOOR");
        if (trap ? !trapdoorOk : !(type.endsWith("_DOOR") || type.endsWith("_FENCE_GATE"))) return false;
        return clickOpen(p, b, now);
    }

    /**
     * Открыть, как игрок: ПКМ по закрытой двери, калитке или люку. Сервер сам проверяет
     * дальность, зону, приваты и запреты других плагинов, звук тоже его. true - открылась.
     */
    boolean clickOpen(Player p, Block b, int now) {
        if (!(b.getBlockData() instanceof org.bukkit.block.data.Openable)
                || ((org.bukkit.block.data.Openable) b.getBlockData()).isOpen()) return false;
        if (isLocked(b.getX(), b.getY(), b.getZ())) return false; // уже знаем: не открывается
        Location eye = p.getEyeLocation();
        double cx = b.getX() + 0.5 - eye.getX(), cy = b.getY() + 0.5 - eye.getY(), cz = b.getZ() + 0.5 - eye.getZ();
        if (cx * cx + cy * cy + cz * cz > 4.4 * 4.4 || now < busyUntil) return false;
        // Плагинный предмет в руке (ствол, дрон, рация) сработает от ПКМ сам: сначала пустая рука.
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held != null && !held.getType().isAir() && held.hasItemMeta() && Items.isCustom(held)) {
            int slot = freeHandSlot(p);
            if (slot < 0 || !hold(p, slot, now)) return false;
        }
        // Присев с вещью в руке дверь не открыть.
        if (p.isSneaking()) BotNms.sneak(p, false);
        // Жмём по грани, которая смотрит на бота, и смотрим в точку клика: античит и защита
        // регионов принимают клик только по тому, на что игрок смотрит.
        int face;
        double hx = b.getX() + 0.5, hy = b.getY() + 0.5, hz = b.getZ() + 0.5;
        if (Math.abs(cy) > Math.max(Math.abs(cx), Math.abs(cz))) { face = cy > 0 ? 0 : 1; hy += cy > 0 ? -0.5 : 0.5; }
        else if (Math.abs(cx) >= Math.abs(cz)) { face = cx > 0 ? 4 : 5; hx += cx > 0 ? -0.5 : 0.5; }
        else { face = cz > 0 ? 2 : 3; hz += cz > 0 ? -0.5 : 0.5; }
        // Голову доворачиваем за несколько тиков (не щелчком), кликаем, когда навелись.
        if (!motor.aim(p, Motor.yawTo(hx - eye.getX(), hz - eye.getZ()),
                Motor.pitchTo(hx - eye.getX(), hy - eye.getY(), hz - eye.getZ()), 8f)) return false;
        BotNms.useItemOn(p, b.getX(), b.getY(), b.getZ(), face);
        p.swingMainHand();
        boolean open = b.getBlockData() instanceof org.bukkit.block.data.Openable
            && ((org.bukkit.block.data.Openable) b.getBlockData()).isOpen();
        if (open) return true;
        // Клик отменил другой плагин (на некоторых серверах у ботов отменяют любые действия,
        // как у не вошедших игроков) - открываем сами, как раньше.
        warnClickCancelled(b);
        if (forceOpen(b)) return true;
        // Не открылась и так (повторно) - заперта: 2 минуты считаем стеной,
        // путь идёт в обход, руками в неё не машем. Знание общее для всех ботов.
        long k = dev.volansvo.svo.bots.nav.Pos.pack(b.getX(), b.getY(), b.getZ());
        Integer tries = doorTries.get(k);
        doorTries.put(k, tries == null ? 1 : tries + 1);
        if (tries != null && tries >= 1) {
            doorTries.remove(k);
            lockDoor(b);
            nav.clear();
            note(name + " не открывается " + b.getType() + " " + b.getX() + "," + b.getY() + "," + b.getZ() + ", иду в обход");
        }
        return false;
    }

    /** Открыть дверь/калитку/люк напрямую (клик отменён). true - открыта. */
    private static boolean forceOpen(Block b) {
        org.bukkit.block.data.BlockData d = b.getBlockData();
        if (!(d instanceof org.bukkit.block.data.Openable)) return false;
        org.bukkit.block.data.Openable o = (org.bukkit.block.data.Openable) d;
        o.setOpen(true);
        b.setBlockData(o, true);
        String type = b.getType().name();
        b.getWorld().playSound(b.getLocation(), type.endsWith("_TRAPDOOR") ? org.bukkit.Sound.BLOCK_WOODEN_TRAPDOOR_OPEN
            : type.endsWith("_GATE") ? org.bukkit.Sound.BLOCK_FENCE_GATE_OPEN : org.bukkit.Sound.BLOCK_WOODEN_DOOR_OPEN, 1f, 1f);
        return b.getBlockData() instanceof org.bukkit.block.data.Openable && ((org.bukkit.block.data.Openable) b.getBlockData()).isOpen();
    }

    private final java.util.Map<Long, Integer> doorTries = new java.util.HashMap<Long, Integer>();
    /** Двери, которые не открылись от ПКМ: позиция -> до какого времени (мс) считаем запертой. */
    private static final java.util.Map<Long, Long> LOCKED = new java.util.HashMap<Long, Long>();
    private static boolean warnedLocked;

    static boolean isLocked(int x, int y, int z) {
        return isLocked(dev.volansvo.svo.bots.nav.Pos.pack(x, y, z));
    }

    static boolean isLocked(long k) {
        if (LOCKED.isEmpty()) return false;
        Long until = LOCKED.get(k);
        if (until == null) return false;
        if (System.currentTimeMillis() < until) return true;
        LOCKED.remove(k);
        return false;
    }

    /** Запомнить запертую дверь (обе половины). */
    private static void lockDoor(Block b) {
        long until = System.currentTimeMillis() + 120_000L;
        LOCKED.put(dev.volansvo.svo.bots.nav.Pos.pack(b.getX(), b.getY(), b.getZ()), until);
        if (b.getBlockData() instanceof org.bukkit.block.data.Bisected) {
            boolean top = ((org.bukkit.block.data.Bisected) b.getBlockData()).getHalf() == org.bukkit.block.data.Bisected.Half.TOP;
            Block o = b.getRelative(top ? org.bukkit.block.BlockFace.DOWN : org.bukkit.block.BlockFace.UP);
            LOCKED.put(dev.volansvo.svo.bots.nav.Pos.pack(o.getX(), o.getY(), o.getZ()), until);
        }
        if (LOCKED.size() > 500) LOCKED.clear();
    }

    /** Один раз в консоль: ПКМ бота по двери отменили - кто слушает клики. */
    private static void warnClickCancelled(Block b) {
        if (warnedLocked) return;
        warnedLocked = true;
        java.util.Set<String> who = new java.util.TreeSet<String>();
        for (org.bukkit.plugin.RegisteredListener rl : org.bukkit.event.player.PlayerInteractEvent.getHandlerList().getRegisteredListeners())
            who.add(rl.getPlugin().getName());
        org.bukkit.Bukkit.getLogger().warning("[VolanSVO] ПКМ бота по " + b.getType() + " в " + b.getWorld().getName() + " "
            + b.getX() + "," + b.getY() + "," + b.getZ() + " отменил другой плагин (защита региона, античит, авторизация). "
            + "Клики слушают: " + who + ". Двери боты открывают сами.");
    }

    // =====================================================================  рыхлый снег

    private int snowDx, snowDz, snowDirUntil, snowUntil = -1, snowSurfaceY;

    /** Провалился в рыхлый снег (без кожаных ботинок в нём тонут и замерзают). */
    private static boolean inPowderSnow(Player p) {
        return p.getLocation().getBlock().getType() == Material.POWDER_SNOW
            || p.getEyeLocation().getBlock().getType() == Material.POWDER_SNOW;
    }

    /** На клетку (x,y,z) можно встать: в ней и над ней не снег и пусто, под ней твёрдый не снег. */
    private static boolean standable(World w, int x, int y, int z) {
        Block feet = w.getBlockAt(x, y, z), head = feet.getRelative(org.bukkit.block.BlockFace.UP), floor = feet.getRelative(org.bukkit.block.BlockFace.DOWN);
        return feet.getType() != Material.POWDER_SNOW && head.getType() != Material.POWDER_SNOW && feet.isPassable() && head.isPassable()
            && floor.getType() != Material.POWDER_SNOW && !floor.isPassable();
    }

    /** Твёрдый блок (не снег) - стенка ямы, в ней рубим ступеньки. */
    private static boolean wallBlock(Block b) {
        return b.getType() != Material.POWDER_SNOW && !b.isPassable();
    }

    /**
     * Выбираемся из рыхлого снега до уровня его поверхности: ломаем снег, в котором стоим,
     * идём к ближайшей твёрдой земле или стенке ямы, ломая снег по пути, в стенке рубим
     * ступеньки. true - тик занят этим.
     */
    private boolean snowStep(Player p, int now) {
        Location l = p.getLocation();
        World w = p.getWorld();
        int x = l.getBlockX(), y = (int) Math.floor(l.getY() + 0.01), z = l.getBlockZ();
        if (inPowderSnow(p) && now >= snowUntil) {
            snowUntil = now + 20 * 30;
            int top = y;
            while (top < y + 6 && w.getBlockAt(x, top, z).getType() == Material.POWDER_SNOW) top++;
            if (w.getBlockAt(x, y + 1, z).getType() == Material.POWDER_SNOW) {
                top = y + 1;
                while (top < y + 6 && w.getBlockAt(x, top, z).getType() == Material.POWDER_SNOW) top++;
            }
            snowSurfaceY = top;
            snowDirUntil = 0;
            note(name + " в рыхлом снегу, выбираюсь до y=" + snowSurfaceY);
        }
        if (!inPowderSnow(p) && BotNms.onGround(p) && l.getY() >= snowSurfaceY - 0.01) { snowUntil = -1; return false; }
        if (now >= snowDirUntil) {
            snowDirUntil = now + 200; // выбранную сторону держим, иначе в квадратной яме бот мечется между стенками
            int best = Integer.MAX_VALUE;
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : dirs) {
                for (int r = 1; r <= 6 && r < best; r++) {
                    int cx = x + d[0] * r, cz = z + d[1] * r;
                    boolean ok = wallBlock(w.getBlockAt(cx, y, cz));
                    for (int dy = -1; dy <= 1 && !ok; dy++) ok = standable(w, cx, y + dy, cz);
                    if (ok) { best = r; snowDx = d[0]; snowDz = d[1]; break; }
                }
            }
            if (best == Integer.MAX_VALUE) {
                Location g = nav.getGoal();
                double gx = g != null ? g.getX() - l.getX() : -Math.sin(Math.toRadians(motor.yaw()));
                double gz = g != null ? g.getZ() - l.getZ() : Math.cos(Math.toRadians(motor.yaw()));
                if (Math.abs(gx) >= Math.abs(gz)) { snowDx = gx > 0 ? 1 : -1; snowDz = 0; } else { snowDx = 0; snowDz = gz > 0 ? 1 : -1; }
            }
        }
        int dx = snowDx, dz = snowDz;
        // Снег, в котором стоим (голова, ноги), и снег на пути и над головой.
        Block[] clear = {
            w.getBlockAt(x, y + 1, z), w.getBlockAt(x, y, z), w.getBlockAt(x + dx, y + 1, z + dz),
            w.getBlockAt(x + dx, y, z + dz), w.getBlockAt(x, y + 2, z), w.getBlockAt(x + dx, y + 2, z + dz),
        };
        for (Block b : clear) {
            if (b.getType() != Material.POWDER_SNOW || !Builder.inZone(b)) continue; // за зоной снег не сломать
            motor.stop(p);
            builder.mine(p, b, now);
            return true;
        }
        // Впереди стенка ямы - ступенька вверх (как из пещеры), иначе просто идём.
        if (wallBlock(w.getBlockAt(x + dx, y, z + dz)) && l.getY() < snowSurfaceY - 0.01) {
            tunnel(p, now, dx, dz, 1);
            if (caveWhy.startsWith("nodig") || caveWhy.startsWith("lava")) snowDirUntil = 0; // эту стенку не прорубить - другая сторона
            return true;
        }
        motor.turn(p, Motor.yawTo(dx, dz), 0f, Math.min(skill.turnSpeed, 20f));
        motor.drive(p, dx, dz, 1.0, 0f, BotNms.horizontalCollision(p) && BotNms.onGround(p), false);
        return true;
    }

    // =====================================================================  пояс шахида (камикадзе)

    private boolean kamikaze;
    private int kamikazeUntil, kamikazeBanUntil, kamikazeTapAt = -1, nextHelpShout;

    private static boolean isVest(ItemStack it) {
        return "suicide_vest".equals(Items.warkitId(it));
    }

    /** Пояс шахида MilitaryCraft есть, хп мало, враг-игрок рядом и своих возле него нет. */
    private boolean kamikazeReady(Player p, int now) {
        if (kamikaze) return target != null && target.entity != null && !target.entity.isDead() && now - target.seenTick < 60;
        if (now < kamikazeBanUntil || p.getHealth() > 8) return false;
        Contact t = target;
        if (t == null || !t.visible || !(t.entity instanceof Player) || t.entity.isDead()) return false;
        if (t.last.distance(p.getLocation()) > 20) return false;
        boolean vest = isVest(p.getInventory().getChestplate());
        for (int i = 0; i < 36 && !vest; i++) vest = isVest(p.getInventory().getItem(i));
        if (!vest) return false;
        for (Player mate : teammates(p)) {
            if (mate.getLocation().distance(t.entity.getLocation()) < 9 || mate.getLocation().distance(p.getLocation()) < 9) return false;
        }
        return true;
    }

    /**
     * Камикадзе: надеваем пояс, с криком бежим во врага и вплотную жмём Shift дважды
     * (пояс срабатывает на два нажатия за полсекунды, если враг в 7 блоках). true - тик занят.
     */
    private boolean kamikazeStep(Player p, int now, LivingEntity e, double d) {
        PlayerInventory inv = p.getInventory();
        if (!kamikaze) {
            if (!isVest(inv.getChestplate())) {
                for (int i = 0; i < 36; i++) {
                    if (!isVest(inv.getItem(i))) continue;
                    ItemStack cur = inv.getChestplate();
                    inv.setChestplate(inv.getItem(i));
                    inv.setItem(i, cur == null || cur.getType().isAir() ? null : cur);
                    break;
                }
            }
            if (!isVest(inv.getChestplate())) { kamikazeBanUntil = now + 20 * 30; return false; }
            kamikaze = true;
            kamikazeUntil = now + 20 * 15;
            kamikazeTapAt = -1;
            mgr.shout(p, BotChatter.Topic.KAMIKAZE, e.getName());
            note(name + " камикадзе на " + e.getName());
        }
        if (now > kamikazeUntil || e.isDead() || !isVest(inv.getChestplate())) { stopKamikaze(p, now); return false; }
        Location loc = p.getLocation();
        double dx = e.getLocation().getX() - loc.getX(), dz = e.getLocation().getZ() - loc.getZ();
        motor.turn(p, Motor.yawTo(dx, dz), 0f, skill.turnSpeed);
        boolean bump = BotNms.horizontalCollision(p) && BotNms.onGround(p);
        if (d > 2.3) {
            if (d > 5 || Motor.tallBetween(p, e.getLocation())) {
                nav.setGoal(e.getLocation(), 1);
                Navigator.Move m = nav.tick(p, now);
                if (m.active && nav.hasPath() && nav.reaches()) motor.drive(p, m.dx, m.dz, 1.0, m.strafeBias, m.jump, true);
                else motor.drive(p, dx, dz, 1.0, 0f, bump, true);
            } else motor.drive(p, dx, dz, 1.0, 0f, bump, true);
            if (d > 4) { kamikazeTapAt = -1; BotNms.sneak(p, false); return true; }
        } else {
            motor.drive(p, dx, dz, 0.5, 0f, false, false);
        }
        // Вплотную: Shift, отпустить, Shift (за 200 мс).
        if (kamikazeTapAt < 0) { kamikazeTapAt = now; BotNms.sneak(p, false); BotNms.sneak(p, true); }
        else if (now - kamikazeTapAt == 2) BotNms.sneak(p, false);
        else if (now - kamikazeTapAt == 4) { mgr.shout(p, BotChatter.Topic.KAMIKAZE_BOOM, e.getName()); BotNms.sneak(p, true); }
        else if (now - kamikazeTapAt > 14) { kamikazeTapAt = -1; BotNms.sneak(p, false); } // не сработало - ещё раз
        return true;
    }

    /** Передумали (враг ушёл, время вышло): пояс снимаем, иначе любой двойной присед рядом с врагом его взорвёт. */
    private void stopKamikaze(Player p, int now) {
        kamikaze = false;
        kamikazeTapAt = -1;
        kamikazeBanUntil = now + 20 * 20;
        BotNms.sneak(p, false);
        PlayerInventory inv = p.getInventory();
        if (!isVest(inv.getChestplate())) return;
        int free = inv.firstEmpty();
        if (free < 0 || free >= 36) return;
        inv.setItem(free, inv.getChestplate());
        inv.setChestplate(null);
    }

    /** Мало хп в бою - иногда кричим в общий чат «помогите». */
    private void maybeCryForHelp(Player p, int now) {
        if (now < nextHelpShout || p.getHealth() > 6 || p.isDead()) return;
        if (target == null || now - target.seenTick > 60) return;
        nextHelpShout = now + 20 * 60;
        mgr.chat(p, BotChatter.Topic.HELP, 0.2, null, null);
    }

    // =====================================================================  выход из ловушки (крыша, тупик)

    /** Где бот ходил последние минуты (раз в полсекунды, если сдвинулся): по ним можно вернуться. */
    private final java.util.ArrayDeque<Location> crumbs = new java.util.ArrayDeque<Location>();
    private int nextCrumb;
    private Location trapAnchor;
    private int trapSince;
    private int escapeUntil = -1, escapePhaseStart, escapeBestTick;
    private boolean escapeJump;
    private double escapeBest, escapeStartY;
    private Location escapeTo, escapeGoal;
    private Drop escapeDrop;
    private boolean escapeBelow, escapePlatform;
    private int belowBanUntil, platformChecks, platformCheckAt, platformBanUntil;
    private int localTryAt;

    private void recordCrumb(Player p, int now) {
        if (now < nextCrumb || !BotNms.onGround(p) || p.isInsideVehicle()) return;
        nextCrumb = now + 10;
        Location l = p.getLocation();
        Location last = crumbs.peekLast();
        if (last != null && (!last.getWorld().equals(l.getWorld()) || last.distanceSquared(l) > 12 * 12)) crumbs.clear(); // телепорт, смерть, спуск
        else if (last != null && last.distanceSquared(l) < 1.5 * 1.5) return;
        crumbs.addLast(l.clone());
        while (crumbs.size() > 120) crumbs.removeFirst();
    }

    /**
     * Застрял: 25 секунд бегаем по пятачку радиусом 14 блоков, а цель дальше (крыша без
     * спуска, тупик). Тогда идём назад по своим следам - тем же путём, каким пришли; не вышло -
     * прыгаем с того края, где упасть безопаснее всего.
     */
    /** До какого тика прокапываемся из застройки (путь ломает и долгие блоки). */
    private int digOutUntil = -1, passageRetryAt;

    private void checkTrapped(Player p, int now) {
        if (now < escapeUntil) return;
        if (now < gatherUntil) { trapSince = now; return; } // добываем блоки на месте - это не ловушка
        // Сменилась цель (бой -> уход от зоны): кружение ради старой цели ловушкой не считаем.
        if (goal != trapGoal) { trapGoal = goal; trapAnchor = null; }
        if (pitMode || now < climbUntil) { trapAnchor = null; platformChecks = 0; return; }
        Location l = p.getLocation();
        // Летающая постройка: под полом пустота. Побыли на ней ~12 секунд, а цель не тут - слезаем.
        if (now >= platformCheckAt) {
            platformCheckAt = now + 40;
            // В прыжке (бег прыжками) не сбрасываем: проверяем только стоя на полу.
            if (now < platformBanUntil) platformChecks = 0;
            else if (BotNms.onGround(p)) platformChecks = onFloatingFloor(p) ? platformChecks + 1 : 0;
        }
        boolean platform = platformChecks >= 6;
        // Спуск (лестница в доме тоже крутится на пятачке) ловушкой не считаем.
        if (trapAnchor == null || !trapAnchor.getWorld().equals(l.getWorld()) || trapAnchor.distanceSquared(l) > 14 * 14
                || l.getY() < trapAnchor.getY() - 2.5) {
            trapAnchor = l.clone();
            trapSince = now;
            digOutUntil = -1;
            if (!platform) return;
        }
        if (now - trapSince < 20 * 6 && !platform) return;
        switch (goal) {
            case FIGHT: case DODGE: case EVADE: case HEAL: case DROP: case HOLD: case NUKE: case CAVE:
            case WARDEN: case AVOID_WARDEN: case SHARE: case AIRPIG:
                return;
            default:
        }
        if (rides.active() || p.isInsideVehicle() || !BotNms.onGround(p)) return;
        Location g = nav.getGoal();
        if (g != null && !g.getWorld().equals(l.getWorld())) g = null;
        // Заперты в комнате с дверью или калиткой - открываем и пробуем путь заново.
        // Один раз на ловушку: иначе бот без конца «открывает» ту же калитку и не ищет выход.
        if (now - trapSince >= 20 * 6 && now >= passageRetryAt && openNearbyPassages(p, now) > 0) {
            passageRetryAt = now + 20 * 40;
            trapSince = now;
            platformChecks = 0;
            nav.clear();
            note(name + " застрял, открыл дверь/калитку");
            return;
        }
        if (platform) {
            // Цель на этой же постройке и до неё есть путь - не уходим.
            if (g != null && g.getY() > l.getY() - 4 && nav.hasPath() && nav.reaches()) return;
            startEscape(p, now, false, true, g);
            return;
        }
        if (g == null) return;
        // Кружим на пятачке 8 секунд, цель далеко, а пути к ней нет: свой короткий поиск (присев,
        // через двери, ступеньками) уводит туда, где просторнее, не дожидаясь отхода по следам.
        if (now - trapSince >= 20 * 8 && now >= localTryAt && now >= wanderUntil && g.distanceSquared(trapAnchor) >= 16 * 16
                && !(nav.hasPath() && nav.reaches()) && platformChecks == 0 && !onFloatingFloor(p)) { // с летающей постройки - своим спуском
            localTryAt = now + 20 * 8;
            if (startLocalPath(p, now, "кружит на месте (" + goal + ")")) return;
        }
        // Цель почти под нами (союзник в доме, а мы на крыше), пути вниз нет: спрыгиваем, не ждём.
        boolean below = now >= belowBanUntil && g.getY() < l.getY() - 3.5
            && Math.hypot(g.getX() - l.getX(), g.getZ() - l.getZ()) < 24 && !(nav.hasPath() && nav.reaches());
        // Прежде чем прыгать вниз - свой короткий путь (присев под низким потолком, ступеньками).
        if (below && now >= localTryAt && now >= wanderUntil && platformChecks == 0) {
            localTryAt = now + 20 * 8;
            if (startLocalPath(p, now, "цель внизу (" + goal + ")")) return;
        }
        // Застроены (комната без выхода, свои следы не выводят): ищем выход, где ломать меньше
        // всего, разрешая ломать руками и доски, и терракоту, и камень. Пока копаем - не прыгаем.
        if (!below && now - trapSince >= 20 * 12 && now >= digOutUntil && !(nav.hasPath() && nav.reaches())
                && g.distanceSquared(trapAnchor) >= 4 * 4 && skill.navDig) {
            if (digOutUntil < 0 || now - digOutUntil > 20 * 60) {
                digOutUntil = now + 20 * 45;
                nav.clear();
                nav.setGoal(g, 3);
                note(name + " застроен, прокапываюсь наружу (где ломать меньше)");
                return;
            }
        }
        if (now < digOutUntil) return;
        if (!below && (now - trapSince < 20 * 25 || g.distanceSquared(trapAnchor) < 16 * 16)) return;
        startEscape(p, now, below, !below && onFloatingFloor(p), g);
    }

    private void startEscape(Player p, int now, boolean below, boolean platform, Location g) {
        Location l = p.getLocation();
        trapSince = now;
        escapeUntil = now + 20 * (platform ? 45 : 30);
        escapeBelow = below;
        escapePlatform = platform;
        escapeGoal = g == null ? null : g.clone();
        if (below) belowBanUntil = now + 20 * 40;
        if (platform) platformChecks = 0;
        escapeJump = below || platform || crumbs.size() < 3;
        escapePhaseStart = now;
        escapeBestTick = now;
        escapeBest = Double.MAX_VALUE;
        escapeStartY = l.getY();
        escapeTo = null;
        escapeDrop = null;
        fallPrepTried = false;
        nav.clear();
        note(name + " застрял" + (below ? " (цель внизу)" : platform ? " (летающая постройка)" : "")
            + ", выхожу " + (escapeJump ? "прыжком" : "назад по своим следам"));
    }

    /** Стоим на летающей постройке: под полом (до 4 блоков толщиной) 10+ блоков пустоты. */
    private static boolean onFloatingFloor(Player p) {
        Location l = p.getLocation();
        World w = p.getWorld();
        int x = l.getBlockX(), z = l.getBlockZ(), y = (int) Math.floor(l.getY() - 0.001);
        int floor = 0;
        while (floor < 5 && !w.getBlockAt(x, y - floor, z).isPassable()) floor++;
        if (floor == 0 || floor >= 5) return false;
        int air = 0;
        for (int yy = y - floor; yy > w.getMinHeight() && air < 10; yy--) {
            Block b = w.getBlockAt(x, yy, z);
            if (!b.isPassable() || b.isLiquid()) break;
            air++;
        }
        return air >= 10;
    }

    /** Среди следов есть точки заметно ниже: сюда поднялись снизу. */
    private boolean cameFromBelow(Location l) {
        for (Location c : crumbs) if (c.getWorld().equals(l.getWorld()) && c.getY() < l.getY() - 2.5) return true;
        return false;
    }

    private void finishEscape() {
        escapeUntil = -1;
        escapeTo = null;
        escapeDrop = null;
        escapePlatform = false;
        trapAnchor = null;
        crumbs.clear();
        nav.clear();
    }

    /** Шаг выхода из ловушки. true - этот тик занят им. */
    private boolean escapeStep(Player p, int now) {
        Location l = p.getLocation();
        if (!escapeJump) {
            // Назад по следам: ближайшая непройденная крошка дальше 3 блоков.
            if (escapeTo == null || l.distanceSquared(escapeTo) < 1.6 * 1.6) {
                escapeTo = null;
                while (!crumbs.isEmpty()) {
                    Location c = crumbs.pollLast();
                    if (c.getWorld().equals(l.getWorld()) && c.distanceSquared(l) > 3 * 3) { escapeTo = c; break; }
                }
                if (escapeTo == null) { escapeJump = true; escapePhaseStart = now; return false; }
                escapeBest = Double.MAX_VALUE;
                escapeBestTick = now;
            }
            if (trapAnchor != null && l.distanceSquared(trapAnchor) > 16 * 16) { finishEscape(); return false; } // выбрались
            if (BotNms.onGround(p) && l.getY() < escapeStartY - 3.5) { finishEscape(); return false; } // спустились
            double d = l.distance(escapeTo);
            if (d < escapeBest - 0.3) { escapeBest = d; escapeBestTick = now; }
            if (now - escapeBestTick > 20 * 6) { escapeJump = true; escapePhaseStart = now; escapeTo = null; return false; }
            nav.setGoal(escapeTo, 1);
            Navigator.Move m = nav.tick(p, now);
            boolean path = m.active && nav.hasPath();
            double dx = path ? m.dx : escapeTo.getX() - l.getX(), dz = path ? m.dz : escapeTo.getZ() - l.getZ();
            motor.turn(p, Motor.yawTo(dx, dz), 0f, Math.min(skill.turnSpeed, 20f));
            motor.drive(p, dx, dz, 1.0, path ? m.strafeBias : 0f,
                (path && m.jump) || (BotNms.horizontalCollision(p) && BotNms.onGround(p)), false);
            return true;
        }
        // Прыжок: к краю, где упасть безопаснее всего, и шаг вниз (без спринта - не прыжок вдаль).
        if (now - escapePhaseStart > 20 * (escapePlatform ? 35 : 15)) { finishEscape(); return false; }
        if (escapeDrop == null) {
            Location toward = escapeBelow ? escapeGoal : null;
            int maxR = escapePlatform ? 32 : escapeBelow ? 12 : 8;
            double hp = p.getHealth();
            // Сначала так, чтобы осталось хотя бы 8 хп.
            escapeDrop = bestDropEdge(p, toward, Math.max(4, hp - 8), maxR);
            if (escapeDrop == null && escapeBelow && cameFromBelow(l)) {
                // Забрались сюда снизу - спускаемся тем же путём.
                escapeBelow = false;
                escapeJump = false;
                escapePhaseStart = now;
                escapeBest = Double.MAX_VALUE;
                escapeBestTick = now;
                return false;
            }
            // Цель внизу - не ловушка: ради неё больнее трёх сердец не прыгаем, ищем обход.
            if (escapeBelow && escapeDrop != null && escapeDrop.dmg > 6) escapeDrop = bestDropEdge(p, toward, 6, maxR);
            // Заперты (крыша, летающая постройка) и другого пути нет: прыгаем, даже если останется полсердечка.
            if (escapeDrop == null && !escapeBelow) escapeDrop = bestDropEdge(p, toward, hp - 1, maxR);
            if (escapeDrop == null && toward != null && !escapeBelow) escapeDrop = bestDropEdge(p, null, hp - 1, maxR);
            if (escapeDrop == null) {
                boolean wasPlatform = escapePlatform;
                finishEscape();
                if (wasPlatform) platformBanUntil = now + 20 * 60; // спрыгнуть нельзя - минуту не пробуем
                startLocalPath(p, now, "прыгать некуда");
                return false;
            }
            note(name + " спрыгивает у " + escapeDrop.land.getBlockX() + "," + escapeDrop.land.getBlockY() + ","
                + escapeDrop.land.getBlockZ() + " (урон " + escapeDrop.dmg + ", парашютов/зелий " + fallSaverCount(p)
                + ", с " + escapeDrop.stand.getBlockX() + "," + escapeDrop.stand.getBlockY() + "," + escapeDrop.stand.getBlockZ()
                + (escapeDrop.tower ? ", блок под себя" : "") + ")");
        }
        Drop dr = escapeDrop;
        // Внизу: опустились до места посадки (а выход через стенку на ту же высоту - дошли до него).
        boolean down = escapeBelow || escapePlatform
            ? l.getY() <= dr.land.getY() + 1.2 && (l.getY() < escapeStartY - 1.5 || l.distanceSquared(dr.land) < 2.5)
            : l.getY() < escapeStartY - 2.5 || dr.land.getY() > escapeStartY - 1.5 && l.distanceSquared(dr.land) < 2.5;
        if ((BotNms.onGround(p) || p.isInWater()) && down) { finishEscape(); return false; } // внизу
        boolean top = l.getY() > escapeStartY - 1.5;
        // До края далеко - идём к нему.
        if (top && BotNms.onGround(p) && l.distanceSquared(dr.stand) > 2.5 * 2.5) {
            nav.setGoal(dr.stand, 1);
            Navigator.Move m = nav.tick(p, now);
            boolean path = m.active && nav.hasPath();
            double dx = path ? m.dx : dr.stand.getX() - l.getX(), dz = path ? m.dz : dr.stand.getZ() - l.getZ();
            motor.turn(p, Motor.yawTo(dx, dz), 0f, Math.min(skill.turnSpeed, 20f));
            motor.drive(p, dx, dz, 1.0, path ? m.strafeBias : 0f,
                (path && m.jump) || (BotNms.horizontalCollision(p) && BotNms.onGround(p)), false);
            return true;
        }
        // Стенка в полтора блока: встаём точно в клетку у неё и ставим блок под себя.
        if (dr.tower && l.getY() < dr.stand.getY() + 0.9) {
            double cx = dr.stand.getX() - l.getX(), cz = dr.stand.getZ() - l.getZ();
            if (Math.abs(cx) > 0.3 || Math.abs(cz) > 0.3) {
                if (!BotNms.onGround(p)) return true;
                motor.turn(p, Motor.yawTo(cx, cz), 0f, Math.min(skill.turnSpeed, 20f));
                motor.drive(p, cx, cz, 0.3, 0f, false, false);
                return true;
            }
            if (builder.tower(p, dr.stand.getBlockY() + 1, now)) return true;
            if (l.getY() < dr.stand.getY() + 0.9) { finishEscape(); return false; } // не встал блок
        }
        // У края, а лететь высоко: парашют в левую руку или зелье медленного падения.
        if (top && BotNms.onGround(p) && dr.dmg >= 2 && prepareFall(p, now)) return true;
        double dx = dr.land.getX() - l.getX(), dz = dr.land.getZ() - l.getZ();
        motor.turn(p, Motor.yawTo(dx, dz), 35f, Math.min(skill.turnSpeed, 20f));
        // бортик в блок по краю крыши перепрыгиваем
        motor.drive(p, dx, dz, 1.0, 0f, BotNms.horizontalCollision(p) && BotNms.onGround(p), false);
        BotNms.sneak(p, false);
        return true;
    }

    /** Куда спрыгнуть: точка приземления, где стоять перед шагом вниз, урон без парашюта. */
    private static final class Drop {
        final Location land, stand;
        final double dmg;
        /** Перед прыжком поставить блок под себя (перелезть стенку в полтора блока). */
        final boolean tower;
        Drop(Location land, Location stand, double dmg, boolean tower) { this.land = land; this.stand = stand; this.dmg = dmg; this.tower = tower; }
    }

    /**
     * Край, с которого спрыгнуть с наименьшим уроном: внизу вода, слизь, сено или
     * снег - лучше всего, иначе самая малая высота. Лаву, огонь, кактусы и бездну не берём.
     * Урон (с поправкой на парашют/зелье) не больше cap и не смертельный.
     *
     * Края ищем обходом площадки, по которой бот может пройти (в пределах maxR): так
     * находится и узкий проход в заборе по краю крыши или летающей постройки, в который
     * лучи от места бота не попадали.
     */
    private Drop bestDropEdge(Player p, Location toward, double cap, int maxR) {
        Location l = p.getLocation();
        World w = p.getWorld();
        int fy = (int) Math.floor(l.getY() + 0.01), sx0 = l.getBlockX(), sz0 = l.getBlockZ();
        // К цели внизу (союзник под крышей): прыгаем там, где опустимся до её высоты, поближе к ней.
        boolean below = toward != null;
        // Парашют или зелье: с любой высоты почти без урона (парашют раскрывается за секунду,
        // до этого пролетаем блоков пять).
        boolean saver = p.hasPotionEffect(PotionEffectType.SLOW_FALLING) || fallSaverCount(p) > 0;
        // Есть свои блоки: у стенки в полтора блока ставим блок под себя и перелезаем.
        boolean blocks = Builder.blockCount(p) > 0 && !builder.placeBlocked();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        // Обходим площадку на двух высотах: пол и верх подставок в блок (бочка, ящик), с которых
        // перелезают стенку в полтора блока.
        java.util.Map<Long, Integer> dist = new java.util.HashMap<Long, Integer>();
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<int[]>();
        q.add(new int[]{sx0, sz0, 0});
        dist.put(cellKey(sx0, fy, sz0), 0);
        Drop best = null;
        double bestCost = Double.MAX_VALUE;
        while (!q.isEmpty() && dist.size() < 1600) {
            int[] c = q.poll();
            int lv = c[2], y = fy + lv;
            int steps = dist.get(cellKey(c[0], y, c[1]));
            for (int[] d : dirs) {
                int nx = c[0] + d[0], nz = c[1] + d[1];
                if (Math.abs(nx - sx0) > maxR || Math.abs(nz - sz0) > maxR) continue;
                Block feet = w.getBlockAt(nx, y, nz), head = feet.getRelative(org.bukkit.block.BlockFace.UP);
                // Ходьба по площадке: на той же высоте, на подставку и с неё.
                if (walkable(w, nx, y, nz)) { visit(dist, q, nx, y, nz, lv, steps); }
                else if (lv == 0 && walkable(w, nx, y + 1, nz) && w.getBlockAt(c[0], y + 2, c[1]).isPassable()) visit(dist, q, nx, y + 1, nz, 1, steps);
                else if (lv == 1 && walkable(w, nx, y - 1, nz)) visit(dist, q, nx, y - 1, nz, 0, steps);
                // Края, с которых прыгать: {откуда падаем (высота ног), клетка за краем, добавка к падению, ставить блок}.
                double fromY = Double.NaN, extra = 0;
                int ex = nx, ez = nz;
                boolean tower = false;
                if (feet.isPassable() && head.isPassable()) {
                    if (!w.getBlockAt(nx, y - 1, nz).isPassable()) {
                        // Пол дальше. Стенка в полтора блока сразу под ногами соседа - мы на подставке.
                        if (lv == 1 && Motor.tallAt(w, nx, nz, y - 1) && head.getRelative(org.bukkit.block.BlockFace.UP).isPassable()) {
                            ex = nx + d[0]; ez = nz + d[1];
                            fromY = y + 1; extra = 0;
                        } else continue;
                    } else fromY = y; // обрыв
                } else if (head.isPassable() && head.getRelative(org.bukkit.block.BlockFace.UP).isPassable()) {
                    if (!Motor.tallAt(w, nx, nz, y)) {
                        // Бортик в блок: перепрыгиваем, край - за ним.
                        ex = nx + d[0]; ez = nz + d[1];
                        fromY = y; extra = 2;
                    } else if (lv == 0 && blocks && w.getBlockAt(c[0], y + 2, c[1]).isPassable()
                            && head.getRelative(org.bukkit.block.BlockFace.UP).getRelative(org.bukkit.block.BlockFace.UP).isPassable()) {
                        // Стенка или забор в полтора блока: блок под себя, шаг на стенку и вниз.
                        ex = nx + d[0]; ez = nz + d[1];
                        fromY = y + 2; extra = 0; tower = true;
                    } else continue;
                } else continue;
                int fyy = (int) Math.floor(fromY);
                Block e = w.getBlockAt(ex, fyy, ez);
                if (!e.isPassable() || !e.getRelative(org.bukkit.block.BlockFace.UP).isPassable()) continue;
                if (!w.getBlockAt(ex, fyy - 1, ez).isPassable()) {
                    // За стенкой сразу пол на той же высоте - это не край, а выход: туда и идём.
                    if (fromY > y) {
                        double cost = steps + (tower ? 6 : 2);
                        if (cost < bestCost) {
                            bestCost = cost;
                            best = new Drop(new Location(w, ex + 0.5, fyy, ez + 0.5), new Location(w, c[0] + 0.5, y, c[1] + 0.5), 0, tower);
                        }
                    }
                    continue;
                }
                // Край. Шагнув с него, пролетаем ещё блок-два вперёд: смотрим и туда.
                for (int k = 0; k <= 2; k++) {
                    int lx = ex + d[0] * k, lz = ez + d[1] * k;
                    if (k > 0 && !(w.getBlockAt(lx, fyy, lz).isPassable() && w.getBlockAt(lx, fyy + 1, lz).isPassable())) break;
                    // В полёте сносит на блок: безопасной должна быть и соседняя земля (вперёд и по бокам),
                    // иначе бот промахивался мимо узкого столбика и разбивался.
                    double raw = landingDamage(w, lx, fyy, lz);
                    for (int[] o : new int[][]{{d[0], d[1]}, {d[1], d[0]}, {-d[1], -d[0]}}) {
                        if (raw < 0) break;
                        double r2 = landingDamage(w, lx + o[0], fyy, lz + o[1]);
                        raw = r2 < 0 ? -1 : Math.max(raw, r2);
                    }
                    if (raw < 0) continue; // лава, огонь, бездна
                    raw += extra;
                    double dmg = saver ? Math.min(raw, 1) : raw;
                    if (dmg >= p.getHealth() || dmg > cap) continue;
                    double ly = landingY(w, lx, fyy, lz);
                    // Прыжок - это вниз: приземлиться на свой же блок или пол загона - не выход.
                    if (ly > fy - 1.5) continue;
                    // Скат крыши, ступенька: так вниз к цели не спуститься.
                    if (below && ly > toward.getY() + 2) continue;
                    double cost = dmg * 10 + steps + k + (tower ? 6 : 0);
                    if (below) cost += Math.hypot(lx + 0.5 - toward.getX(), lz + 0.5 - toward.getZ()) * 0.7;
                    if (cost < bestCost) {
                        bestCost = cost;
                        best = new Drop(new Location(w, lx + 0.5, ly, lz + 0.5), new Location(w, c[0] + 0.5, y, c[1] + 0.5), raw, tower);
                    }
                }
            }
        }
        return best;
    }

    private static void visit(java.util.Map<Long, Integer> dist, java.util.ArrayDeque<int[]> q, int x, int y, int z, int lv, int steps) {
        long k = cellKey(x, y, z);
        if (dist.containsKey(k)) return;
        dist.put(k, steps + 1);
        q.add(new int[]{x, z, lv});
    }

    // =====================================================================  парашют и зелье медленного падения

    private int chuteSwapSlot = -1, chuteLandAt = -1;
    private boolean fallPrepTried;

    /** Парашют ExecutableItems: из левой руки в воздухе раз в секунду даёт медленное падение. */
    static boolean isParachute(ItemStack it) {
        return "parashut".equalsIgnoreCase(Items.eiId(it));
    }

    static boolean isSlowFallPotion(ItemStack it) {
        if (it == null || it.getType() != Material.POTION || !(it.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta)) return false;
        org.bukkit.inventory.meta.PotionMeta pm = (org.bukkit.inventory.meta.PotionMeta) it.getItemMeta();
        if (pm.getBasePotionType() != null)
            for (org.bukkit.potion.PotionEffect e : pm.getBasePotionType().getPotionEffects()) if (e.getType().equals(PotionEffectType.SLOW_FALLING)) return true;
        for (org.bukkit.potion.PotionEffect e : pm.getCustomEffects()) if (e.getType().equals(PotionEffectType.SLOW_FALLING)) return true;
        return false;
    }

    static boolean isFallSaver(ItemStack it) {
        return isParachute(it) || isSlowFallPotion(it);
    }

    /** Сколько парашютов и зелий медленного падения при себе (с левой рукой). */
    private static int fallSaverCount(Player p) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) if (isFallSaver(inv.getItem(i))) n++;
        if (isFallSaver(inv.getItemInOffHand())) n++;
        return n;
    }

    private void putChuteOffhand(Player p, int slot) {
        PlayerInventory inv = p.getInventory();
        ItemStack off = inv.getItemInOffHand();
        inv.setItemInOffHand(inv.getItem(slot));
        inv.setItem(slot, off == null || off.getType().isAir() ? null : off);
        chuteSwapSlot = slot;
        chuteLandAt = -1;
    }

    /**
     * Перед прыжком с высоты: парашют в левую руку (раскроется сам), без него выпиваем
     * зелье медленного падения. true - ещё готовимся (пьём).
     */
    private boolean prepareFall(Player p, int now) {
        if (p.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return false;
        PlayerInventory inv = p.getInventory();
        if (isParachute(inv.getItemInOffHand())) return false;
        for (int i = 0; i < 36; i++) {
            if (!isParachute(inv.getItem(i))) continue;
            putChuteOffhand(p, i);
            note(name + " надел парашют");
            return false;
        }
        if (now < busyUntil) { motor.stop(p); return true; } // пьём
        if (fallPrepTried) return false;
        int slot = -1;
        for (int i = 0; i < 36 && slot < 0; i++) if (isSlowFallPotion(inv.getItem(i))) slot = i;
        if (slot < 0) return false;
        motor.stop(p);
        if (!hold(p, slot, now)) return true;
        BotNms.useItem(p, false);
        busyUntil = now + 40;
        fallPrepTried = true;
        note(name + " пьёт зелье медленного падения");
        return true;
    }

    /**
     * Падаем с высоты: парашют кладём в левую руку (раскроется сам). Приземлились - через
     * полторы секунды (раскрытый парашют предмет списывает сам) возвращаем прежнюю вещь.
     */
    private void fallGuard(Player p, int now) {
        PlayerInventory inv = p.getInventory();
        if (BotNms.onGround(p) || BotNms.inWater(p)) {
            if (chuteSwapSlot < 0) return;
            if (chuteLandAt < 0) chuteLandAt = now;
            if (now - chuteLandAt < 30) return;
            ItemStack off = inv.getItemInOffHand();
            ItemStack old = inv.getItem(chuteSwapSlot);
            boolean chuteLeft = isParachute(off);
            if ((chuteLeft || off == null || off.getType().isAir()) && old != null && !old.getType().isAir() && !isParachute(old)) {
                inv.setItemInOffHand(old);
                inv.setItem(chuteSwapSlot, chuteLeft ? off : null);
            }
            chuteSwapSlot = -1;
            chuteLandAt = -1;
            return;
        }
        chuteLandAt = -1;
        if (goal == Goal.DROP || p.isInsideVehicle() || p.isGliding() || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return;
        if (leap != null || leapDest != null || now < leapImmuneUntil) return; // после крюка/ранца падение не ранит
        if (p.getFallDistance() < 2.5f || p.getVelocity().getY() > -0.2) return;
        if (isParachute(inv.getItemInOffHand())) return;
        for (int i = 0; i < 36; i++) {
            if (!isParachute(inv.getItem(i))) continue;
            putChuteOffhand(p, i);
            note(name + " падает, раскрывает парашют");
            return;
        }
    }

    /** Урон от падения в столбец (x,z) с высоты ног fy: вода, слизь, снег - 0; -1 - туда нельзя. */
    private static double landingDamage(World w, int x, int fy, int z) {
        for (int dy = 1; dy <= 40; dy++) {
            Block b = w.getBlockAt(x, fy - dy, z);
            Material m = b.getType();
            if (m == Material.LAVA || m == Material.FIRE || m == Material.SOUL_FIRE) return -1;
            if (m == Material.WATER || m == Material.POWDER_SNOW) return 0;
            if (b.isPassable()) continue;
            if (m == Material.MAGMA_BLOCK || m == Material.CACTUS || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE
                    || m == Material.POINTED_DRIPSTONE || m == Material.SWEET_BERRY_BUSH) return -1;
            if (m == Material.SLIME_BLOCK) return 0;
            return Math.max(0, dy - 1 - 3) * (m == Material.HAY_BLOCK ? 0.2 : 1.0);
        }
        return -1; // бездна
    }

    /** Высота ног после падения в столбец (x,z) с высоты fy (в воде и снегу - их уровень). */
    private static double landingY(World w, int x, int fy, int z) {
        for (int dy = 1; dy <= 40; dy++) {
            Block b = w.getBlockAt(x, fy - dy, z);
            if (b.getType() == Material.WATER || b.getType() == Material.POWDER_SNOW) return fy - dy;
            if (!b.isPassable()) return fy - dy + 1;
        }
        return fy - 40;
    }

    // =====================================================================  бой

    private enum Weapon { GUN, AUTO, SHOTGUN, LAUNCHER, BOW, CROSSBOW, SPRAYER, THROW, CUSTOM, MELEE }

    private void fight(Player p, int now) {
        Contact t = target;
        if (t == null || t.entity == null || t.entity.isDead()) { target = null; motor.stop(p); return; }
        LivingEntity e = t.entity;
        Location loc = p.getLocation();
        boolean visible = t.visible;
        // Спрятавшегося врага ведём по месту, где видели в последний раз, а не сквозь стену.
        Location seen = visible || t.last == null || !t.last.getWorld().equals(loc.getWorld()) ? e.getLocation() : t.last;
        if (visible) {
            t.track(now, e.getLocation());
            if (skill.humanAim) {
                // Глаз отстаёт: бот целится туда, где цель, по его расчёту, должна быть.
                Location was = t.delayed(now, skill.trackDelay, 0.8, loc.getWorld());
                if (was != null) seen = was;
            }
        }
        double d = seen.distance(loc);

        if (kamikazeReady(p, now) && kamikazeStep(p, now, e, d)) return;

        // Предметы ExecutableItems (ракетница, снайперка, огнемёт...): если что-то
        // подходит к этой дистанции - применяем, этот тик на это и уходит.
        if (reactionLeft <= 0 && eikit.combat(p, now, e, d, visible, t.velocity)) { motor.stop(p); return; }

        // Есть свой танк, а враг не вплотную: ставим его и воюем из него.
        if (visible && tryTank(p, now, d, seen)) return;
        // Враг только что скрылся за укрытием рядом: закидываем туда гранату.
        if (!visible && throwAtHidden(p, now, t, d)) return;

        Weapon w = chooseWeapon(p, d, now);
        int slot = weaponSlot(p, w);
        // Драться нечем - кулаками, а не луком без стрел/пустым стволом в руке.
        if (w == Weapon.MELEE && slot < 0) slot = freeHandSlot(p);
        boolean ready = slot < 0 || hold(p, slot, now);
        // Только что сменил предмет в руке: первое действие им - не в тот же миг.
        if (now < handReadyAt) ready = false;

        // ---- позиционирование
        double lo, hi;
        switch (w) {
            case GUN: {
                boolean pistol = "pistol".equals(Items.warkitId(p.getInventory().getItemInMainHand()));
                lo = pistol ? 4 : 7; hi = pistol ? 22 : 42; break;
            }
            case AUTO: lo = 5; hi = 35; break;
            case SHOTGUN: lo = 0; hi = 6; break;
            case BOW: case CROSSBOW: lo = 9; hi = 32; break;
            case LAUNCHER: lo = 10; hi = 34; break;
            case SPRAYER: lo = 2; hi = 6; break;
            case THROW: lo = 8; hi = 22; break;
            case CUSTOM: lo = 4; hi = 18; break;
            default: lo = 0; hi = 2.6; break;
        }
        // Стрелок держит дальнюю дистанцию, штурмовик прижимается ближе.
        boolean ranged = w == Weapon.GUN || w == Weapon.AUTO || w == Weapon.BOW || w == Weapon.CROSSBOW;
        if (ranged && persona.type == Persona.Archetype.MARKSMAN) { lo = Math.max(lo, 14); hi = Math.max(hi, 40); }
        else if (ranged && persona.type == Persona.Archetype.ASSAULT) { lo = Math.min(lo, 4); hi = Math.min(hi, 24); }

        // У врага только рукопашное - не пятимся от него со стволом (так нас догоняли и
        // били руками), а стоим и стреляем, двигаясь боком.
        if (lo > 1.5 && (w == Weapon.GUN || w == Weapon.AUTO || w == Weapon.SPRAYER) && meleeOnly(e)) lo = 0;

        if (now >= strafeSwitchAt) {
            strafeDir = rnd.nextDouble() < 0.5 + skill.strafeBias ? 1 : -1;
            strafeSwitchAt = now + 8 + rnd.nextInt(w == Weapon.MELEE ? 14 : 24);
        }

        Location aim = aimPoint(p, seen, e.getHeight(), t, w);
        float yaw = yawTo(p, aim), pitch = pitchTo(p, aim);
        updateAimNoise(p, e, d, now);
        motor.track(p, yaw + aimOffYaw, pitch + aimOffPitch, skill.turnSpeed);

        boolean onGround = BotNms.onGround(p);
        double dx = seen.getX() - loc.getX(), dz = seen.getZ() - loc.getZ();
        boolean sneak = false;

        if (!visible) {
            boolean pathOn = nav.hasPath() && nav.reaches() && !nav.arrived(p, 2.5);
            if (!pathOn && seekLineOfSight(p, now, e)) return;
            if (target == null) return;
            // Враг за укрытием: идём туда, где видели, держа прицел на углу.
            nav.setGoal(t.last, 2);
            Navigator.Move m = nav.tick(p, now);
            BotNms.sneak(p, false);
            if (m.active) motor.drive(p, m.dx, m.dz, 1.0, m.strafeBias, m.jump, w == Weapon.MELEE);
            else motor.stop(p);
            return;
        }

        // Телекинетик в руке и враг рядом: присел-встал - тотем-страж (раз в 15 секунд).
        boolean totem = now < totemSneakUntil;
        if (!totem && visible && w == Weapon.MELEE && d < 12 && now >= nextTotem && ready
                && Items.isTelekinetic(p.getInventory().getItemInMainHand())) {
            nextTotem = now + 20 * 15;
            totemSneakUntil = now + 3;
            totem = true;
            note(name + " ставит тотем телекинетика на " + e.getName());
        }
        if (w == Weapon.MELEE) {
            double hl = Math.max(1e-6, Math.hypot(dx, dz));
            // Между нами забор или стена выше прыжка. Пока идём к проходу, режим держим, иначе на
            // границе дальности бот поворачивал назад и топтался на месте.
            boolean fence = d < 24 && Motor.tallBetween(p, e.getLocation());
            if (fence) fenceSeenUntil = now + 40;
            else if (now < fenceSeenUntil) fence = true;
            if (d > 4.5 || fence) {
                nav.setGoal(e.getLocation(), 1);
                Navigator.Move m = nav.tick(p, now);
                // Обрывок пути, упирающийся в забор, не берём: по нему бот топчется у забора.
                if (m.active && nav.hasPath() && (!fence || nav.reaches())) motor.drive(p, m.dx, m.dz, 1.0, m.strafeBias, m.jump, true);
                else if (fence) {
                    // Пути нет: идём вдоль забора к ближайшему проходу (сторону держим, чтобы не метаться).
                    if (now >= fenceSideUntil) {
                        fenceSide = fenceGapSide(p, dx, dz);
                        fenceSideUntil = now + 60;
                    }
                    // fenceSide: ось и знак вдоль забора (±1 по z, ±2 по x)
                    if (fenceSide != 0) {
                        double mx = Math.abs(fenceSide) == 2 ? Math.signum(fenceSide) : 0, mz = Math.abs(fenceSide) == 1 ? fenceSide : 0;
                        driveSafe(p, mx, mz, 1.0, 0f, false, true);
                    } else driveSafe(p, dx, dz, 0.15, strafeDir, false, false);
                }
                else driveSafe(p, dx, dz, 1.0, 0f, BotNms.horizontalCollision(p) && onGround, true);
            } else {
                // Вплотную: кружим, прыгаем для крита, отпускаем W после удара (сброс спринта).
                boolean wtap = now < wtapUntil;
                double fwd = wtap ? 0.0 : (d > 2.2 ? 1.0 : 0.45);
                float side = (float) (strafeDir * 0.55);
                boolean jumpCrit = false;
                // Прыжок не удался (потолок, вода) - не ждём падения вечно.
                if (critJumped && onGround && now - critJumpTick > 14) critJumped = false;
                if (onGround && p.getAttackCooldown() > 0.8f && d < 3.4 && d > 1.2 && rnd.nextInt(3) > 0) {
                    jumpCrit = true;
                    critJumped = true;
                    critJumpTick = now;
                }
                // Крит не проходит на спринте: в воздухе после прыжка спринт отпускаем.
                boolean critAir = critJumped && !onGround;
                driveSafe(p, dx, dz, fwd, side, jumpCrit || (BotNms.horizontalCollision(p) && onGround), !wtap && !critAir);
            }
            BotNms.sneak(p, totem);
            if (reactionLeft > 0) { reactionLeft--; return; }
            boolean falling = !onGround && p.getVelocity().getY() < -0.05;
            boolean critWindow = !critJumped || falling;
            boolean handOk = ready && (slot >= 0 || punchable(p.getInventory().getItemInMainHand()));
            if (handOk && d <= 3.05 && p.getAttackCooldown() >= 0.92f && critWindow && aimedAt(p, e, 18f)) {
                BotNms.attack(p, e);
                tauntOnAttack(p, e);
                critJumped = false;
                wtapUntil = now + 2;
                if (learnKey == null) maybeStartLearning(p, e, now, p.getInventory().getItemInMainHand());
            }
            return;
        }

        // ---- дальний бой: держим дистанцию и стрейфим
        double fwd;
        if (d > hi) fwd = 1.0;
        else if (d < lo) fwd = -1.0;
        else fwd = 0.0;
        float side = (float) strafeDir;
        // Издалека стреляем стоя и присев: разброс меньше. Под ответным огнём - снова стрейф.
        boolean longShot = d > 12 && w == Weapon.GUN && now - lastHurt > 30;
        if (longShot && fwd == 0) { side = 0f; sneak = true; }

        boolean bump = onGround && BotNms.horizontalCollision(p);
        if (d > hi + 6) {
            nav.setGoal(e.getLocation(), 6);
            Navigator.Move m = nav.tick(p, now);
            BotNms.sneak(p, false);
            if (m.active) motor.drive(p, m.dx, m.dz, 1.0, m.strafeBias, m.jump, true);
            else driveSafe(p, dx, dz, 1.0, 0f, bump, true);
        } else {
            BotNms.sneak(p, sneak);
            // Не отступаем спиной в обрыв или лаву.
            if (fwd < 0 && dangerBehind(p, dx, dz)) fwd = 0;
            if (fwd > 0) driveSafe(p, dx, dz, 1.0, side * 0.35f, bump, false);
            else if (fwd < 0) driveSafe(p, -dx, -dz, 1.0, side * 0.6f, bump, false);
            else driveSafe(p, 0, 0, 0, side, bump && side != 0, false);
        }

        if (!ready) return;
        if (reactionLeft > 0) { reactionLeft--; return; }
        fireWeapon(p, w, e, aim, d, now);
        tauntOnAttack(p, e);
    }

    /**
     * Стрельба на ходу (уход из зоны, бегство). Возвращает точку прицела, если в руках
     * дальнобойное: тогда бот смотрит на врага, а ноги несут его куда надо.
     */
    private Location combatWhileMoving(Player p, int now) {
        if (target == null || !target.visible || now < busyUntil) return null;
        double d = target.last.distance(p.getLocation());
        Weapon w = chooseWeapon(p, d, now);
        if (w == Weapon.MELEE && d > 3.2) return null;
        int slot = weaponSlot(p, w);
        if (w == Weapon.MELEE && slot < 0) slot = freeHandSlot(p);
        if (slot >= 0 && !hold(p, slot, now)) return null;
        Location aim = aimPoint(p, target.entity, target, w, d);
        Location face = w == Weapon.MELEE || w == Weapon.THROW ? null : aim;
        if (face != null) updateAimNoise(p, target.entity, d, now);
        if (!aimedAt(p, target.entity, 12f)) return face;
        if (reactionLeft > 0) { reactionLeft--; return face; }
        if (w == Weapon.MELEE) {
            if (p.getAttackCooldown() > 0.9f && (slot >= 0 || punchable(p.getInventory().getItemInMainHand())))
                BotNms.attack(p, target.entity);
        } else fireWeapon(p, w, target.entity, aim, d, now);
        return face;
    }

    private void fireWeapon(Player p, Weapon w, LivingEntity e, Location aim, double d, int now) {
        float tol = (float) (Math.toDegrees(Math.atan2(0.45, Math.max(1, d))) + 1.6);
        switch (w) {
            case GUN:
                if (aimedAtPoint(p, aim, tol * 1.4f)) fireGun(p, now, aim);
                break;
            case LAUNCHER:
                if (now >= nextFire && aimedAtPoint(p, aim, 6f)) {
                    if (Items.ammo(p.getInventory().getItemInMainHand()) == 0) { reload(p, now); break; }
                    BotNms.useItem(p, false);
                    nextFire = now + 24;
                }
                break;
            case SPRAYER:
                if (d < 7 && now >= nextFire && aimedAtPoint(p, aim, 12f)) {
                    BotNms.useItem(p, false);
                    nextFire = now + 4;
                }
                break;
            case THROW:
                if (now >= nextThrow && aimedAtPoint(p, aim, 5f)) {
                    note(name + " бросает " + Items.warkitId(p.getInventory().getItemInMainHand()) + " в " + e.getName() + " d=" + (int) d);
                    BotNms.useItem(p, false);
                    nextThrow = now + 20 * (5 + rnd.nextInt(5));
                    nextInventory = now + 10;
                }
                break;
            case AUTO:
                if (eiEmptyReload(p, Items.Custom.AUTO, now)) break;
                if (now >= nextFire && aimedAtPoint(p, aim, tol * 1.4f)) {
                    BotNms.useItem(p, false);
                    nextFire = now + 3;
                    eiFired(p, e, now, 25);
                }
                break;
            case SHOTGUN:
                if (eiEmptyReload(p, Items.Custom.SHOTGUN, now)) break;
                if (d <= 12 && now >= nextFire && aimedAtPoint(p, aim, 8f)) {
                    BotNms.useItem(p, false);
                    nextFire = now + 22; // кулдаун выстрела 1 сек
                    eiFired(p, e, now, 7);
                }
                break;
            case BOW: {
                if (!hasArrows(p)) { if (bowDrawStart >= 0) BotNms.releaseUseItem(p); bowDrawStart = -1; break; }
                if (bowDrawStart < 0) { BotNms.useItem(p, false); bowDrawStart = now; break; }
                int drawn = now - bowDrawStart;
                if (drawn >= 20 && aimedAtPoint(p, aim, tol)) {
                    BotNms.releaseUseItem(p);
                    bowDrawStart = -1;
                } else if (drawn > 70) { BotNms.releaseUseItem(p); bowDrawStart = -1; }
                break;
            }
            case CROSSBOW: {
                ItemStack cb = p.getInventory().getItemInMainHand();
                boolean charged = cb.getItemMeta() instanceof CrossbowMeta && ((CrossbowMeta) cb.getItemMeta()).hasChargedProjectiles();
                if (charged) {
                    if (aimedAtPoint(p, aim, tol)) { BotNms.useItem(p, false); crossbowLoadStart = -1; }
                } else if (!hasArrows(p)) {
                    crossbowLoadStart = -1;
                } else if (crossbowLoadStart < 0) {
                    BotNms.useItem(p, false); crossbowLoadStart = now;
                } else if (now - crossbowLoadStart >= 27) {
                    BotNms.releaseUseItem(p); crossbowLoadStart = -1;
                }
                break;
            }
            case CUSTOM:
                if (now >= nextCustom && aimedAtPoint(p, aim, 6f)) {
                    ItemStack it = p.getInventory().getItemInMainHand();
                    BotNms.useItem(p, false);
                    nextCustom = now + 20 * (3 + rnd.nextInt(3));
                    maybeStartLearning(p, e, now, it);
                }
                break;
            default:
                break;
        }
    }

    private int nextTankTry;

    /** Поставить свой танк в бою. Враг ближе 10 блоков - не до того, дальше 60 - незачем. */
    private boolean tryTank(Player p, int now, double d, Location enemyAt) {
        if (now < nextTankTry || d < 10 || d > 60 || p.isInsideVehicle() || now < busyUntil) return false;
        nextTankTry = now + 20 * 3;
        if (!rides.hasCombatVehicle(p)) { nextTankTry = now + 20 * 15; return false; }
        // Хочется не всем и не всегда: технику любит техник, остальные - когда тяжело.
        boolean want = persona.vehicles > 0.5 || p.getHealth() < 14 || rnd.nextDouble() < 0.35
            || (target != null && enemyPower(target.entity) > myPower(p) * 0.9);
        if (!want) return false;
        if (!rides.deployTank(p, now, enemyAt)) return false;
        motor.stop(p);
        note(name + " ставит танк против " + (target == null ? "?" : target.entity.getName()));
        return true;
    }

    private int hiddenThrowCheck = -1000;
    private boolean hiddenThrowGo;

    /**
     * Враг скрылся за укрытием полсекунды-шесть секунд назад и он рядом: игрок закидывает это
     * место гранатой. Решает один раз на эпизод, стоит, доводит прицел и бросает.
     */
    private boolean throwAtHidden(Player p, int now, Contact t, double d) {
        if (t.last == null || now < nextThrow || now < busyUntil || d < 6 || d > 22) return false;
        if (t.lostTick < 0 || now - t.lostTick < 10 || now - t.lostTick > 120) return false;
        if (!t.last.getWorld().equals(p.getWorld())) return false;
        int slot = find(p, Items.Kind.THROW_DAMAGE);
        if (slot < 0) return false;
        if (now >= hiddenThrowCheck) {
            hiddenThrowCheck = now + 60;
            hiddenThrowGo = rnd.nextDouble() < 0.35 + persona.gadgets * 0.5;
        }
        if (!hiddenThrowGo) return false;
        motor.stop(p);
        if (!hold(p, slot, now) || now < handReadyAt) return true;
        Location aim = aimPoint(p, t.last, 1.0, null, Weapon.THROW);
        if (!motor.aim(p, yawTo(p, aim), pitchTo(p, aim), 4f)) return true;
        note(name + " закидывает гранату за укрытие к " + t.entity.getName() + " d=" + (int) d);
        BotNms.useItem(p, false);
        nextThrow = now + 20 * (5 + rnd.nextInt(5));
        nextInventory = now + 10;
        hiddenThrowGo = false;
        return true;
    }

    /** «Зажатая ПКМ»: клиент шлёт использование раз в 4 тика, так же делает бот. */
    private void fireGun(Player p, int now, Location aim) {
        if (now < reloadUntil || now < nextFire) return;
        ItemStack gun = p.getInventory().getItemInMainHand();
        int ammo = Items.ammo(gun);
        if (ammo == 0) { reload(p, now); return; }
        BotNms.useItem(p, false);
        nextFire = now + 4;
    }

    private void reload(Player p, int now) {
        if (now < reloadUntil) return;
        BotNms.clickAir(p); // ЛКМ в воздух = перезарядка у MilitaryCraft
        reloadUntil = now + 20;
    }

    private Weapon chooseWeapon(Player p, double d, int now) {
        PlayerInventory inv = p.getInventory();
        double melee = 0;
        int meleeSlot = bestMelee(p);
        if (meleeSlot >= 0) melee = Items.meleeDps(inv.getItem(meleeSlot));
        boolean gun = findGun(p) >= 0;
        boolean launcher = find(p, Items.Kind.LAUNCHER) >= 0;
        boolean sprayer = find(p, Items.Kind.SPRAYER) >= 0;
        boolean bow = find(p, Items.Kind.BOW) >= 0 && hasArrows(p);
        boolean xbow = find(p, Items.Kind.CROSSBOW) >= 0 && (hasArrows(p) || crossbowCharged(p));
        int auto = findEi(p, Items.Custom.AUTO, now, true);
        int shotgun = findEi(p, Items.Custom.SHOTGUN, now, true);
        // Заряженных нет, а стрелять больше не из чего: берём пустой ствол с запасом и заряжаем.
        if (auto < 0 && shotgun < 0 && !gun) {
            auto = findEi(p, Items.Custom.AUTO, now, false);
            shotgun = findEi(p, Items.Custom.SHOTGUN, now, false);
        }
        if (shotgun >= 0 && d <= 8) return Weapon.SHOTGUN;
        if (auto >= 0 && d <= 3.2 && melee >= 9 && !gun) return Weapon.MELEE;
        boolean thr = find(p, Items.Kind.THROW_DAMAGE) >= 0 && now >= nextThrow;
        int custom = findLearnedCustom(p, now);

        // Винтовка/пистолет MilitaryCraft сильнее любого меча даже в упор. Ближний бой -
        // только без огнестрела или пока он перезаряжается, а враг уже рядом.
        if (gun) {
            int g = findGun(p);
            boolean empty = g >= 0 && Items.ammo(inv.getItem(g)) == 0;
            if (empty && d <= 3.5 && melee >= 6) return Weapon.MELEE;
        } else if (d <= 3.2 && melee >= 5) {
            return Weapon.MELEE;
        }
        // Гранату - в стоящего (или иногда в бегущего); тактик кидает охотнее.
        if (thr && d > 8 && d < 22 && (target != null && target.velocity.lengthSquared() < 0.01 || rnd.nextDouble() < 0.04 + persona.gadgets * 0.2))
            return Weapon.THROW;
        if (custom >= 0 && d > 3 && d < 20) return Weapon.CUSTOM;
        if (sprayer && d < 6.5) return Weapon.SPRAYER;
        if (auto >= 0 && d <= 45) return Weapon.AUTO;
        if (gun) return Weapon.GUN;
        if (shotgun >= 0 && d <= 14) return Weapon.SHOTGUN;
        if (launcher && d > 9) return Weapon.LAUNCHER;
        if ((bow || xbow) && d > 6) return bow ? Weapon.BOW : Weapon.CROSSBOW;
        if (launcher) return Weapon.LAUNCHER;
        return Weapon.MELEE;
    }

    private int weaponSlot(Player p, Weapon w) {
        switch (w) {
            case GUN: return findGun(p);
            case AUTO: return findEi(p, Items.Custom.AUTO, mgr.now());
            case SHOTGUN: return findEi(p, Items.Custom.SHOTGUN, mgr.now());
            case LAUNCHER: return find(p, Items.Kind.LAUNCHER);
            case SPRAYER: return find(p, Items.Kind.SPRAYER);
            case BOW: return find(p, Items.Kind.BOW);
            case CROSSBOW: return find(p, Items.Kind.CROSSBOW);
            case THROW: return find(p, Items.Kind.THROW_DAMAGE);
            case CUSTOM: return findLearnedCustom(p, 0);
            default: return bestMelee(p);
        }
    }

    /** Заранее достаёт оружие под дистанцию, пока идёт к врагу. */
    private void prepareWeapon(Player p, double d) {
        int now = mgr.now();
        if (now < busyUntil) return;
        int slot = weaponSlot(p, chooseWeapon(p, d, now));
        if (slot >= 0) hold(p, slot, now);
    }

    /** Точка прицеливания: корпус цели с упреждением и поправкой на баллистику. */
    private Location aimPoint(Player p, LivingEntity e, Contact c, Weapon w, double d) {
        return aimPoint(p, e.getLocation(), e.getHeight(), c, w);
    }

    /** То же по точке, где стоит (или где в последний раз был виден) враг ростом h. */
    private Location aimPoint(Player p, Location feet, double h, Contact c, Weapon w) {
        Location eye = p.getEyeLocation();
        Location base = feet.clone().add(0, h * 0.62, 0);
        Vector vel = c != null ? c.velocity : new Vector();
        double speed, gravity;
        switch (w) {
            case BOW: speed = 3.0; gravity = 0.05; break;
            case CROSSBOW: speed = 3.15; gravity = 0.05; break;
            case THROW: speed = 1.3; gravity = 0.03; break;    // frag/molotov throw-speed 1.3
            case LAUNCHER: speed = 0.9; gravity = 0.05; break; // grenade-launcher speed 0.9
            case CUSTOM: speed = 1.5; gravity = 0.03; break;
            default: return base; // пули MilitaryCraft мгновенные, кулак тоже
        }
        Location target = base.clone();
        for (int i = 0; i < 2; i++) {
            double dist = target.distance(eye);
            double t = dist / speed;
            target = base.clone().add(vel.clone().setY(0).multiply(t));
        }
        // Поправка на падение снаряда: целимся выше.
        double flat = Math.hypot(target.getX() - eye.getX(), target.getZ() - eye.getZ());
        double dy = target.getY() - eye.getY();
        double v2 = speed * speed;
        double disc = v2 * v2 - gravity * (gravity * flat * flat + 2 * dy * v2);
        if (disc > 0 && flat > 0.5) {
            double ang = Math.atan((v2 - Math.sqrt(disc)) / (gravity * flat));
            double drag = 1.0 + flat * 0.006; // сопротивление воздуха
            double aimY = eye.getY() + Math.tan(ang) * flat * drag;
            target.setY(aimY);
        } else if (flat > 0.5) {
            target.add(0, flat * 0.4, 0);
        }
        return target;
    }

    /** Человеческая ошибка прицела: плавный «дрейф», больше на дальних и бегающих целях. */
    private void updateAimNoise(Player p, LivingEntity e, double d, int now) {
        // Взвинченный бот мажет сильнее, первые выстрелы по новой цели - тоже.
        double spread = skill.aimError * affect.aim() * motor.warm();
        Contact c = target;
        double lateral = c != null ? Math.hypot(c.velocity.getX(), c.velocity.getZ()) : 0;
        spread *= 1.0 + lateral * 3.0;
        if (Math.hypot(p.getVelocity().getX(), p.getVelocity().getZ()) > 0.1) spread *= 1.3;
        if (p.isSneaking()) spread *= 0.7;
        if (now - lastHurt < 10) spread *= 1.6; // дёрнулся от попадания
        if ((now + thinkPhase) % 6 == 0) {
            aimDriftYaw = (float) (rnd.nextGaussian() * spread);
            aimDriftPitch = (float) (rnd.nextGaussian() * spread * 0.6);
        }
        aimOffYaw += (aimDriftYaw - aimOffYaw) * 0.25f;
        aimOffPitch += (aimDriftPitch - aimOffPitch) * 0.25f;
    }

    private boolean aimedAt(Player p, LivingEntity e, float tolDeg) {
        return aimedAtPoint(p, e.getLocation().add(0, e.getHeight() * 0.6, 0), tolDeg);
    }

    private boolean aimedAtPoint(Player p, Location point, float tolDeg) {
        float dy = Math.abs(Motor.wrap(yawTo(p, point) - motor.yaw()));
        float dp = Math.abs(pitchTo(p, point) - motor.pitch());
        return dy <= tolDeg + Math.abs(aimOffYaw) && dp <= tolDeg + Math.abs(aimOffPitch);
    }

    private void setTarget(Contact c) {
        if (target == c) return;
        target = c;
        Player me = player();

        // Время реакции: человек не стреляет в ту же миллисекунду, как увидел.
        reactionLeft = react(timing.reaction());
        motor.acquire();
        nav.clear();
    }

    private void tryPearlEscape(Player p, Location from, int now) {
        if (now < nextPearl || p.getHealth() > 7 || from.distance(p.getLocation()) > 8) return;
        int pearl = find(p, Items.Kind.PEARL);
        if (pearl < 0 || !hold(p, pearl, now)) return;
        Vector away = p.getLocation().toVector().subtract(from.toVector());
        float yaw = Motor.yawTo(away.getX(), away.getZ());
        if (!motor.aim(p, yaw, -25f, 6f)) return;
        BotNms.useItem(p, false);
        nextPearl = now + 20 * 15;
    }

    // =====================================================================  Жириновский

    private void warden(Player p, int now) {
        Warden w = hooks.warden();
        if (w == null) { goal = Goal.ROAM; return; }
        Location loc = p.getLocation();
        double d = w.getLocation().distance(loc);
        Location aim = w.getLocation().add(0, 1.6, 0);
        // Подобрался (он быстрее): столб в 4 блока под себя, сверху стреляем.
        int pillar = wardenPillar(p, w, d, now);
        if (pillar == 1) return;
        motor.turn(p, yawTo(p, aim), pitchTo(p, aim), skill.turnSpeed);
        double dx = w.getLocation().getX() - loc.getX(), dz = w.getLocation().getZ() - loc.getZ();
        BotNms.sneak(p, false);
        if (pillar == 2) {
            motor.stop(p); // на столбе: не слезаем
        } else if (d < 26) {
            // Звуковой удар бьёт на ~15-20 блоков: отходим, продолжая стрелять.
            motor.drive(p, -dx, -dz, 1.0, strafeDir * 0.5f, BotNms.onGround(p) && BotNms.horizontalCollision(p), false);
        } else if (d > 38 || !p.hasLineOfSight(w)) {
            nav.setGoal(w.getLocation(), 28);
            Navigator.Move m = nav.tick(p, now);
            if (nav.getFailures() >= 3) { wardenRetryAt = now + 20 * 60; nav.clear(); goal = Goal.ROAM; return; }
            if (m.active) motor.drive(p, m.dx, m.dz, 1.0, m.strafeBias, m.jump, true);
            else motor.stop(p);
        } else {
            motor.drive(p, dx, dz, 0.0, strafeDir * 0.6f, false, false);
            if (now >= strafeSwitchAt) { strafeDir = -strafeDir; strafeSwitchAt = now + 20 + rnd.nextInt(20); }
        }
        int gun = findGun(p);
        if (gun >= 0 && hold(p, gun, now) && p.hasLineOfSight(w) && d < 45) fireGun(p, now, aim);
    }

    private int wardenPillarTo = Integer.MIN_VALUE, wardenPillarBest, wardenPillarTick, wardenWallAt;
    private int wardenPillarX, wardenPillarZ, wardenRingTries;
    private Location wardenPillarAt;

    /**
     * Жириновский рядом, а он быстрее бота: строим столб в 4 блока под себя (стенки ему не
     * сломать, наверх не достать) и стоим там, пока он рядом.
     * 0 - столба нет, 1 - строим (тик занят), 2 - стоим на столбе.
     */
    private int wardenPillar(Player p, Warden w, double d, int now) {
        Location l = p.getLocation();
        int fy = l.getBlockY();
        if (wardenPillarTo != Integer.MIN_VALUE) {
            if (fy > wardenPillarBest) { wardenPillarBest = fy; wardenPillarTick = now; }
            if (now - wardenPillarTick < 60 && builder.tower(p, wardenPillarTo, now)) return 1;
            if (!BotNms.onGround(p) && now - wardenPillarTick < 60) { motor.stop(p); return 1; } // ещё в прыжке
            boolean done = fy >= wardenPillarTo;
            wardenPillarTo = Integer.MIN_VALUE;
            if (!done) return 0;
            wardenPillarAt = new Location(l.getWorld(), wardenPillarX + 0.5, fy, wardenPillarZ + 0.5);
            wardenRingTries = 0;
            note(name + " на столбе от Жириновского");
        }
        if (wardenPillarAt != null) {
            double hx = wardenPillarAt.getX() - l.getX(), hz = wardenPillarAt.getZ() - l.getZ();
            if (l.getWorld().equals(wardenPillarAt.getWorld()) && hx * hx + hz * hz < 1.0 && d < 32
                    && l.getY() > wardenPillarAt.getY() - 0.6 && l.getY() < wardenPillarAt.getY() + 6) {
                // Подбросило звуковым ударом: в воздухе не рулим, падаем обратно в своё кольцо.
                if (!BotNms.onGround(p)) { motor.stop(p); return 2; }
                // Стоим на краю столба - отходим к середине крадучись (с края присед не даёт упасть).
                double cx = wardenPillarAt.getX() - l.getX(), cz = wardenPillarAt.getZ() - l.getZ();
                if (cx * cx + cz * cz > 0.04 && BotNms.onGround(p)) {
                    BotNms.sneak(p, true);
                    motor.drive(p, cx, cz, 0.6, 0f, false, false);
                    return 1;
                }
                BotNms.sneak(p, false);
                // Обкладываемся блоками: сначала ярус вровень с верхом столба (опора), потом на уровне ног.
                // Его звуковой удар отбрасывает, а так со столба не слететь. Стрелять поверх стенки можно.
                if (Builder.blockCount(p) > 0 && !builder.placeBlocked() && BotNms.onGround(p) && wardenRingTries < 24) {
                    int top = wardenPillarAt.getBlockY();
                    World wd = p.getWorld();
                    for (int dy = -1; dy <= 0; dy++) {
                        for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                            Block b = wd.getBlockAt(wardenPillarX + o[0], top + dy, wardenPillarZ + o[1]);
                            if (!b.getType().isAir() && !b.isReplaceable()) continue;
                            motor.stop(p);
                            if (now >= wardenWallAt) { wardenWallAt = now + 3; wardenRingTries++; builder.place(p, b); }
                            return 1;
                        }
                    }
                }
                return 2;
            }
            if (d >= 32 || !l.getWorld().equals(wardenPillarAt.getWorld()) || l.getY() < wardenPillarAt.getY() - 1.5) wardenPillarAt = null;
        }
        if (d < 10 && BotNms.onGround(p) && Builder.blockCount(p) >= 4 && !builder.placeBlocked()
                && w.getLocation().getY() < l.getY() + 2) {
            World wd = p.getWorld();
            for (int k = 2; k <= 6; k++) if (!wd.getBlockAt(l.getBlockX(), fy + k, l.getBlockZ()).isPassable()) return 0; // потолок
            wardenPillarTo = fy + 4;
            wardenPillarX = l.getBlockX();
            wardenPillarZ = l.getBlockZ();
            wardenPillarBest = fy;
            wardenPillarTick = now;
            wardenPillarAt = null;
            note(name + " строит столб от Жириновского");
            return builder.tower(p, wardenPillarTo, now) ? 1 : 0;
        }
        return 0;
    }

    // =====================================================================  добыча блоков

    private int gatherNeed, gatherUntil = -1, gatherBanUntil, gatherCheckAt;
    private Block gatherBlock;
    private final java.util.Set<Long> gatherSkip = new java.util.HashSet<Long>();
    private final java.util.Set<UUID> gatherIgnore = new java.util.HashSet<UUID>();
    private UUID gatherDropId;
    private int gatherDropSince;

    /** Цели, ради которых можно отвлечься и добыть блоки (не бой и не бегство от зоны). */
    private boolean gatherGoal() {
        switch (goal) {
            case LOOT: case PICKUP: case ROAM: case HUNT: case FOLLOW: case SHARE: case AIRDROP: case CENTER: case PLAN:
                return true;
            default:
                return false;
        }
    }

    /**
     * Цель на скале или уступе выше нас, пути к ней нет, а блоков на столб не хватает:
     * заранее считаем высоту и добываем на пару блоков больше.
     */
    private void maybeGather(Player p, int now) {
        if (now < gatherBanUntil || !gatherGoal() || !skill.navPlace || builder.placeBlocked() || !BotNms.onGround(p)) return;
        Location g = nav.getGoal();
        Location l = p.getLocation();
        if (g == null || !g.getWorld().equals(l.getWorld())) return;
        if (nav.hasPath() && nav.reaches() || nav.getFailures() < 1) return;
        double dy = g.getY() - l.getY();
        if (dy < 2.5 || dy > 20 || Math.hypot(g.getX() - l.getX(), g.getZ() - l.getZ()) > 24) return;
        int need = (int) Math.ceil(dy) + 2;
        if (Builder.blockCount(p) - 2 >= need) return; // хватает (2 в запасе путь не тратит)
        startGather(p, need + 2, now, "забраться на " + (int) Math.ceil(dy) + " блоков");
    }

    private void startGather(Player p, int need, int now, String why) {
        gatherNeed = need;
        gatherUntil = now + 20 * 40;
        gatherBlock = null;
        nav.clear();
        note(name + " добываю блоки (нужно " + need + ", есть " + Builder.blockCount(p) + "): " + why);
    }

    private void stopGather(int now, int ban) {
        gatherUntil = -1;
        gatherBanUntil = now + ban;
        gatherBlock = null;
        nav.clear();
    }

    /** Шаг добычи: подобрать выпавшее, подойти к блоку, сломать. true - тик занят. */
    private boolean gatherStep(Player p, int now) {
        int have = Builder.blockCount(p);
        if (have >= gatherNeed) {
            note(name + " набрал блоков: " + have);
            stopGather(now, 20 * 15);
            return false;
        }
        Location l = p.getLocation();
        World w = p.getWorld();
        // Выпавшее рядом - подбираем.
        Item drop = null;
        double dd = Double.MAX_VALUE;
        for (Item it : w.getNearbyEntitiesByType(Item.class, l, 6)) {
            if (!Items.isBuildBlock(it.getItemStack()) || Math.abs(it.getLocation().getY() - l.getY()) > 2.5) continue;
            if (gatherIgnore.contains(it.getUniqueId())) continue;
            double d = it.getLocation().distanceSquared(l);
            if (d < dd) { dd = d; drop = it; }
        }
        if (drop != null && !builder.isMining()) {
            if (!drop.getUniqueId().equals(gatherDropId)) { gatherDropId = drop.getUniqueId(); gatherDropSince = now; }
            if (now - gatherDropSince > 20 * 4) { gatherIgnore.add(drop.getUniqueId()); gatherDropId = null; return true; } // не подобрать
            Location il = drop.getLocation();
            // Лежит в ямке под нами: идём в середину ямки (сверху с края его не подобрать).
            if (il.getY() < l.getY() - 0.4) il = new Location(w, il.getBlockX() + 0.5, il.getY(), il.getBlockZ() + 0.5);
            double dx = il.getX() - l.getX(), dz = il.getZ() - l.getZ();
            motor.turn(p, Motor.yawTo(dx, dz), 30f, Math.min(skill.turnSpeed, 25f));
            driveSafe(p, dx, dz, 1.0, 0f, BotNms.onGround(p) && BotNms.horizontalCollision(p), false);
            return true;
        }
        if (builder.isMining()) {
            motor.stop(p);
            if (builder.resumeMining(p, now)) return true;
        }
        if (gatherBlock != null && (gatherBlock.getType().isAir() || !Builder.canDig(p, gatherBlock) || builder.isDenied(gatherBlock))) gatherBlock = null;
        if (gatherBlock == null) gatherBlock = findGatherBlock(p);
        if (gatherBlock == null) {
            note(name + " добывать нечего (есть " + have + " блоков)");
            stopGather(now, 20 * 30);
            return false;
        }
        Location c = gatherBlock.getLocation().add(0.5, 0.5, 0.5);
        if (p.getEyeLocation().distance(c) > 4.2) {
            double dx = c.getX() - l.getX(), dz = c.getZ() - l.getZ();
            motor.turn(p, Motor.yawTo(dx, dz), 20f, Math.min(skill.turnSpeed, 25f));
            driveSafe(p, dx, dz, 1.0, 0f, BotNms.onGround(p) && BotNms.horizontalCollision(p), false);
            return true;
        }
        motor.stop(p);
        if (builder.mine(p, gatherBlock, now)) return true;
        if (!gatherBlock.getType().isAir()) gatherSkip.add(cellKey(gatherBlock.getX(), gatherBlock.getY(), gatherBlock.getZ()));
        gatherBlock = null;
        return true;
    }

    /**
     * Ближайший блок, который быстро ломается и даёт строительный блок: земля, с киркой - камень,
     * с топором - брёвна. Пол под собой и блоки под песком/гравием не трогаем.
     */
    private Block findGatherBlock(Player p) {
        Location l = p.getLocation();
        World w = p.getWorld();
        int bx = l.getBlockX(), bz = l.getBlockZ(), fy = (int) Math.floor(l.getY() + 0.01);
        org.bukkit.block.BlockFace[] faces = {org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.NORTH,
            org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST};
        Block best = null;
        double bestScore = Double.MAX_VALUE;
        for (int x = bx - 6; x <= bx + 6; x++) {
            for (int z = bz - 6; z <= bz + 6; z++) {
                for (int y = fy - 1; y <= fy + 3; y++) {
                    if (x == bx && z == bz && y < fy) continue;
                    double score = (x - bx) * (x - bx) + (z - bz) * (z - bz) + (y - fy - 0.5) * (y - fy - 0.5) + (y < fy ? 6 : 0);
                    if (score >= bestScore) continue;
                    Block b = w.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (m.isAir() || !m.isSolid() || b.isLiquid()) continue;
                    if (gatherSkip.contains(cellKey(x, y, z))) continue;
                    Block above = b.getRelative(org.bukkit.block.BlockFace.UP);
                    if (above.getType().hasGravity() || above.isLiquid()) continue;
                    boolean open = false;
                    for (org.bukkit.block.BlockFace f : faces) if (b.getRelative(f).isPassable()) { open = true; break; }
                    if (!open || !Builder.canDig(p, b) || builder.isDenied(b)) continue;
                    int slot = Builder.bestTool(p, b);
                    ItemStack tool = slot >= 0 ? p.getInventory().getItem(slot) : null;
                    boolean gives = false;
                    for (ItemStack d : b.getDrops(tool == null ? new ItemStack(Material.AIR) : tool, p)) {
                        if (Items.isBuildBlock(d)) { gives = true; break; }
                    }
                    if (!gives) continue;
                    bestScore = score;
                    best = b;
                }
            }
        }
        return best;
    }

    // =====================================================================  лут

    /** Где стоять, чтобы открыть выбранный сундук (null - такого места нет), и для какого сундука. */
    private Location chestStandAt;
    private long chestStandKey = Long.MIN_VALUE;
    private int chestStandTick = -1000, chestNoSee = -1;
    private long chestNoSeeKey = Long.MIN_VALUE;

    /**
     * Клетка рядом с сундуком, откуда игрок его видит и достаёт рукой: ноги и голова свободны,
     * под ногами опора, луч от глаз упирается в сам сундук. Ближайшая к боту. Раньше бот шёл
     * «к сундуку» и открывал его, стоя за стеной.
     */
    private Location chestStand(Player p) {
        if (chest == null) return null;
        int now = mgr.now();
        long k = key(chest);
        if (k == chestStandKey && now - chestStandTick < 100) return chestStandAt;
        chestStandKey = k;
        chestStandTick = now;
        chestStandAt = null;
        World w = p.getWorld();
        Block b = w.getBlockAt(chest[0], chest[1], chest[2]);
        Location me = p.getLocation();
        double bd = Double.MAX_VALUE;
        for (int dy = -2; dy <= 1; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    int x = chest[0] + dx, y = chest[1] + dy, z = chest[2] + dz;
                    Block feet = w.getBlockAt(x, y, z), head = feet.getRelative(BlockFace.UP), floor = feet.getRelative(BlockFace.DOWN);
                    if (!feet.isPassable() || !head.isPassable() || floor.isPassable()) continue;
                    if (feet.isLiquid() || floor.getType() == Material.LAVA || floor.getType() == Material.MAGMA_BLOCK) continue;
                    Location eye = new Location(w, x + 0.5, y + 1.62, z + 0.5);
                    double ex = chest[0] + 0.5 - eye.getX(), ey = chest[1] + 0.5 - eye.getY(), ez = chest[2] + 0.5 - eye.getZ();
                    if (ex * ex + ey * ey + ez * ez > 4.2 * 4.2) continue;
                    if (!seesBlock(eye, b)) continue;
                    double d = (x + 0.5 - me.getX()) * (x + 0.5 - me.getX()) + (z + 0.5 - me.getZ()) * (z + 0.5 - me.getZ())
                        + (y - me.getY()) * (y - me.getY()) * 4;
                    if (d < bd) { bd = d; chestStandAt = new Location(w, x + 0.5, y, z + 0.5); }
                }
            }
        }
        return chestStandAt;
    }

    /** Видно ли блок отсюда: хоть один луч от глаз к нему упирается в него самого (или во вторую половину сундука). */
    private static boolean seesBlock(Location eye, Block b) {
        World w = b.getWorld();
        double[][] pts = {{0.5, 0.5, 0.5}, {0.5, 0.85, 0.5}, {0.15, 0.6, 0.5}, {0.85, 0.6, 0.5}, {0.5, 0.6, 0.15}, {0.5, 0.6, 0.85}};
        for (double[] o : pts) {
            Vector to = new Vector(b.getX() + o[0] - eye.getX(), b.getY() + o[1] - eye.getY(), b.getZ() + o[2] - eye.getZ());
            double len = to.length();
            if (len < 1e-3) return true;
            org.bukkit.util.RayTraceResult r = w.rayTraceBlocks(eye, to.multiply(1.0 / len), len + 0.3, org.bukkit.FluidCollisionMode.NEVER, true);
            Block h = r == null ? null : r.getHitBlock();
            if (h == null) continue;
            if (h.equals(b)) return true;
            if (h.getType() == b.getType() && Math.abs(h.getX() - b.getX()) + Math.abs(h.getY() - b.getY()) + Math.abs(h.getZ() - b.getZ()) == 1) return true;
        }
        return false;
    }

    /** Первый блок между глазами и блоком b (стена перед замурованным сундуком) или null. */
    private static Block blockingBlock(Location eye, Block b) {
        Vector to = new Vector(b.getX() + 0.5 - eye.getX(), b.getY() + 0.5 - eye.getY(), b.getZ() + 0.5 - eye.getZ());
        double len = to.length();
        if (len < 1e-3) return null;
        org.bukkit.util.RayTraceResult r = b.getWorld().rayTraceBlocks(eye, to.multiply(1.0 / len), len, org.bukkit.FluidCollisionMode.NEVER, true);
        Block h = r == null ? null : r.getHitBlock();
        return h == null || h.equals(b) ? null : h;
    }

    /** Ванильный сундук не открывается, если над ним твёрдый блок (крышке некуда подняться). */
    private static boolean lidBlocked(Block b) {
        if (b.getType() != Material.CHEST && b.getType() != Material.TRAPPED_CHEST) return false;
        return b.getRelative(BlockFace.UP).getType().isOccluding();
    }

    /** Сундук открыть не вышло: больше к нему не идём. */
    private void giveUpChest(String why) {
        if (chest == null) return;
        note(name + " бросает сундук: " + why);
        searched.add(key(chest));
        chest = null;
        chestOpenAt = -1;
        chestNoSee = -1;
        nav.clear();
    }

    /**
     * Шаг лутания выбранного сундука. Открыть - только как игрок: дотянуться рукой (4.3 блока
     * от глаз), видеть сам сундук (не сквозь стену), крышка не придавлена. Замурованный сундук
     * бот прокапывает, придавленный - освобождает. true - бот занят этим (копает или наводится).
     */
    private boolean lootChest(Player p, int now) {
        World w = p.getWorld();
        Location c = new Location(w, chest[0] + 0.5, chest[1] + 0.5, chest[2] + 0.5);
        if (!w.isChunkLoaded(chest[0] >> 4, chest[2] >> 4)) return false;
        Block b = w.getBlockAt(chest[0], chest[1], chest[2]);
        BlockState st = b.getState();
        if (!(st instanceof Container)) { searched.add(key(chest)); chest = null; chestOpenAt = -1; return false; }
        Location eye = p.getEyeLocation();
        double dist = eye.distance(c);
        if (chestOpenAt < 0) {
            if (!Builder.inZone(b)) { searched.add(key(chest)); chest = null; return false; } // за зоной не открыть
            if (key(chest) != chestNoSeeKey) { chestNoSeeKey = key(chest); chestNoSee = -1; }
            if (dist > 4.3) { chestNoSee = -1; return false; }
            if (chestNoSee < 0) chestNoSee = now;
            if (!seesBlock(eye, b)) {
                Location stand = chestStand(p);
                // Ещё идём на место, откуда сундук видно.
                if (stand != null && stand.distanceSquared(p.getLocation()) > 0.6 * 0.6 && now - chestNoSee < 20 * 8) return false;
                // Такого места нет (сундук в стене, в подвале): прокапываемся, как игрок.
                Block wall = blockingBlock(eye, b);
                if (wall != null && Builder.inZone(wall) && builder.mine(p, wall, now)) { motor.stop(p); return true; }
                if (wall != null && wall.isPassable()) { chestNoSee = now; return true; } // проломил - копаем дальше
                if (now - chestNoSee > 20 * 6) giveUpChest("не видно, не прокопать");
                return false;
            }
            if (lidBlocked(b)) {
                Block up = b.getRelative(BlockFace.UP);
                if (Builder.inZone(up) && builder.mine(p, up, now)) { motor.stop(p); return true; }
                if (lidBlocked(b) && now - chestNoSee > 20 * 6) giveUpChest("крышку придавило");
                return lidBlocked(b);
            }
            // Перед ПКМ игрок смотрит на сундук.
            motor.stop(p);
            if (!motor.aim(p, yawTo(p, c), pitchTo(p, c), 10f)) return true;
            if (st instanceof Lidded) ((Lidded) st).open();
            chestOpenAt = now;
            chestNoSee = -1;
            return true;
        }
        Inventory inv = ((Container) st).getInventory();
        int items = 0;
        for (ItemStack it : inv.getContents()) if (it != null && !it.getType().isAir()) items++;
        // Человек тратит время, чтобы рассмотреть и переложить вещи.
        // В бою хватаем самое ценное почти сразу.
        if (skill.lootByOne) {
            if (now - chestOpenAt < (grabbing ? 5 : 9) || now < chestNextTake) return false;
            // Вещи уходят из сундука по одной, самая ценная первой.
            if (takeOne(p, inv)) { chestNextTake = now + 3 + rnd.nextInt(grabbing ? 2 : 5); return false; }
        } else {
            if (now - chestOpenAt < (grabbing ? 5 : 8 + Math.min(items, 10) * 3)) return false;
            takeFrom(p, inv);
        }
        if (st instanceof Lidded) ((Lidded) st).close();
        // Тактик оставляет у пустого сундука растяжку или мину: следующий за лутом нарвётся.
        try { maybeTrapChest(p, now); } catch (Throwable t) { mgr.warn("trap " + name, t); }
        searched.add(key(chest));
        chest = null;
        chestOpenAt = -1;
        nav.clear();
        nextInventory = now; // разобрать новое сразу
        return false;
    }

    /** Забирает из контейнера одну вещь - самую ценную из нужных. false - брать больше нечего. */
    private boolean takeOne(Player p, Inventory from) {
        PlayerInventory inv = p.getInventory();
        int best = -1;
        double bv = 0.5;
        for (int i = 0; i < from.getSize(); i++) {
            ItemStack it = from.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            double v = value(it);
            if (v <= bv || (v < 6 && inv.contains(it.getType())) || !wantsMore(p, it)) continue;
            best = i;
            bv = v;
        }
        if (best < 0) return false;
        ItemStack it = from.getItem(best);
        if (inv.firstEmpty() < 0) dropWorst(p, bv);
        Map<Integer, ItemStack> left = inv.addItem(it.clone());
        if (!left.isEmpty()) {
            ItemStack rest = left.values().iterator().next();
            if (rest.getAmount() >= it.getAmount()) return false; // не влезло
            from.setItem(best, rest);
        } else from.setItem(best, null);
        if (bv >= 45) talk(p, BotChatter.Topic.GOOD_LOOT, 0.25, 20 * 90, null);
        return true;
    }

    /** Забирает из контейнера всё полезное, самое ценное первым. */
    private void takeFrom(Player p, Inventory from) {
        List<Integer> slots = new ArrayList<Integer>();
        for (int i = 0; i < from.getSize(); i++) {
            ItemStack it = from.getItem(i);
            if (it != null && !it.getType().isAir()) slots.add(i);
        }
        final Inventory src = from;
        slots.sort((a, b) -> Double.compare(value(src.getItem(b)), value(src.getItem(a))));
        PlayerInventory inv = p.getInventory();
        for (int i : slots) {
            ItemStack it = from.getItem(i);
            double v = value(it);
            if (v <= 0.5) continue;
            // Дешёвое, что уже есть (второе ведро молока и т.п.), не тащим.
            if (v < 6 && inv.contains(it.getType())) continue;
            // Худшую броню и худшее оружие, чем надето/есть, тоже.
            Items.Kind k = Items.kind(it, hooks);
            if (!wantsMore(p, it)) continue;
            if (inv.firstEmpty() < 0) dropWorst(p, v);
            if (v >= 45) talk(p, BotChatter.Topic.GOOD_LOOT, 0.25, 20 * 90, null);
            Map<Integer, ItemStack> left = inv.addItem(it.clone());
            if (left.isEmpty()) from.setItem(i, null);
            else from.setItem(i, left.values().iterator().next());
        }
    }

    private void dropWorst(Player p, double incoming) {
        PlayerInventory inv = p.getInventory();
        int worst = -1;
        double wv = incoming;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            if (hooks.isNukeButton(it) || it.getType() == Material.FILLED_MAP) continue;
            double v = value(it);
            if (v < wv) { wv = v; worst = i; }
        }
        if (worst >= 0) {
            p.getWorld().dropItemNaturally(p.getLocation(), inv.getItem(worst));
            inv.setItem(worst, null);
        }
    }

    /** Бочки (и сундуки не из списка карты) рядом, где лежит что-то ценное. */
    private final List<int[]> extraContainers = new ArrayList<int[]>();
    private int nextContainerScan;

    private void scanContainers(Player p, int now) {
        if (now < nextContainerScan) return;
        nextContainerScan = now + 200;
        extraContainers.clear();
        World w = p.getWorld();
        int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
        Set<Long> known = new HashSet<Long>();
        int[][] list = hooks.chests();
        if (list != null) for (int[] c : list) known.add(key(c));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (!w.isChunkLoaded(cx + dx, cz + dz)) continue;
                for (BlockState st : w.getChunkAt(cx + dx, cz + dz).getTileEntities(false)) {
                    if (!(st instanceof org.bukkit.block.Barrel) && !(st instanceof org.bukkit.block.Chest)) continue;
                    int[] c = {st.getX(), st.getY(), st.getZ()};
                    if (known.contains(key(c)) || searched.contains(key(c))) continue;
                    double v = 0;
                    for (ItemStack it : ((Container) st).getInventory().getContents()) if (it != null) v += pickupWorth(p, it);
                    if (v >= 12) extraContainers.add(c); // только если там правда что-то полезное
                }
            }
        }
    }

    private int[] findChest(Player p, double radius) {
        try { scanContainers(p, mgr.now()); } catch (Throwable ignored) {}
        int[][] base = hooks.chests();
        int[][] all;
        if (extraContainers.isEmpty()) all = base;
        else {
            int n = base == null ? 0 : base.length;
            all = new int[n + extraContainers.size()][];
            for (int i = 0; i < n; i++) all[i] = base[i];
            for (int i = 0; i < extraContainers.size(); i++) all[n + i] = extraContainers.get(i);
        }
        if (all == null || all.length == 0) return null;
        World w = p.getWorld();
        Location me = p.getLocation();
        int[] best = null;
        double bestScore = Double.MAX_VALUE;
        double margin = borderShrinking ? 30 : 8;
        for (int[] c : all) {
            if (searched.contains(key(c))) continue;
            double dx = c[0] + 0.5 - me.getX(), dy = c[1] - me.getY(), dz = c[2] + 0.5 - me.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 > radius * radius || Math.abs(dy) > 40) continue;
            if (!w.isChunkLoaded(c[0] >> 4, c[2] >> 4)) continue;
            if (!insideBorder(w, c[0], c[2], margin)) continue;
            if (nearWarden(c[0], c[2], 26)) continue;
            Material t = w.getBlockAt(c[0], c[1], c[2]).getType();
            if (t != Material.CHEST && t != Material.TRAPPED_CHEST && t != Material.BARREL) { searched.add(key(c)); continue; }
            double score = Math.sqrt(d2) + Math.abs(dy) * 2.5;
            // Не лезем к сундуку, возле которого виден враг.
            for (Contact ct : contacts.values()) {
                if (ct.visible && ct.last.distanceSquared(new Location(w, c[0], c[1], c[2])) < 15 * 15) score += 60;
            }
            if (score < bestScore) { bestScore = score; best = c; }
        }
        return best;
    }

    private boolean containerHasLoot(Location l) {
        World w = l.getWorld();
        if (!w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return true;
        BlockState st = w.getBlockAt(l).getState(false);
        if (!(st instanceof Container)) return false;
        for (ItemStack it : ((Container) st).getInventory().getContents()) if (it != null && !it.getType().isAir()) return true;
        return false;
    }

    private Item findPickup(Player p) {
        Item best = null;
        double bestScore = 0;
        for (Entity e : p.getNearbyEntities(16, 6, 16)) {
            if (!(e instanceof Item)) continue;
            Item it = (Item) e;
            if (it.getPickupDelay() > 40 || badPickups.contains(it.getUniqueId())) continue;
            UUID thrower = it.getThrower();
            if (thrower != null && thrower.equals(id)) continue; // сам выкинул
            double v = pickupWorth(p, it.getItemStack());
            // Подарок от тиммейта берём всегда.
            if (thrower != null && hooks.sameTeam(id, thrower)) v = Math.max(v, 25);
            else if (v < 7 || !wantsMore(p, it.getItemStack())) continue;
            double score = v / (1 + e.getLocation().distance(p.getLocation()));
            if (score > bestScore) { bestScore = score; best = it; }
        }
        return best;
    }

    /** Оружие/броня/хил на земле в паре шагов или сундук рядом, пока враг бьёт нас руками. */
    private Goal grabInFight(Player p, Contact t, int now) {
        Location me = p.getLocation();
        Location enemy = t.last;
        Item best = null;
        double bestScore = 0;
        for (Entity e : p.getNearbyEntities(10, 4, 10)) {
            if (!(e instanceof Item)) continue;
            Item it = (Item) e;
            if (it.getPickupDelay() > 30 || badPickups.contains(it.getUniqueId())) continue;
            ItemStack st = it.getItemStack();
            Items.Kind k = Items.kind(st, hooks);
            boolean useful;
            switch (k) {
                case MELEE: case GUN: case LAUNCHER: case SPRAYER: case TRIDENT: case THROW_DAMAGE:
                case ARMOR: case HEAL: case TOTEM: case SHIELD:
                    useful = true; break;
                case GADGET: // мины и турели нужны тем, кто их ставит
                    useful = persona.gadgets > 0.4; break;
                case BOW: case CROSSBOW:
                    useful = hasArrows(p); break;
                case CUSTOM: {
                    Items.Custom c = Items.customType(st);
                    useful = c == Items.Custom.AUTO || c == Items.Custom.SHOTGUN;
                    break;
                }
                default:
                    useful = isArrow(st.getType()) && (find(p, Items.Kind.BOW) >= 0 || find(p, Items.Kind.CROSSBOW) >= 0);
            }
            if (!useful || !wantsMore(p, st)) continue;
            double v = pickupWorth(p, st);
            if (v < 6) continue;
            Location il = it.getLocation();
            double d = il.distance(me);
            // Не лезем за предметом, который лежит у врага под ногами.
            if (enemy.getWorld().equals(il.getWorld()) && il.distance(enemy) < 2 && d > 3) continue;
            double score = v / (1 + d);
            if (score > bestScore) { bestScore = score; best = it; }
        }
        if (best != null) {
            if (pickup == null || !pickup.getUniqueId().equals(best.getUniqueId())) pickupSince = now;
            pickup = best;
            return Goal.PICKUP;
        }
        if (chest != null && goal == Goal.LOOT && !searched.contains(key(chest))
                && me.distance(new Location(p.getWorld(), chest[0] + 0.5, chest[1], chest[2] + 0.5)) < 12) return Goal.LOOT;
        int[] c = findChest(p, 9);
        if (c != null && Math.abs(c[1] - me.getBlockY()) <= 3
                && containerHasLoot(new Location(p.getWorld(), c[0], c[1], c[2]))) {
            chest = c;
            return Goal.LOOT;
        }
        return null;
    }

    /** Чем сбить аирдроп (шар) с этого расстояния, или null. */
    private Weapon airpigWeapon(Player p, double d) {
        if (findGun(p) >= 0 && d < 60) return Weapon.GUN;
        if (findEi(p, Items.Custom.AUTO, mgr.now()) >= 0 && d < 45) return Weapon.AUTO;
        if (hasArrows(p) && d < 50) {
            if (find(p, Items.Kind.BOW) >= 0) return Weapon.BOW;
            if (find(p, Items.Kind.CROSSBOW) >= 0) return Weapon.CROSSBOW;
        }
        if (findEi(p, Items.Custom.SHOTGUN, mgr.now()) >= 0 && d < 10) return Weapon.SHOTGUN;
        return null;
    }

    /**
     * Страховка от зависаний: 15 секунд идём «куда-то», а сдвинулись меньше чем на 2 блока
     * (застряли в углу, тупик, который навигатор не видит) - бросаем эту цель.
     */
    private void watchdog(Player p, int now) {
        Location l = p.getLocation();
        if (stillPos == null || !stillPos.getWorld().equals(l.getWorld()) || stillPos.distanceSquared(l) > 4) {
            stillPos = l.clone();
            stillTick = now;
            return;
        }
        if (now - stillTick < 300) return;
        stillTick = now;
        if (rides.active() || chestOpenAt >= 0 || now < busyUntil || p.isInsideVehicle()) return;
        if (now - lastTunnel < 40 && digCount < 25) return; // прокапываемся - это не зависание
        switch (goal) {
            case LOOT: case PICKUP: case ROAM: case FOLLOW: case HUNT: case AIRDROP: case SHARE: case ZONE: case PLAN:
                if (goal == Goal.PLAN && planHoldSince >= 0) break; // стоим на своей позиции - так и задумано
                if (goal == Goal.FOLLOW && helpAlly != null && helpAlly.getWorld().equals(l.getWorld())
                        && helpAlly.getLocation().distance(l) < 7) break; // стоим рядом с союзником - так и надо
                note(name + " завис (" + goal + "), бросаю цель");
                towerTo = Integer.MIN_VALUE;
                minedBlock = null;
                abandonGoal(now);
                if (goal == Goal.FOLLOW) helpAlly = null;
                if (goal == Goal.SHARE) shareTo = null;
                break;
            default:
        }
    }

    /**
     * Бегство, но с головой: в упор, в тупике или когда за 5 секунд так и не оторвались,
     * бежать бесполезно - разворачиваемся и деремся всем, что есть (8-10 секунд).
     */
    private Goal evade(int now, double tDist, boolean visible) {
        Goal fight = visible ? Goal.FIGHT : Goal.CHASE;
        if (now < fightLockUntil) return fight;
        if (tDist < 3.5 && now - lastHurt < 40) { fightLockUntil = now + 160; return fight; }
        if (goal != Goal.EVADE) {
            evadeStart = now;
            evadeDist0 = tDist;
        } else if (now - evadeStart > 100 && tDist < Math.max(evadeDist0 + 6, 14)) {
            fightLockUntil = now + 200; // не оторвались - даём бой
            return fight;
        }
        return Goal.EVADE;
    }

    /** В какую сторону вдоль стены свободнее: 1 влево, -1 вправо (0 - некуда). */
    private int freeSide(Player p, double dx, double dz) {
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) return 1;
        dx /= len; dz /= len;
        Location l = p.getLocation();
        World w = p.getWorld();
        int best = 0, bestFree = -1;
        for (int side : new int[]{1, -1}) {
            int free = 0;
            for (int k = 1; k <= 4; k++) {
                int x = (int) Math.floor(l.getX() - dz * side * k), z = (int) Math.floor(l.getZ() + dx * side * k);
                if (w.getBlockAt(x, l.getBlockY(), z).isPassable() && w.getBlockAt(x, l.getBlockY() + 1, z).isPassable()) free++;
                else break;
            }
            if (free > bestFree) { bestFree = free; best = side; }
        }
        return bestFree <= 0 ? (rnd.nextBoolean() ? 1 : -1) : best;
    }

    /** Глубоко под землёй: неба не видно и над головой больше 8 блоков земли. */
    private boolean underground(Player p) {
        Location eye = p.getEyeLocation();
        Block b = eye.getBlock();
        if (b.getLightFromSky() > 0) return false;
        return p.getWorld().getHighestBlockYAt(eye) > eye.getY() + 8;
    }

    /** Копать лестницу наверх туда, где поверхность ниже всего (меньше копать). */
    private void chooseCaveDir(Player p, int now) {
        Location l = p.getLocation();
        World w = p.getWorld();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int best = 0, bestY = Integer.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            int y = w.getHighestBlockYAt(l.getBlockX() + dirs[i][0] * 16, l.getBlockZ() + dirs[i][1] * 16);
            if (y < bestY) { bestY = y; best = i; }
        }
        caveDx = dirs[best][0];
        caveDz = dirs[best][1];
        caveDirSince = now;
    }

    /**
     * Выбраться наверх: есть блоки - столб под себя с пробиванием потолка над головой,
     * нет - лестница ступеньками в сторону, где поверхность ниже.
     */
    private void digStairs(Player p, int now) {
        Location l = p.getLocation();
        int surface = p.getWorld().getHighestBlockYAt(l) + 1;
        if (caveTowerOk && Builder.blockCount(p) >= 3 && !builder.placeBlocked()) {
            int fy = l.getBlockY();
            if (fy > caveBestY) { caveBestY = fy; caveBestTick = now; }
            if (now - caveBestTick < 100 && builder.tower(p, surface, now)) { caveWhy = "tower " + builder.towerWhy; return; }
            caveWhy = "towerstop " + builder.towerWhy;
            caveTowerOk = false; // столб не идёт - копаем лестницу
            caveTowerRetry = now + 20 * 30;
        }
        if (!caveTowerOk && now >= caveTowerRetry && BotNms.onGround(p)) {
            caveTowerOk = true;
            caveBestY = l.getBlockY();
            caveBestTick = now;
        }
        if (now - caveDirSince > 20 * 40) chooseCaveDir(p, now); // долго не выходит - пробуем иначе
        // Лестницу рубим в стену: впереди на уровне ног должен быть твёрдый блок.
        World w = p.getWorld();
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        if (!w.getBlockAt(x + caveDx, y, z + caveDz).getType().isSolid()) {
            int[][] dirs = {{caveDx, caveDz}, {-caveDz, caveDx}, {caveDz, -caveDx}, {-caveDx, -caveDz}};
            for (int[] d : dirs) {
                Block wall = w.getBlockAt(x + d[0], y, z + d[1]);
                if (wall.getType().isSolid() && Builder.canDig(p, wall)) { caveDx = d[0]; caveDz = d[1]; caveDirSince = now; break; }
            }
        }
        if (!w.getBlockAt(x + caveDx, y, z + caveDz).getType().isSolid()) {
            // Стены рядом нет - идём ровно к ближайшей, в большие обрывы не шагаем.
            tunnel(p, now, caveDx, caveDz, 0); // ровный проход: мешающее над водой/уступ ломаем
            return;
        }
        tunnel(p, now, caveDx, caveDz, 1);
    }

    /**
     * Шаг прохода сквозь блоки в направлении (dx,dz): vert 1 - вверх ступенькой, 0 - прямо,
     * -1 - вниз. Ломаем мешающее (лаву не вскрываем), в обрыв не шагаем: ставим блок или
     * поворачиваем.
     */
    private void tunnel(Player p, int now, int dx, int dz, int vert) {
        lastTunnel = now;
        Location l = p.getLocation();
        World w = p.getWorld();
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        // Нет движения 2 секунды: сначала ломаем и ступеньку (идём ровно), потом другое направление.
        if (tunnelPos == null || !tunnelPos.getWorld().equals(l.getWorld()) || tunnelPos.distanceSquared(l) > 0.25) {
            tunnelPos = l.clone();
            tunnelPosTick = now;
            tunnelStuck = 0;
        } else if (builder.isMining()) {
            tunnelPosTick = now; // ломаем блок - это не застревание
        } else if (now - tunnelPosTick > 40 && minedBlock == null) {
            tunnelPosTick = now;
            if (++tunnelStuck >= 3) { rotateCaveDir(now); tunnelStuck = 0; }
        }
        if (tunnelStuck >= 1 && vert > 0 && !pitKeepUp) vert = 0;
        java.util.List<Block> need = new java.util.ArrayList<Block>();
        if (vert > 0) need.add(w.getBlockAt(x, y + 2, z));
        if (vert >= 0) {
            need.add(w.getBlockAt(x + dx, y + vert, z + dz));
            need.add(w.getBlockAt(x + dx, y + 1 + vert, z + dz));
            // Прыжок на ступеньку поднимает голову на 3 блока: без этого упираемся в потолок.
            if (vert > 0) need.add(w.getBlockAt(x + dx, y + 3, z + dz));
        } else {
            need.add(w.getBlockAt(x + dx, y + 1, z + dz));
            need.add(w.getBlockAt(x + dx, y, z + dz));
            need.add(w.getBlockAt(x + dx, y - 1, z + dz));
        }
        for (Block b : need) {
            for (org.bukkit.block.BlockFace f : new org.bukkit.block.BlockFace[]{org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.NORTH,
                    org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
                if (b.getRelative(f).getType() == Material.LAVA) { caveWhy = "lava"; rotateCaveDir(now); motor.stop(p); return; }
            }
            if (!b.getType().isSolid()) continue;
            if (!Builder.canDig(p, b)) { caveWhy = "nodig " + b.getType(); rotateCaveDir(now); motor.stop(p); return; }
            motor.stop(p);
            caveWhy = "mine " + b.getType();
            if (!builder.mine(p, b, now) && b.getType().isSolid()) { caveWhy = "minefail " + b.getType(); rotateCaveDir(now); }
            return;
        }
        Block step = w.getBlockAt(x + dx, y + (vert > 0 ? 0 : -1), z + dz);
        if (vert > 0 && !step.getType().isSolid()) {
            // Ступеньки нет: под ней твёрдо - ставим ступеньку (или идём прямо), под ней пусто - обрыв.
            Block under = step.getRelative(org.bukkit.block.BlockFace.DOWN);
            if (!under.getType().isSolid()) {
                if (Builder.blockCount(p) > 0 && builder.place(p, under)) { caveWhy = "bridge"; return; }
                caveWhy = "drop"; rotateCaveDir(now); motor.stop(p); return;
            }
            if (Builder.blockCount(p) > 0 && builder.place(p, step)) { caveWhy = "step"; return; }
        }
        if (vert <= 0 && !safeStep(p, dx, dz)) { caveWhy = "cliff"; rotateCaveDir(now); motor.stop(p); return; }
        caveWhy = "walk";
        boolean up = vert > 0 && w.getBlockAt(x + dx, y, z + dz).getType().isSolid();
        motor.turn(p, Motor.yawTo(dx, dz), up ? -20f : 0f, skill.turnSpeed);
        motor.drive(p, dx, dz, 1.0, 0f, (up && BotNms.onGround(p)) || BotNms.inWater(p), false);
    }

    private void rotateCaveDir(int now) {
        int t = caveDx;
        caveDx = -caveDz;
        caveDz = t;
        caveDirSince = now;
    }

    /** Стрельба с пассажирского сиденья: только целимся и стреляем, без движения и приседа. */
    private void fightFromSeat(Player p, int now) {
        Contact t = target;
        if (t == null || t.entity == null || t.entity.isDead() || !t.visible) return;
        LivingEntity e = t.entity;
        double d = e.getLocation().distance(p.getLocation());
        Weapon w = chooseWeapon(p, d, now);
        if (w == Weapon.MELEE || w == Weapon.THROW) return;
        int slot = weaponSlot(p, w);
        if (slot < 0 || !hold(p, slot, now)) return;
        Location aim = aimPoint(p, e, t, w, d);
        updateAimNoise(p, e, d, now);
        motor.turn(p, yawTo(p, aim) + aimOffYaw, pitchTo(p, aim) + aimOffPitch, skill.turnSpeed);
        if (reactionLeft > 0) { if (aimedAt(p, e, 12f)) reactionLeft--; return; }
        fireWeapon(p, w, e, aim, d, now);
        tauntOnAttack(p, e);
    }

    private int fenceSide, fenceSideUntil, nextTotem, totemSneakUntil;

    /**
     * Куда идти вдоль забора к ближайшему проходу (в 24 блоках). Забор считаем идущим
     * поперёк главной оси направления на цель. Ответ: ±1 - вдоль z, ±2 - вдоль x, 0 - прохода нет.
     */
    private int fenceGapSide(Player p, double dx, double dz) {
        Location l = p.getLocation();
        World w = p.getWorld();
        int fy = (int) Math.floor(l.getY() + 0.01);
        boolean alongZ = Math.abs(dx) >= Math.abs(dz);       // забор поперёк x - идём вдоль z
        int ox = alongZ ? (int) Math.signum(dx) : 0, oz = alongZ ? 0 : (int) Math.signum(dz);
        // Клетка на нашей стороне забора (прижавшись к столбу, бот стоит одной ногой в его клетке).
        int bx = (int) Math.floor(l.getX() - ox * 0.35), bz = (int) Math.floor(l.getZ() - oz * 0.35);
        int best = 0, bestK = Integer.MAX_VALUE;
        for (int side : new int[]{1, -1}) {
            for (int k = 1; k <= 24 && k < bestK; k++) {
                int hx = bx + (alongZ ? 0 : side * k), hz = bz + (alongZ ? side * k : 0);
                Block here = w.getBlockAt(hx, fy, hz);
                if (!here.isPassable() || !here.getRelative(org.bukkit.block.BlockFace.UP).isPassable()) break; // упёрлись вдоль
                if (here.getRelative(org.bukkit.block.BlockFace.DOWN).isPassable()) break; // обрыв
                if (!Motor.tallAt(w, hx + ox, hz + oz, l.getY()) && w.getBlockAt(hx + ox, fy, hz + oz).isPassable()) {
                    best = alongZ ? side : side * 2;
                    bestK = k;
                    break;
                }
            }
        }
        return best;
    }

    /** У врага в руках нет ничего дальнобойного. */
    private boolean meleeOnly(LivingEntity e) {
        if (!(e instanceof Player)) return true;
        ItemStack it = ((Player) e).getInventory().getItemInMainHand();
        Items.Kind k = Items.kind(it, hooks);
        switch (k) {
            case GUN: case BOW: case CROSSBOW: case LAUNCHER: case SPRAYER: case THROW_DAMAGE: case TRIDENT: case CUSTOM:
                return false;
            default:
                return true;
        }
    }

    private int nextRocketHit;

    /** Замеченные ракеты: когда бот успеет на них среагировать (тик). */
    private final Map<UUID, Integer> rocketSeen = new HashMap<UUID, Integer>();

    /**
     * Сбить летящую в нас ракету (пуля шалкера «Ракетницы», «Пэтриот» MilitaryCraft) ударом. Как человек: замечает только ту, что перед глазами
     * (или уже совсем рядом), реагирует не сразу, голову доводит, а не щёлкает. Раньше бот
     * мгновенно разворачивался к ракете за спиной.
     */
    private boolean shootDownRockets(Player p, int now) {
        if (now < nextRocketHit) return false;
        Location eye = p.getEyeLocation();
        Vector look = eye.getDirection();
        Entity best = null;
        double bd = 3.2 * 3.2;
        boolean any = false;
        for (Entity e : p.getNearbyEntities(10, 10, 10)) {
            if (!isEnemyRocket(e)) continue;
            any = true;
            Vector to = e.getLocation().toVector().subtract(eye.toVector());
            double d2 = to.lengthSquared();
            boolean noticed = d2 < 2.5 * 2.5 || (d2 > 1e-6 && look.angle(to) < Math.toRadians(70));
            Integer due = rocketSeen.get(e.getUniqueId());
            if (due == null) {
                if (noticed) rocketSeen.put(e.getUniqueId(), now + 3 + rnd.nextInt(5));
                continue;
            }
            if (now < due || d2 >= bd) continue;
            bd = d2;
            best = e;
        }
        if (!any) { if (!rocketSeen.isEmpty()) rocketSeen.clear(); return false; }
        if (rocketSeen.size() > 32) rocketSeen.clear();
        if (best == null) return false;
        Location t = best.getLocation();
        if (!motor.aim(p, Motor.yawTo(t.getX() - eye.getX(), t.getZ() - eye.getZ()),
                Motor.pitchTo(t.getX() - eye.getX(), t.getY() - eye.getY(), t.getZ() - eye.getZ()), 15f)) return true;
        BotNms.attack(p, best);
        nextRocketHit = now + 4;
        talk(p, BotChatter.Topic.ROCKET_BLOCKED, 0.35, 20 * 60, null);
        note(name + " сбивает ракету " + best.getType());
        return true;
    }

    private boolean isEnemyRocket(Entity e) {
        String owner = null;
        boolean rocket = false;
        if (e instanceof org.bukkit.entity.ShulkerBullet) {
            rocket = true;
            for (String tag : e.getScoreboardTags()) {
                if (tag.startsWith("rocket_")) {
                    String rest = tag.substring(7);
                    int us = rest.lastIndexOf('_');
                    owner = us > 0 ? rest.substring(0, us) : rest;
                }
            }
        } else {
            org.bukkit.persistence.PersistentDataContainer pdc = e.getPersistentDataContainer();
            if (pdc.isEmpty()) return false;
            for (org.bukkit.NamespacedKey k : pdc.getKeys()) {
                // Ключ другого типа get(STRING) не читает, а бросает исключение - сначала has().
                if (!pdc.has(k, org.bukkit.persistence.PersistentDataType.STRING)) continue;
                if ("patriot_missile".equals(pdc.get(k, org.bukkit.persistence.PersistentDataType.STRING))) { rocket = true; break; }
            }
        }
        if (!rocket) return false;
        if (owner != null && (owner.equals(name) || isTeammateName(owner))) return false; // свои не сбиваем
        return true;
    }

    private Location holdPoint;
    private Player lootCommander;
    private int lootOrderUntil, nextLootDelivery;
    private boolean lowHpSaid, dropSaid, zoneSaid;
    private Goal lastGoal;
    private final Map<BotChatter.Topic, Integer> saidAt = new HashMap<BotChatter.Topic, Integer>();

    /**
     * Сказать что-нибудь по теме: T_* - своим в командный чат, остальное - всем в мире.
     * cooldown - не чаще, чем раз в столько тиков (на этого бота).
     */
    void talk(Player p, BotChatter.Topic t, double chance, int cooldown, String victim) {
        int now = mgr.now();
        // В перестрелке не печатают: только короткое своим.
        if (!t.name().startsWith("T_") && inFight()) return;
        Integer last = saidAt.get(t);
        if (last != null && now - last < cooldown) return;
        saidAt.put(t, now);
        if (t.name().startsWith("T_")) mgr.teamSay(p, t, chance);
        else mgr.chat(p, t, chance, name, victim);
    }
    private int seenOrder = -1;

    /**
     * Что делать по приказу. null - приказ этот тик не мешает обычным решениям (например,
     * в режиме «за мной» враг рядом - дерёмся по-своему).
     */
    private Goal orderGoal(Player p, int now, SquadRadio.Order o) {
        if (o.version != seenOrder) { seenOrder = o.version; nav.clear(); }
        Location me = p.getLocation();
        Player cmd = o.commander == null ? null : org.bukkit.Bukkit.getPlayer(o.commander);
        switch (o.mode) {
            case ATTACK: {
                Player t = o.target == null ? null : org.bukkit.Bukkit.getPlayer(o.target);
                if (t == null || t.isDead() || t.getGameMode() != GameMode.SURVIVAL || !hooks.inGame(t.getUniqueId())
                        || now > o.until || !t.getWorld().equals(p.getWorld())) {
                    o.mode = o.before == SquadRadio.Mode.ATTACK ? SquadRadio.Mode.AUTO : o.before;
                    o.target = null;
                    return null;
                }
                if (p.getHealth() <= 5 && findHeal(p) >= 0) return Goal.HEAL;
                Contact c = contact(t);
                c.last = t.getLocation();
                c.seenTick = now;
                c.attackedMe = true;
                c.visible = me.distance(t.getLocation()) < skill.viewDistance && p.hasLineOfSight(t);
                setTarget(c);
                return c.visible ? Goal.FIGHT : Goal.CHASE;
            }
            case FOLLOW: {
                if (cmd == null || !cmd.isOnline() || !cmd.getWorld().equals(p.getWorld())) return null;
                double d = cmd.getLocation().distance(me);
                // Враг рядом, а командир недалеко - дерёмся как обычно.
                if (target != null && target.visible && target.last.distance(me) < 25 && d < 30) return null;
                helpAlly = cmd;
                helpUntil = now + 40;
                return Goal.FOLLOW;
            }
            case LOOT: {
                // Лутаем как обычно, но хорошее носим командиру (см. teamwork).
                if (cmd != null && cmd.isOnline()) { lootCommander = cmd; lootOrderUntil = now + 60; }
                return null;
            }
            case HOLD: {
                if (o.hold == null || !o.hold.getWorld().equals(p.getWorld())) return null;
                holdPoint = o.hold;
                if (target != null && target.visible && target.last.distance(o.hold) < 35 && me.distance(o.hold) < 12) return null;
                return Goal.HOLD;
            }
            default:
                return null;
        }
    }

    private Location coverFrom, dodgeFrom, dodgePoint;
    private int coverUntil, nextCoverWall, nextTurretScan;
    private UUID tauntedId;
    private final List<Location> turrets = new ArrayList<Location>();

    /** Враг с картой в инвентаре (его стрелка видна на карте). */
    private Player mapCarrier(Player p, double r) {
        Player best = null;
        double bd = r * r;
        for (Player o : hooks.alivePlayers()) {
            if (o.getUniqueId().equals(id) || hooks.sameTeam(id, o.getUniqueId()) || !o.getWorld().equals(p.getWorld())) continue;
            if (o.getGameMode() != GameMode.SURVIVAL || !o.getInventory().contains(Material.FILLED_MAP)) continue;
            double d = o.getLocation().distanceSquared(p.getLocation());
            if (d < bd) { bd = d; best = o; }
        }
        return best;
    }

    /** Подразнить того, кого только что атаковали (раз на цель). */
    private void tauntOnAttack(Player p, LivingEntity e) {
        if (!(e instanceof Player) || e.getUniqueId().equals(tauntedId)) return;
        tauntedId = e.getUniqueId();
        // Как раньше по частоте, но через раз школьный токсик.
        talk(p, rnd.nextBoolean() ? BotChatter.Topic.TOXIC : BotChatter.Topic.SEE_ENEMY, 0.12, 20 * 45, e.getName());
    }

    /** ПВО MilitaryCraft рядом, которая видит бота (её мы и слышим). */
    private Location turretShooting(Player p, int now) {
        if (now >= nextTurretScan) {
            nextTurretScan = now + 40;
            turrets.clear();
            java.util.Set<String> ids = new java.util.HashSet<String>();
            for (Entity e : p.getNearbyEntities(70, 40, 70)) {
                for (org.bukkit.NamespacedKey k : e.getPersistentDataContainer().getKeys()) {
                    if (!k.getKey().equals("turret_id") || !k.getNamespace().equals("antiaircraft")) continue;
                    String tid = String.valueOf(e.getPersistentDataContainer().get(k, org.bukkit.persistence.PersistentDataType.STRING));
                    if (ids.add(tid)) turrets.add(e.getLocation());
                }
            }
        }
        Location best = null;
        double bd = Double.MAX_VALUE;
        for (Location t : turrets) {
            if (!t.getWorld().equals(p.getWorld())) continue;
            double d = t.distanceSquared(p.getLocation());
            if (d < bd && turretSees(t, p)) { bd = d; best = t; }
        }
        return best;
    }

    private static boolean turretSees(Location t, Player p) {
        Location from = t.clone().add(0, 1.5, 0);
        Vector dir = p.getEyeLocation().toVector().subtract(from.toVector());
        double len = dir.length();
        if (len < 0.5) return true;
        return t.getWorld().rayTraceBlocks(from, dir.multiply(1 / len), len - 0.5,
            org.bukkit.FluidCollisionMode.NEVER, true) == null;
    }

    /** Отбежать от этой точки (динамит, удар) на ticks тиков. */
    private void markDanger(Location l, int ticks, int now) {
        if (droneThreat != null && now < droneThreatUntil && droneThreat.distanceSquared(l) < 4) {
            droneThreatUntil = Math.max(droneThreatUntil, now + ticks);
            return;
        }
        droneThreat = l.clone();
        droneThreatUntil = now + ticks;
    }

    /** Боец банды хаоса (Хезболла, МЕЦАХ). */
    private static boolean isFaction(Entity e) {
        return e.getScoreboardTags().contains(dev.volansvo.svo.managers.ChaosManager.FACTION_TAG);
    }

    /** Банда на нас наседает - уходим от неё (как от дрона), в драку не лезем. */
    private void avoidFaction(Entity e, int now) {
        contacts.remove(e.getUniqueId());
        if (target != null && target.entity != null && target.entity.getUniqueId().equals(e.getUniqueId())) target = null;
        droneThreat = e.getLocation().clone();
        droneThreatUntil = now + 60;
    }

    /** Цель недостижима: запоминаем и выбираем другую. */
    private void abandonGoal(int now) {
        if (pickup != null) { badPickups.add(pickup.getUniqueId()); pickup = null; }
        if (chest != null && goal == Goal.LOOT) { searched.add(key(chest)); chest = null; }
        if (target != null && (goal == Goal.CHASE || goal == Goal.HUNT)) {
            ignoreUntil.put(target.entity.getUniqueId(), now + 20 * 60);
            target = null;
        }
        roam = null;
        if (goal == Goal.PLAN) { planPoint = null; planBanUntil = now + 20 * 30; }
        nav.clear();
    }

    private Item findNukeItem(Player p) {
        for (Entity e : p.getNearbyEntities(70, 30, 70)) {
            if (e instanceof Item && hooks.isNukeButton(((Item) e).getItemStack())) return (Item) e;
        }
        return null;
    }

    /** Нужен ли ещё такой предмет (не тащим десятый лук и худшую броню). */
    private boolean wantsMore(Player p, ItemStack it) {
        if (isFallSaver(it)) return fallSaverCount(p) < 2; // парашют/зелье падения: хотя бы один при себе
        if (Leap.isHook(it)) return gearSlot(p, true, false) < 0;  // один крюк
        if (Leap.isJet(it)) return gearSlot(p, false, false) < 0;  // один ранец
        Items.Kind k = Items.kind(it, hooks);
        PlayerInventory inv = p.getInventory();
        if (k == Items.Kind.ARMOR) {
            if (isVest(it)) { // один пояс шахида берём
                if (isVest(inv.getChestplate())) return false;
                for (int i = 0; i < 36; i++) if (isVest(inv.getItem(i))) return false;
                return true;
            }
            EquipmentSlot s = Items.armorSlotOf(it);
            ItemStack cur = s == null ? null : inv.getItem(s);
            return Items.armorValue(it) > Items.armorValue(cur) + 0.5;
        }
        if (k == Items.Kind.MELEE) {
            int m = bestMelee(p);
            return m < 0 || Items.meleeDps(it) > Items.meleeDps(inv.getItem(m)) + 0.5;
        }
        if (k == Items.Kind.BOW || k == Items.Kind.CROSSBOW || k == Items.Kind.SPRAYER || k == Items.Kind.LAUNCHER)
            return find(p, k) < 0;
        if (Items.isBuildBlock(it)) return Builder.blockCount(p) < 64;
        if (isTool(it.getType())) {
            String kind = it.getType().name().replaceAll("^[A-Z]+_", "");
            for (ItemStack o : inv.getStorageContents())
                if (o != null && o.getType().name().endsWith(kind) && toolTier(o.getType()) >= toolTier(it.getType())) return false;
            return true;
        }
        if (k == Items.Kind.CUSTOM) {
            Items.Custom ct = Items.customType(it);
            if (ct == Items.Custom.AUTO || ct == Items.Custom.SHOTGUN) return findCustom(p, ct) < 0;
        }
        if (k == Items.Kind.GUN) {
            // Двух стволов хватает: второй - запасной магазин без перезарядки.
            int guns = 0;
            for (ItemStack o : inv.getStorageContents()) if (Items.kind(o, hooks) == Items.Kind.GUN) guns++;
            return guns < 2;
        }
        return true;
    }

    /** Насколько нужен предмет с земли именно сейчас (еда, когда её мало, стрелы под лук...). */
    private double pickupWorth(Player p, ItemStack it) {
        double v = value(it);
        Items.Kind k = Items.kind(it, hooks);
        if (k == Items.Kind.FOOD && countKind(p, Items.Kind.FOOD) < 12) v = Math.max(v, 9);
        if (isArrow(it.getType()) && (find(p, Items.Kind.BOW) >= 0 || find(p, Items.Kind.CROSSBOW) >= 0)) v = Math.max(v, 12);
        if (Items.isBuildBlock(it) && Builder.blockCount(p) < 48) v = Math.max(v, 7);
        if (isFallSaver(it) && fallSaverCount(p) == 0) v = Math.max(v, 20);
        if (k == Items.Kind.CUSTOM && Items.customType(it) == Items.Custom.AMMO && hasAnyGun(p)) v = Math.max(v, 14);
        return v;
    }

    private double value(ItemStack it) {
        if (it == null || it.getType().isAir()) return 0;
        if (Rides.vehicleItem(it) != null) return 22; // техника MilitaryCraft
        if (isVest(it)) return 24; // пояс шахида: на крайний случай
        if (isFallSaver(it)) return 12; // парашют, зелье медленного падения
        if (Leap.isGear(it)) return 16;  // крюк-кошка, ранец
        if (Items.isBuildBlock(it)) return 4.5;
        if (isTool(it.getType())) return 5;
        if (it.getType() == Material.ARROW || it.getType() == Material.SPECTRAL_ARROW || it.getType() == Material.TIPPED_ARROW) return 9;
        return Items.value(it, hooks, mgr.learning());
    }

    // =====================================================================  инвентарь

    /** Надеть лучшую броню, тотем/щит во вторую руку. */
    private void manageInventory(Player p) {
        PlayerInventory inv = p.getInventory();
        int nowT = mgr.now();
        if (nowT >= nextJunkDrop && (target == null || !target.visible)) {
            int junk = findJunk(p);
            if (junk >= 0) {
                ItemStack it = inv.getItem(junk);
                inv.setItem(junk, null);
                tossAway(p, it);
                nextJunkDrop = nowT + 30;
            }
        }
        // Броню надевает equipArmor: по одной вещи и со звуком, как игрок.
        ItemStack off = inv.getItemInOffHand();
        Items.Kind offKind = Items.kind(off, hooks);
        if (offKind != Items.Kind.TOTEM && chuteSwapSlot < 0) {
            int totem = find(p, Items.Kind.TOTEM);
            int shield = find(p, Items.Kind.SHIELD);
            int pick = totem >= 0 ? totem : (offKind == Items.Kind.NONE ? shield : -1);
            if (pick >= 0 && !(offKind == Items.Kind.SHIELD && totem < 0)) {
                ItemStack moving = inv.getItem(pick);
                inv.setItem(pick, (off == null || off.getType().isAir()) ? null : off);
                inv.setItemInOffHand(moving);
            }
        }
        // В руке то, чем сейчас будем драться: лучший ствол/оружие, иначе пустая рука.
        // (Подобранный лук без стрел ложится в выбранный слот - с ним не ходим.)
        if (goal != Goal.FIGHT && nowT >= busyUntil && bowDrawStart < 0 && crossbowLoadStart < 0
                && minedBlock == null && towerTo == Integer.MIN_VALUE && leap == null
                && !glancing(nowT) && nowT >= craftUntil && nowT >= ladderUntil && gadgetSpot == null) {
            ItemStack held = inv.getItemInMainHand();
            Items.Kind hk = Items.kind(held, hooks);
            boolean useless = (hk == Items.Kind.BOW && !hasArrows(p))
                || (hk == Items.Kind.CROSSBOW && !hasArrows(p) && !crossbowCharged(p))
                || hk == Items.Kind.NONE || hk == Items.Kind.ARMOR || hk == Items.Kind.MILK
                || hk == Items.Kind.FOOD || hk == Items.Kind.HEAL
                || (hk == Items.Kind.CUSTOM && isDroneItem(held))
                || (EiKit.handled(held) && hk != Items.Kind.MELEE); // ракетница, мина и т.п. - только в момент применения
            if (useless || Items.isBuildBlock(held)) {
                int ready = weaponSlot(p, chooseWeapon(p, 20, nowT));
                if (ready < 0) ready = freeHandSlot(p);
                if (ready >= 0) hold(p, ready, nowT);
            }
        }
        // Еда: голоден и врагов рядом нет.
        if (p.getFoodLevel() <= 15 && (target == null || !target.visible)) {
            int food = bestFood(p);
            if (food >= 0 && hold(p, food, mgr.now())) {
                BotNms.useItem(p, false);
                busyUntil = mgr.now() + 40;
            }
        }
        // Плохие эффекты - молоко.
        if (p.hasPotionEffect(PotionEffectType.POISON) || p.hasPotionEffect(PotionEffectType.WITHER)
                || p.hasPotionEffect(PotionEffectType.BLINDNESS)) {
            int milk = find(p, Items.Kind.MILK);
            if (milk >= 0 && hold(p, milk, mgr.now())) { BotNms.useItem(p, false); busyUntil = mgr.now() + 40; }
        }
        // Бафф перед боем.
        if (target != null && target.visible && mgr.now() >= nextBuff) {
            int buff = -1;
            for (int i = 0; i < 36 && buff < 0; i++) {
                ItemStack bi = p.getInventory().getItem(i);
                if (Items.kind(bi, hooks) == Items.Kind.BUFF && !isSlowFallPotion(bi)) buff = i;
            }
            if (buff >= 0 && hold(p, buff, mgr.now())) {
                BotNms.useItem(p, false);
                busyUntil = mgr.now() + 35;
                nextBuff = mgr.now() + 20 * 40;
            }
        }
    }

    /** Лечение. false - лечиться нечем. */
    private boolean heal(Player p, int now) {
        if (now < busyUntil) return true;
        int slot = findHeal(p);
        if (slot < 0) return false;
        ItemStack it = p.getInventory().getItem(slot);
        if (!hold(p, slot, now)) return true;
        String wid = Items.warkitId(it);
        if (it.getType() == Material.SPLASH_POTION || it.getType() == Material.LINGERING_POTION) {
            if (!motor.aim(p, motor.yaw(), 88f, 5f)) return true;
            BotNms.useItem(p, false);
            busyUntil = now + 10;
            return true;
        }
        BotNms.useItem(p, false);
        if ("medkit".equals(wid)) {
            // Аптечка MilitaryCraft бинтует 4 секунды и сбрасывается, если убрать её из руки
            // или получить урон: держим её в руке всё это время.
            stillUntil = now + 20;
            busyUntil = now + 88;
        } else {
            busyUntil = now + 40;
        }
        return true;
    }

    private int findHeal(Player p) {
        PlayerInventory inv = p.getInventory();
        int best = -1;
        double bestV = 0;
        for (int i = 0; i < 36; i++) {
            if (kindAt(p, i) != Items.Kind.HEAL) continue;
            ItemStack it = inv.getItem(i);
            if (p.hasCooldown(it)) continue; // аптечка/яблоко на перезарядке
            double v = it.getType() == Material.ENCHANTED_GOLDEN_APPLE ? 5
                : Items.warkitId(it) != null ? 4 : it.getType() == Material.GOLDEN_APPLE ? 3 : 2;
            if (v > bestV) { bestV = v; best = i; }
        }
        if (best < 0 && p.getFoodLevel() < 18) best = bestFood(p);
        return best;
    }

    private int countHeals(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents())
            if (Items.kind(it, hooks) == Items.Kind.HEAL) n += it.getAmount();
        return n;
    }

    private int bestFood(Player p) {
        PlayerInventory inv = p.getInventory();
        int best = -1, bestV = 1;
        for (int i = 0; i < 36; i++) {
            if (kindAt(p, i) != Items.Kind.FOOD) continue;
            ItemStack it = inv.getItem(i);
            int v = Items.foodValue(it.getType());
            if (v > bestV) { bestV = v; best = i; }
        }
        return best;
    }

    private int find(Player p, Items.Kind kind) {
        for (int i = 0; i < 36; i++) if (kindAt(p, i) == kind) return i;
        return -1;
    }

    // ---- что лежит в слотах: разбор предмета дорогой (метки, описание), а спрашивают о нём
    // десятки раз за тик боя. Помним разбор, пока в слоте тот же самый предмет.
    private final Object[] invRaw = new Object[36];
    private final Items.Kind[] invKind = new Items.Kind[36];
    private final Items.Custom[] invCustom = new Items.Custom[36];

    private void slotInfo(Player p, int i) {
        Object raw = BotNms.rawItem(p, i);
        if (raw != null && raw == invRaw[i] && invKind[i] != null) return;
        ItemStack it = p.getInventory().getItem(i);
        Items.Kind k = Items.kind(it, hooks);
        invRaw[i] = raw;
        invKind[i] = k;
        invCustom[i] = k == Items.Kind.CUSTOM ? Items.customType(it) : Items.Custom.UNKNOWN;
    }

    private Items.Kind kindAt(Player p, int i) {
        slotInfo(p, i);
        return invKind[i];
    }

    private Items.Custom customAt(Player p, int i) {
        slotInfo(p, i);
        return invCustom[i];
    }

    private int findGun(Player p) {
        PlayerInventory inv = p.getInventory();
        int best = -1, bestScore = -1;
        for (int i = 0; i < 36; i++) {
            if (kindAt(p, i) != Items.Kind.GUN) continue;
            ItemStack it = inv.getItem(i);
            int score = "rifle".equals(Items.warkitId(it)) ? 2 : 1;
            if (Items.ammo(it) != 0) score += 4;
            if (i == inv.getHeldItemSlot()) score += 1;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        return best;
    }

    private boolean hasGun(Player p) {
        return findGun(p) >= 0 || find(p, Items.Kind.LAUNCHER) >= 0;
    }

    private boolean hasArrows(Player p) {
        PlayerInventory inv = p.getInventory();
        return inv.contains(Material.ARROW) || inv.contains(Material.SPECTRAL_ARROW) || inv.contains(Material.TIPPED_ARROW)
            || inv.getItemInOffHand().getType() == Material.ARROW;
    }

    private int bestMelee(Player p) {
        PlayerInventory inv = p.getInventory();
        int best = -1;
        double bestV = 1.5;
        for (int i = 0; i < 36; i++) {
            if (kindAt(p, i) != Items.Kind.MELEE) continue;
            ItemStack it = inv.getItem(i);
            double v = Items.meleeDps(it);
            if (v > bestV) { bestV = v; best = i; }
        }
        return best;
    }

    /** Изученный полезный плагинный предмет или ещё не изученный (если разрешено пробовать). */
    private int findLearnedCustom(Player p, int now) {
        if (now != 0 && now < nextCustom) return -1;
        PlayerInventory inv = p.getInventory();
        ItemLearning l = mgr.learning();
        for (int i = 0; i < 36; i++) {
            if (kindAt(p, i) != Items.Kind.CUSTOM || customAt(p, i) != Items.Custom.UNKNOWN) continue; // дрон/стволы/патроны - отдельно
            ItemStack it = inv.getItem(i);
            if (EiKit.handled(it)) continue; // ракетница и т.п. - у них своё применение
            String k = Items.customKey(it);
            if (l.isWeapon(k) || (skill.learnItems && l.worthTrying(k))) return i;
        }
        return -1;
    }

    /**
     * Взять предмет в руку. Из рюкзака переносим в хотбар (на место наименее ценного),
     * как это делает игрок через инвентарь. false - в этот тик ещё переключаемся.
     */
    private int swapReadyAt;

    private boolean hold(Player p, int slot, int now) {
        PlayerInventory inv = p.getInventory();
        if (slot < 0) return false;
        if (now < busyUntil) return inv.getHeldItemSlot() == slot;
        if (slot >= 9) {
            if (now < swapReadyAt) return false;
            int hs = cheapestHotbarSlot(p);
            ItemStack a = inv.getItem(slot), b = inv.getItem(hs);
            inv.setItem(hs, a);
            inv.setItem(slot, b);
            slot = hs;
            swapReadyAt = now + 4;
        }
        if (inv.getHeldItemSlot() != slot) {
            BotNms.selectSlot(p, slot);
            handReadyAt = now + timing.swap();
            bowDrawStart = -1;
            crossbowLoadStart = -1;
        }
        return inv.getHeldItemSlot() == slot;
    }

    private int cheapestHotbarSlot(Player p) {
        PlayerInventory inv = p.getInventory();
        int best = 8;
        double bestV = Double.MAX_VALUE;
        for (int i = 0; i < 9; i++) {
            ItemStack it = inv.getItem(i);
            double v = (it == null || it.getType().isAir()) ? -1 : value(it);
            if (it != null && (hooks.isNukeButton(it))) v = 1e9;
            if (v < bestV) { bestV = v; best = i; }
        }
        return best;
    }

    // =====================================================================  изучение предметов

    private void maybeStartLearning(Player p, LivingEntity e, int now, ItemStack used) {
        if (!skill.learnItems || used == null || Items.kind(used, hooks) != Items.Kind.CUSTOM) return;
        learnKey = Items.customKey(used);
        learnUntil = now + 60;
        learnTarget = e;
        learnTargetHp = e.getHealth() + e.getAbsorptionAmount();
        learnSelfDamage = 0;
        learnDealt = 0;
        learnOther = 0;
    }

    private void finishLearning(Player p) {
        double dealt = 0;
        if (learnTarget != null) {
            double now = learnTarget.isDead() ? 0 : learnTarget.getHealth() + learnTarget.getAbsorptionAmount();
            // Пока бот пробовал предмет, цель могли бить другие: их урон предмету не засчитываем.
            dealt = Math.max(learnDealt, Math.max(0, learnTargetHp - now) - learnOther);
        }
        mgr.learning().record(learnKey, dealt, learnSelfDamage);
        learnKey = null;
        learnTarget = null;
    }

    // =====================================================================  оценки сил

    private double myPower(Player p) {
        return power(p, p.getHealth(), armorTotal(p), bestWeaponFactor(p));
    }

    private double enemyPower(LivingEntity e) {
        if (!(e instanceof Player)) return 0.6;
        Player o = (Player) e;
        double hp = hpGuess(contacts.get(o.getUniqueId()), o, mgr.now());
        ItemStack hand = o.getInventory().getItemInMainHand();
        double wf;
        Items.Kind k = Items.kind(hand, hooks);
        Items.Custom ct = k == Items.Kind.CUSTOM ? Items.customType(hand) : Items.Custom.UNKNOWN;
        if (ct == Items.Custom.AUTO) k = Items.Kind.GUN;
        else if (ct == Items.Custom.SHOTGUN) k = Items.Kind.LAUNCHER;
        switch (k) {
            case GUN: wf = 2.2; break;
            case LAUNCHER: case SPRAYER: wf = 2.0; break;
            case BOW: case CROSSBOW: wf = 1.6; break;
            case MELEE: wf = 1.0 + Items.meleeDps(hand) / 9.0; break;
            default: wf = 0.9; break; // мог убрать оружие в инвентарь - не считаем слабым
        }
        return power(o, hp, armorTotal(o), wf);
    }

    private static double power(LivingEntity e, double hp, double armor, double weapon) {
        return (hp / 20.0) * (1.0 + armor / 18.0) * weapon;
    }

    private double bestWeaponFactor(Player p) {
        if (findGun(p) >= 0 || findEi(p, Items.Custom.AUTO, mgr.now()) >= 0) return 2.2;
        if (findEi(p, Items.Custom.SHOTGUN, mgr.now()) >= 0) return 1.9;
        if (find(p, Items.Kind.LAUNCHER) >= 0 || find(p, Items.Kind.SPRAYER) >= 0) return 2.0;
        if ((find(p, Items.Kind.BOW) >= 0 || find(p, Items.Kind.CROSSBOW) >= 0) && hasArrows(p)) return 1.6;
        int m = bestMelee(p);
        if (m >= 0) return 1.0 + Items.meleeDps(p.getInventory().getItem(m)) / 9.0;
        return 0.5;
    }

    private static double armorTotal(LivingEntity e) {
        AttributeInstance a = e.getAttribute(Attribute.ARMOR);
        AttributeInstance t = e.getAttribute(Attribute.ARMOR_TOUGHNESS);
        return (a == null ? 0 : a.getValue()) + (t == null ? 0 : t.getValue() * 0.6);
    }

    private static double maxHp(LivingEntity e) {
        AttributeInstance a = e.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    // =====================================================================  зона и места

    /** 0 - в безопасности, иначе насколько близко к краю зоны. */
    private double zoneDanger(Player p) {
        WorldBorder wb = p.getWorld().getWorldBorder();
        Location l = p.getLocation();
        double half = wb.getSize() / 2.0;
        double edge = half - Math.max(Math.abs(l.getX() - wb.getCenter().getX()), Math.abs(l.getZ() - wb.getCenter().getZ()));
        double margin = borderShrinking ? Math.min(30, half * 0.35) : 5;
        return edge < margin ? margin - edge : 0;
    }

    /**
     * Враг у самого края едущей зоны (ближе к нему, чем мы): не догоняем, зона его и так накроет,
     * а нам туда возвращаться. В упор или когда он нас бьёт - деремся.
     */
    private boolean zoneDoomed(Player p, Contact c, double dist, int now) {
        if (!borderShrinking || c == null || c.last == null || dist <= 6) return false;
        if (c.attackedMe && now - lastHurt < 60) return false;
        if (!c.last.getWorld().equals(p.getWorld())) return false;
        return edgeAt(p.getWorld(), c.last.getX(), c.last.getZ()) < Math.min(25, zoneEdge(p) - 4);
    }

    /** Расстояние от точки до края зоны (меньше нуля - за краем). */
    private static double edgeAt(World w, double x, double z) {
        WorldBorder wb = w.getWorldBorder();
        return wb.getSize() / 2.0 - Math.max(Math.abs(x - wb.getCenter().getX()), Math.abs(z - wb.getCenter().getZ()));
    }

    /** Расстояние до края зоны (меньше нуля - уже за краем). */
    private double zoneEdge(Player p) {
        WorldBorder wb = p.getWorld().getWorldBorder();
        Location l = p.getLocation();
        return wb.getSize() / 2.0 - Math.max(Math.abs(l.getX() - wb.getCenter().getX()), Math.abs(l.getZ() - wb.getCenter().getZ()));
    }

    /** У края зоны можно драться: враг на виду, а зона стоит или доедет нескоро (успеем отойти и после боя). */
    private boolean fightNearZone(Player p, int now) {
        Contact t = target;
        if (t == null || t.entity == null || !(t.visible || now - t.seenTick < 40)) return false;
        if (zoneEdge(p) <= 1.5) return false;
        return zoneEta(p, now) > zoneNeed(p, now) + 20;
    }

    private boolean zoneRetreat;
    private int zoneEtaTick = -100;
    private double zoneEtaCached = Double.MAX_VALUE;

    /**
     * Через сколько секунд край зоны дойдёт до бота: по тому, как она едет сейчас, и по
     * расписанию следующего сужения. MAX_VALUE - не дойдёт.
     */
    private double zoneEta(Player p, int now) {
        if (now - zoneEtaTick < 20) return zoneEtaCached;
        zoneEtaTick = now;
        double edge = zoneEdge(p), eta = Double.MAX_VALUE;
        try {
            if (edge <= 0) eta = 0;
            else {
                World w = p.getWorld();
                double size = w.getWorldBorder().getSize(), t = 0;
                double[] lerp = BotNms.borderLerp(w);
                boolean done = false;
                if (lerp[1] > 0 && lerp[0] < size) {
                    double secs = lerp[1] / 1000.0, move = (size - lerp[0]) / 2.0;
                    if (edge <= move) { eta = edge / (move / Math.max(0.05, secs)); done = true; }
                    else { edge -= move; t = secs; size = lerp[0]; }
                }
                if (!done && hooks.gameActive()) {
                    // Ближайшее сужение по расписанию, которое начнётся после текущего.
                    int left = hooks.remainingTicks();
                    long[] next = null;
                    for (long[] ph : hooks.zoneSchedule()) {
                        double start = (left - ph[0]) / 20.0;
                        if (start > 0 && start >= t - 1 && (next == null || ph[0] > next[0])) next = ph;
                    }
                    if (next != null) {
                        double start = (left - next[0]) / 20.0, move = next[1] / 2.0;
                        if (edge <= move) eta = start + edge / (move / Math.max(1, next[2]));
                    }
                }
            }
        } catch (Throwable ignored) {
            eta = borderShrinking && borderEdgeSpeed > 0 ? edge / (borderEdgeSpeed * 20) : Double.MAX_VALUE;
        }
        return zoneEtaCached = eta;
    }

    /** Сколько секунд нужно, чтобы отойти от края на 40 блоков вглубь (из пещеры - заметно дольше). */
    private double zoneNeed(Player p, int now) {
        double need = Math.max(0, 40 - zoneEdge(p)) / 3.5;
        if (belowGround(p) || undergroundSince >= 0) need = need * 2.5 + 15;
        return need;
    }

    /** Край зоны ближе 40 блоков и дойдёт раньше, чем мы спокойно успеем отойти: уходим сейчас, хоть из боя. */
    private boolean zoneUrgent(Player p, int now) {
        double edge = zoneEdge(p);
        if (edge >= 40) return false;
        return zoneEta(p, now) < zoneNeed(p, now) + 12;
    }

    private boolean insideBorder(World w, double x, double z, double margin) {
        WorldBorder wb = w.getWorldBorder();
        double half = wb.getSize() / 2.0 - margin;
        if (half <= 0) half = wb.getSize() / 4.0;
        return Math.abs(x - wb.getCenter().getX()) < half && Math.abs(z - wb.getCenter().getZ()) < half;
    }

    private Location zoneSafePoint(Player p) {
        WorldBorder wb = p.getWorld().getWorldBorder();
        Location c = wb.getCenter();
        Location l = p.getLocation();
        Vector dir = c.toVector().subtract(l.toVector()).setY(0);
        double len = dir.length();
        if (len < 1) return c.clone();
        double step = Math.min(len, 45);
        Location t = l.clone().add(dir.normalize().multiply(step));
        t.setY(nearY(p.getWorld(), t.getBlockX(), t.getBlockZ(), l.getBlockY()));
        return t;
    }

    private Location escapePoint(Player p, Location from) {
        return escapePoint(p, from, false);
    }

    private Location escapePoint(Player p, Location from, boolean preferRoof) {
        Location l = p.getLocation();
        Vector away = l.toVector().subtract(from.toVector()).setY(0);
        if (away.lengthSquared() < 0.01) away = new Vector(rnd.nextDouble() - 0.5, 0, rnd.nextDouble() - 0.5);
        away.normalize();
        // Пробуем несколько направлений «от врага» и берём то, что внутри зоны и за укрытием.
        Location best = null;
        double bestScore = -1e9;
        for (int i = 0; i < 6; i++) {
            double ang = (rnd.nextDouble() - 0.5) * Math.PI * 0.9;
            Vector d = away.clone().rotateAroundY(ang).multiply(14 + rnd.nextInt(8));
            Location t = l.clone().add(d);
            if (!insideBorder(p.getWorld(), t.getX(), t.getZ(), 8)) continue;
            t.setY(nearY(p.getWorld(), t.getBlockX(), t.getBlockZ(), l.getBlockY()));
            double score = t.distance(from);
            Location eyeT = t.clone().add(0, 1.6, 0);
            if (from.getWorld().rayTraceBlocks(from.clone().add(0, 1.6, 0), eyeT.toVector().subtract(from.toVector().add(new Vector(0, 1.6, 0))),
                    eyeT.distance(from.clone().add(0, 1.6, 0))) != null) score += 25; // укрытие
            if (preferRoof) {
                // Под крышей бомба и дрон не достанут (ищем точку, где сверху есть блоки).
                int x = t.getBlockX(), z = t.getBlockZ();
                for (int dy = 2; dy <= 8; dy++) {
                    if (p.getWorld().getBlockAt(x, l.getBlockY() + dy, z).getType().isSolid()) { score += 45; t.setY(l.getBlockY()); break; }
                }
            }
            if (score > bestScore) { bestScore = score; best = t; }
        }
        return best != null ? best : zoneSafePoint(p);
    }

    private Location roamPoint(Player p) {
        World w = p.getWorld();
        WorldBorder wb = w.getWorldBorder();
        double r = Math.min(wb.getSize() * 0.3, 140);
        Location c = wb.getCenter();
        Location l = p.getLocation();
        // Половина времени - к центру, половина - вокруг себя (исследование).
        double bx = rnd.nextBoolean() ? c.getX() : l.getX();
        double bz = rnd.nextBoolean() ? c.getZ() : l.getZ();
        double x = bx + (rnd.nextDouble() * 2 - 1) * r;
        double z = bz + (rnd.nextDouble() * 2 - 1) * r;
        // Зона едет - точку берём там, куда она за минуту не доберётся.
        double half = Math.max(5, wb.getSize() / 2.0 - 10 - (borderShrinking ? Math.min(80, borderEdgeSpeed * 20 * 60) : 0));
        x = Math.max(c.getX() - half, Math.min(c.getX() + half, x));
        z = Math.max(c.getZ() - half, Math.min(c.getZ() + half, z));
        Warden wd = hooks.warden();
        if (wd != null && nearWarden(x, z, 30)) {
            // Точка у Жириновского - сдвигаем её от него.
            Vector away = new Vector(x - wd.getLocation().getX(), 0, z - wd.getLocation().getZ());
            if (away.lengthSquared() < 1) away = new Vector(1, 0, 0);
            away.normalize().multiply(35);
            x = wd.getLocation().getX() + away.getX();
            z = wd.getLocation().getZ() + away.getZ();
        }
        return new Location(w, x, surfaceY(w, (int) x, (int) z, l.getBlockY()), z);
    }

    /** Куда приземляться: к сундукам поближе к центру зоны. */
    private Location dropLanding;

    private Location dropTarget(Player p) {
        // Старая точка (с прошлой высадки или до сужения) уже у края или за зоной - выбираем заново.
        if (dropLanding != null && (!dropLanding.getWorld().equals(p.getWorld())
                || !insideBorder(p.getWorld(), dropLanding.getX(), dropLanding.getZ(), 15))) dropLanding = null;
        if (dropLanding != null) return dropLanding;
        int[][] all = hooks.chests();
        World w = p.getWorld();
        Location l = p.getLocation();
        // Место, где в прошлые разы высадка удалась, бот выбирает снова.
        if (skill.memory && memory.hasLanding && memory.landScore >= 0 && rnd.nextInt(10) < 6
                && insideBorder(w, memory.landX, memory.landZ, 20) && Math.hypot(memory.landX - l.getX(), memory.landZ - l.getZ()) < 150) {
            dropLanding = new Location(w, memory.landX, l.getY(), memory.landZ);
            return dropLanding;
        }
        Location best = null;
        double bestD = Double.MAX_VALUE;
        Warden warden = hooks.warden();
        if (all != null) {
            for (int k = 0; k < 40 && all.length > 0; k++) {
                int[] c = all[rnd.nextInt(all.length)];
                double d = Math.hypot(c[0] - l.getX(), c[2] - l.getZ());
                if (!insideBorder(w, c[0], c[2], 20)) continue;
                // Не садимся рядом с Жириновским.
                if (warden != null && Math.hypot(c[0] - warden.getLocation().getX(), c[2] - warden.getLocation().getZ()) < 45) continue;
                if (d < bestD) { bestD = d; best = new Location(w, c[0] + 0.5, c[1], c[2] + 0.5); }
            }
        }
        if (best == null || bestD > 120) {
            best = l.clone();
            // Падаем у края или за зоной: правим к центру зоны.
            if (!insideBorder(w, l.getX(), l.getZ(), 25)) {
                Location c = w.getWorldBorder().getCenter();
                Vector in = new Vector(c.getX() - l.getX(), 0, c.getZ() - l.getZ());
                double len = in.length();
                if (len > 1) best.add(in.multiply(Math.min(len, 60) / len));
            }
            if (warden != null && best.distance(warden.getLocation()) < 45) {
                Vector away = l.toVector().subtract(warden.getLocation().toVector()).setY(0);
                if (away.lengthSquared() < 1) away = new Vector(1, 0, 0);
                best.add(away.normalize().multiply(50));
            }
        }
        dropLanding = best;
        return best;
    }

    private int groundY(Player p) {
        Location l = p.getLocation();
        World w = p.getWorld();
        for (int y = l.getBlockY(); y > w.getMinHeight(); y--) {
            if (w.getBlockAt(l.getBlockX(), y, l.getBlockZ()).getType().isSolid()) return y + 1;
        }
        return w.getMinHeight();
    }

    /** Высота для точки рядом с ботом: ближайшее к его уровню место, где можно стоять. */
    private int nearY(World w, int x, int z, int near) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return near;
        int y = nav.standY(w, x, z, near);
        return y != Integer.MIN_VALUE ? y : w.getHighestBlockYAt(x, z) + 1;
    }

    private int surfaceY(World w, int x, int z, int near) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return near;
        return w.getHighestBlockYAt(x, z) + 1;
    }

    /**
     * Как motor.drive, но шаг, который уведёт с обрыва глубже 4 блоков или в лаву, не делаем:
     * стоим (в бою бот шёл к врагу напрямик и падал в ущелье).
     */
    private void driveSafe(Player p, double dx, double dz, double speed, float strafe, boolean jump, boolean sprint) {
        if (BotNms.onGround(p)) {
            double len = Math.hypot(dx, dz), mx = 0, mz = 0;
            if (len > 1e-6 && speed > 0) { mx = dx / len * speed; mz = dz / len * speed; }
            if (strafe != 0f) {
                // Шаг вбок (плюс - влево от взгляда), как его считает Motor.
                double yr = Math.toRadians(motor.yaw());
                mx += Math.cos(yr) * strafe;
                mz += Math.sin(yr) * strafe;
            }
            if ((mx != 0 || mz != 0) && !safeStep(p, mx, mz)) { motor.stop(p); return; }
        }
        motor.drive(p, dx, dz, speed, strafe, jump, sprint);
    }

    /** Можно ли шагнуть в направлении (dx,dz): под следующей клеткой есть опора не глубже 4 блоков и нет лавы. */
    private boolean safeStep(Player p, double dx, double dz) {
        return safeStep(p, dx, dz, 0.8);
    }

    private boolean safeStep(Player p, double dx, double dz, double ahead) {
        double len = Math.hypot(dx, dz);
        if (len < 1e-3) return true;
        Location l = p.getLocation();
        World w = p.getWorld();
        int x = (int) Math.floor(l.getX() + dx / len * ahead), z = (int) Math.floor(l.getZ() + dz / len * ahead);
        int y = l.getBlockY();
        if (w.getBlockAt(x, y, z).getType().isSolid()) return true; // стена/ступенька - это не обрыв
        for (int dy = 1; dy <= 5; dy++) {
            Block b = w.getBlockAt(x, y - dy, z);
            Material t = b.getType();
            if (t == Material.LAVA || t == Material.FIRE || t == Material.MAGMA_BLOCK) return false;
            if (t == Material.WATER) return true;
            if (t.isSolid()) return dy <= 4;
        }
        return false;
    }

    /** Точка ближе radius к Жириновскому (его логово обходим стороной). */
    private boolean nearWarden(double x, double z, double radius) {
        Warden w = hooks.warden();
        if (w == null) return false;
        double dx = x - w.getLocation().getX(), dz = z - w.getLocation().getZ();
        return dx * dx + dz * dz < radius * radius;
    }

    private boolean dangerBehind(Player p, double dx, double dz) {
        double len = Math.hypot(dx, dz);
        if (len < 1e-3) return false;
        Location back = p.getLocation().add(-dx / len * 1.2, 0, -dz / len * 1.2);
        World w = p.getWorld();
        for (int dy = 1; dy <= 4; dy++) {
            Block b = w.getBlockAt(back.getBlockX(), back.getBlockY() - dy, back.getBlockZ());
            if (b.getType() == Material.LAVA || b.getType() == Material.FIRE) return true;
            if (b.getType().isSolid()) return false;
        }
        return true; // обрыв глубже 3 блоков
    }

    // =====================================================================  команда

    private Player teamLeader(Player p) {
        int team = hooks.teamIdOf(id);
        if (team < 0) return null;
        Player best = null;
        for (Player o : hooks.alivePlayers()) {
            if (o.getUniqueId().equals(id) || hooks.teamIdOf(o.getUniqueId()) != team) continue;
            if (!mgr.isBot(o.getUniqueId())) return o; // живой человек всегда лидер
            if (best == null || o.getName().compareTo(best.getName()) < 0) best = o;
        }
        if (best != null && p.getName().compareTo(best.getName()) < 0) return null; // лидер - я
        return best;
    }

    // =====================================================================  утилиты

    private Contact contact(LivingEntity e) {
        Contact c = contacts.get(e.getUniqueId());
        if (c == null) {
            c = new Contact();
            c.entity = e;
            c.last = e.getLocation();
            contacts.put(e.getUniqueId(), c);
        }
        c.entity = e;
        return c;
    }

    private Contact freshestContact(int now, int maxAge) {
        Contact best = null;
        for (Contact c : contacts.values()) {
            if (now - c.seenTick > maxAge) continue;
            if (!(c.entity instanceof Player)) continue;
            if (best == null || c.seenTick > best.seenTick) best = c;
        }
        return best;
    }

    private static LivingEntity livingSource(Entity e) {
        if (e instanceof LivingEntity) return (LivingEntity) e;
        if (e instanceof Projectile && ((Projectile) e).getShooter() instanceof LivingEntity)
            return (LivingEntity) ((Projectile) e).getShooter();
        if (e instanceof TNTPrimed && ((TNTPrimed) e).getSource() instanceof LivingEntity)
            return (LivingEntity) ((TNTPrimed) e).getSource();
        return null;
    }

    private static float yawTo(Player p, Location t) {
        Location e = p.getEyeLocation();
        return Motor.yawTo(t.getX() - e.getX(), t.getZ() - e.getZ());
    }

    private static float pitchTo(Player p, Location t) {
        Location e = p.getEyeLocation();
        return Motor.pitchTo(t.getX() - e.getX(), t.getY() - e.getY(), t.getZ() - e.getZ());
    }

    private static long key(int[] c) { return key(c[0], c[1], c[2]); }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    // =====================================================================  дроны

    /**
     * FPV-камикадзе и Bombsender: запускаем, когда рядом нет врагов (тело остаётся стоять
     * беззащитным), а цель в досягаемости полёта.
     */
    private void maybePilotDrone(Player p, int now) {
        if (pilot.active() || now < pilot.nextUse || now < busyUntil || p.getHealth() < 10) return;
        if (!BotNms.onGround(p) || BotNms.inWater(p)) return;
        int fpv = findCustom(p, Items.Custom.FPV), bomber = findCustom(p, Items.Custom.BOMBER);
        if (fpv < 0 && bomber < 0) return;
        for (Contact c : contacts.values()) {
            if (now - c.seenTick < 100 && c.last.distance(p.getLocation()) < 30) return; // враг рядом - не до дрона
        }
        boolean useFpv = fpv >= 0 && (bomber < 0 || rnd.nextBoolean());
        // Летим только к тому, о ком знаем (видели, слышали, сказали свои, заметили на карте).
        Player tgt = knownEnemy(useFpv ? 150 : 100, 20 * 30);
        if (tgt == null || !pilot.safeTarget(tgt)) return;
        if (!hold(p, useFpv ? fpv : bomber, now)) return;
        nav.clear();
        motor.stop(p);
        note(name + " запускает " + (useFpv ? "FPV" : "Bombsender") + " на " + tgt.getName());
        pilot.launch(p, useFpv, tgt, now);
        talk(p, BotChatter.Topic.PILOT, 0.3, 20 * 120, null);
    }

    /**
     * Вражеские дроны: самонаводка (стойка с тегом minidronest), пилоты FPV/Bombsender
     * (игрок-наблюдатель с тегом fpvfly/bombfly - его не видно, но слышно жужжание).
     * Свои и дроны тиммейтов не пугают.
     */
    private void scanDrones(Player p, int now) {
        Location me = p.getLocation();
        // Нас выбрала целью самонаводка - бежим, даже если её не видно.
        for (String tag : p.getScoreboardTags()) {
            if (!tag.endsWith("mdronetarget")) continue;
            String owner = tag.substring(0, tag.length() - "mdronetarget".length());
            if (owner.equals(name) || isTeammateName(owner)) continue;
            for (Entity e : p.getWorld().getEntitiesByClass(ArmorStand.class)) {
                if (e.getScoreboardTags().contains(owner + "mdrone")) { markDrone(e.getLocation(), now); return; }
            }
        }
        for (Entity e : p.getNearbyEntities(45, 120, 45)) {
            String owner = null;
            boolean drone = false;
            if (e instanceof ArmorStand) {
                for (String t : e.getScoreboardTags()) {
                    if (t.equals("minidronest")) drone = true;
                    else if (t.endsWith("mdrone") && t.length() > 6) owner = t.substring(0, t.length() - 6);
                }
            } else if (e instanceof Player && ((Player) e).getGameMode() == GameMode.SPECTATOR) {
                java.util.Set<String> tags = e.getScoreboardTags();
                if (tags.contains("fpvfly") || tags.contains("bombfly")) { drone = true; owner = e.getName(); }
            }
            if (!drone) continue;
            if (owner != null && (owner.equals(name) || isTeammateName(owner))) continue;
            Location dl = e.getLocation();
            double flat = Math.hypot(dl.getX() - me.getX(), dl.getZ() - me.getZ());
            boolean pilotDrone = e instanceof Player;
            // Самонаводку видно глазами, пилота слышно (жужжит) вблизи по горизонтали.
            boolean noticed = pilotDrone ? flat < 32 : (flat < 14 || p.hasLineOfSight(e));
            if (noticed && flat < 45) { markDrone(dl, now); return; }
        }
    }

    /** Дрон-предмет в руке запускается от спринта/приседа - держим его только в момент запуска. */
    private static boolean isDroneItem(ItemStack it) {
        Items.Custom c = Items.customType(it);
        return c == Items.Custom.FPV || c == Items.Custom.BOMBER || c == Items.Custom.DRONE || c == Items.Custom.AMMO;
    }

    private void stowDrone(Player p, int now) {
        if (!isDroneItem(p.getInventory().getItemInMainHand())) return;
        int slot = weaponSlot(p, chooseWeapon(p, 20, now));
        if (slot < 0 || isDroneItem(p.getInventory().getItem(slot))) slot = freeHandSlot(p);
        if (slot >= 0) {
            busyUntil = 0;
            hold(p, slot, now);
        }
    }

    private void markDrone(Location l, int now) {
        droneThreat = l.clone();
        droneThreatUntil = now + 50;
    }

    private boolean isTeammateName(String playerName) {
        Player o = org.bukkit.Bukkit.getPlayerExact(playerName);
        return o != null && hooks.sameTeam(id, o.getUniqueId());
    }

    // =====================================================================  плагинное оружие

    /** Слот плагинного предмета этого типа (стволы, которые «не стреляют», пропускаем минуту). */
    private int findEi(Player p, Items.Custom type, int now) {
        return findEi(p, type, now, false);
    }

    /** loadedOnly - только с патронами в магазине; иначе пустой с запасом тоже годится. */
    private int findEi(Player p, Items.Custom type, int now, boolean loadedOnly) {
        PlayerInventory inv = p.getInventory();
        int empty = -1;
        for (int i = 0; i < 36; i++) {
            if (kindAt(p, i) != Items.Kind.CUSTOM || customAt(p, i) != type) continue;
            ItemStack it = inv.getItem(i);
            Integer dud = eiDudUntil.get(Items.customKey(it));
            if (dud != null && now < dud) continue;
            int[] mag = Items.eiMag(it);
            if (mag != null && mag[0] == 0 && mag[2] < minReload(type) && !EiKit.hasAmmo(p, type == Items.Custom.AUTO)) continue; // стрелять нечем
            if (mag != null && mag[0] == 0) {
                // Магазин пуст, запас есть: годится, если заряженного нет (тот, что в руке, первым).
                if (empty < 0 || i == inv.getHeldItemSlot()) empty = i;
                continue;
            }
            return i;
        }
        return loadedOnly ? -1 : empty;
    }

    private int findCustom(Player p, Items.Custom type) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            if (kindAt(p, i) == Items.Kind.CUSTOM && customAt(p, i) == type) return i;
        }
        return -1;
    }

    private boolean hasAnyGun(Player p) {
        return hasGun(p) || findCustom(p, Items.Custom.AUTO) >= 0 || findCustom(p, Items.Custom.SHOTGUN) >= 0;
    }

    /**
     * После выстрела из плагинного ствола: запоминаем, что надо перезарядиться, и следим,
     * попадает ли он. Если много выстрелов подряд цель не теряет ХП - патроны кончились или
     * ствол не работает: минуту им не пользуемся.
     */
    private void eiFired(Player p, LivingEntity e, int now, int maxMisses) {
        eiNeedsReload = true;
        if (Items.eiMag(p.getInventory().getItemInMainHand()) != null) return; // патроны видны в описании
        String k = Items.customKey(p.getInventory().getItemInMainHand());
        double hpNow = e.getHealth() + e.getAbsorptionAmount();
        if (!k.equals(eiShotKey) || eiShotTarget != e) {
            eiShotKey = k; eiShotTarget = e; eiShots = 0; eiShotTargetHp = hpNow;
        }
        if (hpNow < eiShotTargetHp - 0.4) { eiShots = 0; eiShotTargetHp = hpNow; return; }
        if (++eiShots >= maxMisses) {
            eiDudUntil.put(k, now + 20 * 60);
            eiShots = 0;
        }
    }

    /** Перезарядка плагинного ствола (ЛКМ) в спокойную минуту, как сделал бы игрок. */
    private int nextEiCheck;

    private void maybeReloadEi(Player p, int now) {
        if (now < busyUntil || now < eiReloadUntil) return;
        // После своего выстрела смотрим сразу, а подобранные полупустые стволы раз в 3 сек.
        if (!eiNeedsReload && now < nextEiCheck) return;
        nextEiCheck = now + 60;
        if (target != null && now - target.seenTick < 60) return;
        PlayerInventory inv = p.getInventory();
        int slot = -1, time = 0;
        boolean unknown = false;
        for (int i = 0; i < 36 && slot < 0; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || Items.kind(it, hooks) != Items.Kind.CUSTOM) continue;
            Items.Custom t = Items.customType(it);
            if (t != Items.Custom.SHOTGUN && t != Items.Custom.AUTO) continue;
            int[] mag = Items.eiMag(it);
            if (mag == null) { if (eiNeedsReload && !unknown) { unknown = true; slot = i; time = eiReloadTicks(t, null); } continue; }
            if (mag[2] < minReload(t) || mag[0] >= mag[1]) continue;
            // Автомат не даёт дозарядить, пока в магазине влезает меньше пачки (7 патронов).
            if (t == Items.Custom.AUTO && mag[1] - mag[0] < 7) continue;
            slot = i; time = eiReloadTicks(t, mag);
        }
        eiNeedsReload = false;
        if (slot < 0 || !hold(p, slot, now)) return;
        if (skill.reloadLookUp && !motor.aim(p, motor.yaw(), -55f, 8f)) { nextEiCheck = now; return; }
        BotNms.clickAir(p);
        // Патроны встают в магазин сразу по клику, дальше у ствола только задержка выстрела.
        busyUntil = now + 6;
        eiReloadUntil = now + time;
        eiDudUntil.clear();
    }

    /** Меньше этого запаса ствол не перезаряжается (условие перезарядки в предмете). */
    private static int minReload(Items.Custom t) {
        return t == Items.Custom.AUTO ? 7 : 1;
    }

    /** Сколько тиков держать ствол в руке ради перезарядки. */
    private static int eiReloadTicks(Items.Custom t, int[] mag) {
        // И автомат, и дробовик заряжаются одним кликом, после него 5 сек ствол не стреляет.
        return 104;
    }

    /**
     * В бою магазин опустел: если есть запас, жмём ЛКМ (перезарядка) и не стреляем, пока
     * идёт перезарядка. true = сейчас не стрелять.
     */
    private boolean eiEmptyReload(Player p, Items.Custom t, int now) {
        if (now < eiReloadUntil) return true;
        ItemStack gun = p.getInventory().getItemInMainHand();
        int[] mag = Items.eiMag(gun);
        if (mag == null || mag[0] > 0) return false;
        if (mag[2] < minReload(t)) {
            // На перезарядку запаса мало (автомат берёт от 7): пачку патронов в левую руку и
            // клик. Пачки нет - этот ствол пока откладываем, иначе бот бесконечно жмёт
            // перезарядку, которая не срабатывает, и кружит вокруг врага без выстрелов.
            if (eikit.refillNow(p, now)) return true;
            eiDudUntil.put(Items.customKey(gun), now + 20 * 20);
            return true;
        }
        if (skill.reloadLookUp && !motor.aim(p, motor.yaw(), -55f, 8f)) return true;
        BotNms.clickAir(p); // ЛКМ в воздух, даже если враг вплотную или над головой потолок
        eiReloadUntil = now + eiReloadTicks(t, mag);
        talk(p, BotChatter.Topic.T_RELOAD, 0.12, 20 * 45, null);
        note(name + " перезаряжает " + t);
        return true;
    }

    /** Дрон-самонаводка сам летит к ближайшему игроку не из нашей команды в радиусе 200. */
    private void maybeLaunchDrone(Player p, int now) {
        if (now < nextDrone || now < busyUntil) return;
        int slot = findCustom(p, Items.Custom.DRONE);
        if (slot < 0) return;
        // Дрон сам найдёт ближайшего врага, но запускаем, только когда знаем, что враг рядом.
        // Раньше запуск отменялся, если ближе всех стоял тиммейт, а знал бот обо всех сквозь стены.
        Player nearest = knownEnemy(190, 20 * 45);
        if (nearest == null) return;
        if (target != null && target.visible && target.last.distance(p.getLocation()) < 8) return;
        if (!hold(p, slot, now)) return;
        BotNms.useItem(p, false);
        talk(p, BotChatter.Topic.DRONE, 0.3, 20 * 120, null);
        note(name + " запускает дрон, ближайший враг " + nearest.getName());
        nextDrone = now + 20 * 115;
        busyUntil = now + 6;
    }

    private boolean crossbowCharged(Player p) {
        int i = find(p, Items.Kind.CROSSBOW);
        if (i < 0) return false;
        ItemStack cb = p.getInventory().getItem(i);
        return cb.getItemMeta() instanceof CrossbowMeta && ((CrossbowMeta) cb.getItemMeta()).hasChargedProjectiles();
    }

    /** Пустой слот хотбара (драться кулаком), иначе слот с чем-то безобидным. */
    private int freeHandSlot(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) return i;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack it = inv.getItem(i);
            Items.Kind k = Items.kind(it, hooks);
            // Плагинное «ненужное» (станковый пулемёт и т.п.) в руку не берём - оно тяжёлое.
            if ((k == Items.Kind.FOOD || k == Items.Kind.NONE) && !Items.isCustom(it)) return i;
        }
        // Освобождаем слот: самое дешёвое из хотбара - в рюкзак.
        int empty = -1;
        for (int i = 9; i < 36 && empty < 0; i++) if (inv.getItem(i) == null || inv.getItem(i).getType().isAir()) empty = i;
        if (empty >= 0) {
            int hs = cheapestHotbarSlot(p);
            inv.setItem(empty, inv.getItem(hs));
            inv.setItem(hs, null);
            return hs;
        }
        // Рюкзак полон: обычный предмет (блок, инструмент) бьёт как кулак.
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && !Items.isCustom(it)) return i;
        }
        return -1;
    }

    /** Этим можно бить: пустая рука, оружие ближнего боя или обычный предмет. Стволы и предметы плагинов - нет. */
    private boolean punchable(ItemStack it) {
        if (it == null || it.getType().isAir()) return true;
        if (Items.kind(it, hooks) == Items.Kind.MELEE) return true;
        return !it.hasItemMeta() || !Items.isCustom(it);
    }

    private static boolean isTool(Material m) {
        String n = m.name();
        return n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || m == Material.SHEARS;
    }

    private static int toolTier(Material m) {
        String n = m.name();
        return n.startsWith("NETHERITE") ? 5 : n.startsWith("DIAMOND") ? 4 : n.startsWith("IRON") ? 3
            : n.startsWith("STONE") ? 2 : n.startsWith("GOLDEN") ? 1 : 0;
    }

    private boolean betterToolExists(Player p, ItemStack tool, int slot) {
        String kind = tool.getType().name().replaceAll("^[A-Z]+_", "");
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            if (i == slot) continue;
            ItemStack o = inv.getItem(i);
            if (o == null || !o.getType().name().endsWith(kind)) continue;
            int a = toolTier(o.getType()), b = toolTier(tool.getType());
            if (a > b || (a == b && i < slot)) return true;
        }
        return false;
    }

    private static boolean isArrow(Material m) {
        return m == Material.ARROW || m == Material.SPECTRAL_ARROW || m == Material.TIPPED_ARROW;
    }

    // =====================================================================  инвентарь: хлам

    /** Что выкинуть: мусор, худшие дубли оружия и брони, лук без стрел при наличии ствола. */
    private int findJunk(Player p) {
        PlayerInventory inv = p.getInventory();
        int bestMelee = bestMelee(p);
        int milk = 0, guns = 0, blocks = Builder.blockCount(p);
        boolean betterRanged = hasAnyGun(p);
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            if (hooks.isNukeButton(it) || it.getType() == Material.FILLED_MAP) continue;
            if (Rides.vehicleItem(it) != null) continue;
            if (isFallSaver(it) || Leap.isGear(it)) continue; // держим, пока не понадобится
            if (now() < busyUntil && i == inv.getHeldItemSlot()) continue;
            Items.Kind k = Items.kind(it, hooks);
            if (Items.isBuildBlock(it)) {
                if (blocks > 96) return i;
                continue;
            }
            if (isTool(it.getType())) {
                // Инструменты: по одному лучшему каждого вида (кирка, лопата).
                if (betterToolExists(p, it, i)) return i;
                continue;
            }
            switch (k) {
                case NONE:
                    if (!isArrow(it.getType()) && value(it) <= 0.5) return i;
                    break;
                case MELEE:
                    if (bestMelee >= 0 && i != bestMelee && Items.meleeDps(it) <= Items.meleeDps(inv.getItem(bestMelee))) return i;
                    break;
                case ARMOR: {
                    if (isVest(it)) break; // пояс шахида держим на крайний случай
                    EquipmentSlot s = Items.armorSlotOf(it);
                    if (s != null && Items.armorValue(it) <= Items.armorValue(inv.getItem(s))) return i;
                    break;
                }
                case BOW: case CROSSBOW:
                    if (betterRanged && !hasArrows(p) && !(k == Items.Kind.CROSSBOW && crossbowCharged(p))) return i;
                    if (find(p, k) != i) return i; // второй такой же
                    break;
                case MILK:
                    if (++milk > 1) return i;
                    break;
                case GUN:
                    if (++guns > 2) return i;
                    break;
                case CUSTOM:
                    if (Items.customType(it) == Items.Custom.UNKNOWN && !EiKit.handled(it) && mgr.learning().valueOf(Items.customKey(it)) <= 0
                            && !mgr.learning().worthTrying(Items.customKey(it))) return i; // ещё не пробовали - держим
                    break;
                default:
                    break;
            }
        }
        return -1;
    }

    private int now() { return mgr.now(); }

    /** Выбросить предмет перед собой (или в сторону тиммейта). */
    /** Выбросить ненужное: в сторону от людей рядом и подальше, чтобы не мешалось. */
    private void tossAway(Player p, ItemStack it) {
        Location me = p.getLocation();
        Vector away = null;
        double bd = 12 * 12;
        for (Player o : p.getWorld().getPlayers()) {
            if (o.equals(p) || o.getGameMode() == GameMode.SPECTATOR) continue;
            double d = o.getLocation().distanceSquared(me);
            if (d < bd) { bd = d; away = me.toVector().subtract(o.getLocation().toVector()).setY(0); }
        }
        if (away == null || away.lengthSquared() < 1e-4) {
            away = me.getDirection().setY(0).rotateAroundY(Math.toRadians(rnd.nextBoolean() ? 90 : -90));
        }
        if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
        away.normalize();
        float yaw = Motor.yawTo(away.getX(), away.getZ());
        BotNms.look(p, yaw, -15f);
        motor.sync(p);
        Item drop = toss(p, it, away, false);
        drop.setVelocity(away.clone().multiply(0.55).add(new Vector(0, 0.28, 0))); // улетает блоков на 4-5
    }

    private Item toss(Player p, ItemStack it, Vector dir, boolean gift) {
        Location eye = p.getEyeLocation();
        Item drop = p.getWorld().dropItem(eye.clone().add(0, -0.3, 0), it);
        Vector v = dir.clone();
        if (v.lengthSquared() < 1e-4) v = eye.getDirection();
        drop.setVelocity(v.normalize().multiply(gift ? 0.32 : 0.25).add(new Vector(0, 0.12, 0)));
        drop.setThrower(id);
        drop.setPickupDelay(gift ? 10 : 40);
        badPickups.add(drop.getUniqueId());
        p.swingMainHand();
        return drop;
    }

    // =====================================================================  команда: делёжка

    private List<Player> teammates(Player p) {
        List<Player> out = new ArrayList<Player>();
        int team = hooks.teamIdOf(id);
        if (team < 0) return out;
        for (Player o : hooks.alivePlayers()) {
            if (o.getUniqueId().equals(id) || hooks.teamIdOf(o.getUniqueId()) != team) continue;
            if (o.getWorld().equals(p.getWorld())) out.add(o);
        }
        return out;
    }

    /** Раз в 2 секунды смотрим, не нужна ли тиммейтам еда, хил, стрелы или патроны. */
    private void teamwork(Player p, int now) {
        if (now < nextShareCheck) return;
        nextShareCheck = now + 40;
        if (shareTo != null) {
            if (!shareTo.isOnline() || shareTo.isDead() || now - shareSince > 20 * 25) { shareTo = null; shareItems.clear(); }
            return;
        }
        if (target != null && target.visible && target.last.distance(p.getLocation()) < 30) return;
        // Приказ «лутать»: хорошее несём командиру, оставляя себе не меньше 30%.
        Player cmd = lootCommander;
        if (now < lootOrderUntil && now >= nextLootDelivery && cmd != null && cmd.isOnline() && !cmd.isDead()
                && cmd.getGameMode() == GameMode.SURVIVAL && cmd.getWorld().equals(p.getWorld())
                && cmd.getLocation().distance(p.getLocation()) < 150) {
            List<ItemStack> give = generousGive(p, cmd);
            double v = 0;
            for (ItemStack it : give) v += value(it);
            if (v >= 25) {
                shareTo = cmd;
                shareItems.clear();
                shareItems.addAll(give);
                shareSince = now;
                nextLootDelivery = now + 20 * 45;
                return;
            }
        }
        for (Player mate : teammates(p)) {
            if (mate.getLocation().distance(p.getLocation()) > 35) continue;
            List<ItemStack> give = whatToGive(p, mate);
            if (give.isEmpty()) continue;
            shareTo = mate;
            shareItems.clear();
            shareItems.addAll(give);
            shareSince = now;
            return;
        }
    }

    /**
     * Что отдать командиру по приказу «лутать»: всё, что ему пригодится (броня лучше его,
     * оружие, которого у него нет, хил, еда, патроны...), но себе оставляем хотя бы 30%
     * ценности и своё лучшее оружие.
     */
    private List<ItemStack> generousGive(Player me, Player mate) {
        List<ItemStack> out = new ArrayList<ItemStack>(whatToGive(me, mate));
        PlayerInventory inv = me.getInventory();
        double total = 0;
        List<ItemStack> all = new ArrayList<ItemStack>();
        for (int i = 0; i < 36; i++) { ItemStack it = inv.getItem(i); if (it != null && !it.getType().isAir()) all.add(it); }
        for (ItemStack it : inv.getArmorContents()) if (it != null && !it.getType().isAir()) all.add(it);
        for (ItemStack it : all) total += value(it);
        double given = 0;
        for (ItemStack it : out) given += value(it);
        double keep = total * 0.3;
        int myBest = bestMelee(me), myGun = findGun(me);
        all.sort((a, b) -> Double.compare(value(b), value(a)));
        java.util.Set<String> kinds = new java.util.HashSet<String>();
        for (ItemStack it : all) {
            double v = value(it);
            if (v <= 1 || hooks.isNukeButton(it) || it.getType() == Material.FILLED_MAP) continue;
            if (myBest >= 0 && it.equals(inv.getItem(myBest)) && myGun < 0) continue; // единственное оружие - себе
            if (myGun >= 0 && it.equals(inv.getItem(myGun))) continue;                  // свой ствол - себе
            if (total - given - v < keep) continue;
            if (!usefulFor(mate, it)) continue;
            String k = Items.kind(it, hooks) + ":" + ((Items.armorSlotOf(it) != null) ? Items.armorSlotOf(it) : "");
            if (!kinds.add(k) && Items.kind(it, hooks) != Items.Kind.FOOD) continue; // по одному каждого вида
            ItemStack c = it.clone();
            if (Items.kind(it, hooks) == Items.Kind.FOOD) c.setAmount(Math.max(1, Math.min(c.getAmount(), 8)));
            out.add(c);
            given += value(c);
        }
        return out;
    }

    /** Пригодится ли предмет союзнику (у него нет такого или есть хуже, и ему этого не хватает). */
    private boolean usefulFor(Player mate, ItemStack it) {
        if (isFallSaver(it)) return false; // свой парашют не отдаём
        PlayerInventory inv = mate.getInventory();
        Items.Kind k = Items.kind(it, hooks);
        switch (k) {
            case ARMOR: {
                if (isVest(it)) return false;
                EquipmentSlot s = Items.armorSlotOf(it);
                return s != null && Items.armorValue(it) > Items.armorValue(inv.getItem(s)) + 0.5;
            }
            case MELEE: {
                int m = bestMelee(mate);
                return m < 0 || Items.meleeDps(it) > Items.meleeDps(inv.getItem(m)) + 0.5;
            }
            case GUN: case LAUNCHER: case SPRAYER: case BOW: case CROSSBOW: case TRIDENT: case SHIELD:
                return find(mate, k) < 0;
            case HEAL: return countKind(mate, Items.Kind.HEAL) < 3;
            case FOOD: return countKind(mate, Items.Kind.FOOD) < 8;
            case THROW_DAMAGE: return countKind(mate, Items.Kind.THROW_DAMAGE) < 3;
            case TOTEM: return find(mate, Items.Kind.TOTEM) < 0 && (inv.getItemInOffHand() == null || inv.getItemInOffHand().getType() != Material.TOTEM_OF_UNDYING);
            case PEARL: return countKind(mate, Items.Kind.PEARL) < 4;
            case CUSTOM: {
                Items.Custom c = Items.customType(it);
                if (c == Items.Custom.AMMO) return hasAnyGun(mate) && countCustom(mate, Items.Custom.AMMO) < 30;
                if (c != Items.Custom.UNKNOWN) return findCustom(mate, c) < 0;
                EiKit.Use u = EiKit.use(it);
                if (u != null) {
                    for (ItemStack o : inv.getContents()) if (EiKit.use(o) == u) return false;
                    return true;
                }
                if (Rides.vehicleItem(it) != null) {
                    for (ItemStack o : inv.getContents()) if (Rides.vehicleItem(o) != null) return false;
                    return true;
                }
                return false;
            }
            default:
                if (isArrow(it.getType())) return (find(mate, Items.Kind.BOW) >= 0 || find(mate, Items.Kind.CROSSBOW) >= 0) && countArrows(mate) < 32;
                return false;
        }
    }

    private List<ItemStack> whatToGive(Player me, Player mate) {
        List<ItemStack> out = new ArrayList<ItemStack>();
        // Еда: у тиммейта голод и нет еды, у нас есть лишняя.
        int myFood = countKind(me, Items.Kind.FOOD);
        // У тиммейта нет еды совсем - делимся всегда, даже последним.
        if (countKind(mate, Items.Kind.FOOD) == 0 && myFood >= 1) {
            ItemStack f = stackOf(me, Items.Kind.FOOD, Math.max(1, Math.min(8, myFood / 2)));
            if (f != null) out.add(f);
        }
        // Хил: тиммейт ранен и лечиться нечем.
        int myHeal = countKind(me, Items.Kind.HEAL);
        if (mate.getHealth() <= 12 && countKind(mate, Items.Kind.HEAL) == 0
                && (myHeal >= 2 || (myHeal >= 1 && me.getHealth() >= 16))) {
            ItemStack h = stackOf(me, Items.Kind.HEAL, 1);
            if (h != null) out.add(h);
        }
        // Стрелы: у тиммейта лук/арбалет и мало стрел.
        boolean mateBow = find(mate, Items.Kind.BOW) >= 0 || find(mate, Items.Kind.CROSSBOW) >= 0;
        boolean myBow = find(me, Items.Kind.BOW) >= 0 || find(me, Items.Kind.CROSSBOW) >= 0;
        int myArrows = countArrows(me);
        if (mateBow && countArrows(mate) < 8 && myArrows > 0 && (!myBow || myArrows >= 16)) {
            out.add(new ItemStack(Material.ARROW, myBow ? myArrows / 2 : Math.min(64, myArrows)));
        }
        // Патроны (плагинные предметы-патроны): у тиммейта ствол и мало патронов.
        int myAmmo = countCustom(me, Items.Custom.AMMO);
        if (myAmmo > 0 && hasAnyGun(mate) && countCustom(mate, Items.Custom.AMMO) < 10
                && (!hasAnyGun(me) || myAmmo >= 20)) {
            ItemStack a = customStack(me, Items.Custom.AMMO, hasAnyGun(me) ? myAmmo / 2 : myAmmo);
            if (a != null) out.add(a);
        }
        return out;
    }

    /** Подошли к тиммейту (или он виден в пределах 10 блоков): бросаем ему вещи. */
    private void giveTo(Player p, Player mate) {
        Location eye = p.getEyeLocation(), to = mate.getLocation().add(0, 0.6, 0);
        if (!motor.aim(p, Motor.yawTo(to.getX() - eye.getX(), to.getZ() - eye.getZ()),
                Motor.pitchTo(to.getX() - eye.getX(), to.getY() - eye.getY(), to.getZ() - eye.getZ()), 10f)) return;
        boolean gave = false;
        for (ItemStack want : shareItems) {
            ItemStack taken = takeFromInventory(p, want);
            if (taken == null) continue;
            // Кидаем в руки: предмет падает игроку под ноги.
            Item drop = toss(p, taken, to.toVector().subtract(eye.toVector()), true);
            drop.setVelocity(lob(drop.getLocation(), to));
            gave = true;
        }
        if (gave) talk(p, mate.equals(lootCommander) ? BotChatter.Topic.T_LOOT_TO_YOU : BotChatter.Topic.T_SHARE, 0.45, 0, null);
        shareTo = null;
        shareItems.clear();
    }

    /** Скорость броска предмета навесом, чтобы он упал в точку to (гравитация 0.04, сопротивление 0.98). */
    private static Vector lob(Location from, Location to) {
        double dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        double t = Math.max(6, flat * 2.2 + Math.max(0, dy) * 1.5); // тиков полёта
        double drag = Math.pow(0.98, t / 2);
        double vx = dx / t / drag, vz = dz / t / drag;
        double vy = (dy + 0.04 * t * t / 2) / t / drag;
        Vector v = new Vector(vx, vy, vz);
        if (v.length() > 1.2) v.normalize().multiply(1.2);
        return v;
    }

    /** Достаёт из инвентаря до want.getAmount() таких же предметов. */
    private ItemStack takeFromInventory(Player p, ItemStack want) {
        PlayerInventory inv = p.getInventory();
        int need = want.getAmount();
        ItemStack result = null;
        for (int i = 0; i < 36 && need > 0; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || !it.isSimilar(want)) continue;
            int n = Math.min(need, it.getAmount());
            if (result == null) { result = it.clone(); result.setAmount(0); }
            result.setAmount(result.getAmount() + n);
            need -= n;
            if (n >= it.getAmount()) inv.setItem(i, null);
            else it.setAmount(it.getAmount() - n);
        }
        if (need > 0 && (Items.armorSlotOf(want) != null)) {
            EquipmentSlot s = Items.armorSlotOf(want);
            ItemStack worn = s == null ? null : inv.getItem(s);
            if (worn != null && worn.isSimilar(want)) {
                result = worn.clone();
                inv.setItem(s, null);
                need = 0;
            }
        }
        return result != null && result.getAmount() > 0 ? result : null;
    }

    private int countKind(Player p, Items.Kind k) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (it != null && Items.kind(it, hooks) == k) n += it.getAmount();
        return n;
    }

    private int countArrows(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if (it != null && isArrow(it.getType())) n += it.getAmount();
        return n;
    }

    private int countCustom(Player p, Items.Custom type) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents())
            if (it != null && Items.kind(it, hooks) == Items.Kind.CUSTOM && Items.customType(it) == type) n += it.getAmount();
        return n;
    }

    /** Копия самой большой стопки этого вида с нужным количеством (что отдать). */
    private ItemStack stackOf(Player p, Items.Kind k, int amount) {
        ItemStack best = null;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it == null || Items.kind(it, hooks) != k) continue;
            if (best == null || it.getAmount() > best.getAmount()) best = it;
        }
        if (best == null) return null;
        ItemStack c = best.clone();
        c.setAmount(Math.min(amount, best.getAmount()));
        return c;
    }

    private ItemStack customStack(Player p, Items.Custom type, int amount) {
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it == null || Items.kind(it, hooks) != Items.Kind.CUSTOM || Items.customType(it) != type) continue;
            ItemStack c = it.clone();
            c.setAmount(Math.max(1, Math.min(amount, it.getAmount())));
            return c;
        }
        return null;
    }

    /** Последние причины (застрял, бросил блок, лезет...) - для /asvobot why, пишутся всегда. */
    private final java.util.ArrayDeque<String> notes = new java.util.ArrayDeque<String>();

    // =====================================================================  план на матч

    private Location planPoint;
    private int planPointUntil, planHoldSince = -1, planBanUntil;
    /** Центр облюбованного для засады места (сундуки вокруг), вокруг него ставим мины. */
    private Location ambushCenter;

    /** Отступ от края зоны, где точка ещё безопасна (больше, когда зона едет). */
    private double zoneKeep() {
        return 10 + (borderShrinking ? Math.min(80, borderEdgeSpeed * 20 * 60) : 0);
    }

    private Location at(World w, double x, double z, Location near) {
        return new Location(w, x, nearY(w, (int) Math.floor(x), (int) Math.floor(z), near.getBlockY()), z);
    }

    /** Точка на «кольце» вокруг центра зоны со своей стороны (frac - доля полуразмера зоны). */
    private Location ringPoint(Player p, double frac) {
        World w = p.getWorld();
        WorldBorder wb = w.getWorldBorder();
        Location c = wb.getCenter(), me = p.getLocation();
        double dx = me.getX() - c.getX(), dz = me.getZ() - c.getZ(), d = Math.hypot(dx, dz);
        if (d < 1) { double a = rnd.nextDouble() * Math.PI * 2; dx = Math.cos(a); dz = Math.sin(a); d = 1; }
        double r = Math.min(d, wb.getSize() / 2.0 * frac);
        return at(w, c.getX() + dx / d * r, c.getZ() + dz / d * r, me);
    }

    /** Самое высокое место, где можно стоять, в радиусе r от (x,z): обзор и выгодная позиция. */
    private Location highGround(Player p, double x, double z, double r) {
        World w = p.getWorld();
        Location best = null;
        for (int k = 0; k < 10; k++) {
            double a = rnd.nextDouble() * Math.PI * 2, rr = k == 0 ? 0 : rnd.nextDouble() * r;
            int bx = (int) Math.floor(x + Math.cos(a) * rr), bz = (int) Math.floor(z + Math.sin(a) * rr);
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) continue;
            int y = groundTop(w, bx, bz, p.getLocation().getBlockY());
            if (!walkable(w, bx, y, bz)) continue;
            if (best == null || y > best.getY()) best = new Location(w, bx + 0.5, y, bz + 0.5);
        }
        return best != null ? best : at(w, x, z, p.getLocation());
    }

    /** Засада: место, вокруг которого больше всего сундуков (туда придут лутать), и где тихо. */
    private Location ambushPoint(Player p, int now) {
        int[][] all = hooks.chests();
        World w = p.getWorld();
        Location me = p.getLocation();
        if (all == null || all.length == 0) return ringPoint(p, 0.3);
        ThreatMap tm = threat();
        int[] best = null;
        double bestScore = -1e9;
        for (int k = 0; k < 30; k++) {
            int[] c = all[rnd.nextInt(all.length)];
            if (!insideBorder(w, c[0], c[2], zoneKeep() + 10)) continue;
            double d = Math.hypot(c[0] - me.getX(), c[2] - me.getZ());
            if (d > 220) continue;
            int n = 0;
            for (int[] o : all) if (Math.abs(o[0] - c[0]) < 20 && Math.abs(o[2] - c[2]) < 20) n++;
            double score = n * 3 - d * 0.03 - tm.heat(c[0], c[2], now) * 2;
            if (score > bestScore) { bestScore = score; best = c; }
        }
        if (best == null) return ringPoint(p, 0.3);
        ambushCenter = new Location(w, best[0] + 0.5, best[1], best[2] + 0.5);
        // Стоим в стороне (шагов семь), откуда видно подходы к сундукам.
        double dx = me.getX() - best[0], dz = me.getZ() - best[2], d = Math.max(1, Math.hypot(dx, dz));
        return highGround(p, best[0] + dx / d * 7, best[2] + dz / d * 7, 4);
    }

    /** Точка плана на матч или null (сейчас плану нечего делать: лутаем и т.п.). */
    private Location planPoint(Player p, int now, boolean lootDone) {
        if (now < planBanUntil) return null;
        World w = p.getWorld();
        if (planPoint != null && (!planPoint.getWorld().equals(w) || now > planPointUntil
                || !insideBorder(w, planPoint.getX(), planPoint.getZ(), zoneKeep()))) planPoint = null;
        if (planPoint != null) return planPoint;
        double el = hooks.elapsedTicks(), left = hooks.remainingTicks();
        double f = el / Math.max(1, el + left);
        Location me = p.getLocation();
        WorldBorder wb = w.getWorldBorder();
        Location c = wb.getCenter();
        double half = wb.getSize() / 2.0;
        ThreatMap tm = threat();
        Location pt = null;
        switch (persona.plan) {
            case HOT_DROP: {
                double[] hot = tm.hottest(me.getX(), me.getZ(), 140, 1.5, now);
                if (hot != null) pt = at(w, hot[0], hot[1], me);
                else if (lootDone) pt = ringPoint(p, 0.3);
                break;
            }
            case LOOT_PUSH: {
                if (!lootDone) return null;
                double[] hot = tm.hottest(me.getX(), me.getZ(), 100, 2, now);
                pt = hot != null ? at(w, hot[0], hot[1], me) : ringPoint(p, 0.3);
                break;
            }
            case HOLD_CENTER:
                if (!lootDone && f < 0.2) return null;
                pt = highGround(p, c.getX(), c.getZ(), Math.min(12, half * 0.3));
                break;
            case THIRD_PARTY: {
                double[] hot = tm.hottest(me.getX(), me.getZ(), 160, 3, now); // свежая перестрелка
                if (hot != null) {
                    double dx = me.getX() - hot[0], dz = me.getZ() - hot[1], d = Math.max(1, Math.hypot(dx, dz));
                    double r = Math.min(d, 24); // подходим на дистанцию выстрела, не в самую кашу
                    pt = at(w, hot[0] + dx / d * r, hot[1] + dz / d * r, me);
                } else if (lootDone) pt = ringPoint(p, 0.35);
                break;
            }
            case EDGE: {
                if (!lootDone && f < 0.3) return null;
                double keep = Math.max(5, half - zoneKeep() - 18);
                double ang = Math.atan2(me.getZ() - c.getZ(), me.getX() - c.getX()), bestHeat = Double.MAX_VALUE;
                double bx = c.getX(), bz = c.getZ();
                // Со своей стороны, но туда, где тише.
                for (int k = -2; k <= 2; k++) {
                    double a = ang + k * 0.35;
                    double x = c.getX() + Math.max(-keep, Math.min(keep, Math.cos(a) * keep * 1.2));
                    double z = c.getZ() + Math.max(-keep, Math.min(keep, Math.sin(a) * keep * 1.2));
                    double h = tm.heat(x, z, now) + Math.abs(k) * 0.3;
                    if (h < bestHeat) { bestHeat = h; bx = x; bz = z; }
                }
                pt = at(w, bx, bz, me);
                break;
            }
            case HUNT: {
                double[] hot = tm.hottest(me.getX(), me.getZ(), 220, 1, now);
                pt = hot != null ? at(w, hot[0], hot[1], me) : (lootDone ? ringPoint(p, 0.3) : null);
                break;
            }
            case AMBUSH:
                if (!lootDone && f < 0.15) return null;
                pt = ambushPoint(p, now);
                break;
            default:
        }
        if (pt == null || !insideBorder(w, pt.getX(), pt.getZ(), zoneKeep())) return null;
        planPoint = pt;
        planPointUntil = now + 20 * (25 + (int) (persona.patience * 60));
        planHoldSince = -1;
        return pt;
    }

    /**
     * Стоим на позиции плана: смотрим туда, откуда скорее всего придут (по карте угроз),
     * стрелок и тактик сидят присев, тактик минирует подходы. Через какое-то время (терпение)
     * точка пересчитывается - бот меняет позицию, как игрок, которому надоело сидеть.
     */
    private void holdPosition(Player p, int now) {
        if (gadgetStep(p, now)) return;
        if (gadgetSpot == null && now >= nextGadget && rnd.nextDouble() < persona.gadgets) planGadget(p, now);
        motor.stop(p);
        boolean crouch = (persona.type == Persona.Archetype.MARKSMAN || persona.type == Persona.Archetype.TACTICIAN
            || persona.type == Persona.Archetype.SURVIVOR) && persona.patience > 0.35
            && Items.eiId(p.getInventory().getItemInMainHand()) == null; // присед с предметом EI запускает его
        BotNms.sneak(p, crouch);
        prepareWeapon(p, persona.range);
        Location look = attention(p, now, true);
        if (look != null) motor.turn(p, yawTo(p, look), pitchTo(p, look), Math.min(skill.turnSpeed, 12f));
    }

    // =====================================================================  внимание

    private Location attnPoint, heardAt;
    private int attnUntil, nextAttn, heardTick = -1000;

    /**
     * Куда сейчас посмотреть, кроме дороги: человек на ходу оглядывается - на выстрел, на
     * место, где недавно были враги, иногда за спину. Голова поворачивается с задержкой
     * реакции. Это не украшение: зрение у бота - конус взгляда, так что он, как и человек,
     * замечает тех, на кого посмотрел, и пропускает тех, кто зашёл со спины.
     * standing - стоит на позиции (оглядывается чаще и дольше). null - смотреть по ходу.
     */
    private Location attention(Player p, int now, boolean standing) {
        if (now < attnUntil && attnPoint != null && attnPoint.getWorld().equals(p.getWorld())) return attnPoint;
        attnPoint = null;
        Location me = p.getLocation();
        // Выстрел или взрыв рядом - повернуться на звук (после реакции).
        if (heardAt != null && now - heardTick >= 5 && now - heardTick < 30 && heardAt.getWorld().equals(me.getWorld())
                && heardAt.distanceSquared(me) < 60 * 60) {
            attnPoint = heardAt.clone().add(0, 1.4, 0);
            heardAt = null;
            attnUntil = now + 15 + rnd.nextInt(20);
            return attnPoint;
        }
        if (now < nextAttn) return null;
        nextAttn = now + (int) ((standing ? 30 : 60) + rnd.nextInt(standing ? 40 : 90) * (1.4 - persona.curiosity));
        double[] hot = threat().hottest(me.getX(), me.getZ(), 70, 0.8, now);
        double r = rnd.nextDouble();
        if (hot != null && r < 0.55) {
            attnPoint = new Location(me.getWorld(), hot[0], me.getY() + 1.4, hot[1]);
        } else if (r < 0.85 || standing) {
            // Оглядеться: в сторону или за спину.
            double a = Math.toRadians(motor.yaw() + 90 + (rnd.nextBoolean() ? 1 : -1) * (40 + rnd.nextInt(110)));
            attnPoint = me.clone().add(-Math.sin(a) * 12, 1.4, Math.cos(a) * 12);
        } else return null;
        attnUntil = now + (standing ? 25 : 10) + rnd.nextInt(standing ? 40 : 15);
        return attnPoint;
    }

    // =====================================================================  карта

    private int glanceUntil = -1, nextGlance = 20 * 40, glanceSlot = -1;

    /**
     * Иногда смотрим на карту (как игрок): на пару секунд берём её в руку и опускаем голову.
     * На карте видны игроки, у которых она тоже в инвентаре, и метка аирдропа. Увиденное
     * ложится в память о врагах (место примерно, «устаревшее» - не повод сразу драться) и на
     * карту угроз: охотник идёт туда, осторожный обходит, выживальщик держится подальше.
     */
    private void maybeGlanceMap(Player p, int now) {
        if (glanceSlot >= 0 && now >= glanceUntil) {
            readMap(p, now);
            glanceSlot = -1;
            nextInventory = now; // вернуть в руку оружие
            return;
        }
        if (glanceSlot >= 0 || now < nextGlance) return;
        if (target != null && target.visible || !calmGoal() || now < busyUntil || chestOpenAt >= 0 || leap != null
                || !BotNms.onGround(p) || now < craftUntil) return;
        int slot = -1;
        for (int i = 0; i < 36 && slot < 0; i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (it != null && it.getType() == Material.FILLED_MAP) slot = i;
        }
        if (slot < 0) { nextGlance = now + 20 * 30; return; }
        if (!hold(p, slot, now)) return;
        glanceSlot = p.getInventory().getHeldItemSlot();
        glanceUntil = now + 25 + rnd.nextInt(25);
        nextGlance = glanceUntil + (int) (20 * (70 - 50 * persona.mapUse) * (0.7 + rnd.nextDouble() * 0.6));
    }

    /** Глядим на карту - бот держит её в руке. */
    private boolean glancing(int now) {
        return glanceSlot >= 0 && now < glanceUntil;
    }

    private void readMap(Player p, int now) {
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held == null || held.getType() != Material.FILLED_MAP) return; // убрали из руки раньше времени
        Location me = p.getLocation();
        for (Player o : hooks.alivePlayers()) {
            if (o.getUniqueId().equals(id) || hooks.sameTeam(id, o.getUniqueId()) || !o.getWorld().equals(me.getWorld())) continue;
            if (o.getGameMode() != GameMode.SURVIVAL || !o.getInventory().contains(Material.FILLED_MAP)) continue;
            if (o.getLocation().distanceSquared(me) > 500 * 500) continue;
            Location l = o.getLocation().add(rnd.nextGaussian() * 3, 0, rnd.nextGaussian() * 3); // метка на карте неточная
            threat().add(l.getX(), l.getZ(), 1.5, now);
            Contact c = contact(o);
            if (c.visible || now - c.seenTick < 60) continue;
            c.last = l;
            c.seenTick = now - 60; // «давно видел»: повод сходить, но не повод стрелять в стену
        }
    }

    // =====================================================================  крафт

    private int craftUntil = -1, nextCraft;
    private Location craftLook;

    /**
     * Крафт в спокойную минуту: брёвна в доски, когда блоков на столбы мало (из бревна - четыре
     * блока); лишние доски - в лестницы (у верстака: нет рядом - делаем и ставим свой).
     * На крафт уходит время: бот стоит, как игрок с открытым инвентарём.
     */
    private void maybeCraft(Player p, int now) {
        if (now < nextCraft || now < craftUntil) return;
        nextCraft = now + 20 + rnd.nextInt(20);
        if (target != null && target.visible && target.last.distance(p.getLocation()) < 40) return;
        if (!calmGoal() && goal != Goal.CENTER && goal != Goal.CAVE) return;
        if (now < busyUntil || chestOpenAt >= 0 || !BotNms.onGround(p) || glancing(now) || leap != null) return;
        PlayerInventory inv = p.getInventory();
        World w = p.getWorld();
        int blocks = Builder.blockCount(p);
        if (blocks < 40 && Crafting.countTag(inv, true) > 0 && Crafting.planks(w, inv)) {
            craftUntil = now + 10 + rnd.nextInt(10);
            craftLook = null;
            return;
        }
        int planks = Crafting.countTag(inv, false);
        boolean climber = persona.type == Persona.Archetype.TACTICIAN || persona.type == Persona.Archetype.SURVIVOR
            || persona.type == Persona.Archetype.MARKSMAN || pitMode || finale();
        if (Builder.ladderCount(p) >= 4 || planks < (climber ? 8 : 16)) return;
        Block table = nearbyTable(p);
        if (table == null) {
            int ts = -1;
            for (int i = 0; i < 36 && ts < 0; i++) {
                ItemStack it = inv.getItem(i);
                if (it != null && it.getType() == Material.CRAFTING_TABLE && !it.hasItemMeta()) ts = i;
            }
            if (ts < 0) {
                if (planks >= 12 && Crafting.table(w, inv)) craftUntil = now + 10 + rnd.nextInt(8);
                return;
            }
            Block ground = tableSpot(p);
            if (ground != null && builder.useOnFace(p, ground, org.bukkit.block.BlockFace.UP, ts)) {
                note(name + " ставит верстак");
                craftUntil = now + 6;
            }
            return;
        }
        int guard = 0;
        while (Crafting.count(inv, Material.STICK) < 7 && guard++ < 3 && Crafting.sticks(w, inv)) { }
        if (Crafting.ladders(w, inv)) {
            craftUntil = now + 25 + rnd.nextInt(15);
            craftLook = table.getLocation().add(0.5, 1.0, 0.5);
            note(name + " крафтит лестницы (" + Builder.ladderCount(p) + ")");
        }
    }

    private Block nearbyTable(Player p) {
        Location l = p.getLocation();
        World w = p.getWorld();
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = -2; dy <= 2; dy++) {
            Block b = w.getBlockAt(l.getBlockX() + dx, l.getBlockY() + dy, l.getBlockZ() + dz);
            if (b.getType() == Material.CRAFTING_TABLE && b.getLocation().add(0.5, 0.5, 0.5).distance(p.getEyeLocation()) < 4.3) return b;
        }
        return null;
    }

    /** Куда поставить верстак: пол рядом (не под собой), над ним пусто. Возвращает опору. */
    private Block tableSpot(Player p) {
        Location l = p.getLocation();
        World w = p.getWorld();
        int fy = (int) Math.floor(l.getY() + 0.01);
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}}) {
            Block cell = w.getBlockAt(l.getBlockX() + d[0], fy, l.getBlockZ() + d[1]);
            Block floor = cell.getRelative(org.bukkit.block.BlockFace.DOWN);
            // Клетка, в которую заходит тело бота, не годится: сервер туда блок не поставит.
            boolean body = l.getX() + 0.3 > cell.getX() && l.getX() - 0.3 < cell.getX() + 1
                && l.getZ() + 0.3 > cell.getZ() && l.getZ() - 0.3 < cell.getZ() + 1;
            if (!body && cell.getType().isAir() && floor.getType().isSolid() && Builder.inZone(cell)) return floor;
        }
        return null;
    }

    // =====================================================================  лестница по стене

    private int ladderUntil = -1, ladderDx, ladderDz, ladderTop;

    /** Начать подъём на лестницах к стене (dx,dz) до высоты ног top, если лестниц хватает. */
    private boolean startLadder(Player p, int now, int dx, int dz, int top) {
        // Лестница ставится на стену по одной оси.
        dx = Integer.signum(dx);
        dz = dx != 0 ? 0 : Integer.signum(dz);
        if (dx == 0 && dz == 0) return false;
        int need = top - (int) Math.floor(p.getLocation().getY() + 0.01);
        if (need < 2 || need > 24 || Builder.ladderCount(p) < need - 1 || now < ladderBanUntil) return false;
        Location l = p.getLocation();
        if (!p.getWorld().getBlockAt(l.getBlockX() + dx, l.getBlockY(), l.getBlockZ() + dz).getType().isSolid()) return false;
        ladderDx = dx;
        ladderDz = dz;
        ladderTop = top;
        ladderUntil = now + 20 * (6 + need);
        nav.clear();
        note(name + " лезет по стене на лестницах (" + need + ")");
        return true;
    }

    private int ladderBanUntil;

    // =====================================================================  мины, турели, растяжки

    /** Свои мины и растяжки: x, z, до какого тика помним (обходим). */
    private final List<double[]> ownMines = new ArrayList<double[]>();
    private Location gadgetSpot;
    private Gadgets.Type gadgetType;
    private int nextGadget = 20 * 60, gadgetSince;

    /**
     * Выбрать, что и куда поставить на позиции: турель - рядом с собой лицом к подходу,
     * мину или растяжку - в 4-7 блоках по направлению, откуда скорее всего придут.
     */
    private void planGadget(Player p, int now) {
        PlayerInventory inv = p.getInventory();
        Gadgets.Type t = null;
        if (Gadgets.find(inv, Gadgets.Type.TURRET) >= 0) t = Gadgets.Type.TURRET;
        else if (Gadgets.find(inv, Gadgets.Type.MINE) >= 0) t = Gadgets.Type.MINE;
        else if (Gadgets.find(inv, Gadgets.Type.TRAP) >= 0) t = Gadgets.Type.TRAP;
        if (t == null) { nextGadget = now + 20 * 30; return; }
        Location me = p.getLocation();
        World w = p.getWorld();
        // Откуда придут: где жарко, иначе со стороны центра зоны, иначе от сундуков засады.
        double[] hot = threat().hottest(me.getX(), me.getZ(), 80, 0.5, now);
        double dx, dz;
        if (hot != null) { dx = hot[0] - me.getX(); dz = hot[1] - me.getZ(); }
        else if (ambushCenter != null && ambushCenter.getWorld().equals(w)) { dx = ambushCenter.getX() - me.getX(); dz = ambushCenter.getZ() - me.getZ(); }
        else { Location c = w.getWorldBorder().getCenter(); dx = c.getX() - me.getX(); dz = c.getZ() - me.getZ(); }
        double d = Math.hypot(dx, dz);
        if (d < 1) { double a = rnd.nextDouble() * Math.PI * 2; dx = Math.cos(a); dz = Math.sin(a); d = 1; }
        dx /= d; dz /= d;
        double r = t == Gadgets.Type.TURRET ? 1.6 : 4 + rnd.nextDouble() * 3;
        double side = (rnd.nextDouble() - 0.5) * 3;
        int x = (int) Math.floor(me.getX() + dx * r - dz * side), z = (int) Math.floor(me.getZ() + dz * r + dx * side);
        int y = nav.standY(w, x, z, me.getBlockY());
        if (y == Integer.MIN_VALUE || !walkable(w, x, y, z)) { nextGadget = now + 20 * 10; return; }
        gadgetSpot = new Location(w, x + 0.5, y, z + 0.5);
        gadgetType = t;
        gadgetSince = now;
    }

    /** Тактик залутал сундук: иногда оставляет у него мину для следующего гостя. */
    private void maybeTrapChest(Player p, int now) {
        if (now < nextGadget || gadgetSpot != null || rnd.nextDouble() > persona.gadgets * 0.5) return;
        PlayerInventory inv = p.getInventory();
        Gadgets.Type t = Gadgets.find(inv, Gadgets.Type.MINE) >= 0 ? Gadgets.Type.MINE
            : Gadgets.find(inv, Gadgets.Type.TRAP) >= 0 ? Gadgets.Type.TRAP : null;
        if (t == null) return;
        Location me = p.getLocation();
        gadgetSpot = new Location(me.getWorld(), me.getBlockX() + 0.5, Math.floor(me.getY() + 0.01), me.getBlockZ() + 0.5);
        gadgetType = t;
        gadgetSince = now;
    }

    /** Дойти до места и поставить. true - тик занят. */
    private boolean gadgetStep(Player p, int now) {
        if (gadgetSpot == null) return false;
        Location l = p.getLocation();
        if (!gadgetSpot.getWorld().equals(l.getWorld()) || now - gadgetSince > 20 * 12
                || target != null && target.visible && target.last.distance(l) < 18 && goal != Goal.EVADE) {
            gadgetSpot = null;
            return false;
        }
        PlayerInventory inv = p.getInventory();
        int slot = Gadgets.find(inv, gadgetType);
        if (slot < 0) { gadgetSpot = null; return false; }
        boolean under = Gadgets.underSelf(inv.getItem(slot));
        double d = Math.hypot(gadgetSpot.getX() - l.getX(), gadgetSpot.getZ() - l.getZ());
        if (d > (under ? 0.6 : 2.6) || Math.abs(gadgetSpot.getY() - l.getY()) > 1.2) {
            nav.setGoal(gadgetSpot, 0);
            Navigator.Move m = nav.tick(p, now);
            if (!m.active && nav.getFailures() >= 2) { gadgetSpot = null; return false; }
            double mx = m.active ? m.dx : gadgetSpot.getX() - l.getX(), mz = m.active ? m.dz : gadgetSpot.getZ() - l.getZ();
            motor.turn(p, Motor.yawTo(mx, mz), 20f, Math.min(skill.turnSpeed, 20f));
            motor.drive(p, mx, mz, d < 2 ? 0.4 : 1.0, 0f, m.active && m.jump, false);
            return true;
        }
        motor.stop(p);
        if (under) {
            if (!hold(p, slot, now) || now < handReadyAt) return true;
            BotNms.useItem(p, false);
        } else {
            Block ground = l.getWorld().getBlockAt(gadgetSpot.getBlockX(), gadgetSpot.getBlockY() - 1, gadgetSpot.getBlockZ());
            if (!ground.getType().isSolid()) { gadgetSpot = null; return false; }
            if (!builder.useOnFace(p, ground, org.bukkit.block.BlockFace.UP, slot)) {
                if (builder.turning()) return true;
                gadgetSpot = null;
                return false;
            }
        }
        if (gadgetType != Gadgets.Type.TURRET) ownMines.add(new double[]{gadgetSpot.getX(), gadgetSpot.getZ(), now + 20 * 600});
        note(name + " ставит " + (gadgetType == Gadgets.Type.MINE ? "мину" : gadgetType == Gadgets.Type.TURRET ? "турель" : "растяжку")
            + " у " + gadgetSpot.getBlockX() + "," + gadgetSpot.getBlockY() + "," + gadgetSpot.getBlockZ());
        gadgetSpot = null;
        nextGadget = now + 20 * (8 + rnd.nextInt(12));
        busyUntil = now + 6;
        return true;
    }

    // =====================================================================  броня

    private int nextEquip;

    /**
     * Надеть лучшее, что есть: по одной вещи за раз, с паузой, как игрок кликает броню. Плагинная
     * броня (свои атрибуты, «надеваемый» предмет) тоже учитывается. В бою - только заметно
     * лучшую (некогда возиться ради мелочи).
     */
    private void equipArmor(Player p, int now) {
        if (now < busyUntil || kamikaze || chuteSwapSlot >= 0) return;
        boolean fighting = target != null && target.visible && target.last.distance(p.getLocation()) < 25;
        PlayerInventory inv = p.getInventory();
        int best = -1;
        EquipmentSlot bestSlot = null;
        double bestGain = fighting ? 2.0 : 0.3;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            EquipmentSlot slot = Items.armorSlotOf(it);
            if (slot == null || isVest(it)) continue;
            double gain = Items.armorValue(it) - Items.armorValue(inv.getItem(slot));
            if (gain > bestGain) { bestGain = gain; best = i; bestSlot = slot; }
        }
        if (best < 0) return;
        ItemStack cur = inv.getItem(bestSlot);
        if (cur != null && cur.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.BINDING_CURSE) > 0) return; // не снять
        if (isVest(cur) && kamikaze) return;
        ItemStack it = inv.getItem(best);
        inv.setItem(bestSlot, it);
        inv.setItem(best, cur == null || cur.getType().isAir() ? null : cur);
        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.8f, 1f);
        handReadyAt = Math.max(handReadyAt, now + timing.swap());
    }

    // =====================================================================  известные враги

    /**
     * Ближайший враг-игрок, о котором бот знает (видел, слышал, сказали свои, видел на карте)
     * не дольше maxAge тиков назад, в радиусе r. Для дронов и вертолёта: раньше они брали
     * ближайшего врага в 150-190 блоках даже за горами.
     */
    Player knownEnemy(double r, int maxAge) {
        Player p = player();
        if (p == null) return null;
        int now = mgr.now();
        Player best = null;
        double bd = r * r;
        for (Contact c : contacts.values()) {
            if (!(c.entity instanceof Player) || now - c.seenTick > maxAge || c.entity.isDead()) continue;
            Player o = (Player) c.entity;
            if (o.getGameMode() != GameMode.SURVIVAL || !o.getWorld().equals(p.getWorld()) || hooks.sameTeam(id, o.getUniqueId())) continue;
            double d = c.last.distanceSquared(p.getLocation());
            if (d < bd) { bd = d; best = o; }
        }
        return best;
    }

    /** Хаос поменял инвентари местами: всё, что бот помнил о своих вещах, устарело. */
    void onInventorySwapped() {
        for (int i = 0; i < invRaw.length; i++) invRaw[i] = null;
        shareTo = null;
        shareItems.clear();
        chuteSwapSlot = -1;
        glanceSlot = -1;
        gadgetSpot = null;
        Player p = player();
        if (p != null && kamikaze) stopKamikaze(p, mgr.now());
        nextInventory = mgr.now();
    }

    void note(String msg) {
        int now = mgr.now();
        notes.addLast((now / 20) + "с: " + (msg.startsWith(name + " ") ? msg.substring(name.length() + 1) : msg));
        while (notes.size() > 8) notes.removeFirst();
        if (skill.debug) mgr.debug(msg);
    }

    java.util.List<String> notes() { return new java.util.ArrayList<String>(notes); }

    String debug() {
        Player p = player();
        int now = mgr.now();
        String t = target == null ? "-" : target.entity.getName() + (target.visible ? "(v)" : "");
        String modes = (now < escapeUntil ? " ловушка" : "") + (pitMode ? " яма" : "") + (now < climbUntil ? " лестница" : "")
            + (now < wanderUntil ? " свой-путь" : "") + (platformChecks > 0 ? " постройка" + platformChecks : "") + (builder.isMining() ? " копает" : "")
            + (leap != null ? (leap.hook ? " крюк" : " ранец") + (leapPhase == 0 ? "-цель" : "-полёт") : "")
            + (now < typingUntil ? " печатает" : "") + (now < craftUntil ? " крафт" : "") + (now < ladderUntil ? " лестницы" : "")
            + (now < glanceUntil ? " карта" : "") + (gadgetSpot != null ? " ставит-" + gadgetType : "")
            + (now < gatherUntil ? " добыча" + Builder.blockCount(p) + "/" + gatherNeed + (gatherBlock == null ? "" : "@" + gatherBlock.getX() + "," + gatherBlock.getY() + "," + gatherBlock.getZ() + ":" + gatherBlock.getType()) : "");
        return name + " [" + persona.describe() + "] goal=" + goal + " target=" + t + " hp=" + (p == null ? 0 : (int) p.getHealth())
            + " path=" + nav.hasPath() + " reach=" + nav.reaches() + " fails=" + nav.getFailures() + nav.debug() + modes + rides.state()
            + (goal == Goal.CAVE ? " cave=" + caveDigging + "/" + caveDx + "," + caveDz + "/" + caveWhy : "")
            + (nav.getGoal() == null ? "" : " to=" + nav.getGoal().getBlockX() + "," + nav.getGoal().getBlockY() + "," + nav.getGoal().getBlockZ())
            + (p == null ? "" : " use=" + p.isHandRaised() + " busy=" + (busyUntil - mgr.now()) + " held=" + p.getInventory().getItemInMainHand().getType());
    }
}
