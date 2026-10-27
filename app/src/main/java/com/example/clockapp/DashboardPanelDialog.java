package com.example.clockapp;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/** Shared full-width presentation for weather and server monitoring. */
final class DashboardPanelDialog extends BottomSheetDialog {
    private final GestureDetector gestures;
    private boolean longPressHandled;

    DashboardPanelDialog(Context context, boolean weather, Runnable refresh, Runnable openSettings) {
        super(context);
        setContentView(R.layout.dashboard_panel_sheet);
        findViewById(R.id.dashboard_panel_sheet).setId(weather ? R.id.weather_sheet : R.id.zabbix_sheet);
        ((TextView) findViewById(R.id.panel_sheet_title)).setText(weather ? R.string.weather : R.string.zabbix);
        ((TextView) findViewById(R.id.panel_sheet_hint)).setText(weather
                ? R.string.weather_dismiss_hint : R.string.zabbix_dismiss_hint);
        FrameLayout content = findViewById(R.id.panel_sheet_content);
        getLayoutInflater().inflate(weather ? R.layout.weather_layout : R.layout.zabbix_layout, content, true);
        android.view.View refreshButton = findViewById(R.id.panel_sheet_refresh);
        refreshButton.setId(weather ? R.id.refresh_button : R.id.zabbix_refresh_button);
        refreshButton.setOnClickListener(view -> refresh.run());
        TextView status = findViewById(R.id.panel_sheet_status);
        status.setId(weather ? R.id.last_updated : R.id.zabbix_status);
        status.setText(weather ? R.string.never_updated : R.string.loading);
        findViewById(R.id.panel_sheet_close).setOnClickListener(view -> dismiss());
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent event) {
                longPressHandled = true;
                cancelChildTouch(event);
                dismiss();
                openSettings.run();
            }

            @Override
            public boolean onFling(MotionEvent start, MotionEvent end, float velocityX, float velocityY) {
                if (start == null || end == null) return false;
                float deltaX = end.getX() - start.getX();
                float deltaY = end.getY() - start.getY();
                float threshold = 100 * context.getResources().getDisplayMetrics().density;
                if (Math.abs(deltaX) > Math.abs(deltaY) && Math.abs(deltaX) > threshold
                        && Math.abs(velocityX) > threshold && (deltaX > 0) == weather) {
                    dismiss();
                    return true;
                }
                return false;
            }
        });
        setOnShowListener(dialog -> {
            longPressHandled = false;
            getBehavior().setSkipCollapsed(true);
            getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) longPressHandled = false;
        if (gestures.onTouchEvent(event) || longPressHandled) {
            cancelChildTouch(event);
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private void cancelChildTouch(MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
    }
}
