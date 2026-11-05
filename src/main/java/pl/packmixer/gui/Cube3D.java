package pl.packmixer.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;

/** Izometryczny sześcian z trzema teksturami (góra, lewa, prawa ściana), rysowany przez przekształcenie macierzy. */
final class Cube3D {
    private static final float K = 0.8660254f; // cos 30 stopni
    private static final int LOCAL = 16;

    private Cube3D() {}

    /**
     * @param cx   środek poziomy sześcianu
     * @param top  górna krawędź (wierzchołek) sześcianu
     * @param size całkowita wysokość sześcianu w pikselach
     */
    static void draw(DrawContext ctx, TexCache.Thumb topTex, TexCache.Thumb leftTex, TexCache.Thumb rightTex,
                     int cx, int top, int size) {
        float e = size / 2f; // długość krawędzi
        float s = e / LOCAL; // skala lokalnego kwadratu 16x16
        float ox = cx;
        float oy = top;

        // góra: romb. x -> (K, 0.5), y -> (-K, 0.5), początek w górnym wierzchołku
        face(ctx, topTex, K * s, 0.5f * s, -K * s, 0.5f * s, ox, oy, 0);
        // lewa ściana: x -> (K, 0.5), y -> (0, 1), początek w lewym wierzchołku góry
        face(ctx, leftTex, K * s, 0.5f * s, 0, s, ox - K * e, oy + 0.5f * e, 0x40000000);
        // prawa ściana: x -> (K, -0.5), y -> (0, 1), początek w dolnym wierzchołku góry
        face(ctx, rightTex, K * s, -0.5f * s, 0, s, ox, oy + e, 0x70000000);
    }

    private static void face(DrawContext ctx, TexCache.Thumb t, float a, float b, float c, float d, float tx, float ty, int shade) {
        if (t == null) return;
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.multiplyPositionMatrix(new Matrix4f(
                a, b, 0f, 0f,
                c, d, 0f, 0f,
                0f, 0f, 1f, 0f,
                tx, ty, 0f, 1f));
        int regionH = Math.min(t.h(), t.w()); // animacje: pierwsza klatka
        ctx.drawTexture(RenderLayer::getGuiTextured, t.id(), 0, 0, 0f, 0f, LOCAL, LOCAL, t.w(), regionH, t.w(), t.h());
        if (shade != 0) ctx.fill(0, 0, LOCAL, LOCAL, shade);
        m.pop();
    }
}
