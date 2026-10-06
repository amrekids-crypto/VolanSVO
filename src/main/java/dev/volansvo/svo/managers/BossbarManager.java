package dev.volansvo.svo.managers;

import dev.volansvo.svo.VolanSVO;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.Warden;

public class BossbarManager {

    private final VolanSVO plugin;
    private BossBar timerBar = null;
    private BossBar wardenBar = null;

    public BossbarManager(VolanSVO plugin) {
        this.plugin = plugin;
    }

    public void setupTimerBar(int totalTicks) {
        if (timerBar != null) timerBar.removeAll();
        timerBar = Bukkit.createBossBar(formatTime(totalTicks), BarColor.RED, BarStyle.SOLID);
        timerBar.setProgress(1.0);
        timerBar.setVisible(true);
        for (Player p : plugin.getGameManager().getAllSvoPlayers()) timerBar.addPlayer(p);
    }

    public void updateTimerProgress(int currentTick, int totalTicks) {
        if (timerBar == null) return;
        double progress = Math.max(0.0, Math.min(1.0, (double) currentTick / totalTicks));
        timerBar.setProgress(progress);
        timerBar.setTitle(formatTime(currentTick));
        if (currentTick <= 2000) timerBar.setColor(BarColor.RED);
        else if (currentTick <= 6000) timerBar.setColor(BarColor.YELLOW);
        else timerBar.setColor(BarColor.GREEN);
    }

    public void hideTimerBar() {
        if (timerBar != null) {
            timerBar.setVisible(false);
            timerBar.removeAll();
            timerBar = null;
        }
    }

    private String formatTime(int ticks) {
        int s = ticks / 20;
        int m = s / 60;
        int rs = s % 60;
        int alive = plugin.getGameManager() != null ? plugin.getGameManager().getActivePlayers().size() : 0;
        return String.format("СВО %02d:%02d | Живых: %d", m, rs, alive);
    }

    /** Имя босса для активной карты (svo=Жириновский, east=Нетаньяху). */
    private String wardenName() {
        dev.volansvo.svo.maps.MapData m = plugin.getMapManager().getActiveMap();
        return (m != null) ? m.getWardenName() : "Жириновский";
    }

    public void setupWardenBar(Warden warden) {
        if (wardenBar != null) wardenBar.removeAll();
        wardenBar = Bukkit.createBossBar(wardenName(), BarColor.PURPLE, BarStyle.SEGMENTED_10);
        wardenBar.setVisible(true);
        for (Player p : plugin.getGameManager().getAllSvoPlayers()) wardenBar.addPlayer(p);
        updateWardenBar(warden);
        // Сдвигаем боссбар ХП тиммейтов ниже только что появившегося варден-бара.
        plugin.getGameManager().bumpTeamHpBars();
    }

    public void updateWardenBar(Warden warden) {
        if (wardenBar == null || !warden.isValid()) return;
        AttributeInstance attr = warden.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = attr != null ? attr.getValue() : 500.0;
        double progress = Math.max(0.0, Math.min(1.0, warden.getHealth() / maxHp));
        wardenBar.setProgress(progress);
        wardenBar.setTitle(wardenName());
    }

    public void hideWardenBar() {
        if (wardenBar != null) {
            wardenBar.setVisible(false);
            wardenBar.removeAll();
            wardenBar = null;
        }
    }

    public void addPlayerToAll(Player p) {
        if (timerBar != null) timerBar.addPlayer(p);
        if (wardenBar != null) wardenBar.addPlayer(p);
    }

    public void cleanup() {
        hideTimerBar();
        hideWardenBar();
    }
}
