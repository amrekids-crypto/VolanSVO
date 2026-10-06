package dev.volansvo.svo.managers;

import dev.volansvo.svo.VolanSVO;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public class AirdropManager {

    private final VolanSVO plugin;
    private final Random rng = new Random();
    private final List<Entity> activeEntities = new ArrayList<Entity>();

    /** 6 темплейт-сундуков аирдропа в лобби (-587/-586, 313, 442..444). */
    private static final int[][] AIRDROP_TEMPLATES = {
        {-586, 313, 444}, {-587, 313, 444},
        {-586, 313, 443}, {-587, 313, 443},
        {-586, 313, 442}, {-587, 313, 442}
    };

    /** Фиксированная высота с которой стартует аирдроп. */
    private static final double AIRDROP_START_Y = 318;
    /** Буфер от worldborder в блоках чтобы зона не съела сундук до приземления. */
    private static final double BORDER_BUFFER = 60.0;
    /** За сколько тиков ДО появления самого аирдропа (сообщение/самолёт/ящик) играть звук
     *  пролетающего истребителя - звук сейчас, сам дроп появляется позже (см. triggerAirdrop). */
    private static final long AIRDROP_SOUND_LEAD_TICKS = 60L; // 3 сек

    /**
     * Высота самолёта НАД игроком (а не фикс. Y). Сервер трекает display-сущность только в
     * пределах ~78 блоков от игрока (tracking range, из конфига, плагином не меняется), поэтому
     * фикс. Y=200 был виден только с большой высоты. Относительный отступ держит самолёт в зоне
     * трекинга - видно с любой высоты.
     */
    private static final double PLANE_HEIGHT = 50.0;
    /** На сколько блоков ПОЗАДИ игрока появляется самолёт (чтобы пролетел над головой). */
    private static final double PLANE_START_BACK = 30.0;
    /** На сколько блоков ВПЕРЁД (в сторону дропа) летит самолёт от точки старта. */
    private static final double PLANE_TRAVEL = 300.0;
    /** Длительность полёта самолёта в тиках (меньше = быстрее). */
    private static final int PLANE_DURATION = 130;
    /** Сколько тиков длится уменьшение самолёта в конце пути перед исчезновением. */
    private static final int PLANE_SHRINK_TICKS = 15;
    /** Сколько тиков держится дымный след за самолётом (10 сек). */
    private static final int PLANE_TRAIL_DURATION = 200;
    /** За сколько тиков после окончания полёта дым полностью растворяется (быстро). */
    private static final int PLANE_FADE_TICKS = 30;
    /** Локальный отступ точек дыма от центра по X (две полосы - левая/правая законцовки). */
    private static final double PLANE_TRAIL_X = 12.0;
    /** На сколько блоков назад тянется готовый дымный след (иллюзия "прилетел издалека"). */
    private static final double PLANE_TRAIL_BACK = 100.0;
    /**
     * Части самолёта в ЛОКАЛЬНЫХ координатах (вперёд = +Z, вправо = +X, вверх = +Y):
     * {offsetX, offsetY, offsetZ, sizeX, sizeY, sizeZ}. Крылья = 26 блоков по X.
     */
    private static final double[][] PLANE_PARTS = {
        {0, 0, -1, 26, 0.6, 5},          // 1 крылья
        {0, 0, 0, 2.6, 2.0, 16},         // 2 фюзеляж (низ)
        {0, 1.0, -0.5, 2.0, 1.2, 14},    // 3 фюзеляж (верх/спина)
        {0, 0.1, 8.3, 1.8, 1.8, 2.2},    // 4 нос (конус)
        {0, 1.0, 6.4, 1.7, 1.0, 2.6},    // 5 кабина (стекло)
        {0, 2.5, -7, 0.5, 4.5, 3},       // 6 киль (вертикальный хвост)
        {0, 4.4, -7.4, 0.6, 1.2, 2.2},   // 7 верхушка киля (красная)
        {0, 0.5, -7, 10, 0.5, 2.5},      // 8 горизонтальный хвост
        {7, -0.8, 1.5, 1.8, 1.8, 5},     // 9 двигатель правый
        {-7, -0.8, 1.5, 1.8, 1.8, 5},    // 10 двигатель левый
        {7, -0.8, 4.1, 2.0, 2.0, 0.7},   // 11 воздухозаборник правый (чёрный)
        {-7, -0.8, 4.1, 2.0, 2.0, 0.7},  // 12 воздухозаборник левый (чёрный)
        {12.7, 0.5, -1, 0.6, 1.8, 3},    // 13 законцовка крыла правая (винглет)
        {-12.7, 0.5, -1, 0.6, 1.8, 3},   // 14 законцовка крыла левая (винглет)
        {0, -0.15, 0, 2.7, 0.5, 14}      // 15 брюшная полоса (синяя)
    };
    private static final Material[] PLANE_MATS = {
        Material.WHITE_CONCRETE,         // 1
        Material.LIGHT_GRAY_CONCRETE,    // 2
        Material.WHITE_CONCRETE,         // 3
        Material.GRAY_CONCRETE,          // 4
        Material.LIGHT_BLUE_STAINED_GLASS, // 5
        Material.WHITE_CONCRETE,         // 6
        Material.RED_CONCRETE,           // 7
        Material.WHITE_CONCRETE,         // 8
        Material.GRAY_CONCRETE,          // 9
        Material.GRAY_CONCRETE,          // 10
        Material.BLACK_CONCRETE,         // 11
        Material.BLACK_CONCRETE,         // 12
        Material.RED_CONCRETE,           // 13
        Material.RED_CONCRETE,           // 14
        Material.BLUE_CONCRETE           // 15
    };

    private BukkitTask descentTask = null;

    /** Форслоад чанка падающего аирдропа - чтобы сущности не выгрузились вдали от игроков. */
    private boolean airdropChunkForced = false;
    private int forcedCX, forcedCZ;
    private World forcedWorld = null;

    public AirdropManager(VolanSVO plugin) {
        this.plugin = plugin;
    }

    /** Чанки, которые держим тикетом плагина до конца игры (место дропа, шаблоны лута). */
    private final java.util.Set<Long> heldChunks = new java.util.HashSet<Long>();
    private World heldWorld;

    private void holdChunkAsync(final World world, final int cx, final int cz) {
        if (heldWorld != null && !heldWorld.equals(world)) releaseHeldChunks();
        heldWorld = world;
        if (!heldChunks.add(((long) cx << 32) ^ (cz & 0xffffffffL))) return;
        world.getChunkAtAsync(cx, cz, true).thenAccept(c -> {
            if (heldWorld == world) world.addPluginChunkTicket(cx, cz, plugin);
        });
    }

    /** Чанк места падения держим до приземления (дальше его держит форслоад спуска). */
    private World dropTicketWorld;
    private int dropTicketX, dropTicketZ;

    private void holdDropChunkAsync(final World world, final int cx, final int cz) {
        releaseDropTicket();
        dropTicketWorld = world; dropTicketX = cx; dropTicketZ = cz;
        world.getChunkAtAsync(cx, cz, true).thenAccept(c -> {
            if (dropTicketWorld == world && dropTicketX == cx && dropTicketZ == cz) world.addPluginChunkTicket(cx, cz, plugin);
        });
    }

    private void releaseDropTicket() {
        if (dropTicketWorld == null) return;
        try { dropTicketWorld.removePluginChunkTicket(dropTicketX, dropTicketZ, plugin); } catch (Throwable ignored) {}
        dropTicketWorld = null;
    }

    private void releaseHeldChunks() {
        if (heldWorld != null) {
            for (long k : heldChunks) {
                try { heldWorld.removePluginChunkTicket((int) (k >> 32), (int) k, plugin); } catch (Throwable ignored) {}
            }
        }
        heldChunks.clear();
        heldWorld = null;
    }

    /** Удерживает чанк аирдропа загруженным (снимая прошлый форслоад если был). */
    private void forceAirdropChunk(World world, int blockX, int blockZ) {
        unforceAirdropChunk();
        int cx = blockX >> 4;
        int cz = blockZ >> 4;
        try {
            world.setChunkForceLoaded(cx, cz, true);
            forcedWorld = world; forcedCX = cx; forcedCZ = cz; airdropChunkForced = true;
        } catch (Throwable ignored) {}
    }

    /** Снимает форслоад с чанка аирдропа. */
    private void unforceAirdropChunk() {
        releaseDropTicket();
        if (!airdropChunkForced || forcedWorld == null) return;
        try { forcedWorld.setChunkForceLoaded(forcedCX, forcedCZ, false); } catch (Throwable ignored) {}
        airdropChunkForced = false;
        forcedWorld = null;
    }

    public void triggerAirdrop(final World world, List<Player> activePlayers) {
        if (activePlayers.isEmpty()) return;
        killOldAirdropEntities(world);

        // РАНДОМ точка внутри worldborder (с буфером) - без привязки к конфиг-точкам
        WorldBorder wb = world.getWorldBorder();
        double cx = wb.getCenter().getX();
        double cz = wb.getCenter().getZ();
        double half = wb.getSize() / 2.0 - BORDER_BUFFER;
        if (half <= 0) {
            // Барьер слишком маленький - ничего не пишем в чат
            return;
        }
        final double tx = cx + (rng.nextDouble() * 2 - 1) * half;
        final double tz = cz + (rng.nextDouble() * 2 - 1) * half;
        final double ty = AIRDROP_START_Y;

        // Чанки места падения и шаблона лута грузим в фоне (до появления дропа 3 сек) и
        // держим тикетом: синхронная загрузка дальнего чанка давала пик тика в 100-200 мс.
        holdDropChunkAsync(world, (int) tx >> 4, (int) tz >> 4);
        dev.volansvo.svo.maps.MapData md = plugin.getMapManager().getActiveMap();
        java.util.List<int[]> tmpls = md != null && md.hasAirdropTemplates()
            ? md.getAirdropTemplates() : java.util.Arrays.asList(AIRDROP_TEMPLATES);
        for (int[] tmpl : tmpls) holdChunkAsync(world, tmpl[0] >> 4, tmpl[2] >> 4);

        // Звук пролетающего истребителя - играем СЕЙЧАС, за AIRDROP_SOUND_LEAD_TICKS (3 сек)
        // ДО того как сам аирдроп реально появится (сообщение/самолёт/ящик, см. ниже).
        for (Player p : world.getPlayers()) {
            p.playSound(p.getLocation(), "custom:zvuk-letyaschego-istrebitelya", 3.0f, 1.0f);
        }

        new BukkitRunnable() {
            @Override public void run() { triggerAirdropVisual(world, tx, tz, ty); }
        }.runTaskLater(plugin, AIRDROP_SOUND_LEAD_TICKS);
    }

    /** Сама видимая часть аирдропа (сообщение/самолёт/карта/ящик) - запускается через
     *  AIRDROP_SOUND_LEAD_TICKS после звука пролетающего истребителя (см. triggerAirdrop). */
    private void triggerAirdropVisual(World world, double tx, double tz, double ty) {
        // Сообщение - просто что падает
        plugin.getGameManager().broadcastActive(ChatColor.GOLD + "" + ChatColor.BOLD + "АИРДРОП падает!");

        // Самолёт-анонс: над каждым игроком (виден только ему) пролетает в сторону места дропа
        spawnPlanesForPlayers(world, tx, tz);

        // Карта + красный маркер всем живым - ТОЛЬКО если у карты есть map_id (или карта не выбрана).
        // На east миникарты нет (map_id не задан) - карту/курсор не трогаем.
        dev.volansvo.svo.maps.MapData mapData = plugin.getMapManager().getActiveMap();
        if (mapData == null || mapData.hasMapId()) {
            int mapId = (mapData != null) ? mapData.getMapId() : 106;
            for (Player p : plugin.getGameManager().getActivePlayers()) {
                plugin.getLootManager().giveMap(p, mapId);
            }
            plugin.getLootManager().setAirdropCursor(mapId, tx, tz);
        }

        int floorY = computeFloorY(world, (int) tx, (int) ty, (int) tz);
        spawnAirdrop(world, tx, ty, tz, floorY);
    }

    private int computeFloorY(World world, int x, int startY, int z) {
        for (int py = startY - 1; py > world.getMinHeight() + 5; py--) {
            Material t = world.getBlockAt(x, py, z).getType();
            if (t.isSolid() || t == Material.WATER) {
                return py + 1;
            }
        }
        return world.getMinHeight() + 5;
    }

    private void killOldAirdropEntities(World world) {
        for (Entity e : new ArrayList<Entity>(activeEntities)) {
            if (e.isValid()) e.remove();
        }
        activeEntities.clear();
        for (Entity e : world.getEntities()) {
            Set<String> tags = e.getScoreboardTags();
            if (tags.contains("airpig") || tags.contains("air_chest")
                || tags.contains("air_fence") || tags.contains("air_wool")
                || tags.contains("fallingchestt") || tags.contains("air_balloon")
                || tags.contains("air_plane")) {
                e.remove();
            }
        }
        if (descentTask != null) { descentTask.cancel(); descentTask = null; }
        unforceAirdropChunk();
    }

    private void spawnAirdrop(final World world, final double tx, final double ty, final double tz, final int floorY) {
        final Location startLoc = new Location(world, tx, ty, tz);

        // КЛЮЧЕВОЕ: держим чанк аирдропа загруженным на весь спуск. Аирдроп падает в случайной
        // точке зоны, часто вдали от игроков - без форслоада чанк выгрузится, сущности станут
        // невалидными, спуск остановится и аирдроп "зависнет" в воздухе.
        forceAirdropChunk(world, (int) tx, (int) tz);

        // Видимый зомби-стенд для попадания (невидимый но не маркер, обычный размер)
        final ArmorStand airpig = (ArmorStand) world.spawnEntity(startLoc, EntityType.ARMOR_STAND);
        airpig.setVisible(false);
        airpig.setSmall(false);
        airpig.setMarker(false);          // ОБЯЗАТЕЛЬНО - иначе хитбокс пропадает
        airpig.setGravity(false);
        airpig.setInvulnerable(false);    // принимает урон
        airpig.setBasePlate(false);
        airpig.setArms(false);
        airpig.addScoreboardTag("airpig");
        activeEntities.add(airpig);

        // Block displays с правильными scale (как в оригинальном NBT)
        final BlockDisplay airChest = spawnBlockDisplay(world, startLoc,                            Material.CHEST,          "air_chest", 1.0f, 1.0f, 1.0f);
        final BlockDisplay airFence = spawnBlockDisplay(world, startLoc.clone().add(0, 0.5, 0),  Material.DARK_OAK_FENCE, "air_fence", 1.0f, 1.5f, 1.0f);
        final BlockDisplay airWool  = spawnBlockDisplay(world, startLoc.clone().add(0, 2.0, 0),  Material.WHITE_WOOL,     "air_wool",  0.9f, 1.2f, 0.9f);

        descentTask = new BukkitRunnable() {
            int ticks = 0;
            double y = ty;
            final double speed = 0.15;

            @Override
            public void run() {
                if (!airpig.isValid()) { cancel(); descentTask = null; unforceAirdropChunk(); return; }
                if (++ticks > 4000) {
                    land(world, tx, y, tz);
                    cleanup();
                    cancel(); descentTask = null;
                    return;
                }

                y -= speed;
                Location nl = new Location(world, tx, y, tz);
                airpig.teleport(nl);
                if (airChest != null && airChest.isValid()) airChest.teleport(nl);
                if (airFence != null && airFence.isValid()) airFence.teleport(new Location(world, tx, y + 0.5, tz));
                if (airWool != null && airWool.isValid())   airWool.teleport(new Location(world, tx, y + 2.0, tz));

                // Партикл-след отправляем КАЖДОМУ игроку индивидуально - минует view distance
                if (ticks % 2 == 0) {
                    for (Player viewer : world.getPlayers()) {
                        if (plugin.getGameManager().isBot(viewer.getUniqueId())) continue;
                        viewer.spawnParticle(Particle.CRIT, nl.getX(), nl.getY() + 10, nl.getZ(),
                            30, 0, 5, 0, 0, null, true);
                        viewer.spawnParticle(Particle.CLOUD, nl.getX(), nl.getY(), nl.getZ(),
                            3, 0.3, 0.1, 0.3, 0.01, null, true);
                    }
                }

                // Столб партиклов от airpig до пола - каждому игроку, force=true чтобы видно издалека
                int curY = (int) Math.floor(y);
                for (Player viewer : world.getPlayers()) {
                    if (plugin.getGameManager().isBot(viewer.getUniqueId())) continue;
                    for (int py = floorY; py <= curY; py += 2) {
                        viewer.spawnParticle(Particle.SMOKE,
                            tx + 0.5, py + 0.5, tz + 0.5,
                            1, 0.05, 0.05, 0.05, 0, null, true);
                    }
                }

                Block below = world.getBlockAt((int) Math.floor(tx), (int) Math.floor(y - 1), (int) Math.floor(tz));
                // Вода тоже останавливает падение - аирдроп плавает на поверхности
                if (below.getType().isSolid() || below.getType() == Material.WATER) {
                    land(world, tx, y, tz);
                    cleanup();
                    cancel(); descentTask = null;
                }
            }

            private void cleanup() {
                if (airpig.isValid())  airpig.remove();
                if (airChest != null && airChest.isValid()) airChest.remove();
                if (airFence != null && airFence.isValid()) airFence.remove();
                if (airWool  != null && airWool.isValid())  airWool.remove();
                activeEntities.remove(airpig);
                activeEntities.remove(airChest);
                activeEntities.remove(airFence);
                activeEntities.remove(airWool);
                unforceAirdropChunk();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private BlockDisplay spawnBlockDisplay(World world, Location loc, Material mat, String tag,
                                            float scaleX, float scaleY, float scaleZ) {
        try {
            BlockDisplay bd = world.spawn(loc, BlockDisplay.class);
            bd.setBlock(mat.createBlockData());
            bd.addScoreboardTag(tag);
            // Transformation как в оригинале: translation (-0.5, 0, -0.5) центрирует блок на entity,
            // scale задаёт размер блока (1.0 = одна клетка, 1.5 = в 1.5 раза выше итд)
            Transformation t = new Transformation(
                new Vector3f(-0.5f, 0f, -0.5f),
                new Quaternionf(0, 0, 0, 1),
                new Vector3f(scaleX, scaleY, scaleZ),
                new Quaternionf(0, 0, 0, 1)
            );
            bd.setTransformation(t);
            bd.setInterpolationDuration(0);
            bd.setInterpolationDelay(0);
            bd.setTeleportDuration(0);
            activeEntities.add(bd);
            return bd;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Для каждого активного игрока спавнит большой самолёт из BlockDisplay, который пролетает
     * прямо НАД игроком в сторону аирдропа (высота - отступ над игроком, чтобы всегда быть в
     * зоне трекинга и быть видимым с любой высоты). Самолёт виден ТОЛЬКО своему игроку
     * (от остальных скрыт через hideEntity), по пути плавно "растворяется" и пропадает.
     */
    private void spawnPlanesForPlayers(final World world, final double tx, final double tz) {
        for (final Player player : plugin.getGameManager().getActivePlayers()) {
            if (player == null || !player.isOnline() || !player.getWorld().equals(world)) continue;
            if (plugin.getGameManager().isBot(player.getUniqueId())) continue;

            final Location ploc = player.getLocation();
            double ddx = tx - ploc.getX();
            double ddz = tz - ploc.getZ();
            double len = Math.sqrt(ddx * ddx + ddz * ddz);
            // направление к дропу (если игрок ровно на дропе - произвольное)
            final double dirX = (len < 0.001) ? 0.0 : ddx / len;
            final double dirZ = (len < 0.001) ? 1.0 : ddz / len;

            // Высота - ОТНОСИТЕЛЬНО игрока (фикс. отступ), чтобы появиться в зоне трекинга.
            final double planeY = ploc.getY() + PLANE_HEIGHT;
            // Старт чуть позади игрока, дальше летит вперёд (в сторону дропа) на PLANE_TRAVEL.
            final double startX = ploc.getX() - dirX * PLANE_START_BACK;
            final double startZ = ploc.getZ() - dirZ * PLANE_START_BACK;
            final double travel = PLANE_TRAVEL;

            // yaw такой, что локальный +Z (нос) смотрит в сторону дропа
            final float yaw = (float) Math.atan2(dirX, dirZ);
            final Quaternionf rot = new Quaternionf().rotateY(yaw);

            final List<BlockDisplay> parts = new ArrayList<BlockDisplay>();
            final List<Vector3f> localOffsets = new ArrayList<Vector3f>();
            for (int i = 0; i < PLANE_PARTS.length; i++) {
                double[] pd = PLANE_PARTS[i];
                BlockDisplay bd = spawnPlanePart(world, startX, planeY, startZ, PLANE_MATS[i],
                    (float) pd[3], (float) pd[4], (float) pd[5], rot);
                if (bd == null) continue;
                // прячем от всех остальных игроков - каждый видит только свой самолёт
                for (Player other : Bukkit.getOnlinePlayers()) {
                    if (!other.equals(player)) {
                        try { other.hideEntity(plugin, bd); } catch (Throwable ignored) {}
                    }
                }
                parts.add(bd);
                localOffsets.add(new Vector3f((float) pd[0], (float) pd[1], (float) pd[2]));
            }
            if (parts.isEmpty()) continue;

            // Две полосы дыма: записываем мировые точки под левой/правой законцовкой во время
            // полёта, затем продолжаем дымить из них ещё до 10 сек суммарно.
            final List<double[]> leftTrail = new ArrayList<double[]>();
            final List<double[]> rightTrail = new ArrayList<double[]>();

            // Предзаполняем след на PLANE_TRAIL_BACK блоков НАЗАД (против полёта) - чтобы сразу
            // тянулся длинный дым, будто самолёт прилетел издалека.
            {
                Vector3f lo = new Vector3f((float) -PLANE_TRAIL_X, 0f, -1f); rot.transform(lo);
                Vector3f ro = new Vector3f((float)  PLANE_TRAIL_X, 0f, -1f); rot.transform(ro);
                for (double d = PLANE_TRAIL_BACK; d > 0; d -= 2.5) {
                    double bx = startX - dirX * d;
                    double bz = startZ - dirZ * d;
                    leftTrail.add(new double[]{bx + lo.x, planeY + lo.y, bz + lo.z});
                    rightTrail.add(new double[]{bx + ro.x, planeY + ro.y, bz + ro.z});
                }
            }
            new BukkitRunnable() {
                int t = 0;
                boolean planeRemoved = false; // корпус уже удалён (улетел к границе прогрузки)
                int trailFullSize = -1;       // длина следа на момент окончания полёта
                @Override public void run() {
                  try {
                    t++;

                    // --- фаза полёта (полный размер) + фаза уменьшения в конце ---
                    final int flightEnd = PLANE_DURATION;
                    final int shrinkEnd = PLANE_DURATION + PLANE_SHRINK_TICKS;
                    // Дым тает быстро и постепенно после окончания полёта: точки удаляются
                    // с ДАЛЬНЕГО от аирдропа конца (индекс 0) к ближнему (конец списка).
                    final int fadeEnd = flightEnd + PLANE_FADE_TICKS;
                    if (t <= shrinkEnd && player.isOnline()) {
                        double scaleMul;
                        double baseX, baseZ;
                        if (t <= flightEnd) {
                            // летит вперёд БЕЗ уменьшения
                            double progress = (double) t / flightEnd;
                            scaleMul = 1.0;
                            baseX = startX + dirX * travel * progress;
                            baseZ = startZ + dirZ * travel * progress;
                        } else {
                            // долетел - уменьшается на месте и пропадает
                            double sp = (double) (t - flightEnd) / PLANE_SHRINK_TICKS; // 0..1
                            scaleMul = 1.0 - sp;
                            baseX = startX + dirX * travel;
                            baseZ = startZ + dirZ * travel;
                        }

                        // Двигаем КОРПУС только пока его чанк загружен. Телепорт в невыгруженный
                        // чанк оставлял бы сущности там навсегда (isValid()=false -> remove()
                        // не срабатывает). У границы прогрузки удаляем корпус - он ещё в
                        // загруженном чанке, remove() надёжно отработает. Дым тянется дальше сам.
                        if (!planeRemoved) {
                            int cx = (int) Math.floor(baseX) >> 4;
                            int cz = (int) Math.floor(baseZ) >> 4;
                            if (!world.isChunkLoaded(cx, cz)) {
                                removePlaneParts(parts);
                                planeRemoved = true;
                            } else {
                                for (int i = 0; i < parts.size(); i++) {
                                    BlockDisplay bd = parts.get(i);
                                    if (!bd.isValid()) continue;
                                    double[] pd = PLANE_PARTS[i];
                                    Vector3f off = new Vector3f(localOffsets.get(i)).mul((float) scaleMul);
                                    rot.transform(off);
                                    bd.teleport(new Location(world, baseX + off.x, planeY + off.y, baseZ + off.z));
                                    applyPlaneTransform(bd, (float) (pd[3] * scaleMul),
                                        (float) (pd[4] * scaleMul), (float) (pd[5] * scaleMul), rot);
                                }
                                if (t == shrinkEnd) { removePlaneParts(parts); planeRemoved = true; }
                            }
                        }

                        // точки двух полос дыма пишем всю фазу полёта (даже если корпус уже
                        // удалён) - след тянется по всему виртуальному пути до 300 блоков.
                        if (t <= flightEnd) {
                            Vector3f l = new Vector3f((float) -PLANE_TRAIL_X, 0f, -1f); rot.transform(l);
                            Vector3f r = new Vector3f((float)  PLANE_TRAIL_X, 0f, -1f); rot.transform(r);
                            leftTrail.add(new double[]{baseX + l.x, planeY + l.y, baseZ + l.z});
                            rightTrail.add(new double[]{baseX + r.x, planeY + r.y, baseZ + r.z});
                        }
                    }

                    // Момент окончания полёта - запоминаем полную длину следа для расчёта таяния.
                    if (t == flightEnd && trailFullSize < 0) trailFullSize = leftTrail.size();

                    // --- постепенное таяние: обрезаем след с ДАЛЬНЕГО конца (индекс 0) ---
                    if (t > flightEnd && trailFullSize > 0) {
                        double fp = (double) (t - flightEnd) / PLANE_FADE_TICKS; // 0..1
                        if (fp > 1.0) fp = 1.0;
                        int keep = (int) Math.round(trailFullSize * (1.0 - fp));
                        while (leftTrail.size()  > keep && !leftTrail.isEmpty())  leftTrail.remove(0);
                        while (rightTrail.size() > keep && !rightTrail.isEmpty()) rightTrail.remove(0);
                    }

                    // --- эмиссия дыма из обеих полос (густой, виден издалека) ---
                    if (t % 2 == 0 && player.isOnline()) {
                        for (double[] p : leftTrail)
                            player.spawnParticle(Particle.LARGE_SMOKE, p[0], p[1], p[2], 1, 0.15, 0.15, 0.15, 0.0, null, true);
                        for (double[] p : rightTrail)
                            player.spawnParticle(Particle.LARGE_SMOKE, p[0], p[1], p[2], 1, 0.15, 0.15, 0.15, 0.0, null, true);
                    }

                    if (t >= fadeEnd || (leftTrail.isEmpty() && rightTrail.isEmpty()) || !player.isOnline()) {
                        removePlaneParts(parts);
                        cancel();
                    }
                  } catch (Throwable ex) {
                    // Любое исключение НЕ должно оставить самолёт висеть: чистим и выходим.
                    removePlaneParts(parts);
                    cancel();
                  }
                }
            }.runTaskTimer(plugin, 1L, 1L);
        }
    }

    /**
     * Надёжно удаляет части самолёта. Если часть оказалась в невыгруженном чанке
     * (isValid()=false), подгружаем её чанк - тогда remove() гарантированно сработает,
     * и самолёт не "зависает" над аирдропом.
     */
    private void removePlaneParts(java.util.List<BlockDisplay> parts) {
        for (BlockDisplay bd : parts) {
            if (bd == null) continue;
            try {
                activeEntities.remove(bd);
                bd.remove();
            } catch (Throwable ignored) {}
        }
    }

    private BlockDisplay spawnPlanePart(World world, double x, double y, double z, Material mat,
                                        float sx, float sy, float sz, Quaternionf rot) {
        try {
            BlockDisplay bd = world.spawn(new Location(world, x, y, z), BlockDisplay.class);
            bd.setBlock(mat.createBlockData());
            bd.addScoreboardTag("air_plane");
            bd.setPersistent(false);
            // Большой viewRange - чтобы клиент рисовал display-сущность с дистанции (иначе
            // пропадает дальше ~32 блоков). Дальность серверного трекинга это не меняет.
            bd.setViewRange(20.0f);
            bd.setTeleportDuration(1);     // плавное движение между тиками
            bd.setInterpolationDuration(1);
            bd.setInterpolationDelay(0);
            applyPlaneTransform(bd, sx, sy, sz, rot);
            activeEntities.add(bd);
            return bd;
        } catch (Throwable t) {
            return null;
        }
    }

    private void applyPlaneTransform(BlockDisplay bd, float sx, float sy, float sz, Quaternionf rot) {
        // центрируем повёрнутый блок на entity: translation = rot * (-size/2)
        Vector3f trans = new Vector3f(-sx / 2f, -sy / 2f, -sz / 2f);
        rot.transform(trans);
        Transformation tr = new Transformation(
            trans,
            new Quaternionf(rot),
            new Vector3f(sx, sy, sz),
            new Quaternionf(0, 0, 0, 1)
        );
        bd.setTransformation(tr);
    }

    /** Сбили airpig - сундук быстро падает, шар улетает вверх. */
    public void onAirpigShot(ArmorStand airpig) {
        if (!airpig.isValid()) return;
        final Location loc = airpig.getLocation();
        final World world = airpig.getWorld();

        // Быстрое управляемое падение сундука (BlockDisplay), а НЕ vanilla FallingBlock:
        // FallingBlock над водой исчезает и сундук терялся. Тут мы сами ловим
        // поверхность (вода/земля) и ставим сундук через land().
        final double dropX = loc.getX();
        final double dropZ = loc.getZ();
        BlockDisplay fallChestTmp;
        try {
            fallChestTmp = world.spawn(loc, BlockDisplay.class);
            fallChestTmp.setBlock(Material.CHEST.createBlockData());
            fallChestTmp.addScoreboardTag("fallingchestt");
            Transformation ct = new Transformation(
                new Vector3f(-0.5f, 0f, -0.5f),
                new Quaternionf(0, 0, 0, 1),
                new Vector3f(1f, 1f, 1f),
                new Quaternionf(0, 0, 0, 1)
            );
            fallChestTmp.setTransformation(ct);
            fallChestTmp.setTeleportDuration(0);
            activeEntities.add(fallChestTmp);
        } catch (Throwable t) {
            fallChestTmp = null;
        }
        final BlockDisplay fallChest = fallChestTmp;
        new BukkitRunnable() {
            double y = loc.getY();
            @Override public void run() {
                y -= 1.2; // быстрое падение
                Block below = world.getBlockAt((int) Math.floor(dropX), (int) Math.floor(y - 1), (int) Math.floor(dropZ));
                boolean landed = below.getType().isSolid() || below.getType() == Material.WATER
                    || y <= world.getMinHeight() + 2;
                if (landed) {
                    if (fallChest != null && fallChest.isValid()) { fallChest.remove(); activeEntities.remove(fallChest); }
                    land(world, dropX, y, dropZ);
                    unforceAirdropChunk();
                    cancel();
                    return;
                }
                if (fallChest != null && fallChest.isValid()) {
                    fallChest.teleport(new Location(world, dropX, y, dropZ));
                } else {
                    // дисплей не создался - всё равно приземляем по достижении поверхности
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        // Шар (white_wool) улетает ВВЕРХ
        Location woolLoc = loc.clone().add(0, 2, 0);
        try {
            final BlockDisplay balloon = world.spawn(woolLoc, BlockDisplay.class);
            balloon.setBlock(Material.WHITE_WOOL.createBlockData());
            balloon.addScoreboardTag("air_balloon");
            // Тот же transformation что у air_wool в обычном спавне
            Transformation bt = new Transformation(
                new Vector3f(-0.5f, 0f, -0.5f),
                new Quaternionf(0, 0, 0, 1),
                new Vector3f(0.9f, 1.2f, 0.9f),
                new Quaternionf(0, 0, 0, 1)
            );
            balloon.setTransformation(bt);
            balloon.setTeleportDuration(0);
            activeEntities.add(balloon);
            new BukkitRunnable() {
                int t = 0;
                @Override public void run() {
                    if (!balloon.isValid() || t++ > 200) {
                        if (balloon.isValid()) balloon.remove();
                        activeEntities.remove(balloon);
                        cancel();
                        return;
                    }
                    balloon.teleport(balloon.getLocation().add(0, 0.5, 0));
                    world.spawnParticle(Particle.CLOUD, balloon.getLocation().getX(), balloon.getLocation().getY(),
                        balloon.getLocation().getZ(), 2, 0.2, 0.1, 0.2, 0.01, null, true);
                }
            }.runTaskTimer(plugin, 1L, 1L);
        } catch (Throwable ignored) {}

        // Удаляем air_chest/air_fence И исходный air_wool (иначе он остаётся стоять на месте
        // сбития, дублируя улетающий вверх шар air_balloon).
        for (Entity e : world.getNearbyEntities(loc, 6, 6, 6)) {
            Set<String> tags = e.getScoreboardTags();
            if (tags.contains("air_chest") || tags.contains("air_fence") || tags.contains("air_wool")) {
                e.remove();
                activeEntities.remove(e);
            }
        }

        airpig.remove();
        activeEntities.remove(airpig);
        if (descentTask != null) { descentTask.cancel(); descentTask = null; }

        plugin.getGameManager().broadcastActive(ChatColor.AQUA + "Аирдроп сбит! Сундук падает.");
    }

    /** Где приземлился последний аирдроп (для ботов). null - нет или убран. */
    private Location lastLanded = null;
    public Location getLastLanded() { return lastLanded; }

    private void land(World world, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        // Ищем поверхность, начиная ВЫСОКО над точкой удара (+20), чтобы надёжно
        // найти верх воды, даже если на момент вызова сундук уже у/под поверхностью.
        int by = surfaceY(world, bx, (int) Math.ceil(y) + 20, bz);
        placeAndFillAirdropChest(world, bx, by, bz);
        Location loc = new Location(world, bx + 0.5, by + 0.5, bz + 0.5);
        lastLanded = new Location(world, bx, by, bz);
        world.spawnParticle(Particle.EXPLOSION, loc.getX(), loc.getY(), loc.getZ(),
            10, 1, 1, 1, 0.1, null, true);
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.2f);
        plugin.getGameManager().broadcastActive(ChatColor.GOLD + "Аирдроп приземлился!");
    }

    /**
     * Возвращает Y для сундука:
     *  - над первым (сверху вниз) ТВЁРДЫМ блоком: solidY + 1;
     *  - если первой встретилась ВОДА: ставим НА месте верхнего блока воды (waterY),
     *    заменяя воду - сундук сидит на поверхности воды, а не тонет.
     */
    private int surfaceY(World world, int x, int startY, int z) {
        int maxY = Math.min(startY, world.getMaxHeight() - 1);
        for (int py = maxY; py > world.getMinHeight() + 1; py--) {
            Material t = world.getBlockAt(x, py, z).getType();
            if (t == Material.WATER) {
                return py;       // заменяем верхний блок воды - сундук на поверхности
            }
            if (t.isSolid()) {
                return py + 1;   // на твёрдой земле
            }
        }
        return world.getMinHeight() + 5;
    }

    public void placeAndFillAirdropChest(World world, int bx, int by, int bz) {
        String dim = world.getKey().toString();
        // Берём темплейты из активной карты СВО, иначе хардкод
        dev.volansvo.svo.maps.MapData md = plugin.getMapManager().getActiveMap();
        int[][] templates;
        if (md != null && md.hasAirdropTemplates()) {
            templates = md.getAirdropTemplates().toArray(new int[0][]);
        } else {
            templates = AIRDROP_TEMPLATES;
        }
        int[] src = templates[rng.nextInt(templates.length)];
        // Шаблон карты может лежать в незагруженном чанке - тогда "data modify ... from block"
        // молча не копирует лут и аирдроп приходит пустым.
        world.getChunkAt(src[0] >> 4, src[2] >> 4).load(true);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            "execute in " + dim + " run setblock " + bx + " " + by + " " + bz + " minecraft:chest replace");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            "execute in " + dim + " run data modify block " + bx + " " + by + " " + bz
            + " Items set from block " + src[0] + " " + src[1] + " " + src[2] + " Items");
    }

    public void cleanupEntities() {
        lastLanded = null;
        releaseHeldChunks();
        releaseDropTicket();
        for (Entity e : activeEntities) if (e.isValid()) e.remove();
        activeEntities.clear();
        plugin.getLootManager().clearAirdropCursor();
        if (descentTask != null) { descentTask.cancel(); descentTask = null; }
        unforceAirdropChunk();
    }
}
