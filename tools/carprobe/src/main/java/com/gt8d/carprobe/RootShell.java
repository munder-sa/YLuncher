package com.gt8d.carprobe;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Call from the single command executor, never from the UI thread. */
public final class RootShell {
    private static final Pattern ROOT_ID = Pattern.compile("(?m)^uid=0(?:\\(|\\s|$)");
    private static final Pattern PROCESS_ID = Pattern.compile("(?m)^__CARPROBE_PID__=([1-9][0-9]*)\\r?\\n");
    private static Route cachedRoute;

    private enum Route {
        SDSU_NO("sdsu_no"), SU("su");

        final String label;

        Route(String label) {
            this.label = label;
        }

        ProcessBuilder process(String command) {
            if (this == SDSU_NO) {
                return new ProcessBuilder("/system/xbin/sdsu_no", "0", "sh", "-c", command);
            }
            return new ProcessBuilder("su", "-c", command);
        }
    }

    public static final class Result {
        public final int exitCode;
        public final String stdout;
        public final boolean ok;
        public final String via;

        private Result(int exitCode, String stdout, String via) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.ok = exitCode == 0;
            this.via = via;
        }
    }

    private RootShell() {}

    public static synchronized Result run(String command, int timeoutMs) {
        if (timeoutMs <= 0) {
            return new Result(-1, "timeoutMs must be positive", "none");
        }
        if (Thread.currentThread().isInterrupted()) {
            return new Result(-1, "Interrupted", "none");
        }
        if (cachedRoute == null) {
            StringBuilder failures = new StringBuilder("no root");
            for (Route route : Route.values()) {
                Result probe = execute(route, "exec id", Math.min(timeoutMs, 5000));
                if (probe.ok && ROOT_ID.matcher(probe.stdout).find()) {
                    cachedRoute = route;
                    if ("id".equals(command)) {
                        return probe;
                    }
                    break;
                }
                failures.append("\n").append(route.label).append(": exit=")
                        .append(probe.exitCode).append(" ").append(probe.stdout.trim());
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
            if (cachedRoute == null) {
                return new Result(-1, failures.toString(), "none");
            }
        }
        // Never retry a failed operation through another route: it may have had effects.
        return execute(cachedRoute, command, timeoutMs);
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static Result execute(Route route, String command, int timeoutMs) {
        Process process = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            // The root shell identifies itself before exec replaces it with the command.
            process = route.process("printf '__CARPROBE_PID__=%s\\n' \"$$\"; " + command)
                    .redirectErrorStream(true).start();
            process.getOutputStream().close();
            InputStream stream = process.getInputStream();
            byte[] buffer = new byte[8192];
            boolean exited = false;
            while (true) {
                // Read only available bytes so a silent process cannot block the timeout.
                int available;
                while ((available = stream.available()) > 0 && System.nanoTime() < deadline) {
                    int count = stream.read(buffer, 0, Math.min(available, buffer.length));
                    if (count < 0) {
                        break;
                    }
                    output.write(buffer, 0, count);
                }
                if (exited && stream.available() == 0) {
                    return new Result(process.exitValue(), utf8(output), route.label);
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    String termination = terminate(route, process, output);
                    return new Result(124, utf8(output) + "\nTimed out after " + timeoutMs
                            + " ms" + termination, route.label);
                }
                exited = process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(20)),
                        TimeUnit.NANOSECONDS);
            }
        } catch (IOException | SecurityException e) {
            String termination = process == null ? "" : terminate(route, process, output);
            return new Result(-1, utf8(output) + "\n" + e + termination, route.label);
        } catch (InterruptedException e) {
            String termination = process == null ? "" : terminate(route, process, output);
            Thread.currentThread().interrupt();
            return new Result(-1, utf8(output) + "\nInterrupted" + termination, route.label);
        } finally {
            if (process != null) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                close(process.getOutputStream());
                close(process.getInputStream());
                close(process.getErrorStream());
            }
        }
    }

    private static String utf8(ByteArrayOutputStream output) {
        return PROCESS_ID.matcher(new String(output.toByteArray(), StandardCharsets.UTF_8))
                .replaceFirst("");
    }

    private static String terminate(Route route, Process process, ByteArrayOutputStream output) {
        if (!process.isAlive()) {
            return "";
        }
        Process killer = null;
        String failure = "";
        try {
            Matcher pid = PROCESS_ID.matcher(new String(output.toByteArray(), StandardCharsets.UTF_8));
            if (pid.find()) {
                // A setuid process may reject a signal from the app UID. Use the same root route.
                killer = route.process("kill -KILL " + pid.group(1)).redirectErrorStream(true).start();
                killer.getOutputStream().close();
                if (!killer.waitFor(2, TimeUnit.SECONDS)) {
                    failure = "\nRoot termination timed out";
                } else if (killer.exitValue() != 0) {
                    failure = "\nRoot termination failed: exit=" + killer.exitValue();
                }
            }
        } catch (IOException | SecurityException e) {
            failure = "\nRoot termination failed: " + e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = "\nRoot termination interrupted";
        } finally {
            if (killer != null) {
                if (killer.isAlive()) {
                    killer.destroyForcibly();
                }
                close(killer.getOutputStream());
                close(killer.getInputStream());
                close(killer.getErrorStream());
            }
            process.destroyForcibly();
        }
        return failure;
    }

    private static void close(Closeable stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Cleanup must preserve the original failure/timeout in Result.
        }
    }
}
