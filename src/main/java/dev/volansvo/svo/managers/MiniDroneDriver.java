package dev.volansvo.svo.managers;

import dev.volansvo.svo.VolanSVO;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Objective;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Полёт дрона-самонаводки (предмет ExecutableItems "minidrone").
 *
 * Раньше предмет вёл дрон сам: LOOP_START 2000 с DELAYTICK 1 внутри. SCore превращает
 * такой цикл в десятки тысяч отложенных команд и каждую ищет в общем списке перебором,
 * поэтому несколько дронов разом роняли тик до сотен миллисекунд. Теперь предмет
 * запускает дрон и вызывает /asvominidrone <игрок>, а весь полёт (развёртывание, поиск
 * пути, «сбили», «врезался») считает этот класс за пару проверок блоков на дрон в тик.
 * Теги и счёт mdrone те же, что ставил предмет.
 */
public final class MiniDroneDriver extends BukkitRunnable implements CommandExecutor {

    private static final int DEPLOY_TICKS = 200;
    private static final int HUNT_TICKS = 2000;

    private final VolanSVO plugin;
    private final List<Drone> drones = new ArrayList<Drone>();

    private static final class Drone {
        final String owner;
        final UUID stand;
        UUID box, mark;
        int age;
        boolean hunting, boomSaid;
        Drone(String owner, UUID stand, UUID box) { this.owner = owner; this.stand = stand; this.box = box; }
    }

    public MiniDroneDriver(VolanSVO plugin) {
        this.plugin = plugin;
    }

    // ================================================================== запуск из предмета

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (s instanceof Player && !s.isOp()) return true; // вызывает предмет от консоли
        if (a.length < 1) return true;
        Player owner = Bukkit.getPlayerExact(a[0]);
        if (owner == null) return true;
        String n = owner.getName();
        ArmorStand stand = null;
        LivingEntity box = null;
        for (Entity e : owner.getNearbyEntities(4, 4, 4)) {
            if (e instanceof ArmorStand && e.getScoreboardTags().contains(n + "mdrone")) stand = (ArmorStand) e;
            else if (e instanceof LivingEntity && e.getScoreboardTags().contains(n + "mdrbox")) box = (LivingEntity) e;
        }
        if (stand == null) return true;
        for (Iterator<Drone> it = drones.iterator(); it.hasNext(); ) {
            Drone d = it.next();
            if (d.stand.equals(stand.getUniqueId())) it.remove();
        }
        drones.add(new Drone(n, stand.getUniqueId(), box == null ? null : box.getUniqueId()));
        return true;
    }

    // ================================================================== каждый тик

    @Override
    public void run() {
        for (Iterator<Drone> it = drones.iterator(); it.hasNext(); ) {
            Drone d = it.next();
            boolean alive;
            try { alive = tick(d); } catch (Throwable t) { alive = false; }
            if (!alive) { cleanup(d); it.remove(); }
        }
    }

    /** Шаг дрона. false - полёт закончен. */
    private boolean tick(Drone d) {
        d.age++;
        Entity standE = Bukkit.getEntity(d.stand);
        Entity boxE = d.box == null ? null : Bukkit.getEntity(d.box);
        Player owner = Bukkit.getPlayerExact(d.owner);
        if (!(standE instanceof ArmorStand) || !standE.isValid()) return false;
        ArmorStand stand = (ArmorStand) standE;
        if (!d.hunting) {
            // Развёртывание: дрон поднимается (левитация от предмета), коробка следом.
            if (boxE != null && boxE.isValid()) boxE.teleport(stand.getLocation().add(0, 0.3, 0));
            if (d.age < DEPLOY_TICKS) return true;
            d.hunting = true;
            d.age = 0;
            if (owner != null) owner.sendActionBar(net.kyori.adventure.text.Component.text("Дрон ищет цель"));
            setScore(stand, 1);
            if (boxE instanceof LivingEntity) ((LivingEntity) boxE).setInvulnerable(false);
            stand.setGravity(false);
            Player target = target(d);
            if (target != null) {
                ArmorStand mark = target.getWorld().spawn(target.getLocation(), ArmorStand.class, m -> {
                    m.setInvisible(true);
                    m.setInvulnerable(true);
                    m.setGravity(false);
                    m.setSmall(true);
                    m.addScoreboardTag(d.owner + "mdronetargetst");
                });
                d.mark = mark.getUniqueId();
            }
            return true;
        }
        if (d.age > HUNT_TICKS) return false;
        Player target = target(d);
        Entity markE = d.mark == null ? null : Bukkit.getEntity(d.mark);
        Location loc = stand.getLocation();
        if (markE != null && target != null && target.getWorld().equals(loc.getWorld())) {
            Location m = target.getLocation();
            // Далеко - метка в 20 блоках над целью: дрон идёт высоко и не цепляет рельеф.
            if (m.distance(loc) > 40) m.add(0, 20, 0);
            markE.teleport(m);
        }
        // Коробка (её можно сбить) убита - дрон сбит.
        if (boxE == null || !boxE.isValid() || boxE.isDead()) setScore(stand, 5);
        Block here = loc.getBlock();
        boolean inAir = openAir(here);
        int score = score(stand);
        if (score == 5) {
            say(owner, "§7§lваш §7§l§nДРОН§r §c§l сбили");
            if (owner != null) owner.playSound(owner.getLocation(), "minecraft:entity.item.break", SoundCategory.MASTER, 1f, 0.7f);
            return false;
        }
        if (score == 3) {
            if (inAir) {
                say(owner, "§7§lваш §7§l§nДРОН§r §c§l сбили");
                if (owner != null) owner.playSound(owner.getLocation(), "minecraft:entity.item.break", SoundCategory.MASTER, 1f, 0.7f);
            } else say(owner, "§7§lваш §7§l§nДРОН§r §c§l врезался в блок");
            return false;
        }
        if (score == 4 && !d.boomSaid) {
            d.boomSaid = true;
            StringBuilder near = new StringBuilder();
            for (Entity e : stand.getNearbyEntities(5, 5, 5)) if (e instanceof Player) near.append(near.length() > 0 ? ", " : "").append(e.getName());
            say(owner, "§7§lваш §7§l§nДРОН§r §2§l подорвал игрока §e§l" + near);
            for (Player p : loc.getWorld().getPlayers()) p.sendMessage("§4§lДрон " + d.owner + " влетел в §e§lкого-то");
        }
        if (boxE instanceof LivingEntity && ((LivingEntity) boxE).getNoDamageTicks() > 0) {
            loc.getWorld().spawnParticle(Particle.FLAME, loc.clone().add(0, 0.3, 0), 6, 0.3, 0.3, 0.3, 0.01, null, true);
        }
        if (markE != null && markE.isValid() && target != null && target.getWorld().equals(loc.getWorld())
                && target.getLocation().distance(loc) <= 400) {
            move(stand, markE.getLocation());
        }
        // Влетел в блок, воду или лаву - в следующий тик «врезался».
        Block now = stand.getLocation().getBlock();
        if (!Tag.REPLACEABLE.isTagged(now.getType()) || now.getType() == Material.WATER || now.getType() == Material.LAVA) setScore(stand, 3);
        if (boxE != null && boxE.isValid()) boxE.teleport(stand.getLocation().add(0, 0.3, 0));
        return true;
    }

    /** Ход к метке: первый свободный из восьми вариантов (вперёд, в стороны, вверх, вниз, назад). */
    private static void move(ArmorStand stand, Location mark) {
        Location p = stand.getLocation();
        World w = p.getWorld();
        Vector to = mark.toVector().subtract(p.toVector());
        if (to.lengthSquared() < 1e-6) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
        float pitch = (float) -Math.toDegrees(Math.atan2(to.getY(), Math.hypot(to.getX(), to.getZ())));
        Vector[] axes = axes(yaw, pitch);
        // {сдвиг-проверка (лево, верх, вперёд, мировой y), доп. проверки (лево, верх, вперёд), итоговый шаг}
        Vector next = null;
        if (free(w, p, axes, 0, 0, 0.35, 0, new double[][]{{0, 0, 0.65}, {0, 0, 1.55}})) next = local(p, axes, 0, 0, 0.28, 0);
        else if (free(w, p, axes, -0.3, 0, 0.2, 0, new double[][]{{-0.6, 0, 0.4}, {-1.3, 0, 0.9}})) next = local(p, axes, -0.22, 0, 0.14, 0);
        else if (free(w, p, axes, 0.3, 0, 0.2, 0, new double[][]{{0.6, 0, 0.4}, {1.3, 0, 0.9}})) next = local(p, axes, 0.22, 0, 0.14, 0);
        else if (freeWorld(w, p, 0.35, new double[]{0, 0.8, 1.2})) next = p.toVector().add(new Vector(0, 0.26, 0));
        else if (freeWorld(w, p, -0.3, new double[]{0, 0.8, -0.7})) next = p.toVector().add(new Vector(0, -0.22, 0));
        else if (free(w, p, axes, 0, 0, 0.2, 0, new double[][]{{0, 0, 0.4}})) next = local(p, axes, 0, 0, 0.12, 0);
        else if (free(w, p, axes, 0, 0, -0.3, -0.3, new double[][]{})) next = local(p, axes, 0, 0, -0.2, -0.2);
        else if (free(w, p, axes, 0, 0, -0.35, 0, new double[][]{{0, 0, -0.6}})) next = local(p, axes, 0, 0, -0.25, 0);
        if (next == null) return;
        Location n = new Location(w, next.getX(), next.getY(), next.getZ());
        Vector look = mark.toVector().subtract(next);
        n.setYaw((float) Math.toDegrees(Math.atan2(-look.getX(), look.getZ())));
        n.setPitch((float) -Math.toDegrees(Math.atan2(look.getY(), Math.hypot(look.getX(), look.getZ()))));
        stand.teleport(n);
    }

    /** Оси локальных координат Майнкрафта (^лево ^верх ^вперёд) для yaw/pitch. */
    private static Vector[] axes(float yaw, float pitch) {
        double f = Math.cos(Math.toRadians(yaw + 90)), g = Math.sin(Math.toRadians(yaw + 90));
        double h = Math.cos(Math.toRadians(-pitch)), i = Math.sin(Math.toRadians(-pitch));
        double j = Math.cos(Math.toRadians(-pitch + 90)), k = Math.sin(Math.toRadians(-pitch + 90));
        Vector fwd = new Vector(f * h, i, g * h), up = new Vector(f * j, k, g * j);
        Vector left = fwd.clone().crossProduct(up).multiply(-1);
        return new Vector[]{left, up, fwd};
    }

    private static Vector local(Location p, Vector[] ax, double l, double u, double fwd, double worldY) {
        return p.toVector().add(ax[0].clone().multiply(l)).add(ax[1].clone().multiply(u)).add(ax[2].clone().multiply(fwd)).add(new Vector(0, worldY, 0));
    }

    /** Новая точка и над ней (0.8) свободны, и дополнительные точки по локальным осям от неё. */
    private static boolean free(World w, Location p, Vector[] ax, double l, double u, double fwd, double worldY, double[][] extra) {
        Vector c = local(p, ax, l, u, fwd, worldY);
        if (!open(w, c) || !open(w, c.clone().add(new Vector(0, 0.8, 0)))) return false;
        Location cl = new Location(w, c.getX(), c.getY(), c.getZ());
        for (double[] e : extra) if (!open(w, local(cl, ax, e[0], e[1], e[2], 0))) return false;
        return true;
    }

    /** Сдвиг по мировой вертикали dy и проверки на высотах offs от новой точки. */
    private static boolean freeWorld(World w, Location p, double dy, double[] offs) {
        Vector c = p.toVector().add(new Vector(0, dy, 0));
        for (double o : offs) if (!open(w, c.clone().add(new Vector(0, o, 0)))) return false;
        return true;
    }

    private static boolean open(World w, Vector v) {
        return openAir(w.getBlockAt((int) Math.floor(v.getX()), (int) Math.floor(v.getY()), (int) Math.floor(v.getZ())));
    }

    /** Как в предмете: блок из #replaceable, но не вода и не лава. */
    private static boolean openAir(Block b) {
        Material m = b.getType();
        return Tag.REPLACEABLE.isTagged(m) && m != Material.WATER && m != Material.LAVA;
    }

    /** Цель: игрок с тегом «<владелец>mdronetarget», не под ЭМИ и не наблюдатель. */
    private static Player target(Drone d) {
        String tag = d.owner + "mdronetarget";
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getScoreboardTags().contains(tag) && !p.getScoreboardTags().contains("shsender") && p.getGameMode() != GameMode.SPECTATOR) return p;
        }
        return null;
    }

    private static int score(Entity e) {
        Objective o = Bukkit.getScoreboardManager().getMainScoreboard().getObjective("mdrone");
        if (o == null) return 0;
        org.bukkit.scoreboard.Score s = o.getScoreFor(e);
        return s.isScoreSet() ? s.getScore() : 0;
    }

    private static void setScore(Entity e, int v) {
        Objective o = Bukkit.getScoreboardManager().getMainScoreboard().getObjective("mdrone");
        if (o != null) o.getScoreFor(e).setScore(v);
    }

    private static void say(Player owner, String msg) {
        if (owner != null) owner.sendMessage(msg);
    }

    /** Конец полёта: как в конце цикла предмета - метку, дрон и коробку убрать, тег цели снять. */
    private static void cleanup(Drone d) {
        for (UUID id : new UUID[]{d.mark, d.stand, d.box}) {
            if (id == null) continue;
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        String tag = d.owner + "mdronetarget";
        for (Player p : Bukkit.getOnlinePlayers()) p.removeScoreboardTag(tag);
    }

    /** Выключение плагина: дроны не оставляем висеть. */
    public void shutdown() {
        for (Drone d : drones) cleanup(d);
        drones.clear();
    }
}
