package dev.volansvo.svo.bots;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;

import java.util.Map;

/**
 * Крафт, которым пользуется бот: брёвна в доски (из одного бревна четыре блока на столб),
 * доски в палки, верстак и лестницы. Рецепты берутся у сервера (Bukkit.getCraftingRecipe),
 * поэтому подходят любые породы дерева и рецепты, изменённые плагинами.
 *
 * Сам бот при этом ведёт себя как игрок: на крафт тратит время, стоя на месте, а лестницы
 * (рецепт 3x3) делает только у верстака - ставит свой, если рядом нет.
 */
final class Crafting {

    private Crafting() {}

    static boolean isLog(Material m) { return Tag.LOGS.isTagged(m); }

    static boolean isPlanks(Material m) { return Tag.PLANKS.isTagged(m); }

    static int count(PlayerInventory inv, Material m) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() == m && !it.hasItemMeta()) n += it.getAmount();
        }
        return n;
    }

    /** Сколько обычных (не плагинных) брёвен / досок. */
    static int countTag(PlayerInventory inv, boolean logs) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.hasItemMeta()) continue;
            if (logs ? isLog(it.getType()) : isPlanks(it.getType())) n += it.getAmount();
        }
        return n;
    }

    private static int firstTag(PlayerInventory inv, boolean logs) {
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.hasItemMeta()) continue;
            if (logs ? isLog(it.getType()) : isPlanks(it.getType())) return i;
        }
        return -1;
    }

    /** Результат рецепта по сетке 3x3 (слоты 0..8, null - пусто) или null. */
    private static ItemStack result(World w, ItemStack[] grid) {
        try {
            Recipe r = Bukkit.getCraftingRecipe(grid, w);
            return r == null ? null : r.getResult();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Убрать из инвентаря n штук материала m (только обычных предметов). */
    private static void take(PlayerInventory inv, Material m, int n) {
        for (int i = 0; i < 36 && n > 0; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() != m || it.hasItemMeta()) continue;
            int k = Math.min(n, it.getAmount());
            n -= k;
            if (k >= it.getAmount()) inv.setItem(i, null);
            else it.setAmount(it.getAmount() - k);
        }
    }

    /** Положить результат; что не влезло - вернуть false (тогда крафт не делаем). */
    private static boolean fits(PlayerInventory inv, ItemStack add) {
        int free = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) return true;
            if (it.isSimilar(add)) free += it.getMaxStackSize() - it.getAmount();
        }
        return free >= add.getAmount();
    }

    private static void give(PlayerInventory inv, ItemStack add) {
        Map<Integer, ItemStack> left = inv.addItem(add);
        // Не влезло - fits() это проверял, сюда не попадаем.
        if (!left.isEmpty()) left.clear();
    }

    /** Одно бревно в доски. true - скрафтили. */
    static boolean planks(World w, PlayerInventory inv) {
        int s = firstTag(inv, true);
        if (s < 0) return false;
        Material log = inv.getItem(s).getType();
        ItemStack[] g = new ItemStack[9];
        g[0] = new ItemStack(log);
        ItemStack res = result(w, g);
        if (res == null || !isPlanks(res.getType()) || !fits(inv, res)) return false;
        take(inv, log, 1);
        give(inv, res);
        return true;
    }

    /** Две доски в палки. */
    /** Порода досок, которых не меньше n штук (доски разных пород в одном рецепте - не у всех рецептов). */
    private static Material planksWithAtLeast(PlayerInventory inv, int n) {
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && !it.hasItemMeta() && isPlanks(it.getType()) && count(inv, it.getType()) >= n) return it.getType();
        }
        return null;
    }

    static boolean sticks(World w, PlayerInventory inv) {
        Material pl = planksWithAtLeast(inv, 2);
        if (pl == null) return false;
        ItemStack[] g = new ItemStack[9];
        g[0] = new ItemStack(pl);
        g[3] = new ItemStack(pl);
        ItemStack res = result(w, g);
        if (res == null || res.getType() != Material.STICK || !fits(inv, res)) return false;
        take(inv, pl, 2);
        give(inv, res);
        return true;
    }

    /** Четыре доски одной породы в верстак. */
    static boolean table(World w, PlayerInventory inv) {
        Material pl = planksWithAtLeast(inv, 4);
        if (pl == null) return false;
        ItemStack[] g = new ItemStack[9];
        g[0] = new ItemStack(pl); g[1] = new ItemStack(pl); g[3] = new ItemStack(pl); g[4] = new ItemStack(pl);
        ItemStack res = result(w, g);
        if (res == null || res.getType() != Material.CRAFTING_TABLE || !fits(inv, res)) return false;
        take(inv, pl, 4);
        give(inv, res);
        return true;
    }

    /** Из чего бот делает меч: от лучшего к худшему (доски - отдельно, любой породы). */
    private static final Material[][] SWORDS = {
        {Material.DIAMOND, Material.DIAMOND_SWORD},
        {Material.IRON_INGOT, Material.IRON_SWORD},
        {Material.COBBLESTONE, Material.STONE_SWORD},
        {Material.COBBLED_DEEPSLATE, Material.STONE_SWORD},
        {Material.BLACKSTONE, Material.STONE_SWORD},
    };

    /** Материал на клинок лучшего меча, чем есть (урон в секунду больше have), или null. */
    static Material betterSword(World w, PlayerInventory inv, double have) {
        int sticks = count(inv, Material.STICK);
        Material pl = planksWithAtLeast(inv, 1);
        int planks = pl == null ? 0 : count(inv, pl);
        for (Material[] s : SWORDS) {
            if (count(inv, s[0]) < 2 || sticks < 1 && planks < 2) continue;
            if (Items.meleeDps(new ItemStack(s[1])) > have + 0.5) return s[0];
            return null; // лучший доступный не лучше нашего - остальные тем более
        }
        if (pl != null && planks >= (sticks > 0 ? 2 : 4) && Items.meleeDps(new ItemStack(Material.WOODEN_SWORD)) > have + 0.5) return pl;
        return null;
    }

    /** Меч у верстака: два материала и палка (нет палки - из двух досок). Возвращает меч или null. */
    static ItemStack sword(World w, PlayerInventory inv, double have) {
        Material head = betterSword(w, inv, have);
        if (head == null) return null;
        if (count(inv, Material.STICK) < 1 && !sticks(w, inv)) return null;
        if (count(inv, head) < 2) return null; // доски ушли на палки
        ItemStack[] g = new ItemStack[9];
        g[1] = new ItemStack(head);
        g[4] = new ItemStack(head);
        g[7] = new ItemStack(Material.STICK);
        ItemStack res = result(w, g);
        if (res == null || !res.getType().name().endsWith("_SWORD") || !fits(inv, res)) return null;
        take(inv, head, 2);
        take(inv, Material.STICK, 1);
        give(inv, res);
        return res;
    }

    /** Семь палок в лестницы (только у верстака - это проверяет бот). */
    static boolean ladders(World w, PlayerInventory inv) {
        if (count(inv, Material.STICK) < 7) return false;
        ItemStack st = new ItemStack(Material.STICK);
        ItemStack[] g = {st, null, st, st, st, st, st, null, st};
        ItemStack res = result(w, g);
        if (res == null || res.getType() != Material.LADDER || !fits(inv, res)) return false;
        take(inv, Material.STICK, 7);
        give(inv, res);
        return true;
    }
}
