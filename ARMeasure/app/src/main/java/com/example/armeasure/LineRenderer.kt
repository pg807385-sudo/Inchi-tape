package com.example.armeasure

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws simple 3D points and line segments (the measurement markers and lines)
 * projected through the AR camera's view/projection matrices.
 */
class LineRenderer {

    private var program = 0
    private var positionAttrib = 0
    private var mvpUniform = 0
    private var colorUniform = 0

    fun createOnGlThread() {
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_MVP")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
    }

    fun drawSegment(
        viewMatrix: FloatArray, projMatrix: FloatArray,
        x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float
    ) {
        drawGeometry(
            floatArrayOf(x1, y1, z1, x2, y2, z2),
            viewMatrix, projMatrix, GLES20.GL_LINES,
            floatArrayOf(1f, 0.8f, 0f, 1f), 8f
        )
    }

    fun drawPoint(viewMatrix: FloatArray, projMatrix: FloatArray, x: Float, y: Float, z: Float) {
        drawGeometry(
            floatArrayOf(x, y, z),
            viewMatrix, projMatrix, GLES20.GL_POINTS,
            floatArrayOf(0.2f, 1f, 0.4f, 1f), 20f
        )
    }

    private fun drawGeometry(
        vertices: FloatArray, viewMatrix: FloatArray, projMatrix: FloatArray,
        mode: Int, color: FloatArray, lineWidth: Float
    ) {
        val vpMatrix = FloatArray(16)
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        val buffer: FloatBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertices); position(0) }

        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        buffer.position(0)
        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, vpMatrix, 0)
        GLES20.glUniform4fv(colorUniform, 1, color, 0)
        GLES20.glLineWidth(lineWidth)
        GLES20.glDrawArrays(mode, 0, vertices.size / 3)
        GLES20.glDisableVertexAttribArray(positionAttrib)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 u_MVP;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_MVP * a_Position;
                gl_PointSize = 20.0;
            }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """
    }
}
