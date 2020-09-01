package com.befovy.fijkplayer;

public class FijkEventConstants {
    static final int FLUSH = 0;
    static final int ERROR = 100;
    static final int PREPARED = 200;
    static final int COMPLETED = 300;
    static final int VIDEO_SIZE_CHANGED = 400;
    static final int SAR_CHANGED = 401;
    static final int VIDEO_RENDERING_START = 402;
    static final int AUDIO_RENDERING_START = 403;
    static final int VIDEO_ROTATION_CHANGED = 404;
    static final int AUDIO_DECODED_START = 405;
    static final int VIDEO_DECODED_START = 406;
    static final int OPEN_INPUT = 407;
    static final int FIND_STREAM_INFO = 408;
    static final int COMPONENT_OPEN = 409;
    static final int VIDEO_SEEK_RENDERING_START = 410;
    static final int AUDIO_SEEK_RENDERING_START = 411;
    static final int BUFFERING_START = 500;
    static final int BUFFERING_END = 501;
    static final int BUFFERING_UPDATE = 502;
    static final int BUFFERING_BYTES_UPDATE = 503;
    static final int BUFFERING_TIME_UPDATE = 504;
    static final int CURRENT_POSITION_UPDATE = 510;
    static final int SEEK_COMPLETE = 600;
    static final int PLAYBACK_STATE_CHANGED = 700;
    static final int TIMED_TEXT = 800;
    static final int ACCURATE_SEEK_COMPLETE = 900;
    static final int GET_IMG_STATE = 1000;
}
