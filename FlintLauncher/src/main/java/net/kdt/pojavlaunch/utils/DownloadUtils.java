package net.kdt.pojavlaunch.utils;

import androidx.annotation.Nullable;

import com.flintlauncher.feature.log.Logging;
import com.flintlauncher.utils.path.PathManager;
import com.flintlauncher.utils.ZHTools;
import com.flintlauncher.utils.path.UrlManager;

import net.kdt.pojavlaunch.Tools;

import org.apache.commons.io.IOUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.io.EOFException;
import java.io.InterruptedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

@SuppressWarnings("IOStreamConstructor")
public class DownloadUtils {
    public static void download(String url, OutputStream os) throws IOException {
        download(new URL(url), os);
    }

    public static void download(URL url, OutputStream os) throws IOException {
        InputStream is = null;
        try {
            HttpURLConnection conn = UrlManager.createHttpConnection(url);
            conn.setDoInput(true);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned HTTP " + conn.getResponseCode()
                        + ": " + conn.getResponseMessage());
            }
            is = conn.getInputStream();
            IOUtils.copy(is, os);
        } catch (SocketTimeoutException e) {
            throw new IOException("Download timed out: " + url, e);
        } catch (IOException e) {
            throw new IOException("Unable to download from " + url, e);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                    Logging.e("DownloadUtils", Tools.printToString(e));
                }
            }
        }
    }

    public static String downloadString(String url) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        download(url, bos);
        bos.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    public static void downloadFile(String url, File out) throws IOException {
        FileUtils.ensureParentDirectory(out);
        try (FileOutputStream fileOutputStream = new FileOutputStream(out)) {
            download(url, fileOutputStream);
        }
    }

    public static void downloadFileMonitored(String urlInput, File outputFile, @Nullable byte[] buffer,
                                             Tools.DownloaderFeedback monitor) throws IOException {
        FileUtils.ensureParentDirectory(outputFile);
        IOException lastError = null;

        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection conn = null;
            InputStream readStr = null;
            try {
                conn = UrlManager.createHttpConnection(new URL(urlInput));
                conn.setDoInput(true);
                conn.connect();
                int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    IOException responseError = new IOException("Server returned HTTP " + responseCode + " for " + urlInput);
                    if (responseCode < 500 && responseCode != 408 && responseCode != 429) throw responseError;
                    throw new RetryableDownloadException(responseError);
                }

                readStr = conn.getInputStream();
                int overall = 0;
                int length = conn.getContentLength();
                if (buffer == null) buffer = new byte[65535];
                try (FileOutputStream fos = new FileOutputStream(outputFile, false)) {
                    int current;
                    while ((current = readStr.read(buffer)) != -1) {
                        overall += current;
                        fos.write(buffer, 0, current);
                        monitor.updateProgress(overall, length);
                    }
                    fos.getFD().sync();
                }
                if (length >= 0 && overall != length) {
                    throw new EOFException("Incomplete download from " + urlInput + " (" + overall + "/" + length + " bytes)");
                }
                return;
            } catch (IOException error) {
                lastError = error instanceof RetryableDownloadException ? (IOException) error.getCause() : error;
                if (outputFile.exists() && !outputFile.delete()) {
                    throw new IOException("Could not remove incomplete download: " + outputFile, lastError);
                }
                if (attempt == 2 || !isTransientDownloadError(error)) throw lastError;
                monitor.updateProgress(0, -1);
                try {
                    Thread.sleep(500L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException interruptedError = new InterruptedIOException("Download retry interrupted");
                    interruptedError.initCause(interrupted);
                    throw interruptedError;
                }
            } finally {
                if (readStr != null) {
                    try { readStr.close(); } catch (IOException ignored) { }
                }
                if (conn != null) conn.disconnect();
            }
        }
        throw lastError == null ? new IOException("Download failed: " + urlInput) : lastError;
    }

    private static boolean isTransientDownloadError(IOException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnknownHostException || cause instanceof SocketTimeoutException
                    || cause instanceof SocketException || cause instanceof EOFException
                    || cause instanceof RetryableDownloadException) return true;
        }
        return false;
    }

    private static final class RetryableDownloadException extends IOException {
        RetryableDownloadException(IOException cause) { super(cause); }
    }

    public static <T> T downloadStringCached(String url, String cacheName, boolean force, ParseCallback<T> parseCallback) throws IOException, ParseException{
        File cacheDestination = new File(PathManager.DIR_CACHE_STRING, cacheName);
        if (force && cacheDestination.exists()) org.apache.commons.io.FileUtils.deleteQuietly(cacheDestination);
        if (cacheDestination.isFile() && cacheDestination.canRead() &&
                ZHTools.getCurrentTimeMillis() < (cacheDestination.lastModified() + 86400000)) {
            try {
                String cachedString = Tools.read(new FileInputStream(cacheDestination));
                return parseCallback.process(cachedString);
            }catch(IOException e) {
                Logging.i("DownloadUtils", "Failed to read the cached file", e);
            }catch (ParseException e) {
                Logging.i("DownloadUtils", "Failed to parse the cached file", e);
            }
        }
        String urlContent = DownloadUtils.downloadString(url);
        // if we download the file and fail parsing it, we will yeet outta there
        // and not cache the unparseable sting. We will return this after trying to save the downloaded
        // string into cache
        T parseResult = parseCallback.process(urlContent);

        boolean tryWriteCache;
        if(cacheDestination.exists()) {
            tryWriteCache = cacheDestination.canWrite();
        } else {
            tryWriteCache = FileUtils.ensureParentDirectorySilently(cacheDestination);
        }

        if(tryWriteCache) try {
            Tools.write(cacheDestination.getAbsolutePath(), urlContent);
        }catch(IOException e) {
            Logging.i("DownloadUtils", "Failed to cache the string", e);
        }
        return parseResult;
    }

    private static <T> T downloadFile(Callable<T> downloadFunction) throws IOException{
        try {
            return downloadFunction.call();
        } catch (IOException e){
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean verifyFile(File file, String sha1) {
        return file.exists() && Tools.compareSHA1(file, sha1);
    }

    public static <T> T ensureSha1(File outputFile, @Nullable String sha1, Callable<T> downloadFunction) throws IOException {
        // Skip if needed
        if(sha1 == null) {
            // If the file exists and we don't know it's SHA1, don't try to redownload it.
            if(outputFile.exists()) return null;
            else return downloadFile(downloadFunction);
        }

        int attempts = 0;
        boolean fileOkay = verifyFile(outputFile, sha1);
        T result = null;
        while (attempts < 5 && !fileOkay){
            attempts++;
            downloadFile(downloadFunction);
            fileOkay = verifyFile(outputFile, sha1);
        }
        if(!fileOkay) throw new SHA1VerificationException("SHA1 verifcation failed after 5 download attempts");
        return result;
    }

    /**
     * Get the content length for a given URL.
     * @param url the URL to get the length for
     * @return the length in bytes or -1 if not available
     * @throws IOException if an I/O error occurs.
     */
    public static long getContentLength(String url) throws IOException {
        HttpURLConnection urlConnection = (HttpURLConnection) new URL(url).openConnection();
        urlConnection.setRequestMethod("HEAD");
        urlConnection.setDoInput(false);
        urlConnection.setDoOutput(false);
        urlConnection.connect();
        int responseCode = urlConnection.getResponseCode();
        if(responseCode >= 200 && responseCode <= 299) return urlConnection.getContentLength();
        return -1;
    }

    public interface ParseCallback<T> {
        T process(String input) throws ParseException;
    }
    public static class ParseException extends Exception {
        public ParseException(Exception e) {
            super(e);
        }
    }

    public static class SHA1VerificationException extends IOException {
        public SHA1VerificationException(String message) {
            super(message);
        }
    }
}

