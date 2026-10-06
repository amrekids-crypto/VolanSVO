package dev.volansvo.svo.commands;

import dev.volansvo.svo.VolanSVO;
import dev.volansvo.svo.maps.MapData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

public class PlaySvoCommand implements CommandExecutor, TabCompleter {

    private final VolanSVO plugin;
    private static final List<String> LIVES = Arrays.asList("1", "2", "3");
    private static final List<String> TIMES = Arrays.asList("15", "30");
    private static final List<String> BOOLEANS = Arrays.asList("true", "false");
    private static final List<String> TEAMS = Arrays.asList(
        "одиночный", "дуо", "трио", "рандомдуо", "рандомтрио");

    private static final String USAGE =
        ChatColor.YELLOW + "/svoplay <карта> <жизни> <время(мин)> <с жириновским?> <с подсветкой?> <быстрая зона?> <С аирдропами?> <режим: одиночный|дуо|трио|рандомдуо|рандомтрио> [подсветка тиммейтов?] [боты 0-10]";

    public PlaySvoCommand(VolanSVO plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Только для игроков.");
            return true;
        }
        Player player = (Player) sender;

        // Идёт ли уже игра? Всем предлагаем наблюдать кнопками в чате.
        if (plugin.getGameManager().isGameRunning()) {
            plugin.getGameManager().sendSpectateOffer(player);
            return true;
        }

        if (!plugin.getGameManager().isInQueue(player)) {
            player.sendMessage(ChatColor.RED + "Ты не в очереди. Используй /svolobby чтобы встать в очередь.");
            return true;
        }

        // Без аргументов - открываем GUI настройки всех параметров игры.
        if (args.length == 0) {
            plugin.getGameManager().openSetupGui(player);
            return true;
        }

        // /svoplay <map> [lives time zir glow fast airdrops]
        String mapId = args[0].toLowerCase();
        MapData mapData = plugin.getMapManager().get(mapId);
        if (mapData == null) {
            player.sendMessage(ChatColor.RED + "Нет карты '" + mapId + "'. Доступные:");
            showMapList(player);
            return true;
        }
        if (!mapData.isReady()) {
            player.sendMessage(ChatColor.RED + "Карта '" + mapData.getDisplayName() + "' не настроена. Админ: /asvomap validate " + mapId);
            return true;
        }

        // Требуем ВСЕ аргументы: <карта> <жизни> <время> <жириновский> <подсветка> <быстрая зона> <аирдропы> <режим команды>
        if (args.length < 8) {
            player.sendMessage(ChatColor.RED + "Нужно указать все аргументы (включая режим команды).");
            player.sendMessage(USAGE);
            return true;
        }

        int lives, time;
        boolean withZir, withGlow, fastZone, airdrop;
        try {
            lives    = Integer.parseInt(args[1]);
            time     = Integer.parseInt(args[2]);
            withZir  = parseBool(args[3]);
            withGlow = parseBool(args[4]);
            fastZone = parseBool(args[5]);
            airdrop  = parseBool(args[6]);
        } catch (IllegalArgumentException e) {
            player.sendMessage(ChatColor.RED + "Неверные аргументы. Используй true/false и числа.");
            player.sendMessage(USAGE);
            return true;
        }
        if (lives < 1 || lives > 3) { player.sendMessage(ChatColor.RED + "Жизней: 1, 2 или 3."); player.sendMessage(USAGE); return true; }
        if (time != 15 && time != 30) { player.sendMessage(ChatColor.RED + "Время: 15 или 30."); player.sendMessage(USAGE); return true; }

        String mode = parseMode(args[7]);
        if (mode == null) {
            player.sendMessage(ChatColor.RED + "Режим: одиночный, дуо, трио, рандомдуо или рандомтрио.");
            player.sendMessage(USAGE);
            return true;
        }

        // Опциональный 9-й аргумент: подсветка тиммейтов (по умолчанию выкл).
        boolean teamGlow = false;
        if (args.length >= 9) {
            try { teamGlow = parseBool(args[8]); }
            catch (IllegalArgumentException e) {
                player.sendMessage(ChatColor.RED + "Подсветка тиммейтов: true/false.");
                player.sendMessage(USAGE);
                return true;
            }
        }

        // Опциональный 10-й аргумент: сколько ботов добавить.
        int bots = 0;
        if (args.length >= 10) {
            int max = dev.volansvo.svo.bots.BotManager.MAX_BOTS;
            try { bots = Integer.parseInt(args[9]); } catch (NumberFormatException e) { bots = -1; }
            if (bots < 0 || bots > max) {
                player.sendMessage(ChatColor.RED + "Боты: число от 0 до " + max + ".");
                return true;
            }
        }

        // Запоминаем выбранную карту для запуска
        plugin.getMapManager().setActiveMap(mapId);
        plugin.getGameManager().requestStartMode(player, lives, time, withZir, withGlow, fastZone, airdrop, teamGlow, false, mode, bots);
        return true;
    }

    private void showMapList(Player player) {
        Map<String, MapData> maps = plugin.getMapManager().getAll();
        if (maps.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Карт нет. Админ создаёт через /asvonew");
            return;
        }
        player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Выберите карту и режим:");
        for (MapData m : maps.values()) {
            if (!m.isReady()) {
                // Не настроенная карта - просто серая строка, без кнопок
                player.sendMessage(ChatColor.GRAY + "[ " + m.getDisplayName() + " - не настроена ]");
                continue;
            }
            // Название карты + кнопки выбора режима. Жизни/время по умолчанию 1/15
            // (для тонкой настройки - текстовая команда).
            String name = m.getDisplayName();
            String id = m.getId();
            String b1   = "/svoplay " + id + " 1 15 true true false true одиночный";
            String bduo = "/svoplay " + id + " 1 15 true true false true дуо";
            String btri = "/svoplay " + id + " 1 15 true true false true трио";
            String brduo = "/svoplay " + id + " 1 15 true true false true рандомдуо";
            String brtri = "/svoplay " + id + " 1 15 true true false true рандомтрио";
            String tellraw = "tellraw " + player.getName() + " [\"\""
                + ",{\"text\":\"" + escape(name) + ": \",\"color\":\"green\",\"bold\":true}"
                + ",{\"text\":\"[Одиночный]\",\"color\":\"yellow\""
                + ",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"" + escape(b1) + "\"}"
                + ",\"hoverEvent\":{\"action\":\"show_text\",\"contents\":\"Каждый сам за себя\"}}"
                + ",{\"text\":\" \"}"
                + ",{\"text\":\"[Дуо]\",\"color\":\"aqua\""
                + ",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"" + escape(bduo) + "\"}"
                + ",\"hoverEvent\":{\"action\":\"show_text\",\"contents\":\"Команды по 2, выбор тиммейта (нужно 3+ игроков)\"}}"
                + ",{\"text\":\" \"}"
                + ",{\"text\":\"[Трио]\",\"color\":\"blue\""
                + ",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"" + escape(btri) + "\"}"
                + ",\"hoverEvent\":{\"action\":\"show_text\",\"contents\":\"Команды по 3, выбор тиммейтов (нужно 4+ игроков)\"}}"
                + ",{\"text\":\" \"}"
                + ",{\"text\":\"[Рандом-дуо]\",\"color\":\"light_purple\""
                + ",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"" + escape(brduo) + "\"}"
                + ",\"hoverEvent\":{\"action\":\"show_text\",\"contents\":\"Команды по 2 случайно (нужно 3+ игроков)\"}}"
                + ",{\"text\":\" \"}"
                + ",{\"text\":\"[Рандом-трио]\",\"color\":\"dark_purple\""
                + ",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"" + escape(brtri) + "\"}"
                + ",\"hoverEvent\":{\"action\":\"show_text\",\"contents\":\"Команды по 3 случайно (нужно 4+ игроков)\"}}"
                + "]";
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), tellraw);
        }
        player.sendMessage(ChatColor.GRAY + "Жизни/время по умолчанию 1/15. Полная настройка: /svoplay <id> <жизни> <время> <жириновский> <подсветка> <быстрая зона> <аирдропы> <режим>");
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Строгий парс true/false. Любое другое значение - ошибка. */
    private boolean parseBool(String s) {
        if (s.equalsIgnoreCase("true"))  return true;
        if (s.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException("not a boolean: " + s);
    }

    /** Нормализует режим. Возвращает solo|duo|trio|randomduo|randomtrio, или null. */
    private String parseMode(String s) {
        String t = s.toLowerCase();
        if (t.equals("одиночный") || t.equals("solo") || t.equals("1")) return "solo";
        if (t.equals("дуо") || t.equals("duo") || t.equals("2"))        return "duo";
        if (t.equals("трио") || t.equals("trio") || t.equals("3"))      return "trio";
        if (t.equals("рандомдуо") || t.equals("randomduo"))             return "randomduo";
        if (t.equals("рандомтрио") || t.equals("randomtrio"))           return "randomtrio";
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> r = new ArrayList<String>();
            String prefix = args[0].toLowerCase();
            for (String id : plugin.getMapManager().getAll().keySet()) {
                if (id.startsWith(prefix)) r.add(id);
            }
            return r;
        }
        List<String> opts;
        switch (args.length) {
            case 2: opts = LIVES; break;
            case 3: opts = TIMES; break;
            case 4: case 5: case 6: case 7: opts = BOOLEANS; break;
            case 8: opts = TEAMS; break;
            case 9: opts = BOOLEANS; break;
            case 10: opts = Arrays.asList("0", "1", "2", "3", "5", "10"); break;
            default: return Collections.emptyList();
        }
        List<String> matches = new ArrayList<String>();
        String prefix = args[args.length - 1].toLowerCase();
        for (String o : opts) if (o.startsWith(prefix)) matches.add(o);
        return matches;
    }
}
