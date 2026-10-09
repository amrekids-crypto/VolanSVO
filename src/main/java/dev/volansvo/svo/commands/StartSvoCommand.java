package dev.volansvo.svo.commands;

import dev.volansvo.svo.GameState;
import dev.volansvo.svo.VolanSVO;
import dev.volansvo.svo.managers.WorldManager;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.io.File;

public class StartSvoCommand implements CommandExecutor {

    private final VolanSVO plugin;
    private static final long COOLDOWN_MS = 5 * 60 * 1000L;
    private long lastUsed = 0L;

    public StartSvoCommand(VolanSVO plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Только для игроков.");
            return true;
        }
        final Player player = (Player) sender;

        // Идёт игра? Всем предлагаем наблюдать кнопками.
        if (plugin.getGameManager().isGameRunning()) {
            plugin.getGameManager().sendSpectateOffer(player);
            return true;
        }

        // Ставим в очередь. Мир (svogame_/eastgame_) НЕ создаём - он клонируется лишь после
        // голосования за карту (см. GameManager.startGame). Телепортируем только к тому же
        // стенду лобби, куда возвращает по окончании игры (standtpspawn2).
        if (plugin.getGameManager().isInQueue(player)) {
            player.sendMessage(ChatColor.YELLOW + "Ты уже в очереди СВО.");
            teleportToLobbyStand(player);
            return true;
        }
        boolean wasFirst = plugin.getGameManager().getQueueSize() == 0;
        player.addScoreboardTag("insvo");
        plugin.getGameManager().addToQueue(player);
        plugin.getGameManager().giveStartItem(player);
        teleportToLobbyStand(player);
        player.sendMessage(ChatColor.GREEN + "Ты в очереди СВО.");
        player.sendMessage(ChatColor.GRAY + "ПКМ по " + ChatColor.AQUA + "звезде"
            + ChatColor.GRAY + " в руке - открыть меню запуска игры.");
        // Первый в очереди - глобальный анонс с кликабельной кнопкой (вместо текста "/svolobby") -
        // клик от имени игрока выполняет /svolobby.
        // Не чаще раза в 5 минут: иначе вход-выход из очереди спамил анонсом весь сервер.
        long nowMs = System.currentTimeMillis();
        if (wasFirst && nowMs - lastUsed >= COOLDOWN_MS) {
            lastUsed = nowMs;
            String tellraw = "tellraw @a [\"\""
                + ",{\"text\":\"СВО началось! \",\"bold\":true,\"color\":\"gold\"}"
                + ",{\"text\":\"[ПРИСОЕДИНИТЬСЯ]\",\"color\":\"aqua\",\"bold\":true"
                + ",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/svolobby\"}"
                + ",\"hoverEvent\":{\"action\":\"show_text\",\"contents\":\"Встать в очередь СВО\"}}]";
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), tellraw);
        }
        return true;
    }

    private void teleportToLobbyStand(Player player) {
        Location loc = plugin.getGameManager().getLobbyReturnLocation();
        if (loc != null) player.teleport(loc);
    }
}
