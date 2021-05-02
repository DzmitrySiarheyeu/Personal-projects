package com.RingMake.Ringdroid;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import com.RingMake.Ringdroid.soundfile.SoundFile;

import java.nio.ShortBuffer;

public class SamplePlayer {
    public interface OnCompletionListener {
        public void onCompletion();
    }

    private ShortBuffer mSamples;
    private int mSampleRate;
    private int mChannels;
    private int mNumSamples;
    private AudioTrack mAudioTrack;
    private short[] mBuffer;
    private int mPlaybackStart;
    private Thread mPlayThread;
    private boolean mKeepPlaying;
    private OnCompletionListener mListener;

    public SamplePlayer(ShortBuffer samples, int sampleRate, int channels, int numSamples) {
        mSamples = samples;
        mSampleRate = sampleRate;
        mChannels = channels;
        mNumSamples = numSamples;
        mPlaybackStart = 0;

        int bufferSize = AudioTrack.getMinBufferSize(
                mSampleRate,
                mChannels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (bufferSize < mChannels * mSampleRate * 2) {
            bufferSize = mChannels * mSampleRate * 2;
        }
        mBuffer = new short[bufferSize / 2];
        mAudioTrack = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                mSampleRate,
                mChannels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                mBuffer.length * 2,
                AudioTrack.MODE_STREAM);
        mAudioTrack.setNotificationMarkerPosition(mNumSamples - 1);
        mAudioTrack.setPlaybackPositionUpdateListener(
                new AudioTrack.OnPlaybackPositionUpdateListener() {
                    @Override
                    public void onPeriodicNotification(AudioTrack track) {
                    }

                    @Override
                    public void onMarkerReached(AudioTrack track) {
                        stop();
                        if (mListener != null) {
                            mListener.onCompletion();
                        }
                    }
                });
        mPlayThread = null;
        mKeepPlaying = true;
        mListener = null;
    }

    public SamplePlayer(SoundFile sf) {
        this(sf.getSamples(), sf.getSampleRate(), sf.getChannels(), sf.getNumSamples());
    }

    public void setOnCompletionListener(OnCompletionListener listener) {
        mListener = listener;
    }

    public boolean isPlaying() {
        return mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
    }

    public boolean isPaused() {
        return mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PAUSED;
    }

    public void start() {
        if (isPlaying()) {
            return;
        }
        mKeepPlaying = true;
        mAudioTrack.flush();
        mAudioTrack.play();
        mPlayThread = new Thread() {
            public void run() {
                int position = mPlaybackStart * mChannels;
                mSamples.position(position);
                int limit = mNumSamples * mChannels;
                while (mSamples.position() < limit && mKeepPlaying) {
                    int numSamplesLeft = limit - mSamples.position();
                    if (numSamplesLeft >= mBuffer.length) {
                        mSamples.get(mBuffer);
                    } else {
                        for (int i = numSamplesLeft; i < mBuffer.length; i++) {
                            mBuffer[i] = 0;
                        }
                        mSamples.get(mBuffer, 0, numSamplesLeft);
                    }
                    mAudioTrack.write(mBuffer, 0, mBuffer.length);
                }
            }
        };
        mPlayThread.start();
    }

    public void pause() {
        if (isPlaying()) {
            mAudioTrack.pause();
        }
    }

    public void stop() {
        if (isPlaying() || isPaused()) {
            mKeepPlaying = false;
            mAudioTrack.pause();
            mAudioTrack.stop();
            if (mPlayThread != null) {
                try {
                    mPlayThread.join();
                } catch (InterruptedException e) {
                }
                mPlayThread = null;
            }
            mAudioTrack.flush();
        }
    }

    public void release() {
        stop();
        mAudioTrack.release();
    }

    public void seekTo(int msec) {
        boolean wasPlaying = isPlaying();
        stop();
        mPlaybackStart = (int) (msec * (mSampleRate / 1000.0));
        if (mPlaybackStart > mNumSamples) {
            mPlaybackStart = mNumSamples;
        }
        mAudioTrack.setNotificationMarkerPosition(mNumSamples - 1 - mPlaybackStart);
        if (wasPlaying) {
            start();
        }
    }

    public int getCurrentPosition() {
        return (int) ((mPlaybackStart + mAudioTrack.getPlaybackHeadPosition()) *
                (1000.0 / mSampleRate));
    }
}
