package com.whw.videowallpaper;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Renders MediaPlayer frames through an external OES texture. Owning the final
 * draw step lets us preserve aspect ratio even on OEM wallpaper surfaces that
 * stretch MediaPlayer output.
 */
final class WallpaperGlRenderer {
    private static final String TAG = "WallpaperGlRenderer";
    private static final int FLOAT_SIZE_BYTES = 4;

    private static final float[] VERTICES = {
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
    };

    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n"
                    + "attribute vec2 aTextureCoord;\n"
                    + "uniform mat4 uTextureMatrix;\n"
                    + "uniform vec2 uCropScale;\n"
                    + "varying vec2 vTextureCoord;\n"
                    + "void main() {\n"
                    + "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
                    + "  vec2 cropped = (aTextureCoord - vec2(0.5)) * uCropScale + vec2(0.5);\n"
                    + "  vTextureCoord = (uTextureMatrix * vec4(cropped, 0.0, 1.0)).xy;\n"
                    + "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "uniform samplerExternalOES uTexture;\n"
                    + "varying vec2 vTextureCoord;\n"
                    + "void main() {\n"
                    + "  gl_FragColor = texture2D(uTexture, vTextureCoord);\n"
                    + "}\n";

    interface Callback {
        void onInputSurfaceReady(WallpaperGlRenderer renderer, Surface inputSurface);

        void onRendererError(WallpaperGlRenderer renderer, Throwable error);
    }

    private final Surface outputSurface;
    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread renderThread = new HandlerThread("VideoWallpaperRenderer");
    private final FloatBuffer vertexBuffer;
    private final float[] textureMatrix = new float[16];

    private Handler renderHandler;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture surfaceTexture;
    private Surface inputSurface;
    private int textureId;
    private int programId;
    private int positionLocation;
    private int textureCoordinateLocation;
    private int textureMatrixLocation;
    private int cropScaleLocation;
    private int textureSamplerLocation;
    private int outputWidth;
    private int outputHeight;
    private int videoWidth;
    private int videoHeight;
    private volatile boolean released;
    private boolean errorReported;

    WallpaperGlRenderer(
            Surface outputSurface,
            int width,
            int height,
            Callback callback
    ) {
        this.outputSurface = outputSurface;
        this.outputWidth = width;
        this.outputHeight = height;
        this.callback = callback;
        vertexBuffer = ByteBuffer
                .allocateDirect(VERTICES.length * FLOAT_SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        vertexBuffer.put(VERTICES).position(0);

        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());
        renderHandler.post(this::initialize);
    }

    void resize(int width, int height) {
        if (released) {
            return;
        }
        renderHandler.post(() -> {
            outputWidth = width;
            outputHeight = height;
            drawCurrentFrame(false);
        });
    }

    void setVideoSize(int width, int height) {
        if (released) {
            return;
        }
        renderHandler.post(() -> {
            videoWidth = width;
            videoHeight = height;
            drawCurrentFrame(false);
        });
    }

    void showPlaceholder() {
        if (released) {
            return;
        }
        renderHandler.post(this::clearSurface);
    }

    void release() {
        if (released) {
            return;
        }
        released = true;
        renderHandler.post(() -> {
            releaseGl();
            renderThread.quitSafely();
        });
    }

    private void initialize() {
        try {
            initializeEgl();
            initializeGlObjects();
            clearSurface();
            mainHandler.post(() -> {
                if (!released) {
                    callback.onInputSurfaceReady(this, inputSurface);
                }
            });
        } catch (RuntimeException error) {
            reportError(error);
        }
    }

    private void initializeEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new IllegalStateException("Unable to get EGL display");
        }

        int[] versions = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) {
            throw new IllegalStateException("Unable to initialize EGL");
        }

        int[] configAttributes = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] configCount = new int[1];
        if (!EGL14.eglChooseConfig(
                eglDisplay,
                configAttributes,
                0,
                configs,
                0,
                configs.length,
                configCount,
                0
        ) || configCount[0] == 0) {
            throw new IllegalStateException("Unable to choose EGL config");
        }

        int[] contextAttributes = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(
                eglDisplay,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                contextAttributes,
                0
        );
        checkEgl("eglCreateContext");

        int[] surfaceAttributes = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                configs[0],
                outputSurface,
                surfaceAttributes,
                0
        );
        checkEgl("eglCreateWindowSurface");
        makeCurrent();
    }

    private void initializeGlObjects() {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        programId = GLES20.glCreateProgram();
        GLES20.glAttachShader(programId, vertexShader);
        GLES20.glAttachShader(programId, fragmentShader);
        GLES20.glLinkProgram(programId);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0);
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        if (linkStatus[0] == 0) {
            String info = GLES20.glGetProgramInfoLog(programId);
            GLES20.glDeleteProgram(programId);
            programId = 0;
            throw new IllegalStateException("Could not link GL program: " + info);
        }

        positionLocation = GLES20.glGetAttribLocation(programId, "aPosition");
        textureCoordinateLocation = GLES20.glGetAttribLocation(programId, "aTextureCoord");
        textureMatrixLocation = GLES20.glGetUniformLocation(programId, "uTextureMatrix");
        cropScaleLocation = GLES20.glGetUniformLocation(programId, "uCropScale");
        textureSamplerLocation = GLES20.glGetUniformLocation(programId, "uTexture");

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
        );
        GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
        );
        GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
        );
        GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
        );

        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setOnFrameAvailableListener(
                ignored -> drawCurrentFrame(true),
                renderHandler
        );
        inputSurface = new Surface(surfaceTexture);
    }

    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] == 0) {
            String info = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Could not compile GL shader: " + info);
        }
        return shader;
    }

    private void drawCurrentFrame(boolean updateTexture) {
        if (released || surfaceTexture == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            return;
        }
        try {
            makeCurrent();
            if (updateTexture) {
                surfaceTexture.updateTexImage();
            }
            surfaceTexture.getTransformMatrix(textureMatrix);

            GLES20.glViewport(0, 0, outputWidth, outputHeight);
            GLES20.glClearColor(0.063f, 0.137f, 0.11f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(programId);

            vertexBuffer.position(0);
            GLES20.glEnableVertexAttribArray(positionLocation);
            GLES20.glVertexAttribPointer(
                    positionLocation,
                    2,
                    GLES20.GL_FLOAT,
                    false,
                    4 * FLOAT_SIZE_BYTES,
                    vertexBuffer
            );
            vertexBuffer.position(2);
            GLES20.glEnableVertexAttribArray(textureCoordinateLocation);
            GLES20.glVertexAttribPointer(
                    textureCoordinateLocation,
                    2,
                    GLES20.GL_FLOAT,
                    false,
                    4 * FLOAT_SIZE_BYTES,
                    vertexBuffer
            );

            AspectCropCalculator.CropScale cropScale = AspectCropCalculator.calculate(
                    videoWidth,
                    videoHeight,
                    outputWidth,
                    outputHeight
            );
            GLES20.glUniformMatrix4fv(textureMatrixLocation, 1, false, textureMatrix, 0);
            GLES20.glUniform2f(cropScaleLocation, cropScale.x, cropScale.y);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(textureSamplerLocation, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(positionLocation);
            GLES20.glDisableVertexAttribArray(textureCoordinateLocation);
            EGLExt.eglPresentationTimeANDROID(
                    eglDisplay,
                    eglSurface,
                    surfaceTexture.getTimestamp()
            );
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                throw new IllegalStateException("eglSwapBuffers failed");
            }
        } catch (RuntimeException error) {
            reportError(error);
        }
    }

    private void clearSurface() {
        if (released || eglSurface == EGL14.EGL_NO_SURFACE) {
            return;
        }
        try {
            makeCurrent();
            GLES20.glViewport(0, 0, outputWidth, outputHeight);
            GLES20.glClearColor(0.063f, 0.137f, 0.11f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        } catch (RuntimeException error) {
            reportError(error);
        }
    }

    private void makeCurrent() {
        if (!EGL14.eglMakeCurrent(
                eglDisplay,
                eglSurface,
                eglSurface,
                eglContext
        )) {
            throw new IllegalStateException("eglMakeCurrent failed");
        }
    }

    private void checkEgl(String operation) {
        int error = EGL14.eglGetError();
        if (error != EGL14.EGL_SUCCESS) {
            throw new IllegalStateException(operation + " failed: 0x"
                    + Integer.toHexString(error));
        }
    }

    private void reportError(Throwable error) {
        Log.e(TAG, "OpenGL wallpaper rendering failed", error);
        if (errorReported) {
            return;
        }
        errorReported = true;
        mainHandler.post(() -> callback.onRendererError(this, error));
    }

    private void releaseGl() {
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if (textureId != 0) {
            int[] textures = {textureId};
            GLES20.glDeleteTextures(1, textures, 0);
            textureId = 0;
        }
        if (programId != 0) {
            GLES20.glDeleteProgram(programId);
            programId = 0;
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
            );
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
            }
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglSurface = EGL14.EGL_NO_SURFACE;
        eglContext = EGL14.EGL_NO_CONTEXT;
    }
}
