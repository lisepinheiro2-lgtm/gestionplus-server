import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import fr.gestionplus.server.auth.PasswordSecurityConfig;
import org.springframework.security.crypto.password.PasswordEncoder;

public final class Argon2LocalBenchmark {

    private static final int WARMUP_CHECKS = 100;
    private static final int SAMPLES_PER_CASE = 200;

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !args[0].matches("[1-9][0-9]{0,2}")) {
            throw new IllegalArgumentException("Expected a numeric fork identifier and a results directory.");
        }
        String fork = args[0];
        Path directory = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        if (Files.exists(directory.resolve("samples-fork-" + fork + ".csv"))
                || Files.exists(directory.resolve("summary-fork-" + fork + ".json"))) {
            throw new IllegalArgumentException("This fork already has recorded results.");
        }
        PasswordEncoder encoder = new PasswordSecurityConfig().passwordEncoder();
        String correctPassword = "Local-measurement-password-2026!";
        String wrongPassword = correctPassword.substring(0, correctPassword.length() - 1) + "?";
        String encodedPassword = encoder.encode(correctPassword);
        String[] encodedParts = encodedPassword.split("\\$");
        if (encodedParts.length < 4 || !encodedParts[1].startsWith("argon2")) {
            throw new IllegalStateException("The project password encoder is not an Argon2 encoder.");
        }
        String algorithm = encodedParts[1];
        String parameters = encodedParts[3];

        System.out.printf("Fork %s: warm-up of %d checks%n", fork, WARMUP_CHECKS);
        System.out.flush();

        for (int index = 0; index < WARMUP_CHECKS; index++) {
            boolean expected = index % 2 == 0;
            String candidate = expected ? correctPassword : wrongPassword;
            if (encoder.matches(candidate, encodedPassword) != expected) {
                throw new IllegalStateException("Unexpected warm-up verification result.");
            }
        }

        long[] correctSamples = new long[SAMPLES_PER_CASE];
        long[] wrongSamples = new long[SAMPLES_PER_CASE];
        List<Sample> samples = new ArrayList<>(2 * SAMPLES_PER_CASE);
        long gcCountBefore = gcCount();
        long gcTimeBefore = gcTimeMillis();
        Instant startedAt = Instant.now();

        System.out.printf("Fork %s: measuring %d checks per case%n", fork, SAMPLES_PER_CASE);
        System.out.flush();

        for (int index = 0; index < SAMPLES_PER_CASE; index++) {
            if (index % 2 == 0) {
                correctSamples[index] = measure(encoder, correctPassword, encodedPassword, true);
                samples.add(new Sample("correct", correctSamples[index]));
                wrongSamples[index] = measure(encoder, wrongPassword, encodedPassword, false);
                samples.add(new Sample("wrong", wrongSamples[index]));
            } else {
                wrongSamples[index] = measure(encoder, wrongPassword, encodedPassword, false);
                samples.add(new Sample("wrong", wrongSamples[index]));
                correctSamples[index] = measure(encoder, correctPassword, encodedPassword, true);
                samples.add(new Sample("correct", correctSamples[index]));
            }
        }

        long measuredGcCount = gcCount() - gcCountBefore;
        long measuredGcTimeMillis = gcTimeMillis() - gcTimeBefore;
        String summary = String.format(Locale.ROOT, """
                {
                  "fork": "%s",
                  "startedAtUtc": "%s",
                  "javaVersion": "%s",
                  "availableProcessors": %d,
                  "maxHeapMiB": %d,
                  "algorithm": "%s",
                  "parameters": "%s",
                  "warmupChecks": %d,
                  "samplesPerCase": %d,
                  "measuredGcCount": %d,
                  "measuredGcTimeMillis": %d,
                  "correct": %s,
                  "wrong": %s
                }
                """, fork, startedAt, System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / (1024L * 1024L), algorithm, parameters,
                WARMUP_CHECKS, SAMPLES_PER_CASE, measuredGcCount, measuredGcTimeMillis,
                summarize(correctSamples).json(), summarize(wrongSamples).json());

        StringBuilder csv = new StringBuilder("fork,sequence,case,elapsed_nanos\n");
        for (int index = 0; index < samples.size(); index++) {
            Sample sample = samples.get(index);
            csv.append(fork).append(',').append(index + 1).append(',')
                    .append(sample.kind()).append(',').append(sample.elapsedNanos()).append('\n');
        }

        Files.writeString(directory.resolve("samples-fork-" + fork + ".csv"), csv);
        Files.writeString(directory.resolve("summary-fork-" + fork + ".json"), summary);
        System.out.println(summary);
    }

    private static long measure(PasswordEncoder encoder, String candidate,
            String encodedPassword, boolean expected) {
        long started = System.nanoTime();
        boolean matched = encoder.matches(candidate, encodedPassword);
        long elapsed = System.nanoTime() - started;
        if (matched != expected) {
            throw new IllegalStateException("Unexpected measured verification result.");
        }
        return elapsed;
    }

    private static Statistics summarize(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        double median = (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2.0;
        double mean = Arrays.stream(sorted).average().orElseThrow();
        long percentile95 = sorted[(int) Math.ceil(sorted.length * 0.95) - 1];
        return new Statistics(sorted[0] / 1_000_000.0, median / 1_000_000.0,
                percentile95 / 1_000_000.0, sorted[sorted.length - 1] / 1_000_000.0,
                mean / 1_000_000.0);
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0L, bean.getCollectionCount())).sum();
    }

    private static long gcTimeMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0L, bean.getCollectionTime())).sum();
    }

    private record Sample(String kind, long elapsedNanos) {
    }

    private record Statistics(double minimumMs, double medianMs, double percentile95Ms,
            double maximumMs, double meanMs) {

        String json() {
            return String.format(Locale.ROOT,
                    "{\"minMs\": %.6f, \"medianMs\": %.6f, \"p95Ms\": %.6f, \"maxMs\": %.6f, \"meanMs\": %.6f}",
                    minimumMs, medianMs, percentile95Ms, maximumMs, meanMs);
        }
    }
}

