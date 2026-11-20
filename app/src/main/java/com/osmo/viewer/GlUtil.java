package com.osmo.viewer;

import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** GL 资源小工具：编译着色器、建外部纹理、全屏三角形。 */
final class GlUtil {

    private static final String TAG = "GlUtil";

    private GlUtil() {}

    static int compile(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(s);
            GLES30.glDeleteShader(s);
            throw new RuntimeException("shader compile failed: " + log);
        }
        return s;
    }

    static int program(String vert, String frag) {
        int p = GLES30.glCreateProgram();
        int v = compile(GLES30.GL_VERTEX_SHADER, vert);
        int f = compile(GLES30.GL_FRAGMENT_SHADER, frag);
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        GLES30.glDeleteShader(v);
        GLES30.glDeleteShader(f);
        if (ok[0] == 0) {
            String log = GLES30.glGetProgramInfoLog(p);
            GLES30.glDeleteProgram(p);
            throw new RuntimeException("program link failed: " + log);
        }
        return p;
    }

    static int externalTexture() {
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0]);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        return t[0];
    }

    /** 覆盖整个视口的大三角形，省一个顶点 buffer 的索引。 */
    static FloatBuffer fullscreenTriangle() {
        float[] v = {-1f, -1f, 3f, -1f, -1f, 3f};
        FloatBuffer b = ByteBuffer.allocateDirect(v.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(v).position(0);
        return b;
    }

    static void check(String where) {
        int e = GLES30.glGetError();
        if (e != GLES30.GL_NO_ERROR) Log.w(TAG, where + ": glError 0x" + Integer.toHexString(e));
    }
}
