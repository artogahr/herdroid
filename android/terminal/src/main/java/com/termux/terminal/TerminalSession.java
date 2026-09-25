package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.util.UUID;

/**
 * A terminal session backed by a remote byte stream instead of a local subprocess.
 * <p>
 * Herdroid change: the upstream Termux class spawns a process through JNI. Here output
 * arrives through {@link #appendRemoteOutput(byte[], int, int)} from any thread, and user
 * input and size changes go to a {@link RemoteIO}. All emulation runs on the main thread.
 */
public final class TerminalSession extends TerminalOutput {

    /** Receives what the user types and the size the view wants. */
    public interface RemoteIO {
        void onInput(byte[] data, int offset, int count);

        void onResize(int columns, int rows);
    }

    private static final int MSG_NEW_INPUT = 1;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(1 << 20);
    private final byte[] mUtf8InputBuffer = new byte[5];

    TerminalSessionClient mClient;
    private final RemoteIO mRemote;
    private final Integer mTranscriptRows;
    private volatile boolean mRunning = true;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    public TerminalSession(Integer transcriptRows, TerminalSessionClient client, RemoteIO remote) {
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
        this.mRemote = remote;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
            mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
        mRemote.onResize(columns, rows);
    }

    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /** Queues bytes from the remote terminal. Safe to call from any thread. */
    public void appendRemoteOutput(byte[] data, int offset, int count) {
        if (!mRunning) return;
        mProcessToTerminalIOQueue.write(data, offset, count);
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
    }

    /** Marks the remote stream as ended. */
    public void finish() {
        mRunning = false;
        mProcessToTerminalIOQueue.close();
        mMainThreadHandler.post(() -> mClient.onSessionFinished(TerminalSession.this));
    }

    @Override
    public void write(byte[] data, int offset, int count) {
        if (mRunning) mRemote.onInput(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public boolean isRunning() {
        return mRunning;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        final byte[] mReceiveBuffer = new byte[4 * 1024];

        @Override
        public void handleMessage(Message msg) {
            if (mEmulator == null) return;
            int bytesRead;
            while ((bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false)) > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
            }
            notifyScreenUpdate();
        }

    }

}
