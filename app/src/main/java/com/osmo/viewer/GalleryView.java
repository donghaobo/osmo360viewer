package com.osmo.viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Scroller;

import java.util.ArrayList;
import java.util.List;

/**
 * 相册网格：按日期分组，每组一行标题，下面按列紧密排列。
 *
 * 之所以自绘而不是用 GridView/RecyclerView：分组标题要跨整行、单元格之间要
 * 精确的 2px 缝、缩放时还要换分组粒度，用现成控件都别扭。
 */
public class GalleryView extends View {

    /** 可选的每行列数，与 ItemDate 的分组粒度对应。 */
    public static final int[] COLUMN_LEVELS = {2, 4, 8, 16};
    public static final int DEFAULT_COLUMNS = 4;

    /** 图片之间的缝，用户要求 2 像素。 */
    private static final int GAP_PX = 2;
    private static final String TAG = "OsvGallery";
    /** 一次手势里手指开合到 1.6x（或 1/1.6x）才换一档，换完即锁住。 */
    private static final float PINCH_STEP = 1.6f;

    public interface Listener {
        void onOpen(OsvItem item);

        void onLongPress(OsvItem item);

        void onColumnsChanged(int columns);

        /** 绘制时发现还没有缩略图的条目；实现方负责异步加载后调 refresh()。 */
        void onNeedThumb(OsvItem item, int sizePx);
    }

    private static final class Row {
        boolean header;
        String text;
        int first;      // 单元格行：这行第一个 item 的下标
        int count;
        int top;
        int height;
    }

    private final List<OsvItem> items = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private Listener listener;

    private Scroller scroller;
    private GestureDetector gestures;
    private ScaleGestureDetector scale;

    private final TextPaint headerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blank = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    private final RectF drawRect = new RectF();

    private float density;
    private int headerHeight;
    private int headerTopPad;
    private int bottomPad;
    private float headerLeft;

    private int columns = DEFAULT_COLUMNS;
    private int contentHeight;
    private int scrollY;
    private int cellSize;
    private boolean scaleHandled;
    private float scaleAccum = 1f;

    public GalleryView(Context c) {
        super(c);
        init(c);
    }

    public GalleryView(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        init(c);
    }

    /** XML 布局实例化只认 (Context, AttributeSet)，少这个构造会在 inflate 时直接闪退。 */
    public GalleryView(Context c, AttributeSet attrs) {
        super(c, attrs);
        init(c);
    }

    public GalleryView(Context c, AttributeSet attrs, int defStyleAttr) {
        super(c, attrs, defStyleAttr);
        init(c);
    }

    private void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFF0B0B0E);

        scroller = new Scroller(c);
        headerHeight = Math.round(30 * density);
        headerTopPad = Math.round(18 * density);
        bottomPad = Math.round(16 * density);
        headerLeft = 4 * density;

        headerPaint.setColor(0xFFEDEDF2);
        headerPaint.setTextSize(13f * density);
        headerPaint.setFakeBoldText(true);
        blank.setColor(0xFF17171C);

        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                if (!scroller.isFinished()) scroller.forceFinished(true);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                scrollByPx(Math.round(dy));
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                scroller.fling(0, scrollY, 0, Math.round(-vy),
                        0, 0, 0, Math.max(0, contentHeight - getHeight()));
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                OsvItem item = hitTest(e.getX(), e.getY());
                if (item != null) {
                    listener.onOpen(item);
                    return true;
                }
                return false;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                OsvItem item = hitTest(e.getX(), e.getY());
                if (item != null) listener.onLongPress(item);
            }
        });

        scale = new ScaleGestureDetector(c,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        // 每次手势重新累计；一次手势最多换一档
                        scaleAccum = 1f;
                        scaleHandled = false;
                        Diag.i(TAG, "捏合开始");
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        // getScaleFactor() 是「相对上一次回调」的增量，通常是 1.0x±0.0x，
                        // 不能直接和阈值比；必须累计整个手势的总倍数。
                        scaleAccum *= detector.getScaleFactor();
                        if (scaleHandled) return true;
                        if (scaleAccum >= PINCH_STEP) {
                            scaleHandled = true;
                            Diag.i(TAG, "捏合累计 " + scaleAccum + " -> 放大一档");
                            stepColumns(false);   // 手指张开 = 放大 = 更少列 = 更大的图
                        } else if (scaleAccum <= 1f / PINCH_STEP) {
                            scaleHandled = true;
                            Diag.i(TAG, "捏合累计 " + scaleAccum + " -> 缩小一档");
                            stepColumns(true);    // 手指收拢 = 缩小 = 更多列
                        }
                        return true;
                    }
                });
        scale.setQuickScaleEnabled(false);   // 避免和单击/长按抢手势
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    // ------------------------------------------------------------------ 数据

    public void setItems(List<OsvItem> list) {
        items.clear();
        items.addAll(list);
        rebuild(false);
    }

    public int getColumns() {
        return columns;
    }

    public void setColumns(int value) {
        int target = nearestLevel(value);
        if (target == columns) return;
        columns = target;
        rebuild(true);
        if (listener != null) listener.onColumnsChanged(columns);
    }

    public int getMode() {
        return ItemDate.modeForColumns(columns);
    }

    /** 缩放一档。zoomOut = 更密（列数更多）。 */
    public void stepColumns(boolean zoomOut) {
        int idx = 0;
        for (int i = 0; i < COLUMN_LEVELS.length; i++) {
            if (COLUMN_LEVELS[i] == columns) {
                idx = i;
                break;
            }
        }
        int next = Math.max(0, Math.min(COLUMN_LEVELS.length - 1, idx + (zoomOut ? 1 : -1)));
        if (COLUMN_LEVELS[next] == columns) return;
        columns = COLUMN_LEVELS[next];
        rebuild(true);
        if (listener != null) listener.onColumnsChanged(columns);
    }

    private int nearestLevel(int value) {
        int best = COLUMN_LEVELS[0];
        int bestDiff = Integer.MAX_VALUE;
        for (int level : COLUMN_LEVELS) {
            int diff = Math.abs(level - value);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = level;
            }
        }
        return best;
    }

    /** 重新分组排版。换粒度时保留相对滚动位置，画面不会跳到别处。 */
    private void rebuild(boolean keepPositionRatio) {
        int oldMax = Math.max(0, contentHeight - getHeight());
        float ratio = oldMax > 0 ? (float) scrollY / oldMax : 0f;

        rows.clear();
        contentHeight = 0;

        if (!items.isEmpty() && getWidth() > 0) {
            final int mode = getMode();
            cellSize = cellSizeFor(getWidth());

            int y = 0;
            String currentKey = null;
            Row cellRow = null;
            for (int i = 0; i < items.size(); i++) {
                OsvItem it = items.get(i);
                String key = ItemDate.groupKey(it.dateMillis, mode);
                if (!key.equals(currentKey)) {
                    currentKey = key;
                    if (cellRow != null) {
                        cellRow.height = cellSize + GAP_PX;
                        y += cellRow.height;
                        cellRow = null;
                    }
                    Row header = new Row();
                    header.header = true;
                    header.text = ItemDate.headerText(it.dateMillis, mode);
                    header.top = y;
                    header.height = headerTopPad + headerHeight;
                    rows.add(header);
                    y += header.height;
                }
                if (cellRow == null) {
                    cellRow = new Row();
                    cellRow.header = false;
                    cellRow.first = i;
                    cellRow.count = 0;
                    cellRow.top = y;
                    rows.add(cellRow);
                }
                cellRow.count++;
                if (cellRow.count == columns) {
                    cellRow.height = cellSize + GAP_PX;
                    y += cellRow.height;
                    cellRow = null;
                }
            }
            if (cellRow != null) {
                cellRow.height = cellSize + GAP_PX;
                y += cellRow.height;
            }
            contentHeight = y + bottomPad;
        }

        int max = Math.max(0, contentHeight - getHeight());
        scrollY = keepPositionRatio ? Math.round(ratio * max) : Math.min(scrollY, max);
        scrollY = Math.max(0, Math.min(scrollY, max));
        invalidate();
    }

    private int cellSizeFor(int width) {
        int avail = width - GAP_PX * (columns - 1);
        return Math.max(1, avail / columns);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuild(false);
    }

    // ------------------------------------------------------------------ 滚动

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = Math.max(0, Math.min(scroller.getCurrY(),
                    Math.max(0, contentHeight - getHeight())));
            postInvalidateOnAnimation();
        }
    }

    private void scrollByPx(int dy) {
        int max = Math.max(0, contentHeight - getHeight());
        scrollY = Math.max(0, Math.min(scrollY + dy, max));
        invalidate();
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        if (items.isEmpty() || cellSize <= 0) return;
        final int h = getHeight();
        for (Row row : rows) {
            int top = row.top - scrollY;
            if (top + row.height < 0) continue;
            if (top > h) break;

            if (row.header) {
                canvas.drawText(row.text, headerLeft,
                        top + headerTopPad + headerHeight * 0.6f, headerPaint);
                continue;
            }

            for (int i = 0; i < row.count; i++) {
                OsvItem item = items.get(row.first + i);
                int left = i * (cellSize + GAP_PX);
                dstRect.set(left, top, left + cellSize, top + cellSize);

                Bitmap bmp = item.thumb;
                if (bmp != null && !bmp.isRecycled()) {
                    // 居中裁切填满格子；图片自带黑边，不加边框和圆角
                    float scaleF = Math.max((float) cellSize / bmp.getWidth(),
                            (float) cellSize / bmp.getHeight());
                    float dw = bmp.getWidth() * scaleF;
                    float dh = bmp.getHeight() * scaleF;
                    float dx = dstRect.left + (cellSize - dw) / 2f;
                    float dy = dstRect.top + (cellSize - dh) / 2f;
                    srcRect.set(0, 0, bmp.getWidth(), bmp.getHeight());
                    drawRect.set(dx, dy, dx + dw, dy + dh);
                    canvas.save();
                    canvas.clipRect(dstRect);
                    canvas.drawBitmap(bmp, srcRect, drawRect, img);
                    canvas.restore();
                } else {
                    canvas.drawRect(dstRect, blank);
                    if (listener != null) listener.onNeedThumb(item, cellSize);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 触摸

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scale.onTouchEvent(event);
        if (!scale.isInProgress()) gestures.onTouchEvent(event);
        return true;
    }

    private OsvItem hitTest(float x, float y) {
        if (cellSize <= 0) return null;
        float contentY = y + scrollY;
        for (Row row : rows) {
            if (row.header) continue;
            if (contentY < row.top || contentY >= row.top + row.height) continue;
            int col = (int) (x / (cellSize + GAP_PX));
            if (col < 0 || col >= row.count) return null;
            int idx = row.first + col;
            return idx < items.size() ? items.get(idx) : null;
        }
        return null;
    }

    public void refresh() {
        invalidate();
    }
}
