package dev.volansvo.svo.bots.nms;

import dev.volansvo.svo.bots.nav.BlockView;
import dev.volansvo.svo.bots.nav.Cell;
import dev.volansvo.svo.bots.nav.Pos;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.WitherRoseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;

/**
 * Мир для поиска пути. Класс клетки зависит только от состояния блока, поэтому считается
 * один раз на состояние (по форме коллизии) и дальше берётся из таблицы.
 */
public final class NmsBlockView implements BlockView {

    public static final int PICKAXE = 0, AXE = 1, SHOVEL = 2, HOE = 3;

    private static final byte UNKNOWN = 0x7F;
    private static byte[] typeByState;
    /** Твёрдость состояния; меньше нуля - ломать нельзя. NaN - ещё не считали. */
    private static float[] hardByState;
    /** Младшие 3 бита - каким инструментом копается (0 - любым), 8 - без инструмента не добыть. */
    private static byte[] toolByState;
    /** Низ и верх коллизии состояния (доли блока); низ больше верха - коллизии нет; NaN - не считали. */
    private static float[] collLo, collHi;

    /** Скорость инструмента по материалу: дерево, золото, камень, железо, алмаз, незерит. */
    private static final float[] TIER_SPEED = {2f, 12f, 4f, 6f, 8f, 9f};

    private final ServerLevel level;
    private final int minY, maxY;
    private final Long2ByteOpenHashMap cache;
    private final int[] tools;
    private final LongSet noBreak;
    private LevelChunk chunk;
    private int chunkX = Integer.MIN_VALUE, chunkZ;
    /** Двери, которые не открываются (защита региона, плагин): закрытые - стена. Может быть null. */
    public java.util.function.LongPredicate locked;
    /** Границы зоны на момент поиска (за ними блоки не ломают, не ставят и не открывают). */
    private double zMinX, zMaxX, zMinZ, zMaxZ;

    /**
     * @param cached  запоминать клетки (для одного поиска; для слежения за живым миром - нет)
     * @param tools   лучший материал по видам инструмента (индексы PICKAXE..HOE), -1 - нет
     * @param noBreak блоки, которые сломать не вышло
     */
    public NmsBlockView(World world, boolean cached, int[] tools, LongSet noBreak) {
        this.level = ((CraftWorld) world).getHandle();
        this.minY = level.getMinY();
        this.maxY = level.getMaxY();
        this.cache = cached ? new Long2ByteOpenHashMap(2048) : null;
        if (cache != null) cache.defaultReturnValue(UNKNOWN);
        this.tools = tools;
        this.noBreak = noBreak;
        snapZone();
        if (typeByState == null) {
            int n = Block.BLOCK_STATE_REGISTRY.size();
            byte[] types = new byte[n];
            java.util.Arrays.fill(types, UNKNOWN);
            float[] hard = new float[n];
            java.util.Arrays.fill(hard, Float.NaN);
            toolByState = new byte[n];
            hardByState = hard;
            float[] lo = new float[n], hi = new float[n];
            java.util.Arrays.fill(lo, Float.NaN);
            collHi = hi;
            collLo = lo;
            typeByState = types;
        }
    }

    public int minY() { return minY; }

    public boolean sameWorld(World world) {
        return ((CraftWorld) world).getHandle() == level;
    }

    /** Забыть чанк, запомненный с прошлого тика. */
    public void newTick() {
        chunk = null;
        chunkX = Integer.MIN_VALUE;
        snapZone();
    }

    private void snapZone() {
        net.minecraft.world.level.border.WorldBorder wb = level.getWorldBorder();
        zMinX = wb.getMinX(); zMaxX = wb.getMaxX(); zMinZ = wb.getMinZ(); zMaxZ = wb.getMaxZ();
    }

    /** Блок в зоне. За её границей, как в ванилле, блоки не ломают, не ставят и не открывают. */
    public boolean inZone(int x, int z) {
        return x >= zMinX && x < zMaxX && z >= zMinZ && z < zMaxZ;
    }

    @Override
    public boolean canPlace(int x, int y, int z) {
        return inZone(x, z);
    }

    /**
     * Коллизия блока (x,y,z) задевает высоты от y0 до y1 (мировые). Незагруженное - стена.
     * Для просчёта полёта: ширину блока не уточняем, по высоте - точно (плиты, заборы).
     */
    public boolean collides(int x, int y, int z, double y0, double y1) {
        if (y < minY || y > maxY) return false;
        BlockState s = state(x, y, z);
        if (s == null) return true;
        int id = Block.getId(s);
        float lo, hi;
        if (id >= 0 && id < collLo.length && !Float.isNaN(collLo[id])) { lo = collLo[id]; hi = collHi[id]; }
        else {
            VoxelShape sh = s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (sh.isEmpty()) { lo = 1f; hi = 0f; }
            else { lo = (float) sh.min(Direction.Axis.Y); hi = (float) sh.max(Direction.Axis.Y); }
            if (id >= 0 && id < collLo.length) { collHi[id] = hi; collLo[id] = lo; }
        }
        return lo <= hi && y0 < y + hi && y1 > y + lo;
    }

    @Override
    public byte type(int x, int y, int z) {
        if (cache == null) return read(x, y, z);
        long key = Pos.pack(x, y, z);
        byte t = cache.get(key);
        if (t != UNKNOWN) return t;
        t = read(x, y, z);
        cache.put(key, t);
        return t;
    }

    private byte read(int x, int y, int z) {
        if (y > maxY) return Cell.AIR;
        BlockState s = state(x, y, z);
        if (s == null) return Cell.OBSTACLE;
        int id = Block.getId(s);
        if (id < 0 || id >= typeByState.length) return classify(s);
        byte t = typeByState[id];
        if (t == UNKNOWN) typeByState[id] = t = classify(s);
        // Закрытую дверь за зоной (или запертую) не открыть: это стена.
        if ((t & Cell.KIND) == Cell.DOOR && (!inZone(x, z) || locked != null && locked.test(Pos.pack(x, y, z)))
                && !(s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
                     && s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN))) return Cell.OBSTACLE;
        return t;
    }

    /** Состояние блока или null, если чанк не загружен или y ниже мира. */
    private BlockState state(int x, int y, int z) {
        if (y < minY || y > maxY) return null;
        int cx = x >> 4, cz = z >> 4;
        if (cx != chunkX || cz != chunkZ) {
            chunk = level.getChunkIfLoaded(cx, cz);
            chunkX = cx;
            chunkZ = cz;
        }
        if (chunk == null) return null;
        LevelChunkSection sec = chunk.getSections()[chunk.getSectionIndex(y)];
        return sec.getBlockState(x & 15, y & 15, z & 15);
    }

    private static byte classify(BlockState s) {
        if (s.isAir()) return Cell.AIR;
        Block b = s.getBlock();
        FluidState fluid = s.getFluidState();
        if (fluid.getType().isSame(Fluids.LAVA)) return Cell.DANGER;
        if (b instanceof BaseFireBlock || b instanceof MagmaBlock || b instanceof CactusBlock || b instanceof CampfireBlock
                || b instanceof SweetBerryBushBlock || b instanceof WitherRoseBlock || b instanceof PowderSnowBlock
                || b instanceof NetherPortalBlock || b instanceof EndPortalBlock) return Cell.DANGER;
        if (b instanceof WebBlock) return Cell.OBSTACLE;
        if (b instanceof LadderBlock || b instanceof VineBlock || b instanceof ScaffoldingBlock || s.is(BlockTags.CLIMBABLE)) return Cell.CLIMB;
        VoxelShape shape = s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (shape.isEmpty()) return fluid.getType().isSame(Fluids.WATER) ? Cell.WATER : Cell.AIR;
        if (b instanceof DoorBlock) return ((DoorBlock) b).type().canOpenByHand() ? Cell.DOOR : Cell.OBSTACLE;
        if (b instanceof FenceGateBlock) return Cell.DOOR;
        byte falls = b instanceof FallingBlock ? Cell.FALLS : 0;
        double top = shape.max(Direction.Axis.Y);
        if (top > 1.01) return Cell.OBSTACLE;       // забор, стена
        if (top <= 0.2) return Cell.THIN;
        byte surface = surface(b);
        if (top <= 0.6) return (byte) (Cell.HALF | surface);
        boolean wide = shape.min(Direction.Axis.X) <= 0.07 && shape.max(Direction.Axis.X) >= 0.93
            && shape.min(Direction.Axis.Z) <= 0.07 && shape.max(Direction.Axis.Z) >= 0.93;
        // Верхняя плита, люк наверху: снизу полблока пусто, под ними проходят присев.
        if (wide && shape.min(Direction.Axis.Y) >= 0.49) return (byte) (Cell.LOW | surface);
        if (wide) return (byte) (Cell.SOLID | falls | surface);
        // Решётка, стеклянная панель, наковальня: пройти нельзя, но сверху стоят (пол-сетка).
        return top >= 0.9 ? (byte) (Cell.OBSTACLE | Cell.TOP) : Cell.OBSTACLE;
    }

    private static final String[] ROADS = {"PATH", "PLANKS", "BRICK", "COBBLE", "SMOOTH_STONE", "POLISHED", "TERRACOTTA"};

    /** Удобство поверхности: листва - неудобно, мощёное и дощатое - дорога. */
    private static byte surface(Block b) {
        if (b instanceof LeavesBlock) return Cell.ROUGH;
        String n = CraftMagicNumbers.getMaterial(b).name();
        if (n.contains("CONCRETE") && !n.contains("POWDER")) return Cell.ROAD;
        for (String r : ROADS) if (n.contains(r)) return Cell.ROAD;
        return 0;
    }

    @Override
    public int breakTicks(int x, int y, int z) {
        if (noBreak != null && noBreak.contains(Pos.pack(x, y, z))) return -1;
        if (!inZone(x, z)) return -1;
        BlockState s = state(x, y, z);
        if (s == null) return -1;
        int id = Block.getId(s);
        if (id < 0 || id >= hardByState.length) return -1;
        float h = hardByState[id];
        if (Float.isNaN(h)) {
            hardByState[id] = h = hardness(s);
            toolByState[id] = toolInfo(s);
        }
        if (h < 0) return -1;
        int info = toolByState[id];
        int kind = (info & 7) - 1;
        boolean needTool = (info & 8) != 0;
        double ticks;
        if (kind >= 0 && tools != null && tools[kind] >= 0) {
            ticks = h * 30.0 / TIER_SPEED[tools[kind]];
        } else {
            // Те же пределы, что у рук бота (Builder.canDig): дольше 1.6 сек голыми руками не копаем.
            double seconds = h * (needTool ? 5.0 : 1.5);
            if (seconds > 1.6) return -1;
            ticks = seconds * 20;
        }
        return Math.max(1, (int) Math.ceil(ticks));
    }

    private static float hardness(BlockState s) {
        if (s.isAir() || !s.getFluidState().isEmpty() && s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) return -1f;
        float h = s.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (h < 0 || h > 30 || s.hasBlockEntity()) return -1f;
        String n = CraftMagicNumbers.getMaterial(s.getBlock()).name();
        if (n.contains("DOOR") || n.contains("BED") || n.contains("SIGN") || n.contains("COMMAND") || n.contains("STRUCTURE")
                || n.contains("SPAWNER") || n.contains("PORTAL") || n.equals("BARRIER")) return -1f;
        return h;
    }

    private static byte toolInfo(BlockState s) {
        int kind = 0;
        if (s.is(BlockTags.MINEABLE_WITH_PICKAXE)) kind = PICKAXE + 1;
        else if (s.is(BlockTags.MINEABLE_WITH_AXE)) kind = AXE + 1;
        else if (s.is(BlockTags.MINEABLE_WITH_SHOVEL)) kind = SHOVEL + 1;
        else if (s.is(BlockTags.MINEABLE_WITH_HOE)) kind = HOE + 1;
        return (byte) (kind | (s.requiresCorrectToolForDrops() ? 8 : 0));
    }
}
