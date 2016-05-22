/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.LocalBroadcastManager;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class Sundial extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private Bitmap mWeatherIconBitmap;
    private Bitmap mBackgroundBitmap;

    String MY_PREFS_NAME = "sundial_prefs";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mWeatherMessPaint;
        TextPaint tp;

        boolean mAmbient;

        Time mTime;

        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(Sundial.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = Sundial.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.digital_background));

            mBackgroundBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.bckg);

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mWeatherMessPaint = new Paint();
            mWeatherMessPaint = createTextPaint(resources.getColor(R.color.digital_text));

            tp = new TextPaint();
            tp.setColor(getResources().getColor(R.color.white));

            //mWeatherMessPaint = new TextPaint(resources.getColor(R.color.digital_text));

            mTime = new Time();

            // Register the local broadcast receiver
            IntentFilter messageFilter = new IntentFilter(Intent.ACTION_SEND);
            MessageReceiver messageReceiver = new MessageReceiver();
            LocalBroadcastManager.getInstance(Sundial.this).registerReceiver(messageReceiver, messageFilter);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            Sundial.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            Sundial.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = Sundial.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            float weatherMesTextSize = resources.getDimension(R.dimen.digital_weather_text_size);

            Typeface mTypeface = Typeface.createFromAsset(getBaseContext().getAssets(), "fonts/daniel.ttf");
            mTextPaint.setTextSize(textSize);
            mTextPaint.setTypeface(mTypeface);
            mWeatherMessPaint.setTextSize(weatherMesTextSize);
            mWeatherMessPaint.setTypeface(mTypeface);

            tp.setTextSize(weatherMesTextSize);
            tp.setTypeface(mTypeface);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    mWeatherMessPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            //canvas.drawRect(text, 0, 0, mWeatherMessPaint);
            // SET THE FIRST ROW - CURRENT TIME
            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();

            String text = String.format("%02d:%02d", mTime.hour, mTime.minute);

            if (mAmbient) {
                // if in ambient mode show just a minimal set of information
                canvas.drawColor(Color.BLACK);

                Rect bounds1 = new Rect();
                mTextPaint.getTextBounds(text, 0, text.length(), bounds1);

                float xPos = (canvas.getWidth() / 2)-bounds1.width()/2;
                float yPos = mYOffset;
                canvas.drawText(text, xPos, yPos, mTextPaint);

                // DRAW THE SECOND ROW WHICH IS THE WEATHER INFO
                //get the recorded time info
                SharedPreferences prefs = getSharedPreferences(MY_PREFS_NAME, MODE_PRIVATE);
                String hightemp = prefs.getString("high", null);
                String lowtemp = prefs.getString("low", null);
                if(hightemp != null) {
                    String weatherText = "Low: "+lowtemp + "° High: " + hightemp + "°";
                    float xPosLow = (canvas.getWidth() / 2)-bounds1.width()/2;
                    canvas.drawText(weatherText, xPosLow-5, mYOffset + bounds1.height()/2 + 5, mWeatherMessPaint);
                }
            } else {
                // Draw the background.
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
                canvas.drawBitmap(mBackgroundBitmap, 0, 0, mBackgroundPaint);

                // draw the time always centralised in the middle
                Rect bounds1 = new Rect();
                mTextPaint.getTextBounds(text, 0, text.length(), bounds1);

                float xPos = (canvas.getWidth() / 2)-bounds1.width()/2;
                float yPos = mYOffset;
                canvas.drawText(text, xPos, yPos, mTextPaint);



                // DRAW THE SECOND ROW WHICH IS THE WEATHER INFO
                Rect bounds2 = new Rect();
                mTextPaint.getTextBounds("A", 0, 1, bounds2);

                //get the recorded time info
                SharedPreferences prefs = getSharedPreferences(MY_PREFS_NAME, MODE_PRIVATE);
                String weatherId = prefs.getString("weatherid", null);
                String hightemp = prefs.getString("high", null);
                String lowtemp = prefs.getString("low", null);

                // check if the local data is already synced or this is the first startup so it needs syncing
                if(weatherId != null)
                {
                    String weatherText = lowtemp+"° "+hightemp+"°";
                    String lowText = lowtemp+"°";
                    String highText = hightemp+"°";

                    Rect boundsLowText = new Rect();
                    mWeatherMessPaint.getTextBounds(lowText, 0, lowText.length(), boundsLowText);

                    // determine the icon out of the weatherId and draw it
                    mWeatherIconBitmap = BitmapFactory.decodeResource(getResources(), getIconResourceForWeatherCondition(Integer.parseInt(weatherId)));


                    // get the starting point for the lowtemp
                    float xPosLow = (canvas.getWidth() / 2)-mWeatherIconBitmap.getWidth()/2-boundsLowText.width();
                    canvas.drawText(lowText, xPosLow-5, mYOffset + bounds2.height()/2 + 5, mWeatherMessPaint);

                    // get the starting point for the weather icon and draw it
                    float xPosIcon = (canvas.getWidth() / 2)-mWeatherIconBitmap.getWidth()/2;
                    canvas.drawBitmap(mWeatherIconBitmap, xPosIcon, mYOffset+ bounds2.height()/2 - mWeatherIconBitmap.getHeight()/2, mBackgroundPaint);

                    // now draw the high temp next to the weather icon
                    float xPosHigh = (canvas.getWidth() / 2)+mWeatherIconBitmap.getWidth()/2;
                    canvas.drawText(highText, xPosHigh+5, mYOffset + bounds2.height()/2 + 5, mWeatherMessPaint);

                    // AND FINALLY DRAW THE THIRD ROW FOR SOME TEXT
                    String weatherMessText = getTextForWeatherCondition(Integer.parseInt(weatherId));
                    //Rect boundsHighText = new Rect();
                    //mWeatherMessPaint.getTextBounds(highText, 0, highText.length(), boundsHighText);

                    Rect boundsHighText = new Rect();
                    mWeatherMessPaint.getTextBounds(highText, 0, highText.length(), boundsHighText);

                    //int staticLayoutWidth = boundsLowText.width()+ mWeatherIconBitmap.getWidth()+boundsHighText.width();
                    StaticLayout mTextLayout = new StaticLayout(weatherMessText, tp, bounds1.width(), Layout.Alignment.ALIGN_CENTER, 1, 1, true);

                    canvas.save();

                    float lowCanvasXPos = (canvas.getWidth() / 2) - bounds1.width()/2;
                    float lowCanvasYPos = mYOffset + bounds1.height() + 10;
                    canvas.translate(lowCanvasXPos, lowCanvasYPos);
                    mTextLayout.draw(canvas);
                    canvas.restore();
                }
                else
                {
                    // Write text in case no data was sent from main sunshine app
                    String weatherMessText = getResources().getString(R.string.no_data);
                    StaticLayout mTextLayout = new StaticLayout(weatherMessText, tp, canvas.getWidth()*6/8, Layout.Alignment.ALIGN_CENTER, 1, 1, true);

                    canvas.save();

                    float lowCanvasXPos = (canvas.getWidth() / 8);
                    float lowCanvasYPos = mYOffset;
                    canvas.translate(lowCanvasXPos, lowCanvasYPos);
                    mTextLayout.draw(canvas);

                    canvas.restore();
                }
            }



        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }

    public static int getIconResourceForWeatherCondition(int weatherId) {
        // Based on weather code data found at:
        // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
        if (weatherId >= 200 && weatherId <= 232) {
            return R.drawable.ic_storm;
        } else if (weatherId >= 300 && weatherId <= 321) {
            return R.drawable.ic_light_rain;
        } else if (weatherId >= 500 && weatherId <= 504) {
            return R.drawable.ic_rain;
        } else if (weatherId == 511) {
            return R.drawable.ic_snow;
        } else if (weatherId >= 520 && weatherId <= 531) {
            return R.drawable.ic_rain;
        } else if (weatherId >= 600 && weatherId <= 622) {
            return R.drawable.ic_snow;
        } else if (weatherId >= 701 && weatherId <= 761) {
            return R.drawable.ic_fog;
        } else if (weatherId == 761 || weatherId == 781) {
            return R.drawable.ic_storm;
        } else if (weatherId == 800) {
            return R.drawable.ic_clear;
        } else if (weatherId == 801) {
            return R.drawable.ic_light_clouds;
        } else if (weatherId >= 802 && weatherId <= 804) {
            return R.drawable.ic_cloudy;
        }
        else return R.drawable.ic_clear;
        //return -1;
    }

    public String getTextForWeatherCondition(int weatherId) {
        if (weatherId >= 200 && weatherId <= 232) {
            return getResources().getString(R.string.w_storm);
        } else if (weatherId >= 300 && weatherId <= 321) {
            return getResources().getString(R.string.w_lightrain);
        } else if (weatherId >= 500 && weatherId <= 504) {
            return getResources().getString(R.string.w_rain);
        } else if (weatherId == 511) {
            return getResources().getString(R.string.w_snow);
        } else if (weatherId >= 520 && weatherId <= 531) {
            return getResources().getString(R.string.w_rain);
        } else if (weatherId >= 600 && weatherId <= 622) {
            return getResources().getString(R.string.w_snow);
        } else if (weatherId >= 701 && weatherId <= 761) {
            return getResources().getString(R.string.w_fog);
        } else if (weatherId == 761 || weatherId == 781) {
            return getResources().getString(R.string.w_storm);
        } else if (weatherId == 800) {
            return getResources().getString(R.string.w_clear);
        } else if (weatherId == 801) {
            return getResources().getString(R.string.w_lightclouds);
        } else if (weatherId >= 802 && weatherId <= 804) {
            return getResources().getString(R.string.w_cloudy);
        }
        else return getResources().getString(R.string.w_clear);
    }

    public class MessageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra("message");
            Log.v("RecTag", "Main watchface received message: " + message);
            String[] mesparts = message.split(";");
            //int iconId = getIconResourceForWeatherCondition(weatherId);

            SharedPreferences.Editor editor = getSharedPreferences(MY_PREFS_NAME, MODE_PRIVATE).edit();
            editor.putString("sundailMessage", message);
            editor.putString("weatherid", mesparts[0]);
            editor.putString("high", mesparts[1]);
            editor.putString("low", mesparts[2]);
            editor.commit();

            //SharedPreferences prefs = getSharedPreferences(MY_PREFS_NAME, MODE_PRIVATE);
            //String restoredText = prefs.getString("sundailMessage", null);

            //Log.v("RecTagFromPrefs", restoredText);
            // Display message in UI
            //mTextView.setText(message);
        }
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<Sundial.Engine> mWeakReference;

        public EngineHandler(Sundial.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            Sundial.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }
}
