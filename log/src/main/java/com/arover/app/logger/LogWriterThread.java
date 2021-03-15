package com.arover.app.logger;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;

import com.arover.app.Util;
import com.arover.app.crypto.AesCbcCipher;
import com.arover.app.crypto.RsaCipher;

import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Calendar;

import static com.arover.app.Util.bytesToHexString;
import static com.arover.app.Util.hexStringToBytes;
import static com.arover.app.Util.intToBytes;


public class LogWriterThread extends HandlerThread {

    public static final int ENCRYPT_IV_LEN = 256;
    public static final int ENCRYPT_KEY_LEN = 256;
    private static final String TAG = "LogWriterThread";

    private static final int MSG_FLUSH = 0;
    private static final int MSG_CLOSE = 1;
    private static final int MSG_LOG = 2;
    static final int MSG_COMPRESS_COMPLETED = 3;
    private static final int MSG_FORCE_FLUSH = 4;
    private static final int MSG_INCREASE_LOG_NO = 5;
    private static final long FLUSH_LOG_DELAY = 500;

    public static final int BUFFER_SIZE = 1024 * 16;
    private static final ByteBuffer sLogBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
    private static final long MAX_LOG_FILE_SIZE = 1024 * 1024 * 10; //10mb
    private static final int FLUSH_THRESHOLD = BUFFER_SIZE/4;// write log if size > 4kb
    static final byte ENCRYPT_LOG = 1;
    static final byte PLAIN_LOG = 0;
    private final LoggerManager logManager;
    public static LogWriterThread instance;
    private byte[] publicKey;

    private boolean isCompressing;
    private DataOutputStream fileLogWriter;
    private Handler handler;
    private LogCompressor logCompressor;
    private boolean doCheckUncompressedLogs = true;
    private File currentLogFile;
    private int logFileNo = 0;
    private String currentLogFileName;

    LogWriterThread(LoggerManager loggerManager) {
        super("LogWriter", Process.THREAD_PRIORITY_BACKGROUND);
        this.logManager = loggerManager;
        instance = this;

        if(loggerManager.getPublicKey() != null && loggerManager.getPublicKey().trim().length() > 0){
            publicKey=hexStringToBytes(loggerManager.getPublicKey());
        }

    }

    public void flushBuffer() {
        handler.sendEmptyMessage(MSG_FORCE_FLUSH);
    }

    @Override
    protected void onLooperPrepared() {
        super.onLooperPrepared();

        handler = new Handler(getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_LOG:
                        String log = (String) msg.obj;
                        byte[] bytes = log.getBytes();
//                        android.util.Log.d(TAG, "put log" + log + "，bytes=" + bytesToHexString(bytes));

                        if(sLogBuffer.limit() - sLogBuffer.position() > bytes.length){
                            sLogBuffer.put(log.getBytes());
                            sendEmptyMessageDelayed(MSG_FLUSH, FLUSH_LOG_DELAY);
                        } else {
                            android.util.Log.e(TAG,"back pressure!!!  log discarded,  plz increase sLogBuffer");
                        }
                        break;

                    case MSG_FLUSH:
//                        android.util.Log.d(TAG, "MSG_FLUSH flush log begin");
                        removeMessages(MSG_FLUSH);
                        removeMessages(MSG_CLOSE);
                        writeLog(false);
//                        android.util.Log.d(TAG, "flush log done");
                        break;
                    case MSG_FORCE_FLUSH:
//                        android.util.Log.d(TAG, "MSG_FORCE_FLUSH flush log begin");
                        removeMessages(MSG_FLUSH);
                        removeMessages(MSG_FORCE_FLUSH);
                        removeMessages(MSG_CLOSE);
                        writeLog(true);
//                        android.util.Log.d(TAG, "flush log done");
                        break;
                    case MSG_INCREASE_LOG_NO:
                        android.util.Log.d(TAG, "MSG_INCREASE_LOG_NO");
                        writeLog(true);
                        closeWriter();
                        // add file no to start new filer writer;
                        logFileNo+=1;
                        break;
                    case MSG_CLOSE:
//                        android.util.Log.d(TAG, "close log write");
                        closeWriter();
                        break;
                    case MSG_COMPRESS_COMPLETED:
                        isCompressing = false;
                        logCompressor = null;
                        break;
                }
            }
        };
        Log.i(TAG, "LogWriterThread started level=" + Log.sLogLvlName + ",logcat enable=" + Log.sLogcatEnabled);
        Log.sInitialized = true;
    }

    private void closeWriter() {
        try {
            if (fileLogWriter != null) {
                logFileNo--;
                fileLogWriter.close();
            }
        } catch (Exception ignored) {
        } finally {
            fileLogWriter = null;
        }
    }

    private void writeLog(boolean forceFlush) {
        if (Log.sLogDir == null) return;

        if (fileLogWriter == null || reachLogFileSizeLimit()) {
            createLogWriter();
        }

        if (fileLogWriter == null){
            android.util.Log.e(TAG,"LOGGER ERROR  writeLog createLogWriter failed");
            return;
        }

        if (diskNoMoreSpace()) {
            android.util.Log.e(TAG,"LOGGER ERROR  no more space.");
            sLogBuffer.clear();
            return;
        }
        byte mode = (byte) (publicKey == null? 0:1);
        boolean doWriteNow;
        if(mode == ENCRYPT_LOG) {
            doWriteNow = sLogBuffer.position() > FLUSH_THRESHOLD || forceFlush;
        } else {
            // we write plain log immediately;
            doWriteNow = true;
        }

        if (sLogBuffer.position() > 0 && doWriteNow) {
            android.util.Log.i(TAG, "Log Buffer flushed:n=" + sLogBuffer.position());
            int n = sLogBuffer.position();
            byte[] temp = new byte[n];
            sLogBuffer.flip();
            sLogBuffer.get(temp);
            sLogBuffer.clear();

            if(mode == ENCRYPT_LOG) {
                writeEncryptedLog(temp);
            } else {
                writePlainLog(temp);
            }
        }
    }

    private boolean diskNoMoreSpace() {
        return false;
    }

    private void writePlainLog(byte[] temp) {
        try {
            fileLogWriter.write(temp);
            fileLogWriter.flush();
        } catch (Exception e) {
            android.util.Log.e(TAG, "writePlainLog", e);
            fileLogWriter = null;
        }
    }

    private void writeEncryptedLog(byte[] temp) {
        byte[] iv = AesCbcCipher.genRandomBytes(16);
        byte[] key = AesCbcCipher.genRandomBytes(16);
        byte[] encryptedLog = temp;
        byte mode = ENCRYPT_LOG;
        try {
            encryptedLog = AesCbcCipher.encrypt(temp, key, iv);
        } catch (Exception e) {
            android.util.Log.e(TAG, "encrypt log error", e);
            mode = 0;
        }
        byte[] len = intToBytes(encryptedLog.length);
        byte[] encryptKey = RsaCipher.encrypt(key, publicKey);
        byte[] encryptIv = RsaCipher.encrypt(iv, publicKey);

//        android.util.Log.v(TAG, "mode = " + mode);
//        android.util.Log.v(TAG, "iv = " + bytesToHexString(iv));
//        android.util.Log.v(TAG, "key = " + bytesToHexString(key));
//        android.util.Log.d(TAG, "encryptIv.length = " + encryptIv.length);
//        android.util.Log.d(TAG, "encryptKey.length = " + encryptKey.length);
//        android.util.Log.d(TAG, "write len hex= " + bytesToHexString(len)
//                + ",len = " + encryptedLog.length + ",log hex=" + bytesToHexString(encryptedLog));

        try {
            fileLogWriter.write(len);
            fileLogWriter.write(mode);
            fileLogWriter.write(encryptIv);
            fileLogWriter.write(encryptKey);
            fileLogWriter.write(encryptedLog);
            fileLogWriter.flush();

        } catch (Exception e) {
            android.util.Log.e(TAG, "writeLog", e);
            fileLogWriter = null;
        }
    }


    private boolean reachLogFileSizeLimit() {
        return currentLogFile.length() > MAX_LOG_FILE_SIZE;

    }

    private void createLogWriter() {

        Util.closeQuietly(fileLogWriter);

        File folder = new File(Log.sLogDir);
        if (!folder.exists() && !folder.mkdirs()) {
            android.util.Log.e(TAG, "log dir create failed.dir=" + Log.sLogDir);
        }

        try {
            // find latest log file no.
            while(true) {
                currentLogFileName = genLogFileName();
//                android.util.Log.v(TAG, "createLogWriter file=" + currentLogFileName);
                String zippedLog = currentLogFileName.replace(".log",".zip");
                File zippedFile = new File(Log.sLogDir,zippedLog);
                if(zippedFile.exists()) {
//                    android.util.Log.v(TAG, "zippedLog file exist=" + zippedLog);
                    logFileNo++;
                } else {
                    break;
                }
            }

            currentLogFile = new File(Log.sLogDir, currentLogFileName);
            if (!currentLogFile.exists()) {
                boolean created = currentLogFile.createNewFile();
                if (!created) {
                    android.util.Log.e(TAG, "failed to create new log file  path="
                            + currentLogFile.getAbsolutePath());
                }
                //check uncompressed logs after create new log file.
                doCheckUncompressedLogs = true;
            }

            android.util.Log.d(TAG, "currentLogFileName=" + currentLogFileName);

            fileLogWriter = new DataOutputStream(new FileOutputStream(currentLogFile, true));

            if (doCheckUncompressedLogs && !isCompressing) {
                findAllOldLogsAndCompress();
                doCheckUncompressedLogs = false;
            }
            logFileNo++;
        } catch (Exception e) {
            if(logFileNo > 0) {
                logFileNo -= 1;
            }
            android.util.Log.e(TAG, "writeLog", e);
        }
    }

    private void close(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private String genLogFileName() {
        String prefix="";
        // add prefix to encrypted log file name. avoid write plain log
        // to same file after app changed log mode.
        if(isEncryptMode()){
            prefix = "s_";
        }
        Calendar cal = Calendar.getInstance();
        return prefix + cal.get(Calendar.YEAR)
                + "-" + (cal.get(Calendar.MONTH) + 1)
                + ("-") + cal.get(Calendar.DAY_OF_MONTH)
                + "-" + logFileNo + ".log";
    }

    private boolean isEncryptMode() {
        return publicKey != null;
    }

    private void findAllOldLogsAndCompress() {
        if (isCompressing) return;

        logCompressor = new LogCompressor(handler, logManager.getLogDirFullPath(), currentLogFileName);
        logCompressor.start();
    }

    private String getLogTime() {
        Calendar calendar = Calendar.getInstance();
        return (calendar.get(Calendar.MONTH) + 1)
                + "-" + calendar.get(Calendar.DAY_OF_MONTH)
                + " " + calendar.get(Calendar.HOUR_OF_DAY)
                + ":" + calendar.get(Calendar.MINUTE)
                + ":" + calendar.get(Calendar.SECOND)
                + "." + calendar.get(Calendar.MILLISECOND);
    }

    void writeLog(LoggerManager.Level level, String log) {

        String logStr = getLogTime() + level.prefix + log + "\n";
        if (handler == null) {
            sLogBuffer.put(logStr.getBytes());
            android.util.Log.d(TAG, "looper not prepared, log put in buffer.");
            return;
        }
        Message msg = Message.obtain(handler, MSG_LOG, logStr);
        msg.sendToTarget();
    }

    public File getCurrentFile() {
        return currentLogFile;
    }

    @Override
    public boolean quit() {
        android.util.Log.d(TAG, "quit");
        handler.sendEmptyMessage(MSG_FORCE_FLUSH);
        handler.sendEmptyMessage(MSG_CLOSE);
        return super.quitSafely();
    }


    public void flushAndStartCompressTask() {
        handler.sendEmptyMessage(MSG_FORCE_FLUSH);
        handler.sendEmptyMessage(MSG_INCREASE_LOG_NO);
    }
}
