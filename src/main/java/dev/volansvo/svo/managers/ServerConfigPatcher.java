package dev.volansvo.svo.managers;

import dev.volansvo.svo.VolanSVO;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Отключает Paper-фичу "pause-when-empty-seconds".
 *
 * Когда на сервере не остаётся игроков, Paper усыпляет главный поток, и вместе
 * с ним замирает BukkitScheduler - таймер игры, сужение зоны, мобы. Из-за этого
 * при выходе последнего игрока вся игра СВО вставала на паузу.
 *
 * Патч текстовый (regex по сырому файлу) - комментарии и форматирование YAML
 * сохраняются, меняется только число. Требует разового рестарта сервера.
 */
public class ServerConfigPatcher {

    private final VolanSVO plugin;

    public ServerConfigPatcher(VolanSVO plugin) {
        this.plugin = plugin;
    }

    public void disablePauseWhenEmpty() {
        // Paper хранит world-defaults в разных местах в зависимости от версии/сборки
        File[] candidates = new File[] {
            new File("config" + File.separator + "paper-world-defaults.yml"),
            new File("paper-world-defaults.yml"),
            new File("paper.yml") // старые сборки
        };

        boolean changedAny = false;
        for (File f : candidates) {
            try {
                if (patchFile(f)) changedAny = true;
            } catch (Throwable t) {
                plugin.getLogger().warning("Не удалось обработать " + f.getPath() + ": " + t);
            }
        }

        if (changedAny) {
            plugin.getLogger().warning("=================================================");
            plugin.getLogger().warning(" Отключена пауза сервера при отсутствии игроков.");
            plugin.getLogger().warning(" ПЕРЕЗАПУСТИ СЕРВЕР один раз чтобы применить!");
            plugin.getLogger().warning(" (иначе игра СВО будет вставать на паузу когда");
            plugin.getLogger().warning("  выходит последний игрок)");
            plugin.getLogger().warning("=================================================");
        }
    }

    /** Возвращает true если файл был изменён. */
    private boolean patchFile(File f) throws Exception {
        if (!f.exists() || !f.isFile()) return false;

        String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        if (!content.contains("pause-when-empty-seconds")) return false;

        // Ищем "pause-when-empty-seconds: <число>" (может быть отрицательным)
        Pattern p = Pattern.compile("(pause-when-empty-seconds:\\s*)(-?\\d+)");
        Matcher m = p.matcher(content);
        if (!m.find()) return false;

        String current = m.group(2);
        if (current.equals("-1")) return false; // уже отключено

        String patched = m.replaceFirst("$1-1");
        Files.write(f.toPath(), patched.getBytes(StandardCharsets.UTF_8));
        plugin.getLogger().info("Патч " + f.getName()
            + ": pause-when-empty-seconds " + current + " -> -1");
        return true;
    }
}
