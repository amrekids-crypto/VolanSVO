package dev.volansvo.svo;

import org.bukkit.entity.Player;
import java.util.UUID;

public class SvoPlayer {

    private final UUID uuid;
    private final String name;

    private int lives;
    private boolean inGame;
    private boolean airdropped;
    private int kills;
    private int teamId = -1; // номер команды (для Дуо/Трио). -1 = не назначен.

    private int totalGames;
    private int totalWins;
    private int totalKills;

    public SvoPlayer(Player player) {
        this.uuid = player.getUniqueId();
        this.name = player.getName();
    }

    public UUID getUuid() { return uuid; }
    public String getName() { return name; }

    public int getLives() { return lives; }
    public void setLives(int l) { this.lives = l; }
    // Жив = в игре и не выбыл окончательно (выбывание ставит lives=-1). Без верхнего предела -
    // раньше был lives<=2, из-за чего режим 3 жизни ломал getActivePlayers/телепорт.
    public boolean isAlive() { return inGame && lives >= 0; }

    public boolean isInGame() { return inGame; }
    public void setInGame(boolean b) { this.inGame = b; }

    public boolean isAirdropped() { return airdropped; }
    public void setAirdropped(boolean b) { this.airdropped = b; }

    public int getTeamId() { return teamId; }
    public void setTeamId(int id) { this.teamId = id; }

    public int getRoundKills() { return kills; }

    public int getTotalGames() { return totalGames; }
    public int getTotalWins() { return totalWins; }
    public int getTotalKills() { return totalKills; }

    public void setTotalGames(int v) { this.totalGames = v; }
    public void setTotalWins(int v) { this.totalWins = v; }
    public void setTotalKills(int v) { this.totalKills = v; }

    public void recordGameStart() { totalGames++; }
    public void recordWin() { totalWins++; }
    public void recordKill() { totalKills++; kills++; }
    /** Убийство только в счёт раунда (бот или игра против ботов). */
    public void recordRoundKill() { kills++; }

    public int getWinRate() {
        if (totalGames < 3) return 0;
        return (totalWins * 100) / totalGames;
    }

    public void resetRound(int startingLives) {
        this.lives = startingLives;
        this.inGame = true;
        this.airdropped = false;
        this.kills = 0;
        this.teamId = -1;
    }
}
