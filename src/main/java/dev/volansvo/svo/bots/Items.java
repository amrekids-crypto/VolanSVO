package dev.volansvo.svo.bots;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Collection;
import java.util.Locale;

/**
 * Что бот понимает в предметах. Классификация без привязки к конкретному луту:
 * оружие ближнего боя и броня оцениваются по их реальным атрибутам (урон, скорость,
 * броня, зачарования), оружие MilitaryCraft - по его PDC-метке warkit:item_id,
 * прочие плагинные предметы считаются «неизвестными» и изучаются в бою (см. ItemLearning).
 */
public final class Items {

    private Items() {}

    public enum Kind {
        NONE, MELEE, BOW, CROSSBOW, TRIDENT, GUN, LAUNCHER, THROW_DAMAGE, THROW_UTILITY, SPRAYER,
        HEAL, FOOD, ARMOR, SHIELD, TOTEM, PEARL, SPLASH_HARM, BUFF, MILK, NUKE, CUSTOM
    }

    /** Чем является плагинный предмет (ExecutableItems и т.п.), узнаём по его id и названию. */
    public enum Custom { UNKNOWN, AUTO, SHOTGUN, DRONE, FPV, BOMBER, AMMO }

    /** Слова, по которым узнаётся тип плагинного предмета (меняются в config: bots.custom-items). */
    private static final java.util.Map<Custom, java.util.List<String>> MATCH = new java.util.EnumMap<Custom, java.util.List<String>>(Custom.class);
    static {
        MATCH.put(Custom.FPV, java.util.Arrays.asList("bfpv", "fpv", "камикадз", "kamikaz"));
        MATCH.put(Custom.BOMBER, java.util.Arrays.asList("bombsender", "бомбил", "bomber", "бомбер"));
        MATCH.put(Custom.DRONE, java.util.Arrays.asList("drone", "dron", "дрон", "самонавод"));
        MATCH.put(Custom.SHOTGUN, java.util.Arrays.asList("drob", "дробов", "shotgun", "обрез"));
        MATCH.put(Custom.AUTO, java.util.Arrays.asList("kalash", "калаш", "ak47", "ak-47", "ak74", "автомат",
            "avtomat", "automat", "rifle", "винтовк", "smg", "пулемет", "пулемёт", "gun_"));
        MATCH.put(Custom.AMMO, java.util.Arrays.asList("патрон", "ammo", "пули", "magazine", "магазин", "дробь"));
    }

    /** Подменить слова для типа (из конфига). Пустой список - оставить встроенные. */
    public static void setMatch(Custom type, java.util.List<String> words) {
        if (words == null || words.isEmpty()) return;
        java.util.List<String> low = new java.util.ArrayList<String>();
        for (String w : words) low.add(w.toLowerCase(Locale.ROOT));
        MATCH.put(type, low);
    }

    /** id предмета ExecutableItems (PDC executableitems:*) или null. */
    public static String eiId(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        PersistentDataContainer pdc = it.getItemMeta().getPersistentDataContainer();
        for (NamespacedKey k : pdc.getKeys()) {
            if (!k.getNamespace().equalsIgnoreCase("executableitems")) continue;
            try {
                String v = pdc.get(k, PersistentDataType.STRING);
                if (v != null && !v.isEmpty()) return v;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Тип плагинного предмета (по id и видимому названию без цветов). */
    public static Custom customType(ItemStack it) {
        if (it == null || !it.hasItemMeta() || warkitId(it) != null) return Custom.UNKNOWN;
        StringBuilder sb = new StringBuilder();
        String ei = eiId(it);
        if (ei != null) sb.append(ei).append(' ');
        ItemMeta m = it.getItemMeta();
        if (m.hasDisplayName()) sb.append(stripColor(m.getDisplayName()));
        String text = sb.toString().toLowerCase(Locale.ROOT);
        if (text.trim().isEmpty()) return Custom.UNKNOWN;
        // Точные id предметов с сервера.
        if (ei != null) {
            String id = ei.toLowerCase(Locale.ROOT);
            if (id.equals("bfpv")) return Custom.FPV;
            if (id.equals("bombsender")) return Custom.BOMBER;
            if (id.equals("minidrone")) return Custom.DRONE;
            if (id.startsWith("re_gun_drob")) return Custom.SHOTGUN;
            if (id.startsWith("re_gun_ak")) return Custom.AUTO;
            if (id.equals("bullets1") || id.equals("bulletsd")) return Custom.AMMO;
            if (EiKit.use(it) != null) return Custom.UNKNOWN; // ракетница, снайперка и т.п. - отдельно
        }
        // Порядок важен: "дрон" раньше оружия, патроны раньше стволов (у патронов в имени бывает "автомат").
        for (Custom c : new Custom[]{Custom.FPV, Custom.BOMBER, Custom.DRONE, Custom.AMMO, Custom.SHOTGUN, Custom.AUTO}) {
            for (String w : MATCH.get(c)) if (text.contains(w)) return c;
        }
        return Custom.UNKNOWN;
    }

    /** Блок, из которого бот может строить (столб, укрытие). */
    public static boolean isBuildBlock(ItemStack it) {
        if (it == null || it.getType().isAir() || it.hasItemMeta() && isCustom(it)) return false;
        Material m = it.getType();
        if (!m.isBlock() || !m.isSolid() || m.hasGravity() || m.isInteractable()) return false;
        String n = m.name();
        if (n.contains("TNT") || n.contains("SHULKER") || n.contains("SLAB") || n.contains("STAIRS")
                || n.contains("LEAVES") || n.contains("SIGN") || n.contains("BANNER") || n.contains("HEAD")
                || n.contains("SKULL") || n.contains("PRESSURE") || n.contains("BED") || n.contains("CARPET")
                || n.contains("GLASS_PANE") || n.contains("ICE") || m == Material.SPAWNER || m == Material.BEDROCK) return false;
        return true;
    }

    public static final NamespacedKey WARKIT_ID = new NamespacedKey("warkit", "item_id");
    public static final NamespacedKey WARKIT_AMMO = new NamespacedKey("warkit", "ammo");

    /** id MilitaryCraft или null. */
    public static String warkitId(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        PersistentDataContainer pdc = it.getItemMeta().getPersistentDataContainer();
        return pdc.get(WARKIT_ID, PersistentDataType.STRING);
    }

    public static int ammo(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return -1;
        Integer a = it.getItemMeta().getPersistentDataContainer().get(WARKIT_AMMO, PersistentDataType.INTEGER);
        return a == null ? -1 : a;
    }

    private static final java.util.regex.Pattern LORE_LOADED =
        java.util.regex.Pattern.compile("(?:заряжено|магазин|loaded|clip)\\D*?(\\d+)\\s*/\\s*(\\d+)");
    private static final java.util.regex.Pattern LORE_RESERVE =
        java.util.regex.Pattern.compile("(?:всего патронов|запас|патрон|ammo|reserve)\\D*?(\\d+)\\s*(?:/\\s*(\\d+))?");

    /**
     * Магазин плагинного ствола из описания («Заряжено: 12/35», «Всего патронов: 70/140»):
     * {в магазине, ёмкость, в запасе (-1 если не указано)} или null, если в описании этого нет.
     */
    public static int[] eiMag(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        ItemMeta m = it.getItemMeta();
        if (!m.hasLore()) return null;
        java.util.List<String> lore = m.getLore();
        if (lore == null) return null;
        int[] r = null;
        int reserve = -1;
        try {
        for (String line : lore) {
            String s = stripColor(line).toLowerCase(Locale.ROOT);
            java.util.regex.Matcher a = LORE_LOADED.matcher(s);
            if (r == null && a.find()) {
                r = new int[]{Integer.parseInt(a.group(1)), Integer.parseInt(a.group(2)), -1};
                continue;
            }
            java.util.regex.Matcher b = LORE_RESERVE.matcher(s);
            if (reserve < 0 && b.find()) reserve = Integer.parseInt(b.group(1));
        }
        } catch (NumberFormatException e) { return null; }
        if (r == null || r[1] <= 0) return null;
        r[2] = reserve;
        return r;
    }

    public static Kind kind(ItemStack it, VolanHooks hooks) {
        if (it == null || it.getType().isAir()) return Kind.NONE;
        if (hooks.isNukeButton(it)) return Kind.NUKE;
        String wid = warkitId(it);
        if (wid != null) return warkitKind(wid, it);
        Material m = it.getType();
        String n = m.name();
        // Плагинные стволы часто сделаны из мотыги/лука/арбалета - сначала смотрим, не они ли это.
        if (isCustom(it) && !n.endsWith("_SWORD") && !n.endsWith("_AXE") && !isArmor(m)
                && !((m == Material.BOW || m == Material.CROSSBOW || m == Material.SHIELD || m == Material.TRIDENT)
                     && customType(it) == Custom.UNKNOWN && EiKit.use(it) == null)) {
            if (m != Material.BOW && m != Material.CROSSBOW && m.isEdible()) {
                Custom ct = customType(it);
                if (ct == Custom.UNKNOWN) return Kind.FOOD;
            }
            return Kind.CUSTOM;
        }
        if (n.endsWith("_SWORD") || n.endsWith("_AXE") || m == Material.MACE) return Kind.MELEE;
        if (m == Material.BOW) return Kind.BOW;
        if (m == Material.CROSSBOW) return Kind.CROSSBOW;
        if (m == Material.TRIDENT) return Kind.TRIDENT;
        if (m == Material.SHIELD) return Kind.SHIELD;
        if (m == Material.TOTEM_OF_UNDYING) return Kind.TOTEM;
        if (m == Material.ENDER_PEARL) return Kind.PEARL;
        if (m == Material.MILK_BUCKET) return Kind.MILK;
        if (m == Material.GOLDEN_APPLE || m == Material.ENCHANTED_GOLDEN_APPLE) return Kind.HEAL;
        if (m == Material.POTION || m == Material.SPLASH_POTION || m == Material.LINGERING_POTION) return potionKind(it);
        if (isArmor(m)) return Kind.ARMOR;
        if (isCustom(it)) {
            // Плагинное оружие на базе меча/еды и т.п. уже поймано выше по материалу.
            return Kind.CUSTOM;
        }
        if (m.isEdible()) return Kind.FOOD;
        if (m == Material.SNOWBALL || m == Material.EGG) return Kind.THROW_UTILITY;
        return Kind.NONE;
    }

    private static Kind warkitKind(String id, ItemStack it) {
        switch (id) {
            case "rifle": case "pistol": return Kind.GUN;
            case "grenade_launcher": case "patriot": return Kind.LAUNCHER;
            case "frag_grenade": case "molotov": return Kind.THROW_DAMAGE;
            case "flash_grenade": case "smoke_grenade": case "impulse_grenade": case "sleep_gas": return Kind.THROW_UTILITY;
            case "flamethrower": case "chemical_sprayer": return Kind.SPRAYER;
            case "medkit": case "painkiller": return Kind.HEAL;
            case "ration": return Kind.FOOD;
            case "combat_stim": return Kind.BUFF;
            case "trench_shovel": return Kind.MELEE;
            default:
                if (isArmor(it.getType())) return Kind.ARMOR;
                return Kind.NONE; // развёртываемое (пулемёт, мины, растяжки) боту не нужно
        }
    }

    private static Kind potionKind(ItemStack it) {
        if (!(it.getItemMeta() instanceof PotionMeta)) return Kind.NONE;
        PotionMeta pm = (PotionMeta) it.getItemMeta();
        boolean heal = false, harm = false, buff = false;
        if (pm.getBasePotionType() != null) {
            for (PotionEffect e : pm.getBasePotionType().getPotionEffects()) {
                PotionEffectType t = e.getType();
                if (t.equals(PotionEffectType.INSTANT_HEALTH) || t.equals(PotionEffectType.REGENERATION)) heal = true;
                else if (t.equals(PotionEffectType.INSTANT_DAMAGE) || t.equals(PotionEffectType.POISON)
                    || t.equals(PotionEffectType.WEAKNESS) || t.equals(PotionEffectType.SLOWNESS)) harm = true;
                else buff = true;
            }
        }
        for (PotionEffect e : pm.getCustomEffects()) {
            if (e.getType().equals(PotionEffectType.INSTANT_HEALTH) || e.getType().equals(PotionEffectType.REGENERATION)) heal = true;
            else if (e.getType().equals(PotionEffectType.INSTANT_DAMAGE)) harm = true;
            else buff = true;
        }
        if (harm && it.getType() != Material.POTION) return Kind.SPLASH_HARM;
        if (heal) return Kind.HEAL;
        if (buff && it.getType() == Material.POTION) return Kind.BUFF;
        return Kind.NONE;
    }

    public static boolean isArmor(Material m) {
        String n = m.name();
        return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS")
            || n.endsWith("_BOOTS") || m == Material.TURTLE_HELMET;
    }

    public static EquipmentSlot armorSlot(Material m) {
        String n = m.name();
        if (n.endsWith("_HELMET")) return EquipmentSlot.HEAD;
        if (n.endsWith("_CHESTPLATE")) return EquipmentSlot.CHEST;
        if (n.endsWith("_LEGGINGS")) return EquipmentSlot.LEGS;
        if (n.endsWith("_BOOTS")) return EquipmentSlot.FEET;
        return null;
    }

    /** Предмет помечен другим плагином: свои PDC-ключи, модель или имя. */
    public static boolean isCustom(ItemStack it) {
        if (!it.hasItemMeta()) return false;
        ItemMeta m = it.getItemMeta();
        if (!m.getPersistentDataContainer().getKeys().isEmpty()) return true;
        if (m.hasCustomModelData()) return true;
        try { if (m.hasItemModel()) return true; } catch (Throwable ignored) {}
        return false;
    }

    /** Ключ, по которому бот запоминает «неизвестный» предмет. */
    public static String customKey(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            for (NamespacedKey k : m.getPersistentDataContainer().getKeys()) {
                String v = null;
                try { v = m.getPersistentDataContainer().get(k, PersistentDataType.STRING); } catch (Throwable ignored) {}
                if (v != null && v.length() < 64) return k + "=" + v;
            }
            if (m.hasDisplayName()) return it.getType().name() + ":" + stripColor(m.getDisplayName());
            String ei = eiId(it);
            if (ei != null) return "ei=" + ei;
            if (m.hasCustomModelData()) return it.getType().name() + "#" + m.getCustomModelData();
        }
        return it.getType().name().toLowerCase(Locale.ROOT);
    }

    static String stripColor(String s) {
        return s.replaceAll("§.", "").replaceAll("&#[0-9a-fA-F]{6}", "").replaceAll("&[0-9a-fk-orA-FK-OR]", "");
    }

    // ------------------------------------------------------------------ оценки

    /** Урон в секунду ближнего боя (с учётом скорости атаки и остроты). */
    public static double meleeDps(ItemStack it) {
        double dmg = 1.0, speed = 4.0;
        Collection<AttributeModifier> d = modifiers(it, Attribute.ATTACK_DAMAGE, EquipmentSlot.HAND);
        Collection<AttributeModifier> s = modifiers(it, Attribute.ATTACK_SPEED, EquipmentSlot.HAND);
        for (AttributeModifier am : d) if (am.getOperation() == AttributeModifier.Operation.ADD_NUMBER) dmg += am.getAmount();
        for (AttributeModifier am : s) if (am.getOperation() == AttributeModifier.Operation.ADD_NUMBER) speed += am.getAmount();
        if (it != null && it.hasItemMeta()) {
            int sharp = it.getEnchantmentLevel(Enchantment.SHARPNESS);
            if (sharp > 0) dmg += 0.5 * sharp + 0.5;
            if (it.getEnchantmentLevel(Enchantment.FIRE_ASPECT) > 0) dmg += 1.0;
        }
        speed = Math.max(0.5, Math.min(4.0, speed));
        // Полностью заряженный удар раз в 1/speed секунд; кулак = 1 урона * 4.
        return dmg * Math.min(speed, 1.6) + dmg * 0.4;
    }

    public static double meleeDamage(ItemStack it) {
        double dmg = 1.0;
        for (AttributeModifier am : modifiers(it, Attribute.ATTACK_DAMAGE, EquipmentSlot.HAND))
            if (am.getOperation() == AttributeModifier.Operation.ADD_NUMBER) dmg += am.getAmount();
        if (it != null) {
            int sharp = it.getEnchantmentLevel(Enchantment.SHARPNESS);
            if (sharp > 0) dmg += 0.5 * sharp + 0.5;
        }
        return dmg;
    }

    /** Защитная ценность предмета брони. */
    public static double armorValue(ItemStack it) {
        if (it == null || it.getType().isAir()) return 0;
        // Пояс шахида как броню не надеваем: двойной присед рядом с врагом его взрывает.
        if ("suicide_vest".equals(warkitId(it))) return 0;
        EquipmentSlot slot = armorSlot(it.getType());
        if (slot == null) return 0;
        double armor = 0, tough = 0;
        for (AttributeModifier am : modifiers(it, Attribute.ARMOR, slot))
            if (am.getOperation() == AttributeModifier.Operation.ADD_NUMBER) armor += am.getAmount();
        for (AttributeModifier am : modifiers(it, Attribute.ARMOR_TOUGHNESS, slot))
            if (am.getOperation() == AttributeModifier.Operation.ADD_NUMBER) tough += am.getAmount();
        double v = armor + tough * 0.6;
        v += it.getEnchantmentLevel(Enchantment.PROTECTION) * 0.9;
        v += it.getEnchantmentLevel(Enchantment.PROJECTILE_PROTECTION) * 0.4;
        v += it.getEnchantmentLevel(Enchantment.BLAST_PROTECTION) * 0.4;
        if (it.getType() == Material.CARVED_PUMPKIN) v = 0;
        // Почти сломанная броня хуже целой.
        short max = it.getType().getMaxDurability();
        if (max > 0 && it.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable) {
            int dmg = ((org.bukkit.inventory.meta.Damageable) it.getItemMeta()).getDamage();
            if (dmg > max * 0.9) v *= 0.4;
        }
        return v;
    }

    /** Насколько бот хочет держать предмет (для подбора и выбора, что взять из сундука). */
    public static double value(ItemStack it, VolanHooks hooks, ItemLearning learning) {
        Kind k = kind(it, hooks);
        switch (k) {
            case NUKE: return 1000;
            case GUN: return 60;
            case LAUNCHER: return 45;
            case SPRAYER: return 30;
            case THROW_DAMAGE: return 25;
            case THROW_UTILITY: return 6;
            case BOW: return 28;
            case CROSSBOW: return 26;
            case TRIDENT: return 20;
            case MELEE: return 4 + meleeDps(it) * 2.2;
            case ARMOR: return 6 + armorValue(it) * 4;
            case SHIELD: return 14;
            case TOTEM: return 50;
            case HEAL: return 35;
            case FOOD: return 8;
            case PEARL: return 18;
            case SPLASH_HARM: return 16;
            case BUFF: return 10;
            case MILK: return 3;
            case CUSTOM: {
                switch (customType(it)) {
                    case AUTO: return 65;
                    case SHOTGUN: return 55;
                    case DRONE: case FPV: case BOMBER: return 50;
                    case AMMO: return 15;
                    default: {
                        double ev = EiKit.value(it);
                        return ev > 0 ? ev : learning.valueOf(Items.customKey(it));
                    }
                }
            }
            default: return 0;
        }
    }

    /** Сколько единиц голода восстанавливает еда (приблизительно). */
    public static int foodValue(Material m) {
        switch (m) {
            case GOLDEN_CARROT: return 6;
            case COOKED_BEEF: case COOKED_PORKCHOP: return 8;
            case COOKED_MUTTON: case COOKED_SALMON: case COOKED_CHICKEN: case BAKED_POTATO: case RABBIT_STEW: return 6;
            case BREAD: case COOKED_COD: case COOKED_RABBIT: case BEETROOT_SOUP: case MUSHROOM_STEW: return 5;
            case APPLE: case CARROT: return 4;
            case ROTTEN_FLESH: case SPIDER_EYE: case POISONOUS_POTATO: case PUFFERFISH: return 1;
            default: return 3;
        }
    }

    private static Collection<AttributeModifier> modifiers(ItemStack it, Attribute attr, EquipmentSlot slot) {
        if (it == null) return java.util.Collections.emptyList();
        ItemMeta m = it.getItemMeta();
        if (m != null && m.hasAttributeModifiers()) {
            Collection<AttributeModifier> c = m.getAttributeModifiers(attr);
            return c == null ? java.util.Collections.<AttributeModifier>emptyList() : c;
        }
        try {
            Collection<AttributeModifier> c = it.getType().getDefaultAttributeModifiers(slot).get(attr);
            return c == null ? java.util.Collections.<AttributeModifier>emptyList() : c;
        } catch (Throwable t) {
            return java.util.Collections.emptyList();
        }
    }
}
