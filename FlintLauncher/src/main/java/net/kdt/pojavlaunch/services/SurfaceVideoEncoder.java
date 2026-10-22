package net.kdt.pojavlaunch.services;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.EGLExt;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Copies Minecraft frames to an EGL surface and lets the hardware encoder convert/encode them. */
final class SurfaceVideoEncoder {
    private static final String MIME_TYPE = "video/avc";
    private static final int FRAME_RATE = 24;
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final float[] QUAD = {
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f
    };

    private final int width;
    private final int height;
    private final FloatBuffer quad;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private final ParcelFileDescriptor outputDescriptor;
    private final MediaCodec codec;
    private final MediaMuxer muxer;
    private final Surface inputSurface;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int textureId;
    private int program;
    private int positionLocation;
    private int texCoordLocation;
    private int textureLocation;
    private int videoTrack = -1;
    private long lastPresentationTimeUs;
    private boolean muxerStarted;
    private boolean finished;

    SurfaceVideoEncoder(Context context, Uri outputUri, int width, int height, int bitRate) throws IOException {
        this.width = width;
        this.height = height;
        quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().put(QUAD);
        quad.position(0);
        ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(outputUri, "w");
        if (descriptor == null) throw new IOException("Could not open MP4 output file");
        outputDescriptor = descriptor;

        MediaCodec createdCodec = null;
        MediaMuxer createdMuxer = null;
        Surface createdInputSurface = null;
        try {
            createdMuxer = new MediaMuxer(outputDescriptor.getFileDescriptor(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            createdCodec = MediaCodec.createEncoderByType(MIME_TYPE);
            createdCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            createdInputSurface = createdCodec.createInputSurface();
            createdCodec.start();
            initEgl(createdInputSurface);
        } catch (Exception e) {
            releaseEgl();
            if (createdCodec != null) {
                try { createdCodec.stop(); } catch (RuntimeException ignored) {}
                try { createdCodec.release(); } catch (RuntimeException ignored) {}
            }
            if (createdInputSurface != null) createdInputSurface.release();
            if (createdMuxer != null) {
                try { createdMuxer.release(); } catch (RuntimeException ignored) {}
            }
            try { outputDescriptor.close(); } catch (IOException ignored) {}
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Could not initialize the hardware MP4 encoder", e);
        }
        codec = createdCodec;
        muxer = createdMuxer;
        inputSurface = createdInputSurface;
    }

    private void initEgl(Surface surface) throws IOException {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new IOException("Could not open EGL display for recorder");
        int[] versions = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) {
            throw new IOException("Could not initialize EGL recorder context");
        }
        int[] configAttributes = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] configCount = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttributes, 0, configs, 0, 1, configCount, 0)
                || configCount[0] == 0) {
            throw new IOException("No EGL config can render to the video encoder");
        }
        int[] contextAttributes = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                contextAttributes, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw new IOException("Could not create recorder EGL context");
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface,
                new int[] { EGL14.EGL_NONE }, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw new IOException("Could not create encoder EGL surface");
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new IOException("Could not activate recorder EGL context");
        }
        // The encoder is not displayed; avoid waiting on the phone's screen refresh.
        EGL14.eglSwapInterval(eglDisplay, 0);

        String vertexShader = "attribute vec2 aPosition; attribute vec2 aTexCoord; "
                + "varying vec2 vTexCoord; void main(){ gl_Position=vec4(aPosition,0.0,1.0); vTexCoord=aTexCoord; }";
        String fragmentShader = "precision mediump float; varying vec2 vTexCoord; uniform sampler2D uTexture; "
                + "void main(){ gl_FragColor=texture2D(uTexture,vTexCoord); }";
        program = createProgram(vertexShader, fragmentShader);
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition");
        texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord");
        textureLocation = GLES20.glGetUniformLocation(program, "uTexture");
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glViewport(0, 0, width, height);
        checkGl("initialize recorder texture");
    }

    private static int createProgram(String vertexSource, String fragmentSource) throws IOException {
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int result = GLES20.glCreateProgram();
        GLES20.glAttachShader(result, vertex);
        GLES20.glAttachShader(result, fragment);
        GLES20.glLinkProgram(result);
        int[] status = new int[1];
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        if (status[0] == 0) {
            String message = GLES20.glGetProgramInfoLog(result);
            GLES20.glDeleteProgram(result);
            throw new IOException("Could not link recorder shader: " + message);
        }
        return result;
    }

    private static int compileShader(int type, String source) throws IOException {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String message = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IOException("Could not compile recorder shader: " + message);
        }
        return shader;
    }

    /** Uploads one copied frame and lets the GPU feed the hardware video encoder. */
    void writeFrame(Bitmap bitmap, long presentationTimeUs) throws IOException {
        if (finished) return;
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new IOException("Could not activate recorder EGL surface");
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
        GLES20.glUseProgram(program);
        quad.position(0);
        GLES20.glEnableVertexAttribArray(positionLocation);
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, quad);
        quad.position(2);
        GLES20.glEnableVertexAttribArray(texCoordLocation);
        GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glUniform1i(textureLocation, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(positionLocation);
        GLES20.glDisableVertexAttribArray(texCoordLocation);
        checkGl("draw Minecraft recording frame");

        presentationTimeUs = Math.max(lastPresentationTimeUs + 1, presentationTimeUs);
        lastPresentationTimeUs = presentationTimeUs;
        if (!EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeUs * 1000L)
                || !EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            throw new IOException("Could not send Minecraft frame to the MP4 encoder");
        }
        drainEncoder(false);
    }

    private static void checkGl(String operation) throws IOException {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            throw new IOException("OpenGL recorder error " + error + " while trying to " + operation);
        }
    }

    private void drainEncoder(boolean endOfStream) throws IOException {
        boolean eos = false;
        long deadline = android.os.SystemClock.elapsedRealtime() + (endOfStream ? 3000L : 0L);
        while (!eos) {
            int outputIndex;
            try {
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, endOfStream ? 10_000 : 0);
            } catch (RuntimeException e) {
                throw new IOException("Video encoder failed while writing MP4", e);
            }
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break;
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    throw new IOException("Timed out while finalizing MP4 frames");
                }
                continue;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) throw new IOException("Video encoder changed output format unexpectedly");
                videoTrack = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                muxerStarted = true;
                continue;
            }
            if (outputIndex < 0) continue;

            ByteBuffer encoded = codec.getOutputBuffer(outputIndex);
            if (encoded != null && bufferInfo.size > 0 && muxerStarted
                    && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                encoded.position(bufferInfo.offset);
                encoded.limit(bufferInfo.offset + bufferInfo.size);
                muxer.writeSampleData(videoTrack, encoded, bufferInfo);
            }
            eos = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            codec.releaseOutputBuffer(outputIndex, false);
        }
    }

    boolean finish() throws IOException {
        if (finished) return muxerStarted;
        finished = true;
        try {
            if (!EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) {
                throw new IOException("Could not release recorder EGL context");
            }
            codec.signalEndOfInputStream();
            drainEncoder(true);
            if (muxerStarted) {
                muxer.stop();
                muxerStarted = false;
            }
            return videoTrack >= 0;
        } catch (RuntimeException e) {
            throw new IOException("Could not finalize MP4 recording", e);
        } finally {
            releaseEgl();
            try { codec.stop(); } catch (RuntimeException ignored) {}
            codec.release();
            try { muxer.release(); } catch (RuntimeException ignored) {}
            inputSurface.release();
            try { outputDescriptor.close(); } catch (IOException ignored) {}
        }
    }

    private void releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            boolean hasCurrentContext = eglContext != EGL14.EGL_NO_CONTEXT
                    && eglSurface != EGL14.EGL_NO_SURFACE
                    && EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
            if (hasCurrentContext) {
                if (textureId != 0) GLES20.glDeleteTextures(1, new int[] { textureId }, 0);
                if (program != 0) GLES20.glDeleteProgram(program);
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            }
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
        textureId = 0;
        program = 0;
    }

    void abort() {
        if (finished) return;
        finished = true;
        releaseEgl();
        try { codec.stop(); } catch (RuntimeException ignored) {}
        try { codec.release(); } catch (RuntimeException ignored) {}
        try { muxer.release(); } catch (RuntimeException ignored) {}
        inputSurface.release();
        try { outputDescriptor.close(); } catch (IOException ignored) {}
    }
}
