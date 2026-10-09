package dev.volansvo.svo.managers;

import dev.volansvo.svo.VolanSVO;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.util.*;

public class LootManager {

    private final VolanSVO plugin;
    private final Random rng = new Random();

    /** Идёт ли сейчас перезаполнение сундуков. refreshChests() дёргается на КАЖДУЮ смерть
     *  игрока - если смерти идут чаще, чем длится один проход по всем сундукам карты (десятки
     *  тиков), запуски раньше накладывались друг на друга и удваивали/утраивали нагрузку.
     *  Пока идёт текущий проход - новый просто не запускаем (следующая смерть перезапустит). */
    private volatile boolean refillInProgress = false;
    /** Перезаполнение, запрошенное во время идущего прохода (выполним после него). */
    private World pendingWorld;
    private dev.volansvo.svo.maps.MapData pendingMap;

    /** 254 координаты сундуков (из оригинального place_chests.mcfunction). */
    private static final int[][] CHEST_COORDS = {
        {-1062,65,-13},{-1007,73,-24},{-1013,73,-24},{-1007,73,-25},{-1013,73,-25},
        {-922,67,2},{-921,89,-9},{-936,78,2},{-929,90,2},{-936,78,-18},
        {-922,73,-18},{-921,72,4},{-901,78,-18},{-965,74,53},{-974,64,50},
        {-977,68,51},{-973,80,44},{-972,65,36},{-985,69,99},{-985,70,99},
        {-989,69,99},{-989,70,99},{-957,68,140},{-956,71,142},{-957,65,84},
        {-960,71,95},{-512,80,-29},{-509,80,-29},{-518,80,-20},{-510,76,-30},
        {-510,76,-20},{-516,76,-22},{-509,76,-12},{-557,77,-20},{-557,77,-23},
        {-556,77,-29},{-543,111,-29},{-555,107,-20},{-521,101,-24},{-568,76,-3},
        {-568,76,8},{-565,99,4},{-486,117,20},{-486,100,28},{-495,100,28},
        {-486,94,28},{-495,94,28},{-495,88,28},{-1050,63,197},{-1058,64,178},
        {-1060,64,185},{-1058,69,189},{-1054,74,183},{-1067,79,183},{-212,144,85},
        {-217,149,105},{-207,149,91},{-216,154,93},{-211,166,85},{-214,144,128},
        {-214,149,125},{-191,149,109},{-196,159,122},{-200,171,116},{-238,144,113},
        {-243,149,113},{-236,154,102},{-236,165,111},{-155,191,316},{-150,185,316},
        {-146,179,328},{-151,173,341},{-152,166,329},{-147,158,326},{-153,158,354},
        {-148,164,363},{-152,170,351},{-149,188,354},{-146,157,343},{-177,157,391},
        {-166,169,380},{-180,181,391},{-193,163,369},{-187,169,376},{-189,181,386},
        {-191,187,386},{-193,158,347},{-206,170,354},{-202,176,340},{-189,188,345},
        {-191,200,337},{-201,210,342},{-173,160,320},{-176,157,319},{-833,73,306},
        {-815,73,298},{-825,69,304},{-817,65,298},{-880,65,257},{-847,70,242},
        {-846,75,246},{-878,91,248},{-840,80,258},{-871,85,260},{-813,85,246},
        {-819,75,234},{-844,75,248},{-814,72,248},{-850,67,194},{-269,120,751},
        {-971,80,300},{-736,222,56},{-724,204,56},{-689,210,56},{-704,222,61},
        {-739,222,47},{-751,222,58},{-702,212,50},{-687,213,60},{-746,262,58},
        {-673,248,66},{-741,270,106},{-789,286,56},{-881,260,31},{-738,280,35},
        {-641,104,603},{-634,102,617},{-595,102,651},{-566,102,652},{-521,110,644},
        {-525,103,604},{-776,64,578},{-796,83,601},{-784,87,663},{-776,73,620},
        {-753,80,613},{-779,69,601},{-755,63,629},{-790,59,593},{-773,84,597},
        {-847,71,474},{-645,62,111},{-139,100,869},{-145,106,861},{-980,67,484},
        {-982,90,489},{-1003,69,489},{-1012,68,483},{-464,71,952},{-437,64,952},
        {-417,71,769},{-412,70,762},{-424,65,756},{-278,144,570},{-1040,81,622},
        {-1035,68,660},{-1025,81,651},{-1021,63,608},{-493,96,129},{-276,112,840},
        {-300,114,828},{-306,116,841},{-294,119,826},{-449,110,835},{-454,110,831},
        {-440,110,827},{-500,104,840},{-502,104,831},{-508,104,836},{-415,97,353},
        {-429,97,333},{-444,97,353},{-429,103,353},{-444,103,333},{-424,97,283},
        {-436,97,297},{-433,103,277},{-424,103,297},{-400,94,280},{-400,94,350},
        {-366,94,338},{-370,96,302},{-413,102,265},{-426,110,294},{-456,102,367},
        {-457,95,307},{-394,110,312},{-998,74,924},{-1030,68,910},{-992,67,935},
        {-995,67,923},{-990,67,913},{-998,85,919},{-1024,67,894},{-108,121,524},
        {-123,129,674},{-271,117,318},{-344,140,510},{-595,96,749},{-617,96,749},
        {-692,94,548},{-707,64,7},{-710,64,7},{-590,64,-1},{-590,64,2},
        {-841,64,2},{-841,64,-1},{-849,64,75},{-846,64,75},{-660,99,207},
        {-658,99,211},{-658,100,211},{-658,99,212},{-283,168,457},{-119,145,550},
        {-233,144,557},{-253,101,218},{-142,105,482},{-142,105,494},{-156,113,490},
        {-98,179,588},{-298,127,710},{-195,111,732},{-226,111,737},{-204,63,748},
        {-245,69,893},{-222,63,931},{-551,94,563},{-566,104,566},{-577,94,318},
        {-674,94,312},{-684,72,369},{-442,87,575},{-579,107,432},{-741,68,467},
        {-909,74,439},{-490,136,483},{-385,111,677},{-244,100,642},{-280,119,694},
        {-743,108,799},{-779,96,819},{-750,102,812},{-767,96,847},{-748,102,830},
        {-741,93,840},{-784,102,836},{-787,78,853},{-551,109,430},{-518,107,485},
        {-616,105,472},{-588,104,431},{-570,101,441},{-567,101,498}
    };

    /** 18 темплейт-сундуков в лобби с предзаготовленным лутом (X -591..-586, Y 313, Z 446..448). */
    private static final int[][] TEMPLATE_CHESTS = {
        {-591, 313, 446}, {-590, 313, 446}, {-589, 313, 446}, {-588, 313, 446}, {-587, 313, 446}, {-586, 313, 446},
        {-591, 313, 447}, {-590, 313, 447}, {-589, 313, 447}, {-588, 313, 447}, {-587, 313, 447}, {-586, 313, 447},
        {-591, 313, 448}, {-590, 313, 448}, {-589, 313, 448}, {-588, 313, 448}, {-587, 313, 448}, {-586, 313, 448}
    };

    private static final int MAP_ID = 106;

    /** Координаты сундуков текущей карты и номер раскладки (растёт при каждом перезаполнении).
     *  Нужно ботам: они знают, где на карте стоят сундуки, и забывают «обысканные» после рефреша. */
    private volatile int[][] activeChests = new int[0][];
    private volatile int lootGeneration = 0;

    public int[][] getActiveChests() { return activeChests; }
    public int getLootGeneration() { return lootGeneration; }

    public LootManager(VolanSVO plugin) {
        this.plugin = plugin;
    }

    /**
     * Оригинальная логика: на каждой координате 50% удалить (air), 50% поставить сундук
     * и скопировать в него Items из случайного из 10 темплейтов в лобби.
     *
     * Используется dispatchCommand с execute in <world> - те же команды что в датапаке
     * (setblock + data modify block ... Items set from block ...). Гарантированно работает
     * потому что не зависит от Bukkit BlockState snapshot-ов.
     */
    public void placeAndFill(World world) {
        placeAndFill(world, null);
    }

    /**
     * Заполняет сундуки по данным карты СВО. Если mapData передана и в ней есть
     * списки сундуков + темплейтов - используются они, иначе fallback на legacy hardcode.
     */
    public void placeAndFill(World world, dev.volansvo.svo.maps.MapData mapData) {
        if (refillInProgress) {
            // Предыдущий проход ещё идёт - не накладываем, но и не теряем: повторим сразу после него
            // (раньше смерть во время прохода просто не обновляла сундуки).
            pendingWorld = world;
            pendingMap = mapData;
            return;
        }
        refillInProgress = true;
        try {
            placeAndFillInternal(world, mapData);
        } catch (Throwable t) {
            refillInProgress = false; // синхронная часть упала - не блокируем следующие рефреши навсегда
            plugin.getLogger().warning("placeAndFill: ошибка при запуске: " + t);
        }
    }

    private void placeAndFillInternal(World world, dev.volansvo.svo.maps.MapData mapData) {
        // Координаты и темплейты: из MapData (если есть) или legacy.
        // Сундуки читаются из редактируемого текстового файла plugins/VolanSVO/chests/<id>.txt -
        // админ правит его сам, изменения подхватываются на следующем старте игры без пересборки.
        int[][] chests, templates;
        if (mapData != null && mapData.hasLootTemplates()) {
            chests    = dev.volansvo.svo.maps.ChestFileStore.load(
                plugin, mapData.getId(), toArr(mapData.getChestCoords()));
            templates = toArr(mapData.getLootTemplates());
        } else {
            chests    = CHEST_COORDS;
            templates = TEMPLATE_CHESTS;
        }
        final String source = (mapData != null ? mapData.getId() : "legacy");
        activeChests = chests;
        lootGeneration++;

        // ОПТИМИЗАЦИЯ: раньше на КАЖДЫЙ сундук слалось 2-3 консольных команды
        // (setblock + data modify ... set from block <template>), а чанки темплейтов
        // приходилось держать загруженными всю генерацию. Теперь читаем содержимое
        // темплейт-сундуков ОДИН раз в память и наполняем сундуки напрямую через Bukkit API.
        final List<ItemStack[]> templateItems = new ArrayList<ItemStack[]>();
        for (int[] t : templates) {
            world.getChunkAt(t[0] >> 4, t[2] >> 4); // синхронная загрузка чанка темплейта
            BlockState st = world.getBlockAt(t[0], t[1], t[2]).getState();
            if (st instanceof Chest) {
                ItemStack[] src = ((Chest) st).getBlockInventory().getContents();
                ItemStack[] copy = new ItemStack[src.length];
                for (int i = 0; i < src.length; i++) copy[i] = (src[i] == null) ? null : src[i].clone();
                templateItems.add(copy);
            }
        }
        if (templateItems.isEmpty()) {
            plugin.getLogger().warning("placeAndFill: не найден ни один темплейт-сундук (source="
                + source + ") - сундуки будут пустыми");
        }

        // Группируем сундуки по чанку
        Map<Long, List<int[]>> byChunk = new HashMap<Long, List<int[]>>();
        for (int[] c : chests) {
            long k = chunkKey(c[0] >> 4, c[2] >> 4);
            List<int[]> list = byChunk.get(k);
            if (list == null) { list = new ArrayList<int[]>(); byChunk.put(k, list); }
            list.add(c);
        }

        // КРИТИЧНО: сундуки разбросаны по всей карте (1000x1000). Форслоадить ВСЕ их чанки
        // сразу (200+) - краш по памяти. Обрабатываем по нескольку чанков за тик:
        // forceload -> заполнить через API -> снять форслоад (с задержкой, чтобы запись
        // закоммитилась до выгрузки чанка).
        // ОПТИМИЗАЦИЯ: раньше форслоад/снятие шли через Bukkit.dispatchCommand("execute in ...
        // forceload add/remove ...") - ДВА полных разбора команды (парсер Brigadier) НА КАЖДЫЙ
        // чанк. При up to 341 сундуке (карта east) это сотни лишних command-dispatch'ей на
        // КАЖДЫЙ вызов, а refreshChests() дёргается на КАЖДУЮ смерть игрока. Теперь - прямой
        // API world.setChunkForceLoaded(), без парсинга команд (тот же приём, что и в ChaosManager).
        final World fworld = world;
        final List<Map.Entry<Long, List<int[]>>> entries =
            new ArrayList<Map.Entry<Long, List<int[]>>>(byChunk.entrySet());
        final int totalChunks = entries.size();
        final int CHUNKS_PER_TICK = 6;

        new org.bukkit.scheduler.BukkitRunnable() {
            int idx = 0;
            int placed = 0, deleted = 0;
            @Override public void run() {
                int processedThisTick = 0;
                while (idx < entries.size() && processedThisTick < CHUNKS_PER_TICK) {
                    Map.Entry<Long, List<int[]>> e = entries.get(idx++);
                    final Long key = e.getKey();
                    forceload(fworld, key, true);
                    for (int[] c : e.getValue()) {
                        Block b = fworld.getBlockAt(c[0], c[1], c[2]);
                        if (rng.nextBoolean()) {
                            b.setType(Material.AIR, false);
                            deleted++;
                            continue;
                        }
                        b.setType(Material.CHEST, false);
                        if (!templateItems.isEmpty()) {
                            BlockState st = b.getState();
                            if (st instanceof Chest) {
                                ItemStack[] items = templateItems.get(rng.nextInt(templateItems.size()));
                                Inventory inv = ((Chest) st).getBlockInventory();
                                inv.clear();
                                for (int i = 0; i < items.length; i++)
                                    if (items[i] != null) inv.setItem(i, items[i].clone());
                            }
                        }
                        placed++;
                    }
                    // снимаем форслоад через 4 тика - чтобы запись успела закоммититься до выгрузки
                    Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                        @Override public void run() { forceload(fworld, key, false); }
                    }, 4L);
                    processedThisTick++;
                }
                if (idx >= entries.size()) {
                    plugin.getLogger().info("placeAndFill: placed=" + placed + " deleted=" + deleted
                        + " (чанков сундуков: " + totalChunks + ", темплейтов: " + templateItems.size()
                        + ", source=" + source + ")");
                    refillInProgress = false;
                    cancel();
                    World again = pendingWorld;
                    dev.volansvo.svo.maps.MapData againMap = pendingMap;
                    pendingWorld = null;
                    pendingMap = null;
                    if (again != null && again.equals(fworld) && plugin.getGameManager().isGameActive()) placeAndFill(again, againMap);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** forceload add/remove для одного чанка - напрямую через API, без dispatchCommand. */
    private void forceload(World world, long key, boolean add) {
        int cx = (int) (key >> 32);
        int cz = (int) (long) key;
        try { world.setChunkForceLoaded(cx, cz, add); } catch (Throwable ignored) {}
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static int[][] toArr(java.util.List<int[]> list) {
        return list.toArray(new int[0][]);
    }

    /**
     * Выдаёт игроку карту дефолтную (id=106) для обратной совместимости.
     */
    public void giveMap(Player player) {
        giveMap(player, MAP_ID);
    }

    /**
     * Выдаёт игроку карту с указанным map_id. Если у него уже есть такая - ничего не делает.
     * Также удаляет любые ДРУГИЕ filled_map в инвентаре чтобы у игрока была макс 1 карта.
     */
    public void giveMap(Player player, int mapId) {
        if (mapId < 0) return;
        MapView view = Bukkit.getMap(mapId);
        if (view == null) return;

        // Удалять ЧУЖИЕ filled_map можно ТОЛЬКО в СВО-контексте (игровой мир/лобби),
        // иначе у игрока вне СВО можно случайно стереть его обычные карты.
        World gw = plugin.getWorldManager().getGameWorld();
        World lobby = plugin.getWorldManager().getLobbyWorld();
        boolean svoContext = (gw != null && player.getWorld().equals(gw))
                          || (lobby != null && player.getWorld().equals(lobby));

        boolean alreadyHas = false;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != Material.FILLED_MAP) continue;
            if (!(item.hasItemMeta() && item.getItemMeta() instanceof MapMeta)) continue;
            MapMeta m = (MapMeta) item.getItemMeta();
            if (m.hasMapView() && m.getMapView() != null) {
                if (m.getMapView().getId() == mapId) {
                    alreadyHas = true;
                } else if (svoContext) {
                    // Другая карта - удаляем (правило: 0 или 1 карта) - только в СВО.
                    player.getInventory().setItem(i, null);
                }
            }
        }
        if (alreadyHas) return;

        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) map.getItemMeta();
        if (meta != null) {
            meta.setMapView(view);
            map.setItemMeta(meta);
        }
        player.getInventory().addItem(map);
    }

    /** Ставит красный маркер на карте с указанным id (по умолчанию 106). */
    public void setAirdropCursor(double x, double z) {
        setAirdropCursor(MAP_ID, x, z);
    }
    public void setAirdropCursor(int mapId, double x, double z) {
        MapView view = Bukkit.getMap(mapId);
        if (view == null) return;
        for (MapRenderer r : new ArrayList<MapRenderer>(view.getRenderers())) {
            if (r instanceof AirdropMapRenderer) view.removeRenderer(r);
        }
        view.addRenderer(new AirdropMapRenderer(x, z));
    }

    /** Снимает маркер аирдропа со всех карт-кандидатов. */
    public void clearAirdropCursor() {
        // Чистим default + все зарегистрированные у MapManager карты
        clearCursorFrom(MAP_ID);
        if (plugin.getMapManager() != null) {
            for (dev.volansvo.svo.maps.MapData md : plugin.getMapManager().getAll().values()) {
                if (md.hasMapId()) clearCursorFrom(md.getMapId());
            }
        }
    }

    private void clearCursorFrom(int id) {
        MapView view = Bukkit.getMap(id);
        if (view == null) return;
        for (MapRenderer r : new ArrayList<MapRenderer>(view.getRenderers())) {
            if (r instanceof AirdropMapRenderer) view.removeRenderer(r);
        }
    }
}
