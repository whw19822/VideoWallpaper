package com.whw.videowallpaper;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Draws one poster frame, then fully disconnects EGL before MediaCodec takes the Surface. */
final class WallpaperPosterRenderer {
    private static final String TAG = "WallpaperPoster";
    private static final int FLOAT_BYTES = 4;
    private static final float[] VERTICES = {
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f
    };
    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n"
                    + "attribute vec2 aTextureCoord;\n"
                    + "uniform vec2 uCropScale;\n"
                    + "varying vec2 vTextureCoord;\n"
                    + "void main() {\n"
                    + "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
                    + "  vTextureCoord = (aTextureCoord - vec2(0.5))"
                    + " * uCropScale + vec2(0.5);\n"
                    + "}\n";
    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n"
                    + "uniform sampler2D uTexture;\n"
                    + "varying vec2 vTextureCoord;\n"
                    + "void main() {\n"
                    + "  gl_FragColor = texture2D(uTexture, vTextureCoord);\n"
                    + "}\n";

    private WallpaperPosterRenderer() {
    }

    static boolean draw(Surface surface, Bitmap poster, int outputWidth, int outputHeight) {
        if (surface == null || !surface.isValid() || poster == null
                || outputWidth <= 0 || outputHeight <= 0) {
            return false;
        }

        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface windowSurface = EGL14.EGL_NO_SURFACE;
        int program = 0;
        int texture = 0;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) {
                throw new IllegalStateException("Unable to get EGL display");
            }
            int[] versions = new int[2];
            if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) {
                throw new IllegalStateException("Unable to initialize EGL");
            }

            EGLConfig config = chooseConfig(display);
            int[] contextAttributes = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };
            context = EGL14.eglCreateContext(
                    display,
                    config,
                    EGL14.EGL_NO_CONTEXT,
                    contextAttributes,
                    0
            );
            checkEgl("eglCreateContext");

            int[] surfaceAttributes = {EGL14.EGL_NONE};
            windowSurface = EGL14.eglCreateWindowSurface(
                    display,
                    config,
                    surface,
                    surfaceAttributes,
                    0
            );
            checkEgl("eglCreateWindowSurface");
            if (!EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) {
                throw new IllegalStateException("eglMakeCurrent failed");
            }

            int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] == 0) {
                throw new IllegalStateException(
                        "Could not link poster program: " + GLES20.glGetProgramInfoLog(program)
                );
            }

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            texture = textures[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR
            );
            GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR
            );
            GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE
            );
            GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE
            );
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, poster, 0);

            FloatBuffer vertices = ByteBuffer
                    .allocateDirect(VERTICES.length * FLOAT_BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            vertices.put(VERTICES).position(0);
            int position = GLES20.glGetAttribLocation(program, "aPosition");
            int textureCoordinate = GLES20.glGetAttribLocation(program, "aTextureCoord");
            int cropScale = GLES20.glGetUniformLocation(program, "uCropScale");
            int textureSampler = GLES20.glGetUniformLocation(program, "uTexture");

            PosterCropCalculator.Crop crop = PosterCropCalculator.calculate(
                    poster.getWidth(),
                    poster.getHeight(),
                    outputWidth,
                    outputHeight
            );
            float cropX = (crop.right - crop.left) / (float) poster.getWidth();
            float cropY = (crop.bottom - crop.top) / (float) poster.getHeight();

            GLES20.glViewport(0, 0, outputWidth, outputHeight);
            GLES20.glUseProgram(program);
            vertices.position(0);
            GLES20.glEnableVertexAttribArray(position);
            GLES20.glVertexAttribPointer(
                    position,
                    2,
                    GLES20.GL_FLOAT,
                    false,
                    4 * FLOAT_BYTES,
                    vertices
            );
            vertices.position(2);
            GLES20.glEnableVertexAttribArray(textureCoordinate);
            GLES20.glVertexAttribPointer(
                    textureCoordinate,
                    2,
                    GLES20.GL_FLOAT,
                    false,
                    4 * FLOAT_BYTES,
                    vertices
            );
            GLES20.glUniform2f(cropScale, cropX, cropY);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glUniform1i(textureSampler, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(position);
            GLES20.glDisableVertexAttribArray(textureCoordinate);
            checkGl("poster draw");
            if (!EGL14.eglSwapBuffers(display, windowSurface)) {
                throw new IllegalStateException("eglSwapBuffers failed");
            }
            GLES20.glFinish();
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not draw poster with EGL", error);
            return false;
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                if (context != EGL14.EGL_NO_CONTEXT
                        && EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) {
                    if (texture != 0) {
                        int[] textures = {texture};
                        GLES20.glDeleteTextures(1, textures, 0);
                    }
                    if (program != 0) {
                        GLES20.glDeleteProgram(program);
                    }
                    GLES20.glFinish();
                }
                EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                );
                if (windowSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, windowSurface);
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context);
                }
                EGL14.eglTerminate(display);
                EGL14.eglReleaseThread();
            }
        }
    }

    private static EGLConfig chooseConfig(EGLDisplay display) {
        int[] attributes = {
                EGL14.EGL_RED_SIZE, 5,
                EGL14.EGL_GREEN_SIZE, 6,
                EGL14.EGL_BLUE_SIZE, 5,
                EGL14.EGL_ALPHA_SIZE, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
                || count[0] == 0) {
            throw new IllegalStateException("Unable to choose an RGB565 EGL config");
        }
        return configs[0];
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String info = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Could not compile poster shader: " + info);
        }
        return shader;
    }

    private static void checkEgl(String operation) {
        int error = EGL14.eglGetError();
        if (error != EGL14.EGL_SUCCESS) {
            throw new IllegalStateException(
                    operation + " failed: 0x" + Integer.toHexString(error)
            );
        }
    }

    private static void checkGl(String operation) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            throw new IllegalStateException(
                    operation + " failed: 0x" + Integer.toHexString(error)
            );
        }
    }
}
