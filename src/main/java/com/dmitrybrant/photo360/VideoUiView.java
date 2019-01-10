/*
 * Copyright 2019 Dmitry Brant.
 *
 * Based loosely on the Google VR SDK sample apps.
 * Copyright 2017 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dmitrybrant.photo360;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.SurfaceTexture.OnFrameAvailableListener;
import android.media.MediaPlayer;
import android.support.annotation.AnyThread;
import android.support.annotation.MainThread;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.dmitrybrant.photo360.rendering.CanvasQuad;

/**
 * Contains a UI that can be part of a standard 2D Android Activity or a VR Activity.
 *
 * <p>For 2D Activities, this View behaves like any other Android View. It receives events from the
 * media player, updates the UI, and forwards user input to the appropriate component. In VR
 * Activities, this View uses standard Android APIs to render its child Views to a texture that is
 * displayed in VR. It also receives events from the Daydream Controller and forwards them to its
 * child views.
 */
public class VideoUiView extends LinearLayout {
    // These UI elements are only useful when the app is displaying a video.
    private SeekBar seekBar;
    private TextView statusText;
    private final UiUpdater uiUpdater = new UiUpdater();

    // Since MediaPlayer lacks synchronization for internal events, it should only be accessed on the
    // main thread.
    @Nullable
    private MediaPlayer mediaPlayer;
    // The canvasQuad is only not null when this View is in a VR Activity. It provides the backing
    // canvas that standard Android child Views render to.
    @Nullable
    private CanvasQuad canvasQuad;

    /**
     * Creates this View using standard XML inflation.
     */
    public VideoUiView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Creates this view for use in a VR scene.
     *
     * @param context the context used to set this View's theme
     * @param parent  a parent view this view will be attached to such as the Activity's root View
     * @param quad    the floating quad in the VR scene that will render this View
     */
    @MainThread
    public static VideoUiView createForOpenGl(Context context, ViewGroup parent, CanvasQuad quad) {
        // If a custom theme isn't specified, the Context's theme is used. For VR Activities, this is
        // the old Android default theme rather than a modern theme. Override this with a custom theme.
        Context theme = new ContextThemeWrapper(context, R.style.AppTheme);

        VideoUiView view = (VideoUiView) View.inflate(theme, R.layout.video_ui, null);
        view.canvasQuad = quad;
        view.setLayoutParams(CanvasQuad.getLayoutParams());
        view.setVisibility(View.VISIBLE);
        parent.addView(view, 0);

        view.findViewById(R.id.enter_exit_vr).setContentDescription(
                view.getResources().getString(R.string.exit_vr_label));

        return view;
    }

    /**
     * Binds the media player in order to update video position if the Activity is showing a video.
     * This is also used to clear the bound mediaPlayer when the Activity exits to avoid trying to
     * access the mediaPlayer while it is in an invalid state.
     */
    @MainThread
    public void setMediaPlayer(MediaPlayer mediaPlayer) {
        this.mediaPlayer = mediaPlayer;
        postInvalidate();
    }

    /**
     * Ignores 2D touch events when this View is used in a VR Activity.
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (canvasQuad == null) {
            // Not in VR mode so use standard behavior.
            return super.onInterceptTouchEvent(event);
        }

        if (ActivityManager.isRunningInTestHarness()) {
            // If your app uses UI Automator tests, it's useful to have this touch system handle touch
            // events created during tests. This allows you to create UI tests that work while the app
            // is in VR.
            return false;
        }

        // We are in VR mode. Synthetic events generated by SceneRenderer are marked as SOURCE_GAMEPAD
        // events. For this class of events, we will let the Android Touch system handle the event so we
        // return false. Other classes of events were generated by the user accidentally touching the
        // screen where this hidden view is attached.
        if (event.getSource() != InputDevice.SOURCE_GAMEPAD) {
            // Intercept and suppress touchscreen events so child buttons aren't clicked.
            return true;
        } else {
            // Don't intercept SOURCE_GAMEPAD events. onTouchEvent will handle these.
            return false;
        }
    }

    /**
     * Handles standard Android touch events or synthetic VR events.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (canvasQuad != null) {
            // In VR mode so process controller events & ignore touchscreen events.
            if (event.getSource() != InputDevice.SOURCE_GAMEPAD) {
                // Tell the system that we handled the event. This prevents children from seeing the event.
                return true;
            } else {
                // Have the system send the event to child Views and they will handle clicks.
                return super.onTouchEvent(event);
            }
        } else {
            // Not in VR mode so use standard behavior.
            return super.onTouchEvent(event);
        }
    }

    /**
     * Installs the View's event handlers.
     */
    @Override
    public void onFinishInflate() {
        super.onFinishInflate();

        final ImageButton playPauseToggle = findViewById(R.id.play_pause_toggle);
        playPauseToggle.setOnClickListener(
                v -> {
                    if (mediaPlayer == null) {
                        return;
                    }

                    if (mediaPlayer.isPlaying()) {
                        mediaPlayer.pause();
                        playPauseToggle.setBackgroundResource(R.drawable.ic_play_circle_outline_24dp);
                        playPauseToggle.setContentDescription(getResources().getString(R.string.play_label));
                    } else {
                        mediaPlayer.start();
                        playPauseToggle.setBackgroundResource(R.drawable.ic_pause_24dp);
                        playPauseToggle.setContentDescription(getResources().getString(R.string.pause_label));
                    }
                });

        seekBar = findViewById(R.id.seek_bar);
        seekBar.setOnSeekBarChangeListener(new SeekBarListener());

        statusText = findViewById(R.id.status_text);
    }

    public void showProgressBar(boolean show) {
        findViewById(R.id.progress_bar).setVisibility(show ? VISIBLE : GONE);
    }

    public void showControls(boolean show) {
        findViewById(R.id.controls_container).setVisibility(show ? VISIBLE : GONE);
    }

    /**
     * Sets the OnClickListener used to switch Activities.
     */
    @MainThread
    public void setVrIconClickListener(OnClickListener listener) {
        findViewById(R.id.enter_exit_vr).setOnClickListener(listener);
    }

    /**
     * Renders this View and its children to either Android View hierarchy's Canvas or to the VR
     * scene's CanvasQuad.
     *
     * @param androidUiCanvas used in 2D mode to render children to the screen
     */
    @Override
    public void dispatchDraw(Canvas androidUiCanvas) {
        if (canvasQuad == null) {
            // Handle non-VR rendering.
            super.dispatchDraw(androidUiCanvas);
            return;
        }

        // Handle VR rendering.
        Canvas glCanvas = canvasQuad.lockCanvas();
        if (glCanvas == null) {
            // This happens if Android tries to draw this View before GL initialization completes. We need
            // to retry until the draw call happens after GL invalidation.
            postInvalidate();
            return;
        }

        // Clear the canvas first.
        glCanvas.drawColor(Color.BLACK);
        // Have Android render the child views.
        super.dispatchDraw(glCanvas);
        // Commit the changes.
        canvasQuad.unlockCanvasAndPost(glCanvas);
    }

    /**
     * Gets the listener used to update the seek bar's position on each new video frame.
     *
     * @return a listener that can be passed to
     * {@link SurfaceTexture#setOnFrameAvailableListener(OnFrameAvailableListener)}
     */
    public SurfaceTexture.OnFrameAvailableListener getFrameListener() {
        return uiUpdater;
    }

    /**
     * Updates the seek bar and status text.
     */
    private final class UiUpdater implements SurfaceTexture.OnFrameAvailableListener {
        private int videoDurationMs = 0;

        // onFrameAvailable is called on an arbitrary thread, but we can only access mediaPlayer on the
        // main thread.
        private Runnable uiThreadUpdater = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer == null) {
                    return;
                }

                if (videoDurationMs == 0) {
                    videoDurationMs = mediaPlayer.getDuration();
                    seekBar.setMax(videoDurationMs);
                }
                int positionMs = mediaPlayer.getCurrentPosition();
                seekBar.setProgress(positionMs);

                StringBuilder status = new StringBuilder();
                status.append(String.format("%.2f", positionMs / 1000f));
                status.append(" / ");
                status.append(videoDurationMs / 1000);
                statusText.setText(status.toString());

                if (canvasQuad != null) {
                    // When in VR, we will need to manually invalidate this View.
                    invalidate();
                }
            }
        };

        @AnyThread
        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            post(uiThreadUpdater);
        }
    }

    /**
     * Handles the user seeking to a new position in the video.
     */
    private final class SeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (fromUser && mediaPlayer != null) {
                mediaPlayer.seekTo(progress);
            } // else this was from the ActivityEventHandler.onNewFrame()'s seekBar.setProgress update.
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
