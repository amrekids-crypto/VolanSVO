package dev.volansvo.svo.bots.nms;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Поиск пути для бота ванильным навигатором мобов.
 *
 * У игрока нет навигатора, поэтому держим «зонд»: зомби, который никогда не
 * добавляется в мир. Ставим его в точку бота и просим построить путь. Получаем тот же
 * A* с учётом ступенек, прыжков, воды, дверей, опасных блоков и высоты падения, что и у
 * мобов. Зомби по габаритам почти как игрок (0.6 x 1.95 против 0.6 x 1.8).
 */
public final class PathProbe {

    /** Узел пути: блок, куда встать ногами, и признак двери, которую надо открыть. */
    public static final class Step {
        public final int x, y, z;
        public final boolean door;
        Step(int x, int y, int z, boolean door) { this.x = x; this.y = y; this.z = z; this.door = door; }
    }

    public static final class Result {
        public final List<Step> steps;
        public final boolean reaches;
        Result(List<Step> steps, boolean reaches) { this.steps = steps; this.reaches = reaches; }
    }

    private Zombie probe;
    private ServerLevel level;

    /**
     * @param range    радиус поиска в блоках (как follow_range у моба)
     * @param accuracy на каком расстоянии от цели путь считается дошедшим
     * @return null, если путь не построить вовсе
     */
    public Result find(Player bot, int tx, int ty, int tz, int accuracy, float range) {
        BotPlayer h = BotNms.handle(bot);
        if (h == null) return null;
        ServerLevel lvl = h.serverLevel();
        if (probe == null || level != lvl) {
            probe = new Zombie(EntityType.ZOMBIE, lvl);
            level = lvl;
            PathNavigation nav = probe.getNavigation();
            nav.getNodeEvaluator().setCanOpenDoors(true);
            nav.getNodeEvaluator().setCanPassDoors(true);
            nav.getNodeEvaluator().setCanFloat(true);
            nav.setMaxVisitedNodesMultiplier(2.5f);
        }
        AttributeInstance follow = probe.getAttribute(Attributes.FOLLOW_RANGE);
        if (follow != null && follow.getBaseValue() != range) {
            follow.setBaseValue(range);
            probe.getNavigation().updatePathfinderMaxVisitedNodes();
        }
        probe.setPos(h.getX(), h.getY(), h.getZ());
        // Навигатор строит путь только «стоящему» мобу. Бот в воздухе или в воде всё равно
        // ищет путь от своей точки - этого достаточно, следование само дождётся земли.
        probe.setOnGround(true);

        Path path;
        try {
            path = probe.getNavigation().createPath(new BlockPos(tx, ty, tz), accuracy);
        } catch (Throwable t) {
            return null;
        }
        if (path == null || path.getNodeCount() == 0) return null;
        List<Step> steps = new ArrayList<Step>(path.getNodeCount());
        for (int i = 0; i < path.getNodeCount(); i++) {
            Node n = path.getNode(i);
            steps.add(new Step(n.x, n.y, n.z, n.type == PathType.DOOR_WOOD_CLOSED));
        }
        return new Result(steps, path.canReach());
    }
}
