package dev.volansvo.svo.bots;

import dev.volansvo.svo.bots.nms.BotNms;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

import java.util.function.IntPredicate;

/**
 * Руки бота для блоков: ломает блоки с той же скоростью, что игрок (инструмент, эффекты,
 * в воде/в воздухе медленнее), с трещинами для всех вокруг, и ставит блоки кликом по грани
 * соседнего блока, как клиент. На этом построены: проход сквозь завал, столб вверх к цели
 * и укрытие от выстрелов при лечении.
 */
final class Builder {

    private static final BlockFace[] SUPPORT = {
        BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP
    };

    private final Motor motor;
    private final IntPredicate hold;
    private final float turnSpeed;

    private Block mining;
    private float progress;
    private int mineStart;
    /** Блоки, которые сломать не дали (защита региона и т.п.): ключ позиции -> до какого времени не трогаем. */
    private final java.util.Map<Long, Long> denied = new java.util.HashMap<Long, Long>();
    /** Отладка: почему бросили недоломанный блок (null - не пишем). */
    java.util.function.Consumer<String> debugLog;
    private int towerFeetY = Integer.MIN_VALUE;

    Builder(Motor motor, IntPredicate hold, float turnSpeed) {
        this.motor = motor;
        this.hold = hold;
        this.turnSpeed = turnSpeed;
    }

    boolean isMining() { return mining != null; }

    // ================================================================== ломание

    /**
     * Блок в зоне. За её границей, как в ванилле, блоки не ломают, не ставят и не открывают:
     * это касается и ботов.
     */
    static boolean inZone(Block b) {
        return b.getWorld().getWorldBorder().isInside(b.getLocation());
    }

    /** Можно ли боту ломать этот блок (контейнеры, двери, неразрушимое и всё за зоной не трогаем). */
    static boolean breakable(Block b) {
        Material m = b.getType();
        if (m.isAir() || b.isLiquid() || !inZone(b)) return false;
        float h = m.getHardness();
        if (h < 0 || h > 30) return false;
        if (b.getState() instanceof Container) return false;
        String n = m.name();
        return !(n.contains("DOOR") || n.contains("BED") || n.contains("SIGN") || n.contains("COMMAND")
            || n.contains("STRUCTURE") || n.contains("SPAWNER") || n.contains("PORTAL") || m == Material.BARRIER);
    }

    /**
     * Стоит ли боту ломать этот блок сейчас: подходящим инструментом - любой ломаемый,
     * рукой - только то, что ломается быстро (земля, песок, гравий, листва, стекло...),
     * а камень и прочее «на руках» не ковыряем.
     */
    static boolean canDig(Player p, Block b) {
        return canDig(p, b, 1.6);
    }

    /** Сколько секунд готовы ломать руками (застряли в застройке - дольше обычного). */
    double handLimit = 1.6;

    static boolean canDig(Player p, Block b, double handLimit) {
        if (!breakable(b)) return false;
        if (bestTool(p, b) >= 0) return true;
        float h = b.getType().getHardness();
        boolean needTool;
        try { needTool = b.getBlockData().requiresCorrectToolForDrops(); } catch (Throwable t) { needTool = h >= 1.5f; }
        double seconds = h * (needTool ? 5.0 : 1.5);
        return seconds <= handLimit;
    }

    /**
     * Шаг ломания блока. true - ещё ломаем (бот занят), false - сломан, не сломать или
     * слишком далеко (тогда вызывающий сначала подходит).
     */
    boolean mine(Player p, Block b, int now) {
        if (b == null || !canDig(p, b, handLimit) || isDenied(b)) { stopMining(p, "нельзя"); return false; }
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        if (p.getEyeLocation().distance(c) > 4.4) { stopMining(p, "далеко"); return false; }
        if (mining == null || !mining.equals(b)) {
            stopMining(p, "другой блок " + b.getType());
            mining = b;
            progress = 0f;
            mineStart = now;
        }
        // Инструмент держим всё время копания (его могли переложить).
        int tool = bestTool(p, b);
        if (tool >= 0 && p.getInventory().getHeldItemSlot() != tool) hold.test(tool);
        Location eye = p.getEyeLocation();
        motor.turn(p, Motor.yawTo(c.getX() - eye.getX(), c.getZ() - eye.getZ()),
            Motor.pitchTo(c.getX() - eye.getX(), c.getY() - eye.getY(), c.getZ() - eye.getZ()), turnSpeed);
        float speed = b.getBreakSpeed(p);
        if (speed <= 0f || now - mineStart > 20 * Math.max(12, handLimit + 4)) { stopMining(p, speed <= 0f ? "скорость 0" : "12 секунд"); return false; }
        progress += speed;
        if (now % 5 == 0) p.swingMainHand();
        if (now % 3 == 0) crack(p, b, Math.min(progress, 0.99f));
        if (progress >= 1f) {
            crack(p, b, -1f);
            mining = null;
            progress = 0f;
            // BlockBreakEvent, дроп, износ инструмента - как у игрока.
            if (!p.breakBlock(b)) {
                // Отменил другой плагин (на некоторых серверах у ботов отменяют любые действия, как у
                // не вошедших игроков): блок в зоне ломаем сами, с выпадением. Не вышло - минуту не трогаем.
                warnDenied(b);
                Material was = b.getType();
                if (!inZone(b) || !b.breakNaturally(p.getInventory().getItemInMainHand(), true) && b.getType() == was) {
                    denied.put(key(b), System.currentTimeMillis() + 60_000L);
                    if (debugLog != null) debugLog.accept("не дали сломать " + was + " " + b.getX() + "," + b.getY() + "," + b.getZ());
                }
            }
            return false;
        }
        return true;
    }

    /** Доломать начатый блок, если он ещё стоит и до него достаём. true - ломаем. */
    boolean resumeMining(Player p, int now) {
        if (mining == null) return false;
        // Рыхлый снег и паутина не «твёрдые», но ломать их надо так же до конца.
        if (mining.getType().isAir() || mining.isLiquid()) { stopMining(p); return false; }
        return mine(p, mining, now);
    }

    void stopMining(Player p) {
        stopMining(p, null);
    }

    private void stopMining(Player p, String why) {
        if (mining != null && p != null) {
            crack(p, mining, -1f); // трещину убираем сразу, иначе висит на брошенном блоке
            if (why != null && debugLog != null && progress > 0.1f && !mining.getType().isAir())
                debugLog.accept("бросил " + mining.getType() + " на " + Math.round(progress * 100) + "%: " + why);
        }
        mining = null;
        progress = 0f;
    }

    private static boolean warnedDenied;

    /** Один раз в консоль: ломать блоки ботам не даёт другой плагин (кто слушает BlockBreakEvent). */
    private static void warnDenied(Block b) {
        if (warnedDenied) return;
        warnedDenied = true;
        java.util.Set<String> who = new java.util.TreeSet<String>();
        for (org.bukkit.plugin.RegisteredListener rl : org.bukkit.event.block.BlockBreakEvent.getHandlerList().getRegisteredListeners())
            who.add(rl.getPlugin().getName());
        org.bukkit.Bukkit.getLogger().warning("[VolanSVO] Ломание блока ботом " + b.getType() + " в " + b.getWorld().getName() + " "
            + b.getX() + "," + b.getY() + "," + b.getZ() + " отменил другой плагин (защита региона, античит, авторизация). "
            + "BlockBreakEvent слушают: " + who + ". Блоки в зоне боты ломают сами.");
    }

    private static long key(Block b) {
        return ((long) b.getX() & 0x3FFFFFFL) << 38 | ((long) b.getZ() & 0x3FFFFFFL) << 12 | ((long) b.getY() & 0xFFFL);
    }

    /** Этот блок недавно не дали сломать. */
    boolean isDenied(Block b) {
        if (denied.isEmpty()) return false;
        Long until = denied.get(key(b));
        if (until == null) return false;
        if (System.currentTimeMillis() < until) return true;
        denied.remove(key(b));
        return false;
    }

    /** Трещины на блоке у всех рядом, как от игрока; progress < 0 - убрать. */
    private static void crack(Player p, Block b, float progress) {
        int stage = progress < 0 ? -1 : Math.min(9, (int) (progress * 10f));
        ((org.bukkit.craftbukkit.CraftWorld) b.getWorld()).getHandle().destroyBlockProgress(p.getEntityId(),
            new net.minecraft.core.BlockPos(b.getX(), b.getY(), b.getZ()), stage);
    }

    /** Подходящий инструмент (кирка для камня, топор для дерева...) получше. */
    static int bestTool(Player p, Block b) {
        PlayerInventory inv = p.getInventory();
        int best = -1, bestTier = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir() || !b.isPreferredTool(it)) continue;
            String n = it.getType().name();
            if (!(n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.equals("SHEARS"))) continue;
            int tier = n.startsWith("NETHERITE") ? 5 : n.startsWith("DIAMOND") ? 4 : n.startsWith("IRON") ? 3
                : n.startsWith("STONE") ? 2 : n.startsWith("GOLDEN") ? 1 : 0;
            if (tier > bestTier) { bestTier = tier; best = i; }
        }
        return best;
    }

    // ================================================================== установка

    static int blockSlot(Player p) {
        PlayerInventory inv = p.getInventory();
        int best = -1, bestAmount = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (!Items.isBuildBlock(it)) continue;
            if (it.getAmount() > bestAmount) { bestAmount = it.getAmount(); best = i; }
        }
        return best;
    }

    static int blockCount(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (Items.isBuildBlock(it)) n += it.getAmount();
        return n;
    }

    private int placeFails;
    private long placeBlockedUntil;

    /**
     * Ставить блоки сейчас нельзя: несколько попыток подряд не удались (на карте запрещено
     * строить, защита региона и т.п.) - полторы минуты не пробуем.
     */
    boolean placeBlocked() {
        return System.currentTimeMillis() < placeBlockedUntil;
    }

    /** Поставить блок в target (клик по грани опорного соседа). true - блок встал. */
    boolean place(Player p, Block target) {
        return place(p, target, false);
    }

    /** downOnly: только кликом по блоку снизу (столб под собой), на стены не смотрим. */
    boolean place(Player p, Block target, boolean downOnly) {
        turning = false;
        if (placeBlocked() || !inZone(target)) return false;
        if (!target.getType().isAir() && !target.isReplaceable() && !target.isLiquid()) return false;
        int slot = blockSlot(p);
        if (slot < 0 || !hold.test(slot)) return false;
        boolean tried = false;
        for (BlockFace f : SUPPORT) {
            if (downOnly && f != BlockFace.DOWN) continue;
            Block n = target.getRelative(f);
            if (!n.getType().isSolid() || !inZone(n)) continue;
            BlockFace clicked = f.getOppositeFace(); // грань соседа, смотрящая на target
            Location hit = n.getLocation().add(0.5 + clicked.getModX() * 0.5, 0.5 + clicked.getModY() * 0.5,
                0.5 + clicked.getModZ() * 0.5);
            if (p.getEyeLocation().distance(hit) > 4.4) continue;
            Location eye = p.getEyeLocation();
            float yaw = Motor.yawTo(hit.getX() - eye.getX(), hit.getZ() - eye.getZ());
            float pitch = Motor.pitchTo(hit.getX() - eye.getX(), hit.getY() - eye.getY(), hit.getZ() - eye.getZ());
            // Сначала доворачиваем голову (не рывком), потом жмём.
            if (!motor.aim(p, yaw, pitch, 9f)) { turning = true; return false; }
            BotNms.useItemOn(p, n.getX(), n.getY(), n.getZ(), faceIndex(clicked));
            p.swingMainHand();
            tried = true;
            if (!target.getType().isAir() && !target.isLiquid()) { placeFails = 0; return true; }
            break; // не встал - другие грани не перебираем (иначе бот «смотрит в стену»)
        }
        if (tried && ++placeFails >= 3) {
            placeFails = 0;
            placeBlockedUntil = System.currentTimeMillis() + 90_000L;
        }
        return false;
    }

    /** Последний place() не поставил блок только потому, что ещё доворачивал голову. */
    private boolean turning;

    boolean turning() { return turning; }

    /**
     * Поставить предмет из слота slot кликом по грани face блока support (лестница на стену,
     * верстак, мина или турель на землю). true - кликнули (что встало - решает сервер).
     */
    boolean useOnFace(Player p, Block support, BlockFace face, int slot) {
        turning = false;
        if (slot < 0 || !inZone(support) || !hold.test(slot)) return false;
        Location hit = support.getLocation().add(0.5 + face.getModX() * 0.5, 0.5 + face.getModY() * 0.5, 0.5 + face.getModZ() * 0.5);
        Location eye = p.getEyeLocation();
        if (eye.distance(hit) > 4.4) return false;
        float yaw = Motor.yawTo(hit.getX() - eye.getX(), hit.getZ() - eye.getZ());
        float pitch = Motor.pitchTo(hit.getX() - eye.getX(), hit.getY() - eye.getY(), hit.getZ() - eye.getZ());
        if (!motor.aim(p, yaw, pitch, 9f)) { turning = true; return false; }
        BotNms.useItemOn(p, support.getX(), support.getY(), support.getZ(), faceIndex(face));
        p.swingMainHand();
        return true;
    }

    // ================================================================== лестница по стене

    static int ladderSlot(Player p) {
        for (int i = 0; i < 36; i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (it != null && it.getType() == Material.LADDER && !it.hasItemMeta()) return i;
        }
        return -1;
    }

    static int ladderCount(Player p) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (it != null && it.getType() == Material.LADDER && !it.hasItemMeta()) n += it.getAmount();
        }
        return n;
    }

    private int nextLadderPlace;

    /**
     * Подъём по стене на лестницах: (dx,dz) - направление на стену, topFeetY - на какую высоту
     * ног выбраться. Лестница ставится на стену в клетке бота (сначала встаём в середину
     * клетки, иначе лестница не встанет - в ней тело), потом лезем с зажатым прыжком, ставя
     * следующую выше. Наверху шагаем на стену. true - ещё лезем; false - готово или нечем.
     */
    boolean ladderClimb(Player p, int dx, int dz, int topFeetY, int now) {
        Location l = p.getLocation();
        World w = p.getWorld();
        int x = l.getBlockX(), z = l.getBlockZ(), fy = (int) Math.floor(l.getY() + 0.01);
        if (fy >= topFeetY && BotNms.onGround(p)) return false;
        BlockFace face = dx > 0 ? BlockFace.WEST : dx < 0 ? BlockFace.EAST : dz > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
        Block wallFeet = w.getBlockAt(x + dx, fy, z + dz);
        // Стена у ног кончилась, а под ней ещё стена - мы наверху: шаг вперёд на неё.
        if (!wallFeet.getType().isSolid()) {
            if (!w.getBlockAt(x + dx, fy - 1, z + dz).getType().isSolid()) return false; // стены нет вовсе
            motor.turn(p, Motor.yawTo(dx, dz), 10f, 30f);
            motor.drive(p, dx, dz, 1.0, 0f, true, false);
            return true;
        }
        // Лестницы в своей клетке на уровне ног и головы, где за ними стена.
        boolean climbing = p.isClimbing();
        for (int dy = 0; dy <= 1; dy++) {
            Block cell = w.getBlockAt(x, fy + dy, z), wall = w.getBlockAt(x + dx, fy + dy, z + dz);
            if (cell.getType() == Material.LADDER || !wall.getType().isSolid()) continue;
            if (!cell.getType().isAir() && !cell.isReplaceable()) return false; // клетка занята - не поставить
            int slot = ladderSlot(p);
            if (slot < 0) return climbing; // лестницы кончились: долезть по поставленным
            double cx = x + 0.5 - l.getX(), cz = z + 0.5 - l.getZ();
            if (!climbing && (Math.abs(cx) > 0.12 || Math.abs(cz) > 0.12)) {
                // Встаём в середину клетки: прижавшись к стене, лестница не встанет (там тело).
                motor.drive(p, cx, cz, 0.25, 0f, false, false);
                return true;
            }
            BotNms.input(p, 0f, 0f, false);
            if (now < nextLadderPlace) return true;
            nextLadderPlace = now + 4;
            if (!useOnFace(p, wall, face, slot) && !turning) return false;
            return true;
        }
        // Лезем: к стене с зажатым прыжком, взгляд на стену чуть вверх.
        motor.turn(p, Motor.yawTo(dx, dz), -25f, 30f);
        motor.drive(p, dx, dz, 0.5, 0f, true, false);
        return true;
    }

    private static int faceIndex(BlockFace f) {
        switch (f) {
            case DOWN: return 0;
            case UP: return 1;
            case NORTH: return 2;
            case SOUTH: return 3;
            case WEST: return 4;
            default: return 5;
        }
    }

    // ================================================================== столб

    /**
     * Строит столб под собой до высоты ног targetFeetY: прыжок, в верхней точке блок под
     * ноги. true - ещё строим (вызывать каждый тик), false - готово или нечем/некуда.
     */
    String towerWhy = "";

    boolean tower(Player p, int targetFeetY, int now) {
        Location l = p.getLocation();
        int feet = l.getBlockY();
        // В прыжке ноги выше столба: считаем по верху столба, иначе последний блок не ставится.
        boolean onG = BotNms.onGround(p);
        int built = onG || towerFeetY == Integer.MIN_VALUE ? feet : towerFeetY;
        // Последний блок встал, а мы ещё в воздухе: столб готов, ждём приземления на него.
        if (built >= targetFeetY && !onG && towerFeetY != Integer.MIN_VALUE && l.getY() > towerFeetY - 1.2) {
            towerWhy = "landing";
            BotNms.input(p, 0f, 0f, false);
            return true;
        }
        if (built >= targetFeetY || blockCount(p) == 0 || placeBlocked()) { towerWhy = "end " + feet + "/" + targetFeetY + " b=" + blockCount(p) + " pb=" + placeBlocked(); towerFeetY = Integer.MIN_VALUE; return false; }
        if (!inZone(p.getWorld().getBlockAt(l.getBlockX(), feet, l.getBlockZ()))) { towerWhy = "за зоной"; towerFeetY = Integer.MIN_VALUE; return false; }
        // Над головой должно быть место.
        Block head = p.getWorld().getBlockAt(l.getBlockX(), feet + 2, l.getBlockZ());
        if (head.getType().isSolid()) {
            if (!mine(p, head, now)) { towerWhy = "head " + head.getType(); towerFeetY = Integer.MIN_VALUE; return false; }
            towerWhy = "mine head";
            return true;
        }
        // И блок над ним: иначе прыжок упирается в потолок и не хватает высоты поставить блок.
        Block head2 = head.getRelative(BlockFace.UP);
        if (head2.getType().isSolid() && canDig(p, head2) && BotNms.onGround(p)) {
            if (mine(p, head2, now)) { towerWhy = "mine head2"; return true; }
        }
        boolean ground = BotNms.onGround(p);
        if (ground) {
            // Стоим на плите, ковре и т.п.: клетка под ногами уже занята, блок туда не встанет,
            // а до следующей клетки прыжком не достать. Столб отсюда не построить.
            Block in = p.getWorld().getBlockAt(l.getBlockX(), feet, l.getBlockZ());
            if (!in.getType().isAir() && !in.isReplaceable()) { towerWhy = "in " + in.getType(); towerFeetY = Integer.MIN_VALUE; return false; }
            towerFeetY = feet;
            // Блок в руку заранее: в верхней точке прыжка на переключение слота нет времени.
            int bs = blockSlot(p);
            if (bs >= 0 && p.getInventory().getHeldItemSlot() != bs) {
                hold.test(bs);
                if (p.getInventory().getItem(p.getInventory().getHeldItemSlot()) == null
                        || !Items.isBuildBlock(p.getInventory().getItemInMainHand())) { towerWhy = "take block"; BotNms.input(p, 0f, 0f, false); return true; }
            }
        }
        // Взгляд вниз - не рывком: на земле сначала опускаем голову, потом прыгаем.
        if (!motor.aim(p, motor.yaw(), 89f, 4f) && ground) { towerWhy = "look down"; BotNms.input(p, 0f, 0f, false); return true; }
        BotNms.input(p, 0f, 0f, ground);
        if (!ground && towerFeetY != Integer.MIN_VALUE && l.getY() >= towerFeetY + 1.02) {
            Block below = p.getWorld().getBlockAt(l.getBlockX(), towerFeetY, l.getBlockZ());
            if ((below.getType().isAir() || below.isReplaceable()) && place(p, below, true)) towerFeetY++;
            else towerWhy = "place fail " + below.getType() + " y=" + String.format("%.2f", l.getY());
        } else towerWhy = "jump g=" + ground + " y=" + String.format("%.2f", l.getY()) + " tf=" + towerFeetY;
        return true;
    }

    // ================================================================== укрытие

    /** Ставит стенку в 2 блока между ботом и врагом. true - стенка стоит. */
    boolean cover(Player p, Location enemy) {
        Location l = p.getLocation();
        Vector d = enemy.toVector().subtract(l.toVector()).setY(0);
        if (d.lengthSquared() < 1) return false;
        d.normalize();
        World w = p.getWorld();
        int fx = (int) Math.floor(l.getX() + Math.round(d.getX())), fz = (int) Math.floor(l.getZ() + Math.round(d.getZ()));
        Block feet = w.getBlockAt(fx, l.getBlockY(), fz);
        Block head = feet.getRelative(BlockFace.UP);
        if (feet.getType().isAir() || feet.isReplaceable()) {
            if (!place(p, feet)) return false;
        }
        if (head.getType().isAir() || head.isReplaceable()) return place(p, head);
        return true;
    }
}
