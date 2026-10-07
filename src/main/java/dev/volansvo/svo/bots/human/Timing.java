package dev.volansvo.svo.bots.human;

import java.util.Random;

/**
 * Человеческие задержки. Время реакции распределено с длинным правым хвостом: обычно
 * быстро, изредка заметно медленнее. Здесь это нормальное распределение плюс
 * экспоненциальная добавка, и редкое «зевнул».
 */
public final class Timing {

    private final Random rnd;
    /** Общий темп бота: больше - медленнее во всём. */
    private final double tempo;
    /** Доля случаев, когда бот отвлёкся и среагировал в 2-3 раза позже. */
    private final double lapse;

    public Timing(Random rnd, double tempo, double lapse) {
        this.rnd = rnd;
        this.tempo = tempo;
        this.lapse = lapse;
    }

    /** Задержка в тиках: mu и sigma - обычная реакция в миллисекундах, tau - средняя длина хвоста. */
    public int ticks(double mu, double sigma, double tau) {
        double ms = mu + rnd.nextGaussian() * sigma - tau * Math.log(1.0 - rnd.nextDouble());
        if (rnd.nextDouble() < lapse) ms *= 2.0 + rnd.nextDouble();
        return Math.max(1, (int) Math.round(ms * tempo / 50.0));
    }

    /** Цель появилась там, куда смотрел. */
    public int reaction() { return ticks(280, 50, 90); }

    /** Цель вышла из-за укрытия, которое бот держал на прицеле. */
    public int reacquire() { return ticks(170, 35, 60); }

    /** Урон или звук неизвестно откуда. */
    public int surprise() { return ticks(380, 60, 200); }

    /** Смена занятия, если это не рефлекс. */
    public int decide() { return ticks(350, 80, 250); }

    /** От смены предмета в руке до первого действия им. */
    public int swap() { return ticks(160, 30, 80); }

    /** Кнопка «возродиться» после смерти. */
    public int respawn() { return Math.min(160, ticks(1800, 400, 1500)); }

    /** Написать сообщение: подумать и набрать length знаков. */
    public int typing(int length) {
        double cps = 5.5 / tempo;
        return Math.min(160, ticks(900, 250, 900) + (int) Math.round(length / cps * 20.0));
    }

    /** Уйти с сервера после выбывания: от нескольких секунд до минуты. */
    public int leave() { return Math.min(1200, ticks(6000, 2000, 9000)); }
}
