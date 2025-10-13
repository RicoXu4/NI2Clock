package com.example.clockapp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import android.animation.ValueAnimator;
import android.view.ViewTreeObserver;
import android.util.Log;

public class MainActivity extends Activity {

    private TextView clockTextView;
    private TextView dateTextView;
    private RelativeLayout clockLayout;
    private LinearLayout contentLayout;
    private LinearLayout mainLayout;
    private boolean isContentVisible = false;
    private Handler clockHandler = new Handler();
    private Handler moveHandler = new Handler();
    private Random random = new Random();
    private GestureDetector gestureDetector;

    private WeatherService weatherService;
    private NewsService newsService;
    private boolean weatherBound = false;
    private boolean newsBound = false;
    private TextView weatherLocation;
    private TextView weatherTemp;
    private TextView weatherCondition;
    private TextView cpuText;
    private TextView ramText;
    private TextView networkText;
    private Button refreshButton;
    private TextView lastUpdatedTextView;

    private LinearLayout weatherLayout;
    private LinearLayout zabbixLayout;

    private LinearLayout weatherWrapper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainLayout = findViewById(R.id.main_layout);
        clockLayout = findViewById(R.id.clock_layout);
        clockLayout.setVisibility(View.VISIBLE);
        clockTextView = findViewById(R.id.clock_text);
        Typeface customFont = Typeface.createFromAsset(getAssets(), "fonts/digital-7.ttf");
        clockTextView.setTypeface(customFont);
        dateTextView = findViewById(R.id.date_text);
        //contentLayout = findViewById(R.id.content_layout);
        weatherLocation = findViewById(R.id.weather_location);
        weatherTemp = findViewById(R.id.weather_temp);
        weatherCondition = findViewById(R.id.weather_condition);
        cpuText = findViewById(R.id.cpu_text);
        ramText = findViewById(R.id.ram_text);
        networkText = findViewById(R.id.network_text);
        refreshButton = findViewById(R.id.refresh_button);
        lastUpdatedTextView = findViewById(R.id.last_updated);
        weatherLayout = findViewById(R.id.weather_layout);
        zabbixLayout = findViewById(R.id.zabbix_layout);
        weatherLayout.setVisibility(View.GONE);
        zabbixLayout.setVisibility(View.GONE);
        weatherWrapper = findViewById(R.id.weather_panel_wrapper);

        setupGestureDetector();
        startClock();
        startClockMovement();
        initializeWeatherAndNews();

        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (weatherBound && weatherService != null) {
                    weatherService.forceRefresh();
                }
            }
        });
    }
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (gestureDetector != null && gestureDetector.onTouchEvent(ev)) {
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    private void setupGestureDetector() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_THRESHOLD = 100;
            private static final int SWIPE_VELOCITY_THRESHOLD = 100;

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                float diffX = e2.getX() - e1.getX();
                float diffY = e2.getY() - e1.getY();

                if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            // Swipe Right: show the Zabbix metrics panel
                            showZabbixLayout();
                        } else {
                            // Swipe Left: return to the weather panel
                            showWeatherLayout();
                        }
                        return true;
                    }
                }
                return false;
            }
        });

        mainLayout.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return gestureDetector.onTouchEvent(event); // Ensuring event is passed to the gesture detector
            }
        });
    }

    private void showWeatherLayout() {
        weatherWrapper.setVisibility(View.VISIBLE);
        weatherLayout.setVisibility(View.VISIBLE); // ensure inner layout is shown
        zabbixLayout.setVisibility(View.GONE);
        lastUpdatedTextView.setVisibility(View.VISIBLE);
    }

    private void showZabbixLayout() {
        weatherWrapper.setVisibility(View.GONE);
        weatherLayout.setVisibility(View.GONE); // hide inner layout
        zabbixLayout.setVisibility(View.VISIBLE);
        lastUpdatedTextView.setVisibility(View.GONE);
    }

    private void startClock() {
        clockHandler.post(new Runnable() {
            @Override
            public void run() {
                updateTime();
                clockHandler.postDelayed(this, 1000);
            }
        });
    }

    private void updateTime() {
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault());
        Date current = new Date();

        clockTextView.setText(timeFormat.format(current));
        dateTextView.setText(dateFormat.format(current));
    }

    private void startClockMovement() {
        // Move clock initially after a short delay
        moveHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Log that movement is happening
                System.out.println("DEBUG: Initial clock movement triggered");
                forceClockMovement();

                // Schedule regular movements every 30 seconds (for testing)
                moveHandler.postDelayed(this, 30000); // 30 seconds for testing
            }
        }, 3000); // Initial 3-second delay
    }

    private void forceClockMovement() {
        try {
            // Get available area
            int parentWidth = mainLayout.getWidth();
            int parentHeight = mainLayout.getHeight();

            if (parentWidth <= 0) parentWidth = 800; // fallback
            if (parentHeight <= 0) parentHeight = 480; // fallback

            // Calculate safe area to keep clock fully visible
            int safeWidth = parentWidth - clockLayout.getWidth();
            int safeHeight = parentHeight - clockLayout.getHeight();

            // Ensure we have enough space, so it doesn't end up too close to the edge
            safeWidth = Math.max(safeWidth, 400);  // Increase safe margin to allow larger movement
            safeHeight = Math.max(safeHeight, 300); // Larger margin vertically

            // Generate random position, ensuring the clock doesn't overlap with the edges
            final float newX = random.nextInt(safeWidth - 100) + 50;  // 50px margin from edges horizontally
            final float newY = random.nextInt(safeHeight - 100) + 50; // 50px margin from edges vertically

            System.out.println("DEBUG: Moving clock to X:" + newX + ", Y:" + newY);

            // Direct positioning - bypass layout params entirely
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // Fade out the clock before moving
                    clockLayout.animate()
                        .alpha(0f)
                        .setDuration(1500) // 1 second fade-out
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                // Set new position
                                clockLayout.setX(newX);
                                clockLayout.setY(newY);

                                // Delay the fade-in by 300ms
                                clockLayout.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        // Fade in the clock after moving
                                        clockLayout.animate()
                                            .alpha(1f)
                                            .setDuration(1500) // 1 second fade-in
                                            .start();
                                    }
                                }, 300); // 300ms delay
                            }
                        })
                        .start();
                }
            });
        } catch (Exception e) {
            System.out.println("DEBUG: Movement error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    private void initializeWeatherAndNews() {
        // Initialize weather UI components
        weatherLocation = findViewById(R.id.weather_location);
        weatherTemp = findViewById(R.id.weather_temp);
        weatherCondition = findViewById(R.id.weather_condition);

        // Bind to weather service
        Intent weatherIntent = new Intent(this, WeatherService.class);
        bindService(weatherIntent, weatherConnection, Context.BIND_AUTO_CREATE);

        // Bind to news service
        Intent newsIntent = new Intent(this, NewsService.class);
        bindService(newsIntent, newsConnection, Context.BIND_AUTO_CREATE);
    }
    private ServiceConnection weatherConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            WeatherService.WeatherBinder binder = (WeatherService.WeatherBinder) service;
            weatherService = binder.getService();
            weatherService.setWeatherListener(weatherListener);
            weatherBound = true;

            // Request initial weather update
            weatherService.requestWeatherUpdate();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            weatherBound = false;
        }
    };

    private ServiceConnection newsConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            NewsService.NewsBinder binder = (NewsService.NewsBinder) service;
            newsService = binder.getService();
            newsService.setNewsListener(newsListener);
            newsBound = true;

            // Request initial news update
            newsService.requestNewsUpdate();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            newsBound = false;
        }
    };

    private WeatherService.WeatherListener weatherListener = new WeatherService.WeatherListener() {
        @Override
        public void onWeatherDataReceived(final String location, final String temperature, final String condition) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    weatherLocation.setText(location);
                    weatherTemp.setText(temperature);
                    weatherCondition.setText(condition);
                    lastUpdatedTextView.setText("Last updated: " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
                }
            });
        }

        @Override
        public void onWeatherError(final String error) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    weatherLocation.setText("Weather Unavailable");
                    weatherTemp.setText("--°");
                    weatherCondition.setText(error);
                }
            });
        }
    };

    private NewsService.NewsListener newsListener = new NewsService.NewsListener() {
        @Override
        public void onZabbixDataReceived(final float cpu, final float ram, final float net) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    cpuText.setText("CPU: " + cpu + "%");
                    ramText.setText("RAM: " + ram + "%");
                    networkText.setText("Network: " + net + " Mbps");
                }
            });
        }

        @Override
        public void onNewsError(final String error) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    cpuText.setText("CPU: --");
                    ramText.setText("RAM: --");
                    networkText.setText("Network: --");
                }
            });
        }
    };

    private void showContent() {
        contentLayout.setVisibility(View.VISIBLE);
        ObjectAnimator animator = ObjectAnimator.ofFloat(contentLayout, "translationY", contentLayout.getHeight(), 0);
        animator.setDuration(300);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.start();
        isContentVisible = true;
    }

    private void hideContent() {
        ObjectAnimator animator = ObjectAnimator.ofFloat(contentLayout, "translationY", 0, contentLayout.getHeight());
        animator.setDuration(300);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                contentLayout.setVisibility(View.GONE);
            }
        });
        animator.start();
        isContentVisible = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clockHandler.removeCallbacksAndMessages(null);
        moveHandler.removeCallbacksAndMessages(null);
        if (weatherBound) {
            unbindService(weatherConnection);
            weatherBound = false;
        }

        if (newsBound) {
            unbindService(newsConnection);
            newsBound = false;
        }
    }
}