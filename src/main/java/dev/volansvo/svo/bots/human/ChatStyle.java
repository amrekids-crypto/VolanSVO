package dev.volansvo.svo.bots.human;

import java.util.Random;

/**
 * Манера письма бота. Реплики в BotChatter написаны грамотно, как сценарий; здесь они
 * приводятся к тому, как пишут в чате: строчными, без точек, со скобочками и опечатками.
 * Вставки {k}, {v}, {n} (ники) не трогаются.
 */
public final class ChatStyle {

    public enum Style {
        /** Как написано. */
        PLAIN,
        /** Строчными, без знаков в конце, запятые через раз. */
        LAZY,
        /** Как LAZY плюс «)))» или «((». */
        BRACKETS,
        /** Заглавными и с «!!!», когда зол; иначе как LAZY. */
        SHOUT,
        /** Как LAZY плюс опечатка соседней клавишей. */
        TYPO,
        /** Латиницей. */
        TRANSLIT
    }

    private static final String[] ROWS = {"йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"};
    private static final String VOWELS = "аеиоуыэюя";
    private static final String CYR = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
    private static final String[] LAT = {"a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p",
        "r", "s", "t", "u", "f", "h", "c", "ch", "sh", "sh", "", "i", "", "e", "yu", "ya"};

    private ChatStyle() {}

    /**
     * @param sad   реплика о неудаче (смерть): скобочки будут грустные
     * @param angry бот зол: крикун пишет заглавными
     */
    public static String apply(String line, Style style, Random rnd, boolean sad, boolean angry) {
        if (line == null || style == Style.PLAIN) return line;
        StringBuilder out = new StringBuilder(line.length() + 4);
        // Режем на текст и вставки {x}: вставки идут как есть.
        int i = 0;
        // Опечатка - примерно в каждой третьей реплике того, кто пишет с опечатками.
        boolean typoDone = style != Style.TYPO || rnd.nextInt(3) != 0;
        while (i < line.length()) {
            int open = line.indexOf('{', i);
            int close = open < 0 ? -1 : line.indexOf('}', open);
            String text = line.substring(i, open < 0 || close < 0 ? line.length() : open);
            String piece = lazy(text, rnd);
            if (style == Style.TYPO && !typoDone) {
                String t = typo(piece, rnd);
                typoDone = !t.equals(piece);
                piece = t;
            } else if (style == Style.TRANSLIT) {
                piece = translit(piece);
            } else if (style == Style.SHOUT && angry) {
                piece = stretch(piece, rnd).toUpperCase();
            }
            out.append(piece);
            if (open < 0 || close < 0) break;
            out.append(line, open, close + 1);
            i = close + 1;
        }
        String s = trimEnd(out.toString());
        if (style == Style.BRACKETS) s += sad ? (rnd.nextBoolean() ? "((" : "(((") : (rnd.nextBoolean() ? "))" : ")))");
        if (style == Style.SHOUT && angry) {
            while (s.endsWith("!")) s = s.substring(0, s.length() - 1);
            s += "!!!";
        }
        return s;
    }

    private static String lazy(String s, Random rnd) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ',' && rnd.nextInt(10) < 7) continue;
            if (c == '.' || c == ':' || c == ';') {
                // Точку в середине заменяем пробелом, чтобы фразы не слиплись.
                if (i + 1 < s.length() && s.charAt(i + 1) != ' ') b.append(' ');
                continue;
            }
            b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    private static String trimEnd(String s) {
        int n = s.length();
        while (n > 0 && (s.charAt(n - 1) == ' ' || s.charAt(n - 1) == '!' && n > 1 && s.charAt(n - 2) == '!')) n--;
        return s.substring(0, n);
    }

    /** Одна опечатка: соседняя клавиша или две буквы местами. */
    private static String typo(String s, Random rnd) {
        int letters = 0;
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) letters++;
        if (letters < 5) return s;
        for (int tries = 0; tries < 8; tries++) {
            int at = rnd.nextInt(s.length());
            char c = s.charAt(at);
            if (!Character.isLetter(c)) continue;
            if (rnd.nextBoolean() && at + 1 < s.length() && Character.isLetter(s.charAt(at + 1))) {
                return s.substring(0, at) + s.charAt(at + 1) + c + s.substring(at + 2);
            }
            char n = neighbour(c, rnd);
            if (n != c) return s.substring(0, at) + n + s.substring(at + 1);
        }
        return s;
    }

    private static char neighbour(char c, Random rnd) {
        for (String row : ROWS) {
            int at = row.indexOf(c);
            if (at < 0) continue;
            int to = at + (rnd.nextBoolean() ? 1 : -1);
            if (to < 0) to = 1;
            if (to >= row.length()) to = row.length() - 2;
            return row.charAt(to);
        }
        return c;
    }

    /** Растянуть последнюю гласную: «горячо» -> «горячооо». */
    private static String stretch(String s, Random rnd) {
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (VOWELS.indexOf(c) < 0) continue;
            StringBuilder b = new StringBuilder(s);
            for (int k = 0, n = 2 + rnd.nextInt(3); k < n; k++) b.insert(i, c);
            return b.toString();
        }
        return s;
    }

    private static String translit(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int at = CYR.indexOf(Character.toLowerCase(c));
            b.append(at < 0 ? String.valueOf(c) : LAT[at]);
        }
        return b.toString();
    }
}
