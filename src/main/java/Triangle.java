import static org.lwjgl.opengl.GL33.*;

final class Triangle implements AutoCloseable {
    private static final String VERTEX_SHADER = """
        #version 330 core

        layout(location = 0) in vec2 position;

        void main() {
            gl_Position = vec4(position, 0.0, 1.0);
        }
        """;

    private static final String FRAGMENT_SHADER = """
        #version 330 core

        out vec4 fragmentColor;

        void main() {
            fragmentColor = vec4(0.25, 0.65, 1.0, 1.0);
        }
        """;

    private final int program;
    private final int vao;
    private final int vbo;

    Triangle() {
        program = createProgram();

        float[] vertices = {
                0.0f,  0.55f,  // Top
                -0.55f, -0.45f, // Bottom left
                0.55f, -0.45f  // Bottom right
        };

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);

        int floatsPerVertex = 2;
        int stride = floatsPerVertex * Float.BYTES;
        glVertexAttribPointer(0, floatsPerVertex, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    void draw() {
        glUseProgram(program);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    private static int createProgram() {
        int vertexShader = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER);
        try {
            int fragmentShader = compileShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            try {
                int program = glCreateProgram();
                glAttachShader(program, vertexShader);
                glAttachShader(program, fragmentShader);
                glLinkProgram(program);

                if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
                    String log = glGetProgramInfoLog(program);
                    glDeleteProgram(program);
                    throw new IllegalStateException("Shader program link failed:\n" + log);
                }

                glDetachShader(program, vertexShader);
                glDetachShader(program, fragmentShader);
                return program;
            } finally {
                glDeleteShader(fragmentShader);
            }
        } finally {
            glDeleteShader(vertexShader);
        }
    }

    private static int compileShader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);

        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("Shader compilation failed:\n" + log);
        }

        return shader;
    }

    @Override
    public void close() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        glDeleteProgram(program);
    }
}