package cn.banxu.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class WidgetLayoutTest {
    @Test public void shortPlacementsKeepOneItemRegardlessOfWidth() {
        assertEquals(WidgetLayout.Mode.MINI, WidgetLayout.choose(140, 64, 1).mode);
        assertEquals(WidgetLayout.Mode.MINI, WidgetLayout.choose(400, 111, 1).mode);
        assertEquals(1, WidgetLayout.choose(110, 56, 1).rows);
        assertEquals(WidgetLayout.Mode.COMPACT, WidgetLayout.choose(160, 112, 1).mode);
    }
    @Test public void compactCardsOnlyAddASecondRowWhenItFits() {
        assertEquals(1, WidgetLayout.choose(160, 147, 1).rows);
        assertEquals(2, WidgetLayout.choose(160, 148, 1).rows);
        assertEquals(2, WidgetLayout.choose(160, 500, 1).rows);
    }
    @Test public void wideTallCardsUseTheScrollableList() {
        assertEquals(WidgetLayout.Mode.COMPACT, WidgetLayout.choose(249, 160, 1).mode);
        assertEquals(WidgetLayout.Mode.COMPACT, WidgetLayout.choose(250, 159, 1).mode);
        assertEquals(WidgetLayout.Mode.LIST, WidgetLayout.choose(250, 160, 1).mode);
        assertEquals(WidgetLayout.Mode.LIST, WidgetLayout.choose(300, 160, 1).mode);
    }
    @Test public void largerTextYieldsSpaceInsteadOfShrinkingTextOrOverflowingRows() {
        assertEquals(WidgetLayout.Mode.COMPACT, WidgetLayout.choose(160, 150, 1.4f).mode);
        assertEquals(1, WidgetLayout.choose(160, 150, 1.4f).rows);
        assertEquals(WidgetLayout.Mode.COMPACT, WidgetLayout.choose(300, 160, 1.4f).mode);
        assertEquals(WidgetLayout.Mode.LIST, WidgetLayout.choose(320, 240, 1.4f).mode);
        assertEquals(WidgetLayout.Mode.MINI, WidgetLayout.choose(140, 64, 2).mode);
    }
}
