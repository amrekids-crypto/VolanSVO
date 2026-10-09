package dev.volansvo.svo.bots.mind;

/**
 * Надзор за делом: у каждого дела есть число, которое должно уменьшаться (расстояние до цели,
 * сколько осталось собрать). Не уменьшается дольше положенного - дело провалено.
 */
public final class Progress {

    private String task;
    private double best;
    private int bestTick, startTick;

    /** Начать следить за делом task (смена ключа - новое дело). */
    public void track(String task, double value, int now) {
        if (task == null) { this.task = null; return; }
        if (!task.equals(this.task)) {
            this.task = task;
            best = value;
            bestTick = now;
            startTick = now;
            return;
        }
        if (value < best - 0.75) {
            best = value;
            bestTick = now;
        }
    }

    /** Занятие, которое само по себе считается движением (копает, строит, лутает): таймер сдвигается. */
    public void busy(int now) {
        bestTick = now;
    }

    public boolean stalled(int now, int limit) {
        return task != null && now - bestTick > limit;
    }

    public int stalledFor(int now) {
        return task == null ? 0 : now - bestTick;
    }

    public int age(int now) {
        return task == null ? 0 : now - startTick;
    }

    public String task() {
        return task;
    }

    public void reset() {
        task = null;
    }
}
