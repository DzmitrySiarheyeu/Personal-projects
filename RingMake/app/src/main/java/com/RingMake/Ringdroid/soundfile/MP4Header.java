
package com.RingMake.Ringdroid.soundfile;

class Atom {
    private int mSize;
    private int mType;
    private byte[] mData;
    private Atom[] mChildren;
    private byte mVersion;
    private int mFlags;

    public Atom(String type) {
        mSize = 8;
        mType = getTypeInt(type);
        mData = null;
        mChildren = null;
        mVersion = -1;
        mFlags = 0;
    }

    public Atom(String type, byte version, int flags) {
        mSize = 12;
        mType = getTypeInt(type);
        mData = null;
        mChildren = null;
        mVersion = version;
        mFlags = flags;
    }

    private void setSize() {
        int size = 8;
        if (mVersion >= 0) {
            size += 4;
        }
        if (mData != null) {
            size += mData.length;
        } else if (mChildren != null) {
            for (Atom child : mChildren) {
                size += child.getSize();
            }
        }
        mSize = size;
    }

    public int getSize() {
        return mSize;
    }

    private int getTypeInt(String type_str) {
        int type = 0;
        type |= (byte) (type_str.charAt(0)) << 24;
        type |= (byte) (type_str.charAt(1)) << 16;
        type |= (byte) (type_str.charAt(2)) << 8;
        type |= (byte) (type_str.charAt(3));
        return type;
    }

    public int getTypeInt() {
        return mType;
    }

    public String getTypeStr() {
        String type = "";
        type += (char) ((byte) ((mType >> 24) & 0xFF));
        type += (char) ((byte) ((mType >> 16) & 0xFF));
        type += (char) ((byte) ((mType >> 8) & 0xFF));
        type += (char) ((byte) (mType & 0xFF));
        return type;
    }

    public boolean setData(byte[] data) {
        if (mChildren != null || data == null) {
            // TODO(nfaralli): log something here
            return false;
        }
        mData = data;
        setSize();
        return true;
    }

    public byte[] getData() {
        return mData;
    }

    public boolean addChild(Atom child) {
        if (mData != null || child == null) {
            return false;
        }
        int numChildren = 1;
        if (mChildren != null) {
            numChildren += mChildren.length;
        }
        Atom[] children = new Atom[numChildren];
        if (mChildren != null) {
            System.arraycopy(mChildren, 0, children, 0, mChildren.length);
        }
        children[numChildren - 1] = child;
        mChildren = children;
        setSize();
        return true;
    }

    public Atom getChild(String type) {
        if (mChildren == null) {
            return null;
        }
        String[] types = type.split("\\.", 2);
        for (Atom child : mChildren) {
            if (child.getTypeStr().equals(types[0])) {
                if (types.length == 1) {
                    return child;
                } else {
                    return child.getChild(types[1]);
                }
            }
        }
        return null;
    }

    public byte[] getBytes() {
        byte[] atom_bytes = new byte[mSize];
        int offset = 0;

        atom_bytes[offset++] = (byte) ((mSize >> 24) & 0xFF);
        atom_bytes[offset++] = (byte) ((mSize >> 16) & 0xFF);
        atom_bytes[offset++] = (byte) ((mSize >> 8) & 0xFF);
        atom_bytes[offset++] = (byte) (mSize & 0xFF);
        atom_bytes[offset++] = (byte) ((mType >> 24) & 0xFF);
        atom_bytes[offset++] = (byte) ((mType >> 16) & 0xFF);
        atom_bytes[offset++] = (byte) ((mType >> 8) & 0xFF);
        atom_bytes[offset++] = (byte) (mType & 0xFF);
        if (mVersion >= 0) {
            atom_bytes[offset++] = mVersion;
            atom_bytes[offset++] = (byte) ((mFlags >> 16) & 0xFF);
            atom_bytes[offset++] = (byte) ((mFlags >> 8) & 0xFF);
            atom_bytes[offset++] = (byte) (mFlags & 0xFF);
        }
        if (mData != null) {
            System.arraycopy(mData, 0, atom_bytes, offset, mData.length);
        } else if (mChildren != null) {
            byte[] child_bytes;
            for (Atom child : mChildren) {
                child_bytes = child.getBytes();
                System.arraycopy(child_bytes, 0, atom_bytes, offset, child_bytes.length);
                offset += child_bytes.length;
            }
        }
        return atom_bytes;
    }

    public String toString() {
        String str = "";
        byte[] atom_bytes = getBytes();

        for (int i = 0; i < atom_bytes.length; i++) {
            if (i % 8 == 0 && i > 0) {
                str += '\n';
            }
            str += String.format("0x%02X", atom_bytes[i]);
            if (i < atom_bytes.length - 1) {
                str += ',';
                if (i % 8 < 7) {
                    str += ' ';
                }
            }
        }
        str += '\n';
        return str;
    }
}

public class MP4Header {
    private int[] mFrameSize;
    private int mMaxFrameSize;
    private int mTotSize;
    private int mBitrate;
    private byte[] mTime;
    private byte[] mDurationMS;
    private byte[] mNumSamples;
    private byte[] mHeader;
    private int mSampleRate;
    private int mChannels;

    public MP4Header(int sampleRate, int numChannels, int[] frame_size, int bitrate) {
        if (frame_size == null || frame_size.length < 2 || frame_size[0] != 2) {
            return;
        }
        mSampleRate = sampleRate;
        mChannels = numChannels;
        mFrameSize = frame_size;
        mBitrate = bitrate;
        mMaxFrameSize = mFrameSize[0];
        mTotSize = mFrameSize[0];
        for (int i = 1; i < mFrameSize.length; i++) {
            if (mMaxFrameSize < mFrameSize[i]) {
                mMaxFrameSize = mFrameSize[i];
            }
            mTotSize += mFrameSize[i];
        }
        long time = System.currentTimeMillis() / 1000;
        time += (66 * 365 + 16) * 24 * 60 * 60;
        mTime = new byte[4];
        mTime[0] = (byte) ((time >> 24) & 0xFF);
        mTime[1] = (byte) ((time >> 16) & 0xFF);
        mTime[2] = (byte) ((time >> 8) & 0xFF);
        mTime[3] = (byte) (time & 0xFF);
        int numSamples = 1024 * (frame_size.length - 1);
        int durationMS = (numSamples * 1000) / mSampleRate;
        if ((numSamples * 1000) % mSampleRate > 0) {
            durationMS++;
        }
        mNumSamples = new byte[]{
                (byte) ((numSamples >> 26) & 0XFF),
                (byte) ((numSamples >> 16) & 0XFF),
                (byte) ((numSamples >> 8) & 0XFF),
                (byte) (numSamples & 0XFF)
        };
        mDurationMS = new byte[]{
                (byte) ((durationMS >> 26) & 0XFF),
                (byte) ((durationMS >> 16) & 0XFF),
                (byte) ((durationMS >> 8) & 0XFF),
                (byte) (durationMS & 0XFF)
        };
        setHeader();
    }

    public byte[] getMP4Header() {
        return mHeader;
    }

    public static byte[] getMP4Header(
            int sampleRate, int numChannels, int[] frame_size, int bitrate) {
        return new MP4Header(sampleRate, numChannels, frame_size, bitrate).mHeader;
    }

    public String toString() {
        String str = "";
        if (mHeader == null) {
            return str;
        }
        int num_32bits_per_lines = 8;
        int count = 0;
        for (byte b : mHeader) {
            boolean break_line = count > 0 && count % (num_32bits_per_lines * 4) == 0;
            boolean insert_space = count > 0 && count % 4 == 0 && !break_line;
            if (break_line) {
                str += '\n';
            }
            if (insert_space) {
                str += ' ';
            }
            str += String.format("%02X", b);
            count++;
        }

        return str;
    }

    private void setHeader() {
        Atom a_ftyp = getFTYPAtom();
        Atom a_moov = getMOOVAtom();
        Atom a_mdat = new Atom("mdat");
        Atom a_stco = a_moov.getChild("trak.mdia.minf.stbl.stco");
        if (a_stco == null) {
            mHeader = null;
            return;
        }
        byte[] data = a_stco.getData();
        int chunk_offset = a_ftyp.getSize() + a_moov.getSize() + a_mdat.getSize();
        int offset = data.length - 4;
        data[offset++] = (byte) ((chunk_offset >> 24) & 0xFF);
        data[offset++] = (byte) ((chunk_offset >> 16) & 0xFF);
        data[offset++] = (byte) ((chunk_offset >> 8) & 0xFF);
        data[offset++] = (byte) (chunk_offset & 0xFF);


        byte[] header = new byte[chunk_offset];
        offset = 0;
        for (Atom atom : new Atom[]{a_ftyp, a_moov, a_mdat}) {
            byte[] atom_bytes = atom.getBytes();
            System.arraycopy(atom_bytes, 0, header, offset, atom_bytes.length);
            offset += atom_bytes.length;
        }

        int size = 8 + mTotSize;
        offset -= 8;
        header[offset++] = (byte) ((size >> 24) & 0xFF);
        header[offset++] = (byte) ((size >> 16) & 0xFF);
        header[offset++] = (byte) ((size >> 8) & 0xFF);
        header[offset++] = (byte) (size & 0xFF);

        mHeader = header;
    }

    private Atom getFTYPAtom() {
        Atom atom = new Atom("ftyp");
        atom.setData(new byte[]{
                'M', '4', 'A', ' ',
                0, 0, 0, 0,
                'M', '4', 'A', ' ',
                'm', 'p', '4', '2',
                'i', 's', 'o', 'm'
        });
        return atom;
    }

    private Atom getMOOVAtom() {
        Atom atom = new Atom("moov");
        atom.addChild(getMVHDAtom());
        atom.addChild(getTRAKAtom());
        return atom;
    }

    private Atom getMVHDAtom() {
        Atom atom = new Atom("mvhd", (byte) 0, 0);
        atom.setData(new byte[]{
                mTime[0], mTime[1], mTime[2], mTime[3],
                mTime[0], mTime[1], mTime[2], mTime[3],
                0, 0, 0x03, (byte) 0xE8,
                mDurationMS[0], mDurationMS[1], mDurationMS[2], mDurationMS[3],
                0, 1, 0, 0,
                1, 0,
                0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0x40, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 2
        });
        return atom;
    }

    private Atom getTRAKAtom() {
        Atom atom = new Atom("trak");
        atom.addChild(getTKHDAtom());
        atom.addChild(getMDIAAtom());
        return atom;
    }

    private Atom getTKHDAtom() {
        Atom atom = new Atom("tkhd", (byte) 0, 0x07);
        atom.setData(new byte[]{
                mTime[0], mTime[1], mTime[2], mTime[3],
                mTime[0], mTime[1], mTime[2], mTime[3],
                0, 0, 0, 1,
                0, 0, 0, 0,
                mDurationMS[0], mDurationMS[1], mDurationMS[2], mDurationMS[3],
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0,
                0, 0,
                1, 0,
                0, 0,
                0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0x40, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0
        });
        return atom;
    }

    private Atom getMDIAAtom() {
        Atom atom = new Atom("mdia");
        atom.addChild(getMDHDAtom());
        atom.addChild(getHDLRAtom());
        atom.addChild(getMINFAtom());
        return atom;
    }

    private Atom getMDHDAtom() {
        Atom atom = new Atom("mdhd", (byte) 0, 0);
        atom.setData(new byte[]{
                mTime[0], mTime[1], mTime[2], mTime[3],
                mTime[0], mTime[1], mTime[2], mTime[3],
                (byte) (mSampleRate >> 24), (byte) (mSampleRate >> 16),
                (byte) (mSampleRate >> 8), (byte) (mSampleRate),
                mNumSamples[0], mNumSamples[1], mNumSamples[2], mNumSamples[3],
                0, 0,
                0, 0
        });
        return atom;
    }

    private Atom getHDLRAtom() {
        Atom atom = new Atom("hdlr", (byte) 0, 0);
        atom.setData(new byte[]{
                0, 0, 0, 0,
                's', 'o', 'u', 'n',
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                'S', 'o', 'u', 'n',
                'd', 'H', 'a', 'n',
                'd', 'l', 'e', '\0'
        });
        return atom;
    }

    private Atom getMINFAtom() {
        Atom atom = new Atom("minf");
        atom.addChild(getSMHDAtom());
        atom.addChild(getDINFAtom());
        atom.addChild(getSTBLAtom());
        return atom;
    }

    private Atom getSMHDAtom() {
        Atom atom = new Atom("smhd", (byte) 0, 0);
        atom.setData(new byte[]{
                0, 0,
                0, 0
        });
        return atom;
    }

    private Atom getDINFAtom() {
        Atom atom = new Atom("dinf");
        atom.addChild(getDREFAtom());
        return atom;
    }

    private Atom getDREFAtom() {
        Atom atom = new Atom("dref", (byte) 0, 0);
        byte[] url = getURLAtom().getBytes();
        byte[] data = new byte[4 + url.length];
        data[3] = 0x01;
        System.arraycopy(url, 0, data, 4, url.length);
        atom.setData(data);
        return atom;
    }

    private Atom getURLAtom() {
        Atom atom = new Atom("url ", (byte) 0, 0x01);
        return atom;
    }

    private Atom getSTBLAtom() {
        Atom atom = new Atom("stbl");
        atom.addChild(getSTSDAtom());
        atom.addChild(getSTTSAtom());
        atom.addChild(getSTSCAtom());
        atom.addChild(getSTSZAtom());
        atom.addChild(getSTCOAtom());
        return atom;
    }

    private Atom getSTSDAtom() {
        Atom atom = new Atom("stsd", (byte) 0, 0);
        byte[] mp4a = getMP4AAtom().getBytes();
        byte[] data = new byte[4 + mp4a.length];
        data[3] = 0x01;
        System.arraycopy(mp4a, 0, data, 4, mp4a.length);
        atom.setData(data);
        return atom;
    }

    private Atom getMP4AAtom() {
        Atom atom = new Atom("mp4a");
        byte[] ase = new byte[]{
                0, 0, 0, 0, 0, 0,
                0, 1,
                0, 0, 0, 0,
                0, 0, 0, 0,
                (byte) (mChannels >> 8), (byte) mChannels,
                0, 0x10,
                0, 0,
                0, 0,
                (byte) (mSampleRate >> 8), (byte) (mSampleRate), 0, 0,
        };
        byte[] esds = getESDSAtom().getBytes();
        byte[] data = new byte[ase.length + esds.length];
        System.arraycopy(ase, 0, data, 0, ase.length);
        System.arraycopy(esds, 0, data, ase.length, esds.length);
        atom.setData(data);
        return atom;
    }

    private Atom getESDSAtom() {
        Atom atom = new Atom("esds", (byte) 0, 0);
        atom.setData(getESDescriptor());
        return atom;
    }

    private byte[] getESDescriptor() {
        int[] samplingFrequencies = new int[]{96000, 88200, 64000, 48000, 44100, 32000, 24000,
                22050, 16000, 12000, 11025, 8000, 7350};
        byte[] ESDescriptor_top = new byte[]{0x03, 0x19, 0x00, 0x00, 0x00};
        byte[] decConfigDescr_top = new byte[]{0x04, 0x11, 0x40, 0x15};
        byte[] audioSpecificConfig = new byte[]{0x05, 0x02, 0x10, 0x00};
        byte[] slConfigDescr = new byte[]{0x06, 0x01, 0x02};
        int offset;
        int bufferSize = 0x300;
        while (bufferSize < 2 * mMaxFrameSize) {
            bufferSize += 0x100;
        }

        byte[] decConfigDescr = new byte[2 + decConfigDescr_top[1]];
        System.arraycopy(decConfigDescr_top, 0, decConfigDescr, 0, decConfigDescr_top.length);
        offset = decConfigDescr_top.length;
        decConfigDescr[offset++] = (byte) ((bufferSize >> 16) & 0xFF);
        decConfigDescr[offset++] = (byte) ((bufferSize >> 8) & 0xFF);
        decConfigDescr[offset++] = (byte) (bufferSize & 0xFF);
        decConfigDescr[offset++] = (byte) ((mBitrate >> 24) & 0xFF);
        decConfigDescr[offset++] = (byte) ((mBitrate >> 16) & 0xFF);
        decConfigDescr[offset++] = (byte) ((mBitrate >> 8) & 0xFF);
        decConfigDescr[offset++] = (byte) (mBitrate & 0xFF);
        decConfigDescr[offset++] = (byte) ((mBitrate >> 24) & 0xFF);
        decConfigDescr[offset++] = (byte) ((mBitrate >> 16) & 0xFF);
        decConfigDescr[offset++] = (byte) ((mBitrate >> 8) & 0xFF);
        decConfigDescr[offset++] = (byte) (mBitrate & 0xFF);
        int index;
        for (index = 0; index < samplingFrequencies.length; index++) {
            if (samplingFrequencies[index] == mSampleRate) {
                break;
            }
        }
        if (index == samplingFrequencies.length) {
            index = 4;
        }
        audioSpecificConfig[2] |= (byte) ((index >> 1) & 0x07);
        audioSpecificConfig[3] |= (byte) (((index & 1) << 7) | ((mChannels & 0x0F) << 3));
        System.arraycopy(
                audioSpecificConfig, 0, decConfigDescr, offset, audioSpecificConfig.length);

        byte[] ESDescriptor = new byte[2 + ESDescriptor_top[1]];
        System.arraycopy(ESDescriptor_top, 0, ESDescriptor, 0, ESDescriptor_top.length);
        offset = ESDescriptor_top.length;
        System.arraycopy(decConfigDescr, 0, ESDescriptor, offset, decConfigDescr.length);
        offset += decConfigDescr.length;
        System.arraycopy(slConfigDescr, 0, ESDescriptor, offset, slConfigDescr.length);
        return ESDescriptor;
    }

    private Atom getSTTSAtom() {
        Atom atom = new Atom("stts", (byte) 0, 0);
        int numAudioFrames = mFrameSize.length - 1;
        atom.setData(new byte[]{
                0, 0, 0, 0x02,
                0, 0, 0, 0x01,
                0, 0, 0, 0,
                (byte) ((numAudioFrames >> 24) & 0xFF), (byte) ((numAudioFrames >> 16) & 0xFF),
                (byte) ((numAudioFrames >> 8) & 0xFF), (byte) (numAudioFrames & 0xFF),
                0, 0, 0x04, 0,
        });
        return atom;
    }

    private Atom getSTSCAtom() {
        Atom atom = new Atom("stsc", (byte) 0, 0);
        int numFrames = mFrameSize.length;
        atom.setData(new byte[]{
                0, 0, 0, 0x01,
                0, 0, 0, 0x01,
                (byte) ((numFrames >> 24) & 0xFF), (byte) ((numFrames >> 16) & 0xFF),
                (byte) ((numFrames >> 8) & 0xFF), (byte) (numFrames & 0xFF),
                0, 0, 0, 0x01,
        });
        return atom;
    }

    private Atom getSTSZAtom() {
        Atom atom = new Atom("stsz", (byte) 0, 0);
        int numFrames = mFrameSize.length;
        byte[] data = new byte[8 + 4 * numFrames];
        int offset = 0;
        data[offset++] = 0;
        data[offset++] = 0;
        data[offset++] = 0;
        data[offset++] = 0;
        data[offset++] = (byte) ((numFrames >> 24) & 0xFF);
        data[offset++] = (byte) ((numFrames >> 16) & 0xFF);
        data[offset++] = (byte) ((numFrames >> 8) & 0xFF);
        data[offset++] = (byte) (numFrames & 0xFF);
        for (int size : mFrameSize) {
            data[offset++] = (byte) ((size >> 24) & 0xFF);
            data[offset++] = (byte) ((size >> 16) & 0xFF);
            data[offset++] = (byte) ((size >> 8) & 0xFF);
            data[offset++] = (byte) (size & 0xFF);
        }
        atom.setData(data);
        return atom;
    }

    private Atom getSTCOAtom() {
        Atom atom = new Atom("stco", (byte) 0, 0);
        atom.setData(new byte[]{
                0, 0, 0, 0x01,
                0, 0, 0, 0
        });
        return atom;
    }
}
