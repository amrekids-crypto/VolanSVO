package dev.volansvo.svo.bots;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ставящиеся предметы: мины, турели (пулемёт на треноге, ПВО), растяжки. MilitaryCraft узнаём
 * по его id предмета (warkit:item_id), мину ExecutableItems - по её id. Слова, по которым
 * узнаётся тип, меняются в config.yml (bots.deployables).
 *
 * Как ставит игрок: мина и растяжка - ПКМ по земле (мина ExecutableItems - ПКМ, встаёт под
 * себя), турель - ПКМ по земле рядом с собой.
 */
final class Gadgets {

    enum Type { MINE, TURRET, TRAP }

    private static final Map<Type, List<String>> WORDS = new EnumMap<Type, List<String>>(Type.class);
    static {
        WORDS.put(Type.MINE, Arrays.asList("mine", "claymore", "мина"));
        WORDS.put(Type.TURRET, Arrays.asList("turret", "machine_gun", "machinegun", "sentry", "tripod", "mounted", "пулем", "турел"));
        WORDS.put(Type.TRAP, Arrays.asList("tripwire", "trip_wire", "trap", "растяж", "ловушк"));
    }

    private Gadgets() {}

    static void setWords(Type t, List<String> words) {
        if (words == null || words.isEmpty()) return;
        List<String> low = new ArrayList<String>();
        for (String w : words) low.add(w.toLowerCase(Locale.ROOT));
        WORDS.put(t, low);
    }

    /** Что это за ставящийся предмет, или null. */
    static Type typeOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        String wid = Items.warkitId(it);
        if (wid != null) {
            String id = wid.toLowerCase(Locale.ROOT);
            // Стволы и гранаты MilitaryCraft - не ставятся, даже если в id есть похожее слово.
            if (id.equals("rifle") || id.equals("pistol") || id.endsWith("_grenade") || id.equals("grenade_launcher")) return null;
            for (Type t : Type.values()) for (String w : WORDS.get(t)) if (id.contains(w)) return t;
            return null;
        }
        if (EiKit.use(it) == EiKit.Use.MINE) return Type.MINE;
        return null;
    }

    /** Мина ExecutableItems ставится под себя (ПКМ в воздух), остальное - кликом по земле. */
    static boolean underSelf(ItemStack it) {
        return EiKit.use(it) == EiKit.Use.MINE;
    }

    static int find(PlayerInventory inv, Type t) {
        for (int i = 0; i < 36; i++) if (typeOf(inv.getItem(i)) == t) return i;
        return -1;
    }

    static int count(PlayerInventory inv) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (typeOf(it) != null) n += it.getAmount();
        }
        return n;
    }
}
