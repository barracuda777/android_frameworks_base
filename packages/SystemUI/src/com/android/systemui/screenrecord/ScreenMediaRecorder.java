/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.screenrecord;

import static android.content.Context.MEDIA_PROJECTION_SERVICE;

import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.INTERNAL;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.MIC;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.MIC_AND_INTERNAL;

import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodecInfo;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.ThumbnailUtils;
import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import com.android.systemui.R;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Recording screen and mic/internal audio
 */
public class ScreenMediaRecorder {
    private static final int TOTAL_NUM_TRACKS = 1;
    private static final int VIDEO_FRAME_RATE = 30;
    private static final int VIDEO_FRAME_RATE_TO_RESOLUTION_RATIO = 6;
    private static final int AUDIO_BIT_RATE = 196000;
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int MAX_DURATION_MS = 60 * 60 * 1000;
    private static final long MAX_FILESIZE_BYTES = 5000000000L;
    private static final String TAG = "ScreenMediaRecorder";


    private File mTempVideoFile;
    private File mTempAudioFile;
    private MediaProjection mMediaProjection;
    private Surface mInputSurface;
    private VirtualDisplay mVirtualDisplay;
    private MediaRecorder mMediaRecorder;
    private int mUser;
    private ScreenRecordingMuxer mMuxer;
    private ScreenInternalAudioRecorder mAudio;
    private ScreenRecordingAudioSource mAudioSource;
    private int mMaxRefreshRate;

    private Context mContext;
    MediaRecorder.OnInfoListener mListener;

    public ScreenMediaRecorder(Context context,
            int user, ScreenRecordingAudioSource audioSource,
            MediaRecorder.OnInfoListener listener) {
        mContext = context;
        mUser = user;
        mListener = listener;
        mAudioSource = audioSource;
        mMaxRefreshRate = mContext.getResources().getInteger(
                R.integer.config_screenRecorderMaxFramerate);
    }

    private void prepare() throws IOException, RemoteException {
        //Setup media projection
        IBinder b = ServiceManager.getService(MEDIA_PROJECTION_SERVICE);
        IMediaProjectionManager mediaService =
                IMediaProjectionManager.Stub.asInterface(b);
        IMediaProjection proj = null;
        proj = mediaService.createProjection(mUser, mContext.getPackageName(),
                    MediaProjectionManager.TYPE_SCREEN_CAPTURE, false);
        IBinder projection = proj.asBinder();
        mMediaProjection = new MediaProjection(mContext,
                IMediaProjection.Stub.asInterface(projection));

        File cacheDir = mContext.getCacheDir();
        cacheDir.mkdirs();
        mTempVideoFile = File.createTempFile("temp", ".mp4", cacheDir);

        // Set up media recorder
        mMediaRecorder = new MediaRecorder();

        // Set up audio source
        if (mAudioSource == MIC) {
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
        }
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);


        // Set up video
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(metrics);
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        int refereshRate = (int) wm.getDefaultDisplay().getRefreshRate();
        if (mMaxRefreshRate != 0 && refereshRate > mMaxRefreshRate) refereshRate = mMaxRefreshRate;
        int vidBitRate = screenHeight * screenWidth * refereshRate / VIDEO_FRAME_RATE
                * VIDEO_FRAME_RATE_TO_RESOLUTION_RATIO;
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setVideoEncodingProfileLevel(
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                MediaCodecInfo.CodecProfileLevel.AVCLevel3);
        mMediaRecorder.setVideoSize(screenWidth, screenHeight);
        mMediaRecorder.setVideoFrameRate(refereshRate);
        mMediaRecorder.setVideoEncodingBitRate(vidBitRate);
        mMediaRecorder.setMaxDuration(MAX_DURATION_MS);
        mMediaRecorder.setMaxFileSize(MAX_FILESIZE_BYTES);

        // Set up audio
        if (mAudioSource == MIC) {
            mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC);
            mMediaRecorder.setAudioChannels(TOTAL_NUM_TRACKS);
            mMediaRecorder.setAudioEncodingBitRate(AUDIO_BIT_RATE);
            mMediaRecorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE);
        }

        mMediaRecorder.setOutputFile(mTempVideoFile);
        mMediaRecorder.prepare();
        // Create surface
        mInputSurface = mMediaRecorder.getSurface();
        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                "Recording Display",
                screenWidth,
                screenHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mInputSurface,
                null,
                null);

        mMediaRecorder.setOnInfoListener(mListener);
        if (mAudioSource == INTERNAL ||
                mAudioSource == MIC_AND_INTERNAL) {
            mTempAudioFile = File.createTempFile("temp", ".aac",
                    mContext.getCacheDir());
            mAudio = new ScreenInternalAudioRecorder(mTempAudioFile.getAbsolutePath(),
                    mMediaProjection, mAudioSource == MIC_AND_INTERNAL);
        }

    }

    /**
    * Start screen recording
    */
    void start() throws IOException, RemoteException, IllegalStateException {
        Log.d(TAG, "start recording");
        prepare();
        mMediaRecorder.start();
        recordInternalAudio();
    }

    /**
     * End screen recording
     */
    void end() {
        mMediaRecorder.stop();
        mMediaProjection.stop();
        mMediaRecorder.release();
        mMediaRecorder = null;
        mMediaProjection = null;
        mInputSurface.release();
        mVirtualDisplay.release();
        stopInternalAudioRecording();

        Log.d(TAG, "end recording");
    }

    private void stopInternalAudioRecording() {
        if (mAudioSource == INTERNAL || mAudioSource == MIC_AND_INTERNAL) {
            mAudio.end();
            mAudio = null;
        }
    }

    private  void recordInternalAudio() throws IllegalStateException {
        if (mAudioSource == INTERNAL || mAudioSource == MIC_AND_INTERNAL) {
            mAudio.start();
        }
    }

    /**
     * Store recorded video
     */
    protected SavedRecording save() throws IOException {
        String fileName = new SimpleDateFormat("'screen-'yyyyMMdd-HHmmss'.mp4'")
                .format(new Date());

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis());
        values.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis());

        ContentResolver resolver = mContext.getContentResolver();
        Uri collectionUri = MediaStore.Video.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri itemUri = resolver.insert(collectionUri, values);

        Log.d(TAG, itemUri.toString());
        if (mAudioSource == MIC_AND_INTERNAL || mAudioSource == INTERNAL) {
            try {
                Log.d(TAG, "muxing recording");
                File file = File.createTempFile("temp", ".mp4",
                        mContext.getCacheDir());
                mMuxer = new ScreenRecordingMuxer(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                        file.getAbsolutePath(),
                        mTempVideoFile.getAbsolutePath(),
                        mTempAudioFile.getAbsolutePath());
                mMuxer.mux();
                mTempVideoFile.delete();
                mTempVideoFile = file;
            } catch (IOException e) {
                Log.e(TAG, "muxing recording " + e.getMessage());
                e.printStackTrace();
            }
        }

        // Add to the mediastore
        OutputStream os = resolver.openOutputStream(itemUri, "w");
        Files.copy(mTempVideoFile.toPath(), os);
        os.close();
        if (mTempAudioFile != null) mTempAudioFile.delete();
        DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
        Size size = new Size(metrics.widthPixels, metrics.heightPixels);
        SavedRecording recording = new SavedRecording(itemUri, mTempVideoFile, size);
        mTempVideoFile.delete();
        return recording;
    }

    /**
    * Object representing the recording
    */
    public class SavedRecording {

        private Uri mUri;
        private Bitmap mThumbnailBitmap;

        protected SavedRecording(Uri uri, File file, Size thumbnailSize) {
            mUri = uri;
            try {
                mThumbnailBitmap = ThumbnailUtils.createVideoThumbnail(
                        file, thumbnailSize, null);
            } catch (IOException e) {
                Log.e(TAG, "Error creating thumbnail", e);
            }
        }

        public Uri getUri() {
            return mUri;
        }

        public @Nullable Bitmap getThumbnail() {
            return mThumbnailBitmap;
        }
    }
}
