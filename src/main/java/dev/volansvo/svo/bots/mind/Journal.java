package dev.volansvo.svo.bots.mind;

import java.util.ArrayList;
import java.util.List;

/**
 * Журнал решений бота за последнюю минуту: что заметил, что решил и почему, что не вышло.
 * По нему разбирают странное поведение (/asvobot dump).
 */
public final class Journal {

    public enum Kind {
        /** Заметил: увидел, услышал, доложили. */
        SENSE("вижу"),
        /** Сменил намерение. */
        INTENT("решил"),
        /** Дело провалено. */
        FAIL("провал"),
        /** Нарушено правило «так быть не должно». */
        RULE("сбой"),
        /** Прочее. */
        NOTE("заметка");

        final String title;

        Kind(String title) { this.title = title; }
    }

    private static final int SIZE = 256;

    private final int[] ticks = new int[SIZE];
    private final Kind[] kinds = new Kind[SIZE];
    private final String[] texts = new String[SIZE];
    private int head, count;

    public void add(int now, Kind kind, String text) {
        // Одно и то же подряд не пишем: журнал короткий.
        if (count > 0) {
            int last = (head + SIZE - 1) % SIZE;
            if (kinds[last] == kind && text.equals(texts[last]) && now - ticks[last] < 100) return;
        }
        ticks[head] = now;
        kinds[head] = kind;
        texts[head] = text;
        head = (head + 1) % SIZE;
        if (count < SIZE) count++;
    }

    /** Записи не старше maxAge тиков, от старых к новым. */
    public List<String> lines(int now, int maxAge) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            int k = (head + SIZE - count + i) % SIZE;
            if (now - ticks[k] > maxAge) continue;
            out.add(String.format(java.util.Locale.ROOT, "%6.1fс %-7s %s", ticks[k] / 20.0, kinds[k].title, texts[k]));
        }
        return out;
    }

    public void clear() {
        head = 0;
        count = 0;
    }
}
