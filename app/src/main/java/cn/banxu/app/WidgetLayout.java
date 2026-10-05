package cn.banxu.app;

/** Fit content to launcher space without shrinking the user's chosen text size. */
final class WidgetLayout {
    enum Mode { MINI, COMPACT, LIST }
    final Mode mode;
    final int rows;

    private WidgetLayout(Mode mode, int rows) { this.mode = mode; this.rows = rows; }

    static WidgetLayout choose(int widthDp, int heightDp, float fontScale) {
        float scale = Float.isFinite(fontScale) ? Math.max(1f, fontScale) : 1f;
        float extra = scale - 1f;
        if (heightDp < 112 + Math.ceil(48 * extra)) return new WidgetLayout(Mode.MINI, 1);
        if (widthDp >= 250 + Math.ceil(40 * extra) && heightDp >= 160 + Math.ceil(100 * extra))
            return new WidgetLayout(Mode.LIST, 0);
        return new WidgetLayout(Mode.COMPACT, heightDp >= 148 + Math.ceil(72 * extra) ? 2 : 1);
    }
}
