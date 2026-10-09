package dev.volansvo.svo.bots;

import dev.volansvo.svo.bots.mind.Belief;
import dev.volansvo.svo.bots.mind.Blacklist;
import dev.volansvo.svo.bots.mind.Board;
import dev.volansvo.svo.bots.mind.Journal;
import dev.volansvo.svo.bots.mind.Pace;
import dev.volansvo.svo.bots.mind.Progress;
import dev.volansvo.svo.bots.mind.Search;
import dev.volansvo.svo.bots.mind.Tactics;
import dev.volansvo.svo.bots.mind.Utility;

import java.io.File;
import java.nio.file.Files;
import java.util.Random;
import java.util.UUID;

/**
 * Проверка «головы» бота без сервера: убеждения, поиск, взвешивание, выбор позиции, темп
 * матча, доска отряда. Запуск: java -cp <классы плагина и Paper> dev.volansvo.svo.bots.MindCheck
 */
public final class MindCheck {

    private static int failed, passed;

    private static void check(String what, boolean ok) {
        if (ok) passed++;
        else { failed++; System.out.println("НЕ ПРОШЛО: " + what); }
    }

    /** Плоский мир из кубиков: пол на y=0, стены - где solid. */
    private static final class Grid implements Search.Probe, Tactics.Probe {
        final boolean[][][] solid = new boolean[64][8][64];

        Grid() {
            for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) solid[x][0][z] = true;
        }

        void wall(int x0, int z0, int x1, int z1, int height) {
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) for (int y = 1; y <= height; y++) solid[x][y][z] = true;
        }

        boolean block(int x, int y, int z) {
            if (x < 0 || z < 0 || x >= 64 || z >= 64 || y < 0) return true;
            return y < 8 && solid[x][y][z];
        }

        @Override public boolean standable(int x, int y, int z) {
            return !block(x, y, z) && !block(x, y + 1, z) && block(x, y - 1, z);
        }

        @Override public boolean solid(int x, int y, int z) { return block(x, y, z); }

        @Override public boolean sees(double ex, double ey, double ez, double x, double y, double z) {
            double dx = x - ex, dy = y - ey, dz = z - ez, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            for (double t = 0.1; t < len; t += 0.1) {
                if (block((int) Math.floor(ex + dx * t / len), (int) Math.floor(ey + dy * t / len), (int) Math.floor(ez + dz * t / len))) return false;
            }
            return true;
        }

        @Override public boolean inZone(double x, double z) { return x > 1 && z > 1 && x < 62 && z < 62; }

        @Override public double heat(double x, double z) { return 0; }
    }

    public static void main(String[] args) throws Exception {
        belief();
        search();
        utility();
        tactics();
        reliability();
        pace();
        board();
        learning();
        System.out.println("прошло " + passed + ", не прошло " + failed);
        if (failed > 0) System.exit(1);
    }

    private static void belief() {
        check("увиденному верят полностью", Belief.conf(Belief.Source.SEEN, 0) == 1.0);
        check("через 2 секунды по памяти ещё можно стрелять", Belief.conf(Belief.Source.SEEN, 40) > Belief.FIRE_CONF);
        check("через 3 секунды уже нельзя", Belief.conf(Belief.Source.SEEN, 60) < Belief.FIRE_CONF);
        check("через 8 секунд идём ещё прямо к месту", Belief.conf(Belief.Source.SEEN, 160) > Belief.CHASE_CONF);
        check("через 10 секунд - обыскиваем", Belief.conf(Belief.Source.SEEN, 200) < Belief.CHASE_CONF);
        check("шагам верят меньше, чем глазам", Belief.Source.STEPS.conf0 < Belief.Source.SEEN.conf0);
        check("метке на карте не хватает на прямую погоню", Belief.Source.MAP.conf0 < Belief.CHASE_CONF);
        check("доклад слабее своего взгляда", Belief.Source.CALLOUT.conf0 < Belief.FIRE_CONF);
        check("место расплывается", Belief.radius(0.5, 40, Belief.WALK) > 8 && Belief.radius(0.5, 40, Belief.WALK) < 10);
        check("стоявший враг далеко не ушёл", Belief.radius(0.5, 40, Belief.STILL) < Belief.FIRE_RADIUS);
        check("радиус ограничен", Belief.radius(0.5, 20 * 600, Belief.RUN) == 40);
        check("в начале матча враг слабее, чем в середине", Belief.expectedPower(0) < Belief.expectedPower(0.5));
    }

    private static void search() {
        Grid g = new Grid();
        // Стена поперёк с проходом: враг пропал за ней.
        g.wall(10, 30, 28, 30, 3);
        g.wall(32, 30, 54, 30, 3);
        Random rnd = new Random(7);
        Search s = new Search();
        s.seed(30.5, 1, 34.5, 0, 0.2, 10, 16, 0, rnd, g);
        check("догадки раскиданы", s.size() == 16);
        boolean allStand = true;
        for (Search.Guess q : s.guesses()) allStand &= g.standable((int) Math.floor(q.x), (int) q.y, (int) Math.floor(q.z));
        check("все догадки там, где можно стоять", allStand);
        // Бот стоит по эту сторону стены в стороне от прохода и смотрит в неё: за стеной ничего не проверить.
        int behind = 0;
        for (Search.Guess q : s.guesses()) if (q.z > 31 && q.x < 28) behind++;
        int removed = s.observe(15.5, 2.62, 25.5, 0.6, 0, 0.8, Math.toRadians(80), 40, g);
        int behindAfter = 0;
        for (Search.Guess q : s.guesses()) if (q.z > 31 && q.x < 28) behindAfter++;
        check("сквозь стену догадки не отбрасываются", behind > 0 && behindAfter == behind);
        // Вошёл в проход и осмотрелся: видимое отброшено.
        int before = s.size();
        removed = s.observe(30.5, 2.62, 31.5, 0, 0, 1, Math.toRadians(80), 30, g);
        check("увиденные пустые места отброшены", removed > 0 && s.size() == before - removed);
        Search.Guess pick = s.best(30.5, 1, 31.5);
        check("выбранной догадки держимся", pick == null || s.best(0, 1, 0) == pick);
        s.spread(40, rnd, g);
        for (Search.Guess q : s.guesses()) allStand &= g.standable((int) Math.floor(q.x), (int) q.y, (int) Math.floor(q.z));
        check("расползшиеся догадки остались на полу", allStand);
        while (s.active()) s.drop(s.best(30, 1, 31));
        check("проверил всё - поиск окончен", !s.active() && s.best(0, 0, 0) == null);
        // Место «в стене»: начинаем с ближайшего пола, а не теряем все догадки в одной точке.
        s.seed(20.5, 1, 30.5, 0, 0, 8, 12, 0, rnd, g);
        check("место в стене не ломает поиск", s.size() == 12);
    }

    private static void utility() {
        check("кривые в пределах 0..1", Utility.logistic(-50, 0, 4) >= 0 && Utility.logistic(50, 0, 4) <= 1 && Utility.power(2, 0.5) == 1);
        check("колокол в центре 1", Math.abs(Utility.bell(14, 14, 6) - 1) < 1e-9 && Utility.bell(40, 14, 6) < 0.01);
        check("одно нулевое соображение обнуляет намерение", Utility.score(1, 1, 0, 1) == 0);
        check("все единицы дают единицу", Math.abs(Utility.score(1, 1, 1, 1, 1) - 1) < 1e-9);
        double two = Utility.score(0.8, 0.8), six = Utility.score(0.8, 0.8, 0.8, 0.8, 0.8, 0.8);
        check("много соображений не топят намерение", six > Math.pow(0.8, 6) * 1.8 && six < two);
        // Равные силы, полное здоровье, подходящая дистанция: бот дерётся; при здоровье 4 - отходит.
        check("равные силы, цел - в бой", engage(1.0, 20) > disengage(1.0, 20));
        check("равные силы, почти мёртв - отход", engage(1.0, 4) < disengage(1.0, 4));
        check("враг вдвое сильнее - отход", engage(0.5, 20) < disengage(0.5, 20));
    }

    /** Те же соображения, что в Bot.wantsFight, при идеальных остальных. */
    private static double engage(double x, double hp) {
        return Utility.score(Utility.logistic(x, 1.0, 4.0), 1, 1, 0.3 + 0.7 * Utility.power(hp / 20.0, 0.6), 1, 1);
    }

    private static double disengage(double x, double hp) {
        return Utility.score(1 - Utility.logistic(x, 1.0, 4.0), 1.0, 0.4 + 0.6 * Utility.inverse(hp, 6, 20));
    }

    private static void tactics() {
        Grid g = new Grid();
        // Короткая стенка в полный рост между ботом и врагом.
        g.wall(29, 26, 31, 26, 2);
        Tactics t = new Tactics();
        Tactics.Need need = new Tactics.Need();
        need.range = 12;
        need.rangeWidth = 6;
        need.wCover = 2.0;
        t.start(need, 30.5, 1, 22.5, 30.5, 1, 34.5, null, null, null, g);
        int ticks = 0;
        while (!t.step(8, g)) ticks++;
        Tactics.Spot s = t.result();
        check("запрос уложился в несколько тиков", ticks <= 3);
        check("позиция найдена", s != null && s.score > 0);
        check("с позиции можно стрелять (прямо или выглянув)", s != null && (s.fire || s.hasPeek));
        check("осторожный выбрал место за стеной", s != null && s.cover == 2);
        if (s != null && s.hasPeek) check("из-за стены видно врага", g.sees(s.peekX, s.y + 1.62, s.peekZ, 30.5, 2.5, 34.5));
        boolean rejected = false;
        for (Tactics.Spot c : t.candidates()) if (c.score < 0) rejected = true;
        check("негодные точки отброшены с причиной", rejected);

        // Укрытие для лечения: точка, откуда врага не видно.
        Tactics.Need hide = new Tactics.Need();
        hide.hide = true; hide.needFire = false;
        hide.wFire = 0; hide.wRange = 0; hide.wCover = 1.5; hide.wPath = 1.4;
        hide.range = 12;
        t.start(hide, 30.5, 1, 22.5, 30.5, 1, 34.5, null, null, null, g);
        while (!t.step(10, g)) { }
        Tactics.Spot h = t.result();
        check("укрытие найдено", h != null && h.score > 0);
        check("в укрытии врагу нас не видно", h != null && !g.sees(h.x, h.y + 1.62, h.z, 30.5, 2.5, 34.5));

        // В чистом поле прятаться негде.
        Grid open = new Grid();
        t.start(hide, 30.5, 1, 22.5, 30.5, 1, 34.5, null, null, null, open);
        while (!t.step(10, open)) { }
        check("в чистом поле укрытия нет", t.result() == null);
        check("смена позиции только на заметно лучшую", !Tactics.worthMoving(spot(0.60), spot(0.65)) && Tactics.worthMoving(spot(0.50), spot(0.65)));
    }

    private static Tactics.Spot spot(double score) {
        Tactics.Spot s = new Tactics.Spot();
        s.score = score;
        return s;
    }

    private static void reliability() {
        Blacklist b = new Blacklist();
        int t1 = b.ban("chest@1,2,3", 100, 0, "не дойти");
        check("запрет действует", b.banned("chest@1,2,3", 50) && !b.banned("chest@1,2,3", 101));
        int t2 = b.ban("chest@1,2,3", 100, 200, "не дойти");
        int t3 = b.ban("chest@1,2,3", 100, 500, "не дойти");
        check("срок растёт с повторами", t1 == 100 && t2 == 200 && t3 == 400);
        for (int i = 0; i < 5; i++) b.ban("chest@1,2,3", 100, 1000 + i, null);
        check("но не больше чем в 8 раз", b.ban("chest@1,2,3", 100, 1100, null) == 800);
        check("давняя неудача забыта", b.ban("chest@1,2,3", 100, 1100 + 20 * 301, null) == 100);
        b.hold("leap", 30, 0);
        b.hold("leap", 30, 100);
        check("пауза не растёт", !b.banned("leap", 131));

        Progress p = new Progress();
        p.track("loot", 30, 0);
        p.track("loot", 25, 100);
        check("приближается - не застрял", !p.stalled(300, 400));
        p.track("loot", 25.5, 300);
        check("топчется - застрял", p.stalled(601, 500));
        p.busy(601);
        check("копает - не застрял", !p.stalled(700, 500));
        p.track("other", 99, 5000);
        check("новое дело - отсчёт заново", !p.stalled(5100, 500) && p.age(5100) == 100);

        Journal j = new Journal();
        for (int i = 0; i < 600; i++) j.add(i * 5, Journal.Kind.NOTE, "запись " + i);
        check("журнал держит последнюю минуту", j.lines(3000, 1200).size() == 240);
        j.add(3000, Journal.Kind.NOTE, "повтор");
        j.add(3010, Journal.Kind.NOTE, "повтор");
        check("повторы подряд не пишутся", j.lines(3010, 10).size() == 1);
    }

    private static void pace() {
        Pace pc = new Pace();
        UUID h = UUID.randomUUID(), b1 = UUID.randomUUID(), b2 = UUID.randomUUID(), b3 = UUID.randomUUID();
        check("первым двум ботам стрелять можно", pc.mayFire(h, b1, 0, false) && pc.mayFire(h, b2, 0, false));
        check("третьему нельзя", !pc.mayFire(h, b3, 1, false));
        check("но защищаться можно всегда", pc.mayFire(h, b3, 1, true));
        pc.clear();
        pc.mayFire(h, b1, 0, false);
        pc.mayFire(h, b2, 0, false);
        check("кто перестал стрелять, уступает очередь", pc.mayFire(h, b3, 100, false));

        pc.clear();
        pc.second(h, 0, 400);
        check("в начале - обычная игра", pc.state(h) == Pace.State.BUILD && pc.huntable(h));
        for (int i = 0; i < 4; i++) pc.hurt(h, 6, 20);
        pc.second(h, 20, 400);
        check("после тяжёлого боя - пик", pc.state(h) == Pace.State.PEAK && !pc.huntable(h));
        int now = 20;
        while (pc.state(h) == Pace.State.PEAK && now < 20 * 300) { now += 20; pc.second(h, now, 400); }
        check("жар спадает - передышка", pc.state(h) == Pace.State.RELAX && !pc.huntable(h) && now < 20 * 120);
        int relaxStart = now;
        while (pc.state(h) == Pace.State.RELAX) { now += 20; pc.second(h, now, 400); }
        check("передышка длится заданное время", now - relaxStart >= 400 && now - relaxStart <= 440 && pc.huntable(h));
        check("сразу после передышки не скучно", !pc.wantsVisitor(h, now));
        for (int i = 0; i < 95; i++) { now += 20; pc.second(h, now, 400); }
        check("полторы минуты тишины - скука", pc.state(h) == Pace.State.BORED);
        check("гостя зовём, но не чаще раза в минуту", pc.wantsVisitor(h, now) && !pc.wantsVisitor(h, now + 200));
    }

    private static void board() {
        Board bd = new Board();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        check("первый бронирует", bd.claim("chest@1,2,3", a, 0));
        check("второму занято", !bd.claim("chest@1,2,3", b, 10) && bd.taken("chest@1,2,3", b, 10) && !bd.taken("chest@1,2,3", a, 10));
        check("бронь без подтверждения снимается", bd.claim("chest@1,2,3", b, 20 * 9));
        bd.claim("x", a, 0);
        bd.releaseAll(a);
        check("погибший освобождает брони", !bd.taken("x", b, 1));
        bd.role(a, Board.Role.COVER);
        check("роли считаются без себя", bd.count(Board.Role.COVER, b) == 1 && bd.count(Board.Role.COVER, a) == 0);
    }

    private static void learning() throws Exception {
        File dir = Files.createTempDirectory("mindcheck").toFile();
        ItemLearning l = new ItemLearning(dir);
        check("незнакомый предмет стоит пробовать", l.worthTrying("x") && !l.isWeapon("x"));
        for (int i = 0; i < 3; i++) l.record("dud", 10, 0, 0);
        check("три промаха ещё не приговор", l.worthTrying("dud"));
        for (int i = 0; i < 3; i++) l.record("dud", 10, 0, 0);
        check("шесть промахов - бесполезен", !l.worthTrying("dud") && !l.isWeapon("dud"));
        l.record("gun", 10, 6, 0);
        check("одна удача - ещё не оружие", !l.isWeapon("gun") && l.worthTrying("gun"));
        l.record("gun", 10, 7, 0);
        l.record("gun", 12, 5, 0);
        check("три удачи - оружие", l.isWeapon("gun") && l.valueOf("gun") > 0);
        for (int i = 0; i < 4; i++) l.record("flame", 3, 8, 0);
        for (int i = 0; i < 5; i++) l.record("flame", 30, 0, 0);
        check("огнемёт годится вблизи и не годится вдали", l.worksAt("flame", 3) && !l.worksAt("flame", 30) && l.worksAt("flame", 12));
        for (int i = 0; i < 3; i++) l.record("suicide", 4, 3, 6);
        check("бьёт своего хозяина - не оружие", !l.isWeapon("suicide"));
        l.save();
        ItemLearning l2 = new ItemLearning(dir);
        check("знания переживают перезапуск", l2.isWeapon("gun") && !l2.worksAt("flame", 30) && !l2.worthTrying("dud"));
        // Файл прежнего вида (без дистанций) читается.
        File f = new File(dir, "bot_knowledge.yml");
        Files.write(f.toPath(), ("items:\n  i0:\n    key: old\n    uses: 5\n    enemy-damage: 40.0\n    self-damage: 0.0\n"
            + "  i1:\n    key: oldbad\n    uses: 6\n    enemy-damage: 0.0\n    self-damage: 3.0\n").getBytes("UTF-8"));
        ItemLearning l3 = new ItemLearning(dir);
        check("прежний файл знаний читается", l3.isWeapon("old") && !l3.worthTrying("oldbad"));
        f.delete();
        dir.delete();
    }
}
