package ai.bench.app.phone;

// The Shizuku user service: runs as the shell user, makes the hidden screen and works on it.
interface IShell {
    // Shizuku's own: called to end the service.
    void destroy() = 16777114;

    // A shell command's output (stdout and stderr), within 20 seconds.
    String exec(String command) = 1;

    // Makes the hidden screen (or keeps the one there is) and returns its display id.
    int open(int width, int height, int dpi) = 2;

    // The hidden screen now, as a JPEG; empty when there is none.
    byte[] frame(int quality) = 3;

    // Removes the hidden screen and what is on it.
    void close() = 4;
}
