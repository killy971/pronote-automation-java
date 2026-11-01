package com.pronote;

import com.pronote.cli.CliOptions;
import com.pronote.config.AppConfig;
import com.pronote.config.ConfigLoader;
import com.pronote.notification.NotificationService;
import com.pronote.pipeline.DiffRunner;
import com.pronote.pipeline.FetchRunner;
import com.pronote.pipeline.FetchSource;
import com.pronote.pipeline.ValidateRunner;
import com.pronote.pipeline.ViewsRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Entry point: parses the CLI, loads the configuration, and dispatches to the runner of the
 * selected {@code --mode} (see {@code com.pronote.pipeline}).
 *
 * <p>Exits 0 on success, 1 on any error.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            run(args);
            System.exit(0);
        } catch (Exception e) {
            log.error("Job failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /** The CLI pipeline without {@code System.exit} — package-private for the end-to-end tests. */
    static void run(String[] args) {
        CliOptions opts = CliOptions.parse(args);
        // Only fetch talks to Pronote; the offline modes run without credentials.
        AppConfig config = loadConfig(opts, "fetch".equals(opts.mode()));
        Path dataDir = Path.of(config.getData().getDirectory());
        boolean dryRun = opts.dryRun();
        if (dryRun) log.info("--dry-run active: notifications will be previewed in the log, not sent.");

        switch (opts.mode()) {
            case "fetch"    -> new FetchRunner(config, dataDir, dryRun).run();
            case "views"    -> new ViewsRunner(config, dataDir).run();
            case "diff"     -> new DiffRunner(config, dataDir, dryRun).run();
            case "validate" -> new ValidateRunner(config, dataDir).run();
            default -> throw new IllegalArgumentException(
                    "Unknown --mode '" + opts.mode() + "'. Valid modes: fetch, views, diff, validate");
        }
    }

    /**
     * Test entry point for {@code --mode fetch}: same CLI and config handling as {@link #run},
     * but the post-login pipeline reads from {@code source} instead of a live Pronote session.
     * Lockout and session handling are skipped — they precede the source.
     */
    static void runFetchWith(String[] args, FetchSource source, NotificationService errorNotifier) {
        CliOptions opts = CliOptions.parse(args);
        AppConfig config = loadConfig(opts, true);
        new FetchRunner(config, Path.of(config.getData().getDirectory()), opts.dryRun())
                .runWith(source, errorNotifier);
    }

    private static AppConfig loadConfig(CliOptions opts, boolean requireCredentials) {
        AppConfig config = ConfigLoader.load(opts.configPath(), requireCredentials);
        opts.featureOverride().ifPresent(enabled -> {
            CliOptions.applyFeatureOverride(config.getFeatures(), enabled);
            log.info("Feature override applied via --features flag: {}", enabled);
        });
        return config;
    }
}
