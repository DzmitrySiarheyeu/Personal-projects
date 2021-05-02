package com.RingMake.Ringdroid.soundfile;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.support.annotation.RequiresApi;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;

public class SoundFile {
    private ProgressListener mProgressListener = null;
    private File mInputFile = null;

    private String mFileType;
    private int mFileSize;
    private int mAvgBitRate;
    private int mSampleRate;
    private int mChannels;
    private int mNumSamples;
    private ByteBuffer mDecodedBytes;
    private ShortBuffer mDecodedSamples;
    private int mNumFrames;
    private int[] mFrameGains;
    private int[] mFrameLens;
    private int[] mFrameOffsets;

    public interface ProgressListener {
        boolean reportProgress(double fractionComplete);
    }

    public class InvalidInputException extends Exception {
        private static final long serialVersionUID = -2505698991597837165L;
        public InvalidInputException(String message) {
            super(message);
        }
    }

    public static String[] getSupportedExtensions() {
        return new String[] {"mp3", "wav", "3gpp", "3gp", "amr", "aac", "m4a", "ogg"};
    }

    public static boolean isFilenameSupported(String filename) {
        String[] extensions = getSupportedExtensions();
        for (int i=0; i<extensions.length; i++) {
            if (filename.endsWith("." + extensions[i])) {
                return true;
            }
        }
        return false;
    }

    public static SoundFile create(String fileName,
                                   ProgressListener progressListener)
        throws java.io.FileNotFoundException,
               java.io.IOException, InvalidInputException {
        File f = new File(fileName);
        if (!f.exists()) {
            throw new java.io.FileNotFoundException(fileName);
        }
        String name = f.getName().toLowerCase();
        String[] components = name.split("\\.");
        if (components.length < 2) {
            return null;
        }
        if (!Arrays.asList(getSupportedExtensions()).contains(components[components.length - 1])) {
            return null;
        }
        SoundFile soundFile = new SoundFile();
        soundFile.setProgressListener(progressListener);
        soundFile.ReadFile(f);
        return soundFile;
    }

    public static SoundFile record(ProgressListener progressListener) {
        if (progressListener ==  null) {
            return null;
        }
        SoundFile soundFile = new SoundFile();
        soundFile.setProgressListener(progressListener);
        soundFile.RecordAudio();
        return soundFile;
    }

    public String getFiletype() {
        return mFileType;
    }

    public int getFileSizeBytes() {
        return mFileSize;
    }

    public int getAvgBitrateKbps() {
        return mAvgBitRate;
    }

    public int getSampleRate() {
        return mSampleRate;
    }

    public int getChannels() {
        return mChannels;
    }

    public int getNumSamples() {
        return mNumSamples;
    }

    public int getNumFrames() {
        return mNumFrames;
    }

    public int getSamplesPerFrame() {
        return 1024;
    }

    public int[] getFrameGains() {
        return mFrameGains;
    }

    public ShortBuffer getSamples() {
        if (mDecodedSamples != null) {
            return mDecodedSamples.asReadOnlyBuffer();
        } else {
            return null;
        }
    }

    private SoundFile() {
    }

    private void setProgressListener(ProgressListener progressListener) {
        mProgressListener = progressListener;
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
    private void ReadFile(File inputFile)
        throws java.io.FileNotFoundException,
               java.io.IOException, InvalidInputException {
        MediaExtractor extractor = new MediaExtractor();
        MediaFormat format = null;
        int i;

        mInputFile = inputFile;
        String[] components = mInputFile.getPath().split("\\.");
        mFileType = components[components.length - 1];
        mFileSize = (int)mInputFile.length();
        extractor.setDataSource(mInputFile.getPath());
        int numTracks = extractor.getTrackCount();
        for (i=0; i<numTracks; i++) {
            format = extractor.getTrackFormat(i);
            if (format.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                extractor.selectTrack(i);
                break;
            }
        }
        if (i == numTracks) {
            throw new InvalidInputException("No audio track found in " + mInputFile);
        }
        mChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        mSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int expectedNumSamples =
            (int)((format.getLong(MediaFormat.KEY_DURATION) / 1000000.f) * mSampleRate + 0.5f);

        MediaCodec codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        codec.configure(format, null, null, 0);
        codec.start();

        int decodedSamplesSize = 0;
        byte[] decodedSamples = null;
        ByteBuffer[] inputBuffers = codec.getInputBuffers();
        ByteBuffer[] outputBuffers = codec.getOutputBuffers();
        int sample_size;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long presentation_time;
        int tot_size_read = 0;
        boolean done_reading = false;

        mDecodedBytes = ByteBuffer.allocate(1<<20);
        Boolean firstSampleData = true;
        while (true) {
            int inputBufferIndex = codec.dequeueInputBuffer(100);
            if (!done_reading && inputBufferIndex >= 0) {
                sample_size = extractor.readSampleData(inputBuffers[inputBufferIndex], 0);
                if (firstSampleData
                        && format.getString(MediaFormat.KEY_MIME).equals("audio/mp4a-latm")
                        && sample_size == 2) {
                    extractor.advance();
                    tot_size_read += sample_size;
                } else if (sample_size < 0) {

                    codec.queueInputBuffer(
                            inputBufferIndex, 0, 0, -1, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    done_reading = true;
                } else {
                    presentation_time = extractor.getSampleTime();
                    codec.queueInputBuffer(inputBufferIndex, 0, sample_size, presentation_time, 0);
                    extractor.advance();
                    tot_size_read += sample_size;
                    if (mProgressListener != null) {
                        if (!mProgressListener.reportProgress((float)(tot_size_read) / mFileSize)) {
                            extractor.release();
                            extractor = null;
                            codec.stop();
                            codec.release();
                            codec = null;
                            return;
                        }
                    }
                }
                firstSampleData = false;
            }

            int outputBufferIndex = codec.dequeueOutputBuffer(info, 100);
            if (outputBufferIndex >= 0 && info.size > 0) {
                if (decodedSamplesSize < info.size) {
                    decodedSamplesSize = info.size;
                    decodedSamples = new byte[decodedSamplesSize];
                }
                outputBuffers[outputBufferIndex].get(decodedSamples, 0, info.size);
                outputBuffers[outputBufferIndex].clear();
                if (mDecodedBytes.remaining() < info.size) {
                    int position = mDecodedBytes.position();
                    int newSize = (int)((position * (1.0 * mFileSize / tot_size_read)) * 1.2);
                    if (newSize - position < info.size + 5 * (1<<20)) {
                        newSize = position + info.size + 5 * (1<<20);
                    }
                    ByteBuffer newDecodedBytes = null;
                    int retry = 10;
                    while(retry > 0) {
                        try {
                            newDecodedBytes = ByteBuffer.allocate(newSize);
                            break;
                        } catch (OutOfMemoryError oome) {
                            retry--;
                        }
                    }
                    if (retry == 0) {
                        break;
                    }
                    mDecodedBytes.rewind();
                    newDecodedBytes.put(mDecodedBytes);
                    mDecodedBytes = newDecodedBytes;
                    mDecodedBytes.position(position);
                }
                mDecodedBytes.put(decodedSamples, 0, info.size);
                codec.releaseOutputBuffer(outputBufferIndex, false);
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                outputBuffers = codec.getOutputBuffers();
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    || (mDecodedBytes.position() / (2 * mChannels)) >= expectedNumSamples) {
                break;
            }
        }
        mNumSamples = mDecodedBytes.position() / (mChannels * 2);  // One sample = 2 bytes.
        mDecodedBytes.rewind();
        mDecodedBytes.order(ByteOrder.LITTLE_ENDIAN);
        mDecodedSamples = mDecodedBytes.asShortBuffer();
        mAvgBitRate = (int)((mFileSize * 8) * ((float)mSampleRate / mNumSamples) / 1000);

        extractor.release();
        extractor = null;
        codec.stop();
        codec.release();
        codec = null;

        mNumFrames = mNumSamples / getSamplesPerFrame();
        if (mNumSamples % getSamplesPerFrame() != 0){
            mNumFrames++;
        }
        mFrameGains = new int[mNumFrames];
        mFrameLens = new int[mNumFrames];
        mFrameOffsets = new int[mNumFrames];
        int j;
        int gain, value;
        int frameLens = (int)((1000 * mAvgBitRate / 8) *
                ((float)getSamplesPerFrame() / mSampleRate));
        for (i=0; i<mNumFrames; i++){
            gain = -1;
            for(j=0; j<getSamplesPerFrame(); j++) {
                value = 0;
                for (int k=0; k<mChannels; k++) {
                    if (mDecodedSamples.remaining() > 0) {
                        value += java.lang.Math.abs(mDecodedSamples.get());
                    }
                }
                value /= mChannels;
                if (gain < value) {
                    gain = value;
                }
            }
            mFrameGains[i] = (int) Math.sqrt(gain);
            mFrameLens[i] = frameLens;
            mFrameOffsets[i] = (int)(i * (1000 * mAvgBitRate / 8) *
                    ((float)getSamplesPerFrame() / mSampleRate));
        }
        mDecodedSamples.rewind();
    }

    private void RecordAudio() {
        if (mProgressListener ==  null) {
            return;
        }
        mInputFile = null;
        mFileType = "raw";
        mFileSize = 0;
        mSampleRate = 44100;
        mChannels = 1;
        short[] buffer = new short[1024];
        int minBufferSize = AudioRecord.getMinBufferSize(
                mSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        if (minBufferSize < mSampleRate * 2) {
            minBufferSize = mSampleRate * 2;
        }
        AudioRecord audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                mSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize
                );


        mDecodedBytes = ByteBuffer.allocate(20 * mSampleRate * 2);
        mDecodedBytes.order(ByteOrder.LITTLE_ENDIAN);
        mDecodedSamples = mDecodedBytes.asShortBuffer();
        audioRecord.startRecording();
        while (true) {

            if (mDecodedSamples.remaining() < 1024) {

                int newCapacity = mDecodedBytes.capacity() + 10 * mSampleRate * 2;
                ByteBuffer newDecodedBytes = null;
                try {
                    newDecodedBytes = ByteBuffer.allocate(newCapacity);
                } catch (OutOfMemoryError oome) {
                    break;
                }
                int position = mDecodedSamples.position();
                mDecodedBytes.rewind();
                newDecodedBytes.put(mDecodedBytes);
                mDecodedBytes = newDecodedBytes;
                mDecodedBytes.order(ByteOrder.LITTLE_ENDIAN);
                mDecodedBytes.rewind();
                mDecodedSamples = mDecodedBytes.asShortBuffer();
                mDecodedSamples.position(position);
            }
            audioRecord.read(buffer, 0, buffer.length);
            mDecodedSamples.put(buffer);
            if (!mProgressListener.reportProgress(
                    (float)(mDecodedSamples.position()) / mSampleRate)) {
                break;
            }
        }
        audioRecord.stop();
        audioRecord.release();
        mNumSamples = mDecodedSamples.position();
        mDecodedSamples.rewind();
        mDecodedBytes.rewind();
        mAvgBitRate = mSampleRate * 16 / 1000;

        mNumFrames = mNumSamples / getSamplesPerFrame();
        if (mNumSamples % getSamplesPerFrame() != 0){
            mNumFrames++;
        }
        mFrameGains = new int[mNumFrames];
        mFrameLens = null;
        mFrameOffsets = null;
        int i, j;
        int gain, value;
        for (i=0; i<mNumFrames; i++){
            gain = -1;
            for(j=0; j<getSamplesPerFrame(); j++) {
                if (mDecodedSamples.remaining() > 0) {
                    value = java.lang.Math.abs(mDecodedSamples.get());
                } else {
                    value = 0;
                }
                if (gain < value) {
                    gain = value;
                }
            }
            mFrameGains[i] = (int) Math.sqrt(gain);
        }
        mDecodedSamples.rewind();
    }

    public void WriteFile(File outputFile, int startFrame, int numFrames)
            throws java.io.IOException {
        float startTime = (float)startFrame * getSamplesPerFrame() / mSampleRate;
        float endTime = (float)(startFrame + numFrames) * getSamplesPerFrame() / mSampleRate;
        WriteFile(outputFile, startTime, endTime);
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
    public void WriteFile(File outputFile, float startTime, float endTime)
            throws java.io.IOException {
        int startOffset = (int)(startTime * mSampleRate) * 2 * mChannels;
        int numSamples = (int)((endTime - startTime) * mSampleRate);
        int numChannels = (mChannels == 1) ? 2 : mChannels;

        String mimeType = "audio/mp4a-latm";
        int bitrate = 64000 * numChannels;
        MediaCodec codec = MediaCodec.createEncoderByType(mimeType);
        MediaFormat format = MediaFormat.createAudioFormat(mimeType, mSampleRate, numChannels);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();

        int estimatedEncodedSize = (int)((endTime - startTime) * (bitrate / 8) * 1.1);
        ByteBuffer encodedBytes = ByteBuffer.allocate(estimatedEncodedSize);
        ByteBuffer[] inputBuffers = codec.getInputBuffers();
        ByteBuffer[] outputBuffers = codec.getOutputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean done_reading = false;
        long presentation_time = 0;

        int frame_size = 1024;
        byte buffer[] = new byte[frame_size * numChannels * 2];
        mDecodedBytes.position(startOffset);
        numSamples += (2 * frame_size);
        int tot_num_frames = 1 + (numSamples / frame_size);
        if (numSamples % frame_size != 0) {
            tot_num_frames++;
        }
        int[] frame_sizes = new int[tot_num_frames];
        int num_out_frames = 0;
        int num_frames=0;
        int num_samples_left = numSamples;
        int encodedSamplesSize = 0;
        byte[] encodedSamples = null;
        while (true) {
            int inputBufferIndex = codec.dequeueInputBuffer(100);
            if (!done_reading && inputBufferIndex >= 0) {
                if (num_samples_left <= 0) {
                    codec.queueInputBuffer(
                            inputBufferIndex, 0, 0, -1, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    done_reading = true;
                } else {
                    inputBuffers[inputBufferIndex].clear();
                    if (buffer.length > inputBuffers[inputBufferIndex].remaining()) {
                        continue;
                    }
                    int bufferSize = (mChannels == 1) ? (buffer.length / 2) : buffer.length;
                    if (mDecodedBytes.remaining() < bufferSize) {
                        for (int i=mDecodedBytes.remaining(); i < bufferSize; i++) {
                            buffer[i] = 0;
                        }
                        mDecodedBytes.get(buffer, 0, mDecodedBytes.remaining());
                    } else {
                        mDecodedBytes.get(buffer, 0, bufferSize);
                    }
                    if (mChannels == 1) {
                        for (int i=bufferSize - 1; i >= 1; i -= 2) {
                            buffer[2*i + 1] = buffer[i];
                            buffer[2*i] = buffer[i-1];
                            buffer[2*i - 1] = buffer[2*i + 1];
                            buffer[2*i - 2] = buffer[2*i];
                        }
                    }
                    num_samples_left -= frame_size;
                    inputBuffers[inputBufferIndex].put(buffer);
                    presentation_time = (long) (((num_frames++) * frame_size * 1e6) / mSampleRate);
                    codec.queueInputBuffer(
                            inputBufferIndex, 0, buffer.length, presentation_time, 0);
                }
            }

            int outputBufferIndex = codec.dequeueOutputBuffer(info, 100);
            if (outputBufferIndex >= 0 && info.size > 0 && info.presentationTimeUs >=0) {
                if (num_out_frames < frame_sizes.length) {
                    frame_sizes[num_out_frames++] = info.size;
                }
                if (encodedSamplesSize < info.size) {
                    encodedSamplesSize = info.size;
                    encodedSamples = new byte[encodedSamplesSize];
                }
                outputBuffers[outputBufferIndex].get(encodedSamples, 0, info.size);
                outputBuffers[outputBufferIndex].clear();
                codec.releaseOutputBuffer(outputBufferIndex, false);
                if (encodedBytes.remaining() < info.size) {
                    estimatedEncodedSize = (int)(estimatedEncodedSize * 1.2);
                    ByteBuffer newEncodedBytes = ByteBuffer.allocate(estimatedEncodedSize);
                    int position = encodedBytes.position();
                    encodedBytes.rewind();
                    newEncodedBytes.put(encodedBytes);
                    encodedBytes = newEncodedBytes;
                    encodedBytes.position(position);
                }
                encodedBytes.put(encodedSamples, 0, info.size);
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                outputBuffers = codec.getOutputBuffers();
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                break;
            }
        }
        int encoded_size = encodedBytes.position();
        encodedBytes.rewind();
        codec.stop();
        codec.release();
        codec = null;

        buffer = new byte[4096];
        try {
            FileOutputStream outputStream = new FileOutputStream(outputFile);
            outputStream.write(
                    MP4Header.getMP4Header(mSampleRate, numChannels, frame_sizes, bitrate));
            while (encoded_size - encodedBytes.position() > buffer.length) {
                encodedBytes.get(buffer);
                outputStream.write(buffer);
            }
            int remaining = encoded_size - encodedBytes.position();
            if (remaining > 0) {
                encodedBytes.get(buffer, 0, remaining);
                outputStream.write(buffer, 0, remaining);
            }
            outputStream.close();
        } catch (IOException e) {
            Log.e("Ringdroid", "Failed to create the .m4a file.");
            Log.e("Ringdroid", getStackTrace(e));
        }
    }

    private void swapLeftRightChannels(byte[] buffer) {
        byte left[] = new byte[2];
        byte right[] = new byte[2];
        if (buffer.length % 4 != 0) {
            return;
        }
        for (int offset = 0; offset < buffer.length; offset += 4) {
            left[0] = buffer[offset];
            left[1] = buffer[offset + 1];
            right[0] = buffer[offset + 2];
            right[1] = buffer[offset + 3];
            buffer[offset] = right[0];
            buffer[offset + 1] = right[1];
            buffer[offset + 2] = left[0];
            buffer[offset + 3] = left[1];
        }
    }

    public void WriteWAVFile(File outputFile, int startFrame, int numFrames)
            throws java.io.IOException {
        float startTime = (float)startFrame * getSamplesPerFrame() / mSampleRate;
        float endTime = (float)(startFrame + numFrames) * getSamplesPerFrame() / mSampleRate;
        WriteWAVFile(outputFile, startTime, endTime);
    }

    public void WriteWAVFile(File outputFile, float startTime, float endTime)
            throws java.io.IOException {
        int startOffset = (int)(startTime * mSampleRate) * 2 * mChannels;
        int numSamples = (int)((endTime - startTime) * mSampleRate);

        FileOutputStream outputStream = new FileOutputStream(outputFile);
        outputStream.write(WAVHeader.getWAVHeader(mSampleRate, mChannels, numSamples));

        byte buffer[] = new byte[1024 * mChannels * 2];
        mDecodedBytes.position(startOffset);
        int numBytesLeft = numSamples * mChannels * 2;
        while (numBytesLeft >= buffer.length) {
            if (mDecodedBytes.remaining() < buffer.length) {
                for (int i = mDecodedBytes.remaining(); i < buffer.length; i++) {
                    buffer[i] = 0;
                }
                mDecodedBytes.get(buffer, 0, mDecodedBytes.remaining());
            } else {
                mDecodedBytes.get(buffer);
            }
            if (mChannels == 2) {
                swapLeftRightChannels(buffer);
            }
            outputStream.write(buffer);
            numBytesLeft -= buffer.length;
        }
        if (numBytesLeft > 0) {
            if (mDecodedBytes.remaining() < numBytesLeft) {
                for (int i = mDecodedBytes.remaining(); i < numBytesLeft; i++) {
                    buffer[i] = 0;
                }
                mDecodedBytes.get(buffer, 0, mDecodedBytes.remaining());
            } else {
                mDecodedBytes.get(buffer, 0, numBytesLeft);
            }
            if (mChannels == 2) {
                swapLeftRightChannels(buffer);
            }
            outputStream.write(buffer, 0, numBytesLeft);
        }
        outputStream.close();
    }

    private void DumpSamples(String fileName) {
        String externalRootDir = Environment.getExternalStorageDirectory().getPath();
        if (!externalRootDir.endsWith("/")) {
            externalRootDir += "/";
        }
        String parentDir = externalRootDir + "media/audio/debug/";
        File parentDirFile = new File(parentDir);
        parentDirFile.mkdirs();
        if (!parentDirFile.isDirectory()) {
            parentDir = externalRootDir;
        }
        if (fileName == null || fileName.isEmpty()) {
            fileName = "samples.tsv";
        }
        File outFile = new File(parentDir + fileName);

        BufferedWriter writer = null;
        float presentationTime = 0;
        mDecodedSamples.rewind();
        String row;
        try {
            writer = new BufferedWriter(new FileWriter(outFile));
            for (int sampleIndex = 0; sampleIndex < mNumSamples; sampleIndex++) {
                presentationTime = (float)(sampleIndex) / mSampleRate;
                row = Float.toString(presentationTime);
                for (int channelIndex = 0; channelIndex < mChannels; channelIndex++) {
                    row += "\t" + mDecodedSamples.get();
                }
                row += "\n";
                writer.write(row);
            }
        } catch (IOException e) {
            Log.w("Ringdroid", "Failed to create the sample TSV file.");
            Log.w("Ringdroid", getStackTrace(e));
        }
        try {
            writer.close();
        } catch (Exception e) {
            Log.w("Ringdroid", "Failed to close sample TSV file.");
            Log.w("Ringdroid", getStackTrace(e));
        }
        mDecodedSamples.rewind();
    }

    private void DumpSamples() {
        DumpSamples(null);
    }

    private String getStackTrace(Exception e) {
        StringWriter writer = new StringWriter();
        e.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
