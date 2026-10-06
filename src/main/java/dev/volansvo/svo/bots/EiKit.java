package dev.volansvo.svo.bots;

import dev.volansvo.svo.bots.nms.BotNms;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;

/**
 * Предметы ExecutableItems с сервера (архив от 2026-10-05), которыми бот умеет пользоваться.
 * Бот жмёт те же кнопки, что игрок (ПКМ, ЛКМ, присед), всё остальное делает сам предмет.
 *
 * Узнаём по id предмета EI (имя файла). Чего здесь нет, бот не берёт и не трогает.
 */
final class EiKit {

    enum Use {
        ROCKET,      // самонаводящаяся ракета (ПКМ), летит в ближайшего врага
        SNIPER,      // одноразовая снайперка: присед = выстрел
        FLAMER,      // огнемёт: ЛКМ, на 8 очках нагрева (счёт ognemet) взрывается
        PULSE,       // пулемётик: ЛКМ, почти мгновенная стрела
        TNT_AHEAD,   // тикающая: ЛКМ, динамит в 3 блоках перед собой
        BLACKHOLE,   // чёрная дыра: ПКМ, летит вперёд и затягивает
        FATIGUE,     // аура усталости: клик, всем в 6 блоках
        JUSTICE,     // меч правосудия: ПКМ - рывок
        ORBITAL,     // орбитальная пушка: удар по своему месту через 5 сек - нажать и бежать
        SMOKE,       // дымовая: клик, яд всем в 4 блоках
        STIM,        // стимулятор: сила и поглощение
        MINE,        // мина: ПКМ - поставить под себя
        ZEUS,        // яйцо Зевса: молния по всем в элитрах
        EMI,         // ЭМИ: в любой руке - ракеты и дроны не наводятся
        AMMO_AK,     // патроны автомата: в левую руку + клик
        AMMO_SHOTGUN // патроны дробовика: в левую руку + клик
    }

    private static final Map<String, Use> IDS = new HashMap<String, Use>();
    static {
        IDS.put("rockets", Use.ROCKET);
        IDS.put("rocketspoly", Use.ROCKET);
        IDS.put("sniper", Use.SNIPER);
        IDS.put("firearm", Use.FLAMER);
        IDS.put("pulebow", Use.PULSE);
        IDS.put("zfierball3", Use.TNT_AHEAD);
        IDS.put("blakhole", Use.BLACKHOLE);
        IDS.put("fatigue_stick", Use.FATIGUE);
        IDS.put("justicesword", Use.JUSTICE);
        IDS.put("prem_boom_v1_8", Use.ORBITAL);
        IDS.put("smokebomb", Use.SMOKE);
        IDS.put("stimulator", Use.STIM);
        IDS.put("mina", Use.MINE);
        IDS.put("zeusjajk", Use.ZEUS);
        IDS.put("emi", Use.EMI);
        IDS.put("bullets1", Use.AMMO_AK);
        IDS.put("bulletsd", Use.AMMO_SHOTGUN);
    }

    static Use use(ItemStack it) {
        String id = Items.eiId(it);
        return id == null ? null : IDS.get(id.toLowerCase(Locale.ROOT));
    }

    /** Ценность для подбора (0 - не наш предмет). */
    static double value(ItemStack it) {
        Use u = use(it);
        if (u == null) return 0;
        switch (u) {
            case ROCKET: case SNIPER: case BLACKHOLE: return 40;
            case FLAMER: case PULSE: case ORBITAL: return 32;
            case EMI: return 30;
            case STIM: case MINE: case TNT_AHEAD: return 22;
            case SMOKE: case FATIGUE: case ZEUS: case JUSTICE: return 15;
            default: return 15; // патроны
        }
    }

    private final BotManager mgr;
    private final UUID self;
    private final String name;
    private final Motor motor;
    private final IntPredicate hold;
    private final BiConsumer<Location, Integer> danger; // «отсюда бежать» (точка, на сколько тиков)
    private final Map<Use, Integer> nextUse = new HashMap<Use, Integer>();

    // начатое действие (довести прицел и нажать)
    private Use pending;
    private int pendingSlot = -1, pendingUntil;
    private int flamerShots, sneakRelease = -1;
    // пополнение патронов: что лежало в левой руке
    private ItemStack savedOffhand;
    private int restoreOffhandAt = -1;

    EiKit(BotManager mgr, UUID self, String name, Motor motor, IntPredicate hold, BiConsumer<Location, Integer> danger) {
        this.mgr = mgr;
        this.self = self;
        this.name = name;
        this.motor = motor;
        this.hold = hold;
        this.danger = danger;
    }

    private boolean ready(Use u, int now) {
        Integer t = nextUse.get(u);
        return t == null || now >= t;
    }

    private void cool(Use u, int now, int ticks) {
        nextUse.put(u, now + ticks);
    }

    private static int find(Player p, Use u) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) if (use(inv.getItem(i)) == u) return i;
        return -1;
    }

    // =====================================================================  каждый тик

    /** Служебное: вернуть левую руку после патронов, отпустить присед после снайперки. */
    void tick(Player p, int now) {
        if (sneakRelease >= 0 && now >= sneakRelease) { BotNms.sneak(p, false); sneakRelease = -1; }
        if (restoreOffhandAt >= 0 && now >= restoreOffhandAt) {
            restoreOffhandAt = -1;
            PlayerInventory inv = p.getInventory();
            ItemStack off = inv.getItemInOffHand();
            if (savedOffhand != null) {
                inv.setItemInOffHand(savedOffhand);
                if (off != null && !off.getType().isAir()) {
                    java.util.Map<Integer, ItemStack> left = inv.addItem(off);
                    for (ItemStack l : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), l);
                }
            }
            savedOffhand = null;
        }
    }

    /** Занят ли бот действием с предметом (тогда обычный бой этот тик не ведём). */
    boolean busy(int now) {
        return pending != null && now < pendingUntil || restoreOffhandAt >= 0;
    }

    // =====================================================================  бой

    /**
     * Попробовать боевой предмет против врага. true - этот тик занят предметом.
     */
    boolean combat(Player p, int now, LivingEntity e, double d, boolean visible, Vector vel) {
        if (restoreOffhandAt >= 0) return true;
        if (pending != null) {
            if (now >= pendingUntil || pendingSlot < 0 || use(p.getInventory().getItem(pendingSlot)) != pending) {
                if (pending != null) cool(pending, now, 15); // не навелись - пока воюем обычным оружием
                pending = null;
                return false;
            }
            return finish(p, now, e, d, vel);
        }
        if (!visible) return false;
        // Порядок: сильное и редкое первым.
        if (d >= 12 && d <= 110 && tryStart(p, now, Use.SNIPER)) return finish(p, now, e, d, vel);
        if (d >= 6 && d <= 48 && tryStart(p, now, Use.ROCKET)) return finish(p, now, e, d, vel);
        if (d >= 4 && d <= 30 && tryStart(p, now, Use.BLACKHOLE)) return finish(p, now, e, d, vel);
        if (d <= 20 && ready(Use.STIM, now) && !p.hasPotionEffect(PotionEffectType.STRENGTH) && tryStart(p, now, Use.STIM)) return finish(p, now, e, d, vel);
        if (d <= 6 && tryStart(p, now, Use.ORBITAL)) return finish(p, now, e, d, vel);
        if (d >= 3 && d <= 7 && tryStart(p, now, Use.TNT_AHEAD)) return finish(p, now, e, d, vel);
        if (d <= 10 && tryStart(p, now, Use.FLAMER)) return finish(p, now, e, d, vel);
        if (d <= 40 && tryStart(p, now, Use.PULSE)) return finish(p, now, e, d, vel);
        if (d <= 5 && tryStart(p, now, Use.FATIGUE)) return finish(p, now, e, d, vel);
        if (d >= 3 && d <= 7 && tryStart(p, now, Use.JUSTICE)) return finish(p, now, e, d, vel);
        if (e instanceof Player && ((Player) e).isGliding() && tryStart(p, now, Use.ZEUS)) return finish(p, now, e, d, vel);
        return false;
    }

    private boolean tryStart(Player p, int now, Use u) {
        if (!ready(u, now)) return false;
        if (u == Use.FLAMER && flamerHeat(p, flamerShots) >= 6) { flamerShots = 0; cool(u, now, 20 * 8); return false; }
        int slot = find(p, u);
        if (slot < 0) return false;
        if (!hold.test(slot)) return false;
        pending = u;
        pendingSlot = slot;
        pendingUntil = now + 20;
        return true;
    }

    /** Довести прицел и нажать нужную кнопку. true - бот этот тик занят. */
    private boolean finish(Player p, int now, LivingEntity e, double d, Vector vel) {
        if (p.getInventory().getHeldItemSlot() != pendingSlot) { hold.test(pendingSlot); return true; }
        Use u = pending;
        Location eye = p.getEyeLocation();
        Location t = e.getLocation().add(0, e.getHeight() * 0.6, 0);
        if (u == Use.PULSE || u == Use.SNIPER) t.add(vel.clone().setY(0).multiply(Math.min(6, d / 7.0)));
        boolean needAim;
        float tol;
        switch (u) {
            case SNIPER: needAim = true; tol = 1.6f; break;
            case PULSE: needAim = true; tol = 2.5f; break;
            case FLAMER: case BLACKHOLE: case JUSTICE: case TNT_AHEAD: needAim = true; tol = 8f; break;
            case ROCKET: needAim = true; tol = 30f; break;
            default: needAim = false; tol = 180f;
        }
        float yaw = Motor.yawTo(t.getX() - eye.getX(), t.getZ() - eye.getZ());
        float pitch = Motor.pitchTo(t.getX() - eye.getX(), t.getY() - eye.getY(), t.getZ() - eye.getZ());
        // Вблизи цель большая - промахнуться трудно, не ждём идеального прицела.
        tol = Math.max(tol, (float) Math.toDegrees(Math.atan2(0.55, Math.max(0.5, d))) + 1f);
        if (needAim) {
            motor.turn(p, yaw, pitch, u == Use.SNIPER ? 12f : 25f);
            if (Math.abs(Motor.wrap(yaw - motor.yaw())) > tol || Math.abs(pitch - motor.pitch()) > tol) return true;
        }
        switch (u) {
            case ROCKET: BotNms.useItem(p, false); cool(u, now, 40); break;
            case BLACKHOLE: BotNms.useItem(p, false); cool(u, now, 20 * 8); break;
            case JUSTICE: BotNms.useItem(p, false); cool(u, now, 20 * 11); break;
            case STIM: BotNms.useItem(p, false); cool(u, now, 20 * 36); break;
            case FATIGUE: BotNms.useItem(p, false); cool(u, now, 20 * 8); break;
            case ZEUS: BotNms.useItem(p, false); cool(u, now, 20 * 10); break;
            case PULSE: BotNms.clickAir(p); cool(u, now, 14); break;
            case SNIPER:
                BotNms.sneak(p, false);
                BotNms.sneak(p, true); // выстрел по нажатию приседа
                sneakRelease = now + 3;
                cool(u, now, 40);
                break;
            case FLAMER:
                BotNms.clickAir(p);
                // Нагрев +1 за выстрел, -1 раз в 2 сек, на 8 огнемёт взрывается у лица.
                if (flamerHeat(p, ++flamerShots) >= 6) { flamerShots = 0; cool(u, now, 20 * 8); }
                else cool(u, now, 4);
                break;
            case TNT_AHEAD: {
                BotNms.clickAir(p);
                Vector f = new Vector(t.getX() - eye.getX(), 0, t.getZ() - eye.getZ());
                if (f.lengthSquared() > 1e-6) f.normalize().multiply(3);
                danger.accept(p.getLocation().add(f), 50); // динамит с запалом 2 сек - отходим
                cool(u, now, 20 * 6);
                break;
            }
            case ORBITAL:
                BotNms.useItem(p, false);
                danger.accept(p.getLocation(), 130); // удар по этому месту через 5 сек - бежим
                cool(u, now, 20 * 20);
                break;
            default:
                break;
        }
        if (mgr.skill().debug) mgr.debug(name + " применяет " + u);
        pending = null;
        return true;
    }

    // =====================================================================  бегство

    /** На бегу: дымовая вплотную, мина под ноги преследователю. true - тик занят. */
    boolean evading(Player p, int now, LivingEntity e, double d) {
        if (restoreOffhandAt >= 0) return false;
        if (d <= 4 && ready(Use.SMOKE, now)) {
            int s = find(p, Use.SMOKE);
            if (s >= 0 && hold.test(s) && p.getInventory().getHeldItemSlot() == s) {
                BotNms.useItem(p, false);
                cool(Use.SMOKE, now, 20 * 15);
                danger.accept(p.getLocation(), 40);
                return true;
            }
        }
        if (d <= 25 && d >= 5 && ready(Use.MINE, now) && BotNms.onGround(p)) {
            int s = find(p, Use.MINE);
            if (s >= 0 && hold.test(s) && p.getInventory().getHeldItemSlot() == s) {
                BotNms.useItem(p, false);
                cool(Use.MINE, now, 20 * 20);
                return true;
            }
        }
        return false;
    }

    // =====================================================================  обслуживание

    /**
     * Раз в пару секунд: ЭМИ в левую руку (ракеты и дроны его не видят), снять с себя
     * оставшуюся метку ЭМИ, если его в руках уже нет, и пополнить патроны в спокойствии.
     */
    void maintain(Player p, int now, boolean calm) {
        PlayerInventory inv = p.getInventory();
        boolean emiHeld = use(inv.getItemInMainHand()) == Use.EMI || use(inv.getItemInOffHand()) == Use.EMI;
        if (!emiHeld && p.getScoreboardTags().contains("shsender")) p.removeScoreboardTag("shsender");
        if (restoreOffhandAt >= 0) return;
        ItemStack off = inv.getItemInOffHand();
        Items.Kind offKind = off == null || off.getType().isAir() ? Items.Kind.NONE : null;
        boolean offTotem = off != null && off.getType() == org.bukkit.Material.TOTEM_OF_UNDYING;
        if (!emiHeld && !offTotem) {
            int s = find(p, Use.EMI);
            if (s >= 0) {
                ItemStack emi = inv.getItem(s);
                inv.setItem(s, offKind == Items.Kind.NONE ? null : off);
                inv.setItemInOffHand(emi);
                return;
            }
        }
        if (calm) refill(p, now);
    }

    /** Патроны в левую руку, ствол в правую, клик - и обратно. */
    private void refill(Player p, int now) {
        PlayerInventory inv = p.getInventory();
        for (int g = 0; g < 36; g++) {
            ItemStack gun = inv.getItem(g);
            Items.Custom ct = Items.customType(gun);
            if (ct != Items.Custom.AUTO && ct != Items.Custom.SHOTGUN) continue;
            int[] mag = Items.eiMag(gun);
            if (mag == null || mag[2] < 0) continue;
            boolean ak = ct == Items.Custom.AUTO;
            int cap = ak ? 140 : 30, pack = ak ? 50 : 12;
            if (mag[2] + pack > cap) continue;
            int ammo = find(p, ak ? Use.AMMO_AK : Use.AMMO_SHOTGUN);
            if (ammo < 0) continue;
            if (!hold.test(g) || inv.getHeldItemSlot() != g) return;
            ItemStack pkt = inv.getItem(ammo);
            savedOffhand = inv.getItemInOffHand();
            if (savedOffhand != null && savedOffhand.getType().isAir()) savedOffhand = null;
            inv.setItem(ammo, null);
            inv.setItemInOffHand(pkt);
            BotNms.look(p, motor.yaw(), -55f); // клик в воздух
            motor.sync(p);
            BotNms.clickAir(p);
            restoreOffhandAt = now + 3;
            if (mgr.skill().debug) mgr.debug(name + " пополняет патроны " + ct);
            return;
        }
    }

    /** Нагрев огнемёта из счёта ognemet, который ведёт сам предмет (нет счёта - свой подсчёт). */
    private static int flamerHeat(Player p, int fallback) {
        org.bukkit.scoreboard.Objective o = org.bukkit.Bukkit.getScoreboardManager().getMainScoreboard().getObjective("ognemet");
        if (o == null) return fallback;
        org.bukkit.scoreboard.Score s = o.getScore(p.getName());
        return s.isScoreSet() ? s.getScore() : 0;
    }

    /** Предметы, которые бот сам «пробовать» не должен (у них своё применение). */
    static boolean handled(ItemStack it) {
        return use(it) != null;
    }

    @SuppressWarnings("unused")
    private static boolean survival(Player o) { return o.getGameMode() == GameMode.SURVIVAL; }
}
