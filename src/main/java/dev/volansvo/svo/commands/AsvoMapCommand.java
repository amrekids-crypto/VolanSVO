package dev.volansvo.svo.commands;

import dev.volansvo.svo.VolanSVO;
import dev.volansvo.svo.maps.MapData;
import dev.volansvo.svo.maps.MapManager;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * /asvonew <id> <world-name> <display-name>
 *   - регистрирует новую карту
 *   - пишет инструкцию шагов настройки
 *
 * /asvomap warden    <id> <x> <y> <z>
 * /asvomap spawnbox  <id> <minX> <maxX> <Y> <minZ> <maxZ>
 * /asvomap border    <id> <centerX> <centerZ> <size>
 * /asvomap validate  <id>
 * /asvomap list
 * /asvomap delete    <id>
 *
 * Tab-complete для координат подсказывает блок на который смотришь.
 */
public class AsvoMapCommand implements CommandExecutor, TabCompleter {

    private final VolanSVO plugin;

    public AsvoMapCommand(VolanSVO plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!dev.volansvo.svo.listeners.CommandListener.isBuilder(sender)) return true;
        if (!(sender instanceof Player)) { sender.sendMessage("Только для игроков."); return true; }
        Player player = (Player) sender;
        if (!isAdmin(player)) {
            player.sendMessage(ChatColor.RED + "Только для svobuilder/OP.");
            return true;
        }

        MapManager mm = plugin.getMapManager();

        // /asvonew <id> <worldName> <displayName>
        if (label.equalsIgnoreCase("asvonew")) {
            if (args.length < 3) {
                player.sendMessage(ChatColor.YELLOW + "/asvonew <id> <имя-мира> <отображаемое-имя>");
                return true;
            }
            String id = args[0].toLowerCase();
            String worldName = args[1];
            String display = joinFrom(args, 2);
            if (mm.exists(id)) {
                player.sendMessage(ChatColor.RED + "Карта '" + id + "' уже существует. /asvomap list");
                return true;
            }
            if (Bukkit.getWorld(worldName) == null) {
                player.sendMessage(ChatColor.YELLOW + "Внимание: мир '" + worldName + "' не загружен. Убедись что он есть в server.properties или Multiverse.");
            }
            MapData m = new MapData(id);
            m.setSourceWorld(worldName);
            m.setDisplayName(display);
            mm.register(m);
            printInstructions(player, m);
            return true;
        }

        // /asvomap <subcmd> ...
        if (args.length == 0) {
            sendUsage(player);
            return true;
        }
        String sub = args[0].toLowerCase();

        if (sub.equals("list")) {
            Map<String, MapData> all = mm.getAll();
            if (all.isEmpty()) {
                player.sendMessage(ChatColor.YELLOW + "Карт нет. Создай через /asvonew");
                return true;
            }
            player.sendMessage(ChatColor.GOLD + "--- Карты СВО ---");
            for (MapData m : all.values()) {
                String status = m.isReady() ? ChatColor.GREEN + "ГОТОВА" : ChatColor.RED + "НЕ НАСТРОЕНА";
                player.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + m.getId()
                    + " (" + m.getDisplayName() + ") world=" + m.getSourceWorld() + " " + status);
            }
            return true;
        }

        if (sub.equals("delete")) {
            if (args.length < 2) { player.sendMessage(ChatColor.YELLOW + "/asvomap delete <id>"); return true; }
            String id = args[1].toLowerCase();
            if (!mm.exists(id)) { player.sendMessage(ChatColor.RED + "Нет такой карты."); return true; }
            mm.unregister(id);
            player.sendMessage(ChatColor.GREEN + "Карта '" + id + "' удалена.");
            return true;
        }

        // Все остальные команды требуют <id>
        if (args.length < 2) { sendUsage(player); return true; }
        String id = args[1].toLowerCase();
        MapData m = mm.get(id);
        if (m == null) { player.sendMessage(ChatColor.RED + "Нет такой карты: " + id); return true; }

        if (sub.equals("warden")) {
            if (args.length < 5) { player.sendMessage(ChatColor.YELLOW + "/asvomap warden " + id + " <x> <y> <z>"); return true; }
            try {
                double x = Double.parseDouble(args[2]);
                double y = Double.parseDouble(args[3]);
                double z = Double.parseDouble(args[4]);
                m.setWarden(x, y, z);
                mm.save(m);
                player.sendMessage(ChatColor.GREEN + "Жириновский: " + (int) x + " " + (int) y + " " + (int) z);
                printRemaining(player, m);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Числа ожидаются.");
            }
            return true;
        }

        if (sub.equals("spawnbox")) {
            if (args.length < 7) { player.sendMessage(ChatColor.YELLOW + "/asvomap spawnbox " + id + " <minX> <maxX> <Y> <minZ> <maxZ>"); return true; }
            try {
                int minX = Integer.parseInt(args[2]);
                int maxX = Integer.parseInt(args[3]);
                int y    = Integer.parseInt(args[4]);
                int minZ = Integer.parseInt(args[5]);
                int maxZ = Integer.parseInt(args[6]);
                m.setSpawnBox(minX, maxX, y, minZ, maxZ);
                mm.save(m);
                player.sendMessage(ChatColor.GREEN + "spawn_box: X[" + minX + ".." + maxX + "] Y=" + y + " Z[" + minZ + ".." + maxZ + "]");
                printRemaining(player, m);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Числа ожидаются.");
            }
            return true;
        }

        if (sub.equals("border")) {
            if (args.length < 5) { player.sendMessage(ChatColor.YELLOW + "/asvomap border " + id + " <centerX> <centerZ> <size>"); return true; }
            try {
                double cx = Double.parseDouble(args[2]);
                double cz = Double.parseDouble(args[3]);
                double sz = Double.parseDouble(args[4]);
                m.setBorder(cx, cz, sz);
                mm.save(m);
                player.sendMessage(ChatColor.GREEN + "worldborder: центр (" + (int) cx + ", " + (int) cz + ") размер " + (int) sz);
                printRemaining(player, m);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Числа ожидаются.");
            }
            return true;
        }

        // Сканирует сундуки в мире-источнике карты и сохраняет их координаты как loot-позиции
        if (sub.equals("scanchests")) {
            World w = Bukkit.getWorld(m.getSourceWorld());
            if (w == null) { player.sendMessage(ChatColor.RED + "Мир не загружен."); return true; }
            // Радиус сканирования = worldborder (если задан) или 1000 блоков от центра spawn_box
            double cx, cz, half;
            if (m.hasBorder()) {
                cx = m.getBorderCenterX(); cz = m.getBorderCenterZ(); half = m.getBorderSize() / 2.0;
            } else {
                cx = 0; cz = 0; half = 1000;
            }
            int found = 0;
            m.clearChests();
            // Скан по всем загруженным чанкам в радиусе
            int cMinX = (int)(cx - half) >> 4, cMaxX = (int)(cx + half) >> 4;
            int cMinZ = (int)(cz - half) >> 4, cMaxZ = (int)(cz + half) >> 4;
            // Темплейт-позиции исключаем
            java.util.Set<String> templates = new java.util.HashSet<String>();
            for (int[] c : m.getLootTemplates())    templates.add(c[0]+","+c[1]+","+c[2]);
            for (int[] c : m.getAirdropTemplates()) templates.add(c[0]+","+c[1]+","+c[2]);
            for (int chx = cMinX; chx <= cMaxX; chx++) {
                for (int chz = cMinZ; chz <= cMaxZ; chz++) {
                    if (!w.isChunkLoaded(chx, chz)) w.loadChunk(chx, chz, false);
                    org.bukkit.Chunk chunk = w.getChunkAt(chx, chz);
                    for (org.bukkit.block.BlockState bs : chunk.getTileEntities()) {
                        if (!(bs instanceof org.bukkit.block.Chest)) continue;
                        org.bukkit.block.Block bb = bs.getBlock();
                        String key = bb.getX()+","+bb.getY()+","+bb.getZ();
                        if (templates.contains(key)) continue; // не дублируем темплейты
                        m.addChest(bb.getX(), bb.getY(), bb.getZ());
                        found++;
                    }
                }
            }
            mm.save(m);
            player.sendMessage(ChatColor.GREEN + "Найдено " + found + " сундуков лута в мире '" + w.getName() + "'.");
            player.sendMessage(ChatColor.GRAY + "Перезапусти сканирование после изменения карты.");
            printRemaining(player, m);
            return true;
        }

        // Регистрирует чанк на который смотришь как темплейт
        if (sub.equals("settemplate")) {
            if (args.length < 3) { player.sendMessage(ChatColor.YELLOW + "/asvomap settemplate " + id + " loot|airdrop"); return true; }
            String kind = args[2].toLowerCase();
            Block target = player.getTargetBlockExact(10);
            if (target == null || target.getType() != Material.CHEST) {
                player.sendMessage(ChatColor.RED + "Смотри на сундук в радиусе 10 блоков.");
                return true;
            }
            int tx = target.getX(), ty = target.getY(), tz = target.getZ();
            if (kind.equals("loot")) {
                m.addLootTemplate(tx, ty, tz);
                mm.save(m);
                player.sendMessage(ChatColor.GREEN + "Loot-темплейт #" + m.getLootTemplates().size() + " добавлен: " + tx + " " + ty + " " + tz);
                if (m.getLootTemplates().size() < 10) {
                    player.sendMessage(ChatColor.GRAY + "Осталось добавить " + (10 - m.getLootTemplates().size()) + " loot-темплейта (рекомендуется 10 шт.)");
                }
            } else if (kind.equals("airdrop") || kind.equals("air")) {
                m.addAirdropTemplate(tx, ty, tz);
                mm.save(m);
                player.sendMessage(ChatColor.GREEN + "Airdrop-темплейт #" + m.getAirdropTemplates().size() + " добавлен: " + tx + " " + ty + " " + tz);
                if (m.getAirdropTemplates().size() < 6) {
                    player.sendMessage(ChatColor.GRAY + "Осталось добавить " + (6 - m.getAirdropTemplates().size()) + " airdrop-темплейта (рекомендуется 6 шт.)");
                }
            } else {
                player.sendMessage(ChatColor.RED + "Тип должен быть loot или airdrop.");
            }
            return true;
        }

        if (sub.equals("clearchests")) {
            m.clearChests();
            mm.save(m);
            player.sendMessage(ChatColor.YELLOW + "Список сундуков очищен.");
            return true;
        }
        if (sub.equals("cleartemplates")) {
            if (args.length < 3) { player.sendMessage(ChatColor.YELLOW + "/asvomap cleartemplates " + id + " loot|airdrop"); return true; }
            String kind = args[2].toLowerCase();
            if (kind.equals("loot")) { m.clearLootTemplates(); mm.save(m); player.sendMessage(ChatColor.YELLOW + "Loot-темплейты очищены."); }
            else if (kind.equals("airdrop") || kind.equals("air")) { m.clearAirdropTemplates(); mm.save(m); player.sendMessage(ChatColor.YELLOW + "Airdrop-темплейты очищены."); }
            else player.sendMessage(ChatColor.RED + "Тип должен быть loot или airdrop.");
            return true;
        }

        if (sub.equals("mapbounds")) {
            if (args.length < 6) { player.sendMessage(ChatColor.YELLOW + "/asvomap mapbounds " + id + " <minX> <maxX> <minZ> <maxZ>"); return true; }
            try {
                int minX = Integer.parseInt(args[2]);
                int maxX = Integer.parseInt(args[3]);
                int minZ = Integer.parseInt(args[4]);
                int maxZ = Integer.parseInt(args[5]);
                m.setMapBounds(minX, maxX, minZ, maxZ);
                // Создаём MapView для покрытия указанной области
                World w = Bukkit.getWorld(m.getSourceWorld());
                if (w == null) {
                    player.sendMessage(ChatColor.RED + "Мир-источник '" + m.getSourceWorld() + "' не загружен. Загрузи его и повтори.");
                    return true;
                }
                org.bukkit.map.MapView view = Bukkit.createMap(w);
                view.setCenterX((minX + maxX) / 2);
                view.setCenterZ((minZ + maxZ) / 2);
                int width = Math.max(Math.abs(maxX - minX), Math.abs(maxZ - minZ));
                // Scale 0=128, 1=256, 2=512, 3=1024, 4=2048
                int scale = 0;
                while ((128 << scale) < width && scale < 4) scale++;
                org.bukkit.map.MapView.Scale s;
                switch (scale) {
                    case 0: s = org.bukkit.map.MapView.Scale.CLOSEST;  break;
                    case 1: s = org.bukkit.map.MapView.Scale.CLOSE;    break;
                    case 2: s = org.bukkit.map.MapView.Scale.NORMAL;   break;
                    case 3: s = org.bukkit.map.MapView.Scale.FAR;      break;
                    default: s = org.bukkit.map.MapView.Scale.FARTHEST; break;
                }
                view.setScale(s);
                view.setUnlimitedTracking(true);
                m.setMapId(view.getId());
                mm.save(m);
                player.sendMessage(ChatColor.GREEN + "Map: X[" + minX + ".." + maxX + "] Z[" + minZ + ".." + maxZ + "] id=" + view.getId() + " scale=" + s);
                printRemaining(player, m);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Числа ожидаются.");
            }
            return true;
        }

        if (sub.equals("validate")) {
            World w = Bukkit.getWorld(m.getSourceWorld());
            player.sendMessage(ChatColor.GOLD + "--- Проверка карты '" + id + "' ---");
            player.sendMessage(check(m.getSourceWorld() != null, "Имя мира", m.getSourceWorld()));
            player.sendMessage(check(w != null, "Мир-источник загружен", m.getSourceWorld()));
            player.sendMessage(check(m.hasWarden(), "Жириновский", m.hasWarden() ? ((int) m.getWardenX() + " " + (int) m.getWardenY() + " " + (int) m.getWardenZ()) : "не задан"));
            player.sendMessage(check(m.hasSpawnBox(), "spawn_box", m.hasSpawnBox() ? "X[" + m.getSpawnMinX() + ".." + m.getSpawnMaxX() + "] Y=" + m.getSpawnY() + " Z[" + m.getSpawnMinZ() + ".." + m.getSpawnMaxZ() + "]" : "не задан"));
            player.sendMessage(check(m.hasBorder(), "worldborder", m.hasBorder() ? "центр (" + (int) m.getBorderCenterX() + ", " + (int) m.getBorderCenterZ() + ") размер " + (int) m.getBorderSize() : "не задан"));
            player.sendMessage(check(m.hasMapBounds() && m.hasMapId(), "Карта-предмет (map_id)", m.hasMapId() ? "id=" + m.getMapId() + " X[" + m.getMapMinX() + ".." + m.getMapMaxX() + "] Z[" + m.getMapMinZ() + ".." + m.getMapMaxZ() + "]" : "не задана"));
            player.sendMessage(check(m.hasChests(), "Сундуки лута", m.getChestCoords().size() + " шт."));
            player.sendMessage(check(m.hasLootTemplates(), "Loot-темплейты", m.getLootTemplates().size() + " шт. (рекомендуется 10)"));
            player.sendMessage(check(m.hasAirdropTemplates(), "Airdrop-темплейты", m.getAirdropTemplates().size() + " шт. (рекомендуется 6)"));
            // Проверка стендов в исходном мире
            if (w != null) {
                int svorestart = countTaggedStands(w, "svorestart");
                int svorazboist = countTaggedStands(w, "svorazboist");
                int yaniks = countTaggedZombies(w, "yaniksvo");
                player.sendMessage(check(svorestart >= 1, "Стенд svorestart", svorestart + " шт."));
                player.sendMessage(ChatColor.GRAY + "  Опционально: svorazboist=" + svorazboist + ", yaniksvo=" + yaniks);
            }
            player.sendMessage(m.isReady() ? ChatColor.GREEN + "Карта готова к игре." : ChatColor.RED + "Карта НЕ готова. Дозаполни параметры.");
            return true;
        }

        sendUsage(player);
        return true;
    }

    private String check(boolean ok, String label, String value) {
        String mark = ok ? ChatColor.GREEN + "[OK]" : ChatColor.RED + "[NO]";
        return mark + " " + ChatColor.GRAY + label + ": " + ChatColor.WHITE + value;
    }

    private int countTaggedStands(World w, String tag) {
        int n = 0;
        for (Entity e : w.getEntitiesByClass(ArmorStand.class)) {
            if (e.getScoreboardTags().contains(tag)) n++;
        }
        return n;
    }

    private int countTaggedZombies(World w, String tag) {
        int n = 0;
        for (Entity e : w.getEntitiesByClass(org.bukkit.entity.Zombie.class)) {
            if (e.getScoreboardTags().contains(tag)) n++;
        }
        return n;
    }

    private void printInstructions(Player player, MapData m) {
        player.sendMessage(ChatColor.GREEN + "Карта '" + m.getId() + "' зарегистрирована.");
        player.sendMessage(ChatColor.GOLD + "Что осталось настроить:");
        player.sendMessage(ChatColor.YELLOW + "1. /asvomap warden " + m.getId() + " <x> <y> <z>" + ChatColor.GRAY + " - координаты спавна Жириновского");
        player.sendMessage(ChatColor.YELLOW + "2. /asvomap spawnbox " + m.getId() + " <minX> <maxX> <Y> <minZ> <maxZ>" + ChatColor.GRAY + " - куб ТП игроков в начале");
        player.sendMessage(ChatColor.YELLOW + "3. /asvomap border " + m.getId() + " <centerX> <centerZ> <size>" + ChatColor.GRAY + " - центр и размер worldborder");
        player.sendMessage(ChatColor.YELLOW + "4. /asvomap mapbounds " + m.getId() + " <minX> <maxX> <minZ> <maxZ>" + ChatColor.GRAY + " - крайние точки карты-предмета (выдаётся игрокам)");
        player.sendMessage(ChatColor.GOLD + "Также в исходном мире '" + m.getSourceWorld() + "' нужно:");
        player.sendMessage(ChatColor.GRAY + "  - 1 armor_stand с тегом " + ChatColor.WHITE + "svorestart" + ChatColor.GRAY + " (точка возврата в лобби)");
        player.sendMessage(ChatColor.GRAY + "  - опционально: armor_stand с тегом " + ChatColor.WHITE + "svorazboist" + ChatColor.GRAY + " (триггер спавна разбойников)");
        player.sendMessage(ChatColor.GRAY + "  - опционально: яник через /asvoyanyk в нужных местах");
        player.sendMessage(ChatColor.GRAY + "  - 10 темплейт-сундуков для лута: " + ChatColor.WHITE + "-587..-586, 313, 446..448");
        player.sendMessage(ChatColor.GRAY + "  - 6 темплейт-сундуков для аирдропа: " + ChatColor.WHITE + "-587..-586, 313, 442..444");
        player.sendMessage(ChatColor.YELLOW + "В конце: /asvomap validate " + m.getId());
        player.sendMessage(ChatColor.GRAY + "Подсказка: tab-complete у /asvomap warden подставляет координаты блока на который смотришь.");
    }

    private void printRemaining(Player player, MapData m) {
        List<String> todo = new ArrayList<String>();
        if (m.getSourceWorld() == null) todo.add("имя мира");
        if (!m.hasWarden())   todo.add("warden");
        if (!m.hasSpawnBox()) todo.add("spawnbox");
        if (!m.hasBorder())   todo.add("border");
        if (!m.hasMapBounds() || !m.hasMapId()) todo.add("mapbounds");
        if (!m.hasChests())                     todo.add("scanchests");
        if (!m.hasLootTemplates())              todo.add("settemplate loot (x10)");
        if (!m.hasAirdropTemplates())           todo.add("settemplate airdrop (x6)");
        if (todo.isEmpty()) {
            player.sendMessage(ChatColor.GREEN + "Все параметры заполнены. Запусти /asvomap validate " + m.getId());
        } else {
            player.sendMessage(ChatColor.YELLOW + "Осталось: " + String.join(", ", todo));
        }
    }

    private void sendUsage(Player player) {
        player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Команды настройки карт СВО:");
        player.sendMessage(ChatColor.YELLOW + "/asvonew <id> <world> <display>"
            + ChatColor.GRAY + " - создать новую карту. id - короткое имя файла, world - имя мира-источника (тот что клонируется), display - название показываемое игрокам.");
        player.sendMessage(ChatColor.YELLOW + "/asvomap warden <id> <x> <y> <z>"
            + ChatColor.GRAY + " - где спавнится Жириновский.");
        player.sendMessage(ChatColor.YELLOW + "/asvomap spawnbox <id> <minX> <maxX> <Y> <minZ> <maxZ>"
            + ChatColor.GRAY + " - кубоид куда тп игроков в начале игры (рандомная точка внутри).");
        player.sendMessage(ChatColor.YELLOW + "/asvomap border <id> <centerX> <centerZ> <size>"
            + ChatColor.GRAY + " - центр и размер сжимающейся зоны worldborder.");
        player.sendMessage(ChatColor.YELLOW + "/asvomap mapbounds <id> <minX> <maxX> <minZ> <maxZ>"
            + ChatColor.GRAY + " - крайние точки которые показывает карта-предмет в руках игрока. Плагин создаст MapView с нужным масштабом.");
        player.sendMessage(ChatColor.YELLOW + "/asvomap scanchests <id>"
            + ChatColor.GRAY + " - найти все сундуки в мире и сохранить их позиции для лута. Запусти после расстановки сундуков. Темплейт-позиции (см. ниже) автоматически исключаются.");
        player.sendMessage(ChatColor.YELLOW + "/asvomap settemplate <id> loot|airdrop"
            + ChatColor.GRAY + " - смотришь на сундук и регистрируешь его как источник лута. loot нужно 10 шт (заполни лутом игровых сундуков), airdrop нужно 6 шт (заполни лутом аирдропов).");
        player.sendMessage(ChatColor.YELLOW + "/asvomap clearchests <id>"
            + ChatColor.GRAY + " - очистить список сундуков (перед пере-сканированием).");
        player.sendMessage(ChatColor.YELLOW + "/asvomap cleartemplates <id> loot|airdrop"
            + ChatColor.GRAY + " - очистить темплейты данного типа.");
        player.sendMessage(ChatColor.YELLOW + "/asvomap validate <id>"
            + ChatColor.GRAY + " - проверка готовности карты. Покажет что не настроено + найдёт ли стенды svorestart/svorazboist в мире.");
        player.sendMessage(ChatColor.YELLOW + "/asvomap list"
            + ChatColor.GRAY + " - список всех карт со статусом.");
        player.sendMessage(ChatColor.YELLOW + "/asvomap delete <id>"
            + ChatColor.GRAY + " - удалить карту.");
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "Что нужно в мире-источнике:");
        player.sendMessage(ChatColor.GRAY + " - armor_stand с тегом " + ChatColor.WHITE + "svorestart" + ChatColor.GRAY + " (точка возврата в лобби, 1 шт)");
        player.sendMessage(ChatColor.GRAY + " - опционально: armor_stand с тегом " + ChatColor.WHITE + "svorazboist" + ChatColor.GRAY + " (триггер спавна разбойников)");
        player.sendMessage(ChatColor.GRAY + " - сундуки лута (любое кол-во) - расставь и /asvomap scanchests");
        player.sendMessage(ChatColor.GRAY + " - 10 темплейт-сундуков с лутом для игры (зарегистрируй /asvomap settemplate loot)");
        player.sendMessage(ChatColor.GRAY + " - 6 темплейт-сундуков с лутом для аирдропа (зарегистрируй /asvomap settemplate airdrop)");
    }

    private boolean isAdmin(Player p) {
        return p.isOp() || p.getScoreboardTags().contains("svobuilder");
    }

    private String joinFrom(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    // tab complete: подставляет координаты блока на который смотрит игрок
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) return Collections.emptyList();
        Player player = (Player) sender;

        if (alias.equalsIgnoreCase("asvonew")) {
            if (args.length == 2) {
                List<String> r = new ArrayList<String>();
                for (World w : Bukkit.getWorlds()) r.add(w.getName());
                return r;
            }
            return Collections.emptyList();
        }

        // /asvomap warden|spawnbox|border <id> <coords...>
        if (args.length == 1) {
            return Arrays.asList("warden", "spawnbox", "border", "mapbounds",
                "scanchests", "settemplate", "clearchests", "cleartemplates",
                "validate", "list", "delete");
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("settemplate") || args[0].equalsIgnoreCase("cleartemplates"))) {
            return Arrays.asList("loot", "airdrop");
        }
        if (args.length == 2) {
            return new ArrayList<String>(plugin.getMapManager().getAll().keySet());
        }

        // Tab-complete координаты
        Block target = player.getTargetBlockExact(50);
        if (target == null) return Collections.emptyList();
        String sub = args[0].toLowerCase();
        if (sub.equals("warden") && args.length >= 3 && args.length <= 5) {
            switch (args.length) {
                case 3: return Collections.singletonList(String.valueOf(target.getX()));
                case 4: return Collections.singletonList(String.valueOf(target.getY()));
                case 5: return Collections.singletonList(String.valueOf(target.getZ()));
            }
        }
        if (sub.equals("spawnbox") && args.length >= 3 && args.length <= 7) {
            switch (args.length) {
                case 3: case 4: return Collections.singletonList(String.valueOf(target.getX()));
                case 5: return Collections.singletonList(String.valueOf(target.getY()));
                case 6: case 7: return Collections.singletonList(String.valueOf(target.getZ()));
            }
        }
        if (sub.equals("mapbounds") && args.length >= 3 && args.length <= 6) {
            switch (args.length) {
                case 3: case 4: return Collections.singletonList(String.valueOf(target.getX()));
                case 5: case 6: return Collections.singletonList(String.valueOf(target.getZ()));
            }
        }
        if (sub.equals("border") && args.length >= 3 && args.length <= 5) {
            switch (args.length) {
                case 3: return Collections.singletonList(String.valueOf(target.getX()));
                case 4: return Collections.singletonList(String.valueOf(target.getZ()));
                case 5: return Collections.singletonList("1031");
            }
        }
        return Collections.emptyList();
    }
}
