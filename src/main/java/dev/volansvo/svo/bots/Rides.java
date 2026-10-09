package dev.volansvo.svo.bots;

import dev.volansvo.svo.bots.nms.BotNms;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.util.*;
import java.util.function.IntPredicate;

/**
 * Поездки бота: техника MilitaryCraft (сесть к союзнику, поставить свою и вести её)
 * и зиплайны VolanZip. Всё делается теми же действиями, что у игрока: ПКМ предметом по
 * блоку (поставить технику), ПКМ по технике (сесть), клавиши W/S и взгляд (руль), присед
 * (выйти), взмах рукой (выстрел из башни танка и пулемёта пикапа), прыжок под тросом.
 *
 * Пока бот идёт к технике или к тросу, путь прокладывает обычный навигатор бота
 * (walkTarget), а когда сидит или едет, управление целиком здесь (tick возвращает true).
 */
final class Rides {

    private enum Mode { NONE, ZIP_WALK, ZIP_JUMP, ZIP_RIDE, CAR_PLACE, CAR_WALK, CAR_DRIVE, CAR_RIDE, TRAIN_WAIT, TRAIN_RIDE }

    /** Ключи PDC частей техники и предметов-установщиков MilitaryCraft (имя ключа -> тип). */
    private static final Map<String, String> PART_KEYS = new HashMap<String, String>();
    private static final Map<String, String> ITEM_KEYS = new HashMap<String, String>();
    static {
        PART_KEYS.put("truck_id", "kamaz");
        PART_KEYS.put("motorcycle_id", "moto");
        PART_KEYS.put("pickup_id", "pickup");
        PART_KEYS.put("jeep_id", "pickup");
        PART_KEYS.put("tank_id", "tank");
        PART_KEYS.put("heli_id", "heli");
        ITEM_KEYS.put("kamaz_item", "kamaz");
        ITEM_KEYS.put("motorcycle_item", "moto");
        ITEM_KEYS.put("pickup_item", "pickup");
        ITEM_KEYS.put("jeep_item", "pickup");
        ITEM_KEYS.put("tank_item", "tank");
        ITEM_KEYS.put("heli_item", "heli");
    }

    private final BotManager mgr;
    private final VolanHooks hooks;
    private final UUID self;
    private final String name;
    private final Motor motor;
    private final IntPredicate hold;
    private final Random rnd = new Random();

    private Mode mode = Mode.NONE;
    private int since;
    private int nextPlan;
    private int banCarsUntil, banPlaceUntil;
    private final Map<Object, Integer> bannedZips = new HashMap<Object, Integer>();

    // зиплайн
    private Object zipKey;
    private Location zipFrom, zipTo, zipStand, zipDest;
    private double zipBest;
    private int jumps;

    // техника
    private String carId, carKind;
    private Entity carPart;
    private boolean wantDriver, gunner, joiningAlly;
    private UUID allyId;
    private Location dest;
    private Location lastPos;
    private int lastPosTick, stuck, reverseUntil, lastShot, placeSlot = -1;
    private final Set<UUID> knownParts = new HashSet<UUID>();

    Rides(BotManager mgr, VolanHooks hooks, UUID self, String name, Motor motor, IntPredicate hold) {
        this.mgr = mgr;
        this.hooks = hooks;
        this.self = self;
        this.name = name;
        this.motor = motor;
        this.hold = hold;
    }

    boolean active() { return mode != Mode.NONE; }

    /** Куда идти пешком (к тросу или к технике), или null. */
    Location walkTarget() {
        if (mode == Mode.ZIP_WALK) return zipStand;
        if (mode == Mode.TRAIN_WAIT && trainStand != null) return trainStand;
        if (mode == Mode.CAR_WALK && carPart != null && carPart.isValid()) return carPart.getLocation();
        return null;
    }

    /** Навигатор не может дойти до троса или техники: бросаем эту затею. */
    void walkFailed(int now) {
        if (mode == Mode.TRAIN_WAIT) { mode = Mode.NONE; banTrainUntil = now + 20 * 40; nextPlan = now + 40; return; }
        if (mode == Mode.ZIP_WALK) banZip(now);
        if (mode == Mode.CAR_WALK) banCarsUntil = now + 20 * 40;
        if (mode == Mode.ZIP_WALK || mode == Mode.CAR_WALK) { mode = Mode.NONE; nextPlan = now + 40; }
    }

    String state() { return mode == Mode.NONE ? "" : " ride=" + mode + (carKind != null ? "/" + carKind : ""); }

    void reset(Player p) {
        if (p != null && p.isInsideVehicle()) dismount(p);
        mode = Mode.NONE;
        carPart = null;
        carId = null;
        knownParts.clear();
    }

    // =====================================================================  планирование

    /**
     * Раз в секунду: стоит ли ехать. travel - куда бот сейчас идёт (null, если не путешествует),
     * danger - бой, бегство, лечение: тогда ни во что не садимся.
     */
    void plan(Player p, int now, Location travel, boolean danger) {
        if (danger && (mode == Mode.ZIP_WALK || mode == Mode.CAR_WALK || mode == Mode.CAR_PLACE || mode == Mode.TRAIN_WAIT)
                && !joiningAlly) { mode = Mode.NONE; return; }
        if (travel != null && mode == Mode.TRAIN_RIDE) dest = travel.clone();
        trackTrains(p, now);
        if (travel != null && (mode == Mode.CAR_DRIVE || mode == Mode.CAR_WALK && wantDriver)) dest = travel.clone();
        if (mode != Mode.NONE || now < nextPlan || danger || p.isInsideVehicle()) return;
        nextPlan = now + 20;
        if (!BotNms.onGround(p) || BotNms.inWater(p)) return;

        // 1. Союзник сидит в технике рядом - подсаживаемся.
        if (now >= banCarsUntil && tryJoinAlly(p, now)) return;

        // 1б. Есть вертолёт (в инвентаре или пустой рядом), а враг в паре сотен блоков -
        // летим бомбить.
        if (now >= banHeliUntil && nearestEnemy(p, 160) != null) {
            Entity heli = nearestEmptyCar(p, 24, "heli");
            if (heli != null) { startCarWalk(heli, true, false, null, null, now); heliMode = true; return; }
            int slot = placerSlot(p, "heli");
            if (slot >= 0) {
                placeSlot = slot;
                knownParts.clear();
                for (Entity e : p.getNearbyEntities(14, 8, 14)) if (vehicleId(e) != null) knownParts.add(e.getUniqueId());
                mode = Mode.CAR_PLACE;
                heliMode = true;
                since = now;
                return;
            }
        }

        if (travel == null || !travel.getWorld().equals(p.getWorld())) return;
        double far = flat(p.getLocation(), travel);
        if (far < 45) return;

        // 2. Зиплайн по пути.
        if (tryZipline(p, now, travel, far)) return;

        // 2б. Поезд идёт в нашу сторону (и не в зону) - ждём его у рельсов впереди и садимся.
        if (far >= 60 && now >= banTrainUntil && tryTrain(p, now, travel)) return;

        if (far < 80 || now < banCarsUntil) return;
        // 3. Пустая техника рядом - садимся за руль.
        Entity part = nearestEmptyCar(p, 24, null);
        if (part != null) {
            startCarWalk(part, true, false, null, travel, now);
            return;
        }
        // 4. Своя техника в инвентаре - ставим.
        if (now >= banPlaceUntil) {
            int slot = placerSlot(p, null);
            if (slot >= 0) {
                placeSlot = slot;
                dest = travel.clone();
                knownParts.clear();
                for (Entity e : p.getNearbyEntities(14, 8, 14)) if (vehicleId(e) != null) knownParts.add(e.getUniqueId());
                mode = Mode.CAR_PLACE;
                since = now;
            }
        }
    }

    // =====================================================================  каждый тик

    /** true - бот сидит/едет/прыгает к тросу, остальное поведение в этот тик не нужно. */
    private int sneakUntil;

    private boolean shootFromSeat;

    /** Сидим пассажиром там, где можно пользоваться своим оружием: стреляем с места. */
    boolean shootFromSeat() { return shootFromSeat; }

    boolean tick(Player p, int now, LivingEntity enemy) {
        shootFromSeat = false;
        if (now < sneakUntil) { BotNms.sneak(p, true); BotNms.input(p, 0f, 0f, false); return true; }
        if (sneakUntil != 0) { BotNms.sneak(p, false); sneakUntil = 0; }
        // Зацепились за трос случайно (прыгнули рядом) - спрыгиваем.
        if (mode == Mode.NONE && !p.hasGravity() && !p.isInsideVehicle()
                && p.getGameMode() == org.bukkit.GameMode.SURVIVAL && !ziplines(p.getWorld(), now).isEmpty()) {
            sneakUntil = now + 3;
            return true;
        }
        // Сидим в поезде (сели сами или посадили).
        if (p.isInsideVehicle() && mode != Mode.TRAIN_RIDE && trainOfSeat(p.getVehicle()) != null) {
            mode = Mode.TRAIN_RIDE;
            trainId = trainOfSeat(p.getVehicle());
            since = now;
            rideLast = p.getLocation();
            rideLastTick = now;
            rideStill = 0;
            rideFar = 0;
            log.accept(name + " едет на поезде" + (dest != null ? " к " + dest.getBlockX() + "," + dest.getBlockZ() : ""));
        }
        // Сел в технику не через нас (или нас посадили) - ведём себя как пассажир.
        if (p.isInsideVehicle() && mode != Mode.CAR_DRIVE && mode != Mode.CAR_RIDE && mode != Mode.TRAIN_RIDE) {
            String id = seatId(p.getVehicle());
            if (id == null) return false; // лодка, вагонетка и т.п. - не наше
            carId = id;
            carKind = kindOf(seatId(p.getVehicle()));
            mode = wantDriver && mode == Mode.CAR_WALK ? Mode.CAR_DRIVE : Mode.CAR_RIDE;
            mgr.teamSay(p, BotChatter.Topic.T_VEHICLE, 0.3);
            since = now;
            lastPos = p.getLocation();
            lastPosTick = now;
            stuck = 0;
        }
        switch (mode) {
            case ZIP_WALK:
                if (now - since > 20 * 25) { banZip(now); mode = Mode.NONE; return false; }
                if (flat(p.getLocation(), zipStand) < 0.7 && BotNms.onGround(p)) { mode = Mode.ZIP_JUMP; since = now; jumps = 0; }
                return false;
            case ZIP_JUMP:
                return zipJump(p, now);
            case ZIP_RIDE:
                return zipRide(p, now);
            case CAR_PLACE:
                return carPlace(p, now);
            case CAR_WALK:
                return carWalk(p, now);
            case CAR_DRIVE:
                return carDrive(p, now, enemy);
            case CAR_RIDE:
                return carRide(p, now, enemy);
            case TRAIN_WAIT:
                return trainWait(p, now);
            case TRAIN_RIDE:
                return trainRide(p, now);
            default:
                return false;
        }
    }

    // =====================================================================  поезд (MilitaryCraft TrainCraft)

    private static final NamespacedKey TRAIN_ID = new NamespacedKey("traincraft", "train_id");
    private static final NamespacedKey CAR_INDEX = new NamespacedKey("traincraft", "car_index");
    /** Где был каждый поезд в прошлый раз: id -> {x, z, тик}. Общая на всех ботов. */
    private static final Map<String, double[]> TRAIN_SEEN = new HashMap<String, double[]>();
    /** Скорость и направление поезда (блоков в секунду по x и z). */
    private static final Map<String, double[]> TRAIN_VEL = new HashMap<String, double[]>();
    private static int trainTrackTick = -100;

    /** Заметки бота (видны в отладке, в консоль - только у ботов с debug). */
    java.util.function.Consumer<String> log = m -> {};

    private String trainId;
    private Location trainStand;
    private int banTrainUntil;
    private Location rideLast;
    private int rideLastTick, rideStill, rideFar;
    private double rideBest = Double.MAX_VALUE;

    /** id поезда, которому принадлежит хитбокс вагона, или null. */
    static String trainOf(Entity e) {
        if (!(e instanceof org.bukkit.entity.Interaction)) return null;
        return e.getPersistentDataContainer().get(TRAIN_ID, PersistentDataType.STRING);
    }

    /** id поезда, если это сиденье поезда (стойка у хитбокса вагона), иначе null. */
    static String trainOfSeat(Entity seat) {
        if (!(seat instanceof org.bukkit.entity.ArmorStand)) return null;
        for (Entity e : seat.getNearbyEntities(2.5, 3, 2.5)) {
            String id = trainOf(e);
            if (id != null) return id;
        }
        return null;
    }

    private static int carIndex(Entity e) {
        Integer i = e.getPersistentDataContainer().get(CAR_INDEX, PersistentDataType.INTEGER);
        return i == null ? -1 : i;
    }

    /** Раз в секунду (на всех ботов одна выборка): где поезда и куда едут. */
    private static void trackTrains(Player p, int now) {
        if (now - trainTrackTick < 10) return;
        double dt = (now - trainTrackTick) / 20.0;
        trainTrackTick = now;
        Map<String, double[]> pos = new HashMap<String, double[]>();
        for (Entity e : p.getWorld().getEntitiesByClass(org.bukkit.entity.Interaction.class)) {
            String id = trainOf(e);
            if (id == null || carIndex(e) != 0) continue;
            pos.put(id, new double[]{e.getLocation().getX(), e.getLocation().getZ()});
        }
        for (Map.Entry<String, double[]> en : pos.entrySet()) {
            double[] was = TRAIN_SEEN.get(en.getKey()), now2 = en.getValue();
            if (was != null && dt > 0 && dt < 3) {
                TRAIN_VEL.put(en.getKey(), new double[]{(now2[0] - was[0]) / dt, (now2[1] - was[1]) / dt});
            }
        }
        TRAIN_SEEN.clear();
        TRAIN_SEEN.putAll(pos);
        TRAIN_VEL.keySet().retainAll(pos.keySet());
    }

    /** Насколько точка далеко от края зоны (меньше нуля - за краем). */
    private static double edgeAt(World w, double x, double z) {
        org.bukkit.WorldBorder wb = w.getWorldBorder();
        return wb.getSize() / 2.0 - Math.max(Math.abs(x - wb.getCenter().getX()), Math.abs(z - wb.getCenter().getZ()));
    }

    /**
     * Поезд рядом едет туда же, куда нам, и не к краю зоны: выбираем рельс впереди него, куда
     * успеваем дойти раньше поезда, и встаём сбоку от путей (на рельсах поезд сбивает).
     */
    private boolean tryTrain(Player p, int now, Location travel) {
        Location me = p.getLocation();
        World w = p.getWorld();
        for (Entity e : p.getNearbyEntities(64, 20, 64)) {
            String id = trainOf(e);
            if (id == null || carIndex(e) != 0) continue;
            double[] v = TRAIN_VEL.get(id);
            if (v == null) continue;
            double speed = Math.hypot(v[0], v[1]);
            if (speed < 3) continue;
            double dx = v[0] / speed, dz = v[1] / speed;
            Location loco = e.getLocation();
            double tx = travel.getX() - loco.getX(), tz = travel.getZ() - loco.getZ(), tl = Math.hypot(tx, tz);
            if (tl < 1 || (dx * tx + dz * tz) / tl < 0.6) continue;      // едет не в нашу сторону
            if (edgeAt(w, loco.getX() + dx * 40, loco.getZ() + dz * 40) < 20) continue; // везёт к зоне
            // Рельс впереди поезда, до которого мы дойдём раньше него (с запасом).
            for (int ahead = 12; ahead <= 64; ahead += 2) {
                double ax = loco.getX() + dx * ahead, az = loco.getZ() + dz * ahead;
                Block rail = findRail(w, ax, loco.getY(), az);
                if (rail == null) continue;
                Location stand = sideOf(w, rail, dx, dz);
                if (stand == null) continue;
                double walk = flat(me, stand) / 4.3 + 1.5, train = ahead / speed;
                if (walk >= train) continue;
                trainId = id;
                trainStand = stand;
                mode = Mode.TRAIN_WAIT;
                since = now;
                dest = travel.clone();
                log.accept(name + " ждёт поезд у " + stand.getBlockX() + "," + stand.getBlockY() + "," + stand.getBlockZ());
                return true;
            }
        }
        return false;
    }

    private static Block findRail(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        for (int r = 0; r <= 1; r++) {
            for (int ox = -r; ox <= r; ox++) {
                for (int oz = -r; oz <= r; oz++) {
                    for (int oy = -2; oy <= 2; oy++) {
                        Block b = w.getBlockAt(bx + ox, by + oy, bz + oz);
                        if (org.bukkit.Tag.RAILS.isTagged(b.getType())) return b;
                    }
                }
            }
        }
        return null;
    }

    /** Клетка в двух блоках сбоку от рельса, где можно стоять (на путях поезд сбивает). */
    private static Location sideOf(World w, Block rail, double dx, double dz) {
        double sx = -dz, sz = dx;
        for (int sign = 1; sign >= -1; sign -= 2) {
            int x = (int) Math.floor(rail.getX() + 0.5 + sx * sign * 2), z = (int) Math.floor(rail.getZ() + 0.5 + sz * sign * 2);
            for (int oy = 1; oy >= -1; oy--) {
                int y = rail.getY() + oy;
                Block feet = w.getBlockAt(x, y, z), head = feet.getRelative(org.bukkit.block.BlockFace.UP), under = feet.getRelative(org.bukkit.block.BlockFace.DOWN);
                if (!feet.isPassable() || !head.isPassable() || !under.getType().isSolid()) continue;
                if (org.bukkit.Tag.RAILS.isTagged(feet.getType()) || feet.isLiquid()) continue;
                return new Location(w, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    /** Стоим у путей: поезд подъезжает - ПКМ по ближайшему вагону. */
    private boolean trainWait(Player p, int now) {
        if (trainId == null || now - since > 20 * 30) { endTrain(now, 20 * 40, "не дождался поезда"); return false; }
        Location me = p.getLocation();
        if (flat(me, trainStand) > 0.9) return false; // ещё идём
        motor.stop(p);
        BotNms.sneak(p, false);
        Entity best = null;
        double bd = 1e9;
        boolean ahead = false;
        double[] v = TRAIN_VEL.get(trainId);
        for (Entity e : p.getNearbyEntities(24, 8, 24)) {
            if (!trainId.equals(trainOf(e))) continue;
            Location el = e.getLocation();
            double d = flat(me, el);
            if (v != null && ((el.getX() - me.getX()) * -v[0] + (el.getZ() - me.getZ()) * -v[1]) > 0) ahead = true; // ещё не доехал
            if (d < bd) { bd = d; best = e; }
        }
        if (best == null) { endTrain(now, 20 * 40, "поезд пропал"); return false; }
        Location bl = best.getLocation();
        motor.turn(p, Motor.yawTo(bl.getX() - me.getX(), bl.getZ() - me.getZ()), 10f, 40f);
        // Хитбокс вагона широкий (3.4): с двух блоков от оси до него рукой подать.
        if (bd < 3.6) BotNms.interact(p, best);
        if (p.isInsideVehicle()) return true;
        if (!ahead && bd > 8 && now - since > 40) { endTrain(now, 20 * 40, "поезд проехал"); return false; }
        return true;
    }

    /**
     * Едем. Сходим (шифт), когда поезд везёт к краю зоны, встал, или мы проехали нужное место.
     */
    private boolean trainRide(Player p, int now) {
        if (!p.isInsideVehicle()) { mode = Mode.NONE; nextPlan = now + 40; return false; }
        if (now - rideLastTick < 10) return true;
        Location me = p.getLocation();
        World w = p.getWorld();
        double dt = (now - rideLastTick) / 20.0;
        double vx = (me.getX() - rideLast.getX()) / dt, vz = (me.getZ() - rideLast.getZ()) / dt;
        rideLast = me;
        rideLastTick = now;
        double sp = Math.hypot(vx, vz);
        rideStill = sp < 0.5 ? rideStill + 1 : 0;
        String why = null;
        double edge = edgeAt(w, me.getX(), me.getZ()), soon = edgeAt(w, me.getX() + vx * 4, me.getZ() + vz * 4);
        if (soon < 8 || edge < 40 && soon < edge - 2.5 && soon < 30) why = "поезд везёт в зону (до края " + (int) edge + ")";
        else if (rideStill >= 4) why = "поезд встал";
        else if (dest != null && dest.getWorld().equals(w)) {
            double d = flat(me, dest);
            if (d < 20) why = "приехал";
            else {
                if (d < rideBest - 0.5) { rideBest = d; rideFar = 0; }
                else if (++rideFar >= 3 && d < 80) why = "проехал нужное место";
            }
        }
        if (why == null && p.getHealth() < 8 && p.getNoDamageTicks() > 0) why = "в поезде подстрелили";
        if (why != null) {
            log.accept(name + " сходит с поезда: " + why);
            dismount(p);
            endTrain(now, 20 * 30, null);
        }
        return true;
    }

    private void endTrain(int now, int ban, String why) {
        if (why != null) log.accept(name + " " + why);
        mode = Mode.NONE;
        trainStand = null;
        rideBest = Double.MAX_VALUE;
        banTrainUntil = now + ban;
        nextPlan = now + 40;
    }

    // =====================================================================  зиплайн

    private boolean tryZipline(Player p, int now, Location travel, double far) {
        List<double[]> lines = ziplines(p.getWorld(), now);
        if (lines.isEmpty()) return false;
        Location me = p.getLocation();
        double best = far * 0.75;
        double[] bestLine = null;
        Location bestStand = null, bestFrom = null, bestTo = null;
        for (double[] z : lines) {
            Object key = Arrays.toString(z);
            Integer ban = bannedZips.get(key);
            if (ban != null && now < ban) continue;
            Location a = new Location(p.getWorld(), z[0], z[1], z[2]);
            Location b = new Location(p.getWorld(), z[3], z[4], z[5]);
            // Едем к тому концу, что ближе к цели.
            Location s = flat(a, travel) > flat(b, travel) ? a : b;
            Location e = s == a ? b : a;
            double len = s.distance(e);
            if (len < 12) continue;
            Vector dir = e.toVector().subtract(s.toVector()).multiply(1.0 / len);
            for (double t = 1; t < len * 0.5; t += 1.0) {
                Location pt = s.clone().add(dir.clone().multiply(t));
                Location stand = standUnder(pt);
                if (stand == null) continue;
                double walk = flat(me, stand);
                if (walk > 50 || Math.abs(stand.getY() - me.getY()) > 6) continue;
                double cost = walk + (len - t) / 3.0 + flat(e, travel);
                if (cost < best) { best = cost; bestLine = z; bestStand = stand; bestFrom = s; bestTo = e; }
                break; // ближайшая к началу точка посадки этой линии
            }
        }
        if (bestLine == null) return false;
        zipKey = Arrays.toString(bestLine);
        zipStand = bestStand;
        zipFrom = bestFrom;
        zipTo = bestTo;
        zipDest = travel.clone();
        zipBest = 1e9;
        mode = Mode.ZIP_WALK;
        since = now;
        if (mgr.skill().debug) log.accept(name + " идёт к зиплайну " + zipKey);
        return true;
    }

    /** Точка на земле под тросом, откуда прыжком достаём до него (глаза в 2 блоках). */
    private static Location standUnder(Location rope) {
        World w = rope.getWorld();
        int x = rope.getBlockX(), z = rope.getBlockZ();
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
        for (int y = (int) Math.floor(rope.getY() - 1.2); y >= (int) Math.floor(rope.getY() - 4.5); y--) {
            Block b = w.getBlockAt(x, y, z);
            if (!b.getType().isSolid()) continue;
            Block f = b.getRelative(0, 1, 0), h = b.getRelative(0, 2, 0);
            if (f.getType().isSolid() || h.getType().isSolid() || f.isLiquid()) return null;
            double feet = y + 1;
            double rel = rope.getY() - feet;
            if (rel < 1.0 || rel > 3.4) return null;
            return new Location(w, x + 0.5, feet, z + 0.5);
        }
        return null;
    }

    private boolean zipJump(Player p, int now) {
        lookAlongRope(p);
        if (!p.hasGravity()) {
            mode = Mode.ZIP_RIDE; since = now; BotNms.input(p, 0f, 0f, false);
            mgr.chat(p, BotChatter.Topic.ZIPLINE, 0.25, null, null);
            return true;
        }
        if (now - since > 12) {
            if (++jumps > 3) { banZip(now); mode = Mode.NONE; BotNms.input(p, 0f, 0f, false); return false; }
            since = now;
        }
        BotNms.sprint(p, false);
        BotNms.input(p, 0f, 0f, BotNms.onGround(p) && now - since < 3);
        return true;
    }

    private boolean zipRide(Player p, int now) {
        BotNms.input(p, 0f, 0f, false);
        if (p.hasGravity()) { // трос нас отпустил (доехали или сорвались)
            mode = Mode.NONE;
            nextPlan = now + 60;
            return false;
        }
        double len = zipFrom.distance(zipTo);
        if (now - since > len / 0.6 + 60) { // что-то не так - спрыгиваем
            sneakUntil = now + 3; // зиплайн видит присед на своём тике: держим пару тиков
            banZip(now);
            mode = Mode.NONE;
            return false;
        }
        // Цель посередине троса: проехали ближайшую к ней точку - спрыгиваем.
        double toDest = flat(p.getLocation(), zipDest);
        zipBest = Math.min(zipBest, toDest);
        if (toDest < 5 || toDest > zipBest + 3) {
            sneakUntil = now + 3;
            mode = Mode.NONE;
            nextPlan = now + 60;
            return true;
        }
        lookAlongRope(p);
        return true;
    }

    private void lookAlongRope(Player p) {
        Vector d = zipTo.toVector().subtract(zipFrom.toVector());
        float yaw = Motor.yawTo(d.getX(), d.getZ());
        float pitch = Motor.pitchTo(d.getX(), d.getY(), d.getZ());
        BotNms.look(p, yaw, pitch);
        motor.sync(p);
    }

    private void banZip(int now) {
        if (zipKey != null) bannedZips.put(zipKey, now + 20 * 90);
    }

    /** Тросы VolanZip в этом мире: {ax,ay,az,bx,by,bz} (центры креплений). Кэш на 5 сек. */
    private static List<double[]> zipCache = Collections.emptyList();
    private static String zipCacheWorld;
    private static int zipCacheTick = -1000;

    private static List<double[]> ziplines(World w, int now) {
        if (now - zipCacheTick < 100 && w.getName().equals(zipCacheWorld)) return zipCache;
        zipCacheTick = now;
        zipCacheWorld = w.getName();
        List<double[]> out = new ArrayList<double[]>();
        Plugin pl = Bukkit.getPluginManager().getPlugin("VolanZip");
        if (pl != null && pl.isEnabled()) {
            try {
                Object manager = field(pl, "manager");
                Map<?, ?> map = (Map<?, ?>) field(manager, "ziplines");
                for (Object z : map.values()) {
                    if (!w.getName().equals(field(z, "world"))) continue;
                    out.add(new double[]{
                        (Integer) field(z, "ax") + 0.5, (Integer) field(z, "ay") + 0.5, (Integer) field(z, "az") + 0.5,
                        (Integer) field(z, "bx") + 0.5, (Integer) field(z, "by") + 0.5, (Integer) field(z, "bz") + 0.5});
                }
            } catch (Throwable ignored) {}
        }
        zipCache = out;
        return out;
    }

    private static Object field(Object o, String name) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    // =====================================================================  техника: посадка

    private boolean tryJoinAlly(Player p, int now) {
        for (Player o : hooks.alivePlayers()) {
            if (o.getUniqueId().equals(self) || !hooks.sameTeam(self, o.getUniqueId())) continue;
            if (!o.getWorld().equals(p.getWorld()) || !o.isInsideVehicle()) continue;
            Entity v = o.getVehicle();
            String id = seatId(v);
            if (id == null) continue;
            String kind = kindOf(id);
            if ("tank".equals(kind)) continue; // в танке только водитель
            if (o.getLocation().distance(p.getLocation()) > 30) continue;
            Entity part = choosePart(p, id, kind, true);
            if (part == null) continue;
            startCarWalk(part, false, true, o.getUniqueId(), null, now);
            if (mgr.skill().debug) log.accept(name + " садится в " + kind + " к " + o.getName());
            return true;
        }
        return false;
    }

    private void startCarWalk(Entity part, boolean driver, boolean ally, UUID allyUuid, Location travel, int now) {
        carPart = part;
        carId = vehicleId(part);
        carKind = vehicleKind(part);
        wantDriver = driver;
        joiningAlly = ally;
        allyId = allyUuid;
        gunner = false;
        if (travel != null) dest = travel.clone();
        mode = Mode.CAR_WALK;
        since = now;
    }

    private boolean carWalk(Player p, int now) {
        if (carPart == null || !carPart.isValid() || now - since > 20 * 20) {
            mode = Mode.NONE;
            banCarsUntil = now + 20 * 30;
            return false;
        }
        if (joiningAlly) {
            Player ally = allyId == null ? null : Bukkit.getPlayer(allyId);
            if (ally == null || !ally.isInsideVehicle() || !carId.equals(seatId(ally.getVehicle()))) { mode = Mode.NONE; return false; }
        }
        Location me = p.getLocation();
        if (me.distance(carPart.getLocation()) > 3.0) return false; // идём
        // Пикап: место пулемётчика (часть 1) лучше всего - можно стрелять.
        motor.stop(p);
        Location pl = carPart.getLocation();
        motor.turn(p, Motor.yawTo(pl.getX() - me.getX(), pl.getZ() - me.getZ()), 20f, 40f);
        if ((now - since) % 10 == 0) {
            gunner = "pickup".equals(carKind) && partIndex(carPart) == 1;
            BotNms.sneak(p, false);
            BotNms.interact(p, carPart);
            // Не вышло (место занято) - пробуем другую часть.
            if ((now - since) >= 20 && !p.isInsideVehicle()) {
                Entity other = choosePart(p, carId, carKind, joiningAlly);
                if (other != null && !other.equals(carPart)) carPart = other;
            }
        }
        return true;
    }

    /** Часть техники для клика: у пикапа пулемёт (1), иначе любая ближайшая не-пулемётная. */
    private Entity choosePart(Player p, String id, String kind, boolean asPassenger) {
        Entity best = null, gun = null;
        double bd = 1e9;
        for (Entity e : p.getWorld().getNearbyEntities(p.getLocation(), 40, 16, 40)) {
            if (!id.equals(vehicleId(e))) continue;
            int idx = partIndex(e);
            if ("pickup".equals(kind) && idx == 1) {
                if (!seatTaken(e)) gun = e;
                continue;
            }
            double d = e.getLocation().distanceSquared(p.getLocation());
            if (d < bd) { bd = d; best = e; }
        }
        if (asPassenger && gun != null) return gun;
        return best != null ? best : gun;
    }

    private static boolean seatTaken(Entity e) {
        return !e.getPassengers().isEmpty();
    }

    private Entity nearestEmptyCar(Player p, double r, String onlyKind) {
        Map<String, Entity> nearest = new HashMap<String, Entity>();
        Set<String> occupied = new HashSet<String>();
        for (Player o : p.getWorld().getPlayers()) {
            if (o.isInsideVehicle()) { String id = seatId(o.getVehicle()); if (id != null) occupied.add(id); }
        }
        Location me = p.getLocation();
        Entity best = null;
        double bd = r * r;
        for (Entity e : p.getNearbyEntities(r, 8, r)) {
            String id = vehicleId(e);
            if (id == null || occupied.contains(id)) continue;
            String kind = kindOf(id);
            if (onlyKind != null ? !onlyKind.equals(kind) : "heli".equals(kind)) continue;
            if ("pickup".equals(vehicleKind(e)) && partIndex(e) == 1) continue;
            if (e.getLocation().getBlock().isLiquid()) continue;
            double d = e.getLocation().distanceSquared(me);
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }

    // =====================================================================  техника: установка

    private boolean carPlace(Player p, int now) {
        if (now - since > 60) { mode = Mode.NONE; banPlaceUntil = now + 20 * 90; return false; }
        int t = now - since;
        if (t == 0 || t == 20) {
            // Новая техника появилась - садимся.
        }
        Entity fresh = null;
        for (Entity e : p.getNearbyEntities(14, 8, 14)) {
            if (vehicleId(e) != null && !knownParts.contains(e.getUniqueId())) { fresh = e; break; }
        }
        if (fresh != null) {
            Entity part = choosePart(p, vehicleId(fresh), vehicleKind(fresh), false);
            startCarWalk(part != null ? part : fresh, true, false, null, null, now);
            return true;
        }
        if (t % 15 != 2) { motor.stop(p); return true; }
        if (placeSlot < 0 || placeSlot >= 36 || vehicleItem(p.getInventory().getItem(placeSlot)) == null) placeSlot = placerSlot(p, heliMode ? "heli" : null);
        if (placeSlot < 0 || !hold.test(placeSlot)) { mode = Mode.NONE; return false; }
        Block spot = placeSpot(p);
        if (spot == null) { mode = Mode.NONE; banPlaceUntil = now + 20 * 30; return false; }
        Location eye = p.getEyeLocation();
        double cx = spot.getX() + 0.5, cy = spot.getY() + 1.0, cz = spot.getZ() + 0.5;
        BotNms.look(p, Motor.yawTo(cx - eye.getX(), cz - eye.getZ()), Motor.pitchTo(cx - eye.getX(), cy - eye.getY(), cz - eye.getZ()));
        motor.sync(p);
        BotNms.useItemOn(p, spot.getX(), spot.getY(), spot.getZ(), 1);
        if (mgr.skill().debug) log.accept(name + " ставит технику у " + spot.getX() + "," + spot.getY() + "," + spot.getZ());
        return true;
    }

    /**
     * Место под технику перед ботом: под четырьмя колёсами твёрдо, над всем корпусом
     * (7 в длину по взгляду, 3 в ширину) два блока воздуха. Корпус встаёт по направлению
     * взгляда, поэтому ищем по нескольким направлениям, начиная с направления к цели.
     */
    private Block placeSpot(Player p) {
        Location me = p.getLocation();
        Vector f = dest != null ? dest.toVector().subtract(me.toVector()).setY(0) : me.getDirection().setY(0);
        if (f.lengthSquared() < 1e-6) f = new Vector(1, 0, 0);
        f.normalize();
        World w = p.getWorld();
        int[] angles = {0, 30, -30, 60, -60, 90, -90, 135, -135, 180};
        for (int ang : angles) {
            Vector d = f.clone().rotateAroundY(Math.toRadians(ang));
            Vector side = new Vector(-d.getZ(), 0, d.getX());
            for (double ahead = 4.0; ahead >= 3.0; ahead -= 0.5) {
                double cx = me.getX() + d.getX() * ahead, cz = me.getZ() + d.getZ() * ahead;
                for (int gy = me.getBlockY() - 1; gy >= me.getBlockY() - 2; gy--) {
                    Block ground = w.getBlockAt((int) Math.floor(cx), gy, (int) Math.floor(cz));
                    if (!ground.getType().isSolid()) continue;
                    if (fits(w, cx, gy, cz, d, side)) return ground;
                    break;
                }
            }
        }
        return null;
    }

    private static boolean fits(World w, double cx, int gy, double cz, Vector d, Vector side) {
        // колёса
        for (double a : new double[]{-2.4, 2.4}) {
            for (double s : new double[]{-1.1, 1.1}) {
                double x = cx + d.getX() * a + side.getX() * s, z = cz + d.getZ() * a + side.getZ() * s;
                if (!w.getBlockAt((int) Math.floor(x), gy, (int) Math.floor(z)).getType().isSolid()) return false;
            }
        }
        // корпус
        for (double a = -3.2; a <= 3.21; a += 0.8) {
            for (double s = -1.3; s <= 1.31; s += 0.65) {
                double x = cx + d.getX() * a + side.getX() * s, z = cz + d.getZ() * a + side.getZ() * s;
                int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
                if (w.getBlockAt(bx, gy + 1, bz).getType().isSolid() || w.getBlockAt(bx, gy + 2, bz).getType().isSolid()) return false;
            }
        }
        return true;
    }

    private int placerSlot(Player p, String onlyKind) {
        for (int i = 0; i < 36; i++) {
            String k = vehicleItem(p.getInventory().getItem(i));
            if (k == null) continue;
            if (onlyKind != null ? onlyKind.equals(k) : !"heli".equals(k)) return i;
        }
        return -1;
    }

    // =====================================================================  техника: езда

    private boolean carDrive(Player p, int now, LivingEntity enemy) {
        if (!p.isInsideVehicle()) { endCar(now, 20 * 5); heliMode = false; return false; }
        if ("heli".equals(carKind)) return heliFly(p, now);
        Location me = p.getLocation();
        if (BotNms.inWater(p) || me.getBlock().isLiquid()) { dismount(p); endCar(now, 20 * 30); return true; }
        boolean tank = "tank".equals(carKind);
        // Враг рядом: из танка стреляем, из остального выходим драться.
        if (enemy != null && enemy.getWorld().equals(me.getWorld()) && enemy.getLocation().distance(me) < (tank ? 70 : 30)) {
            if (!tank) { BotNms.keys(p, false, false, false, false, false, false); dismount(p); endCar(now, 20 * 20); return true; }
            BotNms.keys(p, false, false, false, false, false, false);
            Location eye = p.getEyeLocation(), t = enemy.getLocation().add(0, 1, 0);
            motor.turn(p, Motor.yawTo(t.getX() - eye.getX(), t.getZ() - eye.getZ()),
                Motor.pitchTo(t.getX() - eye.getX(), t.getY() - eye.getY(), t.getZ() - eye.getZ()), 12f);
            if (now - lastShot > 30 && p.hasLineOfSight(enemy)) { BotNms.swing(p); lastShot = now; }
            return true;
        }
        if (dest == null || !dest.getWorld().equals(me.getWorld())) { BotNms.keys(p, false, false, false, false, false, false); dismount(p); endCar(now, 20 * 10); return true; }
        double d = flat(me, dest);
        if (d < 14) {
            BotNms.keys(p, false, true, false, false, false, false); // тормоз
            if (moved(p, now) < 0.3) { BotNms.keys(p, false, false, false, false, false, false); dismount(p); endCar(now, 20 * 15); }
            return true;
        }
        float yaw = Motor.yawTo(dest.getX() - me.getX(), dest.getZ() - me.getZ());
        if (now < reverseUntil) {
            motor.turn(p, yaw + (stuck % 2 == 0 ? 75f : -75f), 5f, 10f);
            BotNms.keys(p, false, true, false, false, false, false);
            return true;
        }
        motor.turn(p, yaw, 5f, 9f);
        BotNms.keys(p, true, false, false, false, false, false);
        // Упёрлись: сдаём назад с поворотом, после трёх раз выходим.
        if (now - lastPosTick >= 50) {
            double mv = lastPos == null || !lastPos.getWorld().equals(me.getWorld()) ? 99 : flat(lastPos, me);
            lastPos = me.clone();
            lastPosTick = now;
            if (now - since > 60 && mv < 2.0) {
                if (++stuck >= 3) { BotNms.keys(p, false, false, false, false, false, false); dismount(p); endCar(now, 20 * 60); return true; }
                reverseUntil = now + 25;
            }
        }
        return true;
    }

    private boolean carRide(Player p, int now, LivingEntity enemy) {
        if (!p.isInsideVehicle()) { endCar(now, 20 * 5); return false; }
        Location me = p.getLocation();
        boolean driverIn = false;
        for (Player o : p.getWorld().getPlayers()) {
            if (o == p || !o.isInsideVehicle() || !carId.equals(seatId(o.getVehicle()))) continue;
            if (hooks.sameTeam(self, o.getUniqueId())) driverIn = true;
        }
        // Союзник вышел - выходим и мы.
        if (!driverIn && now - since > 20) { dismount(p); endCar(now, 20 * 20); return true; }
        if (enemy != null && enemy.getWorld().equals(me.getWorld())) {
            double d = enemy.getLocation().distance(me);
            if (gunner && d < 70) {
                Location eye = p.getEyeLocation(), t = enemy.getLocation().add(0, 1, 0);
                motor.turn(p, Motor.yawTo(t.getX() - eye.getX(), t.getZ() - eye.getZ()),
                    Motor.pitchTo(t.getX() - eye.getX(), t.getY() - eye.getY(), t.getZ() - eye.getZ()), 14f);
                if (now - lastShot >= 3 && p.hasLineOfSight(enemy)) { BotNms.swing(p); lastShot = now; }
                return true;
            }
            // Камаз, мото, вертолёт: пассажиру своё оружие не запрещено - стреляем с сиденья.
            if (!gunner && d < 50 && ("kamaz".equals(carKind) || "moto".equals(carKind) || "heli".equals(carKind))
                    && p.hasLineOfSight(enemy)) {
                shootFromSeat = true;
                return false;
            }
            if (!gunner && d < 12 && moved(p, now) < 0.2) { dismount(p); endCar(now, 20 * 20); return true; }
        }
        return true;
    }

    /** Сдвиг за последние ~10 тиков. */
    private Location speedPos;
    private int speedTick;
    private double lastMoved = 1;

    private double moved(Player p, int now) {
        Location me = p.getLocation();
        if (speedPos == null || now - speedTick >= 10) {
            lastMoved = speedPos == null || !speedPos.getWorld().equals(me.getWorld()) ? 1 : speedPos.distance(me);
            speedPos = me.clone();
            speedTick = now;
        }
        return lastMoved;
    }

    // =====================================================================  вертолёт

    private boolean heliMode, landing, heliPushed;
    private float heliCmdYaw;
    private int heliAlignUntil, heliClimbUntil, heliLastTick;
    private Location heliLastPos;
    private int banHeliUntil, lastBomb, lastRocket;

    /**
     * Полёт на вертолёте MilitaryCraft: взгляд по курсу (вверх - набор высоты, вниз -
     * снижение), W - вперёд. Над врагом ПКМ сбрасывает бомбу, издалека ЛКМ пускает ракету.
     * Через полторы минуты или без врагов садимся и выходим.
     */
    private boolean heliFly(Player p, int now) {
        Location me = p.getLocation();
        World w = me.getWorld();
        double ground = w.getHighestBlockYAt(me);
        Player tgt = landing ? null : nearestEnemy(p, 180);
        if (!landing && (tgt == null || now - since > 20 * 90)) landing = true;
        if (landing) {
            // Садимся: нос вниз, без газа, у земли - выходим.
            BotNms.keys(p, false, false, false, false, false, false);
            BotNms.look(p, motor.yaw(), 50f);
            motor.sync(p);
            if (me.getY() - ground < 2.6) {
                dismount(p);
                landing = false;
                heliMode = false;
                endCar(now, 20 * 20);
                banHeliUntil = now + 20 * 120;
            }
            return true;
        }
        Location t = tgt.getLocation();
        double gT = w.isChunkLoaded(t.getBlockX() >> 4, t.getBlockZ() >> 4) ? w.getHighestBlockYAt(t) : t.getY();
        boolean targetAirborne = t.getY() - gT > 4;   // враг сам в воздухе - бомбы бесполезны
        // Высота: на 16 над целью, но не выше 40 над землёй и не выше потолка (выше вертолёт горит).
        double wantY = Math.min(Math.max(Math.max(t.getY(), gT), ground) + 16, Math.min(ground + 40, 225));
        double dy = wantY - me.getY();
        double dx = t.getX() - me.getX(), dz = t.getZ() - me.getZ();
        double flat = Math.hypot(dx, dz);
        float yaw = Motor.yawTo(dx, dz);
        // Ракета: враг в 12..60 блоках и виден - на тик смотрим на него и жмём ЛКМ.
        if (flat > 12 && flat < 60 && now - lastRocket > 30 && p.hasLineOfSight(tgt)) {
            Location eye = p.getEyeLocation(), te = tgt.getLocation().add(0, 1, 0);
            BotNms.look(p, Motor.yawTo(te.getX() - eye.getX(), te.getZ() - eye.getZ()),
                Motor.pitchTo(te.getX() - eye.getX(), te.getY() - eye.getY(), te.getZ() - eye.getZ()));
            motor.sync(p);
            BotNms.swing(p);
            lastRocket = now;
            return true;
        }
        // Корпус вертолёта поворачивается медленно (около 3.6° за тик): после смены курса
        // ждём, пока развернётся, иначе полетим туда, куда он смотрел.
        if (Math.abs(Motor.wrap(yaw - heliCmdYaw)) > 25) { heliCmdYaw = yaw; heliAlignUntil = now + 45; }
        boolean low = me.getY() - ground < 7;            // сначала вертикальный взлёт
        // Упёрлись (не движемся, хотя жмём вперёд) - набираем высоту.
        if (heliLastPos == null || now - heliLastTick >= 30) {
            boolean stuck = heliLastPos != null && heliPushed && heliLastPos.getWorld().equals(me.getWorld()) && heliLastPos.distance(me) < 1.5;
            if (stuck) heliClimbUntil = now + 40;
            heliLastPos = me.clone();
            heliLastTick = now;
        }
        boolean climb = low || now < heliClimbUntil;
        // Высота взглядом: смотрим вверх - набор, вниз - снижение.
        float pitch = climb ? -45f : (float) Math.max(-45, Math.min(45, -dy * 4));
        BotNms.look(p, yaw, pitch);
        motor.sync(p);
        boolean fwd = flat > 3.5 && !climb && now >= heliAlignUntil;
        heliPushed = fwd;
        BotNms.keys(p, fwd, false, false, false, climb || dy > 8, false);
        // Над целью - бомба (ПКМ).
        if (!targetAirborne && flat < 3.5 && me.getY() > t.getY() + 5 && now - lastBomb > 40) {
            BotNms.useItem(p, false);
            lastBomb = now;
            mgr.chat(p, BotChatter.Topic.DRONE, 0.15, null, null);
        }
        return true;
    }

    private Player nearestEnemy(Player p, double r) {
        Player best = null;
        double bd = r * r;
        for (Player o : hooks.alivePlayers()) {
            if (o.getUniqueId().equals(self) || hooks.sameTeam(self, o.getUniqueId())) continue;
            if (!o.getWorld().equals(p.getWorld()) || o.getGameMode() != org.bukkit.GameMode.SURVIVAL) continue;
            double d = o.getLocation().distanceSquared(p.getLocation());
            if (d < bd) { bd = d; best = o; }
        }
        return best;
    }

    private void endCar(int now, int ban) {
        mode = Mode.NONE;
        carPart = null;
        banCarsUntil = now + ban;
        nextPlan = now + 40;
    }

    private void dismount(Player p) {
        BotNms.keys(p, false, false, false, false, false, true);
        Bukkit.getScheduler().runTaskLater(Bukkit.getPluginManager().getPlugin("VolanSVO"), () -> {
            if (p.isOnline()) {
                BotNms.keys(p, false, false, false, false, false, false);
                if (p.isInsideVehicle()) p.leaveVehicle();
            }
        }, 3L);
    }

    // =====================================================================  PDC MilitaryCraft

    static String vehicleId(Entity e) {
        if (e == null) return null;
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        for (NamespacedKey k : pdc.getKeys()) {
            String type = PART_KEYS.get(k.getKey());
            if (type == null) continue;
            String v = readAny(pdc, k);
            if (v != null) return type + ":" + v;
        }
        return null;
    }

    /** id техники, в которой сиденье: у самого сиденья или у ближайшей части рядом с ним. */
    static String seatId(Entity seat) {
        if (seat == null) return null;
        String id = vehicleId(seat);
        if (id != null) return id;
        for (Entity e : seat.getNearbyEntities(3, 3, 3)) {
            id = vehicleId(e);
            if (id != null) return id;
        }
        return null;
    }

    static String kindOf(String id) {
        return id == null ? null : id.substring(0, id.indexOf(':'));
    }

    static String vehicleKind(Entity e) {
        String id = vehicleId(e);
        return id == null ? null : id.substring(0, id.indexOf(':'));
    }

    static String vehicleItem(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        PersistentDataContainer pdc = it.getItemMeta().getPersistentDataContainer();
        for (NamespacedKey k : pdc.getKeys()) {
            String type = ITEM_KEYS.get(k.getKey());
            if (type != null) return type;
        }
        return null;
    }

    private static int partIndex(Entity e) {
        for (NamespacedKey k : e.getPersistentDataContainer().getKeys()) {
            if (!k.getKey().equals("part_index")) continue;
            try {
                Integer i = e.getPersistentDataContainer().get(k, PersistentDataType.INTEGER);
                return i == null ? -1 : i;
            } catch (Throwable ignored) {}
        }
        return -1;
    }

    private static String readAny(PersistentDataContainer pdc, NamespacedKey k) {
        try { String s = pdc.get(k, PersistentDataType.STRING); if (s != null) return s; } catch (Throwable ignored) {}
        try { Integer i = pdc.get(k, PersistentDataType.INTEGER); if (i != null) return String.valueOf(i); } catch (Throwable ignored) {}
        try { Long l = pdc.get(k, PersistentDataType.LONG); if (l != null) return String.valueOf(l); } catch (Throwable ignored) {}
        return "?";
    }

    private static double flat(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    @SuppressWarnings("unused")
    private static boolean isAir(Material m) { return m.isAir(); }
}
