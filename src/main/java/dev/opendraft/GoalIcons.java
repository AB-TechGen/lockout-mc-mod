package dev.opendraft;

import dev.opendraft.goal.Goal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Draftout goal pictures (assets/opendraft/textures/goal/&lt;goal id&gt;.png), drawn on the board in
 * place of a plain item. The pictures already have the goal's number drawn on them ("64", "20"...),
 * so the number badge is skipped for goals that have one.
 *
 * <p>Goals with colour variants (wool, concrete...) have no picture, because the Draftout image only
 * shows the first colour. They keep the item icon, which does change with the variant.
 *
 * <p>Drawing a scaled texture needs GuiGraphicsExtractor's blit method, whose name and shape move
 * between Minecraft versions, so it's looked up once by reflection (like WorldMaker does for vanilla's
 * screens). If it can't be found, {@link #has} says no and the board quietly falls back to item icons;
 * the log says why.
 */
final class GoalIcons {
    /** The pictures are square, this many pixels a side. */
    private static final int TEXTURE_SIZE = 128;
    private static final Identifier NONE = Identifier.fromNamespaceAndPath("opendraft", "none");
    /** Goal key -> its texture, or NONE when there isn't one. */
    private static final Map<String, Identifier> TEXTURES = new ConcurrentHashMap<>();

    private static boolean resolved;
    private static volatile Method blit;
    private static Object pipeline;

    private GoalIcons() {
    }

    /** Whether this goal has a picture that can be drawn (so the item icon and number badge aren't needed). */
    static boolean has(Goal goal) {
        return goal != null && texture(goal) != NONE && blitReady();
    }

    /** Draws the goal's picture as a size x size square at x, y. Returns false when it has none (draw the item instead). */
    static boolean draw(GuiGraphicsExtractor g, Goal goal, int x, int y, int size) {
        if (!has(goal)) return false;
        try {
            // (pipeline, texture, x, y, u, v, width, height, regionWidth, regionHeight, textureWidth, textureHeight)
            blit.invoke(g, pipeline, texture(goal), x, y, 0f, 0f, size, size, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            OpenDraftClient.LOG.warn("Drawing goal pictures failed, using item icons instead", e);
            blit = null;
            return false;
        }
    }

    private static Identifier texture(Goal goal) {
        return TEXTURES.computeIfAbsent(goal.key, k -> {
            if (!goal.variant.equals("null")) return NONE;
            Identifier id = Identifier.fromNamespaceAndPath("opendraft", "textures/goal/" + goal.id + ".png");
            return Minecraft.getInstance().getResourceManager().getResource(id).isPresent() ? id : NONE;
        });
    }

    private static synchronized boolean blitReady() {
        if (resolved) return blit != null;
        resolved = true;
        try {
            Method found = null;
            for (Method m : GuiGraphicsExtractor.class.getMethods()) {
                if (!scaledBlit(m)) continue;
                found = m;
                if (m.getName().equals("blit")) break; // prefer the one with the usual name, if several match
            }
            if (found == null) throw new NoSuchMethodException("a 12-parameter blit on GuiGraphicsExtractor");
            pipeline = guiTexturedPipeline(found.getParameterTypes()[0]);
            blit = found;
        } catch (ReflectiveOperationException | RuntimeException e) {
            OpenDraftClient.LOG.warn("Goal pictures can't be drawn in this version, using item icons instead", e);
        }
        return blit != null;
    }

    /** (pipeline, Identifier, int x, int y, float u, float v, int w, int h, int regionW, int regionH, int texW, int texH) */
    private static boolean scaledBlit(Method m) {
        Class<?>[] t = m.getParameterTypes();
        if (t.length != 12 || t[1] != Identifier.class || t[0].isPrimitive()) return false;
        if (t[2] != int.class || t[3] != int.class || t[4] != float.class || t[5] != float.class) return false;
        for (int i = 6; i < 12; i++) if (t[i] != int.class) return false;
        return true;
    }

    /** RenderPipelines.GUI_TEXTURED, wherever this version keeps it. */
    private static Object guiTexturedPipeline(Class<?> pipelineType) throws ReflectiveOperationException {
        for (String cls : new String[]{"net.minecraft.client.renderer.RenderPipelines", "com.mojang.blaze3d.pipeline.RenderPipelines"}) {
            try {
                Field f = Class.forName(cls).getField("GUI_TEXTURED");
                Object p = f.get(null);
                if (pipelineType.isInstance(p)) return p;
            } catch (ClassNotFoundException | NoSuchFieldException ignored) {
                // Try the next place.
            }
        }
        throw new NoSuchFieldException("RenderPipelines.GUI_TEXTURED");
    }
}
